# UseSense Android Example App

Demonstrates SDK initialization, enrollment, authentication, event listening, and error handling.

## Setup

1. Clone this repository
2. Open the `example/` directory in Android Studio
3. Sync Gradle
4. Replace the API key placeholder in `ExampleApplication.kt` with your sandbox API key from https://app.usesense.ai
5. Build and run on a physical device (camera required; emulators with virtual cameras may work for basic testing)

## What This Demonstrates

- SDK initialization with sandbox configuration
- Enrollment session (first-time face registration)
- Authentication session (returning user verification with identity ID)
- Real-time event streaming during sessions
- Error handling with dialog display and retry guidance
- Result interpretation with decision badge

## Architecture

The example uses Jetpack Compose with a single-activity architecture:

- **ExampleApplication.kt**: SDK initialization in `Application.onCreate()`
- **MainActivity.kt**: Main screen with enrollment/authentication buttons, identity ID input, result card, and event log

## Notes

- The SDK result is intentionally redacted (no pillar scores). Full scoring data is delivered to your backend via webhook.
- Use a sandbox API key for development. Sandbox sessions are free and unlimited.
- Physical devices are recommended. Emulators will trigger low DeepSense channel trust scores.
