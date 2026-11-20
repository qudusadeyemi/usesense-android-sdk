# Sense Android SDK

Native Android SDK for human presence verification. Verify real humans, detect deepfakes, and prevent identity fraud with three independent verification pillars.

## Requirements

- Android 9.0+ (API level 28)
- Android Studio Hedgehog (2023.1) or later
- Kotlin 1.9+
- Device with front-facing camera (required for face verification; not for the silent device check)
- Google Play Services (recommended, required for Play Integrity attestation)
- Internet connection (required for model download on first use and API calls)

## Installation

The SDK ships through three channels. Pick whichever fits your build pipeline best:

| Channel | Coordinate | Auth required | Best for |
|---------|-----------|---------------|----------|
| **Maven Central** (recommended) | `ai.usesense:sdk:<version>` | None | Most integrators. Zero-auth, fast, standard Android ecosystem path. |
| **JitPack** | `com.github.qudusadeyemi:usesense-android-sdk:v<version>` | None | Teams that want an alternative to Maven Central, or who need a fallback during a Central outage. |
| **GitHub Packages** | `ai.usesense:sdk:<version>` | PAT with `read:packages` | Teams already standardizing on GitHub Packages for internal dependencies. |

### Gradle (Maven Central) — recommended

Maven Central is on by default in every Android project. Just add the
dependency; no repository configuration needed.

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("ai.usesense:sdk:4.16.0")
}
```

If for some reason `mavenCentral()` has been removed from your
`settings.gradle.kts`, add it back:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
```

### Gradle (JitPack) — alternative zero-auth channel

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

// app/build.gradle.kts
dependencies {
    implementation("com.github.qudusadeyemi:usesense-android-sdk:v4.16.0")
}
```

Note that the JitPack coordinate is `com.github.qudusadeyemi:usesense-android-sdk`,
not `ai.usesense:sdk`. The Kotlin import path `com.usesense.sdk.*`
is identical regardless of which install channel you use; only the
Gradle coordinate differs.

### Gradle (GitHub Packages)

Add the GitHub Packages repository to your `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.github.com/qudusadeyemi/usesense-android-sdk")
            credentials {
                username = providers.gradleProperty("gpr.user").getOrElse(System.getenv("GITHUB_ACTOR") ?: "")
                password = providers.gradleProperty("gpr.token").getOrElse(System.getenv("GITHUB_TOKEN") ?: "")
            }
        }
    }
}
```

Then add to your module-level `build.gradle.kts`:

```kotlin
dependencies {
    implementation("ai.usesense:sdk:4.16.0")
}
```

> **Authentication:** GitHub Packages requires authentication. Add your credentials to `~/.gradle/gradle.properties`:
> ```properties
> gpr.user=YOUR_GITHUB_USERNAME
> gpr.token=YOUR_GITHUB_PERSONAL_ACCESS_TOKEN
> ```
> The token needs the `read:packages` scope. [Create one here](https://github.com/settings/tokens/new?scopes=read:packages).

### Manual Installation

1. Download the AAR from the [latest GitHub Release](https://github.com/qudusadeyemi/usesense-android-sdk/releases/latest)
2. Copy it to your module's `libs/` directory
3. Add to `build.gradle.kts`:

```kotlin
dependencies {
    implementation(files("libs/usesense-sdk-4.16.0.aar"))
}
```

4. Also add the SDK's transitive dependencies (listed in the AAR's POM). This is why Maven Central is the recommended approach.

## Quick Start

```kotlin
import com.usesense.sdk.UseSense
import com.usesense.sdk.UseSenseConfig
import com.usesense.sdk.UseSenseCallback
import com.usesense.sdk.UseSenseResult
import com.usesense.sdk.UseSenseError
import com.usesense.sdk.VerificationRequest
import com.usesense.sdk.SessionType

// 1. Initialize (do this once, e.g. in Application.onCreate())
UseSense.initialize(
    context = applicationContext,
    config = UseSenseConfig(
        apiKey = "your_sandbox_api_key",
    ),
)

// 2. Run a verification session
UseSense.startVerification(
    activity = this,
    request = VerificationRequest(
        sessionType = SessionType.ENROLLMENT,
    ),
    callback = object : UseSenseCallback {
        override fun onSuccess(result: UseSenseResult) {
            Log.d("UseSense", "Decision: ${result.decision}")
            Log.d("UseSense", "Session: ${result.sessionId}")
        }

        override fun onError(error: UseSenseError) {
            Log.e("UseSense", "Error ${error.code}: ${error.message}")
        }

        override fun onCancelled() {
            Log.d("UseSense", "User cancelled verification")
        }
    },
)

// The definitive verdict arrives at your backend via webhook.
// The SDK result is for UI feedback only.
```

`startVerification()` requires an Activity context because the SDK launches a full-screen camera Activity.

## White-labeling (Appearance & Copy)

The verification flow UI is fully customizable through two optional inputs you pass
on a flow run: `appearance` (`FlowAppearance`) and `copy` (`FlowCopy`). Values are
merged **SDK-init > dashboard (org settings) > built-in default**. Every field is
optional; anything you omit keeps the Sense default.

You can set this two ways: in code (below), or no-code via the dashboard's
**Flows → Appearance** tab (saved on your org, delivered to every SDK and the hosted
pages, no redeploy).

Customizable surfaces: colors (plus dark-mode overrides), typography, shape and
button style, logo, background, icons/illustrations, the loader, and every
subject-facing string plus privacy copy.

```kotlin
UseSenseFlows.run(
    context,
    flowRunId,
    sdkToken,
    appearance = FlowAppearance(
        colors = AppearanceColors(primary = "#E4002B"),
        shape = AppearanceShape(buttonStyle = "outline"),
    ),
    copy = FlowCopy(
        result = ResultCopy(successTitle = "You're verified"),
    ),
)
```

Full reference: [`docs/WHITE_LABEL.md` in the web SDK](https://github.com/qudusadeyemi/usesense-web-sdk/blob/main/docs/WHITE_LABEL.md).

## Silent device check (Your KYC)

Keep your own KYC provider (MetaMap, Smile ID, Sumsub or any other) and run a Sense device check next to it. The check is headless: **no UI, no camera, no permission prompts**. Sense scores the device with the same camera-free DeepSense engine as a Device Trust step and links the result to the provider's result through your Your KYC connection (dashboard: Your KYC, level 2 "Results + device check").

What the SDK sends, all without a permission:

- **Attestation (D1):** a Play Integrity token bound to the check's nonce (Standard API `requestHash` = lowercase hex SHA-256 of the `dn_` nonce, Classic API fallback with `nonce` = base64url SHA-256), declared with `play_integrity_nonce_binding: "sha256"`. Sense refuses a token that is not bound to the check.
- **Environment (D2):** emulator, root, debuggable, Play services, security patch, hardware-backed keystore (an AndroidKeyStore key, no prompt).
- **Hardware (D3) and session context (D5):** the same device, screen, GPU, battery, locale and network type fields the SDK sends with a face capture, plus `install_id`, a random per-install id (not a hardware identifier) so Sense can tell one phone from another of the same model.
- **Finish (optional):** the camera inventory from `CameraManager` (ids and characteristics only; no camera is opened).

The server only answers `202 { received: true }`. The device never sees the score or verdict: your server reads them with `GET /v1/device-checks/:id` or from the Your KYC result.

**1. Your server** creates the check (API key with the `flows` permission). Pass `"platform": "android"` so the device is scored as Android:

```bash
curl -X POST https://api.usesense.ai/v1/device-checks \
  -H "x-api-key: sk_your_sandbox_key" \
  -H "content-type: application/json" \
  -d '{ "user_ref": "cust_88211", "platform": "android" }'

# 201 { "deviceCheckId": "dc_...", "token": "dct_...", "nonce": "dn_...", "expiresAt": "..." }
```

Hand `deviceCheckId`, `token` and `nonce` to the app. Never ship your API key to the device. The token works only for this check and expires after 15 minutes.

**2. The app** sends `start` before the provider's SDK opens, passes `deviceCheckId` into the provider's metadata, and sends `finish` after it closes:

```kotlin
import com.usesense.sdk.UseSense
import com.usesense.sdk.devicecheck.DeviceCheck
import com.usesense.sdk.devicecheck.DeviceCheckConfig
import com.usesense.sdk.devicecheck.DeviceCheckResult

class KycActivity : AppCompatActivity() {
    private var deviceCheck: DeviceCheck? = null

    private fun startKyc() {
        lifecycleScope.launch {
            // From your server, which called POST /v1/device-checks.
            val kyc = myBackend.startKyc() // { deviceCheckId, token, nonce }

            val check = UseSense.deviceCheck(
                context = this@KycActivity,
                config = DeviceCheckConfig(
                    deviceCheckId = kyc.deviceCheckId,
                    token = kyc.token,
                    nonce = kyc.nonce,
                ),
            )
            deviceCheck = check

            // 1. Before the provider's SDK opens. Off the main thread, bounded, never throws.
            when (val r = check.start()) {
                is DeviceCheckResult.Sent -> Log.d("KYC", "device signals sent")
                is DeviceCheckResult.Failed -> Log.w("KYC", "device check: ${r.error.code} ${r.error.message}")
            }

            // 2. Run your provider as usual, with the device check id in its metadata
            //    (MetaMap metadata, Smile ID partner params, Sumsub metadata, ...).
            //    Map that field to the Sense field "Device check id" in Your KYC.
            kycProvider.start(
                activity = this@KycActivity,
                metadata = mapOf("device_check_id" to kyc.deviceCheckId),
            )
        }
    }

    // 3. Optional: when the provider's flow closes.
    private fun onKycProviderClosed() {
        lifecycleScope.launch { deviceCheck?.finish() }
    }
}
```

Instead of the metadata field, you can send the same `user_ref` to Sense and to the provider: Sense then links the newest check for that user made within the connection's window (30 minutes by default).

From Java, use the callback variants (the callback runs on the main thread):

```java
DeviceCheck check = UseSense.deviceCheck(context, new DeviceCheckConfig(deviceCheckId, token, nonce));
check.start(result -> {
    if (!result.isSuccess()) Log.w("KYC", "device check failed: " + ((DeviceCheckResult.Failed) result).getError().getCode());
});
```

`UseSense.deviceCheck` does not need `UseSense.initialize()`. When `initialize()` was called, its `baseUrl` and `googleCloudProjectNumber` are used unless `DeviceCheckConfig` sets its own. Each call is bounded by `DeviceCheckConfig.timeoutMs` (default 45 s), signal collection and the retry included.

| `DeviceCheckError.Code` | Server | What to do |
|---|---|---|
| `INVALID_TOKEN` | 401 `invalid_token` | Check you passed the `dct_` token from create. |
| `TOKEN_EXPIRED` | 401 `token_expired` | More than 15 minutes after create: create a new check. |
| `NONCE_MISMATCH` | 400 `nonce_mismatch` | Pass the `dn_` nonce from the same create. |
| `INVALID_REQUEST` | other 400, 413 | Should not happen; report it. |
| `NOT_FOUND` | 404 `not_found` | Unknown `deviceCheckId`. |
| `OUT_OF_ORDER` | 409 `already_started` / `not_started` / `already_finished` | `start` once, then `finish` once, in that order. A second local `start` after one was accepted is refused without a request. |
| `SERVICE_UNAVAILABLE` | 5xx, 429 | Already retried once with backoff. Retry `start` later within the token's life. |
| `NETWORK_ERROR` | none | No connection (already retried once). |
| `TIMEOUT` | none | The call did not finish within `timeoutMs`. |
| `INVALID_CONFIG` | none | The id, token or nonce is malformed; nothing was sent. |
| `CANCELLED` | none | `check.cancel()` was called. |

A failed device check never blocks your provider's flow: run the provider either way. A result with no device check is flagged (by default) by your Your KYC connection. Play Integrity needs Google Play services and your app registered in Play Console; without a token the device is still checked, it just scores without attestation.

## Configuration

### UseSenseConfig

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `apiKey` | `String` | Yes | — | Your API key from the [Sense dashboard](https://watchtower.usesense.ai) |
| `environment` | `UseSenseEnvironment` | No | `AUTO` | `SANDBOX`, `PRODUCTION`, or `AUTO` (detects from key prefix) |
| `baseUrl` | `String` | No | `https://api.usesense.ai/v1` | API base URL (override for staging/testing) |
| `branding` | `BrandingConfig?` | No | `null` | UI customization (colors, logo, fonts) |
| `googleCloudProjectNumber` | `Long` | No | Sense default | Google Cloud project for Play Integrity |

### BrandingConfig

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `displayName` | `String?` | Org setting | Display name shown in UI |
| `logoUrl` | `String?` | Org setting | Logo URL |
| `primaryColor` | `String?` | `"#4F7CFF"` | Primary brand color (hex) |
| `redirectUrl` | `String?` | Org setting | Redirect URL after hosted flows |
| `buttonRadius` | `Int` | `12` | Button corner radius in dp |
| `fontFamily` | `String?` | System default | Custom font family |

### VerificationRequest

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `sessionType` | `SessionType` | Yes | `ENROLLMENT`, `AUTHENTICATION` or `LIVENESS` |
| `externalUserId` | `String?` | No | Your internal user ID |
| `identityId` | `String?` | No | Required for `AUTHENTICATION` (the enrolled identity to verify against). Must not be set for `LIVENESS` |
| `metadata` | `Map<String, Any>?` | No | Custom key-value pairs attached to the session |

## Session Types

### Enrollment

First-time face registration. The system captures the user's face, performs a 1:N duplicate scan across all enrolled identities in your organization, and creates an identity record if approved. The returned `sessionId` and identity information are delivered to your backend via webhook.

```kotlin
UseSense.startVerification(
    activity = this,
    request = VerificationRequest(
        sessionType = SessionType.ENROLLMENT,
        externalUserId = "user_123",
        metadata = mapOf("source" to "onboarding"),
    ),
    callback = object : UseSenseCallback {
        override fun onSuccess(result: UseSenseResult) {
            when {
                result.isApproved -> {
                    // Enrollment succeeded — identity created
                    Log.d("UseSense", "Enrolled: ${result.sessionId}")
                }
                result.isPendingReview -> {
                    // Under manual review — show "verification in progress"
                    Log.d("UseSense", "Review pending: ${result.sessionId}")
                }
                result.isRejected -> {
                    // Enrollment rejected
                    Log.d("UseSense", "Rejected: ${result.sessionId}")
                }
            }
        }

        override fun onError(error: UseSenseError) {
            Log.e("UseSense", "Error ${error.code}: ${error.message}")
            if (error.isRetryable) {
                // Safe to retry the session
            }
        }

        override fun onCancelled() {
            Log.d("UseSense", "User cancelled")
        }
    },
)
```

### Authentication

Returning user claims an existing identity. The system performs 1:1 face verification against the enrolled template, plus a 1:N cross-identity scan to detect identity swapping. The `identityId` must be an identity that was previously enrolled.

```kotlin
UseSense.startVerification(
    activity = this,
    request = VerificationRequest(
        sessionType = SessionType.AUTHENTICATION,
        identityId = "identity_abc123", // required for authentication
        externalUserId = "user_123",
    ),
    callback = object : UseSenseCallback {
        override fun onSuccess(result: UseSenseResult) {
            when {
                result.isApproved -> {
                    // Identity verified — proceed with login/transaction
                }
                result.isPendingReview -> {
                    // Show "verification in progress" state
                }
                result.isRejected -> {
                    // Verification failed — identity mismatch
                }
            }
        }

        override fun onError(error: UseSenseError) {
            Log.e("UseSense", "Error ${error.code}: ${error.message}")
        }

        override fun onCancelled() {
            Log.d("UseSense", "User cancelled")
        }
    },
)
```

### Liveness only

Checks that a real person is in front of the camera, and nothing else. No identity is created, no face is enrolled and no 1:N duplicate search runs, so the same person can pass any number of times. A liveness session costs 1 credit. Do not pass an `identityId`: the SDK returns an `INVALID_CONFIG` error (the server rejects it with a 400).

```kotlin
UseSense.startVerification(
    activity = this,
    request = VerificationRequest(
        sessionType = SessionType.LIVENESS,
        externalUserId = "user_123", // optional, for your own records
    ),
    callback = object : UseSenseCallback {
        override fun onSuccess(result: UseSenseResult) {
            if (result.isApproved) {
                // A live person completed the check
            }
        }
        override fun onError(error: UseSenseError) {
            Log.e("UseSense", "Error ${error.code}: ${error.message}")
        }
        override fun onCancelled() {}
    },
)
```

## Keep the API key off the device (client token)

Your server creates the session with its secret key and hands the app a single-use client token. No API key ships in the app. Mirrors iOS `createSessionWithToken(clientToken:sessionType:)` and the web SDK's `clientToken`.

```bash
# Your server (secret key, never in the app):
curl -X POST https://api.usesense.ai/v1/sessions/create-token \
  -H "x-api-key: sk_..." -H "Content-Type: application/json" \
  -d '{"session_type":"enrollment","platform":"android","external_user_id":"user_123"}'
# 201 { "client_token": "cli_tok_...", "expires_at": "..." }   single-use, 10 minutes
```

```kotlin
// The app: initialize is optional here (when called, its branding, baseUrl and
// capture options apply; the apiKey may be blank).
UseSense.startVerificationWithToken(
    activity = this,
    clientToken = clientTokenFromYourServer,
    sessionType = SessionType.ENROLLMENT, // the type your server passed to create-token
    callback = callback, // same UseSenseCallback, results and errors as startVerification
)
```

The SDK calls `POST /v1/sessions/exchange-token` with the token and then runs the usual capture. Exchange failures arrive on `onError` with the server's code in `error.serverCode`: `token_not_found` (404), `token_expired` (410), `token_already_used` (409). A token is single-use, so a rejected result offers no "Try again": start a new verification with a new token from your server. Anything the session needs (`identity_id`, `reference_image`, `metadata`) goes on `create-token`, not in the app.

## Embed the capture in your own screen (UseSenseFragment)

`UseSenseFragment` runs the same capture, with the same branding (code wins over dashboard), copy and callback, inside your layout instead of a full-screen Activity. Mirrors iOS `UseSenseViewController`.

```kotlin
val fragment = UseSenseFragment.newInstance(VerificationRequest(SessionType.ENROLLMENT))
// or, with no API key on the device:
// val fragment = UseSenseFragment.newInstanceWithToken(clientToken, SessionType.ENROLLMENT)

fragment.setCallback(object : UseSenseCallback {
    override fun onSuccess(result: UseSenseResult) { removeCapture() }
    override fun onError(error: UseSenseError) { removeCapture() }
    override fun onCancelled() { removeCapture() }
})
supportFragmentManager.beginTransaction()
    .replace(R.id.capture_container, fragment, "usesense")
    .commit()
```

- **One callback, then remove it.** The callback fires once: `onSuccess` when the person leaves the result screen (at once with `showResultScreens = false`), `onError`, or `onCancelled` (back press, "Leave", the Fragment removed, the host Activity finishing). Remove the Fragment in the callback.
- **Configuration changes.** Rotation, dark mode or a window resize keep the verification and its session (a client token is never exchanged twice), and fire no callback. The recreated Fragment needs its callback again: find it by tag and call `setCallback`, or make the host Activity (or parent Fragment) implement `UseSenseCallback`, which the Fragment picks up by itself. A result reached while no callback is attached is held and delivered when one is.
- **Back press** is handled while the Fragment is shown: mid-capture it asks before leaving, otherwise it ends the verification as cancelled.
- **Permissions** (camera, and the microphone when a challenge needs it) are requested from the Fragment; nothing to wire up.
- **Layout.** Your screen owns the system bars. Give the container the space you want the capture to fill; full screen works best for the camera. The host must be a `FragmentActivity` (for example `AppCompatActivity`).
- After process death the verification is gone: the Fragment reports `onError` with `serverCode = "verification_interrupted"`; start a new one.

## Remote enrolment and verification results

`startRemoteEnrollment` and `startRemoteVerification` take an optional callback (the two-argument forms still work). As iOS `HostedEnrollmentViewController` / `HostedVerificationViewController`, it hears the outcome once:

```kotlin
UseSense.startRemoteVerification(this, remoteSessionId, object : UseSenseCallback {
    override fun onSuccess(result: UseSenseResult) {
        // result.decision: APPROVE, REJECT, MANUAL_REVIEW, or DISPUTED
        // (UseSenseResult.DECISION_DISPUTED) when the person disputed the action
    }
    override fun onError(error: UseSenseError) {}
    override fun onCancelled() {} // left before the end
})
```

## Handling Results

### UseSenseResult

| Property | Type | Description |
|----------|------|-------------|
| `sessionId` | `String` | Unique session identifier |
| `sessionType` | `String?` | `"enrollment"` or `"authentication"` |
| `identityId` | `String?` | Assigned identity ID (enrollment) or verified identity (authentication) |
| `decision` | `String` | `"APPROVE"`, `"REJECT"`, or `"MANUAL_REVIEW"` |
| `timestamp` | `String` | ISO 8601 timestamp |
| `isApproved` | `Boolean` | Convenience: `decision == "APPROVE"` |
| `isRejected` | `Boolean` | Convenience: `decision == "REJECT"` |
| `isPendingReview` | `Boolean` | Convenience: `decision == "MANUAL_REVIEW"` |

**Security note**: The SDK result is intentionally redacted. Internal scoring details (channel trust, liveness, deduplication risk, pillar verdicts) are not exposed to the client. This prevents reverse-engineering of backend analysis logic. Full scoring data is delivered to your backend via webhook.

### Handling Each Decision Type

```kotlin
override fun onSuccess(result: UseSenseResult) {
    when {
        result.isApproved -> {
            // Proceed with onboarding or login
            showSuccessScreen(result.sessionId)
        }
        result.isRejected -> {
            // Show rejection screen, optionally allow retry
            showRejectionScreen(result.sessionId)
        }
        result.isPendingReview -> {
            // Show "verification in progress" screen
            // Wait for webhook at your backend to deliver the final verdict
            showPendingScreen(result.sessionId)
        }
    }
}
```

## Event Listening

Subscribe to real-time session lifecycle events:

```kotlin
val unsubscribe = UseSense.onEvent { event ->
    when (event.type) {
        EventType.SESSION_CREATED -> {
            Log.d("UseSense", "Session started")
        }
        EventType.CHALLENGE_STARTED -> {
            Log.d("UseSense", "Challenge: ${event.data}")
        }
        EventType.CHALLENGE_COMPLETED -> {
            // User completed the challenge, signals uploading
        }
        EventType.UPLOAD_PROGRESS -> {
            Log.d("UseSense", "Upload: ${event.data}")
        }
        EventType.DECISION_RECEIVED -> {
            Log.d("UseSense", "Decision received")
        }
        EventType.ERROR -> {
            Log.e("UseSense", "Error: ${event.data}")
        }
        else -> {
            Log.d("UseSense", "[${event.type}] ${event.data}")
        }
    }
}

// Remove when done
unsubscribe()
```

### Event Types

| Event | Description |
|-------|-------------|
| `SESSION_CREATED` | Session created on server |
| `PERMISSIONS_REQUESTED` | Runtime permissions being requested |
| `PERMISSIONS_GRANTED` | User granted required permissions |
| `PERMISSIONS_DENIED` | User denied required permissions |
| `CAPTURE_STARTED` | Camera capture has begun |
| `FRAME_CAPTURED` | A frame was captured |
| `CAPTURE_COMPLETED` | All frames captured |
| `AUDIO_RECORD_STARTED` | Audio recording started (voice challenges) |
| `AUDIO_RECORD_COMPLETED` | Audio recording finished |
| `CHALLENGE_STARTED` | Challenge presented to user |
| `CHALLENGE_COMPLETED` | User completed the challenge |
| `UPLOAD_STARTED` | Signal upload initiated |
| `UPLOAD_PROGRESS` | Upload progress update |
| `UPLOAD_COMPLETED` | Signal upload finished |
| `COMPLETE_STARTED` | Server-side analysis started |
| `DECISION_RECEIVED` | Verdict received from server |
| `IMAGE_QUALITY_CHECK` | Real-time frame quality feedback |
| `ERROR` | An error occurred |

## Error Handling

### UseSenseError

| Property | Type | Description |
|----------|------|-------------|
| `code` | `Int` | Numeric error code |
| `serverCode` | `String?` | Server-specific error code |
| `message` | `String` | Human-readable error message |
| `isRetryable` | `Boolean` | Whether the operation can be retried |
| `details` | `Map<String, Any>?` | Additional error details |

### Error Code Reference

| Code | Constant | Description | Recovery |
|------|----------|-------------|----------|
| `1001` | `CAMERA_UNAVAILABLE` | No suitable front-facing camera found | Device may lack front camera. Inform user. |
| `1002` | `CAMERA_PERMISSION_DENIED` | Camera permission not granted by user | Request permission again or direct user to Settings. |
| `1003` | `MICROPHONE_PERMISSION_DENIED` | Microphone permission not granted | Request permission or disable audio challenges. |
| `2001` | `NETWORK_ERROR` | Sense API is unreachable | Check connectivity. Retry with exponential backoff. |
| `2002` | `NETWORK_TIMEOUT` | Request timed out | Retry. Check if device is behind a proxy. |
| `3001` | `SESSION_EXPIRED` | 15-minute server-side session expiry reached | Start a new session. This is a hard server limit. |
| `3002` | `UPLOAD_FAILED` | Signal upload failed after retries | Check connectivity. Retry with new session. |
| `4001` | `CAPTURE_FAILED` | Frame capture failed | Camera may be in use by another app. |
| `4002` | `ENCODING_FAILED` | JPEG frame encoding failed | Retry. Contact support if persistent. |
| `5001` | `INVALID_CONFIG` | Missing or invalid configuration | Check apiKey and required fields. |
| `6001` | `QUOTA_EXCEEDED` | Rate limit or credit quota reached | Wait and retry, or purchase credits. |

### Error Handling Example

```kotlin
override fun onError(error: UseSenseError) {
    val userMessage = when (error.code) {
        UseSenseError.CAMERA_PERMISSION_DENIED ->
            "Camera access is required. Please enable it in Settings."
        UseSenseError.CAMERA_UNAVAILABLE ->
            "No front camera found. Please use a device with a front-facing camera."
        UseSenseError.NETWORK_ERROR, UseSenseError.NETWORK_TIMEOUT ->
            "Connection issue. Please check your internet and try again."
        UseSenseError.SESSION_EXPIRED ->
            "Your session expired. Please start over."
        UseSenseError.INVALID_CONFIG ->
            "Configuration error. Please contact support."
        UseSenseError.QUOTA_EXCEEDED ->
            "Service temporarily unavailable. Please try again later."
        else ->
            "Verification failed. Please try again."
    }

    showErrorDialog(
        title = "Verification Error",
        message = userMessage,
        showRetry = error.isRetryable,
    )
}
```

## Permissions

### Required AndroidManifest.xml Entries

The SDK declares these permissions in its own manifest (merged automatically):

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

<!-- Required only for voice challenges -->
<uses-permission android:name="android.permission.RECORD_AUDIO" />

<uses-feature android:name="android.hardware.camera.front" android:required="true" />
<uses-feature android:name="android.hardware.sensor.accelerometer" android:required="false" />
<uses-feature android:name="android.hardware.sensor.gyroscope" android:required="false" />
```

The SDK handles runtime permission requests internally when `startVerification()` is called. If the user denies camera permission, the SDK calls `onError()` with code `CAMERA_PERMISSION_DENIED` (1002).

If you want to control the permission UX, request `CAMERA` permission yourself before calling `startVerification()`.

## Server-Side Webhook Verification

**This is the most important section for secure integration.**

1. **NEVER** trust the SDK result for access-control decisions. The SDK runs on the user's device and can be tampered with.
2. The definitive verdict arrives via HMAC-SHA256 signed webhook to your backend.
3. Always verify the webhook signature before acting on it.

### Webhook Payload

```json
{
  "event": "session.completed",
  "session_id": "ses_abc123",
  "organization_id": "org_xyz",
  "timestamp": "2026-03-12T10:30:00Z",
  "data": {
    "decision": "approved",
    "channel_trust_score": 95,
    "liveness_score": 92,
    "matchsense_risk_score": 8,
    "presence_confidence": 94,
    "session_type": "enrollment",
    "identity_id": "idn_def456",
    "reasons": [],
    "rule_triggered": null,
    "session_signature": "sig_..."
  }
}
```

### Signature Verification (Node.js / Express)

```javascript
const crypto = require('crypto');

app.post('/webhooks/usesense', (req, res) => {
  const signature = req.headers['x-usesense-signature'];
  const timestamp = req.headers['x-usesense-timestamp'];
  const body = req.rawBody;

  const expected = crypto
    .createHmac('sha256', process.env.USESENSE_WEBHOOK_SECRET)
    .update(timestamp + '.' + body)
    .digest('hex');

  if (!crypto.timingSafeEqual(Buffer.from(signature), Buffer.from(expected))) {
    return res.status(401).send('Invalid signature');
  }

  const event = JSON.parse(body);
  // Act on event.data.decision
  res.status(200).send('OK');
});
```

### Signature Verification (Python / Flask)

```python
import hmac
import hashlib

@app.route('/webhooks/usesense', methods=['POST'])
def usesense_webhook():
    signature = request.headers.get('X-UseSense-Signature')
    timestamp = request.headers.get('X-UseSense-Timestamp')
    body = request.get_data(as_text=True)

    expected = hmac.new(
        os.environ['USESENSE_WEBHOOK_SECRET'].encode(),
        f'{timestamp}.{body}'.encode(),
        hashlib.sha256
    ).hexdigest()

    if not hmac.compare_digest(signature, expected):
        return 'Invalid signature', 401

    event = request.get_json()
    # Act on event['data']['decision']
    return 'OK', 200
```

### Signature Verification (Go)

```go
func webhookHandler(w http.ResponseWriter, r *http.Request) {
    signature := r.Header.Get("X-UseSense-Signature")
    timestamp := r.Header.Get("X-UseSense-Timestamp")
    body, _ := io.ReadAll(r.Body)

    mac := hmac.New(sha256.New, []byte(os.Getenv("USESENSE_WEBHOOK_SECRET")))
    mac.Write([]byte(timestamp + "." + string(body)))
    expected := hex.EncodeToString(mac.Sum(nil))

    if !hmac.Equal([]byte(signature), []byte(expected)) {
        http.Error(w, "Invalid signature", http.StatusUnauthorized)
        return
    }

    // Parse and act on the event
    w.WriteHeader(http.StatusOK)
}
```

## ProGuard / R8

The SDK ships consumer ProGuard rules inside the AAR. These are applied automatically when you build your app with minification enabled. In most cases, no additional configuration is needed.

If you encounter issues with minified builds, add these rules to your app's `proguard-rules.pro`:

```
# UseSense SDK
-keep class com.usesense.sdk.** { *; }
-keep interface com.usesense.sdk.** { *; }
-dontwarn com.usesense.sdk.**

# MediaPipe (3D liveness)
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**

# ML Kit Face Detection (inline step-up)
-keep class com.google.mlkit.vision.face.** { *; }
-dontwarn com.google.mlkit.vision.face.**
```

## Sandbox vs Production

| | Sandbox | Production |
|---|---------|-----------|
| API keys | `sk_sandbox_*` / `pk_sandbox_*` / `dk_*` | `sk_prod_*` / `pk_prod_*` |
| Cost | Free and unlimited | One credit per completed session |
| Features | Identical | Identical |
| Face collection | Shared (higher dedup scores expected) | Isolated per organization |

- Use sandbox for all development and testing.
- Switch environments by changing the `environment` parameter in `UseSenseConfig`, or use `AUTO` to detect from the API key prefix.
- The SDK sends `x-environment: sandbox` or `x-environment: production` on every request.

## Troubleshooting

### SDK initialization fails silently

Check that the API key is not blank, check network connectivity, and ensure `initialize()` is called with **application context** (not activity context).

### Camera preview is black

Permission not granted, camera in use by another app, or running on an emulator without camera support.

### Session always times out

Check network connectivity. Check if the device is behind a corporate proxy that blocks the `api.usesense.ai` domain.

### Deduplication always returns high risk on sandbox

Sandbox uses a shared face collection across all organizations. This is expected behavior. Production uses isolated collections.

### Build fails with "Duplicate class" errors

Dependency version conflict. Use Gradle's dependency resolution strategy:

```kotlin
configurations.all {
    resolutionStrategy {
        force("com.squareup.okhttp3:okhttp:4.12.0")
    }
}
```

### Build fails with multidex error

Enable multidex in your app's `build.gradle.kts`:

```kotlin
defaultConfig {
    multiDexEnabled = true
}
```

### Play Integrity attestation fails

Ensure your app is signed with the correct signing key registered in Google Play Console. During development, use debug attestation. Play Integrity will always fail on emulators.

### Session works on Wi-Fi but fails on mobile data

Check if the carrier or corporate MDM blocks the `api.usesense.ai` domain.

### App crashes with TransactionTooLargeException

Ensure you're on the latest SDK version which uses a different result delivery mechanism.

## API Reference

Full generated API documentation is available at [watchtower.usesense.ai/developer-docs](https://watchtower.usesense.ai/developer-docs).

## Changelog

See [CHANGELOG.md](CHANGELOG.md).

## License

Proprietary. See [LICENSE](LICENSE).

## Support

- Documentation: https://watchtower.usesense.ai/developer-docs
- Dashboard: https://watchtower.usesense.ai
- Email: support@usesense.ai
