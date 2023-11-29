package com.posthog.internal

import com.google.gson.JsonParseException
import com.google.gson.ToNumberStrategy
import com.google.gson.stream.JsonReader
import com.google.gson.stream.MalformedJsonException
import java.io.IOException

/**
 * a Gson Number type adapter to deserialize the Java Number type
 * By Default Gson converts Numbers to Doubles, so 10 becomes 10.0 and so on
 * This converter is similar to ToNumberPolicy.LONG_OR_DOUBLE which is available in Gson 2.8.7
 * but we try to convert it to Integer first, then Long and then Double.
 */
internal class GsonNumberPolicy : ToNumberStrategy {
    @Throws(
        JsonParseException::class,
        IOException::class,
        JsonParseException::class,
        MalformedJsonException::class,
        IllegalStateException::class,
    )
    override fun readNumber(reader: JsonReader): Number {
        val value = reader.nextString()
        return try {
            value.toInt()
        } catch (intE: NumberFormatException) {
            try {
                value.toLong()
            } catch (longE: NumberFormatException) {
                try {
                    val d = value.toDouble()
                    if ((d.isInfinite() || d.isNaN()) && !reader.isLenient) {
                        throw MalformedJsonException("JSON forbids NaN and infinities: " + d + "; at path " + reader.previousPath)
                    }
                    d
                } catch (doubleE: NumberFormatException) {
                    throw JsonParseException(
                        "Cannot parse " + value + "; at path " + reader.previousPath,
                        doubleE,
                    )
                }
            }
        }
    }
}
