package com.usesense.sdk.api.models

import android.util.Log
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.lang.reflect.Type

/**
 * The verdict's optional detail blocks decode leniently: a block whose shape
 * differs from this model becomes null (with a warning) instead of failing the
 * whole verdict. The host only ever receives the decision fields; three times
 * an unused detail field (a fractional similarity, an object where a string
 * was declared) turned a real decision into error 2001.
 */
internal object LenientVerdictParts : JsonAdapter.Factory {
    private const val TAG = "UseSense"

    private val lenientTypes: Set<Type> =
        setOf(
            PillarVerdicts::class.java,
            VerdictMetadata::class.java,
            GCScoreResult::class.java,
            SenseiStepUpResult::class.java,
            InlineStepUpResult::class.java,
            ChallengeValidationResult::class.java,
            DedupeAnalysisResult::class.java,
            ReferenceMatchResult::class.java,
            DebugInfo::class.java,
        )

    override fun create(
        type: Type,
        annotations: Set<Annotation>,
        moshi: Moshi,
    ): JsonAdapter<*>? {
        if (Types.getRawType(type) !in lenientTypes) return null
        val delegate = moshi.nextAdapter<Any>(this, type, annotations)
        return object : JsonAdapter<Any>() {
            override fun fromJson(reader: JsonReader): Any? {
                val tree = reader.readJsonValue()
                return try {
                    delegate.fromJsonValue(tree)
                } catch (e: JsonDataException) {
                    Log.w(TAG, "Ignoring malformed verdict field ${reader.path}: ${e.message}")
                    null
                }
            }

            override fun toJson(
                writer: JsonWriter,
                value: Any?,
            ) = delegate.toJson(writer, value)
        }
    }
}

/** The Moshi every API response is decoded with (the client and its tests). */
internal fun apiMoshi(): Moshi =
    Moshi.Builder()
        .add(LenientVerdictParts)
        .addLast(KotlinJsonAdapterFactory())
        .build()
