# Changelog

All notable changes to the UseSense Android SDK will be documented in this file.

This project adheres to [Semantic Versioning](https://semver.org/).

## [1.0.0] - 2026-XX-XX

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
- Maven Central and GitHub Packages distribution
- Sandbox and production environment support
