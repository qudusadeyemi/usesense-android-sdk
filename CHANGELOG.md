# Changelog

All notable changes to the UseSense Android SDK will be documented in this file.

This project adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

## [4.13.0] - Unreleased

The extra check (LiveSense capture redesign, Phase 2): the approved end-user
storyboard (flows 4 to 6) replaces the old challenge screens. Tested on a
Samsung Galaxy A03 against the demo's mock server with the debug fake camera.

### Added

- **Extra check after the scan.** When the /signals response asks for a step-up
  (round 2, `step_up_v1`), a "One more quick check" sheet (the upload sheet's
  styling) says what is coming in one sentence, and Continue runs the movement
  on the scan screen: full-bleed camera, the ring round the oval in purple.
  Head Turn shows an arrow on the side to turn to ("Turn your head to the left",
  "Now to the right"); Follow Dot shows an amber dot with a dashed trail
  ("Follow the dot with your head"). A green tick ("Done"), then the upload
  sheet, then the result. Replaces the round-2 screens of 4.12.0.
- **Extra check before the scan.** When the session's policy already has a
  challenge, the primer says "Two quick steps" and the movement follows straight
  on from a 2 s scan on the same screen, in the same capture, with no sheet.
- **Speak Digits on the scan screen**: the digits in a pill at the top, a live
  microphone meter under the oval, "Say these numbers"; "We can't hear you /
  Speak a little louder" after 2 s of a flat meter (it holds the timer for at
  most 6 s in all).
- **Microphone needed.** If the microphone is refused or can't record:
  "Open Settings" or "Do a head movement instead". The second calls the new
  `POST /v1/sessions/:id/challenge-fallback` (`{"reason":"microphone_unavailable"}`,
  session-token auth) and carries on with the Follow Dot it returns.
- **Never fails on the device.** The face leaving the oval pauses the movement
  (amber ring; "Move your face to the centre / Then keep turning", or "Show your
  face in the oval / The dot will wait for you") and it carries on when the face
  is back. A missed left or right turn gets one more go with a hint ("Let's try
  that again / Turn a little further to the left"); after that the server decides.
- **Leave verification?** Back (system or the top bar) from the camera to the
  upload asks first: "Leave" ends the verification (`onCancelled`, no resume),
  "Keep going" carries on where it was.
- **Primer**: "Take a quick selfie" with three tips, once per verification (not
  on "Try again", and not inside a Flow, which has its own).
- **Result and error pages** (dark, as the storyboard): You're verified, Thanks,
  we're checking, We couldn't verify you (with "Try again" and tips only while
  retries are allowed), Camera access needed (Open Settings / Try again), The
  camera stopped, You're offline, This check timed out, Something went wrong.
  An upload whose retries ran out shows "Couldn't send your scan" over the
  camera; "Try again" sends the kept scan again.
- **Same checks during a movement as during the scan.** Head Turn, Follow Dot
  and Speak Digits (before the scan or in round 2) run the full quality gate:
  face found, one person, covered face (segmenter), distance with the scan's
  margin, in frame, sharpness, eyes open. A failing check pauses the movement
  with an amber ring, the guidance names it ("Keep your face uncovered / Then
  keep turning"), and no frames are banked. Only pose is relaxed, toward the
  side the step asks for; stillness and the region change watch are off while
  the head is meant to move (Speak Digits keeps stillness).
- **Round 2 carries round 1's signals.** The step-up upload now has the same
  metadata keys as round 1 (`channel_integrity`, `suspicion`,
  `deep_classifier_on_device` when on, `frame_hashes`, the mesh
  `verification_package` or its skip reason, `covered_check`), with the face
  mesh fitted again on round 2's frames.
- **On-device attack detection** (policy `inline_step_up.enabled`, the Sensei
  check "Unusual movement during the scan"): the suspicion engine watches the
  live face from align through the scan; when it triggers, Flash Reflection
  and/or RMAS run on the scan screen in the guided style (plain guidance under
  the oval, amber ring while a prompt waits), before the movement or the
  upload, and their evidence goes in `inline_step_up`. This was never wired to
  a screen on Android before. The engine now uses the policy's threshold and
  reads every gate frame.
- `UseSenseConfig.showResultScreens` (default `true`): `false` hands the decision
  straight to `onSuccess` with no result page.
- `UseSenseConfig.maxRetries` (default `0`): how many times "Try again" is offered
  after a rejection.
- Debug test rig: `forceScreen` in `usesense_fake_camera.json` holds any storyboard
  screen for its screenshot (the extra-check states come from the real state
  machine). Demo app: launch extras for automated mock runs (`mock_step_up`,
  `mock_challenge`, `mock_decision`, `max_retries`, `show_results`,
  `mock_fallback_status`, `mock_inline_threshold`, `mock_inline_challenge`), the
  mock server answers step-up and challenge-fallback and logs each round's
  metadata keys, and the rig's `forceState: "pass_scan"` passes only the scan.

### Changed

- "Try again" after a rejection is now off by default (it was always shown in
  session mode). Set `maxRetries` to offer it.
- A session with a challenge no longer shows the instructions overlay, the
  baseline badge or the 3-2-1 countdown: it scans first, then runs the movement.
- Remote hosted links (`startEnrollment` / `startVerification`) keep their intro,
  white result and error screens; the new pages are for SDK sessions and Flows.
- `ChallengePresenter` gains `beginDriven()`, `driveStep()` and `endDriven()`
  (with default bodies) so the scan screen can own the challenge's timing.

### Fixed

- Debug fake camera: frames a decoder returns as RGB_565 are converted, so the
  gate sees a face in unfrozen playback.
- **Native crash as the capture opens** (SIGBUS in MediaPipe, seen on a Galaxy
  A03 with the face mesh on, as production policies have it): the session mesh,
  the quality gate's landmarker and the covered-face segmenter built their
  MediaPipe graphs on three threads at once. Graph creation is now one at a
  time, process-wide. Likely present since 4.12.0.
- **A session that ran out on the primer** (15 minutes) was only found out at
  upload, after the whole scan ("This check timed out"). Start now checks the
  session's `expires_at` and, when under 2 minutes are left, quietly gets a
  fresh session first (once per attempt). `isExpired()` reads any ISO 8601
  form of `expires_at`; it used to need exactly three fraction digits.
- `covered_check.final.outcome` said `"skipped"` when the debug test rig forced
  the gate to pass, the value COVER-v1 keeps for a missing model. It now says
  `"rig_forced"`; release builds never take that path.

## [4.12.0] - 2026-11-10

Guided face capture (LiveSense capture redesign, Phase 1). Tested on a Samsung
Galaxy A03 (Android 13): mock captures, a sandbox Flow run to a verdict, hands
over the face before, during and at the end of the scan, and lighting changes.

**Size:** the AAR grows to about 20 MB (4.11.0 was 4.8 MB) because the covered-face
check bundles MediaPipe's multiclass selfie segmentation model (16.4 MB).

### Added

- **Guided face capture (LiveSense capture redesign, Phase 1).** Every session
  now opens on the oval guide, and capture starts by itself once the face is
  found, alone, inside the oval, at the right distance, facing the screen,
  lit, sharp, eyes open and still. Checks run on a live MediaPipe face mesh and
  on the face area only (a bright window behind the subject no longer hides a
  dark face). Light is advice only: it turns on the fill light and gives a
  hint but never holds the scan, so a darker face in ordinary room light is
  not locked out. One hint at a time, after 400 ms of failure and for at least
  1.2 s, also spoken by TalkBack; a haptic tick when every check passes and
  when the scan completes.
- **Pause instead of restart.** Moving pauses the progress ring and resumes it
  when still; banked frames are kept unless the pause lasts over 1.5 s.
- **Screen fill light.** A face that is too dark turns the screen to full
  brightness under a warm white veil before any "too dark" hint.
- **Shutter fallback** after 20 s or 3 dropped scan windows. It starts the same
  scan with the same quality bar.
- **Contour scan** (relief v3.1, capture-test-kit RELIEF-v3.md): lines across
  the screen bulge away from the face centre over a rounded bump the size of
  the face, arching over the forehead and sagging under the chin, with the
  nose, brow and lips showing as gentle bends, and follow the head live.
  Packed lines fade instead of stacking. The camera blurs 1.5 s into the scan. A progress ring runs round
  the oval, and the new "Checking it's you" upload sheet sits over a blurred
  camera with a bar that keeps moving through the server check.
- **Covered-face check** (capture-test-kit COVER-v1.md): a hand, phone or paper
  over the face holds the scan with "Keep your face uncovered", before it
  starts, during it, and in a final check before it finishes. Uses MediaPipe's
  multiclass selfie segmentation (bundled, 16.4 MB; the AAR grows to ~20 MB) on
  its own thread, plus a per-region change watch during the scan. The change
  watch takes its baseline only from a steady face, needs 2 frames in a row on
  one region, gives way to "Hold still" while the face moves, and re-baselines
  after a fill-light change only on a clear face; the fill light is frozen
  during a scan.
- `metadata.covered_check` in the signals upload (COVER-v1 section 3): the
  covered-face check's numbers (model, timings, six-class face split at scan
  start and at the final check, change-watch maxima, trips and drops). Numbers
  only, no images or landmarks; it changes no decision.
- Demo app: **Run flow** in sandbox mode (flow ID and API key typed at runtime).
- `FlowAppearance.capture` (`ring`, `contourStart`, `contourEnd`, `warning`) with
  Sense defaults; a colour below 3:1 contrast on the camera falls back.
- `UseSenseConfig.guidedCaptureEnabled` (default `true`); `false` starts
  capture from the shutter with no gate.
- Debug builds only: a fake camera that plays a video through the capture
  pipeline, for screenshots against the shared capture test kit; without a
  video it keeps the real camera and only forces a state (e.g. holds the scan).

### Fixed

- A crash on MediaPipe's thread when two face landmarkers with different
  outputs ran in one app (static output-stream indices in tasks-vision
  0.20230731). Both now share one output configuration.
- A stalled signals upload was never retried: the 30 s stall monitors failed
  the session long before an attempt timed out (300 s write idle). Each
  attempt now has a 20 s idle timeout and retries (1/2/4 s, same idempotency
  key) count as progress, so they run inside the stall windows.
- The upload's no-progress monitor treated a retry resending from zero as no
  progress.

The frames format, the rest of the metadata and the scoring are unchanged; the
only addition to the upload is `metadata.covered_check`. Server challenges
(follow the dot, head turn, speak phrase) still run when the session asks for
one; the gate only decides when they start.

## [4.11.0] - 2026-11-07

Tested on a Samsung Galaxy A03 (Android 13) in a Flutter release build (R8),
in session mode and in a Flow, against the sandbox API.

### Fixed

- **Release builds no longer crash at the face step.** R8 stripped Flogger,
  which MediaPipe needs, so `Graph.<clinit>` threw an `Error` on the first face
  capture. Consumer rules now keep it (and silence the AutoValue
  `javax.lang.model` warnings), so integrators need no rules of their own; face
  mesh also catches `Throwable` and continues without mesh rather than crashing.
- **The capture quality check never ran.** The frame-capture analyzer closed
  each camera frame before the quality check read it, so no quality report was
  produced: no indicator and no guidance on any hosted capture. Fixed.
- **Branding reached every screen.** The Flows loader, intro glyph, spinners
  and capture screens take the brand colour, and the header shows the org's
  logo alone (the name only when there is no logo), as on iOS.

### Changed

- **Branding precedence: code wins, field by field.** Per-launch and SDK-init
  values beat the dashboard (Flow, then org), which beats the built-in
  defaults; only values that were explicitly set count. Session mode now gets
  the dashboard branding too (it arrives with the session). Debug logging
  (`adb shell setprop log.tag.UseSense DEBUG`) shows the source of each field.
  `BrandingConfig.buttonRadius` is now nullable: unset inherits the dashboard.
- **The capture flow matches iOS.** No intro screen; Flows go primer → face
  guide → capture. The face guide has a close button (cancels), a quality dot
  and guidance, with copy and "I'm Ready" at the bottom. Processing reads
  "Verifying your presence", then "Almost done", with a brand-coloured spinner.
- **The result screen matches iOS.** A raised card under the org's logo
  header, iOS wording ("Verification Successful / Unsuccessful / Under
  Review"), one button in the outcome colour (Continue / Done / Got It) and,
  in session mode only, "Try Again" on a reject. The org redirect URL is no
  longer opened from the result.
- **Behaviour change: the decision reaches your callback when the person taps
  the result button** (or leaves the screen), not when the result first
  appears. This is what lets "Try Again" start a clean attempt, and it is how
  iOS already behaves.
- Inside a Flow there is no "Try Again" on the result: the server settles the
  run when the capture completes, and the Flow's own policy decides retries.

## [4.10.0] - 2026-11-07

Tested on a real budget device (Samsung Galaxy A03, Android 13, 2.5 GB) against
the sandbox API before release.

### Fixed

- **Mesh binding proofs now match the server.** The canonical mesh JSON used
  `p` as an object, the landmark count for `l`, and Kotlin number formatting,
  so every capture from 4.8.0 on failed the server's binding check
  (`mesh_integrity_failed`, tier "binding"). It now matches the server and iOS
  byte for byte.
- **Verdicts always decode.** `pillar_verdicts` arrives as
  `{deepsense, livesense, dedupe}` objects and scores can be fractional. Both
  used to fail decoding, so the app got error 2001 instead of the decision.
  Optional detail blocks now decode leniently.
- **The capture clock starts on the camera's first frame.** On slow cameras
  the capture window used to close before frames arrived (0–13 frames;
  0 ended in `NO_FRAMES_CAPTURED`). The same phone now captures 22–23.
- **Every wait is bounded.** Capture, upload, completion and Flows polling
  have time limits, so a capture can no longer hang silently.
- **Retry resumes from the step that failed.** If only completion failed, the
  frames are not uploaded again.
- **One capture per tap.** "Got it — start challenge" and "I'm Ready" can't
  start a second capture, and a capture round completes exactly once.

### Added

- Error codes `capture_timeout`, `upload_timeout` and `run_timeout`
  (`FlowError`), passed through the Flutter and React Native wrappers as
  strings.

### Changed

- **Error and result screens.** Failures show a branded, centred screen with
  plain-language copy per cause, Retry / Start over / Exit, and the error code
  as a caption, instead of a system dialog with raw server text. The host
  callback fires on Close, not on the failure. Text is centred even when the
  host app doesn't declare `supportsRtl`.

## [4.9.2] - 2026-10-09

### Added

- **Face init-session sends a device binding.** When a flow's face step
  starts, the runner sends the stable signals the server hashes into the device
  fingerprint (`device_binding.components`), taken from the same collector the
  Device Trust step uses. The server then reuses a Device Trust check from
  earlier in the run only if it came from this device, instead of matching on
  platform and device model alone. No Play Integrity request; capped at 1.5 s
  and never blocks the face step.

## [4.9.1] - 2026-10-08

### Fixed

- **The capture screens take the org's brand colour.** Only the header, intro
  icon and two buttons used the brand. These used `@color/usesense_primary`
  and stayed DeepSense Blue inside a branded Flow:
  - the loading and "Finishing up" spinners
  - the camera-permission icon and button
  - the face-guide "Ready" button
  - the challenge instructions icon and "Got it" button
  - the challenge progress bar
  - the head-turn direction circle

  They now take the brand colour, and text on brand-filled buttons uses the
  appearance's `primaryForeground`. Unbranded sessions keep the built-in
  palette. The dark backdrops over the camera stay dark.
- **A Device Trust step without a nonce is re-read once before failing.** The
  server mints the nonce for a client that declares `device_signals_v1`, so a
  device step that arrives without one is re-read to pick it up. Only if it is
  still missing does the run end with `FlowError(UNKNOWN, "Device Trust step is
  missing its nonce")`. Previously it failed straight away. Same rule as the
  iOS SDK now follows.

## [4.9.0] - 2026-10-08

### Added

- **Camera-free Device Trust.** The Flows runner declares `device_signals_v1`
  (`?caps=` on load, `client.capabilities` on advance). A Device Trust step
  then arrives as a `device` capture (`PendingAction.CaptureDevice`): the
  runner shows "Checking your device", collects the same device signals a face
  upload carries minus the camera, microphone and sensor fields, requests a
  Play Integrity token (bounded at 5s, best-effort) and posts them with the
  step's nonce to `/v1/sdk/flow-runs/:id/device-signals`. A stale nonce or an
  already-settled step re-reads the run. Older SDKs keep working: the server
  settles the step from the network alone for them.
- **Server step-up (round 2).** When a server Step-up rule matches the uploaded
  capture, the `/signals` response now carries a `step_up` instruction.
  `FinalizationCoordinator` emits `FinalizationUpdate.StepUpRequired` after
  the upload instead of completing; `UseSenseActivity` and
  `HostedPageActivity` show "One more quick check", run the requested Head
  Turn or Follow Dot on the still-bound camera with a fresh frame buffer
  (capped at the server's `max_frames`), upload it with `?round=2`, and resume
  at COMPLETING. An instruction this SDK can't render is ignored and the
  session completes as before (the server then sends it to review). The field
  is decoded as a loose map, so a shape change can never fail the upload.
- The SDK declares `capabilities: ["step_up_v1"]` on create, exchange and
  Flow `init-session`, and `client_capabilities` in the upload metadata (the
  only place a backend-created or remote session can declare it).

## [4.8.0] - 2026-09-10

### Fixed

- **Android never ran its face mesh pipeline, so no Android session ever
  uploaded a `verification_package`.** `FaceMeshManager.initialize()`,
  `processFrame()` and `OnDevice3DMMFitter.fitFrame()` were declared but never
  called, so the fitter was always empty and the package builder silently
  returned null. Every Android session from every integration scored
  `mesh_absent` on the server, while iOS (which feeds its frames) passed. The
  capture session now starts the MediaPipe landmarker when the server requests
  mesh (`geometric_coherence.dual_path_enabled`), runs each captured frame
  through it off the capture thread one frame at a time, and fits it, with frame
  indices matching the uploaded JPEG hashes. The upload waits at most 5s for
  queued mesh work. A package with fewer than 3 fitted frames is withheld rather
  than sent to fail validation.

### Added

- **`verification_package_skip_reason`** in the upload metadata whenever the
  package is omitted (`no_gc_config`, `mesh_not_requested`,
  `landmarker_not_started`, `landmarker_init_pending`,
  `landmarker_init_failed`, `no_frames_processed`, `no_face_detected`,
  `insufficient_mesh_frames`), plus `face_mesh_init_result` and, on failure,
  `face_mesh_init_error`. The server stores these on `session.mesh_integrity`,
  so a missing package is explained instead of silent.

### Server compatibility

- The UseSense backend applies mesh integrity to Android sessions only from this
  version (`ANDROID_MESH_CAPABLE_FROM = 4.8.0`). Older Android SDKs are scored
  with mesh excluded rather than penalised. From 4.8.0 the organisation's mesh
  integrity policy (absence penalty, failure action) applies as configured.

## [4.7.1] - 2026-08-13

### Fixed

- **The front camera preview was mirrored twice, so it ended up not mirrored at
  all.** CameraX `PreviewView` already mirrors a front-facing preview;
  `HostedPageActivity` then applied `scaleX = -1f` on top, cancelling it. The
  subject turned their head right and watched their image go left. The other
  capture screen, `UseSenseActivity`, never had the manual flip and has always
  looked correct -- the two disagreeing was the tell. iOS is unaffected: it uses
  `AVCaptureVideoPreviewLayer`, whose `automaticallyAdjustsVideoMirroring`
  default mirrors the front camera correctly.

  This is worth more than cosmetics. The head-turn challenge says "Turn your
  head LEFT/RIGHT", and only in a mirrored preview does turning your own right
  move your image to the right of the screen. Frames sent to the server were
  never mirrored, so scoring was unaffected either way.

- **The Flows runner did not pass its white-label down to the capture screens.**
  `FlowsActivity` built the capture engine's `UseSenseConfig` with only an api
  key and base URL, so `EffectiveBranding` fell back to the built-in default
  colour. A subject saw the org's brand colour on the step primer and default
  blue on the consent, loading and capture screens either side of it. The
  resolved appearance, its primary colour and the merged copy are now handed
  through. Both are set deliberately: the capture screens tint from the flat
  `primaryColor`, while the Compose surfaces read the appearance, so passing
  only one leaves half the screens unbranded.

- **Every API call went to a doubled `/v1/v1/` path, so no Android integration
  had ever completed a production verification.** The version lived in two
  places at once: `UseSenseConfig.DEFAULT_BASE_URL` ended in `/v1`, the Flows
  runner appended `/v1` again, and every path in `UseSenseApiService` already
  begins with `v1/`. A signals upload therefore addressed
  `/v1/v1/sessions/:id/signals`, which the server rejects before it reads the
  request body.

  That rejection did not surface as an error. The upload carries megabytes, and
  a large body against an early rejection deadlocks under HTTP/2 flow control:
  the client waits forever for response headers, and because the request never
  completes server-side the request logger never fires either. The subject saw
  "Finalizing Enrollment" spin indefinitely, the integrator saw nothing, and we
  had no server-side trace. Verified in the field on 2026-08-13 by a thread dump
  showing the upload thread parked in `Http2Stream.takeHeaders`.

  The base URL now carries no version and each path keeps its own prefix, which
  is also required by the un-versioned `remote-enrollment/...` and
  `remote-session/...` routes that hang off the same base. A configured base
  that still ends in `/v1` is normalised rather than rejected, so integrators
  who followed the previous documentation are unaffected. Only a *trailing*
  `/v1` is stripped, so the `/functions/v1/` in a self-hosted Supabase base
  survives. `BaseUrlResolutionTest` asserts the fully resolved URLs, not just
  the base, since the bug was only visible once the two halves were combined.

  Servers running the corresponding fix also collapse a duplicate `/v1`, so
  already-shipped builds keep working without an SDK bump.

## [4.7.0] - 2026-08-13

Released to Maven Central without a changelog entry at the time; recorded here.

### Changed

- **Frames are capped at 960px on the longest edge.** `FrameEncoder.bitmapToJpeg`
  is the single choke point both capture paths use, so the cap lives there. The
  legacy path already ran at 640x480 and is unaffected; this matters for the v4
  path, which requests 1280x720. Uses a filtered rescale, since aliasing reads as
  lost sharpness to the server's screen-replay detector.

  960 matches the web and iOS SDKs and the server's sharpness calibration table.
  Keep all four in step.

- **`metadata.json` is gzipped** via `GZIPOutputStream`. The server detects
  compression from the gzip magic bytes and still accepts plain JSON, so older
  servers are unaffected — but deploy the server first.

### Fixed

- **`frames_manifest` reported a hardcoded 640x480** regardless of what was
  captured, which is wrong for the v4 path's 1280x720 capture. Because the server
  scales its screen-replay sharpness thresholds off these values, under-reporting
  the resolution *lowered* those thresholds and made spoof detection more
  permissive. Dimensions are now read from the encoded JPEG header.

- **The signals write timeout was 30s**, with no headroom for a multi-megabyte
  body on a slow uplink — a measured production session managed 14.6 KB/s. Raised
  to 300s for that request.

- **`EventType.UPLOAD_PROGRESS` was never emitted.** The event existed but
  nothing fired it. An OkHttp interceptor now wraps the assembled multipart body
  and reports `bytes_sent`, `bytes_total` and `percent`.

- Removed reliance on `FrameEncoder.jpegToDownscaled`, which was dead code: it
  was never called, so no downscale was happening despite appearances.

## [4.6.7] - 2026-08-05

Patch release: the runner now tells the server how the document was supplied.

### Changed

- **Report the document capture route.** The server tailors failure guidance to
  it: "hold the document still and wait for the camera to focus" is the right
  instruction for a live scan and meaningless to someone who chose a file, since
  the photo already exists and there is nothing left to hold. The ML Kit
  document scanner is reported as `camera`, `ACTION_GET_CONTENT` as `upload`.
  The runner already distinguished them for its own UI; it just never told the
  server.
- `captureMethod` is optional on the wire, so an older server ignores it and a
  runner that omits it makes the server fall back to the step's configured
  capture methods. No coordinated release required.

## [4.6.6] - 2026-08-04

Patch release: an upload that arrives incomplete is no longer reported as an outage.

### Fixed

- **"Verification is temporarily unavailable" for a document that was fine.**
  The upload branch had two outcomes: `provider` -> "temporarily unavailable",
  everything else -> "please retake it". A file that arrived cut short took the
  first path, so a subject holding a perfectly good licence was told to wait out
  an outage that was not happening, and the retry re-sent identical bytes. The
  server now reports `reason: "incomplete"` and carries the instruction in
  `message`; neither old branch fits it, since nothing upstream is wrong and a
  retake changes nothing. Adds the third branch, plumbs `message` through
  `UploadDocumentResponse`, and adds a `documentIncomplete` copy key.
  `too_large` now also prefers the server's message, which is the only place
  the exceeded limit is known.

  Note: the corruption that produced this in production was server-side and is
  already fixed. This release only changes what the subject is told.

## [4.6.5] - 2026-07-31

### Fixed
- **Android verification could hang forever on "Finalizing Enrollment", having sent nothing to the server.** `PlayIntegrityManager.requestIntegrityToken` wrapped Google's `requestIntegrityToken` in a bare `suspendCancellableCoroutine` with no timeout, and `UseSenseSession.uploadSignals` opened with an unbounded `integrityJob?.join()`. Google's API calls Play services and then Google's servers, and in the field it can settle neither listener (stale Play services, signed-out Play Store, mismatched cloud project number, a network that stalls mid round-trip). When that happened the coroutine never resumed, the join never returned, and the whole verification wedged on the finalizing screen *before the first HTTP request was issued* — leaving the session at `created` with no signals and no server-side trace. Both waits are now bounded: 5s on the token request and 3s on the join. Attestation was always best-effort (the launch site says so), and sessions already complete with no token, so timing out costs an optional signal instead of the entire verification.

### Changed
- `SDK_VERSION` now reports the real version. It had drifted to `4.5.0` while the SDK shipped 4.6.4, and the `User-Agent` was stuck further back at `4.1.0`, which made `sessions.sdk_version` useless for identifying which build a customer was running. The User-Agent is now derived from `SDK_VERSION` so the two cannot diverge again.

## [4.6.4] - 2026-07-09

### Fixed
- **Consumers on Kotlin < 2.2 no longer need a `kotlin-stdlib` resolutionStrategy force** (React Native, older native integrators). The SDK enabled `viewBinding` (unused — the UI is Compose), which under AGP 9.1 pulled `androidx.databinding:viewbinding:9.1.0`. That artifact carries a dependency *constraint* forcing `kotlin-stdlib` to 2.2.10 onto every consumer — and a constraint overrides ordinary version pins, so 4.6.3's explicit stdlib pin couldn't win. Disabling the unused `viewBinding` removes the transitive `viewbinding` dependency and its constraint, so consumers resolve the SDK's own `kotlin-stdlib:2.0.21` (metadata readable by Kotlin 1.9). RN Android now builds with only `minSdk 28` on the app side. No public API or runtime change.

## [4.6.3] - 2026-07-09

### Fixed
- **Consumers on Kotlin < 2.2 still couldn't link, even after 4.6.2**, because the SDK transitively pulled `kotlin-stdlib:2.2.10` (metadata 2.2.0) — Gradle resolves the whole consumer graph to that highest version, and Kotlin 1.9 (React Native) can't read it (`Class 'kotlin.Unit' was compiled with an incompatible version of Kotlin`). Now the SDK pins its own `kotlin-stdlib` to `2.0.21` (`kotlin.stdlib.default.dependency=false` + explicit dependency), so consumers resolve a 1.9-readable stdlib automatically. React Native (and other Kotlin 1.9 integrators) no longer need a `resolutionStrategy` force — only `minSdk 28`. No public API or runtime change.

## [4.6.2] - 2026-07-09

### Fixed
- **Consumers on Kotlin < 2.2 could not compile against the SDK** (`Class 'com.usesense.sdk.UseSense' was compiled with an incompatible version of Kotlin. metadata version is 2.2.0, but the compiler … can read up to 2.0.0`). The module compiles with Kotlin 2.2.10, which by default emits metadata 2.2.0 — unreadable by any older toolchain. React Native (even 0.76) ships Kotlin 1.9.x, so `react-native-usesense` on Android was unbuildable. Pinned `apiVersion`/`languageVersion` to `2.0`, so the emitted metadata is readable by Kotlin 1.9+ consumers. No public API or runtime change; Flutter (already on Kotlin ≥2.2) is unaffected.

## [4.6.1] - 2026-07-09

### Fixed
- **Release builds of consumer apps failing at manifest merge on AGP 8.x.** The SDK pulled `org.tensorflow:tensorflow-lite:2.14.0`, whose transitive `tensorflow-lite-api` artifact declares the same `org.tensorflow.lite` namespace as the main artifact. Modern AGP rejects duplicate namespaces during an application module's release manifest merge, so any consumer (native Android, Flutter, React Native) building a `--release` app on a recent AGP failed with `Namespace 'org.tensorflow.lite' is used in multiple modules and/or libraries`. Bumped TensorFlow Lite to `2.16.1` (the `-api` artifact gets its own namespace there; the SDK uses only the core `Interpreter`/`NnApiDelegate` API, unchanged across 2.14–2.16) and removed the unused `tensorflow-lite-support:0.4.4` dependency (which had the same collision with no fixed release). No public API or runtime behaviour change.

### CI
- The demo app is now built in **release** as well as debug (`:demo:assembleRelease`). The strict release manifest merge is the only place transitive duplicate-namespace collisions surface; building only the AAR + debug demo is why the TFLite collision above shipped undetected.

## [4.4.0] - 2026-06-23

Minor release bringing **on-device antispoof + LiveSense v4** to Android, at
parity with the iOS SDK. Both are opt-in and default off, so existing
integrations are unchanged.

### Added

- **`UseSenseConfig.antispoofOnDeviceEnabled`** (default `false`). When enabled,
  the SDK runs the EfficientNet-B0 CelebA-Spoof classifier on captured face
  frames and uploads per-frame spoof probabilities under
  `signals.deep_classifier_on_device`. The model is loaded from app-private OTA
  storage or AAR assets; when the artifact is absent the classifier no-ops and
  the watchtower backend's server-side classifier stays authoritative.
- **`UseSenseConfig.liveSenseV4Enabled`** (default `false`). Opts the session
  into the LiveSense v4 capture flow: sends `x-usesense-sdk-version: v4`, exposes
  a constitutive zoom-motion phase (`UseSenseSession.runZoomPhase` /
  `setCapturePhase`), and tags each frame with its capture phase for the
  server's SfM perspective validator. The org must also have
  `livesense_v4_enabled` in its features map (server returns `v4_not_enabled`
  otherwise).

### Fixed

- **Unblocks the Flutter plugin's Android build.** `usesense_flutter` (>= 2.1.0)
  passes `antispoofOnDeviceEnabled` + `liveSenseV4Enabled` to `UseSenseConfig`,
  which previously existed only on the iOS SDK. Android consumers hit a compile
  error against `ai.usesense:sdk:4.3.x`; these params now exist on Android.

## [4.3.1] - 2026-06-23

Patch release: the Flows client now identifies its platform to the server.

### Fixed

- **Flow capture scored on the web surface.** `FlowsClient` (the
  `/v1/sdk/flow-runs/*` client, including `init-session`) sent no `User-Agent`,
  so the server couldn't tell Android from web at session creation and ran
  DeepSense channel-trust scoring on the web surface. It now sends
  `UseSense-Android-SDK/<SDK_VERSION>`, which the server reads to score flow
  captures on the Android surface from creation.

## [4.3.0] - 2026-06-15

Minor release adding the **Flows runner** (run operator-authored
verification Flows in-app) and **LiveSense v4** zoom capture. Existing
`startVerification` / Sessions integrations are unaffected; Sessions and
Flows coexist.

### Added

- **`UseSenseFlows.run(activity, flowRunId, sdkToken, callback, apiBaseUrl)`**:
  launches `FlowsActivity`, which drives an operator-authored Flow
  (face, document, form, consent, and info steps) to a terminal outcome
  delivered via `FlowsCallback` (`onResult(FlowRunResult)` /
  `onError(FlowError)`). Flows are token-based and do not require
  `UseSense.initialize()`: your backend creates the run via
  `POST /v1/flow-runs` (with `mint_sdk_token: true`) and ships
  `flowRunId` + `sdkToken` to the app.
- **Document capture via ML Kit** (rear-camera document scanner) for a
  Flow's document step, honoring the server-declared capture contract.
- **Form-step SDUI parity** in the Flows runner: typed `FormField` v2
  plus info-action support.
- **LiveSense v4**: chain signer, hardware-backed key, zoom controller,
  and `LiveSenseV4Activity`. v3 verification behaviour is unchanged.

### Changed

- **Reported SDK version** (`DeviceSignalCollector.SDK_VERSION`, sent to
  the backend as `sdk_version`) corrected from `4.1.0` to `4.3.0`. This
  was stale and understated the client version, which matters for Flow
  `min_sdk_version` gating and the `POST /v1/flow-runs` version check
  (`409 sdk_too_old`). The artifact version (`sdkVersion`) is bumped to
  `4.3.0` to match the iOS SDK.

## [4.2.1] - 2026-04-11

Patch release fixing visual centring of terminal-state titles and
making the bundled example app actually runnable. No SDK public API,
ABI, or Kotlin-facing behaviour changes.

### Fixed

#### SDK UI
- **Terminal-screen title/subtitle/caption centring** in
  `activity_usesense.xml` and `activity_usesense_hosted.xml`. The
  success, denied, failure, and blocked screens each declare a
  `TextView` with `android:layout_width="wrap_content"` plus the
  `UseSense.Text.Title`/`.Subtitle`/`.Caption` styles, which carry
  `android:textAlignment="center"`. `wrap_content` made the
  TextView exactly as wide as its longest line, so `textAlignment`
  only centred shorter lines relative to that narrower box — not
  relative to the screen. When a title wrapped (e.g. long
  localised strings, or narrow devices), the wrapped lines ended
  up visually off-centre from the icon above and button below.
  Changed `wrap_content` to `match_parent` with
  `android:paddingHorizontal="24dp"` on every terminal-screen
  title, subtitle, and caption TextView so text now centres within
  the full screen width, single-line and multi-line alike. Same
  fix applied to the hosted flow's `resultTitle`.

#### Example app
- **Example app is now actually buildable.** The example's composite
  build setup (`includeBuild("..")` + dependency substitution)
  interacted badly with AGP 9.x's new "built-in Kotlin" feature,
  causing the Kotlin Android plugin to double-register its `kotlin`
  extension and fail every configuration-time evaluation with
  "Cannot add extension with name 'kotlin'". Every PR to the
  example since AGP was bumped to 9.1.0 has been silently broken.
- **Dropped the composite build.** The example now depends on the
  published `ai.usesense:sdk:4.2.1` from Maven Central, the way a
  real integrator would consume the SDK. Removed
  `includeBuild("..")` and `dependencySubstitution` from
  `example/settings.gradle.kts` and pinned plugin versions in
  `pluginManagement.plugins { }` so Gradle resolves them without
  needing the composite build classpath.
- **Added `android.builtInKotlin=false`** and
  `android.newDsl=false` to `example/gradle.properties`, matching
  the SDK root's gradle.properties, so AGP 9.x doesn't try to
  auto-register a conflicting Kotlin extension.
- **API key is now entered at runtime in the app UI.** The
  hardcoded `sk_sandbox_replace_me` placeholder in
  `ExampleApplication.kt` is gone; `ExampleApplication.kt` itself
  is gone. `MainActivity.kt` now has a masked `OutlinedTextField`
  for the API key with a show/hide toggle, a sandbox/production
  switch, and `SharedPreferences` persistence across launches —
  matching the iOS example's `@AppStorage("apiKey")` pattern. The
  SDK is initialised lazily on first Enroll/Authenticate tap with
  the currently-entered key; re-initialisation correctly unsubs
  the previous event callback so event log entries don't
  accumulate stale listeners.
- **Event subscription timing fix.** The event listener used to be
  wired via a `LaunchedEffect(Unit)` at composable birth, assuming
  `UseSense.initialize()` had already run from `Application.onCreate()`.
  With lazy initialisation, the first verification session's early
  events (`SESSION_CREATED`, `PERMISSIONS_REQUESTED`) were racing
  with the subscription wiring and getting dropped. The
  subscription is now set up synchronously inside the init
  function, before `UseSense.startVerification()` is called, so no
  events are lost.
- **Layout no longer squeezes the result card off-screen.** The
  outer Column now uses `Modifier.verticalScroll(rememberScrollState())`
  and the event log uses `heightIn(min = 200.dp, max = 400.dp)`
  instead of `weight(1f)`. The result card is always visible after
  a session completes, regardless of screen size.

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
