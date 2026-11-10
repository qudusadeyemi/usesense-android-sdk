package com.usesense.sdk.liveness

/**
 * The output configuration every FaceLandmarker in the SDK must share.
 *
 * In MediaPipe tasks-vision 0.20230731, FaceLandmarker keeps the output stream
 * positions of the blendshapes and the transformation matrices in *static*
 * fields, which each createFromOptions overwrites for the whole process. Two
 * landmarkers built with different outputs (the session mesh and the quality
 * gate) then read each other's positions: a protobuf parse of the wrong packet
 * (a native abort) or an IndexOutOfBounds on MediaPipe's own thread, neither of
 * which can be caught. Seen on a Galaxy A03 in a sandbox flow run.
 *
 * So both landmarkers turn on the same outputs, whatever each one reads.
 */
internal object FaceLandmarkerOutputs {
    const val BLENDSHAPES = true
    const val TRANSFORMATION_MATRICES = true
}

/**
 * One MediaPipe graph is built at a time, process-wide. The session mesh, the
 * quality gate's landmarker and the covered-face segmenter start on three
 * threads as the capture opens; building their graphs at once crashed a Galaxy
 * A03 in native code (SIGBUS in strncmp under Graph.startRunningGraph, during
 * FaceLandmarker.createFromOptions) with the face mesh on (production
 * policies). Graph setup touches process-wide registries that aren't safe to
 * build concurrently; running a built graph is unaffected.
 */
internal object MediaPipeGraphs {
    private val lock = Any()

    fun <T> create(build: () -> T): T = synchronized(lock) { build() }
}
