# PlayTranslate 3.4.1 merged into Noctis

Everything from PlayTranslate lives in Noctis as Gradle modules; nothing was rewritten except the glue below.

| Module | Origin | Notes |
|---|---|---|
| `:playtranslate` | PlayTranslate `app/` | now an Android **library** (`namespace com.playtranslate`), all 1,566 files kept |
| `:mnn`, `:bergamot` | PlayTranslate `mnn/`, `bergamot/` | native OCR/LLM + offline MT; arm64 only, NDK 28.2.13676358 |
| `ptlibs` catalog | PlayTranslate `libs.versions.toml` | `gradle/playtranslate.versions.toml`; shared AndroidX/Material/Lifecycle versions pinned to Noctis' |

## Before the first build
```
./scripts/fetch-playtranslate-native.sh     # MNN, slimt, OpenCL: empty folders in the zip
```
Install NDK `28.2.13676358` and CMake `3.22.1` from the SDK Manager (Noctis' own `:app` keeps NDK 27).

## What changed in Noctis
- `minSdk` 26 → **29** (`AndroidConfig.kt`); PlayTranslate and its natives require it. Android 8/9 devices can no longer install.
- `App.onCreate` calls `PlayTranslateApplication.init(this)`; `App.onTrimMemory` forwards to it.
- **More → PlayTranslate (screen translator)** opens PlayTranslate's home (floating lens, settings, dictionaries, Anki, camera).
- **Reader → long-press page → Translate this page** exports the page and opens PlayTranslate's image review (OCR → translation).
- Manifest: PlayTranslate's services/activities/provider merge in. It has **no launcher icon** of its own.

## What changed in PlayTranslate
- `PlayTranslateApplication` is an `object` (was the manifest `Application`). `YomitanAutoUpdateOrchestrator.maybeRun` takes a `Context`.
- Library manifest no longer sets `<application>` attributes (name, icon, theme, backup rules, `allowAudioPlaybackCapture=false`, network config), so Noctis' apply.
- Self-updater disabled (`BuildConfig.SELF_UPDATE_ENABLED=false`): it would try to install the PlayTranslate APK over Noctis.
- Dropped from the build: R8 mapping archive, catalog URL verification, ARM-TLS APK check (they target the standalone `:app`).

## Licence
PlayTranslate is **GPL-3.0**, Noctis is Apache-2.0. The combined app must be distributed under GPL-3.0 terms.

## Not verified
No Android SDK was available when this was written, so it has **not been compiled**. Likely first-build issues: Kotlin 2.2 (Noctis) vs 2.3 (PlayTranslate upstream), AGP 8.9.1 vs 9.2 APIs, manifest merge conflicts.

## Shared translation (subtitles)
Player → Subtitles → Translation engine → **PlayTranslate**. `SubtitleTranslator` then calls
`TranslationBackendRegistry.translateBatch` (20 lines per call), so subtitles use the same backends, API keys,
cooldowns and downloaded offline models (Bergamot, ML Kit, on-device LLMs) configured in PlayTranslate.
Source language = PlayTranslate's selected game language; target = the subtitle target in Noctis.
If the waterfall fails for a batch, that batch falls back to Google (same as the Claude/Gemini engines).
