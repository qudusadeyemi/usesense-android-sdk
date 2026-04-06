package com.usesense.sdk.liveness

import org.json.JSONArray
import org.json.JSONObject

/**
 * Assembles the verification_package JSON for the metadata upload.
 * Combines per-frame 3DMM fits with cryptographic binding proofs
 * and platform attestation.
 */
internal class VerificationPackageBuilder {

    /**
     * Build the complete verification_package JSON object.
     *
     * @param fitter The 3DMM fitter with all fitted frames
     * @param frameHashes SHA-256 hashes of each JPEG frame (indexed by frame index)
     * @param meshBindingChallenge 32-byte hex challenge from session creation
     * @param meshDataList Per-frame mesh data from FaceMeshManager
     * @param playIntegrityToken Play Integrity token for attestation
     */
    fun build(
        fitter: OnDevice3DMMFitter,
        frameHashes: Map<Int, String>,
        meshBindingChallenge: String,
        meshDataList: List<FrameMeshData>,
        playIntegrityToken: String?,
    ): JSONObject {
        val pkg = JSONObject()
        val framesArray = JSONArray()

        for (fitted in fitter.results) {
            val frameHash = frameHashes[fitted.frameIndex] ?: continue
            val meshData = meshDataList.find { it.frameIndex == fitted.frameIndex }
            val landmarkCount = meshData?.landmarks?.size ?: 468

            val meshDigest = MeshBindingProof.computeMeshDigest(
                shapeParams = fitted.shapeParams,
                pose = fitted.pose,
                depthPlausibility = fitted.depthPlausibility,
                landmarkCount = landmarkCount,
            )

            val bindingProof = if (meshBindingChallenge.isNotEmpty()) {
                MeshBindingProof.computeBindingProof(
                    meshBindingChallenge = meshBindingChallenge,
                    frameHash = frameHash,
                    meshDigest = meshDigest,
                )
            } else ""

            val frameObj = JSONObject().apply {
                put("frameIndex", fitted.frameIndex)
                put("timestamp", fitted.timestampMs)
                put("shapeParams", JSONArray(fitted.shapeParams.toList()))
                put("pose", JSONObject().apply {
                    put("yaw", fitted.pose.yaw)
                    put("pitch", fitted.pose.pitch)
                    put("roll", fitted.pose.roll)
                })
                put("depthPlausibility", fitted.depthPlausibility)
                put("geometricRatios", JSONArray(fitted.geometricRatios.toList()))
                put("poseRatios2D", JSONArray(fitted.poseRatios2D.toList()))
                put("frameHash", frameHash)
                put("meshDigest", meshDigest)
                put("bindingProof", bindingProof)
                put("poseNormalizationMethod", "mediapipe_zyx_v2")
            }
            framesArray.put(frameObj)
        }

        pkg.put("frames", framesArray)
        pkg.put("crossFrameConsistency", fitter.computeCrossFrameConsistency())
        pkg.put("preliminaryScore", fitter.computePreliminaryScore())

        // Attestation
        val attestation = JSONObject().apply {
            put("platform", "android")
            if (playIntegrityToken != null) {
                put("token", playIntegrityToken)
            }
        }
        pkg.put("attestation", attestation)

        return pkg
    }
}
