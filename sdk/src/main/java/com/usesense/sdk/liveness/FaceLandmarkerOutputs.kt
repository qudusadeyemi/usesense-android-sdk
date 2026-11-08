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
