# UseSense Android Integration Guide

## How Verification Works

1. Your app initializes the UseSense SDK with your API key (once, in `Application.onCreate()`)
2. When you need to verify a user (e.g., during onboarding), call `startVerification()` with a callback
3. The SDK launches a full-screen camera Activity
4. The user completes a short challenge (5-15 seconds): following a dot, turning their head, or speaking a phrase
5. The SDK captures frames, sensor data, and optional audio, then uploads everything encrypted to UseSense servers
6. Server-side analysis runs three independent pillars (DeepSense, LiveSense, MatchSense) in parallel
7. The SDK receives a preliminary result -- use this for UI feedback (show success/failure screen)
8. The definitive verdict is delivered to **your backend** via HMAC-signed webhook -- this is what you use for access-control decisions
9. One credit is consumed per completed session, regardless of the decision

```
┌──────────┐     ┌──────────────┐     ┌─────────────┐     ┌──────────────┐
│ Your App │     │ UseSense SDK │     │ UseSense API│     │ Your Backend │
└────┬─────┘     └──────┬───────┘     └──────┬──────┘     └──────┬───────┘
     │ initialize()     │                     │                   │
     │─────────────────>│                     │                   │
     │                  │  validate API key   │                   │
     │                  │────────────────────>│                   │
     │                  │         OK          │                   │
     │                  │<────────────────────│                   │
     │   ready          │                     │                   │
     │<─────────────────│                     │                   │
     │                  │                     │                   │
     │ startVerification│                     │                   │
     │─────────────────>│                     │                   │
     │                  │ POST /v1/sessions   │                   │
     │                  │────────────────────>│                   │
     │                  │  challenge config   │                   │
     │                  │<────────────────────│                   │
     │                  │                     │                   │
     │    [SDK launches camera Activity, user completes challenge]│
     │                  │                     │                   │
     │                  │ POST /signals       │                   │
     │                  │────────────────────>│                   │
     │                  │ POST /complete      │                   │
     │                  │────────────────────>│                   │
     │                  │   SDK result        │                   │
     │  onSuccess()     │<────────────────────│                   │
     │<─────────────────│                     │                   │
     │                  │                     │  webhook (verdict)│
     │                  │                     │──────────────────>│
     │                  │                     │                   │
```

## Why Three Independent Pillars?

Most verification providers return one confidence number. If channel integrity fails but liveness passes, a single composite score hides the risk.

UseSense scores each dimension independently:

- **DeepSense** (Channel & Device Integrity): App attestation via Play Integrity, runtime integrity checks (emulator, root, hooking frameworks), capture pipeline analysis. Produces a `channelTrustScore` (0-100).
- **LiveSense** (Multimodal Proof-of-Life): Facial dynamics, visual integrity, temporal coherence, presentation attack detection, environmental corroboration, challenge compliance, audio authenticity. Produces a `livenessScore` (0-100).
- **MatchSense** (Identity Collision Detection): 1:N face search (enrollment), 1:1 verification (authentication), cross-identity risk scoring, face quality assessment. Produces a `matchSenseRiskScore` (0-100).

A critical failure in any pillar cannot be masked by strong scores in others. Default verdict logic: "weakest link" -- any pillar failing results in rejection. Organizations can configure majority vote or weighted composite via the dashboard.

**Note**: These pillar scores are delivered to your backend via webhook. The SDK result is intentionally redacted to prevent reverse-engineering of the analysis logic.

## Choosing a Session Type

| Use Case | Session Type | Notes |
|----------|-------------|-------|
| User onboarding | `ENROLLMENT` | Creates a new identity record |
| Account creation | `ENROLLMENT` | Runs 1:N duplicate detection |
| Login verification | `AUTHENTICATION` | Requires existing `identityId` |
| Transaction confirmation | `AUTHENTICATION` | 1:1 verification + 1:N scan |
| Periodic re-verification | `AUTHENTICATION` | Confirms identity hasn't changed |

Both session types run all three pillars. Enrollment creates an identity record; authentication requires one to exist.

## Challenge Types

The server selects challenges dynamically. The SDK handles all challenge presentation automatically:

- **Follow Dot**: An animated dot traces a path on screen. The user tracks it with their face. Tests facial dynamics and liveness.
- **Head Turn**: The user is asked to turn their head in specified directions (left, right, up, down). Tests 3D presence and presentation attack resistance.
- **Speak Phrase**: The user reads a displayed phrase aloud. Tests audio authenticity and deepfake detection via DSP analysis.

Challenge selection is controlled server-side. The SDK presents whatever challenge the server assigns.

## Handling the Verdict

### On the client (SDK result)

The `UseSenseResult` provides a redacted decision for UI feedback:

```kotlin
override fun onSuccess(result: UseSenseResult) {
    when {
        result.isApproved -> {
            // Show success screen immediately
            navigateToSuccessScreen()
        }
        result.isPendingReview -> {
            // Show "verification in progress" state
            showPendingReviewScreen(result.sessionId)
        }
        result.isRejected -> {
            // Show rejection, optionally allow retry
            showRejectionScreen(allowRetry = true)
        }
    }
}
```

**NEVER** gate access based solely on the SDK result.

### On your backend (webhook)

The webhook is the authoritative source. It contains the full scoring breakdown:

1. Verify the HMAC-SHA256 signature (see [README webhook section](README.md#server-side-webhook-verification))
2. Map the decision to your business logic:
   - `approved`: Proceed with account creation / login / transaction
   - `rejected`: Block the action, log the attempt
   - `manual_review`: Queue for human review, or auto-approve after delay based on your risk tolerance

### Retry Logic

| Scenario | Action |
|----------|--------|
| Network/timeout failure | Start a new session |
| User cancelled | Allow immediate retry |
| Rejected | Allow retry (recommend max 3 attempts) |

Each retry creates a new session and consumes one credit (if the session completes).

## Android-Specific Considerations

### Activity Lifecycle

`startVerification()` launches a new Activity. Your calling Activity may be paused or even destroyed (low memory).

The result is delivered via the `UseSenseCallback` you provide. If using the callback in an Activity, be aware of recreation on rotation. Recommended pattern:

```kotlin
class VerifyActivity : AppCompatActivity() {

    private val callback = object : UseSenseCallback {
        override fun onSuccess(result: UseSenseResult) {
            // Handle result
        }
        override fun onError(error: UseSenseError) {
            // Handle error
        }
        override fun onCancelled() {
            // Handle cancellation
        }
    }

    private fun startVerification() {
        UseSense.startVerification(
            activity = this,
            request = VerificationRequest(sessionType = SessionType.ENROLLMENT),
            callback = callback,
        )
    }
}
```

### Process Death

If the system kills your process while the UseSense camera Activity is in the foreground, the session is lost. The SDK handles this gracefully -- the callback receives an error or cancellation when the user returns. No credit is consumed for incomplete sessions.

### Background Restrictions

The SDK uploads frames in the foreground. It does not use background services or WorkManager. If the user switches apps mid-session, the session will timeout.

### Emulator Support

The SDK can run on emulators for basic UI testing, but DeepSense channel trust scores will be very low (emulator detection triggers). For realistic testing, always use a physical device. Play Integrity attestation will fail on emulators.

## Data Privacy

- Face images and optional audio are uploaded encrypted and processed server-side
- No biometric data is stored on the user's device
- Face templates are stored server-side in your organization's isolated collection
- Configurable data retention policies via the dashboard
- GDPR/CCPA privacy request support available

## Going to Production Checklist

1. Switch from sandbox to production API key
2. Set `environment` to `PRODUCTION` in `UseSenseConfig` (or use `AUTO`)
3. Purchase credits in the [UseSense dashboard](https://app.usesense.ai)
4. Configure your webhook endpoint for production
5. Test full flow end-to-end on physical devices (multiple OEMs recommended)
6. Ensure your backend handles all three decision types (`APPROVE`, `REJECT`, `MANUAL_REVIEW`)
7. Set up low-credit alerts (webhook: `billing.credits_low`)
8. Register your production signing key in Google Play Console for Play Integrity
9. Test with R8/ProGuard minification enabled
10. Test on minimum supported API level (28) device if possible
