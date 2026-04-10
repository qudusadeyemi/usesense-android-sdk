package com.usesense.sdk.liveness

// REMOVED in Phase 4: Runtime model download has been replaced by the bundled
// asset at sdk/src/main/assets/face_landmarker.task, managed by the
// mediapipe-sdk-sync workflow in qudusadeyemi/usesense-watchtower.
//
// The previous implementation downloaded face_landmarker.task from
// storage.googleapis.com at runtime with no SHA-256 verification (despite
// the docstring claiming otherwise) and no version pinning. Bundling the
// bytes eliminates the first-launch network dependency, removes a silent
// integrity-bypass surface, and ensures cross-platform parity with iOS and
// Web SDK installations.
//
// This file is kept as a placeholder so the package compiles after the
// symbol removal; the next follow-up commit will delete it via direct git
// push (the MCP push_files API only adds and modifies, it cannot delete).
