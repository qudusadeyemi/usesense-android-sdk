package com.usesense.sdk.liveness

import org.json.JSONArray
import org.json.JSONObject

data class SuspicionSignal(
    val name: String,
    val score: Int,
    val weight: Double,
    val detail: String,
)

data class SuspicionSnapshot(
    val score: Int,
    val signals: List<SuspicionSignal>,
    val framesAnalyzed: Int,
    val reliable: Boolean,
    val timestamp: Long,
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("score", score)
            val signalsArray = JSONArray()
            for (signal in signals) {
                signalsArray.put(JSONObject().apply {
                    put("name", signal.name)
                    put("score", signal.score)
                    put("weight", signal.weight)
                    put("detail", signal.detail)
                })
            }
            put("signals", signalsArray)
            put("framesAnalyzed", framesAnalyzed)
            put("reliable", reliable)
            put("timestamp", timestamp)
        }
    }
}
