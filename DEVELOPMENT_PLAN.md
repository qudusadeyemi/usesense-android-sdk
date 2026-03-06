# UseSense Android SDK - Development Plan

## Phase 1: Project Foundation (Week 1)
- Gradle multi-module setup (`:sdk` + `:demo`)
- Dependency declarations (CameraX, OkHttp, Retrofit, Coroutines, Moshi)
- Core models: `UseSenseConfig`, `UseSenseError`, `UseSenseResult`, `SessionType`
- Session state machine (`IDLE -> CREATED -> CAPTURING -> UPLOADING -> COMPLETING -> DONE`)
- ProGuard/R8 consumer rules

## Phase 2: API Client (Week 1-2)
- Retrofit service interface for all 4 endpoints
- Request/response DTOs matching the spec exactly
- OkHttp interceptor for session token, nonce, idempotency key, environment headers
- Certificate pinning for `api.usesense.ai`
- Retry interceptor with exponential backoff (immediate, 1s, 3s)

## Phase 3: Camera & Frame Capture (Week 2)
- CameraX integration (front camera, 640x480 min, JPEG output)
- Non-mirrored frame capture (critical: preview mirrored, frames raw)
- Frame buffer with sequential indexing and millisecond timestamps
- JPEG compression at quality 80-85 on background thread pool
- Frame budget enforcement (`max_frames` from server)
- Baseline phase (2000ms) + challenge phase timing

## Phase 4: Challenge System (Week 2-3)
- `FollowDotChallenge`: animated dot with smooth cubic-bezier transitions, waypoint tracking
- `HeadTurnChallenge`: directional instruction UI with step tracking
- `SpeakPhraseChallenge`: phrase display + recording indicator
- `ChallengeResponseBuilder`: assembles waypoint_frames/step_frames with frame indices
- Dual challenge support (visual + audio simultaneously)
- Instructions screen with user-initiated start

## Phase 5: Audio Capture (Week 3)
- MediaRecorder with WebM/Opus (API 29+)
- Fallback strategy for older devices
- Mono, 48kHz, 32-64kbps
- Duration-limited recording matching `audio_challenge.total_duration_ms`

## Phase 6: Signal Metadata (Week 3)
- Device signal collector (model, manufacturer, OS, API level, screen, battery, network)
- Emulator detection heuristics (fingerprint, model, hardware checks)
- Root detection (su binary, Magisk, SuperSU, test-keys, system properties)
- Debug detection (`ApplicationInfo.FLAG_DEBUGGABLE`)
- Play Integrity API token acquisition
- Sensor data sampling (accelerometer + gyroscope at ~2Hz)
- MetadataBuilder assembling the full JSON schema

## Phase 7: Upload & Verdict (Week 3-4)
- Multipart form-data builder (frames[], metadata, audio)
- Idempotency key generation and header attachment
- Upload with 30s timeout, retry with same idempotency key
- Complete endpoint call with 60s timeout
- Verdict parsing into `UseSenseResult`

## Phase 8: UI Components (Week 4)
- `UseSenseActivity` (full-screen verification flow)
- `UseSenseFragment` (embeddable alternative)
- `CameraPreviewView` with mirrored display
- `ChallengeOverlayView` for dot animation, arrows, phrase text
- `InstructionView` with challenge-specific text + start button
- Processing/uploading progress screen
- Result screen (success/failure/pending)
- Theming support (primary color, background, logo, strings)
- Accessibility (TalkBack, contrast, haptics)

## Phase 9: Public API (Week 4)
- `UseSense.initialize()` singleton
- `UseSense.startVerification()` with Activity/Fragment options
- `UseSenseCallback` (onSuccess, onError, onCancelled)
- `VerificationRequest` builder
- Permission handling flow

## Phase 10: Demo App & Testing (Week 5)
- Demo app with enrollment + authentication flows
- Sandbox API key configuration
- Mock server for fully offline demo (Wiremock/local interceptor)
- Visual challenge preview mode (no network, just UI)
- All 14 test cases from spec Section 17.2
- Integration tests for each session state transition
