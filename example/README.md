# UseSense Android Example App

Demonstrates SDK initialization, enrollment, authentication, event listening, and error handling.

## Setup

1. Clone this repository
2. Open the `example/` directory in Android Studio (open **just** the `example/` folder — not the repo root)
3. Sync Gradle. On first sync, Gradle downloads the published SDK (`ai.usesense:sdk:4.2.0`) from Maven Central.
4. Build and run on a physical device (camera required; emulators with virtual cameras may work for basic UI testing but will fail real verification flows on DeepSense channel trust).
5. On first launch, paste your sandbox or production API key from [watchtower.usesense.ai](https://watchtower.usesense.ai) into the **API Key** field at the top of the app. The key is persisted in `SharedPreferences` and survives subsequent launches.
6. Flip the **Production** toggle if you're using a production key (`sk_prod_*`); leave it off for sandbox (`sk_sandbox_*`).
7. Tap **Enroll** to run a first-time enrollment, or paste an existing Identity ID and tap **Authenticate** to run an authentication session.

## What This Demonstrates

- SDK initialization with sandbox configuration
- Enrollment session (first-time face registration)
- Authentication session (returning user verification with identity ID)
- Real-time event streaming during sessions
- Error handling with dialog display and retry guidance
- Result interpretation with decision badge

## Architecture

The example uses Jetpack Compose with a single-activity architecture:

- **MainActivity.kt**: Main screen with API key input (masked, persisted via SharedPreferences), sandbox/production toggle, enrollment/authentication buttons, identity ID input, result card, and event log. SDK is initialized lazily when the user taps Enroll or Authenticate with the current entered key.

The SDK is consumed from Maven Central (`implementation("ai.usesense:sdk:4.2.0")`), so the example builds as a standalone Gradle project — no composite build, no SDK source checkout needed.

## Notes

- The SDK result is intentionally redacted (no pillar scores). Full scoring data is delivered to your backend via webhook.
- Use a sandbox API key for development. Sandbox sessions are free and unlimited.
- Physical devices are recommended. Emulators will trigger low DeepSense channel trust scores.
