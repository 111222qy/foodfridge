package com.foodfridge.data.remote.crypto

import org.bouncycastle.crypto.engines.SM4Engine
import org.bouncycastle.crypto.modes.CBCBlockCipher
import org.bouncycastle.crypto.paddings.PaddedBufferedBlockCipher
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import org.bouncycastle.util.encoders.Hex

object SM4Crypto {

    private val KEY = com.foodfridge.BuildConfig.SM4_KEY
    private val IV = com.foodfridge.BuildConfig.SM4_IV

    fun encrypt(plainText: String): String {
        val keyBytes = KEY.toByteArray(Charsets.UTF_8)
        val ivBytes = IV.toByteArray(Charsets.UTF_8)
        val inputBytes = plainText.toByteArray(Charsets.UTF_8)
        val paddedInput = zeroPadding(inputBytes, 16)

        val cipher = PaddedBufferedBlockCipher(CBCBlockCipher(SM4Engine()))
        val params = ParametersWithIV(KeyParameter(keyBytes), ivBytes)
        cipher.init(true, params)

        val output = ByteArray(cipher.getOutputSize(paddedInput.size))
        var len = cipher.processBytes(paddedInput, 0, paddedInput.size, output, 0)
        len += cipher.doFinal(output, len)

        return Hex.toHexString(output.copyOf(len))
    }

    fun decrypt(hexCipherText: String): String {
        val cleanHex = hexCipherText.filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
        if (cleanHex.length % 2 != 0) {
            throw IllegalArgumentException("Invalid hex string length")
        }

        val keyBytes = KEY.toByteArray(Charsets.UTF_8)
        val ivBytes = IV.toByteArray(Charsets.UTF_8)
        val inputBytes = Hex.decode(cleanHex)

        val cipher = PaddedBufferedBlockCipher(CBCBlockCipher(SM4Engine()))
        val params = ParametersWithIV(KeyParameter(keyBytes), ivBytes)
        cipher.init(false, params)

        val output = ByteArray(cipher.getOutputSize(inputBytes.size))
        var len = cipher.processBytes(inputBytes, 0, inputBytes.size, output, 0)
        len += cipher.doFinal(output, len)

        val decrypted = output.copyOf(len)
        val unpadded = removeZeroPadding(decrypted)
        return String(unpadded, Charsets.UTF_8)
    }

    private fun zeroPadding(data: ByteArray, blockSize: Int): ByteArray {
        val paddingLen = blockSize - (data.size % blockSize)
        if (paddingLen == blockSize) return data
        return data + ByteArray(paddingLen)
    }

    private fun removeZeroPadding(data: ByteArray): ByteArray {
        var end = data.size
        while (end > 0 && data[end - 1] == 0.toByte()) {
            end--
        }
        return data.copyOf(end)
    }
}
