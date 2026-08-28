#include "lsfg_render_loop.hpp"
#include "android_vk_session.hpp"
#include "android_vk_probe.hpp"
#include "ahb_image_bridge.hpp"
#include "android_shader_loader.hpp"
#ifdef LSFG_HAVE_NCNN
#include "NcnnInterpolator.hpp"
#include "IfrnetInterpolator.hpp"
#endif

#include "lsfg_3_1.hpp"
#include "lsfg_3_1p.hpp"

#include <volk.h>

#include <android/log.h>
#include <android/native_window.h>
#include <android/hardware_buffer.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstring>
#include <cmath>
#include <deque>
#include <functional>
#include <mutex>
#include <string>
#include <thread>
#include <vector>
#include <unordered_map>
#include <sys/resource.h>
#include <sys/types.h>
#include <unistd.h>

// android/thread_defs.h is a platform-private header not shipped in the NDK
// sysroot, so it never resolves in app builds. Define the constants we need
// (match AOSP's system/core/libutils/include/utils/ThreadDefs.h).
#ifndef ANDROID_PRIORITY_URGENT_DISPLAY
#define ANDROID_PRIORITY_URGENT_DISPLAY (-8)
#endif
#ifndef ANDROID_PRIORITY_URGENT_AUDIO
#define ANDROID_PRIORITY_URGENT_AUDIO (-19)
#endif

#include "crash_reporter.hpp"
#include "cpu_core_policy.hpp"
#ifdef LSFG_HAVE_NCNN
#include "ncnn_cpu_policy.hpp"
#endif

#define LOG_TAG "lsfg-vk-loop"
#define LOGE(...) ::lsfg_android::ring_logf(LOG_TAG, ANDROID_LOG_ERROR, __VA_ARGS__)
#define LOGW(...) ::lsfg_android::ring_logf(LOG_TAG, ANDROID_LOG_WARN,  __VA_ARGS__)
#define LOGI(...) ::lsfg_android::ring_logf(LOG_TAG, ANDROID_LOG_INFO,  __VA_ARGS__)

namespace lsfg_android {

namespace {

struct State {
    using Clock = std::chrono::steady_clock;

    // Guards ONLY g.pendingFrames / g.stopRequested / g.pendingCv (the
    // captured-frame queue). Deliberately does NOT guard swapchain/present
    // state — see presentMu below for why that's a separate lock.
    std::mutex mu;
    // Guards g.outWindow, g.swap.*, and everything blitOutputToWindow() /
    // setOutputSurface() / createSwapchain() / destroySwapchain() touch.
    // Held internally by blitOutputToWindow() around its whole
    // acquire/blit/present sequence (see its definition) — callers don't
    // need to take it themselves. Split out from g.mu on purpose:
    // createSwapchain() can hit a vkDeviceWaitIdle() (via destroySwapchain())
    // that runs for a few ms, and that must never be on the same lock as
    // pushFrame()'s queue push, or a swapchain rebuild would stall capture
    // ingestion the same way the old inline waitIdle() stalled it.
    // Also what actually makes it safe for genWaitThread (the backgrounded
    // CPU-waitIdle-fallback job — see cpuFallbackGenInFlight below) to post a
    // generated frame while the main worker posts the next real frame:
    // Vulkan requires external synchronization for concurrent submissions/
    // presents on the same queue, and this lock is what provides it.
    std::mutex presentMu;
    // Set for the lifetime of a backgrounded CPU-waitIdle-fallback generation
    // job (see workerThread()). While true, a new framegen present must not
    // be started: framegen's output AHBs are fixed buffers reused in place,
    // so issuing presentContext() again before the previous job's waitIdle()
    // + blit has consumed those buffers would race the GPU write against our
    // read. Checked as part of generationAllowed; cleared by the background
    // job right before it exits.
    std::atomic<bool> cpuFallbackGenInFlight{false};
    bool initialized = false;
    bool performanceMode = false;
    bool framegenInitOk = false;  // tracks whether LSFG_3_1::initialize succeeded
    bool framegenFp16 = false;    // load IDs 304..351 (FP16 SPIR-V) instead of 353..400 (FP32 SPIR-V)
    bool hdr = false;
    float flowScale = 1.0f;
    int32_t framegenCtxId = -1;
    int multiplier = 2;          // generationCount

    VulkanSession vk{};

    AhbImage inSlot[2]{};        // ping-pong inputs
    uint64_t framesCopied = 0;   // total inputs we've copied into a slot
    uint64_t presentsDone = 0;   // mirrors framegen's internal frameIdx
    // CPU-side logical ordering for REAL/GEN mapping. REAL A = 0,
    // GEN[0..N-1] = 1..N-1, REAL B = N.
    uint64_t nextCaptureSequence = 1;
    uint64_t lastRealSequence = 0;
    int64_t lastRealCaptureTimestampNs = 0;
    // Physical inSlot[] index that received the MOST RECENT capture. Used to
    // detect a bypassed previous capture (see the shift in the worker loop
    // just before processRealFrameIntoSlot): if the next capture's target
    // slot equals this one, presentsDone did not advance in between, i.e.
    // framegen's frameIdx parity never flipped and the *other* slot is about
    // to go stale relative to the new capture. -1 = none yet.
    int lastCaptureSlot = -1;

    // Vulkan swapchain state. When live, blitOutputToWindow takes the
    // GPU-only fast path: vkAcquireNextImageKHR → vkCmdBlitImage from the
    // framegen output AHB → vkQueuePresentKHR. This eliminates the CPU
    // memcpy (AHardwareBuffer_lock(CPU_READ) + ANativeWindow_lock +
    // per-row memcpy) that was the single biggest cost at multiplier ≥ 2.
    //
    // The WSI path is disabled when the extension chain or surface support is
    // unavailable. The presentation pipeline stays GPU-only; a failed WSI path
    // drops the frame rather than copying pixels through the CPU.
    struct SwapchainState {
        VkSurfaceKHR surface = VK_NULL_HANDLE;
        VkSwapchainKHR swapchain = VK_NULL_HANDLE;
        VkExtent2D extent{};
        VkFormat format = VK_FORMAT_UNDEFINED;
        std::vector<VkImage> images;
        // One acquire semaphore per slot, cycled round-robin. Vulkan spec
        // forbids reusing a semaphore until the corresponding acquire has
        // completed; N+1 is the safe lower bound.
        std::vector<VkSemaphore> acquireSems;
        // One render-done semaphore per swapchain image (vkQueuePresentKHR
        // waits on the image's semaphore before presenting).
        std::vector<VkSemaphore> renderSems;
        uint32_t acquireCursor = 0;
        // Track whether each swapchain image has already been presented. After
        // the first present, the image must be transitioned from PRESENT_SRC_KHR.
        std::vector<uint8_t> imageWasPresented;
        bool outOfDate = false;
        VkPresentModeKHR presentMode = VK_PRESENT_MODE_MAILBOX_KHR;
        // Set when an attempt to build the swapchain failed (e.g. the compute
        // queue family cannot present, or the surface rejects TRANSFER_DST
        // usage). Once set we stop retrying for the rest of the session and
        // use the CPU blit path exclusively. Cleared by destroySwapchain()
        // so a surface re-attach gets a fresh attempt.
        bool disabledForSession = false;
    } swap;

    // Cross-device framegen completion semaphores. Each semaphore is created
    // exportable on the host/session VkDevice, its opaque FD is imported by
    // framegen, and framegen signals it after the corresponding generated
    // frame is complete. The host queue waits on it before reading that AHB.
    // Tickets are retired non-blockingly from the blit fence; there is no
    // CPU waitIdle() in the framegen hot path.
    struct FramegenCompletionTicket {
        VkSemaphore semaphore = VK_NULL_HANDLE;
        VkFence fence = VK_NULL_HANDLE;
        // Whether `fence` is privately owned by this ticket and must be
        // vkDestroyFence'd when reaped. False for tickets created from the
        // successful-blit path in blitOutputToSwapchain(), where `fence` is
        // borrowed from the shared command ring (g.vk.ringFences) and is
        // reused/owned there — destroying it here would be a use-after-free
        // for the ring. True for tickets from drainFramegenCompletionSemaphore(),
        // which vkCreateFence's a fence exclusively for that one ticket.
        bool ownsFence = false;
        // --- Real GPU-side generation cost sampling ---------------------
        // Non-zero only for the ticket covering the LAST output of a
        // cross-device-semaphore generation batch (see the blit loop in
        // workerThread). presentContext() itself returns almost instantly —
        // it only submits work to framegen's own queue — so timing it says
        // nothing about actual GPU load. This ticket's fence, by contrast,
        // only signals once the blit that waited on the generation-done
        // semaphore has actually retired on the GPU, i.e. once generation
        // (all multiplier-1 outputs) is truly finished. submittedAtNs is the
        // wall-clock (steady_clock, ns since epoch) at which that
        // presentContext() batch was submitted; reapFramegenCompletionTickets()
        // turns (fence-signal time - submittedAtNs) into the real generation
        // cost sample admission control needs.
        int64_t submittedAtNs = 0;
        uint64_t pixelCountForCost = 0;
    };
    std::vector<FramegenCompletionTicket> framegenCompletionTickets;
    // Sticky runtime guard: if the driver advertises an external semaphore
    // capability but actual FD creation fails, stop retrying on every frame.
    // This keeps the render loop from hammering vkCreateSemaphore/vkGetSemaphoreFdKHR.
    bool crossDeviceSyncDisabled = false;

    // ANativeWindow width/height the swapchain was built for. When the
    // overlay reshapes (rare, mostly on orientation change), we recreate.
    uint32_t swapWinW = 0;
    uint32_t swapWinH = 0;
    // AI backend (ncnn RIFE flownet) state. `aiRequested` mirrors
    // cfg.aiBackend from the most recent initRenderLoop call; `aiLoaded`
    // reflects whether NcnnInterpolator::load() actually succeeded. Kept
    // outside the #ifdef so runAi's condition below compiles identically
    // whether or not this .so was built with ncnn — it just stays false.
    bool aiRequested = false;
    bool aiLoaded = false;
    // Mirrors cfg.aiEngine from the most recent initRenderLoop call (0 =
    // RIFE/NcnnInterpolator, 1 = IFRNet/IfrnetInterpolator). Only one of
    // `ai`/`aiIfrnet` below is ever non-null at a time — runAiInterpolate()
    // branches on this to know which one. Kept outside the #ifdef for the
    // same reason as aiRequested/aiLoaded.
    int aiEngine = 0;
#ifdef LSFG_HAVE_NCNN
    NcnnInterpolator *ai = nullptr;
    IfrnetInterpolator *aiIfrnet = nullptr;
#endif
    std::atomic<bool> bypass{false}; // skip framegen, blit raw input
    // Auto-bypass triggered when framegen returns VK_ERROR_DEVICE_LOST during
    // presentContext. Distinct from the user-controlled `bypass` so the user
    // toggle isn't silently flipped by a recoverable driver event. Cleared on
    // every initRenderLoop / context recreation — if the next session
    // succeeds, framegen runs again. Stuck-on means the next presentContext
    // would error too, so passthrough is the right behaviour anyway.
    std::atomic<bool> framegenAutoDisabled{false};
    std::atomic<uint64_t> cacheHits{0};
    std::atomic<uint64_t> cacheMisses{0};
    std::vector<AhbImage> outputs; // multiplier-many outputs
    // Reusable AI host staging buffers. These are allocated once per resolution
    // instead of creating/destroying several large vectors every frame. The
    // actual model math remains Vulkan-only; this buffer is only the legacy
    // RGBA8 bridge required by the bundled ncnn build.
    std::vector<uint8_t> aiInputAStaging;
    std::vector<uint8_t> aiInputCStaging;
    std::vector<std::vector<uint8_t>> aiOutputStaging;
    std::vector<uint8_t*> aiOutputPtrs;

    // AHB → VkImage import cache. MediaProjection rotates a small pool (2-4)
    // of AHBs; re-importing on every frame wastes vkCreateImage +
    // vkAllocateMemory. The cache holds its own AHardwareBuffer_acquire ref
    // so entries outlive the per-frame release. Evicted FIFO beyond kAhbCacheMax.
    // Capped at 4 (not 8): MediaProjection rotates a pool of 2-4 buffers, so a
    // cache of 8 pins up to 4 stale/duplicate AHBs' worth of GPU-shared memory
    // (each ~10 MB at 1080x2408 RGBA8) with zero cache-hit benefit — pure waste
    // that pushes the process toward the low-memory killer on constrained
    // devices when a second heavy app (e.g. video playback) shares memory.
    // Must be fully cleared (destroyAhbImage + AHardwareBuffer_release for each
    // entry) before vkDestroyDevice in the cleanup path.
    static constexpr size_t kAhbCacheMax = 4;
    std::unordered_map<AHardwareBuffer*, AhbImage> ahbImportCache;

    // Output surface for the final blit. Owned (acquired from JNI).
    ANativeWindow *outWindow = nullptr;
    uint32_t outWidth = 0;
    uint32_t outHeight = 0;
    // Once we have produced a CPU buffer on this ANativeWindow (via
    // ANativeWindow_lock / setBuffersGeometry), the BufferQueue is bound to a
    // CPU producer. Mali (Bifrost/Valhall) and many other drivers will then
    // return VK_ERROR_NATIVE_WINDOW_IN_USE_KHR (-1000000001) for any subsequent
    // vkCreateAndroidSurfaceKHR on the same native window — the producer slot
    // can't be retargeted live. We track this taint per-attached window and
    // skip the WSI swapchain attempt entirely, avoiding the spammy retries seen
    // on Mali-G57 and the cascade where a failed surface creation correlates
    // with a DEVICE_LOST on framegen's compute queue. Reset on each
    // setOutputSurface(win) call, since a freshly-acquired ANativeWindow has
    // no producer yet.
    bool windowCpuProducerLocked = false;
    // Feedback-loop gate: suppresses blits when framegen output luma is very
    // dark, indicating setSkipScreenshot failed and MediaProjection is capturing
    // the overlay instead of the game.  Keeping the overlay transparent lets the
    // next capture see the real game and breaks the loop.
    bool     lumaGateOpen      = false;
    uint32_t lumaGateDarkCount = 0;
    int64_t  lumaGateStartNs   = 0; // steady_clock ns at first suppressed dark frame



    // Bounded realtime queue. A capture queue must never become an unbounded
    // FIFO: if the worker falls behind, old captures only add latency and pin
    // AHardwareBuffer references. The worker owns the frame it has popped;
    // this queue owns only frames that have not started processing.
    // pushFrame() evicts stale queued captures before admitting the newest one.
    // This keeps at most one waiting capture and prevents latency/memory growth.
    struct PendingFrame {
        AHardwareBuffer *ahb = nullptr;
        Clock::time_point queuedAt{};
        int64_t captureTimestampNs = 0;
        // Decided at admission time (pushFrame) by the input FPS cap: when
        // true, this frame skips LSFG/AI generation and is blitted straight
        // through, same as the manual bypass toggle. The frame itself is
        // never dropped — this only decides whether it goes through
        // generation, so the overlay still shows every input frame.
        bool forceBypass = false;
        uint64_t captureEpoch = 0;
        uint64_t sequence = 0; // CPU-assigned capture order; never reordered
    };
    std::deque<PendingFrame> pendingFrames;
    // Monotonically increasing capture epoch. Framegen jobs are invalidated when newer real frames arrive.
    std::atomic<uint64_t> captureEpoch{0};
    // Forces the next processed REAL frame to become a fresh pairing anchor.
    // Set on manual/dynamic bypass so no frame from before the discontinuity can
    // ever be used as an interpolation predecessor after FrameGen resumes.
    std::atomic<bool> pairingResetPending{false};
    // Submitted FrameGen may still be reading both input images. Never overwrite
    // either slot until its completion ticket (or CPU fallback wait) retires.
    std::atomic<bool> framegenInputsInFlight{false};
    std::condition_variable pendingCv; // signaled when a frame is queued (consumer wakes)
    bool stopRequested = false;

    std::thread worker;
    // Persistent thread that runs CPU-waitIdle-fallback generation jobs
    // (see genWorkerThread()). Spawned once in initRenderLoop() alongside
    // `worker`, parked on genCv the rest of the time — NOT spawned per job,
    // to avoid paying Android thread-creation cost on every fallback frame.
    // shutdownRenderLoop() signals genStopRequested and joins this before
    // tearing down Vulkan/framegen state.
    std::thread genWaitThread;
    std::mutex genMu;
    std::condition_variable genCv;
    bool genJobPending = false;
    // If false, the fallback worker only drains waitIdle; its generated
    // outputs are discarded so REAL never waits behind the fallback.
    bool genJobPublish = true;
    bool genStopRequested = false;
    // Job parameters, written by workerThread() under genMu before setting
    // genJobPending, read by genWorkerThread() after waking.
    bool genJobPerfMode = false;
    Clock::time_point genJobStartedAt{};
    // Epoch of the REAL frame that owns the background generation job.
    // Any newer capture or bypass transition invalidates the job output.
    uint64_t genJobEpoch = 0;
    // Completion notification for CPU-waitIdle fallback. Generated frames for
    // a pair must be published before that pair's current REAL frame.
    std::condition_variable genDoneCv;
    uint64_t genCompletedEpoch = 0;
    // The render worker pins itself to the little (efficiency) cluster only,
    // permanently — see workerThread(). The FPS-lock contention controller
    // below is the only thing that widens this to all online cores, and only
    // while actively trying to slow a source app down toward a target fps.
    CpuCorePolicy cpuPolicy{};
    std::atomic<uint64_t> generatedFrames{0};
    // Counts every successful post (WSI present) to the overlay.
    // This is the ground-truth "frames on screen" metric — includes real
    // captures AND LSFG-generated frames. Used by the HUD total-fps counter
    // instead of the old `capturedFps + genFps` which double-counted.
    std::atomic<uint64_t> postedFrames{0};
    // Counts every capture frame that arrives (regardless of content).
    // Used as the HUD's "real fps" / unique-capture metric. Previously this
    // used a per-pixel luma hash to detect duplicate frames from MediaProjection,
    // but the CPU cost of reading back AHB pixels was not worth the metric
    // accuracy gain. We now count every arriving frame as unique — the number
    // is the true capture rate from the OS capture path, which closely tracks
    // the target app's render rate in practice.
    std::atomic<uint64_t> uniqueCaptures{0};

    // Ring buffer of recent post timestamps (ns from CLOCK_MONOTONIC, via
    // steady_clock). Consumed by the HUD frame-pacing graph to show real
    // frame-to-frame intervals instead of rolling counts.
    static constexpr size_t kPostRingSize = 128;
    std::atomic<uint64_t> postRingTimestamps[kPostRingSize]{};
    std::atomic<uint64_t> postRingHead{0};

    // Same idea as postRingTimestamps, but for capture arrivals (pushFrame).
    // Lets getFpsSnapshot derive "real fps" from actual elapsed time between
    // captures instead of a counter+delta over a fixed polling window.
    std::atomic<uint64_t> captureRingTimestamps[kPostRingSize]{};
    std::atomic<uint64_t> captureRingHead{0};

    std::atomic<uint32_t> pushLogCount{0};
    std::atomic<uint32_t> blitLogCount{0};

    // Snapshot of the last completed kProfileWindow rolling window. Updated
    // atomically by the worker thread when a window closes (see workerThread,
    // ProfileAccum). Layout: [copyNs, presentNs, waitIdleNs, blitNs, totalNs,
    // samples]. Each value is the SUM over the window — divide by samples to
    // get the per-frame average. samples == 0 means no window has completed
    // since session start.
    std::atomic<int64_t> profileSnapshotCopyNs{0};
    std::atomic<int64_t> profileSnapshotPresentNs{0};
    std::atomic<int64_t> profileSnapshotWaitIdleNs{0};
    std::atomic<int64_t> profileSnapshotBlitNs{0};
    std::atomic<int64_t> profileSnapshotTotalNs{0};
    std::atomic<int64_t> profileSnapshotQueueNs{0};
    std::atomic<int64_t> profileSnapshotLatencyNs{0};
    std::atomic<int64_t> profileSnapshotBlitCount{0};
    std::atomic<int64_t> profileSnapshotSamples{0};
    std::atomic<bool> shizukuTimingEnabled{false};
    std::atomic<int64_t> shizukuSampleTimestampNs{0};
    std::atomic<int64_t> shizukuFrameTimeNs{0};
    std::atomic<int64_t> shizukuPacingJitterNs{0};

    // --- Dynamic framegen bypass ------------------------------------------------
    // The capture queue is never trimmed and real frames are never dropped.
    // Generation is treated as optional work: when measured generation cost plus
    // queued work would push latency beyond the current capture cadence, the
    // current real frame is passed through and generation is skipped for that
    // frame. As the queue drains, generation automatically resumes.
    std::atomic<int64_t> emaGenerationNs{0};
    std::atomic<int64_t> emaCaptureIntervalNs{0};
    std::atomic<int64_t> lastProcessedCaptureTimestampNs{0};
    std::atomic<uint64_t> dynamicBypassCount{0};

    // --- Resolution-aware generation cost model ---------------------------
    // emaGenerationNs above is a flat, resolution-blind absolute-time EMA.
    // Generation cost scales with pixel count (shader/AI compute is
    // ~O(pixels)), so after a resolution change (dynamic output resolution,
    // physical resolution override, an in-app resolution swap) the raw EMA
    // keeps predicting the OLD resolution's cost for ~8 samples: either
    // bypassing needlessly right after a downscale, or letting generation
    // through right after an upscale until real-frame latency has already
    // blown past budget. Track cost normalized per-megapixel instead, and
    // re-derive the absolute estimate from each decision's ACTUAL pixel
    // count (see projectGenerationCostNs), so a resolution change is
    // reflected on the very next frame instead of waiting for the EMA to
    // re-converge.
    std::atomic<int64_t> emaGenNsPerMegapixel{0};
    // Pixel count of the frame a background CPU-fallback generation job is
    // processing. Written under genMu alongside genJobStartedAt/genJobEpoch
    // when the job is dispatched, read by genWorkerThread() once it wakes —
    // mirrors how those two fields are already threaded through.
    uint64_t genJobPixelCount = 0;

    // --- Adaptive latency bypass (DISABLED) ---
    // This used to watch capture->display latency and automatically engage
    // bypass — raw blit, no framegen/AI — once it climbed above
    // autoLatencyBypassTriggerMs, on the theory that this stops frame
    // generation from becoming a net latency cost of its own under thermal
    // throttling or GPU contention. In practice it caused frame generation
    // to keep turning itself off during normal play, so the engage/release
    // logic in updateAutoLatencyBypass() has been gutted to a no-op and
    // autoLatencyBypassActive can no longer become true. The fields below
    // are kept only so the (now-inert) setter/getter stubs further down
    // still have something to read/write.
    std::atomic<bool> autoLatencyBypassEnabled{false};
    std::atomic<bool> autoLatencyBypassActive{false};
    std::atomic<double> autoLatencyBypassTriggerMs{60.0};
    std::atomic<double> autoLatencyBypassRecoverMs{42.0};
    // Lightweight rolling-average probe feeding the engage/release decision
    // above. Deliberately separate from `prof`/ProfileAccum (the periodic
    // diagnostic-log accumulator further down) because that one only
    // advances while generation actually runs — once bypass is engaged it
    // would freeze forever and latency could never be seen recovering. This
    // probe is fed from every posted blit, generated or raw, so recovery is
    // always observable regardless of which path is currently active.
    std::atomic<int64_t> latencyProbeSumNs{0};
    std::atomic<int32_t> latencyProbeCount{0};
    // Guards against rapid ENGAGE/RELEASE thrash when the device sits right
    // at the capacity boundary: every window's decision can independently
    // cross trigger/recover even with the hysteresis gap, because engaging
    // drops the load enough to immediately satisfy "recover" next window,
    // which re-adds the load and immediately re-crosses "trigger" the window
    // after that. Observed in the field as a state flip roughly every window
    // (sub-second) instead of a stable settle. 0 means "no toggle yet".
    std::atomic<int64_t> autoLatencyBypassLastToggleNs{0};

};

State g{};

// Forward declaration: reapFramegenCompletionTickets() (below) needs to feed
// real GPU-side completion timing back into the admission-control cost
// model; the definition lives further down alongside the rest of that model.
void recordGenerationCostSample(int64_t generationNs, uint64_t pixelCount);

struct ShizukuTimingSample {
    bool enabled = false;
    int64_t timestampNs = 0;
    int64_t frameTimeNs = 0;
    int64_t pacingJitterNs = 0;
};

ShizukuTimingSample loadShizukuTimingSample() {
    return {
        .enabled = g.shizukuTimingEnabled.load(std::memory_order_relaxed),
        .timestampNs = g.shizukuSampleTimestampNs.load(std::memory_order_relaxed),
        .frameTimeNs = g.shizukuFrameTimeNs.load(std::memory_order_relaxed),
        .pacingJitterNs = g.shizukuPacingJitterNs.load(std::memory_order_relaxed),
    };
}

// Apply pacing tunables to `g` with sane clamps. Zero/negative values
// fall back to defaults so partial updates from JNI can't accidentally
// disable the pacer.
void handleFramegenException(const char *callSite, const std::exception &e) {
    const char *what = e.what() != nullptr ? e.what() : "(null)";
    LOGE("%s threw: %s", callSite, what);
    const bool isDeviceLost = std::strstr(what, "error -4") != nullptr ||
                              std::strstr(what, "DEVICE_LOST")  != nullptr;
    if (isDeviceLost && !g.framegenAutoDisabled.load(std::memory_order_relaxed)) {
        LOGE("%s: VK_ERROR_DEVICE_LOST — auto-disabling framegen for this session (passthrough until next context reinit)",
             callSite);
        g.framegenAutoDisabled.store(true, std::memory_order_relaxed);
    }
}

float clamp01(float value) {
    return value < 0.0f ? 0.0f : (value > 1.0f ? 1.0f : value);
}

// Presentation is intentionally uncapped at the application level. Vulkan WSI
// present mode is the only presentation pacing mechanism.
// Record a successful post (WSI present) for HUD metrics.
// Increments postedFrames and pushes the current steady-clock timestamp into
// the ring buffer so the pacing graph can compute real inter-frame intervals.
void recordOverlayPost() {
    g.postedFrames.fetch_add(1, std::memory_order_relaxed);
    const uint64_t nowNs = static_cast<uint64_t>(
        std::chrono::duration_cast<std::chrono::nanoseconds>(
            State::Clock::now().time_since_epoch()).count());
    const uint64_t slot = g.postRingHead.fetch_add(1, std::memory_order_relaxed)
                          % State::kPostRingSize;
    g.postRingTimestamps[slot].store(nowNs, std::memory_order_relaxed);
}

// ---- Shared Vulkan one-shot helpers -------------------------------------------
//
// Every AHB-side image transition in this file (copy, GPU blit, swapchain
// blit, initial layout transition) builds VkImageMemoryBarrier literals that
// differ only in image/access/layout/family — factored here so each call site
// is one line instead of a ~9-line struct.
VkImageMemoryBarrier makeImageBarrier(VkImage image, VkAccessFlags srcAccess, VkAccessFlags dstAccess,
                                       VkImageLayout oldLayout, VkImageLayout newLayout,
                                       uint32_t srcFamily, uint32_t dstFamily) {
    return VkImageMemoryBarrier{
        .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
        .srcAccessMask = srcAccess,
        .dstAccessMask = dstAccess,
        .oldLayout = oldLayout,
        .newLayout = newLayout,
        .srcQueueFamilyIndex = srcFamily,
        .dstQueueFamilyIndex = dstFamily,
        .image = image,
        .subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1},
    };
}

// Allocate a one-shot primary command buffer from the session pool, run
// `record` to fill it, submit on the compute queue and block until the GPU
// has finished. Uses a per-submission VkFence (not vkQueueWaitIdle) so we
// only stall on this submission, not the whole queue. Frees the fence and
// command buffer before returning either way.
//
// Used by every transient (allocate → record → submit → wait → free) Vulkan
// op in this file: initial layout transitions, the CPU-side AHB copy, and
// the linear-blit fallback. The session's persistent command ring
// (acquireCommandRing) is a separate, non-transient path used by the
// steady-state swapchain blit.
bool runTransientCommands(const std::function<void(VkCommandBuffer)> &record) {
    const VkCommandBufferAllocateInfo cbai{
        .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,
        .commandPool = g.vk.commandPool,
        .level = VK_COMMAND_BUFFER_LEVEL_PRIMARY,
        .commandBufferCount = 1,
    };
    VkCommandBuffer cb = VK_NULL_HANDLE;
    if (g.vk.fn.vkAllocateCommandBuffers(g.vk.device, &cbai, &cb) != VK_SUCCESS) return false;

    const VkCommandBufferBeginInfo bi{
        .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
        .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT,
    };
    g.vk.fn.vkBeginCommandBuffer(cb, &bi);
    record(cb);
    g.vk.fn.vkEndCommandBuffer(cb);

    const VkFenceCreateInfo fci{ .sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO };
    VkFence fence = VK_NULL_HANDLE;
    g.vk.fn.vkCreateFence(g.vk.device, &fci, nullptr, &fence);

    const VkSubmitInfo si{
        .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,
        .commandBufferCount = 1,
        .pCommandBuffers = &cb,
    };
    const bool submitted = g.vk.fn.vkQueueSubmit(g.vk.computeQueue, 1, &si, fence) == VK_SUCCESS;
    if (submitted) g.vk.fn.vkWaitForFences(g.vk.device, 1, &fence, VK_TRUE, UINT64_MAX);
    if (fence != VK_NULL_HANDLE) g.vk.fn.vkDestroyFence(g.vk.device, fence, nullptr);
    g.vk.fn.vkFreeCommandBuffers(g.vk.device, g.vk.commandPool, 1, &cb);
    return submitted;
}

// ---- Vulkan swapchain helpers ------------------------------------------------
//
// The swapchain lives on top of the overlay's ANativeWindow and provides the
// GPU-only output path: generated frames are vkCmdBlitImage'd from their AHB
// storage directly into the swapchain image and presented via vkQueuePresentKHR.
// No CPU touch of the pixel data.
//
// DEFAULT OFF. An earlier run on Adreno 840 / Android 14 crashed in a driver
// call during createSwapchain before any of the per-step LOGIs could fire —
// the Adreno compute queue reports `vkGetPhysicalDeviceSurfaceSupportKHR =
// VK_TRUE` but then crashes inside `vkQueuePresentKHR` (known quirk on some
// Qualcomm revisions). Keeping the code path in-tree with aggressive logging
// so future device revisions / validation work can flip this flag without
// another refactor. To enable for testing, set `kEnableWsiSwapchain` to true.
constexpr bool kEnableWsiSwapchain = true;

void destroySwapchain() {
    // Must drain every in-flight ring submission before touching the images
    // — the swapchain images are referenced by recorded CBs via barriers and
    // destroying them while those CBs are still executing = SIGSEGV on some
    // drivers (observed on Qualcomm). vkDeviceWaitIdle is the sledgehammer.
    if (g.vk.device != VK_NULL_HANDLE && g.vk.fn.vkDeviceWaitIdle != nullptr) {
        g.vk.fn.vkDeviceWaitIdle(g.vk.device);
    }

    if (g.vk.fn.vkDestroySemaphore != nullptr) {
        for (VkSemaphore s : g.swap.acquireSems) {
            if (s != VK_NULL_HANDLE) g.vk.fn.vkDestroySemaphore(g.vk.device, s, nullptr);
        }
        for (VkSemaphore s : g.swap.renderSems) {
            if (s != VK_NULL_HANDLE) g.vk.fn.vkDestroySemaphore(g.vk.device, s, nullptr);
        }
    }
    g.swap.acquireSems.clear();
    g.swap.renderSems.clear();
    g.swap.images.clear();
    g.swap.imageWasPresented.clear();
    g.swap.acquireCursor = 0;
    g.swap.outOfDate = false;
    g.swap.disabledForSession = false;

    if (g.swap.swapchain != VK_NULL_HANDLE && g.vk.fn.vkDestroySwapchainKHR != nullptr) {
        g.vk.fn.vkDestroySwapchainKHR(g.vk.device, g.swap.swapchain, nullptr);
        g.swap.swapchain = VK_NULL_HANDLE;
    }
    if (g.swap.surface != VK_NULL_HANDLE && g.vk.instance != VK_NULL_HANDLE) {
        // Surface destruction uses the instance-level function. volk populates
        // it globally after volkLoadInstance.
        if (g.vk.pfnDestroySurfaceKHR != nullptr) {
            g.vk.pfnDestroySurfaceKHR(g.vk.instance, g.swap.surface, nullptr);
        } else {
            vkDestroySurfaceKHR(g.vk.instance, g.swap.surface, nullptr);
        }
        g.swap.surface = VK_NULL_HANDLE;
    }
    g.swap.extent = {0, 0};
    g.swap.format = VK_FORMAT_UNDEFINED;
}

// Build (or rebuild) the swapchain on the current outWindow. Returns true only
// when the hard-coded MAILBOX WSI path is live. There is no presentation fallback.
//
// Safe to call multiple times; previous swapchain is torn down first.
bool createSwapchain() {
    // GPU-only presentation. A failure disables WSI for this session; there is
    // deliberately no CPU pixel fallback in the hot path.
    LOGI("createSwapchain: enter outWindow=%p hasSwapchain=%d enable=%d cpuTainted=%d",
         static_cast<void *>(g.outWindow), (int)g.vk.hasSwapchain,
         (int)kEnableWsiSwapchain, (int)g.windowCpuProducerLocked);
    destroySwapchain();
    if (!kEnableWsiSwapchain) return false;
    if (!g.vk.hasSwapchain) return false;
    if (g.outWindow == nullptr) return false;
    if (g.vk.instance == VK_NULL_HANDLE) return false;
    // Once the worker has produced any CPU buffer on this ANativeWindow
    // (any CPU producer, if present, etc.) the BufferQueue is locked to
    // a CPU producer and vkCreateAndroidSurfaceKHR will return
    // VK_ERROR_NATIVE_WINDOW_IN_USE_KHR (-1000000001) on Mali / many other
    // drivers. Don't even attempt — failed surface creation has been observed
    // to correlate with framegen-side DEVICE_LOST on Mali-G57 (likely an
    // instance-level state corruption when the WSI driver path bails late).
    if (g.windowCpuProducerLocked) {
        LOGI("createSwapchain: skipping — window already bound to CPU producer this session");
        return false;
    }
    // Use the session-cached function pointer instead of volk's global. The
    // global gets clobbered to NULL when framegen's Instance::Instance()
    // calls volkLoadInstance() against its own surface-less VkInstance,
    // because that instance can't resolve vkCreateAndroidSurfaceKHR. The
    // session pointer was resolved against OUR instance at session-init time
    // and survives that overwrite.
    PFN_vkCreateAndroidSurfaceKHR pfnCreateSurface = g.vk.pfnCreateAndroidSurfaceKHR;
    if (pfnCreateSurface == nullptr) pfnCreateSurface = vkCreateAndroidSurfaceKHR;
    if (pfnCreateSurface == nullptr) {
        LOGW("vkCreateAndroidSurfaceKHR fn ptr is NULL — volk didn't load it");
        return false;
    }

    LOGI("createSwapchain: calling vkCreateAndroidSurfaceKHR");
    const VkAndroidSurfaceCreateInfoKHR sci{
        .sType = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR,
        .window = g.outWindow,
    };
    const VkResult sres = pfnCreateSurface(g.vk.instance, &sci, nullptr, &g.swap.surface);
    if (sres != VK_SUCCESS) {
        // VK_ERROR_NATIVE_WINDOW_IN_USE_KHR means the ANativeWindow is locked
        // to a CPU producer (or another consumer). The state is sticky — no
        // amount of retrying on the same window will recover. Pin the taint
        // so subsequent createSwapchain calls bail at the early gate above
        // instead of repeating the same failed driver call (which on some
        // Mali / Adreno revisions can leave the instance in a tainted state
        // and induce a DEVICE_LOST on framegen's separate device).
        if (sres == VK_ERROR_NATIVE_WINDOW_IN_USE_KHR) {
            g.windowCpuProducerLocked = true;
            LOGW("vkCreateAndroidSurfaceKHR: window in use by CPU producer (rc=%d) — WSI disabled for this surface",
                 (int)sres);
        } else {
            LOGW("vkCreateAndroidSurfaceKHR failed (rc=%d) — WSI disabled", (int)sres);
        }
        g.swap.surface = VK_NULL_HANDLE;
        return false;
    }
    LOGI("createSwapchain: surface=%p", static_cast<void *>(g.swap.surface));

    // Can our compute queue actually present on this surface?
    VkBool32 canPresent = VK_FALSE;
    const VkResult suppr = g.vk.pfnGetPhysicalDeviceSurfaceSupportKHR(g.vk.physicalDevice,
            g.vk.computeFamilyIdx, g.swap.surface, &canPresent);
    LOGI("createSwapchain: surfaceSupport rc=%d canPresent=%d", (int)suppr, (int)canPresent);
    if (suppr != VK_SUCCESS || canPresent != VK_TRUE) {
        LOGE("compute queue family %u cannot present on this surface — MAILBOX presentation unavailable",
             g.vk.computeFamilyIdx);
        destroySwapchain();
        return false;
    }

    VkSurfaceCapabilitiesKHR caps{};
    if (g.vk.pfnGetPhysicalDeviceSurfaceCapabilitiesKHR(g.vk.physicalDevice,
            g.swap.surface, &caps) != VK_SUCCESS) {
        LOGW("vkGetPhysicalDeviceSurfaceCapabilitiesKHR failed");
        destroySwapchain();
        return false;
    }

    // Keep the presentation path on one simple, low-latency format: RGBA8
    // sRGB. HDR is still available through the separate toggle later, but the
    // default runtime should stay on the narrowest possible path so the swapchain
    // can stay fully GPU-native and avoid any extra format conversion work.
    uint32_t fmtCount = 0;
    g.vk.pfnGetPhysicalDeviceSurfaceFormatsKHR(g.vk.physicalDevice, g.swap.surface,
                                         &fmtCount, nullptr);
    std::vector<VkSurfaceFormatKHR> fmts(fmtCount);
    if (fmtCount > 0) {
        g.vk.pfnGetPhysicalDeviceSurfaceFormatsKHR(g.vk.physicalDevice, g.swap.surface,
                                             &fmtCount, fmts.data());
    }
    VkSurfaceFormatKHR chosen{VK_FORMAT_R8G8B8A8_UNORM, VK_COLORSPACE_SRGB_NONLINEAR_KHR};
    for (const auto &f : fmts) {
        if (f.format == VK_FORMAT_R8G8B8A8_UNORM &&
            f.colorSpace == VK_COLORSPACE_SRGB_NONLINEAR_KHR) {
            chosen = f;
            break;
        }
    }
    if (fmts.empty()) {
        chosen = {VK_FORMAT_R8G8B8A8_UNORM, VK_COLORSPACE_SRGB_NONLINEAR_KHR};
    }

    // HARD-CODED presentation policy: MAILBOX only.
    // FIFO and IMMEDIATE are never selected and there is no mode fallback.
    // If MAILBOX is not advertised, this presentation path stays disabled.
    constexpr VkPresentModeKHR presentMode = VK_PRESENT_MODE_MAILBOX_KHR;

    uint32_t presentModeCount = 0;
    if (g.vk.pfnGetPhysicalDeviceSurfacePresentModesKHR != nullptr &&
        g.vk.pfnGetPhysicalDeviceSurfacePresentModesKHR(
            g.vk.physicalDevice, g.swap.surface, &presentModeCount, nullptr) == VK_SUCCESS &&
        presentModeCount > 0) {
        std::vector<VkPresentModeKHR> modes(presentModeCount);
        if (g.vk.pfnGetPhysicalDeviceSurfacePresentModesKHR(
                g.vk.physicalDevice, g.swap.surface, &presentModeCount, modes.data()) == VK_SUCCESS) {
            bool selectedAvailable = false;
            for (const auto mode : modes) {
                if (mode == presentMode) {
                    selectedAvailable = true;
                    break;
                }
            }
            if (!selectedAvailable) {
                LOGE("hard-coded MAILBOX present mode is unsupported by surface — presentation disabled");
                destroySwapchain();
                return false;
            }
        } else {
            LOGW("failed to enumerate present modes — WSI disabled");
            destroySwapchain();
            return false;
        }
    } else {
        LOGW("present-mode query unavailable — WSI disabled");
        destroySwapchain();
        return false;
    }
    g.swap.presentMode = presentMode;

    // MAILBOX normally benefits from at least 3 swapchain images so the
    // compositor can replace an older queued image without blocking the producer.
    // ahead by one while SurfaceFlinger holds the currently-displayed image).
    uint32_t imageCount = caps.minImageCount + 1;
    if (caps.maxImageCount > 0 && imageCount > caps.maxImageCount) {
        imageCount = caps.maxImageCount;
    }
    if (imageCount < 2) imageCount = 2;

    VkExtent2D extent = caps.currentExtent;
    if (extent.width == 0 || extent.width == UINT32_MAX) {
        extent.width = g.outWidth > 0 ? g.outWidth : 1920;
    }
    if (extent.height == 0 || extent.height == UINT32_MAX) {
        extent.height = g.outHeight > 0 ? g.outHeight : 1080;
    }

    // TRANSFER_DST is mandatory for the GPU-native swapchain copy.
    // Some drivers require explicit opt-in via capabilities.supportedUsageFlags.
    if ((caps.supportedUsageFlags & VK_IMAGE_USAGE_TRANSFER_DST_BIT) == 0) {
        LOGW("swapchain surface does not advertise TRANSFER_DST usage — WSI disabled");
        destroySwapchain();
        return false;
    }

    const VkSwapchainCreateInfoKHR sci2{
        .sType = VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR,
        .surface = g.swap.surface,
        .minImageCount = imageCount,
        .imageFormat = chosen.format,
        .imageColorSpace = chosen.colorSpace,
        .imageExtent = extent,
        .imageArrayLayers = 1,
        .imageUsage = VK_IMAGE_USAGE_TRANSFER_DST_BIT,
        .imageSharingMode = VK_SHARING_MODE_EXCLUSIVE,
        .preTransform = caps.currentTransform,
        .compositeAlpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR,
        .presentMode = presentMode,
        .clipped = VK_TRUE,
    };
    LOGI("createSwapchain: vkCreateSwapchainKHR fmt=%d extent=%ux%u imageCount=%u",
         (int)chosen.format, extent.width, extent.height, imageCount);
    if (g.vk.fn.vkCreateSwapchainKHR == nullptr) {
        LOGW("vkCreateSwapchainKHR fn ptr is NULL");
        destroySwapchain();
        return false;
    }
    if (g.vk.fn.vkCreateSwapchainKHR(g.vk.device, &sci2, nullptr,
            &g.swap.swapchain) != VK_SUCCESS) {
        LOGW("vkCreateSwapchainKHR failed");
        destroySwapchain();
        return false;
    }
    LOGI("createSwapchain: swapchain=%p", static_cast<void *>(g.swap.swapchain));

    uint32_t realCount = 0;
    g.vk.fn.vkGetSwapchainImagesKHR(g.vk.device, g.swap.swapchain, &realCount, nullptr);
    g.swap.images.resize(realCount);
    g.swap.imageWasPresented.assign(realCount, 0);
    g.vk.fn.vkGetSwapchainImagesKHR(g.vk.device, g.swap.swapchain, &realCount,
                                    g.swap.images.data());

    // Semaphore pools:
    //   acquireSems: one per "in-flight frame" slot (we use realCount + 1)
    //   renderSems:  one per swapchain image (vkQueuePresent waits on this
    //                semaphore for the specific image we rendered to)
    g.swap.acquireSems.resize(realCount + 1, VK_NULL_HANDLE);
    g.swap.renderSems.resize(realCount, VK_NULL_HANDLE);
    const VkSemaphoreCreateInfo semInfo{
        .sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO,
    };
    for (auto &s : g.swap.acquireSems) {
        if (g.vk.fn.vkCreateSemaphore(g.vk.device, &semInfo, nullptr, &s) != VK_SUCCESS) {
            LOGW("vkCreateSemaphore(acquire) failed");
            destroySwapchain();
            return false;
        }
    }
    for (auto &s : g.swap.renderSems) {
        if (g.vk.fn.vkCreateSemaphore(g.vk.device, &semInfo, nullptr, &s) != VK_SUCCESS) {
            LOGW("vkCreateSemaphore(render) failed");
            destroySwapchain();
            return false;
        }
    }

    g.swap.extent = extent;
    g.swap.format = chosen.format;
    g.swap.acquireCursor = 0;
    g.swap.outOfDate = false;
    LOGI("Swapchain ready: %ux%u fmt=%d images=%u mode=MAILBOX",
         extent.width, extent.height, (int)chosen.format, realCount);
    return true;
}

// Reap cross-device framegen completion semaphores without blocking.
void reapFramegenCompletionTickets() {
    if (g.vk.device == VK_NULL_HANDLE || g.vk.fn.vkGetFenceStatus == nullptr ||
            g.vk.fn.vkDestroySemaphore == nullptr) return;
    size_t write = 0;
    for (size_t i = 0; i < g.framegenCompletionTickets.size(); ++i) {
        auto &t = g.framegenCompletionTickets[i];
        const bool done = (t.fence == VK_NULL_HANDLE) ||
            (g.vk.fn.vkGetFenceStatus(g.vk.device, t.fence) == VK_SUCCESS);
        if (done) {
            // Feed the real, measured GPU-side generation latency back into
            // admission control — see submittedAtNs's declaration for why
            // this (not timing presentContext() itself) is the number that
            // actually reflects GPU load.
            if (t.submittedAtNs > 0) {
                const int64_t nowNs = std::chrono::duration_cast<std::chrono::nanoseconds>(
                    State::Clock::now().time_since_epoch()).count();
                const int64_t elapsedNs = nowNs - t.submittedAtNs;
                if (elapsedNs > 0) recordGenerationCostSample(elapsedNs, t.pixelCountForCost);
            }
            if (t.semaphore != VK_NULL_HANDLE)
                g.vk.fn.vkDestroySemaphore(g.vk.device, t.semaphore, nullptr);
            // Only destroy the fence if this ticket privately owns it
            // (from drainFramegenCompletionSemaphore's vkCreateFence). A
            // ticket from the successful-blit path borrows a shared command-
            // ring fence that the ring itself still owns and reuses —
            // destroying it here would be a use-after-free the next time the
            // ring cycles back to that slot.
            if (t.ownsFence && t.fence != VK_NULL_HANDLE && g.vk.fn.vkDestroyFence != nullptr)
                g.vk.fn.vkDestroyFence(g.vk.device, t.fence, nullptr);
        } else {
            g.framegenCompletionTickets[write++] = t;
        }
    }
    g.framegenCompletionTickets.resize(write);
    if (g.framegenCompletionTickets.empty() &&
        !g.cpuFallbackGenInFlight.load(std::memory_order_acquire)) {
        g.framegenInputsInFlight.store(false, std::memory_order_release);
    }
}

void destroyFramegenCompletionTickets() {
    if (g.vk.device == VK_NULL_HANDLE || g.vk.fn.vkDestroySemaphore == nullptr) {
        g.framegenCompletionTickets.clear();
        return;
    }
    for (auto &t : g.framegenCompletionTickets) {
        if (t.semaphore != VK_NULL_HANDLE)
            g.vk.fn.vkDestroySemaphore(g.vk.device, t.semaphore, nullptr);
        if (t.ownsFence && t.fence != VK_NULL_HANDLE && g.vk.fn.vkDestroyFence != nullptr)
            g.vk.fn.vkDestroyFence(g.vk.device, t.fence, nullptr);
    }
    g.framegenCompletionTickets.clear();
}

bool createFramegenCompletionSemaphore(VkSemaphore &outSemaphore, int &outFd) {
    outSemaphore = VK_NULL_HANDLE;
    outFd = -1;
    if (g.vk.device == VK_NULL_HANDLE || g.vk.fn.vkCreateSemaphore == nullptr ||
            g.vk.fn.vkGetSemaphoreFdKHR == nullptr) return false;

    const VkExternalSemaphoreHandleTypeFlagBits handleType =
        g.vk.hasExportableSyncFdSemaphore
            ? VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT
            : VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_FD_BIT;

    const VkExportSemaphoreCreateInfo exportInfo{
        .sType = VK_STRUCTURE_TYPE_EXPORT_SEMAPHORE_CREATE_INFO,
        .handleTypes = handleType,
    };
    const VkSemaphoreCreateInfo createInfo{
        .sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO,
        .pNext = &exportInfo,
    };
    const VkResult createRes = g.vk.fn.vkCreateSemaphore(g.vk.device, &createInfo, nullptr, &outSemaphore);
    if (createRes != VK_SUCCESS) {
        LOGE("createFramegenCompletionSemaphore: vkCreateSemaphore(%s) -> %d",
             handleType == VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT ? "SYNC_FD" : "OPAQUE_FD",
             (int)createRes);
        return false;
    }

    // SYNC_FD has "copy" payload transference (VUID-VkSemaphoreGetFdInfoKHR-
    // handleType-01135): the semaphore must already be signaled, or have a
    // signal operation pending, before its fd can be exported. A freshly
    // created semaphore has neither, so exporting it right after
    // vkCreateSemaphore is invalid usage — Mali's driver enforces this and
    // rejects the call (observed as vkGetSemaphoreFdKHR returning fd=-1).
    // OPAQUE_FD has reference transference and isn't affected, so only do
    // this for SYNC_FD. Submit an empty, wait-free batch that just signals
    // the semaphore; framegen will still wait on/consume it as intended once
    // it imports the fd, since the signal only needs to be *pending*, not
    // completed, at export time.
    if (handleType == VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT) {
        const VkSubmitInfo primeSi{
            .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,
            .signalSemaphoreCount = 1,
            .pSignalSemaphores = &outSemaphore,
        };
        const VkResult primeRes = g.vk.fn.vkQueueSubmit(g.vk.computeQueue, 1, &primeSi, VK_NULL_HANDLE);
        if (primeRes != VK_SUCCESS) {
            LOGE("createFramegenCompletionSemaphore: priming vkQueueSubmit -> %d", (int)primeRes);
            g.vk.fn.vkDestroySemaphore(g.vk.device, outSemaphore, nullptr);
            outSemaphore = VK_NULL_HANDLE;
            return false;
        }
        // On at least some Mali proprietary driver builds, an empty
        // (zero-command-buffer) submit that only signals a semaphore is
        // apparently fast-pathed/optimized in a way that never materializes
        // a real underlying sync_file for that semaphore — so the "pending
        // signal" the SYNC_FD export spec allows for is technically true on
        // the host side but the driver's export path still sees nothing to
        // hand back and returns fd=-1 (observed as
        // "vkGetSemaphoreFdKHR(SYNC_FD) -> -1 fd=-1" the very first time
        // this runs each session). Forcing the batch to fully retire before
        // exporting gives the driver a completed (not just pending) signal
        // to work with, which the proprietary Mali blob handles more
        // reliably in practice than the pending case. This only costs a
        // stall on the rare occasions this function runs (session init /
        // resolution change), never in the per-frame hot path.
        const VkResult primeWaitRes = g.vk.fn.vkQueueWaitIdle(g.vk.computeQueue);
        if (primeWaitRes != VK_SUCCESS) {
            LOGE("createFramegenCompletionSemaphore: priming vkQueueWaitIdle -> %d", (int)primeWaitRes);
            g.vk.fn.vkDestroySemaphore(g.vk.device, outSemaphore, nullptr);
            outSemaphore = VK_NULL_HANDLE;
            return false;
        }
    }

    const VkSemaphoreGetFdInfoKHR fdInfo{
        .sType = VK_STRUCTURE_TYPE_SEMAPHORE_GET_FD_INFO_KHR,
        .semaphore = outSemaphore,
        .handleType = handleType,
    };
    const VkResult fdRes = g.vk.fn.vkGetSemaphoreFdKHR(g.vk.device, &fdInfo, &outFd);
    if (fdRes != VK_SUCCESS || outFd < 0) {
        LOGE("createFramegenCompletionSemaphore: vkGetSemaphoreFdKHR(%s) -> %d fd=%d",
             handleType == VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT ? "SYNC_FD" : "OPAQUE_FD",
             (int)fdRes, outFd);
        g.vk.fn.vkDestroySemaphore(g.vk.device, outSemaphore, nullptr);
        outSemaphore = VK_NULL_HANDLE;
        outFd = -1;
        return false;
    }
    return true;
}

// Recent average interval between successful WSI posts, in ns. recordOverlayPost()
// runs for both REAL and GENERATED frames (see blitOutputToWindow), so this is the
// actual combined output cadence already being achieved on this device right now —
// e.g. multiplier=2 sustaining real+gen posts averages toward the "120fps" interval
// without us having to assume a display refresh rate or trust the generation
// multiplier as a promise of what's actually landing on screen.
// Returns 0 when there isn't enough history yet (cold start) or posting has
// stalled (torn/non-monotonic ring read) — callers must have a fallback for that.
uint64_t recentPostIntervalNs() {
    constexpr uint64_t kSampleCount = 16;
    const uint64_t head = g.postRingHead.load(std::memory_order_acquire);
    if (head < 2) return 0;
    const uint64_t validEntries = std::min<uint64_t>(head, State::kPostRingSize);
    const uint64_t span = std::min<uint64_t>(kSampleCount, validEntries - 1);
    if (span == 0) return 0;
    const uint64_t newestTs = g.postRingTimestamps[(head - 1) % State::kPostRingSize]
                                  .load(std::memory_order_relaxed);
    const uint64_t oldestTs = g.postRingTimestamps[(head - 1 - span) % State::kPostRingSize]
                                  .load(std::memory_order_relaxed);
    if (newestTs == 0 || oldestTs == 0 || oldestTs >= newestTs) return 0;
    return (newestTs - oldestTs) / span;
}

// Hard end-to-end latency guard. Generation is optional; REAL frames are not.
// Keep headroom below 60 ms for compositor/driver jitter.
constexpr int64_t kHardLatencyBudgetNs = 60'000'000LL;
constexpr int64_t kLatencyReserveNs = 8'000'000LL;

uint64_t conservativeOutputIntervalNs() {
    const uint64_t observed = recentPostIntervalNs();
    return observed == 0 ? 16'000'000ULL : observed;
}

bool generationFitsHardLatency(const State::PendingFrame &frame,
                               int64_t generationEstimateNs,
                               size_t outputCount) {
    if (frame.captureTimestampNs <= 0 || generationEstimateNs <= 0 || outputCount == 0)
        return false;
    const int64_t nowNs = static_cast<int64_t>(std::chrono::duration_cast<
        std::chrono::nanoseconds>(State::Clock::now().time_since_epoch()).count());
    const int64_t ageNs = std::max<int64_t>(0, nowNs - frame.captureTimestampNs);
    if (ageNs >= kHardLatencyBudgetNs - kLatencyReserveNs) return false;
    const uint64_t postIntervalNs = conservativeOutputIntervalNs();
    const int64_t outputBudgetNs = static_cast<int64_t>(
        std::min<size_t>(outputCount, 16) * postIntervalNs);
    return ageNs + generationEstimateNs + outputBudgetNs
           <= kHardLatencyBudgetNs - kLatencyReserveNs;
}

bool generationPublicationStillFits(const State::PendingFrame &frame,
                                    size_t remainingOutputs) {
    if (frame.captureTimestampNs <= 0 || remainingOutputs == 0) return true;
    const int64_t nowNs = static_cast<int64_t>(std::chrono::duration_cast<
        std::chrono::nanoseconds>(State::Clock::now().time_since_epoch()).count());
    const int64_t ageNs = std::max<int64_t>(0, nowNs - frame.captureTimestampNs);
    const uint64_t intervalNs = conservativeOutputIntervalNs();
    const int64_t projectedNs = ageNs + static_cast<int64_t>(
        std::min<size_t>(remainingOutputs, 16) * intervalNs);
    return projectedNs < kHardLatencyBudgetNs - kLatencyReserveNs;
}

// Blit `src` AHB-backed VkImage to the next swapchain image and present.
// Returns true on success. On VK_ERROR_OUT_OF_DATE_KHR or _SUBOPTIMAL_KHR the
// swapchain is marked dirty; the caller's next blit will recreate it.
bool blitOutputToSwapchain(const AhbImage &src, VkSemaphore framegenDoneSemaphore = VK_NULL_HANDLE,
                            int64_t genSubmittedAtNs = 0, uint64_t genPixelCount = 0,
                            bool optionalGenerated = false) {
    // Note: Called with g.presentMu held from blitOutputToWindow.
    if (g.swap.swapchain == VK_NULL_HANDLE) return false;
    if (src.image == VK_NULL_HANDLE) return false;
    reapFramegenCompletionTickets();
    if (g.swap.outOfDate) {
        if (!createSwapchain()) return false;
    }

    if (g.swap.acquireSems.empty()) return false;

    // Pick an acquire semaphore from the round-robin pool. Using a single
    // semaphore risks "semaphore already has a pending wait" on fast backs.
    VkSemaphore acquireSem = g.swap.acquireSems[g.swap.acquireCursor];
    g.swap.acquireCursor = (g.swap.acquireCursor + 1) % g.swap.acquireSems.size();

    uint32_t imageIdx = 0;
    // Adaptive acquire: do not use a zero-timeout path that silently discards
    // frames when the presentation queue is temporarily busy. Dynamic bypass
    // handles GPU load; the WSI stage must not become a hidden FPS limiter.
    //
    // Timeout is derived from the actual recent combined post cadence
    // (recentPostIntervalNs(), REAL+GEN together) instead of a fixed guess —
    // a device sustaining ~120 posts/sec tightens this toward ~8ms, ~60
    // posts/sec relaxes it toward ~16ms, self-correcting either way since
    // recentPostIntervalNs() reflects what's actually landing, not what the
    // multiplier merely requests. 2x margin absorbs normal jitter without
    // spurious VK_TIMEOUT drops. Clamped, not unbounded: this call runs with
    // g.presentMu held (see caller), and setOutputSurface() blocks on that
    // same mutex to clear g.outWindow on overlay stop/surface detach — an
    // unbounded wait here would turn a stalled compositor into a permanent
    // deadlock of the stop path instead of a dropped frame. Falls back to a
    // flat 16ms (60Hz-equivalent) before enough post history exists (cold
    // start) or once posting has stalled entirely.
    constexpr uint64_t kMinAcquireTimeoutNs = 4'000'000ULL;    // ~240Hz floor
    constexpr uint64_t kMaxAcquireTimeoutNs = 33'000'000ULL;   // ~30Hz ceiling
    constexpr uint64_t kFallbackAcquireTimeoutNs = 16'000'000ULL; // 60Hz-equivalent
    const uint64_t observedIntervalNs = recentPostIntervalNs();
    // GEN is disposable: never wait on a busy compositor. REAL keeps the
    // adaptive acquire timeout so the authoritative capture timeline is not
    // silently dropped by WSI.
    const uint64_t acquireTimeoutNs = optionalGenerated
        ? 0ULL
        : (observedIntervalNs == 0
            ? kFallbackAcquireTimeoutNs
            : std::clamp(observedIntervalNs * 2, kMinAcquireTimeoutNs, kMaxAcquireTimeoutNs));
    const VkResult ar = g.vk.fn.vkAcquireNextImageKHR(
        g.vk.device, g.swap.swapchain, acquireTimeoutNs, acquireSem, VK_NULL_HANDLE, &imageIdx);
    if (ar == VK_ERROR_OUT_OF_DATE_KHR) {
        g.swap.outOfDate = true;
        return false;
    }
    if (ar == VK_TIMEOUT) {
        // Presentation was busy for this short window. Do not drop the real
        // frame upstream; let the scheduler retry through dynamic pacing.
        return false;
    }
    if (ar == VK_NOT_READY) {
        return false;
    }
    if (ar != VK_SUCCESS && ar != VK_SUBOPTIMAL_KHR) {
        LOGW("vkAcquireNextImageKHR returned %d", (int)ar);
        return false;
    }

    if (imageIdx >= g.swap.images.size()) {
        LOGW("vkAcquireNextImageKHR returned OOB index %u (size %zu)", imageIdx, g.swap.images.size());
        return false;
    }

    VkCommandBuffer cb = VK_NULL_HANDLE;
    VkFence fence = VK_NULL_HANDLE;
    if (!acquireCommandRing(g.vk, cb, fence)) return false;
    const VkCommandBufferBeginInfo bi{
        .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
        .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT,
    };
    g.vk.fn.vkBeginCommandBuffer(cb, &bi);

    // Source: AHB-backed image owned by framegen's device between uses.
    // Acquire for TRANSFER_READ, release back to EXTERNAL after blit.
    const uint32_t foreign = VK_QUEUE_FAMILY_EXTERNAL;
    VkImageMemoryBarrier srcAcquire = makeImageBarrier(src.image,
        0, VK_ACCESS_TRANSFER_READ_BIT,
        VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
        foreign, g.vk.computeFamilyIdx);
    // After the first presentation, an acquired swapchain image is reused
    // from PRESENT_SRC_KHR. IMMEDIATE-capable Android drivers can fault when
    // this is incorrectly discarded back to UNDEFINED on every frame.
    const VkImageLayout dstOldLayout =
        g.swap.imageWasPresented[imageIdx] ? VK_IMAGE_LAYOUT_PRESENT_SRC_KHR
                                           : VK_IMAGE_LAYOUT_UNDEFINED;
    VkImageMemoryBarrier dstToTransfer = makeImageBarrier(g.swap.images[imageIdx],
        g.swap.imageWasPresented[imageIdx] ? VK_ACCESS_MEMORY_READ_BIT : 0,
        VK_ACCESS_TRANSFER_WRITE_BIT,
        dstOldLayout, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
        VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED);
    VkImageMemoryBarrier pre[2] = {srcAcquire, dstToTransfer};
    g.vk.fn.vkCmdPipelineBarrier(cb,
        VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
        0, 0, nullptr, 0, nullptr, 2, pre);

    static std::atomic<bool> loggedBlitMode{false};

    if (src.extent.width == g.swap.extent.width &&
        src.extent.height == g.swap.extent.height) {
        if (!loggedBlitMode.exchange(true, std::memory_order_relaxed)) {
            LOGW("blitOutputToSwapchain: 1:1 scale detected. Using FAST-PATH vkCmdCopyImage.");
        }
        const VkImageCopy region{
            .srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1},
            .srcOffset = {0, 0, 0},
            .dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1},
            .dstOffset = {0, 0, 0},
            .extent = {src.extent.width, src.extent.height, 1},
        };
        g.vk.fn.vkCmdCopyImage(cb,
            src.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
            g.swap.images[imageIdx], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
            1, &region);
    } else {
        if (!loggedBlitMode.exchange(true, std::memory_order_relaxed)) {
            LOGW("blitOutputToSwapchain: Scaled output detected (%ux%u -> %ux%u). Using VK_FILTER_LINEAR vkCmdBlitImage.",
                 src.extent.width, src.extent.height, g.swap.extent.width, g.swap.extent.height);
        }
        const VkImageBlit region{
            .srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1},
            .srcOffsets = {{0, 0, 0}, {
                static_cast<int32_t>(src.extent.width),
                static_cast<int32_t>(src.extent.height), 1,
            }},
            .dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1},
            .dstOffsets = {{0, 0, 0}, {
                static_cast<int32_t>(g.swap.extent.width),
                static_cast<int32_t>(g.swap.extent.height), 1,
            }},
        };
        g.vk.fn.vkCmdBlitImage(cb,
            src.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
            g.swap.images[imageIdx], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
            1, &region, VK_FILTER_LINEAR);
    }

    VkImageMemoryBarrier srcRelease = makeImageBarrier(src.image,
        VK_ACCESS_TRANSFER_READ_BIT, 0,
        VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL,
        g.vk.computeFamilyIdx, foreign);
    VkImageMemoryBarrier dstToPresent = makeImageBarrier(g.swap.images[imageIdx],
        VK_ACCESS_TRANSFER_WRITE_BIT, 0,
        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
        VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED);
    VkImageMemoryBarrier post[2] = {srcRelease, dstToPresent};
    g.vk.fn.vkCmdPipelineBarrier(cb,
        VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
        0, 0, nullptr, 0, nullptr, 2, post);

    g.vk.fn.vkEndCommandBuffer(cb);

    VkSemaphore renderSem = g.swap.renderSems[imageIdx];
    VkSemaphore waitSems[2] = { acquireSem, framegenDoneSemaphore };
    const VkPipelineStageFlags waitStages[2] = {
        VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT
    };
    const uint32_t waitCount = framegenDoneSemaphore != VK_NULL_HANDLE ? 2u : 1u;
    const VkSubmitInfo si{
        .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,
        .waitSemaphoreCount = waitCount,
        .pWaitSemaphores = waitSems,
        .pWaitDstStageMask = waitStages,
        .commandBufferCount = 1,
        .pCommandBuffers = &cb,
        .signalSemaphoreCount = 1,
        .pSignalSemaphores = &renderSem,
    };
    if (g.vk.fn.vkQueueSubmit(g.vk.computeQueue, 1, &si, fence) != VK_SUCCESS) {
        LOGW("vkQueueSubmit(swapchain blit) failed");
        return false;
    }
    // Arm the ring fence manually — submitCommandRing would have done this
    // but we bypassed it to add the semaphore wait/signal pair.
    for (uint32_t i = 0; i < kCommandRingSize; ++i) {
        if (g.vk.ringFences[i] == fence) {
            g.vk.ringFenceArmed[i] = true;
            break;
        }
    }
    if (framegenDoneSemaphore != VK_NULL_HANDLE) {
        g.framegenCompletionTickets.push_back(
            {framegenDoneSemaphore, fence, /*ownsFence=*/false, genSubmittedAtNs, genPixelCount});
    }

    const VkPresentInfoKHR pi{
        .sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR,
        .waitSemaphoreCount = 1,
        .pWaitSemaphores = &renderSem,
        .swapchainCount = 1,
        .pSwapchains = &g.swap.swapchain,
        .pImageIndices = &imageIdx,
    };
    const VkResult pr = g.vk.fn.vkQueuePresentKHR(g.vk.computeQueue, &pi);
    if (pr == VK_ERROR_OUT_OF_DATE_KHR || pr == VK_SUBOPTIMAL_KHR) {
        // Note for next pass — don't fall back this frame (we already posted).
        g.swap.outOfDate = true;
    } else if (pr != VK_SUCCESS) {
        LOGW("vkQueuePresentKHR returned %d", (int)pr);
        return false;
    }

    g.swap.imageWasPresented[imageIdx] = 1;
    // No present-mode fallback or software VSync. The selected WSI mode owns
    // presentation semantics for this swapchain.
    return true;
}

// Transition a freshly-created AHB-backed inSlot image UNDEFINED→GENERAL
// and release ownership to VK_QUEUE_FAMILY_EXTERNAL. This must be called once
// per inSlot after createAhbImage so that every subsequent copyAhbImage can
// acquire with oldLayout=GENERAL, preserving Mali AFRC state across frames.
void initInSlotImageLayout(VkImage image) {
    // Transition UNDEFINED→GENERAL and release to EXTERNAL in one barrier.
    const bool ok = runTransientCommands([&](VkCommandBuffer cb) {
        const VkImageMemoryBarrier barrier = makeImageBarrier(image, 0, 0,
            VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL,
            g.vk.computeFamilyIdx, VK_QUEUE_FAMILY_EXTERNAL);
        g.vk.fn.vkCmdPipelineBarrier(cb,
            VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
            VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
            0, 0, nullptr, 0, nullptr, 1, &barrier);
    });
    if (!ok) {
        LOGE("initInSlotImageLayout: transient command submission failed");
        return;
    }
    LOGI("initInSlotImageLayout: image=%p initialized to GENERAL/EXTERNAL", (void*)image);
}

// Copy `src` AhbImage into `dst` AhbImage on the compute queue using a
// transient command buffer. Both images are AHB-backed so layouts are
// EXTERNAL initially; we transition to TRANSFER_DST/SRC, copy, transition
// back to GENERAL so framegen can read them.
//
// Uses transient alloc+free per call rather than the session's CB ring:
// on Adreno the per-frame vkAllocateCommandBuffers/vkFreeCommandBuffers
// pair runs in single-digit microseconds, while vkResetCommandBuffer +
// vkWaitForFences (the ring path) was measurably slower in field testing.
bool copyAhbImage(const AhbImage &src, const AhbImage &dst) {
    // Per VK_ANDROID_external_memory_android_hardware_buffer: AHB-backed
    // images are conceptually owned by the FOREIGN_EXT queue family between
    // uses. Both src (just received from MediaProjection / ImageReader) and
    // dst (last touched by framegen on its own device) must be acquired from
    // FOREIGN_EXT before our compute queue can touch them — this is what
    // tells the driver "the foreign side is done writing, copy what's there".
    // Keep the Android-side session aligned with framegen's AHB import path:
    // both devices transfer ownership through the generic EXTERNAL family.
    const uint32_t foreign = VK_QUEUE_FAMILY_EXTERNAL;
    const uint32_t w = std::min(src.extent.width,  dst.extent.width);
    const uint32_t h = std::min(src.extent.height, dst.extent.height);

    const bool ok = runTransientCommands([&](VkCommandBuffer cb) {
        VkImageMemoryBarrier toSrc = makeImageBarrier(src.image,
            0, VK_ACCESS_TRANSFER_READ_BIT,
            VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
            foreign, g.vk.computeFamilyIdx);
        // dst.oldLayout is always GENERAL: set by initInSlotImageLayout and
        // preserved by releaseDst below.
        VkImageMemoryBarrier toDst = makeImageBarrier(dst.image,
            0, VK_ACCESS_TRANSFER_WRITE_BIT,
            VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
            foreign, g.vk.computeFamilyIdx);
        VkImageMemoryBarrier preBarriers[2] = {toSrc, toDst};
        g.vk.fn.vkCmdPipelineBarrier(cb,
            VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
            0, 0, nullptr, 0, nullptr, 2, preBarriers);

        const VkImageCopy region{
            .srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1},
            .srcOffset = {0, 0, 0},
            .dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1},
            .dstOffset = {0, 0, 0},
            .extent = {w, h, 1},
        };
        g.vk.fn.vkCmdCopyImage(cb,
            src.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
            dst.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
            1, &region);

        // Release dst back to FOREIGN so framegen (on its own device) can
        // acquire it cleanly via its own image-memory-barrier. We also
        // release src (we don't need it anymore — destroyAhbImage tears
        // down our VkImage wrapper, but the AHB itself stays alive in the
        // ImageReader's pool).
        VkImageMemoryBarrier releaseDst = makeImageBarrier(dst.image,
            VK_ACCESS_TRANSFER_WRITE_BIT, 0,
            VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL,
            g.vk.computeFamilyIdx, foreign);
        VkImageMemoryBarrier releaseSrc = makeImageBarrier(src.image,
            VK_ACCESS_TRANSFER_READ_BIT, 0,
            VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL,
            g.vk.computeFamilyIdx, foreign);
        VkImageMemoryBarrier postBarriers[2] = {releaseDst, releaseSrc};
        g.vk.fn.vkCmdPipelineBarrier(cb,
            VK_PIPELINE_STAGE_TRANSFER_BIT,
            VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
            0, 0, nullptr, 0, nullptr, 2, postBarriers);

        // Flush GPU L2 to DRAM before the CPU reads via AHardwareBuffer_lock.
        // Mali-G77 does not automatically flush the L2 on vkQueueWaitIdle, so
        // AHardwareBuffer_lock(fence=-1) reads stale zeroes without this barrier.
        const VkMemoryBarrier hostFlush{
            .sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER,
            .srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT,
            .dstAccessMask = VK_ACCESS_HOST_READ_BIT,
        };
        g.vk.fn.vkCmdPipelineBarrier(cb,
            VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT,
            0, 1, &hostFlush, 0, nullptr, 0, nullptr);
    });
    if (!ok) {
        LOGE("copyAhbImage: transient command submission failed dst=%ux%u",
             dst.extent.width, dst.extent.height);
    }
    return ok;
}

bool blitAhbImageGpu(const AhbImage &src, const AhbImage &dst) {
    if (src.image == VK_NULL_HANDLE || dst.image == VK_NULL_HANDLE) return false;
    const uint32_t foreign = VK_QUEUE_FAMILY_EXTERNAL;

    return runTransientCommands([&](VkCommandBuffer cb) {
        VkImageMemoryBarrier toSrc = makeImageBarrier(src.image,
            0, VK_ACCESS_TRANSFER_READ_BIT,
            VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
            foreign, g.vk.computeFamilyIdx);
        VkImageMemoryBarrier toDst = makeImageBarrier(dst.image,
            0, VK_ACCESS_TRANSFER_WRITE_BIT,
            VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
            foreign, g.vk.computeFamilyIdx);
        VkImageMemoryBarrier preBarriers[2] = {toSrc, toDst};
        g.vk.fn.vkCmdPipelineBarrier(cb,
            VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
            0, 0, nullptr, 0, nullptr, 2, preBarriers);

        const VkImageBlit region{
            .srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1},
            .srcOffsets = {{0, 0, 0}, {
                static_cast<int32_t>(src.extent.width),
                static_cast<int32_t>(src.extent.height),
                1,
            }},
            .dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1},
            .dstOffsets = {{0, 0, 0}, {
                static_cast<int32_t>(dst.extent.width),
                static_cast<int32_t>(dst.extent.height),
                1,
            }},
        };
        g.vk.fn.vkCmdBlitImage(cb,
            src.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
            dst.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
            1, &region, VK_FILTER_LINEAR);

        VkImageMemoryBarrier releaseSrc = makeImageBarrier(src.image,
            VK_ACCESS_TRANSFER_READ_BIT, 0,
            VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL,
            g.vk.computeFamilyIdx, foreign);
        VkImageMemoryBarrier releaseDst = makeImageBarrier(dst.image,
            VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_MEMORY_READ_BIT,
            VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL,
            g.vk.computeFamilyIdx, foreign);
        VkImageMemoryBarrier postBarriers[2] = {releaseSrc, releaseDst};
        g.vk.fn.vkCmdPipelineBarrier(cb,
            VK_PIPELINE_STAGE_TRANSFER_BIT,
            VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
            0, 0, nullptr, 0, nullptr, 2, postBarriers);
    });
}

bool processRealFrameIntoSlot(AHardwareBuffer *src, const AhbImage &dst) {
    if (src == nullptr || dst.ahb == nullptr) return false;

    // REAL FRAME LANE: CPU-only. Do not submit a Vulkan copy for captured
    // frames. The CPU reads the capture AHB and writes the ping-pong input AHB;
    // LSFG then consumes those AHBs on its own Vulkan device for framegen.
    return cpuCopyAhb(src, dst.ahb);
}

bool initFramegen(const char *cacheDir) {
    const std::string cache(cacheDir ? cacheDir : "");

    // There are two shader sources, in order of preference for a given
    // session:
    //
    //   1. FP16 SPIR-V (Lossless.dll IDs 304..351): explicitly selected.
    //   2. FP32 SPIR-V (Lossless.dll IDs 353..400): explicitly selected/default.
    // The selected precision is exclusive; there is no cross-precision fallback.
    //
    // FP32 SPIR-V is the default: it uses `OpMemoryModel Logical GLSL450`
    // with NO VulkanMemoryModel capability — verified across the entire
    // 353..400 range via _analysis/dump_fp32_spv.py / _analysis/fp32/. That
    // makes it work on devices that lack vulkanMemoryModel (Mali Bifrost/
    // Valhall G57/G68/G77), which a VMM-requiring shader would DEVICE_LOST
    // on at first compute dispatch (see Mali-G57 field log "presentContext
    // threw: Unable to submit command buffer (error -4)").
    const bool fp16Available = fp16_shaders_available(cache);
    const bool fp32SpirvAvailable = fp32_spirv_shaders_available(cache);

    // Precision is exclusive. Never silently switch precision or use a
    // cross-tier shader: the selected Lossless Scaling 3.2.2.0 SPIR-V set
    // must be complete before a framegen session is allowed to start.
    const bool useFp16 = g.framegenFp16;
    const bool selectedAvailable = useFp16 ? fp16Available : fp32SpirvAvailable;

    if (!selectedAvailable) {
        if (useFp16) {
            LOGE("FP16 framegen requested but the complete Lossless Scaling 3.2.2.0 "
                 "FP16 SPIR-V set (resources 304..351) is unavailable in %s — "
                 "no FP32 fallback will be used", cache.c_str());
        } else {
            LOGE("FP32 framegen requested but the complete Lossless Scaling 3.2.2.0 "
                 "FP32 SPIR-V set (resources 353..400) is unavailable in %s — "
                 "no FP16 fallback will be used", cache.c_str());
        }
        return false;
    }

    if (useFp16) {
        LOGI("Loading ONLY FP16 SPIR-V cache from Lossless Scaling 3.2.2.0 "
             "(resource IDs 304..351)");
    } else {
        LOGI("Loading ONLY FP32 SPIR-V cache from Lossless Scaling 3.2.2.0 "
             "(resource IDs 353..400)");
    }

    auto loader = [cache, useFp16](const std::string &name) -> std::vector<uint8_t> {
        // Framegen requests shaders by symbolic name (e.g. "p_mipmaps");
        // we cache them on disk by numeric resource ID. Each of the two
        // sources has its own ID range and on-disk subdirectory, both keyed
        // off the shared base id via constant offsets.
        uint32_t id = 0;
        ShaderCache source = ShaderCache::Fp32Spirv;
        if (useFp16) {
            id = shader_name_to_resource_id_fp16(name);
            source = ShaderCache::Fp16Spirv;
        } else {
            id = shader_name_to_resource_id_fp32_spirv(name);
            source = ShaderCache::Fp32Spirv;
        }
        if (id == 0) {
            LOGE("Unknown shader name '%s' from framegen", name.c_str());
            return {};
        }
        auto spirv = load_cached_spirv(cache, id, source);

        // No cross-precision fallback. A missing shader is fatal for this
        // selected precision so an FP32 session can never accidentally execute
        // an FP16 shader (or vice versa).
        if (spirv.empty()) {
            LOGE("Shader '%s' (id %u) missing from cache (%s)",
                 name.c_str(), id, cache.c_str());
        }
        return spirv;
    };

    try {
        if (g.performanceMode) {
            LSFG_3_1P::initialize(g.vk.deviceUuid, g.hdr, g.flowScale,
                                  static_cast<uint64_t>(g.multiplier), loader);
        } else {
            LSFG_3_1::initialize(g.vk.deviceUuid, g.hdr, g.flowScale,
                                 static_cast<uint64_t>(g.multiplier), loader);
        }
        return true;
    } catch (const std::exception &e) {
        LOGE("LSFG_3_1::initialize threw: %s — likely missing extension or shader. Continuing in capture-only mode.", e.what());
        return false;
    } catch (...) {
        LOGE("LSFG_3_1::initialize threw unknown exception");
        return false;
    }
}

bool createFramegenContext() {
    // Pass AHardwareBuffer pointers to framegen's Android variant. Framegen
    // imports them in its own VkDevice and shares pixel storage with us via
    // the AHB itself — we keep ownership and refcount on the Android side.
    if (g.inSlot[0].ahb == nullptr || g.inSlot[1].ahb == nullptr) {
        LOGE("Input AhbImages have no AHB pointer");
        return false;
    }
    std::vector<AHardwareBuffer*> outAhbs;
    outAhbs.reserve(g.outputs.size());
    for (auto &o : g.outputs) {
        if (o.ahb == nullptr) {
            LOGE("Output AhbImage missing AHB pointer");
            return false;
        }
        outAhbs.push_back(o.ahb);
    }

    try {
        if (g.performanceMode) {
            g.framegenCtxId = LSFG_3_1P::createContextFromAHB(
                g.inSlot[0].ahb, g.inSlot[1].ahb, outAhbs,
                g.inSlot[0].extent, g.inSlot[0].format);
        } else {
            g.framegenCtxId = LSFG_3_1::createContextFromAHB(
                g.inSlot[0].ahb, g.inSlot[1].ahb, outAhbs,
                g.inSlot[0].extent, g.inSlot[0].format);
        }
    } catch (const std::exception &e) {
        LOGE("createContextFromAHB threw: %s", e.what());
        return false;
    }
    return true;
}

// Output blit. Has two paths:
//
//  1. GPU fast path (WSI): vkCmdBlitImage from the output AHB's VkImage
//     straight into the next swapchain image, then vkQueuePresentKHR. Zero
//     CPU touch of the pixel data. Saves ~3-5 ms/blit at 1080p.
//
//  The CPU/software blit fallback (AHardwareBuffer_lock(CPU_READ) +
//  ANativeWindow_lock + per-row memcpy) has been REMOVED. This is now a
//  GPU-only presentation path: if the WSI swapchain isn't available on the
//  current surface (missing extension, driver rejects the surface, compute
//  queue can't present, etc.) the frame is dropped instead of falling back
//  to CPU. This trades away compatibility with devices/drivers that reject
//  the WSI path (e.g. the Mali-G57 window-in-use / Adreno present quirks
//  documented around createSwapchain()) for guaranteeing every posted frame
//  went through the GPU. On an affected device this means no frames get
//  posted at all rather than a degraded CPU blit — check logcat for
//  "blit dropped" warnings if the overlay goes blank.
//
// Returns true iff the frame was actually posted (recordOverlayPost() ran).
// Callers use this to keep generatedFrames/postedFrames in lockstep instead
// of assuming every attempted blit succeeds — see the comment at the
// generatedFrames.fetch_add() call site in workerThread().
void drainFramegenCompletionSemaphore(VkSemaphore semaphore) {
    if (semaphore == VK_NULL_HANDLE || g.vk.device == VK_NULL_HANDLE) return;
    for (const auto &t : g.framegenCompletionTickets)
        if (t.semaphore == semaphore) return; // already consumed by a submitted blit
    VkFence fence = VK_NULL_HANDLE;
    const VkFenceCreateInfo fci{ .sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO };
    if (g.vk.fn.vkCreateFence(g.vk.device, &fci, nullptr, &fence) != VK_SUCCESS) return;
    const VkPipelineStageFlags stage = VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
    const VkSubmitInfo si{
        .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,
        .waitSemaphoreCount = 1,
        .pWaitSemaphores = &semaphore,
        .pWaitDstStageMask = &stage,
    };
    if (g.vk.fn.vkQueueSubmit(g.vk.computeQueue, 1, &si, fence) != VK_SUCCESS) {
        g.vk.fn.vkDestroyFence(g.vk.device, fence, nullptr);
        return;
    }
    g.framegenCompletionTickets.push_back({semaphore, fence, /*ownsFence=*/true});
}

bool blitOutputToWindow(const AhbImage &out, VkSemaphore framegenDoneSemaphore = VK_NULL_HANDLE,
                         int64_t genSubmittedAtNs = 0, uint64_t genPixelCount = 0,
                         bool optionalGenerated = false) {
    // NOTE: g.outWindow is intentionally NOT checked here. setOutputSurface()
    // (the detach/attach path) clears it under g.presentMu, and this function
    // used to check it before taking that lock — a TOCTOU race where the
    // surface detaches between this check and the lock below would sail
    // through, reach createSwapchain() with a null window, fail, and
    // permanently set disabledForSession=true over what was really just a
    // transient detach (observed at overlay-stop time: "Output surface
    // detached" immediately followed by "MAILBOX swapchain creation
    // failed"). The real check now happens under the lock, right before it's
    // used.
    if (out.ahb == nullptr) {
        if (framegenDoneSemaphore != VK_NULL_HANDLE && !optionalGenerated)
            drainFramegenCompletionSemaphore(framegenDoneSemaphore);
        return false;
    }

    if (!kEnableWsiSwapchain || !g.vk.hasSwapchain || g.swap.disabledForSession) {
        if (framegenDoneSemaphore != VK_NULL_HANDLE && !optionalGenerated)
            drainFramegenCompletionSemaphore(framegenDoneSemaphore);
        LOGE("MAILBOX presentation unavailable on this surface; frame not submitted");
        return false;
    }

    // g.presentMu (not g.mu): swapchain/present state has its own lock,
    // deliberately separate from g.mu (which only guards g.pendingFrames /
    // g.stopRequested). createSwapchain() below can hit a vkDeviceWaitIdle()
    // (via destroySwapchain()) that runs for a few ms — holding g.mu across
    // that would block pushFrame() from enqueueing new captures for the
    // duration, reintroducing exactly the kind of stall this file is trying
    // to get rid of elsewhere. Callers (this thread, or genWaitThread posting
    // a generated frame) are serialized against each other here instead.
    std::lock_guard<std::mutex> lock(g.presentMu);
    // Re-check here, under the same lock setOutputSurface() uses to clear
    // g.outWindow. If it's null now, the surface was detached (or a
    // teardown is in progress) — a normal, expected drop, not a driver/
    // capability failure, so skip the frame quietly without touching
    // disabledForSession.
    if (g.outWindow == nullptr) {
        if (framegenDoneSemaphore != VK_NULL_HANDLE && !optionalGenerated)
            drainFramegenCompletionSemaphore(framegenDoneSemaphore);
        return false;
    }
    // MAILBOX is fixed globally.
    const VkPresentModeKHR requestedVkMode = VK_PRESENT_MODE_MAILBOX_KHR;
    if (g.swap.swapchain != VK_NULL_HANDLE && g.swap.presentMode != requestedVkMode) {
        // Mode changes are applied on the render thread, so swapchain teardown is
        // serialized with presentation and never races the Vulkan queue.
        destroySwapchain();
    }
    if (g.swap.swapchain == VK_NULL_HANDLE) {
        if (!createSwapchain()) {
            // g.outWindow was just confirmed non-null above under g.mu, the
            // same lock setOutputSurface() holds while clearing it, so this
            // failure can't be that race — it's a genuine capability/driver
            // problem and disabling WSI for the rest of the session is
            // warranted.
            g.swap.disabledForSession = true;
            LOGE("MAILBOX swapchain creation failed; frame not submitted");
            return false;
        }
    }
    if (blitOutputToSwapchain(out, framegenDoneSemaphore, genSubmittedAtNs, genPixelCount,
                              optionalGenerated)) {
        recordOverlayPost();
        return true;
    }
    if (framegenDoneSemaphore != VK_NULL_HANDLE && !optionalGenerated)
        drainFramegenCompletionSemaphore(framegenDoneSemaphore);
    LOGE("MAILBOX blit/present failed; frame not submitted");
    return false;
}

#ifdef LSFG_HAVE_NCNN
// Runs the ncnn AI backend for one frame pair. The model inference is GPU-only;
// input slots (oldSlot = previous capture, newSlot = just-copied current
// capture — matching the same chronological order LSFG's own frameIdx
// tracking uses), calls NcnnInterpolator::interpolate(), and CPU-writes each
// resulting frame straight into g.outputs[i]'s AHB so the existing
// timedBlit()/blitOutputToWindow() loop in workerThread can present them
// exactly like LSFG-generated output — no other code downstream needs to
// know which backend produced the pixels.
//
// AHardwareBuffer row stride can exceed width*4 bytes (GPU alignment
// padding), so every lock goes through a tightly-packed staging buffer —
// NcnnInterpolator's interpolate() contract assumes no stride.
//
// Input-side host visibility: no extra barrier is needed here.
// processRealFrameIntoSlot() (via copyAhbImage) already issues a
// TRANSFER_WRITE -> HOST_READ barrier before returning, so by the time this
// runs, whichever slot was just written this iteration is safe to
// CPU-read, and the other slot was already flushed on a prior iteration and
// hasn't been GPU-written since.
bool runAiInterpolate(int oldSlot, int newSlot, uint32_t w, uint32_t h) {
    const bool useIfrnet = (g.aiEngine == 1);
    if (useIfrnet) {
        if (g.aiIfrnet == nullptr || !g.aiIfrnet->isLoaded()) return false;
    } else {
        if (g.ai == nullptr || !g.ai->isLoaded()) return false;
    }

    auto lockRead = [](const AhbImage &img, std::vector<uint8_t> &staging) -> bool {
        void *ptr = nullptr;
        if (AHardwareBuffer_lock(img.ahb, AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN,
                                  -1, nullptr, &ptr) != 0 || ptr == nullptr) {
            return false;
        }
        AHardwareBuffer_Desc desc{};
        AHardwareBuffer_describe(img.ahb, &desc);
        const size_t strideBytes = static_cast<size_t>(desc.stride) * 4; // stride is in pixels
        const size_t rowBytes = static_cast<size_t>(desc.width) * 4;
        staging.resize(static_cast<size_t>(desc.height) * rowBytes);
        const uint8_t *src = static_cast<const uint8_t *>(ptr);
        for (uint32_t row = 0; row < desc.height; ++row) {
            std::memcpy(staging.data() + static_cast<size_t>(row) * rowBytes,
                        src + static_cast<size_t>(row) * strideBytes, rowBytes);
        }
        AHardwareBuffer_unlock(img.ahb, nullptr);
        return true;
    };

    auto lockWrite = [](const AhbImage &img, const uint8_t *staging) -> bool {
        void *ptr = nullptr;
        if (AHardwareBuffer_lock(img.ahb, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN,
                                  -1, nullptr, &ptr) != 0 || ptr == nullptr) {
            return false;
        }
        AHardwareBuffer_Desc desc{};
        AHardwareBuffer_describe(img.ahb, &desc);
        const size_t strideBytes = static_cast<size_t>(desc.stride) * 4;
        const size_t rowBytes = static_cast<size_t>(desc.width) * 4;
        uint8_t *dst = static_cast<uint8_t *>(ptr);
        for (uint32_t row = 0; row < desc.height; ++row) {
            std::memcpy(dst + static_cast<size_t>(row) * strideBytes,
                        staging + static_cast<size_t>(row) * rowBytes, rowBytes);
        }
        // fence == nullptr: block until the write is visible to other
        // consumers (the WSI swapchain's vkCmdBlitImage right after this).
        AHardwareBuffer_unlock(img.ahb, nullptr);
        return true;
    };

    const size_t frameBytes = static_cast<size_t>(w) * h * 4u;
    if (g.aiInputAStaging.size() != frameBytes) g.aiInputAStaging.resize(frameBytes);
    if (g.aiInputCStaging.size() != frameBytes) g.aiInputCStaging.resize(frameBytes);

    if (!lockRead(g.inSlot[oldSlot], g.aiInputAStaging) ||
        !lockRead(g.inSlot[newSlot], g.aiInputCStaging)) {
        LOGE("AI backend: failed to bridge input AHBs");
        return false;
    }

    // Reuse output staging allocations across frames. This removes a large
    // per-frame allocation/free cycle at 1080p+ and prevents heap fragmentation.
    const size_t outputCount = g.outputs.size();
    if (g.aiOutputStaging.size() != outputCount) {
        g.aiOutputStaging.resize(outputCount);
        g.aiOutputPtrs.resize(outputCount);
    }
    for (size_t i = 0; i < outputCount; ++i) {
        if (g.aiOutputStaging[i].size() != frameBytes)
            g.aiOutputStaging[i].resize(frameBytes);
        g.aiOutputPtrs[i] = g.aiOutputStaging[i].data();
    }

    // g.outputs.size() == g.multiplier (extra frames per pair); NcnnInterpolator
    // wants the total segment count (extra + 1).
    const int totalMult = g.multiplier + 1;

    // g.flowScale was inverted at init time for LSFG's convention
    // (g.flowScale = 1/userFlow); both interpolators want the plain
    // user-facing 0..1 fraction back, so invert it again here. RIFE and
    // IFRNet share the exact same interpolate() call shape (see the
    // "intentionally interchangeable" note in IfrnetInterpolator.hpp), so
    // only the object the call is made on differs.
    const int rc = useIfrnet
        ? g.aiIfrnet->interpolate(g.aiInputAStaging.data(), g.aiInputCStaging.data(),
                                   static_cast<int>(w), static_cast<int>(h),
                                   g.aiOutputPtrs.data(), totalMult, 1.0f / g.flowScale)
        : g.ai->interpolate(g.aiInputAStaging.data(), g.aiInputCStaging.data(),
                             static_cast<int>(w), static_cast<int>(h),
                             g.aiOutputPtrs.data(), totalMult, 1.0f / g.flowScale);
    if (rc != kNcnnOk) {
        LOGE("AI backend interpolate() failed rc=%d", rc);
        return false;
    }

    for (size_t i = 0; i < g.outputs.size(); ++i) {
        if (!lockWrite(g.outputs[i], g.aiOutputStaging[i].data())) {
            LOGE("AI backend: failed to CPU-lock output AHB %zu for write", i);
            return false;
        }
    }
    return true;
}
#endif // LSFG_HAVE_NCNN

// Auto-latency-bypass has been disabled: it used to watch capture->display
// latency and silently flip the pipeline into raw-blit bypass (no framegen)
// whenever the rolling average crossed autoLatencyBypassTriggerMs, which is
// what caused frame generation to keep turning itself off during normal
// play (thermal spikes, brief GPU contention, etc.). This is now a no-op —
// g.autoLatencyBypassActive is never set to true, so the runAi/runFramegen
// gate in workerThread() always sees it as false. The setters/getters
// (setAutoLatencyBypassEnabled, setAutoLatencyBypassThresholdMs,
// isAutoLatencyBypassActive) are left in place further down as harmless
// stubs so any existing JNI/Kotlin callers keep linking without changes.
// Blend a newly-measured (generationNs, pixelCount) sample into the
// resolution-normalized cost model. Called from both generation paths
// (inline framegen/AI success, and the CPU-waitIdle-fallback worker) right
// after a job's actual GPU/AI cost is known.
void recordGenerationCostSample(int64_t generationNs, uint64_t pixelCount) {
    if (generationNs <= 0) return;
    // Legacy flat EMA — still reset on session (re)start and still cheap to
    // keep around for logging, but admission decisions below no longer read
    // it directly.
    {
        const int64_t old = g.emaGenerationNs.load(std::memory_order_relaxed);
        const int64_t next = old <= 0 ? generationNs : (old * 7 + generationNs) / 8;
        g.emaGenerationNs.store(next, std::memory_order_relaxed);
    }
    if (pixelCount == 0) return; // no resolution info this sample; skip normalized update
    const double megapixels = static_cast<double>(pixelCount) / 1'000'000.0;
    if (megapixels <= 0.0) return;
    const int64_t sampleNsPerMp =
        static_cast<int64_t>(static_cast<double>(generationNs) / megapixels);
    if (sampleNsPerMp <= 0) return;
    const int64_t old = g.emaGenNsPerMegapixel.load(std::memory_order_relaxed);
    const int64_t next = old <= 0 ? sampleNsPerMp : (old * 7 + sampleNsPerMp) / 8;
    g.emaGenNsPerMegapixel.store(next, std::memory_order_relaxed);
}

// Project expected generation cost for a frame of the given pixel count,
// from the resolution-normalized EMA. This is what makes admission control
// react to a resolution change on the very next frame instead of lagging
// behind an absolute-time EMA that was measured at a different resolution.
// Falls back to the flat EMA (or 0, meaning "no history yet") when the
// normalized model has no samples or the caller has no pixel count handy.
int64_t projectGenerationCostNs(uint64_t pixelCount) {
    const int64_t perMp = g.emaGenNsPerMegapixel.load(std::memory_order_relaxed);
    if (perMp > 0 && pixelCount > 0) {
        const double megapixels = static_cast<double>(pixelCount) / 1'000'000.0;
        return static_cast<int64_t>(static_cast<double>(perMp) * megapixels);
    }
    // Bootstrap: no normalized samples yet (e.g. first few frames of the
    // session) — fall back to the flat EMA so admission control isn't
    // permanently wide open until the first normalized sample lands.
    return g.emaGenerationNs.load(std::memory_order_relaxed);
}

void updateAutoLatencyBypass(int64_t /*sampleLatencyNs*/) {
    // Intentionally empty — see comment above.
}

// Persistent thread for CPU-waitIdle-fallback framegen completion. Spawned
// once (alongside `worker`) and parked on g.genCv the rest of the time — see
// genWaitThread's declaration for why this isn't a thread spawned per job.
// Runs LSFG_*::waitIdle() + the generated-output blit off the main capture/
// present loop, so a slow wait on a device without exportable cross-device
// semaphores can't stall real-frame ingestion.
void genWorkerThread() {
    while (true) {
        bool perfMode = false;
        bool publishOutputs = true;
        State::Clock::time_point startedAt{};
        uint64_t jobEpoch = 0;
        uint64_t jobPixelCount = 0;
        {
            std::unique_lock<std::mutex> lock(g.genMu);
            g.genCv.wait(lock, []{ return g.genStopRequested || g.genJobPending; });
            if (g.genStopRequested && !g.genJobPending) return;
            perfMode = g.genJobPerfMode;
            publishOutputs = g.genJobPublish;
            startedAt = g.genJobStartedAt;
            jobEpoch = g.genJobEpoch;
            jobPixelCount = g.genJobPixelCount;
            g.genJobPending = false;
        }

        try {
            if (perfMode) LSFG_3_1P::waitIdle();
            else          LSFG_3_1::waitIdle();
        } catch (const std::exception &e) {
            handleFramegenException("waitIdle", e);
            {
                std::lock_guard<std::mutex> doneLock(g.genMu);
                g.genCompletedEpoch = std::max(g.genCompletedEpoch, jobEpoch);
            }
            g.cpuFallbackGenInFlight.store(false, std::memory_order_release);
            g.genDoneCv.notify_all();
            continue;
        }
        // A newer REAL capture or a bypass transition may have happened while
        // waitIdle() was running. Never publish an older generated frame after
        // that newer real frame. This is the key REAL -> GEN ordering guard.
        const uint64_t currentEpoch =
            g.captureEpoch.load(std::memory_order_acquire);
        const bool generationStillValid =
            publishOutputs &&
            jobEpoch != 0 &&
            jobEpoch == currentEpoch &&
            !g.bypass.load(std::memory_order_relaxed) &&
            !g.stopRequested;

        int posted = 0;
        if (generationStillValid) {
            // blitOutputToWindow() serializes against the main worker's
            // real-frame blit internally via g.presentMu — no lock needed here.
            for (const auto &out : g.outputs) {
                if (blitOutputToWindow(out)) posted++;
            }
        } else {
            LOGI("genWorker: dropping stale generated outputs jobEpoch=%llu currentEpoch=%llu bypass=%d",
                 static_cast<unsigned long long>(jobEpoch),
                 static_cast<unsigned long long>(currentEpoch),
                 g.bypass.load(std::memory_order_relaxed) ? 1 : 0);
        }
        if (posted > 0) {
            g.generatedFrames.fetch_add(static_cast<uint64_t>(posted),
                std::memory_order_relaxed);
        }
        // Feed the same admission-control cost model the main thread uses, so
        // dynamicBypass still reacts to how long generation is actually
        // taking on this device (and at what resolution) — it just no
        // longer has to sit on the hot path to measure it.
        const int64_t generationNs = std::chrono::duration_cast<
            std::chrono::nanoseconds>(State::Clock::now() - startedAt).count();
        recordGenerationCostSample(generationNs, jobPixelCount);
        {
            std::lock_guard<std::mutex> doneLock(g.genMu);
            g.genCompletedEpoch = std::max(g.genCompletedEpoch, jobEpoch);
        }
        g.cpuFallbackGenInFlight.store(false, std::memory_order_release);
        if (g.framegenCompletionTickets.empty()) {
            g.framegenInputsInFlight.store(false, std::memory_order_release);
        }
        g.genDoneCv.notify_all();
    }
}

void workerThread() {
    // Pin every CPU-side operation in this hot thread to the little
    // (efficiency) cluster and deliberately never schedule onto the
    // big/performance cluster, even under CPU pressure. This is thread-local
    // affinity; it does not require root and does not force unrelated
    // application threads onto any particular cluster.
    if (!g.cpuPolicy.useLittleCores()) {
        LOGW("workerThread: little-core affinity unavailable; leaving default affinity");
    }
    // Keep the CPU scheduler latency-friendly without URGENT_AUDIO (-19),
    // which can steal CPU time from the game. CPU coordinates capture/order/
    // admission; GPU is reserved for actual FrameGen compute.
    if (setpriority(PRIO_PROCESS, gettid(), ANDROID_PRIORITY_URGENT_DISPLAY) != 0) {
        LOGW("workerThread: setpriority(URGENT_DISPLAY) failed, staying at default priority");
    }

    // ---- Frame-time profiling ------------------------------------------------
    //
    // Rolling accumulators over a 60-frame window. The 4 segments are:
    //   copy     — importAhbImage + processRealFrameIntoSlot (input prep)
    //   present  — LSFG_3_1::presentContext (framegen submit; usually <1 ms)
    //   waitIdle — legacy profile bucket; cross-device sync is now GPU→GPU semaphore based
    //   blit     — output blit loop (CPU memcpy + ANativeWindow_unlockAndPost,
    //              one per generated + real frame)
    //   total    — frameWorkStartedAt → end of blit loop
    // The worker is intentionally uncapped; it never sleeps to a display-VSync
    // deadline or any derived output-FPS deadline.
    struct ProfileAccum {
        int64_t copyNs    = 0;
        int64_t presentNs = 0;
        int64_t waitIdleNs= 0;
        int64_t blitNs    = 0;
        int64_t totalNs   = 0;
        int64_t queueNs   = 0;
        int64_t captureToDisplayNs = 0;
        uint32_t blitCount = 0;
        uint32_t samples  = 0;
    } prof{};
    constexpr uint32_t kProfileWindow = 60;

    // The GPU swapchain owns presentation; no CPU-side surface clear or pixel
    // copy is performed in the hot path.
    while (true) {
        State::PendingFrame pendingFrame{};
        {
            std::unique_lock<std::mutex> lock(g.mu);
            g.pendingCv.wait(lock, []{ return g.stopRequested || !g.pendingFrames.empty(); });
            if (g.stopRequested && g.pendingFrames.empty()) return;
            // Consume only the frame selected for processing. REAL frames are
            // never discarded here; overload is handled by bypassing generation
            // for newly admitted frames.
            pendingFrame = g.pendingFrames.front();
            g.pendingFrames.pop_front();
        }
        const auto frameWorkStartedAt = State::Clock::now();
        prof.queueNs += std::chrono::duration_cast<std::chrono::nanoseconds>(
            frameWorkStartedAt - pendingFrame.queuedAt).count();
        AHardwareBuffer *ahb = pendingFrame.ahb;
        if (ahb == nullptr) continue;
        const uint64_t jobEpoch = pendingFrame.captureEpoch;

        reapFramegenCompletionTickets();
        if (g.framegenCompletionTickets.empty() &&
            !g.cpuFallbackGenInFlight.load(std::memory_order_acquire)) {
            g.framegenInputsInFlight.store(false, std::memory_order_release);
        }

        // Keep the captured AHB on the CPU side for real-frame movement. Vulkan
        // import is only needed when a captured buffer must be presented directly
        // while the framegen input slots are busy.
        // AHB import cache: only the direct-present fallback needs a Vulkan view.
        // Reuse the cached VkImage+VkDeviceMemory instead of importing per frame.
        AhbImage src{};
        bool srcFromCache = false;
        {
            auto it = g.ahbImportCache.find(ahb);
            if (it != g.ahbImportCache.end()) {
                src = it->second;
                srcFromCache = true;
            }
        }
        if (!srcFromCache) {
            const int rc = importAhbImage(g.vk, ahb, src);
            if (rc != kOk) {
                LOGW("importAhbImage failed rc=%d", rc);
                AHardwareBuffer_release(ahb); // release queue-enqueue ref
                continue;
            }
            // Cache the import; acquire an extra ref so the cache outlives the frame.
            AHardwareBuffer_acquire(ahb);
            if (g.ahbImportCache.size() >= State::kAhbCacheMax) {
                auto oldest = g.ahbImportCache.begin();
                destroyAhbImage(g.vk, oldest->second);
                AHardwareBuffer_release(oldest->first);
                g.ahbImportCache.erase(oldest);
            }
            g.ahbImportCache[ahb] = src;
        }

        // Framegen tracks an internal frameIdx and treats inImg_0 as the
        // "current" frame when frameIdx % 2 == 0, inImg_1 when % 2 == 1
        // (see lsfg-vk-android/framegen/v3.1_include/v3_1/context.hpp:61).
        // We must write the new capture into the slot framegen will consider
        // "current" at the upcoming present — otherwise it computes the optical
        // flow backwards (treating yesterday's frame as "now"), which collapses
        // moving objects like a head or torso.
        const int newSlot = (g.presentsDone % 2 == 0) ? 0 : 1;
        const int oldSlot = 1 - newSlot;
        const bool framegenInputsBusy =
            g.framegenInputsInFlight.load(std::memory_order_acquire);
        const bool pairingResetThisFrame =
            g.pairingResetPending.exchange(false, std::memory_order_acq_rel);

        if (framegenInputsBusy) {
            // Keep this REAL frame passthrough-only. It must not overwrite the
            // images the in-flight generation is still reading.
            g.pairingResetPending.store(true, std::memory_order_release);
        }

        // Bootstrap OR explicit post-bypass re-anchor: seed BOTH slots with the
        // same newest REAL frame. The next REAL frame becomes the first valid
        // interpolation pair. This deliberately breaks continuity across a
        // bypass interval instead of pairing pre-bypass and post-bypass frames.
        if (!framegenInputsBusy && (g.framesCopied == 0 || pairingResetThisFrame)) {
            if (!processRealFrameIntoSlot(src.ahb, g.inSlot[0]) ||
                    !processRealFrameIntoSlot(src.ahb, g.inSlot[1])) {
                LOGW("bootstrap frame input processing failed");
                // Evict from cache on failure — state may be corrupt.
                if (g.ahbImportCache.erase(ahb)) AHardwareBuffer_release(ahb);
                destroyAhbImage(g.vk, src);
                AHardwareBuffer_release(ahb); // release queue-enqueue ref
                continue;
            }
        } else if (!framegenInputsBusy) {
            // If the previous capture targeted this SAME physical slot,
            // g.presentsDone did not advance since then — i.e. the previous
            // capture's generation was bypassed (dynamic bypass, backlog,
            // cpuFallbackGenInFlight, ...) and framegen's internal frameIdx
            // parity never flipped, so newSlot repeats. Left alone,
            // inSlot[oldSlot] still holds whatever was captured before that
            // bypass streak started — arbitrarily many real frames stale.
            // The next time framegen actually runs, it would pair today's
            // capture against that stale frame instead of the one
            // immediately before it: a multi-frame optical-flow jump,
            // which is exactly the warped/skipped-look artifact. Propagate
            // newSlot's about-to-be-overwritten content into oldSlot first
            // so oldSlot always tracks the true immediately-preceding real
            // frame, no matter how many captures were bypassed in between.
            if (g.lastCaptureSlot == newSlot) {
                processRealFrameIntoSlot(g.inSlot[newSlot].ahb, g.inSlot[oldSlot]);
            }
            if (!processRealFrameIntoSlot(src.ahb, g.inSlot[newSlot])) {
                LOGW("frame input processing failed");
                if (g.ahbImportCache.erase(ahb)) AHardwareBuffer_release(ahb);
                destroyAhbImage(g.vk, src);
                AHardwareBuffer_release(ahb); // release queue-enqueue ref
                continue;
            }
        }

        g.lastCaptureSlot = newSlot;
        g.framesCopied++;
        const bool firstRealFrame = (g.framesCopied == 1);

        // Cache owns the AhbImage (both hit and miss paths); only release
        // the per-frame AHB ref that was acquired in pushFrame.
        if (srcFromCache) {
            g.cacheHits.fetch_add(1, std::memory_order_relaxed);
        } else {
            g.cacheMisses.fetch_add(1, std::memory_order_relaxed);
        }
        AHardwareBuffer_release(ahb); // release queue-enqueue ref

        // PROFILE: input copy phase done.
        const auto tCopyDone = State::Clock::now();

        // CPU frame map: this pair is strictly
        //   REAL(previous), GEN[0], GEN[1] ... GEN[N-1], REAL(current).
        // The logical sequence is never inferred from completion order.
        const uint64_t generationPrevRealSequence = g.lastRealSequence;
        const int64_t generationPrevRealTimestampNs = g.lastRealCaptureTimestampNs;

        // Chronological output transaction:
        //   REAL(previous), GEN(previous,current)..., REAL(current)
        //
        // The previous implementation posted REAL(current) before the
        // interpolation for (previous,current), yielding:
        //   REAL(previous), REAL(current), GEN(previous,current)...
        // Keep the current REAL pending until this pair's generated outputs
        // have been published (or generation is skipped/invalidated).
        auto postCurrentReal = [&]() -> bool {
            const bool posted = framegenInputsBusy
                ? blitOutputToWindow(src)
                : blitOutputToWindow(g.inSlot[newSlot]);
            if (posted) {
                const uint64_t postTimeNs = std::chrono::duration_cast<
                    std::chrono::nanoseconds>(
                    State::Clock::now().time_since_epoch()).count();
                if (postTimeNs > pendingFrame.captureTimestampNs) {
                    prof.captureToDisplayNs +=
                        static_cast<int64_t>(postTimeNs - pendingFrame.captureTimestampNs);
                    prof.blitCount++;
                }
                // REAL is the authoritative anchor for the next pair.
                g.lastRealSequence = pendingFrame.sequence;
                g.lastRealCaptureTimestampNs = pendingFrame.captureTimestampNs;
            }
            return posted;
        };

        // AI backend takes priority over the LSFG shader path when it's
        // loaded and active — they're mutually exclusive per session
        // (initRenderLoop only stands up one or the other; see below).
        // Dynamic bypass is evaluated AFTER the real frame has been copied.
        // It never removes a capture from the queue: the current real frame is
        // always posted, while only the expensive interpolation step may be
        // skipped when measured load/backlog is too high.
        const bool autoLatencyBypass = g.autoLatencyBypassActive.load(std::memory_order_relaxed);

        int64_t captureIntervalNs =
            g.emaCaptureIntervalNs.load(std::memory_order_relaxed);
        if (pendingFrame.captureTimestampNs > 0) {
            const int64_t prevCapture =
                g.lastProcessedCaptureTimestampNs.exchange(
                    pendingFrame.captureTimestampNs, std::memory_order_relaxed);
            if (prevCapture > 0 && pendingFrame.captureTimestampNs > prevCapture) {
                const int64_t interval = pendingFrame.captureTimestampNs - prevCapture;
                if (interval > 0 && interval < 2'000'000'000LL) {
                    const int64_t old = g.emaCaptureIntervalNs.load(std::memory_order_relaxed);
                    const int64_t next = old <= 0 ? interval : (old * 7 + interval) / 8;
                    g.emaCaptureIntervalNs.store(next, std::memory_order_relaxed);
                    captureIntervalNs = next;
                }
            }
        }

        size_t queuedBehind = 0;
        {
            std::lock_guard<std::mutex> qlock(g.mu);
            queuedBehind = g.pendingFrames.size();
        }

        // Pixel count of the frame that would actually go through generation
        // this iteration — the resolution framegen/AI was just fed via
        // processRealFrameIntoSlot() above. Queued frames behind it are
        // assumed to be roughly the same resolution (captures don't usually
        // change resolution frame-to-frame), so this same count is reused
        // to project the cost of draining the backlog too.
        const uint64_t currentPixelCount =
            static_cast<uint64_t>(g.inSlot[newSlot].extent.width) *
            static_cast<uint64_t>(g.inSlot[newSlot].extent.height);
        const int64_t emaGenerationNs = projectGenerationCostNs(currentPixelCount);

        // The pressure estimate is entirely measured at runtime. With no
        // history yet, generation is allowed. Once history exists, queued
        // generation work is compared against the current source cadence.
        bool dynamicBypass = false;
        if (emaGenerationNs > 0 && captureIntervalNs > 0) {
            const int64_t queuedWorkNs =
                static_cast<int64_t>(queuedBehind) * emaGenerationNs;
            const int64_t latencyBudgetNs =
                std::max<int64_t>(captureIntervalNs, 1'000'000LL) +
                std::max<int64_t>(captureIntervalNs, 1'000'000LL) / 2;

            // If one generation already costs more than the source cadence,
            // allow generation only while the queue is empty. If queued work
            // is accumulating, temporarily bypass until it drains.
            dynamicBypass =
                (queuedBehind > 0 && emaGenerationNs > captureIntervalNs) ||
                (queuedWorkNs >= latencyBudgetNs);
        }

        if (dynamicBypass) {
            g.dynamicBypassCount.fetch_add(1, std::memory_order_relaxed);
        }

        // REAL-FRAME-FIRST v2:
        // Do not allow interpolation to overtake a capture burst. If new
        // captures are already waiting, the pipeline must drain real frames
        // first. Frame generation is optional idle work only.
        bool newerRealFrameWaiting = false;
        {
            std::lock_guard<std::mutex> qlock(g.mu);
            newerRealFrameWaiting = !g.pendingFrames.empty();
        }

        // LOSSLESS REAL FRAME PROTECTION v3:
        // A framegen job must never start if capture has already produced a
        // newer real frame. The previous scheduler could start interpolation
        // on the popped frame and then a capture burst arrived while GPU work
        // was running. Mark that job as bypass immediately so real captures
        // keep their original cadence instead of being replaced by generated
        // output.
        if (newerRealFrameWaiting) {
            pendingFrame.forceBypass = true;
        }

        const bool generationAllowed =
            (jobEpoch == g.captureEpoch.load(std::memory_order_relaxed)) &&
            !dynamicBypass &&
            !newerRealFrameWaiting &&
            !g.bypass.load(std::memory_order_relaxed) &&
            !autoLatencyBypass &&
            !pendingFrame.forceBypass &&
            !pairingResetThisFrame &&
            // The first capture establishes the REAL anchor only.
            !firstRealFrame &&
            !framegenInputsBusy &&
            // A previous CPU-fallback generation is still draining on the
            // background thread; framegen's output buffers aren't safe to
            // reuse yet. Bypass this capture rather than block waiting for
            // it — same "generation is optional idle work" policy as
            // dynamicBypass above.
            !g.cpuFallbackGenInFlight.load(std::memory_order_acquire);

        // Any bypassed REAL frame is a discontinuity for interpolation. The
        // current REAL frame remains visible, but the next generation must
        // re-anchor from a fresh REAL pair. This covers manual bypass, dynamic
        // pressure bypass, queued-real protection, and the single-frame
        // handoff while a CPU fallback generation is still draining. (The
        // input FPS cap no longer routes frames through this bypass path at
        // all — capped frames are dropped before admission in pushFrame().)
        const bool hasGenerationBackend =
            g.aiLoaded || g.framegenCtxId >= 0;
        if (hasGenerationBackend && !generationAllowed && !pairingResetThisFrame && !firstRealFrame) {
            g.pairingResetPending.store(true, std::memory_order_release);
        }

        // Strong generation preflight: generation is optional work. Start it
        // only when measured history says it can fit safely inside the next
        // REAL-frame interval. This deliberately prefers "do not start" over
        // starting and cancelling halfway through, because a cancellation
        // after submit still costs queue time and can grow capture latency.
        bool generationPreflightOk = generationAllowed;
        if (generationPreflightOk) {
            // Same resolution-projected estimate used for dynamicBypass above
            // — a generation that would be cheap at a lower resolution but
            // expensive at the current one (or vice versa) is judged against
            // what THIS frame actually costs, not a stale flat average.
            const int64_t genEstimateNs = projectGenerationCostNs(currentPixelCount);
            const int64_t sourceIntervalNs = captureIntervalNs > 0
                ? captureIntervalNs
                : 0;
            const int64_t safetyWindowNs = sourceIntervalNs > 0
                ? (sourceIntervalNs * 65) / 100
                : 0;
            if (genEstimateNs > 0 && safetyWindowNs > 0 && genEstimateNs >= safetyWindowNs) {
                generationPreflightOk = false;
            }
            // Never start generation when the queue already contains another
            // REAL frame or when the current pair has no measured timing yet
            // and there is no recent generation history to justify the cost.
            if (queuedBehind > 0 || (sourceIntervalNs <= 0 && genEstimateNs <= 0)) {
                generationPreflightOk = false;
            }
            // On devices without a working exported completion semaphore the
            // only safe way to publish LSFG output is a CPU waitIdle(). That
            // would stall the real-frame lane and can put GEN at the wrong
            // place in the sequence. Prefer zero generation over that cost.
            if (g.framegenCtxId >= 0 &&
                (g.crossDeviceSyncDisabled ||
                 (!g.vk.hasExportableSyncFdSemaphore &&
                  !g.vk.hasExportableOpaqueFdSemaphore))) {
                generationPreflightOk = false;
            }

            // HARD LATENCY GATE: never start a whole xN batch if the current
            // capture age + measured generation cost + the time needed to
            // publish all N-1 generated frames cannot fit under 60 ms.
            // This is CPU admission control; the GPU is only asked to run
            // FrameGen after the CPU has decided the batch is safe.
            if (generationPreflightOk &&
                !generationFitsHardLatency(pendingFrame, genEstimateNs, g.outputs.size())) {
                generationPreflightOk = false;
                g.dynamicBypassCount.fetch_add(1, std::memory_order_relaxed);
                LOGI("CPU latency gate: skip GEN seq=%llu age=%.2fms estGen=%.2fms outputs=%zu",
                     static_cast<unsigned long long>(pendingFrame.sequence),
                     pendingFrame.captureTimestampNs > 0
                         ? (std::chrono::duration_cast<std::chrono::nanoseconds>(
                                State::Clock::now().time_since_epoch()).count() -
                            pendingFrame.captureTimestampNs) / 1'000'000.0
                         : 0.0,
                     genEstimateNs / 1'000'000.0, g.outputs.size());
            }
        }
        const bool runAi = g.aiLoaded && generationPreflightOk;
        const bool runFramegen = !runAi
                                 && g.framegenCtxId >= 0
                                 && generationPreflightOk
                                 && !g.framegenAutoDisabled.load(std::memory_order_relaxed);

        // Frame-time synchronization is always enabled internally, but it is
        // admission-only: it never sleeps the output path. Timing is used by
        // the generation preflight above to decide whether a generated frame
        // can safely fit between two REAL frames.

        if (runFramegen || runAi) {
            const auto generationStartedAt = State::Clock::now();
            std::vector<VkSemaphore> framegenDoneSems;
            std::vector<int> framegenDoneFds;
            // Cross-device GPU->GPU semaphore sync only works if this device
            // can actually export a binary semaphore as OPAQUE_FD (queried
            // once at session init — see hasExportableOpaqueFdSemaphore).
            // Many Android GPUs (Mali in particular) advertise
            // VK_KHR_external_semaphore_fd but only for SYNC_FD, which
            // framegen's fd-import path can't consume. On those devices we
            // fall back to a CPU-side LSFG_*::waitIdle() after presentContext
            // instead of looping forever trying (and failing) to create an
            // exportable semaphore every single frame.
            bool useCpuWaitIdleFallback = runFramegen &&
                (g.crossDeviceSyncDisabled ||
                 (!g.vk.hasExportableSyncFdSemaphore && !g.vk.hasExportableOpaqueFdSemaphore));
            if (runFramegen && !useCpuWaitIdleFallback) {
                framegenDoneSems.resize(g.outputs.size(), VK_NULL_HANDLE);
                framegenDoneFds.resize(g.outputs.size(), -1);
                bool syncReady = true;
                for (size_t i = 0; i < g.outputs.size(); ++i) {
                    if (!createFramegenCompletionSemaphore(framegenDoneSems[i], framegenDoneFds[i])) {
                        syncReady = false;
                        LOGE("Failed to create cross-device framegen completion semaphore");
                        break;
                    }
                }
                if (!syncReady) {
                    for (VkSemaphore sem : framegenDoneSems) {
                        if (sem != VK_NULL_HANDLE)
                            g.vk.fn.vkDestroySemaphore(g.vk.device, sem, nullptr);
                    }
                    // The capability query was positive, but the live driver
                    // rejected semaphore creation/export. Treat that as a
                    // session-level capability mismatch. Do NOT fall back to
                    // CPU waitIdle here: that would hold the REAL lane and can
                    // reorder GEN relative to subsequent REAL frames.
                    g.crossDeviceSyncDisabled = true;
                    LOGW("External semaphore FD creation rejected by driver; disabling framegen for REAL-first mode");
                    postCurrentReal();
                    continue;
                }
            }
            if (runFramegen) {
                // Match framegen's fd import type to the host Vulkan export type.
                const VkExternalSemaphoreHandleTypeFlagBits fdType =
                    g.vk.hasExportableSyncFdSemaphore
                        ? VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT
                        : VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_FD_BIT;
                if (g.performanceMode)
                    LSFG_3_1P::setExternalSemaphoreFdHandleType(fdType);
                else
                    LSFG_3_1::setExternalSemaphoreFdHandleType(fdType);
                g.framegenInputsInFlight.store(true, std::memory_order_release);
                try {
                    // Empty outSem in the fallback case tells framegen there's
                    // nothing to signal; we synchronize below via waitIdle()
                    // instead.
                    if (g.performanceMode)
                        LSFG_3_1P::presentContext(g.framegenCtxId, /*inSem*/ -1, framegenDoneFds);
                    else
                        LSFG_3_1::presentContext(g.framegenCtxId, /*inSem*/ -1, framegenDoneFds);
                } catch (const std::exception &e) {
                    g.framegenInputsInFlight.store(false, std::memory_order_release);
                    handleFramegenException("presentContext", e);
                    // Imported FDs are owned/closed by framegen. Keep the host
                    // semaphore handles alive until session teardown on failure.
                    for (VkSemaphore sem : framegenDoneSems) {
                        if (sem != VK_NULL_HANDLE)
                            g.framegenCompletionTickets.push_back({sem, VK_NULL_HANDLE});
                    }
                    postCurrentReal();
                    continue;
                }
            }
            // PROFILE: presentContext returned (CPU-side; the GPU work is
            // still pending on framegen's queue). For the AI backend this
            // timestamp doesn't mean much on its own — the actual ncnn
            // compute happens below and lands in the waitIdle bucket — but
            // splitting it out isn't worth a second profiling code path.
            const auto tPresentDone = State::Clock::now();

            if (runFramegen) {
                // Framegen's internal frameIdx already advanced the instant
                // presentContext() returned — that's what determines which
                // inSlot is "current" next time, not when the GPU work (or
                // our wait for it) finishes. Advance our mirror here, in
                // lockstep with the call, instead of after the blit/wait
                // below. This also keeps slot bookkeeping correct when the
                // CPU-fallback branch below hands the wait off to a
                // background thread: the next loop iteration must see the
                // updated parity immediately, not whenever that thread gets
                // scheduled.
                g.presentsDone++;
            }

            if (useCpuWaitIdleFallback) {
                // No exportable semaphore was signaled, so ordinarily we'd
                // block here on framegen's own device until this present's
                // GPU work is done. That block used to happen inline on this
                // worker thread — the SAME thread that dequeues and posts
                // real captured frames. A slow waitIdle() (weak GPU, thermal
                // throttling, a heavy multiplier) stalled real-frame
                // ingestion for its entire duration: captures piled up in
                // g.pendingFrames and came out the other side later than
                // they needed to, even though "real frame first" already
                // posts the current capture before this point.
                //
                // Hand the wait (and the generated-output blit that depends
                // on it) off to the persistent genWaitThread instead of
                // doing it inline, so this worker returns to
                // g.pendingCv.wait() immediately and keeps servicing
                // captures at full rate. Only one such job may be in flight
                // at a time — cpuFallbackGenInFlight blocks a new
                // presentContext() from starting until this one's outputs
                // have been consumed, since framegen reuses those output
                // buffers in place and a second present would race the GPU
                // write against our read. That gate also means genWaitThread
                // is always idle (parked on genCv) by the time we get here,
                // so this is just a quick handoff, not a wait.
                g.cpuFallbackGenInFlight.store(true, std::memory_order_release);
                {
                    std::lock_guard<std::mutex> genLock(g.genMu);
                    g.genJobPerfMode = g.performanceMode;
                    g.genJobStartedAt = generationStartedAt;
                    g.genJobEpoch = pendingFrame.captureEpoch;
                    g.genJobPixelCount = currentPixelCount;
                    g.genJobPublish = false;
                    g.genJobPending = true;
                }
                g.genCv.notify_one();

                // No exportable completion path means we cannot publish GEN
                // without waiting for waitIdle(). Never make REAL wait for
                // that fallback. Let the background worker drain the GPU work
                // and discard its generated outputs.
                postCurrentReal();
                g.pairingResetPending.store(true, std::memory_order_release);
                continue;
            }
            // Cross-device GPU→GPU synchronization: framegen signals one
            // exportable semaphore per generated output. The host queue consumes
            // that semaphore in blitOutputToSwapchain(), so the CPU never waits
            // for the framegen device here.
            const auto tWaitIdleDone = State::Clock::now();
            if (!runFramegen) {
#ifdef LSFG_HAVE_NCNN
                // AI path: the model inference itself is Vulkan/GPU-only.
                // The bundled ncnn bridge still uses host staging buffers to
                // exchange RGBA8 frames with AHardwareBuffer.
                // oldSlot/newSlot mirror the ping-pong bookkeeping just above
                // (newSlot = the capture just copied in this iteration).
                const int oldSlot = 1 - newSlot;
                if (!runAiInterpolate(oldSlot, newSlot,
                                       g.inSlot[0].extent.width, g.inSlot[0].extent.height)) {
                    LOGE("AI backend: runAiInterpolate failed for this frame — preserving REAL frame");
                    postCurrentReal();
                    continue;
                }
#else
                // Unreachable: runAi is always false when built without ncnn.
                postCurrentReal();
                continue;
#endif
            }
            // Record the measured interpolation cost for the next dynamic
            // admission decision. This is an EMA, so it follows thermal/load
            // changes instead of relying on a fixed device-specific threshold.
            //
            // Only the AI backend samples here: runAiInterpolate() above is a
            // synchronous, blocking call, so "now - generationStartedAt" is
            // real work time. For LSFG framegen on the cross-device-semaphore
            // path, presentContext() (already called above) returns almost
            // instantly — it only submits work to framegen's own queue — so
            // sampling here would just measure that near-zero submit call and
            // tell admission control generation is free, letting it keep
            // getting admitted even once the GPU is backlogged. That path's
            // real GPU-side cost is recorded once its last output's
            // completion ticket actually retires — see
            // reapFramegenCompletionTickets() and the isLastOutput-tagged
            // timedBlit() call below.
            if (!runFramegen) {
                const int64_t generationNs = std::chrono::duration_cast<
                    std::chrono::nanoseconds>(
                    State::Clock::now() - generationStartedAt).count();
                recordGenerationCostSample(generationNs, currentPixelCount);
            }

            // Outputs are consumed asynchronously by the host GPU queue.
            // Note: a previous revision auto-disabled framegen here when the
            // GPU pipeline was slower than the source cadence. Removed by
            // design — if the user picks heavy settings (flowScale=1.0 +
            // high multiplier on a weak device) it's their call to live with
            // the resulting frame rate. Silently flipping to passthrough
            // looked like a bug ("framegen stopped after 5s"). The bottom-of-
            // pipeline DEVICE_LOST fallback in presentContext above still
            // protects against unrecoverable driver errors.
            // Accumulator for the actual time spent inside blitOutputToWindow
            // calls. Reset each iteration for the lightweight diagnostics window.
            int64_t blitWorkNsThisFrame = 0;
            // Tracks how many of THIS iteration's blits actually posted (vs.
            // were silently dropped by blitOutputToWindow — no swapchain,
            // device lost, OEM present hiccup, etc). generatedFrames must be
            // incremented by this, not by g.outputs.size(): the old code
            // unconditionally added outputs.size() regardless of whether the
            // blits succeeded, so a run of dropped frames still counted as
            // "generated" on the HUD even though nothing new hit the screen —
            // the displayed "total fps" (driven by postedFrames, which only
            // counts real successes) would fall behind real+generated, and
            // the two numbers stopped matching.
            int postedGeneratedThisIter = 0;
            // Wall-clock (steady_clock, ns since epoch) at which this batch's
            // generation work was submitted. Only attached to the LAST
            // output's ticket below — see FramegenCompletionTicket::
            // submittedAtNs — so the cost sample reflects the time to finish
            // the WHOLE multiplier-1 batch, not just its first-ready output.
            const int64_t generationStartedAtNs = std::chrono::duration_cast<
                std::chrono::nanoseconds>(generationStartedAt.time_since_epoch()).count();
            auto timedBlit = [&blitWorkNsThisFrame, &pendingFrame, &prof, generationStartedAtNs, currentPixelCount]
                    (const AhbImage &out, VkSemaphore framegenDone = VK_NULL_HANDLE,
                     bool sampleGenerationCost = false) {
                const auto t0 = State::Clock::now();
                // blitOutputToWindow() takes g.presentMu internally; no lock
                // needed at this call site (see its definition).
                const bool posted = blitOutputToWindow(out, framegenDone,
                    (sampleGenerationCost && framegenDone != VK_NULL_HANDLE) ? generationStartedAtNs : 0,
                    (sampleGenerationCost && framegenDone != VK_NULL_HANDLE) ? currentPixelCount : 0,
                    /*optionalGenerated=*/true);
                const auto t1 = State::Clock::now();
                blitWorkNsThisFrame += std::chrono::duration_cast<
                    std::chrono::nanoseconds>(t1 - t0).count();
                if (posted) {
                    const uint64_t postTimeNs = std::chrono::duration_cast<std::chrono::nanoseconds>(
                        t1.time_since_epoch()).count();
                    if (postTimeNs > pendingFrame.captureTimestampNs) {
                        const int64_t latNs = static_cast<int64_t>(postTimeNs - pendingFrame.captureTimestampNs);
                        prof.captureToDisplayNs += latNs;
                        updateAutoLatencyBypass(latNs);
                    }
                    prof.blitCount++;
                }
                return posted;
            };

            // Generation must still belong to the newest REAL capture at the
            // moment its outputs are about to be published. If a newer capture
            // arrived while presentContext/AI ran, invalidate this output and
            // keep the newer REAL frame as the visible state.
            const uint64_t generationEpoch = pendingFrame.captureEpoch;
            const bool generationStillValid =
                generationEpoch != 0 &&
                generationEpoch == g.captureEpoch.load(std::memory_order_acquire) &&
                !g.bypass.load(std::memory_order_relaxed) &&
                !g.stopRequested;

            if (!generationStillValid) {
                // REAL goes first. Any semaphore-drain wait is queued only
                // after the REAL blit, so stale GEN completion cannot block it.
                postCurrentReal();
                for (VkSemaphore sem : framegenDoneSems) {
                    if (sem != VK_NULL_HANDLE)
                        drainFramegenCompletionSemaphore(sem);
                }
                g.pairingResetPending.store(true, std::memory_order_release);
                LOGI("workerThread: stale generation suppressed jobEpoch=%llu currentEpoch=%llu bypass=%d",
                     static_cast<unsigned long long>(generationEpoch),
                     static_cast<unsigned long long>(
                         g.captureEpoch.load(std::memory_order_relaxed)),
                     g.bypass.load(std::memory_order_relaxed) ? 1 : 0);
                continue;
            }

            // The current REAL frame is the hard end boundary for this pair.
            // Generation was admitted only after the preflight proved it can
            // finish inside the measured source-frame interval. If the runtime
            // invalidated the job anyway, publish REAL immediately and discard
            // GEN; never reorder a generated frame around a REAL frame.
            bool generationPublicationAborted = false;
            for (size_t outputIndex = 0; outputIndex < g.outputs.size(); ++outputIndex) {
                const size_t remainingOutputs = g.outputs.size() - outputIndex;
                const uint64_t currentEpoch =
                    g.captureEpoch.load(std::memory_order_acquire);

                // CPU-side cancellation point. Once a newer REAL arrives, or
                // the current pair can no longer fit under the hard latency
                // budget, stop publishing GEN immediately. Already-submitted
                // GEN work is left to its completion semaphore and is never
                // allowed to block the REAL submission.
                if (currentEpoch != generationEpoch ||
                    g.bypass.load(std::memory_order_relaxed) ||
                    g.stopRequested ||
                    !generationPublicationStillFits(pendingFrame, remainingOutputs)) {
                    generationPublicationAborted = true;
                    break;
                }

                const VkSemaphore done = outputIndex < framegenDoneSems.size()
                    ? framegenDoneSems[outputIndex] : VK_NULL_HANDLE;
                const bool isLastOutput = (outputIndex + 1 == g.outputs.size());

                // CPU frame map: GEN i is assigned between the two REAL
                // timestamps, never by completion order. For x2/x3/x4...x8,
                // i=0..N-2 maps to fractional positions 1/N..(N-1)/N.
                if (generationPrevRealSequence != 0 &&
                    generationPrevRealTimestampNs > 0 &&
                    pendingFrame.captureTimestampNs > generationPrevRealTimestampNs) {
                    const int64_t spanNs =
                        pendingFrame.captureTimestampNs - generationPrevRealTimestampNs;
                    const int64_t mappedTs =
                        generationPrevRealTimestampNs +
                        (spanNs * static_cast<int64_t>(outputIndex + 1)) /
                        static_cast<int64_t>(g.multiplier + 1);
                    LOGI("CPU frame-map: real=%llu -> gen=%zu/%zu -> real=%llu mappedTs=%.3fms",
                         static_cast<unsigned long long>(generationPrevRealSequence),
                         outputIndex + 1, g.outputs.size(),
                         static_cast<unsigned long long>(pendingFrame.sequence),
                         mappedTs / 1'000'000.0);
                }
                // Generated presentation is optional and therefore uses a
                // zero-timeout WSI acquire. A busy compositor drops GEN rather
                // than adding another 16-33 ms wait to the REAL frame.
                if (timedBlit(g.outputs[outputIndex], done, isLastOutput)) {
                    postedGeneratedThisIter++;
                }
            }

            // REAL is the hard end boundary. Any completion semaphores that
            // were not consumed by a successful GEN blit are drained AFTER
            // REAL submission, so semaphore cleanup can never sit in front of
            // the authoritative frame on the same queue.
            postCurrentReal();
            for (VkSemaphore sem : framegenDoneSems) {
                if (sem != VK_NULL_HANDLE)
                    drainFramegenCompletionSemaphore(sem);
            }
            if (generationPublicationAborted) {
                g.dynamicBypassCount.fetch_add(1, std::memory_order_relaxed);
                LOGI("CPU latency/capture cancellation: seq=%llu publishedGen=%d/%zu",
                     static_cast<unsigned long long>(pendingFrame.sequence),
                     postedGeneratedThisIter, g.outputs.size());
            }

            if (postedGeneratedThisIter > 0) {
                g.generatedFrames.fetch_add(static_cast<uint64_t>(postedGeneratedThisIter),
                    std::memory_order_relaxed);
            }
            // Only the AI path still needs this here — runFramegen already
            // advanced g.presentsDone right after presentContext() above
            // (and the CPU-fallback branch continue's before ever reaching
            // this line), so incrementing it again here would double-count.
            if (!runFramegen) g.presentsDone++;

            // PROFILE: accumulate this frame's segments and emit a summary
            // every kProfileWindow frames. Numbers are averages over the
            // completed window.
            const auto tFrameEnd = State::Clock::now();
            using ns = std::chrono::nanoseconds;

            // Policy: stay on the little/efficiency cluster only. Unlike the
            // previous big-cores-first policy, there is no overload escape
            // hatch here — this worker (and ncnn's host-side threads) never
            // schedule onto the big/performance cluster, even if the
            // CPU-side portion of the frame exceeds the 60 Hz budget below.
            // This is intentionally a one-way trade of headroom for staying
            // off the big cluster; the number is only logged for visibility.
            const int64_t cpuHotPathNs =
                std::chrono::duration_cast<ns>(tCopyDone - frameWorkStartedAt).count() +
                std::chrono::duration_cast<ns>(tPresentDone - tCopyDone).count() +
                blitWorkNsThisFrame;
            constexpr int64_t kCpuBudgetNs = 4'000'000;
            if (cpuHotPathNs > kCpuBudgetNs) {
                LOGI("CPU scheduler: little-core CPU work over budget (%.2f ms); "
                     "staying on little cores by policy",
                     cpuHotPathNs / 1'000'000.0);
            }

            prof.copyNs     += std::chrono::duration_cast<ns>(tCopyDone     - frameWorkStartedAt).count();
            prof.presentNs  += std::chrono::duration_cast<ns>(tPresentDone  - tCopyDone).count();
            prof.waitIdleNs += std::chrono::duration_cast<ns>(tWaitIdleDone - tPresentDone).count();
            prof.blitNs     += blitWorkNsThisFrame;
            prof.totalNs    += std::chrono::duration_cast<ns>(tFrameEnd - frameWorkStartedAt).count();
            prof.samples++;
            if (prof.samples >= kProfileWindow) {
                const double n = static_cast<double>(prof.samples);
                const double avgWaitIdleMs = (prof.waitIdleNs / n) / 1'000'000.0;
                const double avgWallEndMs  = (prof.totalNs    / n) / 1'000'000.0;
                const double avgQueueMs    = (prof.queueNs    / n) / 1'000'000.0;
                const double avgLatencyMs  = prof.blitCount > 0 ? (prof.captureToDisplayNs / static_cast<double>(prof.blitCount)) / 1'000'000.0 : 0.0;
                const uint64_t hits = g.cacheHits.exchange(0, std::memory_order_relaxed);
                const uint64_t misses = g.cacheMisses.exchange(0, std::memory_order_relaxed);
                LOGW("frame profile (avg over %u): copy=%.2fms present=%.2fms waitIdle=%.2fms blitWork=%.2fms wallEnd=%.2fms queue=%.2fms latency=%.2fms cache_hits=%llu cache_misses=%llu (gen=%d extra)",
                     prof.samples,
                     (prof.copyNs / n)     / 1'000'000.0,
                     (prof.presentNs / n)  / 1'000'000.0,
                     avgWaitIdleMs,
                     (prof.blitNs / n)     / 1'000'000.0,
                     avgWallEndMs,
                     avgQueueMs,
                     avgLatencyMs,
                     static_cast<unsigned long long>(hits),
                     static_cast<unsigned long long>(misses),
                     g.multiplier);
                // Publish the closed profiling window for the UI / diagnostics.
                g.profileSnapshotCopyNs.store(prof.copyNs, std::memory_order_relaxed);
                g.profileSnapshotPresentNs.store(prof.presentNs, std::memory_order_relaxed);
                g.profileSnapshotWaitIdleNs.store(prof.waitIdleNs, std::memory_order_relaxed);
                g.profileSnapshotBlitNs.store(prof.blitNs, std::memory_order_relaxed);
                g.profileSnapshotTotalNs.store(prof.totalNs, std::memory_order_relaxed);
                g.profileSnapshotQueueNs.store(prof.queueNs, std::memory_order_relaxed);
                g.profileSnapshotLatencyNs.store(prof.captureToDisplayNs, std::memory_order_relaxed);
                g.profileSnapshotBlitCount.store(prof.blitCount, std::memory_order_relaxed);
                g.profileSnapshotSamples.store(prof.samples, std::memory_order_relaxed);
                prof = ProfileAccum{};
            }
        } else {
            // No interpolation was admitted for this capture. Frame Time Sync
            // may pace the REAL output, but never drops or waits on GEN.
            postCurrentReal();
        }


    }
}

} // namespace

int initRenderLoop(const char *cacheDir, const RenderLoopConfig &cfg) {
    std::lock_guard<std::mutex> lock(g.mu);
    if (g.initialized) return kRenderLoopAlreadyInit;

    // multiplier in the prefs is the *total* output rate factor (2x = 60fps from 30fps);
    // framegen's generationCount is how many *extra* frames to interpolate per input
    // pair. So generationCount = multiplier - 1, and we allocate that many output AHBs.
    // (Mirrors lsfg-vk-android/src/context.cpp:80-81,101.)
    const int totalMult = cfg.multiplier > 1 ? cfg.multiplier : 2;
    g.multiplier = totalMult - 1;  // generationCount = N extra frames per pair
    g.performanceMode = cfg.performance;
    g.framegenFp16 = cfg.framegenFp16;
    g.hdr = cfg.hdr;
    // flowScale on the prefs slider is "0.25..1.0" in user-friendly form, but
    // framegen wants the reciprocal (Linux passes 1.0f / conf.flowScale at
    // src/context.cpp:101). Larger user value = finer flow grid = better quality.
    const float userFlow = (cfg.flowScale >= 0.25f && cfg.flowScale <= 1.0f) ? cfg.flowScale : 1.0f;
    g.flowScale = 1.0f / userFlow;
    g.generatedFrames.store(0, std::memory_order_relaxed);
    {
        std::lock_guard<std::mutex> genLock(g.genMu);
        g.genCompletedEpoch = 0;
        g.genJobPending = false;
        g.genJobPublish = true;
    }
    g.postedFrames.store(0, std::memory_order_relaxed);
    g.uniqueCaptures.store(0, std::memory_order_relaxed);
    g.postRingHead.store(0, std::memory_order_relaxed);
    for (auto &slot : g.postRingTimestamps) {
        slot.store(0, std::memory_order_relaxed);
    }
    g.captureRingHead.store(0, std::memory_order_relaxed);
    for (auto &slot : g.captureRingTimestamps) {
        slot.store(0, std::memory_order_relaxed);
    }
    g.pushLogCount.store(0, std::memory_order_relaxed);
    g.blitLogCount.store(0, std::memory_order_relaxed);
    g.lumaGateOpen      = false;
    g.lumaGateDarkCount = 0;
    g.lumaGateStartNs   = 0;
    g.shizukuSampleTimestampNs.store(0, std::memory_order_relaxed);
    g.shizukuFrameTimeNs.store(0, std::memory_order_relaxed);
    g.shizukuPacingJitterNs.store(0, std::memory_order_relaxed);
    g.framesCopied = 0;
    g.presentsDone = 0;
    g.nextCaptureSequence = 1;
    g.lastRealSequence = 0;
    g.lastRealCaptureTimestampNs = 0;
    g.lastCaptureSlot = -1;
    g.pairingResetPending.store(false, std::memory_order_relaxed);
    g.emaGenerationNs.store(0, std::memory_order_relaxed);
    g.emaGenNsPerMegapixel.store(0, std::memory_order_relaxed);
    g.emaCaptureIntervalNs.store(0, std::memory_order_relaxed);
    g.lastProcessedCaptureTimestampNs.store(0, std::memory_order_relaxed);
    g.dynamicBypassCount.store(0, std::memory_order_relaxed);
    // Don't reset g.bypass — the user toggle should persist across re-inits
    // (e.g. when they change multiplier while bypass is on, the new context
    // should also start in bypass).
    // DO reset the framegen auto-disable latch — that flag tracks a driver
    // error tied to the previous device handle, which is being recreated here.
    // Carrying it forward would silently keep framegen off forever after one
    // bad submit, even on a healthy new device.
    g.framegenAutoDisabled.store(false, std::memory_order_relaxed);

    int rc = create_session(g.vk);
    if (rc != kOk) {
        LOGE("create_session failed rc=%d", rc);
        reapFramegenCompletionTickets();
        destroyFramegenCompletionTickets();
        destroy_session(g.vk);
        return kRenderLoopSessionFailed;
    }

    const uint32_t renderW = cfg.width;
    const uint32_t renderH = cfg.height;

    const VkFormat fmt = VK_FORMAT_R8G8B8A8_UNORM;
    for (int i = 0; i < 2; ++i) {
        rc = createAhbImage(g.vk, renderW, renderH, fmt, g.inSlot[i]);
        if (rc != kOk) {
            LOGE("createAhbImage(input %d) failed rc=%d", i, rc);
            shutdownRenderLoop();
            return kRenderLoopBufferAlloc;
        }
        // Input AHBs are populated by the CPU capture lane. Do not submit an
        // initialization/ownership-transfer command here; LSFG owns the Vulkan
        // image state when it consumes the AHB for frame generation.
    }
    // Number of outputs = generationCount, matching framegen "level".
    g.outputs.resize(g.multiplier);
    for (int i = 0; i < g.multiplier; ++i) {
        rc = createAhbImage(g.vk, renderW, renderH, fmt, g.outputs[i]);
        if (rc != kOk) {
            LOGE("createAhbImage(output %d) failed rc=%d", i, rc);
            shutdownRenderLoop();
            return kRenderLoopBufferAlloc;
        }
    }

    g.aiRequested = cfg.aiBackend;
    g.aiLoaded = false;
    g.aiEngine = cfg.aiEngine;
    if (cfg.aiBackend) {
#ifdef LSFG_HAVE_NCNN
        // AI backend replaces the LSFG shader chain entirely for this
        // session — skip initFramegen/createFramegenContext so we don't pay
        // for shader compilation we won't use, and so g.framegenCtxId stays
        // -1 (workerThread's runFramegen check already excludes runAi, but
        // this also keeps the "framegen disabled" return code below honest
        // if AI loading fails and there's no LSFG context to fall back to).
        const char *engineName = (cfg.aiEngine == 1) ? "IFRNet" : "RIFE";
        int rc;
        if (cfg.aiEngine == 1) {
            g.aiIfrnet = new IfrnetInterpolator();
            rc = g.aiIfrnet->load(cfg.aiModelDir, true, /*vulkanDeviceIndex*/ -1, 1);
        } else {
            g.ai = new NcnnInterpolator();
            rc = g.ai->load(cfg.aiModelDir, true, /*vulkanDeviceIndex*/ -1, 1);
        }
        if (rc == kNcnnOk) {
            g.aiLoaded = true;
            LOGI("AI backend (%s) loaded from %s (GPU-only Vulkan)", engineName, cfg.aiModelDir.c_str());
        } else {
            LOGE("AI backend (%s) GPU/Vulkan load() failed rc=%d (modelDir=%s) — "
                 "AI inference will NOT use CPU; falling back to the LSFG Vulkan shader path",
                 engineName, rc, cfg.aiModelDir.c_str());
            if (cfg.aiEngine == 1) {
                delete g.aiIfrnet;
                g.aiIfrnet = nullptr;
            } else {
                delete g.ai;
                g.ai = nullptr;
            }
        }
#else
        LOGE("AI backend requested but this .so was built without ncnn (LSFG_HAVE_NCNN) — "
             "falling back to LSFG shader path");
#endif
    }

    if (!g.aiLoaded) {
        g.framegenInitOk = initFramegen(cacheDir);
        if (g.framegenInitOk) {
            if (!createFramegenContext()) {
                LOGE("createFramegenContext failed — running in capture-only mode (counter will stay at 0)");
                g.framegenCtxId = -1;
            }
        } else {
            g.framegenCtxId = -1;
        }
    } else {
        g.framegenInitOk = false;
        g.framegenCtxId = -1;
    }

    g.initialized = true;



    g.stopRequested = false;
    g.worker = std::thread(workerThread);
    g.genStopRequested = false;
    g.genJobPending = false;
    g.genJobPublish = true;
    g.genWaitThread = std::thread(genWorkerThread);
    // Do NOT build the swapchain here. At initContext time the overlay's
    // Surface is still owned by the mirror VirtualDisplay producer — it's
    // only detached when setLsfgMode() runs (which retargets the VD to the
    // ImageReader). Creating the swapchain before that point races against
    // ANativeWindow's single-producer rule. The first blitOutputToWindow
    // call after LSFG mode is live builds it lazily.
    LOGW("Render loop initialised: capture=%ux%u render=%ux%u totalMult=%dx (gen=%d extra) flowScale=%.2f(internal=%.2f) hdr=%d perf=%d ctxId=%d",
         cfg.width, cfg.height, renderW, renderH, totalMult, g.multiplier,
         userFlow, g.flowScale,
         (int)g.hdr, (int)g.performanceMode, g.framegenCtxId);
    // Tell the caller whether framegen is actually running (either backend).
    // If not, Kotlin will keep the overlay up in mirror mode instead of
    // routing the capture through a dead context.
    return (g.framegenCtxId >= 0 || g.aiLoaded) ? kOk : kRenderLoopFramegenDisabled;
}

void setOutputSurface(ANativeWindow *win, uint32_t w, uint32_t h) {
    // g.presentMu, not g.mu — see blitOutputToWindow()'s lock comment. This
    // call synchronizes with blitOutputToWindow()'s g.outWindow/g.swap
    // access, not with the pending-capture queue.
    std::lock_guard<std::mutex> lock(g.presentMu);
    // Always tear down any prior swapchain first — it holds a VkSurfaceKHR
    // which holds an ANativeWindow reference, and the spec requires the
    // surface outlive the swapchain but be destroyed before the window.
    destroySwapchain();
    if (g.outWindow != nullptr) {
        ANativeWindow_release(g.outWindow);
        g.outWindow = nullptr;
    }
    if (win != nullptr) {
        ANativeWindow_acquire(win);
        g.outWindow = win;
        // These dimensions come from the saved physical/native display size
        // captured before any wm size override. They are the PRESENTATION
        // target, not the current logical display size. Explicitly configure
        // the BufferQueue to use this size before creating the Vulkan surface;
        // otherwise Android may keep a fixed currentExtent matching the
        // temporary low-res wm size (for example 480x1068), and Vulkan will
        // allocate a low-res swapchain even though Kotlin requested 1080x2408.
        g.outWidth = w;
        g.outHeight = h;
        g.swapWinW = w;
        g.swapWinH = h;
        if (w > 0 && h > 0) {
            const int32_t bufferFormat = ANativeWindow_getFormat(win);
            const int32_t geomRc = ANativeWindow_setBuffersGeometry(
                win, static_cast<int32_t>(w), static_cast<int32_t>(h), bufferFormat);
            LOGI("Output buffer geometry requested %ux%u (format=%d) rc=%d",
                 w, h, bufferFormat, geomRc);
        }
        // Fresh ANativeWindow — its BufferQueue producer slot is empty until
        // the first ANativeWindow_lock or vkCreateAndroidSurfaceKHR succeeds.
        // Reset the taint so a new window can take the WSI fast path even if
        // a previous one was poisoned for CPU.
        g.windowCpuProducerLocked = false;
        if (kEnableWsiSwapchain && g.initialized) {
            if (createSwapchain()) {
                LOGW("Output surface attached %ux%u native=%p path=WSI", w, h, static_cast<void *>(win));
            } else {
                LOGW("Output surface attached %ux%u native=%p path=GPU/WSI unavailable", w, h, static_cast<void *>(win));
            }
        } else {
            const char *why = !kEnableWsiSwapchain ? "WSI disabled"
                            : !g.initialized        ? "pre-init"
                                                    : "unknown";
            LOGW("Output surface attached %ux%u native=%p path=GPU/WSI unavailable (%s)",
                 w, h, static_cast<void *>(win), why);
        }
    } else {
        g.outWidth = 0;
        g.outHeight = 0;
        g.swapWinW = 0;
        g.swapWinH = 0;
        LOGW("Output surface detached");
    }
}

void pushFrame(AHardwareBuffer *ahb, int64_t timestampNs) {
    if (ahb == nullptr) return;
    const uint32_t pushLogIndex = g.pushLogCount.load(std::memory_order_relaxed);
    if (pushLogIndex < 30) {
        AHardwareBuffer_Desc desc{};
        AHardwareBuffer_describe(ahb, &desc);
        if (g.pushLogCount.fetch_add(1, std::memory_order_relaxed) < 30) {
            LOGW("pushFrame #%u ahb=%ux%u stride=%u fmt=%u usage=0x%llx ts=%lld",
                 pushLogIndex + 1, desc.width, desc.height, desc.stride, desc.format,
                 static_cast<unsigned long long>(desc.usage),
                 static_cast<long long>(timestampNs));
        }
    }

    // Count every arriving capture frame as unique for the HUD's "real fps" metric.
    // We no longer hash pixel content to detect duplicates — the CPU cost of
    // reading back AHardwareBuffer pixels on every frame outweighed the benefit.
    // The OS capture path already throttles delivery to the target app's render
    // rate in practice, so the raw arrival count is a good proxy for real fps.
    g.uniqueCaptures.fetch_add(1, std::memory_order_relaxed);
    {
        const uint64_t nowNs = static_cast<uint64_t>(
            std::chrono::duration_cast<std::chrono::nanoseconds>(
                State::Clock::now().time_since_epoch()).count());
        const uint64_t slot = g.captureRingHead.fetch_add(1, std::memory_order_relaxed)
                              % State::kPostRingSize;
        g.captureRingTimestamps[slot].store(nowNs, std::memory_order_relaxed);
    }

    bool forceBypass = false;
    AHardwareBuffer_acquire(ahb);
    {
        std::unique_lock<std::mutex> lock(g.mu);
        if (!g.initialized) {
            AHardwareBuffer_release(ahb);
            return;
        }
        if (g.stopRequested) {
            AHardwareBuffer_release(ahb);
            return;
        }
        // REAL captures are authoritative. Never evict them here.
        // Backlog pressure disables/cancels optional GEN instead of creating
        // a missing capture that would corrupt the frame-order map.
        // No artificial queue bound: REAL frames stay chronological.
        // The scheduler reacts to backlog by bypassing generation.
        forceBypass = false;

        const uint64_t epoch = g.captureEpoch.fetch_add(1, std::memory_order_relaxed) + 1;
        const uint64_t sequence = g.nextCaptureSequence++;
        g.pendingFrames.push_back(State::PendingFrame{
            .ahb = ahb,
            .queuedAt = State::Clock::now(),
            .captureTimestampNs = timestampNs,
            .forceBypass = forceBypass,
            .captureEpoch = epoch,
            .sequence = sequence,
        });
    }
    g.pendingCv.notify_one();
}

void shutdownRenderLoop() {
    {
        std::lock_guard<std::mutex> lock(g.mu);
        g.stopRequested = true;
    }
    g.pendingCv.notify_all();
    if (g.worker.joinable()) g.worker.join();
    // genWorkerThread touches g.vk/framegen state via LSFG_*::waitIdle() and
    // blitOutputToWindow(), so it must be stopped and joined before the
    // teardown below runs, or it can use those objects after they're
    // destroyed. `worker` above is already joined, so no new job can be
    // posted to genWaitThread from this point on.
    {
        std::lock_guard<std::mutex> genLock(g.genMu);
        g.genStopRequested = true;
    }
    g.genCv.notify_all();
    if (g.genWaitThread.joinable()) g.genWaitThread.join();
    g.cpuFallbackGenInFlight.store(false, std::memory_order_relaxed);

    {
        std::lock_guard<std::mutex> lock(g.mu);
        for (auto &pending : g.pendingFrames) {
            if (pending.ahb) AHardwareBuffer_release(pending.ahb);
        }
        g.pendingFrames.clear();

        if (g.framegenCtxId >= 0) {
            try {
                if (g.performanceMode) LSFG_3_1P::deleteContext(g.framegenCtxId);
                else                   LSFG_3_1::deleteContext(g.framegenCtxId);
            } catch (...) {}
            g.framegenCtxId = -1;
        }
        if (g.framegenInitOk) {
            try {
                if (g.performanceMode) LSFG_3_1P::finalize();
                else                   LSFG_3_1::finalize();
            } catch (...) {}
            g.framegenInitOk = false;
        }

#ifdef LSFG_HAVE_NCNN
        if (g.ai != nullptr) {
            g.ai->unload();
            delete g.ai;
            g.ai = nullptr;
        }
        if (g.aiIfrnet != nullptr) {
            g.aiIfrnet->unload();
            delete g.aiIfrnet;
            g.aiIfrnet = nullptr;
        }
#endif
        g.aiLoaded = false;
        g.aiRequested = false;
        g.aiEngine = 0;

        // Clear AHB import cache before any Vulkan teardown.
        for (auto &[cachedAhb, img] : g.ahbImportCache) {
            destroyAhbImage(g.vk, img);
            AHardwareBuffer_release(cachedAhb);
        }
        g.ahbImportCache.clear();

        for (auto &o : g.outputs) destroyAhbImage(g.vk, o);
        g.outputs.clear();
        g.aiInputAStaging.clear();
        g.aiInputCStaging.clear();
        g.aiOutputStaging.clear();
        g.aiOutputPtrs.clear();
        for (int i = 0; i < 2; ++i) destroyAhbImage(g.vk, g.inSlot[i]);


        // Destroy swapchain (and its surface) before the underlying ANativeWindow
        // is touched — surface destruction drops its internal window ref.
        // g.outWindow is intentionally kept live so the worker thread can still
        // blit during the next session's shader-compilation window (before
        // setOutputSurface is called).  setOutputSurface always releases the
        // old reference before acquiring the new one, so there is no leak.
        destroySwapchain();
        g.swapWinW = 0;
        g.swapWinH = 0;

        reapFramegenCompletionTickets();
        destroyFramegenCompletionTickets();
        destroy_session(g.vk);
        g.initialized = false;
        g.generatedFrames.store(0, std::memory_order_relaxed);
        g.postedFrames.store(0, std::memory_order_relaxed);
        g.uniqueCaptures.store(0, std::memory_order_relaxed);
        g.postRingHead.store(0, std::memory_order_relaxed);
        for (auto &slot : g.postRingTimestamps) {
            slot.store(0, std::memory_order_relaxed);
        }
        g.captureRingHead.store(0, std::memory_order_relaxed);
        for (auto &slot : g.captureRingTimestamps) {
            slot.store(0, std::memory_order_relaxed);
        }
        g.shizukuSampleTimestampNs.store(0, std::memory_order_relaxed);
        g.shizukuFrameTimeNs.store(0, std::memory_order_relaxed);
        g.shizukuPacingJitterNs.store(0, std::memory_order_relaxed);
        g.emaGenerationNs.store(0, std::memory_order_relaxed);
        g.emaGenNsPerMegapixel.store(0, std::memory_order_relaxed);
        g.emaCaptureIntervalNs.store(0, std::memory_order_relaxed);
        g.lastProcessedCaptureTimestampNs.store(0, std::memory_order_relaxed);
        g.dynamicBypassCount.store(0, std::memory_order_relaxed);
        g.pairingResetPending.store(false, std::memory_order_relaxed);
    }
    LOGI("Render loop shut down");
}

uint64_t getGeneratedFrameCount() {
    return g.generatedFrames.load(std::memory_order_relaxed);
}

uint64_t getPostedFrameCount() {
    return g.postedFrames.load(std::memory_order_relaxed);
}

uint64_t getUniqueCaptureCount() {
    return g.uniqueCaptures.load(std::memory_order_relaxed);
}

double getAverageQueueMs() {
    const int64_t samples = g.profileSnapshotSamples.load(std::memory_order_relaxed);
    if (samples <= 0) return 0.0;
    const int64_t queueNs = g.profileSnapshotQueueNs.load(std::memory_order_relaxed);
    return (static_cast<double>(queueNs) / samples) / 1'000'000.0;
}

double getAverageLatencyMs() {
    const int64_t blitCount = g.profileSnapshotBlitCount.load(std::memory_order_relaxed);
    if (blitCount <= 0) return 0.0;
    const int64_t latencyNs = g.profileSnapshotLatencyNs.load(std::memory_order_relaxed);
    return (static_cast<double>(latencyNs) / blitCount) / 1'000'000.0;
}

uint32_t getProfileWindowNs(int64_t *out, uint32_t cap) {
    // out layout: [copyNs, presentNs, waitIdleNs, blitNs, totalNs, samples].
    // Each value is the SUM over the last completed kProfileWindow window;
    // divide by samples for per-frame averages. Returns 6 when populated, 0
    // when no window has closed yet (samples==0) or when out is too small.
    if (out == nullptr || cap < 6) return 0;
    const int64_t samples = g.profileSnapshotSamples.load(std::memory_order_relaxed);
    if (samples <= 0) return 0;
    out[0] = g.profileSnapshotCopyNs.load(std::memory_order_relaxed);
    out[1] = g.profileSnapshotPresentNs.load(std::memory_order_relaxed);
    out[2] = g.profileSnapshotWaitIdleNs.load(std::memory_order_relaxed);
    out[3] = g.profileSnapshotBlitNs.load(std::memory_order_relaxed);
    out[4] = g.profileSnapshotTotalNs.load(std::memory_order_relaxed);
    out[5] = samples;
    return 6;
}

uint32_t getRecentPostIntervalsNs(int64_t *outIntervalsNs, uint32_t cap) {
    if (outIntervalsNs == nullptr || cap == 0) return 0;
    // Snapshot the ring head. Entries from (head - kPostRingSize) to (head-1)
    // are populated (older ones are overwritten by wrap-around). For intervals
    // we need consecutive pairs, so we can produce at most min(cap, N-1)
    // where N is how many valid entries are present.
    const uint64_t head = g.postRingHead.load(std::memory_order_acquire);
    if (head < 2) return 0;  // need at least 2 timestamps for one interval
    const uint64_t validEntries = std::min<uint64_t>(head, State::kPostRingSize);
    const uint64_t available = validEntries - 1;
    const uint32_t want = static_cast<uint32_t>(std::min<uint64_t>(cap, available));
    // Walk the ring newest-first: slot (head-1), (head-2), ... subtracting
    // successive pairs to produce intervals. Skip the pair if either half
    // is zero (race with concurrent write during startup).
    uint32_t written = 0;
    uint64_t prevTs = g.postRingTimestamps[(head - 1) % State::kPostRingSize]
                          .load(std::memory_order_relaxed);
    for (uint32_t i = 1; i <= want && written < cap; ++i) {
        const uint64_t slot = (head - 1 - i) % State::kPostRingSize;
        const uint64_t ts = g.postRingTimestamps[slot].load(std::memory_order_relaxed);
        if (ts == 0 || prevTs == 0 || prevTs < ts) {
            // Either a torn read (zero) or non-monotonic (wraparound race):
            // stop and return what we have.
            break;
        }
        outIntervalsNs[written++] = static_cast<int64_t>(prevTs - ts);
        prevTs = ts;
    }
    return written;
}

// Average interval spanning the last `kSampleCount` ring entries (or fewer
// while the ring is still filling), converted to fps. Anchored to actual
// event timestamps rather than a fixed wall-clock polling window, so there's
// no window-aliasing for the caller to smooth out — a short averaging span
// is enough to stay stable frame-to-frame.
float fpsFromRing(const std::atomic<uint64_t> (&ring)[State::kPostRingSize],
                   const std::atomic<uint64_t> &ringHead) {
    constexpr uint64_t kSampleCount = 16;
    const uint64_t head = ringHead.load(std::memory_order_acquire);
    if (head < 2) return 0.0f;
    const uint64_t validEntries = std::min<uint64_t>(head, State::kPostRingSize);
    const uint64_t span = std::min<uint64_t>(kSampleCount, validEntries - 1);
    if (span == 0) return 0.0f;
    const uint64_t newestTs = ring[(head - 1) % State::kPostRingSize]
                                  .load(std::memory_order_relaxed);
    const uint64_t oldestTs = ring[(head - 1 - span) % State::kPostRingSize]
                                  .load(std::memory_order_relaxed);
    // Zero means a torn read during startup; oldest >= newest means a
    // wraparound race. Either way, no usable sample this call — the next
    // poll will have fresh data.
    if (newestTs == 0 || oldestTs == 0 || oldestTs >= newestTs) return 0.0f;
    const double elapsedNs = static_cast<double>(newestTs - oldestTs);
    return static_cast<float>(static_cast<double>(span) * 1'000'000'000.0 / elapsedNs);
}

bool getFpsSnapshot(float *out, uint32_t cap) {
    if (out == nullptr || cap < 2) return false;
    const float realFps = fpsFromRing(g.captureRingTimestamps, g.captureRingHead);
    const float totalFps = fpsFromRing(g.postRingTimestamps, g.postRingHead);
    if (realFps <= 0.0f && totalFps <= 0.0f) return false;
    out[0] = realFps;
    out[1] = totalFps;
    return true;
}

void setBypass(bool bypass) {
    // A bypass transition is a hard discontinuity for interpolation. Invalidate
    // every in-flight generation job, keep all REAL captures intact, and force
    // the first REAL frame after resume to become a new pairing anchor.
    const bool previous = g.bypass.exchange(bypass, std::memory_order_acq_rel);
    if (previous != bypass) {
        g.captureEpoch.fetch_add(1, std::memory_order_acq_rel);
        g.pairingResetPending.store(true, std::memory_order_release);
    } else if (bypass) {
        // Repeated ON calls remain harmless but keep the pairing invalidated.
        g.pairingResetPending.store(true, std::memory_order_release);
    }
    LOGI("Framegen bypass=%d; generation invalidated; pairing reset=%d",
         bypass ? 1 : 0,
         g.pairingResetPending.load(std::memory_order_relaxed) ? 1 : 0);
}

// Stub kept for ABI/API compatibility with existing JNI/Kotlin callers.
// The auto-latency-bypass system itself is disabled (see
// updateAutoLatencyBypass above) — this no longer has any effect on the
// render loop. g.autoLatencyBypassActive can no longer be set to true.
void setAutoLatencyBypassEnabled(bool enabled) {
    g.autoLatencyBypassEnabled.store(enabled, std::memory_order_relaxed);
    g.autoLatencyBypassActive.store(false, std::memory_order_relaxed);
    g.latencyProbeSumNs.store(0, std::memory_order_relaxed);
    g.latencyProbeCount.store(0, std::memory_order_relaxed);
    g.autoLatencyBypassLastToggleNs.store(0, std::memory_order_relaxed);
    LOGI("Auto latency bypass: disabled (feature removed, call ignored; requested=%s)",
         enabled ? "enabled" : "disabled");
}

// Stub kept for ABI/API compatibility — see setAutoLatencyBypassEnabled.
void setAutoLatencyBypassThresholdMs(double triggerMs, double recoverMs) {
    const double clampedTrigger = triggerMs > 1.0 ? triggerMs : 1.0;
    const double clampedRecover = (recoverMs > 0.0 && recoverMs < clampedTrigger)
        ? recoverMs : clampedTrigger * 0.7;
    g.autoLatencyBypassTriggerMs.store(clampedTrigger, std::memory_order_relaxed);
    g.autoLatencyBypassRecoverMs.store(clampedRecover, std::memory_order_relaxed);
    LOGI("Auto latency bypass thresholds: %.2fms/%.2fms stored but unused (feature removed)",
         clampedTrigger, clampedRecover);
}

// Always false now — kept for ABI/API compatibility.
bool isAutoLatencyBypassActive() {
    return false;
}

void setPacingParams(float /*emaAlpha*/, float /*outlierRatio*/) {
    // Statistics-only API compatibility shim. No software VSync or frame
    // limiter is applied in the native hot path.
}


void setShizukuTimingEnabled(bool enabled) {
    g.shizukuTimingEnabled.store(enabled, std::memory_order_relaxed);
    if (!enabled) {
        g.shizukuSampleTimestampNs.store(0, std::memory_order_relaxed);
        g.shizukuFrameTimeNs.store(0, std::memory_order_relaxed);
        g.shizukuPacingJitterNs.store(0, std::memory_order_relaxed);
    }
    LOGI("Shizuku timing %s", enabled ? "enabled" : "disabled");
}

void reportShizukuTiming(int64_t timestampNs,
                         int64_t frameTimeNs,
                         int64_t pacingJitterNs) {
    if (!g.shizukuTimingEnabled.load(std::memory_order_relaxed)) return;
    g.shizukuSampleTimestampNs.store(timestampNs, std::memory_order_relaxed);
    g.shizukuFrameTimeNs.store(frameTimeNs, std::memory_order_relaxed);
    g.shizukuPacingJitterNs.store(
        pacingJitterNs >= 0 ? pacingJitterNs : 0,
        std::memory_order_relaxed);
}


} // namespace lsfg_android

