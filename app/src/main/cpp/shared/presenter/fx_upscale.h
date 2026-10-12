// Output upscaler for the presenter: compute passes (SGSR-style edge upscale, FSR 1 EASU, Catmull-Rom,
// Lanczos-2/3, Mitchell, anime line push, NIS-style edge-directed upscale) plus an optional sharpen pass (RCAS, CAS or adaptive) that also
// works after a plain blit (nearest / linear / cubic).
#pragma once

#include <stdbool.h>
#include <stdint.h>

#include "../vk_dispatch.h"

#define FX_UP_NEAREST 0u
#define FX_UP_LINEAR 1u
#define FX_UP_CUBIC 2u
#define FX_UP_SGSR 3u
#define FX_UP_FSR1 4u
#define FX_UP_LANCZOS 5u /* Lanczos-2, 4x4 taps, anti-ringing */
#define FX_UP_CATROM 6u  /* Catmull-Rom bicubic, 9 bilinear fetches */
#define FX_UP_NIS 7u     /* edge-directed, NVIDIA-Image-Scaling style */
#define FX_UP_ANIME 8u    /* line push for flat-shaded / anime content (Anime4K-style, no CNN) */
#define FX_UP_MITCHELL 9u /* Mitchell-Netravali bicubic (B = C = 1/3), 4x4 taps */
#define FX_UP_LANCZOS3 10u /* Lanczos-3, 6x6 taps, anti-ringing */
#define FX_UP_MAX_MODE FX_UP_LANCZOS3

/* Sharpen pass that follows the upscale when the strength is > 0. */
#define FX_SHARP_RCAS 0u
#define FX_SHARP_CAS 1u
#define FX_SHARP_ADAPTIVE 2u /* edge-adaptive high-pass, gated on local contrast */
#define FX_SHARP_MAX_METHOD FX_SHARP_ADAPTIVE

/* The JNI layer carries strength and method in one int: bits 0..7 strength (0..100), bits 8..11
 * method (FX_SHARP_*). Plain old values (< 256) therefore still mean "RCAS at that strength". */
#define FX_UP_SHARP_VALUE(packed) (((packed) & 0xFFu) > 100u ? 100u : ((packed) & 0xFFu))
#define FX_UP_SHARP_METHOD(packed) \
    ((((packed) >> 8) & 0xFu) > FX_SHARP_MAX_METHOD ? FX_SHARP_RCAS : (((packed) >> 8) & 0xFu))

#define FX_UP_SLOTS 2u    /* must equal FX_FRAMES_IN_FLIGHT */
#define FX_UP_MAX_SRC 16u /* must be >= FX_MAX_TARGETS */

typedef struct {
    VkImage image;
    VkDeviceMemory memory;
    VkImageView view;
} FxUpImage;

typedef struct FxUpscaler {
    VkDevice device;
    VkPhysicalDeviceMemoryProperties mem;

    /* created once per device */
    bool pipes_ready;
    VkSampler sampler;
    VkDescriptorSetLayout dsl;
    VkPipelineLayout layout;
    VkPipeline easu;
    VkPipeline sgsr;
    VkPipeline rcas;
    /* optional: a driver that rejects one of these only loses that mode (it falls back to a blit) */
    VkPipeline lanczos;
    VkPipeline catrom;
    VkPipeline nis;
    VkPipeline cas;
    VkPipeline anime;
    VkPipeline mitchell;
    VkPipeline lanczos3;
    VkPipeline adaptive;

    /* depend on the output size and on the target views; dropped whenever either changes */
    bool res_ready;
    uint32_t width;
    uint32_t height;
    VkDescriptorPool pool;
    FxUpImage img[FX_UP_SLOTS][2]; /* [slot][0] = A (first pass), [slot][1] = B (sharpen) */
    VkDescriptorSet set_src[FX_UP_MAX_SRC][FX_UP_SLOTS];
    VkDescriptorSet set_rcas[FX_UP_SLOTS];
} FxUpscaler;

/* Pipelines, sampler, layouts. False leaves the upscaler unusable (caller falls back to a blit). */
bool fx_up_create_pipes(FxUpscaler* u, VkDevice device,
                        const VkPhysicalDeviceMemoryProperties* mem);

/* Output-size images + descriptor sets. `src_views` are the target views (GENERAL layout). */
bool fx_up_create_resources(FxUpscaler* u, VkFormat format, uint32_t width, uint32_t height,
                            const VkImageView* src_views, uint32_t src_count);

/* Device must be idle. */
void fx_up_release_resources(FxUpscaler* u);
void fx_up_destroy(FxUpscaler* u);

/* True when `mode` is done by a compute pass (not by vkCmdBlitImage). */
static inline bool fx_up_is_shader_mode(uint32_t mode) {
    return mode == FX_UP_SGSR || mode == FX_UP_FSR1 || mode == FX_UP_LANCZOS
        || mode == FX_UP_CATROM || mode == FX_UP_NIS || mode == FX_UP_ANIME
        || mode == FX_UP_MITCHELL || mode == FX_UP_LANCZOS3;
}

/* Records one upscale of `src_image` (GENERAL layout, src_w x src_h) into the slot's images and
 * returns the image holding the result (out size, GENERAL layout, visible to transfer reads).
 * `mode` is FX_UP_*; `sharp` 0 = no sharpen pass, 1..100 = strength of the pass picked by
 * `sharp_method` (FX_SHARP_*). `blit_filter` is used by the blit-based modes (and as the fallback
 * when a mode's pipeline is missing) before a sharpen pass follows. */
VkImage fx_up_record(FxUpscaler* u, VkCommandBuffer cmd, uint32_t mode, uint32_t sharp,
                     uint32_t sharp_method, uint32_t src_index, uint32_t slot, VkImage src_image,
                     uint32_t src_w, uint32_t src_h, VkFilter blit_filter);
