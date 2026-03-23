package com.frontieraudio.app.service.audio

object AudioResampler {

    fun resample(input: ShortArray, fromRate: Int, toRate: Int): ShortArray {
        if (fromRate == toRate) return input
        val outputLength = (input.size.toLong() * toRate / fromRate).toInt()
        val output = ShortArray(outputLength)
        val ratio = fromRate.toDouble() / toRate.toDouble()
        for (i in output.indices) {
            val srcIndex = i * ratio
            val srcInt = srcIndex.toInt()
            val frac = srcIndex - srcInt
            if (srcInt + 1 < input.size) {
                output[i] = (input[srcInt] * (1.0 - frac) + input[srcInt + 1] * frac).toInt().toShort()
            } else {
                output[i] = input[srcInt.coerceAtMost(input.size - 1)]
            }
        }
        return output
    }
}
