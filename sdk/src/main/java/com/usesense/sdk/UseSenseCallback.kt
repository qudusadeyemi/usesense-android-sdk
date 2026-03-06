package com.usesense.sdk

interface UseSenseCallback {
    fun onSuccess(result: UseSenseResult)
    fun onError(error: UseSenseError)
    fun onCancelled()
}
