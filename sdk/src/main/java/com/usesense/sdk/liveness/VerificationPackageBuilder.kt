package com.usesense.sdk.liveness

import com.usesense.sdk.evidence.Evidence
import org.json.JSONArray
import org.json.JSONObject

/**
 * Assembles the verification_package JSON for the metadata upload.
 * Combines per-frame 3DMM fits with cryptographic binding proofs
 * and platform attestation.
 *
 * Frame rules (EVIDENCE-v1 section 3): entry `frameIndex` is the upload
 * position of the JPEG it describes, at most one entry per uploaded frame, in
 * ascending order, and an uploaded frame with no mesh simply has no entry
 * (never a neighbour's mesh).
 */
internal class VerificationPackageBuilder {

    /**
     * Build the complete verification_package JSON object.
     *
     * @param fits The 3DMM fits (any order; each carries its frame's buffer index)
     * @param uploadHashes SHA-256 of each uploaded JPEG, by upload position
     * @param uploadPositionOf Upload position of each buffer frame index
     * @param meshBindingChallenge 32-byte hex challenge from session creation
     * @param meshDataList Per-frame mesh data from FaceMeshManager
     * @param playIntegrityToken Play Integrity token for attestation
     * @param evidence Add the EVIDENCE-v1 per-frame fields (raw landmarks, matrix
     *   pose under `matrix_zyx_v3`, eye blendshapes) and bind the landmarks into
     *   `meshDigest`.
     */
    fun build(
        fits: List<FittedFrame>,
        crossFrameConsistency: Int,
        preliminaryScore: Int,
        uploadHashes: List<String>,
        uploadPositionOf: Map<Int, Int>,
        meshBindingChallenge: String,
        meshDataList: List<FrameMeshData>,
        playIntegrityToken: String?,
        evidence: Boolean = false,
    ): JSONObject {
        val pkg = JSONObject()
        val framesArray = JSONArray()
        val meshByIndex = meshDataList.associateBy { it.frameIndex }
        val seenPositions = HashSet<Int>()
        val seenHashes = HashSet<String>()

        val placed = fits.mapNotNull { fit -> uploadPositionOf[fit.frameIndex]?.let { it to fit } }.sortedBy { it.first }
        for ((position, fitted) in placed) {
            val frameHash = uploadHashes.getOrNull(position) ?: continue
            // At most one entry per uploaded frame (rule 3).
            if (!seenPositions.add(position) || !seenHashes.add(frameHash)) continue

            val fields = if (evidence) meshByIndex[fitted.frameIndex]?.let(::EvidenceFrameFields) else null
            val pose = fields?.matrixPose ?: fitted.pose
            val method = if (fields?.matrixPose != null) Evidence.POSE_METHOD_V3 else Evidence.POSE_METHOD_LEGACY
            val landmarks = fields?.landmarks?.takeIf { it.size == 478 * 3 || it.size == 468 * 3 }

            val meshDigest = MeshBindingProof.computeMeshDigest(
                shapeParams = fitted.shapeParams,
                pose = pose,
                depthPlausibility = fitted.depthPlausibility,
                landmarksHash = landmarks?.let(Evidence::landmarksHash),
            )

            val bindingProof = if (meshBindingChallenge.isNotEmpty()) {
                MeshBindingProof.computeBindingProof(
                    meshBindingChallenge = meshBindingChallenge,
                    frameHash = frameHash,
                    meshDigest = meshDigest,
                )
            } else ""

            val frameObj = JSONObject().apply {
                put("frameIndex", position)
                put("timestamp", fitted.timestampMs)
                put("shapeParams", JSONArray(fitted.shapeParams.toList()))
                put("pose", JSONObject().apply {
                    put("yaw", pose.yaw)
                    put("pitch", pose.pitch)
                    put("roll", pose.roll)
                })
                put("depthPlausibility", fitted.depthPlausibility)
                put("geometricRatios", JSONArray(fitted.geometricRatios.toList()))
                put("poseRatios2D", JSONArray(fitted.poseRatios2D.toList()))
                put("frameHash", frameHash)
                put("meshDigest", meshDigest)
                put("bindingProof", bindingProof)
                put("poseNormalizationMethod", method)
                if (landmarks != null) put("landmarks", JSONArray(landmarks.toList()))
                fields?.transformMatrix?.let { put("transformMatrix", JSONArray(it.toList())) }
                fields?.blendshapesJson()?.let { put("blendshapes", it) }
            }
            framesArray.put(frameObj)
        }

        pkg.put("frames", framesArray)
        pkg.put("crossFrameConsistency", crossFrameConsistency)
        pkg.put("preliminaryScore", preliminaryScore)

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

/** What one uploaded frame adds under EVIDENCE-v1 (sections 4 and 5), from that frame's own mesh. */
internal class EvidenceFrameFields(mesh: FrameMeshData) {
    /**
     * 5 decimals. MediaPipe ran on the decoded upload JPEG itself (upright,
     * unmirrored), so its normalised coordinates are already JPEG space.
     */
    val landmarks: DoubleArray = Evidence.flatLandmarks(mesh.landmarks)
    /** Row-major 4x4, 5 decimals. */
    val transformMatrix: DoubleArray? = mesh.transformMatrix?.takeIf { it.size == 16 }?.let { m ->
        DoubleArray(16) { Evidence.round(m[it], 5) }
    }
    /** From the rounded matrix, so the server's recomputation matches. */
    val matrixPose: HeadPose? = transformMatrix?.let(Evidence::matrixPose)
    val eyeBlinkLeft: Double? = mesh.eyeBlinkLeft?.let { Evidence.round(it, 3) }
    val eyeBlinkRight: Double? = mesh.eyeBlinkRight?.let { Evidence.round(it, 3) }

    fun blendshapesJson(): JSONObject? {
        val l = eyeBlinkLeft ?: return null
        val r = eyeBlinkRight ?: return null
        return JSONObject().put("eyeBlinkLeft", l).put("eyeBlinkRight", r)
    }
}
