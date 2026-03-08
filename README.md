# usesense-android-sdk

Native Android SDK for identity verification and liveness detection — used directly in Android apps or as the underlying engine for the [React Native](https://github.com/qudusadeyemi/react-native-usesense) and [Flutter](https://github.com/qudusadeyemi/flutter-usesense) wrappers.

## Requirements

- **minSdkVersion:** 26 (Android 8.0+)
- **compileSdkVersion:** 35
- **Kotlin:** 1.9+
- **Java:** 17

## Installation

### Gradle (Maven)

Add the UseSense repository and dependency to your app-level `build.gradle.kts`:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // Add the UseSense Maven repository (when published)
        // maven { url = uri("https://maven.usesense.ai/releases") }
    }
}
```

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("com.usesense:sdk:0.1.0")
}
```

### Local Development

To build and publish the SDK to your local Maven repository:

```bash
git clone https://github.com/qudusadeyemi/usesense-android-sdk.git
cd usesense-android-sdk
./gradlew :sdk:publishToMavenLocal
```

Then add `mavenLocal()` to your project's repositories and depend on `com.usesense:sdk:0.1.0`.

## Usage

### 1. Initialize the SDK

Call `UseSense.initialize()` once on app startup:

```kotlin
import com.usesense.sdk.UseSense
import com.usesense.sdk.UseSenseConfig

UseSense.initialize(
    context = applicationContext,
    config = UseSenseConfig(
        apiKey = "your_api_key",
    ),
)
```

### 2. Subscribe to Events (Optional)

```kotlin
import com.usesense.sdk.UseSenseEvent

val unsubscribe = UseSense.onEvent { event ->
    Log.d("UseSense", "[${event.type}] ${event.data}")
}

// Call unsubscribe() when done
```

### 3. Start Verification

```kotlin
import com.usesense.sdk.VerificationRequest
import com.usesense.sdk.SessionType
import com.usesense.sdk.UseSenseCallback
import com.usesense.sdk.UseSenseResult
import com.usesense.sdk.UseSenseError

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
                result.isApproved -> Log.d("UseSense", "Verified! ${result.sessionId}")
                result.isPendingReview -> Log.d("UseSense", "Under review")
                result.isRejected -> Log.d("UseSense", "Rejected")
            }
        }

        override fun onError(error: UseSenseError) {
            Log.e("UseSense", "Error ${error.code}: ${error.message}")
            if (error.isRetryable) {
                // Safe to retry
            }
        }

        override fun onCancelled() {
            Log.d("UseSense", "User cancelled")
        }
    },
)
```

### Authentication Sessions

```kotlin
UseSense.startVerification(
    activity = this,
    request = VerificationRequest(
        sessionType = SessionType.AUTHENTICATION,
        identityId = "identity_abc123", // required for authentication
        externalUserId = "user_123",
    ),
    callback = callback,
)
```

### Branding

```kotlin
UseSense.initialize(
    context = applicationContext,
    config = UseSenseConfig(
        apiKey = "your_api_key",
        branding = BrandingConfig(
            primaryColor = "#4F63F5",
            buttonRadius = 12,
            logoUrl = "https://example.com/logo.png",
            fontFamily = "Inter",
        ),
    ),
)
```

## Permissions

The SDK declares the following permissions in its manifest (merged automatically):

| Permission            | Purpose                          | Required |
| --------------------- | -------------------------------- | -------- |
| `CAMERA`              | Capture video frames             | Yes      |
| `RECORD_AUDIO`        | Audio challenge (speak phrase)   | No       |
| `INTERNET`            | API communication                | Yes      |
| `ACCESS_NETWORK_STATE`| Connectivity detection           | Yes      |

> The SDK requests camera (and microphone, if needed) permissions at runtime before capture begins.

## API Reference

### `UseSense.initialize(context, config)`

| Parameter | Type | Required | Notes |
| --------- | ---- | -------- | ----- |
| `context` | `Context` | Yes | Use `applicationContext` |
| `config` | `UseSenseConfig` | Yes | See config options below |

**UseSenseConfig:**

| Parameter                  | Type     | Required | Default          |
| -------------------------- | -------- | -------- | ---------------- |
| `apiKey`                   | String   | Yes      |                  |
| `environment`              | UseSenseEnvironment | No | `AUTO` (detects from key prefix) |
| `baseUrl`                  | String   | No       | UseSense default |
| `gatewayKey`               | String?  | No       |                  |
| `branding`                 | BrandingConfig? | No |                  |
| `googleCloudProjectNumber` | Long     | No       | UseSense default |

**BrandingConfig:**

| Parameter      | Type    | Required | Default     |
| -------------- | ------- | -------- | ----------- |
| `logoUrl`      | String? | No       |             |
| `primaryColor` | String  | No       | `"#4F63F5"` |
| `buttonRadius` | Int     | No       | `12`        |
| `fontFamily`   | String? | No       |             |

### `UseSense.startVerification(activity, request, callback)`

Launch the full-screen verification flow.

| Parameter  | Type                | Required | Notes |
| ---------- | ------------------- | -------- | ----- |
| `activity` | `Activity`          | Yes      | Host activity |
| `request`  | `VerificationRequest` | Yes    | See below |
| `callback` | `UseSenseCallback`  | Yes      | Result handler |

**VerificationRequest:**

| Parameter        | Type               | Required | Notes                               |
| ---------------- | ------------------ | -------- | ----------------------------------- |
| `sessionType`    | `SessionType`      | Yes      | `ENROLLMENT` or `AUTHENTICATION`    |
| `externalUserId` | String?            | No       | Your internal user ID               |
| `identityId`     | String?            | No       | Required for `AUTHENTICATION`       |
| `metadata`       | Map<String, Any>?  | No       | Arbitrary key-value pairs           |

### `UseSense.onEvent(callback)` → `() -> Unit`

Subscribe to SDK lifecycle events. Returns an unsubscribe function.

**Event types:** `SESSION_CREATED`, `PERMISSIONS_GRANTED`, `CAPTURE_STARTED`, `FRAME_CAPTURED`, `CAPTURE_COMPLETED`, `CHALLENGE_STARTED`, `CHALLENGE_COMPLETED`, `UPLOAD_STARTED`, `UPLOAD_PROGRESS`, `UPLOAD_COMPLETED`, `DECISION_RECEIVED`, `IMAGE_QUALITY_CHECK`, `ERROR`, and more.

### `UseSense.isInitialized` → `Boolean`

### `UseSense.reset()`

Clear SDK state and release resources.

## UseSenseResult

| Property          | Type    | Description                        |
| ----------------- | ------- | ---------------------------------- |
| `sessionId`       | String  | Unique session identifier          |
| `sessionType`     | String? | `"enrollment"` or `"authentication"` |
| `identityId`      | String? | Assigned identity ID               |
| `decision`        | String  | `"APPROVE"`, `"REJECT"`, or `"MANUAL_REVIEW"` |
| `timestamp`       | String  | ISO 8601 timestamp                 |
| `isApproved`      | Boolean | Convenience: decision == APPROVE   |
| `isRejected`      | Boolean | Convenience: decision == REJECT    |
| `isPendingReview`  | Boolean | Convenience: decision == MANUAL_REVIEW |

## UseSenseError

| Property      | Type    | Description                            |
| ------------- | ------- | -------------------------------------- |
| `code`        | Int     | Numeric error code (see table below)   |
| `serverCode`  | String? | Server-specific error code             |
| `message`     | String  | Human-readable error message           |
| `isRetryable` | Boolean | Whether the operation can be retried   |

**Error codes:**

| Code | Constant                   | Retryable |
| ---- | -------------------------- | --------- |
| 1001 | `CAMERA_UNAVAILABLE`       | No        |
| 1002 | `CAMERA_PERMISSION_DENIED` | No        |
| 1003 | `MICROPHONE_PERMISSION_DENIED` | No    |
| 2001 | `NETWORK_ERROR`            | Yes       |
| 2002 | `NETWORK_TIMEOUT`          | Yes       |
| 3001 | `SESSION_EXPIRED`          | No        |
| 3002 | `UPLOAD_FAILED`            | Yes       |
| 4001 | `CAPTURE_FAILED`           | No        |
| 4002 | `ENCODING_FAILED`          | No        |
| 5001 | `INVALID_CONFIG`           | No        |
| 6001 | `QUOTA_EXCEEDED`           | No        |

## Demo App

The `demo/` module contains a full working example with a mock server for local testing:

```bash
# Open in Android Studio and run the :demo module
# Toggle between Mock Mode (local server) and Sandbox Mode (live API)
```

## ProGuard

If you use R8/ProGuard, the SDK ships consumer rules that keep the necessary classes. No additional configuration is needed.

## Platform Support

| Platform | Supported |
| -------- | --------- |
| Android  | ✅        |
| iOS      | Planned   |

## License

MIT
