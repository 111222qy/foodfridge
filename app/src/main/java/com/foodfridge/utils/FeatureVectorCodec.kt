package com.foodfridge.utils

import android.util.Base64
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import kotlin.math.sqrt

object FeatureVectorCodec {
    const val FEATURE_DIMENSION = 512
    private const val FORMAT_PREFIX_FLOAT32 = "fv2f:"
    private const val INT_BYTES = 4
    private const val FLOAT_BYTES = 4
    private const val CHECKSUM_BYTES = 4
    private val WHITESPACE_REGEX = Regex("\\s+")

    fun encode(featureVector: FloatArray): String {
        if (featureVector.isEmpty()) return ""

        return try {
            val payloadSize = INT_BYTES + featureVector.size * FLOAT_BYTES
            val payloadBuffer = ByteBuffer.allocate(payloadSize).order(ByteOrder.LITTLE_ENDIAN)
            payloadBuffer.putInt(featureVector.size)
            featureVector.forEach { value ->
                payloadBuffer.putFloat(sanitizeFloat(value))
            }

            val payloadBytes = payloadBuffer.array()
            val checksum = crc32(payloadBytes).toInt()
            val packet = ByteBuffer.allocate(payloadBytes.size + CHECKSUM_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            packet.put(payloadBytes)
            packet.putInt(checksum)

            FORMAT_PREFIX_FLOAT32 + Base64.encodeToString(packet.array(), Base64.NO_WRAP)
        } catch (_: Exception) {
            encodeLegacyText(featureVector)
        }
    }

    fun decode(serialized: String?): FloatArray? {
        val raw = serialized?.trim().orEmpty()
        if (raw.isBlank()) return null

        decodeFloat32Packet(raw)?.let { return it }
        return decodeLegacyText(raw)
    }

    fun normalize(featureVector: FloatArray): FloatArray {
        if (featureVector.isEmpty()) return featureVector

        var norm = 0f
        featureVector.forEach { value ->
            val safeValue = sanitizeFloat(value)
            norm += safeValue * safeValue
        }
        norm = sqrt(norm)

        if (norm <= 0f) {
            val copied = featureVector.copyOf()
            for (index in copied.indices) {
                copied[index] = sanitizeFloat(copied[index])
            }
            return copied
        }

        val normalized = FloatArray(featureVector.size)
        for (index in featureVector.indices) {
            normalized[index] = sanitizeFloat(featureVector[index]) / norm
        }
        return normalized
    }

    private fun decodeFloat32Packet(raw: String): FloatArray? {
        if (!raw.regionMatches(0, FORMAT_PREFIX_FLOAT32, 0, FORMAT_PREFIX_FLOAT32.length, ignoreCase = true)) {
            return null
        }

        val payload = raw.substring(FORMAT_PREFIX_FLOAT32.length).trim()
        if (payload.isBlank()) return null

        val packet = try {
            Base64.decode(payload, Base64.NO_WRAP)
        } catch (_: Exception) {
            return null
        }

        if (packet.size < INT_BYTES + CHECKSUM_BYTES) return null

        val payloadSize = packet.size - CHECKSUM_BYTES
        val payloadBytes = packet.copyOfRange(0, payloadSize)
        val expectedChecksum = ByteBuffer
            .wrap(packet, payloadSize, CHECKSUM_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .int

        if (crc32(payloadBytes).toInt() != expectedChecksum) {
            return null
        }

        val payloadBuffer = ByteBuffer.wrap(payloadBytes).order(ByteOrder.LITTLE_ENDIAN)
        val dimension = payloadBuffer.int
        if (dimension < FEATURE_DIMENSION) return null

        val expectedPayloadSize = INT_BYTES + dimension * FLOAT_BYTES
        if (payloadBytes.size != expectedPayloadSize) return null

        val vector = FloatArray(FEATURE_DIMENSION)
        for (index in 0 until FEATURE_DIMENSION) {
            val value = payloadBuffer.float
            if (!value.isFinite()) return null
            vector[index] = value
        }

        return vector
    }

    private fun decodeLegacyText(raw: String): FloatArray? {
        val values = raw.split(WHITESPACE_REGEX)
        if (values.size < FEATURE_DIMENSION) return null

        val vector = FloatArray(FEATURE_DIMENSION)
        for (index in 0 until FEATURE_DIMENSION) {
            val value = values[index].toFloatOrNull() ?: return null
            if (!value.isFinite()) return null
            vector[index] = value
        }
        return vector
    }

    private fun encodeLegacyText(featureVector: FloatArray): String {
        return featureVector.joinToString(separator = " ") { value ->
            sanitizeFloat(value).toString()
        }
    }

    private fun sanitizeFloat(value: Float): Float {
        return if (value.isFinite()) value else 0f
    }

    private fun crc32(bytes: ByteArray): Long {
        val crc = CRC32()
        crc.update(bytes)
        return crc.value
    }
}