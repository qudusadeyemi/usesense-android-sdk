# Changelog

All notable changes to the UseSense Android SDK will be documented in this file.

This project adheres to [Semantic Versioning](https://semver.org/).

## [4.2.0] - 2026-04-11

Infrastructure, distribution, and tooling release. **No runtime SDK
code changes** — the compiled SDK behaviour is identical to 4.1.0. This
version is cut to bring the Android SDK to infrastructure parity with
UseSense iOS SDK 4.2.x, unlock two zero-auth install paths (Maven
Central and JitPack), and ship source/Dokka artifacts for IDE
navigation.

### Added

#### Distribution
- **Maven Central publishing** via the vanniktech `com.vanniktech.maven.publish`
  plugin (0.34.0) targeting the Sonatype Central Portal. The SDK is now
  published to Maven Central under the same `ai.usesense:sdk:<version>`
  coordinate as GitHub Packages, so integrators just add
  `implementation("ai.usesense:sdk:4.2.0")` with no repository config
  (`mavenCentral()` is on by default in every Android project).
  Signed with the UseSense maintainer GPG key; verifiable via
  `keyserver.ubuntu.com`. The publish step is guarded by a
  `Check Sonatype credentials` gate in `release-android.yml` so the
  rest of the workflow keeps running green even before the Sonatype
  secrets are configured.
- **JitPack support** via `jitpack.yml` at the repo root. Integrators
  can also depend on the SDK through the JitPack repo under a
  different coordinate: add `maven { url = uri("https://jitpack.io") }`
  to `settings.gradle.kts` and `implementation("com.github.qudusadeyemi:usesense-android-sdk:v4.2.0")`.
  The existing GitHub Packages path (`ai.usesense:sdk:4.2.0`) continues
  to work unchanged for teams that prefer it.
- **Sources JAR** alongside the AAR via `withSourcesJar()`, so IDE
  jump-to-definition now surfaces the real Kotlin source instead of
  decompiled bytecode.
- **Dokka HTML API reference** generated at release time and attached
  to the GitHub Release as `usesense-sdk-<version>-dokka.zip`.

#### Tooling and release hygiene
- **ktlint** wired into CI (`:sdk:ktlintCheck`) so future code lands
  against a consistent style baseline.
- **Governance files** in `.github/`: CODEOWNERS, pull request template,
  and issue templates (bug report + feature request) matching the
  iOS SDK repo's conventions.
- **Maintainer notes section** in `CONTRIBUTING.md` documenting the
  release process, where the version lives, how the CI `sed`-override
  works, and the future Maven Central activation path for when the
  Sonatype account is ready.

### Fixed

- **Release workflow now skips republish on duplicate version.** Before,
  re-running `release-android.yml` against an already-published tag
  would hard-fail at the publish step. The workflow now pings the
  GitHub Packages POM endpoint first and skips the publish step if
  the version is already there, matching the idempotent retry
  behaviour we landed on iOS.
- **Release AAR attachment now uses the correct filename.** Before,
  the attached GitHub Release asset was `sdk-release.aar` (the default
  Gradle output name), even though the workflow internally extracted
  a `usesense-sdk-<version>.aar` name. The release step now renames
  the artifact at pack time so the download on the Release page
  matches the documented install snippet in the README.
- **Stale documentation link** `docs.usesense.ai/android` in README's
  "API Reference" section has been rewritten to
  `watchtower.usesense.ai/developer-docs` (the URL target was already
  correct; only the display text was stale).

## [4.1.0] - 2026-04-06

### Breaking Changes
- **minSdk raised from 26 to 28** (Android 9+)
- **Base URL changed** to `https://api.usesense.ai/v1` (Cloudflare Worker proxy)
- **Supabase gateway headers removed** — `apikey` and `Authorization: Bearer` headers are no longer sent; the Cloudflare Worker injects them server-side
- **`gatewayKey` parameter removed** from `UseSenseConfig`
- **Default primary color** changed from `#4f46e5` (indigo) to `#4F7CFF` (DeepSense Blue) per Brand Manual v3.0
- **Environment detection** updated: `sk_prod_*` → PRODUCTION, `sk_sandbox_*` → SANDBOX (previously `sk_*` → SANDBOX)

### Added
- **3D Liveness (Geometric Coherence)**: On-device 3DMM fitting via MediaPipe FaceLandmarker with 468-landmark face mesh, cryptographic HMAC-SHA256 frame-mesh binding proofs, cross-frame consistency scoring, and verification package upload
- **MediaPipe CDN model download**: FaceLandmarker model (~4MB) is downloaded from Google CDN on first use and cached in internal storage — not bundled in the AAR
- **Suspicion Engine**: Client-side presentation attack detection with 4 weighted signals (pose micro-tremor, temporal smoothness, brightness stability, sharpness pattern), rolling 30-frame analysis window, configurable threshold
- **Inline Step-Up challenges**: Flash Reflection (3 random color overlays detecting skin reflection) and RMAS (randomized micro-action sequence with 6 facial actions), 15-second hard timeout, brand-styled overlay UI
- **Screen detection signals**: luminance histogram spread, edge energy ratio, frame luminance CV, color channel uniformity
- **SHA-256 frame hashing**: Every captured frame includes a SHA-256 hash for integrity verification and mesh binding
- **Per-frame luminance collection**: Average luminance computed per frame (64x48 downscale) feeding the suspicion engine
- **Server-side init flow**: `exchangeToken(clientToken)` for integrations where the backend creates a `client_token` via `/v1/sessions/create-token`
- **`x-environment` header**: Sent on all API requests alongside the `env` query parameter
- **Face mesh signals**: Per-frame head pose, eye aspect ratios, and bounding box uploaded in metadata
- **StepUpOverlayView**: Brand-styled UI overlay for inline step-up challenges with intro, flash, RMAS, and completion states
- New API response models: `GeometricCoherenceConfig`, `InlineStepUpConfig`, `GeometricCoherencePolicyConfig`, `ScreenIlluminationConfig`, `MeshIntegrityConfig`
- Expanded `VerdictResponse` with geometric coherence scores, SenSei step-up results, inline step-up results, challenge validation, dedupe analysis, reference match, debug info
- ML Kit Face Detection dependency for RMAS action validation
- ProGuard rules for MediaPipe, ML Kit, and Play Integrity

### Changed
- **SDK version**: `1.17.57` → `4.1.0`
- **Retry strategy**: Network errors now retry 3 times with 1s/2s/4s exponential backoff; 429 respects `Retry-After`; 5xx retries twice with 2s delay; 4xx (except 429) never retries
- **Default capture config**: `max_frames` 65→30, `target_fps` 10→3, `capture_duration_ms` 4500→8000
- **`frames_per_step`**: 3→2
- **Metadata `source` field**: `"direct"` → `"sdk"`
- **`PillarVerdicts`**: Field names changed from `deepsense`/`livesense` to `channel_trust`/`liveness`/`dedupe` (string values, not nested objects)

### Rebranded (Brand Manual v3.0)
- Primary color: `#4F46E5` (indigo) → `#4F7CFF` (DeepSense Blue)
- Success color: `#10B981` → `#00D4AA` (MatchSense Green)
- Error color: `#EF4444` → `#FF6B4A` (brand Critical)
- Warning color: `#F59E0B` → `#FFB84D` (brand Warning)
- Neutral palette: Cool grays → Warm neutrals (n0 `#FFF` through n9 `#0C0B09`)
- Button height: 48dp → 44dp; border radius: 12dp → 10dp
- Card border radius: 16dp → 14dp
- Title letter-spacing: -0.03em; body line-height: 1.6
- All drawable icons updated to brand color equivalents
- Quality indicators use DeepSense Blue / LiveSense Purple
- Risk badges and result screens use brand semantic colors

## [1.0.0] - 2026-03-XX

### Added
- Initial public release
- `UseSense.initialize()` for SDK configuration with API key and environment
- `UseSense.startVerification()` for enrollment and authentication sessions
- `UseSense.startRemoteEnrollment()` for hosted enrollment flows
- `UseSense.startRemoteVerification()` for hosted verification flows
- `UseSense.onEvent()` for real-time session event streaming
- `UseSense.reset()` for clearing SDK state
- `UseSenseCallback` interface for result, error, and cancellation handling
- `UseSenseResult` with session decision (APPROVE / REJECT / MANUAL_REVIEW)
- `UseSenseError` with typed error codes and retry guidance
- Full error code set: camera, network, session, capture, config, and quota errors
- Three challenge types: follow-dot, head-turn, speak-phrase
- CameraX-based frame capture with quality analysis
- Audio capture for voice challenges
- Device signal collection (sensors, emulator detection, root detection)
- Play Integrity API integration for channel trust
- Multipart signal upload with retry and idempotency
- Branding configuration (colors, logo, font, button radius)
- Consumer ProGuard rules for R8/ProGuard compatibility
- GitHub Packages distribution
- Sandbox and production environment support
