package dev.tn3w.shelf.data

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

private const val GELU_RANGE = 8f
private const val GELU_STEPS = 256f
private const val WARM_UP_READS = 3

class Reading(val text: String, val confidence: Float, val positions: List<Float>)

private enum class Activation { NONE, RELU, GELU, HARD_SWISH, HARD_SIGMOID }

private class Tensor(val channels: Int, val height: Int, val width: Int) {
    val area = height * width
    val data = FloatArray(channels * area)
}

private fun erf(value: Float): Float {
    val t = 1 / (1 + 0.3275911f * abs(value))
    val polynomial = t * (
        0.25482959f +
            t * (-0.28449672f + t * (1.4214138f + t * (-1.4531521f + t * 1.0614054f)))
        )
    val result = 1 - polynomial * exp(-value * value)
    return if (value >= 0) result else -result
}

private val GELU_TABLE = FloatArray((2 * GELU_RANGE * GELU_STEPS).toInt() + 2) {
    val value = it / GELU_STEPS - GELU_RANGE
    0.5f * value * (1 + erf(value * 0.70710677f))
}

private fun gelu(value: Float): Float {
    if (value <= -GELU_RANGE) return 0f
    if (value >= GELU_RANGE) return value
    val position = (value + GELU_RANGE) * GELU_STEPS
    val index = position.toInt()
    return GELU_TABLE[index] +
        (GELU_TABLE[index + 1] - GELU_TABLE[index]) * (position - index)
}

private fun hardSigmoid(value: Float) = (value / 6 + 0.5f).coerceIn(0f, 1f)

private fun activate(value: Float, activation: Activation) = when (activation) {
    Activation.NONE -> value
    Activation.RELU -> max(value, 0f)
    Activation.GELU -> gelu(value)
    Activation.HARD_SWISH -> value * hardSigmoid(value)
    Activation.HARD_SIGMOID -> hardSigmoid(value)
}

private sealed interface Layer {
    val residual: Int
        get() = 0

    fun run(input: Tensor): Tensor
}

private class Conv(
    val inputs: Int,
    val outputs: Int,
    val kernelHeight: Int,
    val kernelWidth: Int,
    val strideHeight: Int,
    val strideWidth: Int,
    val groups: Int,
    val activation: Activation,
    override val residual: Int,
    val weights: FloatArray,
    val bias: FloatArray,
) : Layer {
    private val patch = inputs / groups * kernelHeight * kernelWidth
    private val padTop = (kernelHeight - 1) / 2
    private val padLeft = (kernelWidth - 1) / 2
    private val pointwise =
        groups == 1 && patch == inputs && strideHeight * strideWidth == 1

    override fun run(input: Tensor): Tensor {
        val output = Tensor(
            outputs,
            (input.height + 2 * padTop - kernelHeight) / strideHeight + 1,
            (input.width + 2 * padLeft - kernelWidth) / strideWidth + 1,
        )
        when {
            pointwise -> multiply(input.data, output)
            groups == 1 -> multiply(unfold(input, output), output)
            else -> depthwise(input, output)
        }
        return output
    }

    private fun finish(output: Tensor, channel: Int) {
        val shift = bias[channel]
        val start = channel * output.area
        for (index in start until start + output.area) {
            output.data[index] = activate(output.data[index] + shift, activation)
        }
    }

    private fun multiply(columns: FloatArray, output: Tensor) {
        val area = output.area
        val data = output.data
        var channel = 0
        while (channel + 4 <= outputs) {
            val first = channel * area
            for (k in 0 until patch) {
                val weight0 = weights[channel * patch + k]
                val weight1 = weights[(channel + 1) * patch + k]
                val weight2 = weights[(channel + 2) * patch + k]
                val weight3 = weights[(channel + 3) * patch + k]
                val row = k * area
                for (position in 0 until area) {
                    val value = columns[row + position]
                    data[first + position] += weight0 * value
                    data[first + area + position] += weight1 * value
                    data[first + 2 * area + position] += weight2 * value
                    data[first + 3 * area + position] += weight3 * value
                }
            }
            for (done in channel until channel + 4) finish(output, done)
            channel += 4
        }
        for (rest in channel until outputs) {
            for (k in 0 until patch) {
                val weight = weights[rest * patch + k]
                for (position in 0 until area) {
                    data[rest * area + position] += weight * columns[k * area + position]
                }
            }
            finish(output, rest)
        }
    }

    private fun unfold(input: Tensor, output: Tensor): FloatArray {
        val columns = FloatArray(patch * output.area)
        var position = 0
        for (channel in 0 until inputs) {
            for (kernelY in 0 until kernelHeight) {
                for (kernelX in 0 until kernelWidth) {
                    for (y in 0 until output.height) {
                        val sourceY = y * strideHeight + kernelY - padTop
                        for (x in 0 until output.width) {
                            val sourceX = x * strideWidth + kernelX - padLeft
                            if (sourceY in 0 until input.height &&
                                sourceX in 0 until input.width
                            ) {
                                val row = channel * input.area + sourceY * input.width
                                columns[position] = input.data[row + sourceX]
                            }
                            position++
                        }
                    }
                }
            }
        }
        return columns
    }

    private fun depthwise(input: Tensor, output: Tensor) {
        for (channel in 0 until outputs) {
            for (kernelY in 0 until kernelHeight) {
                for (y in 0 until output.height) {
                    val sourceY = y * strideHeight + kernelY - padTop
                    if (sourceY !in 0 until input.height) continue
                    val source = channel * input.area + sourceY * input.width
                    val target = channel * output.area + y * output.width
                    for (kernelX in 0 until kernelWidth) {
                        val weight =
                            weights[
                                (channel * kernelHeight + kernelY) * kernelWidth +
                                    kernelX,
                            ]
                        accumulate(input, output, weight, kernelX, source, target)
                    }
                }
            }
            finish(output, channel)
        }
    }

    private fun accumulate(
        input: Tensor,
        output: Tensor,
        weight: Float,
        kernelX: Int,
        source: Int,
        target: Int,
    ) {
        val shift = kernelX - padLeft
        val first = max(0, (strideWidth - 1 - shift) / strideWidth)
        val last =
            min(output.width, (input.width - shift + strideWidth - 1) / strideWidth)
        for (x in first until last) {
            output.data[target + x] +=
                weight * input.data[source + x * strideWidth + shift]
        }
    }
}

private class Squeeze(val reduce: Conv, val expand: Conv) : Layer {
    override fun run(input: Tensor): Tensor {
        val means = Tensor(input.channels, 1, 1)
        for (channel in 0 until input.channels) {
            var total = 0f
            for (index in channel * input.area until (channel + 1) * input.area) {
                total += input.data[index]
            }
            means.data[channel] = total / input.area
        }
        val gate = expand.run(reduce.run(means)).data
        val output = Tensor(input.channels, input.height, input.width)
        for (index in output.data.indices) {
            output.data[index] = input.data[index] * gate[index / input.area]
        }
        return output
    }
}

private class Pool(val height: Int, val width: Int) : Layer {
    override fun run(input: Tensor): Tensor {
        val output = Tensor(input.channels, input.height / height, input.width / width)
        for (index in output.data.indices) {
            val plane = index / output.area * input.area
            val top = index / output.width % output.height * height
            val left = index % output.width * width
            var total = 0f
            for (y in top until top + height) {
                for (x in left until left + width) {
                    total += input.data[plane + y * input.width + x]
                }
            }
            output.data[index] = total / (height * width)
        }
        return output
    }
}

class Recognizer(bytes: ByteArray) {
    private val alphabet: String
    private val layers: List<Layer>

    init {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        check(String(bytes, 0, 4) == "SCAN") { "not a scan model" }
        buffer.position(4)
        check(buffer.getInt() == 3) { "unsupported scan model" }
        check(buffer.getInt() == LINE_HEIGHT)
        alphabet = String(ByteArray(buffer.getInt()).also(buffer::get))
        layers = List(buffer.getInt()) { readLayer(buffer) }
    }

    private fun readLayer(buffer: ByteBuffer): Layer = when (buffer.getInt()) {
        0 -> readConv(buffer)
        1 -> Squeeze(readConv(buffer), readConv(buffer))
        2 -> Pool(buffer.getInt(), buffer.getInt())
        else -> error("unknown layer type")
    }

    private fun readConv(buffer: ByteBuffer): Conv {
        val spec = IntArray(10) { buffer.getInt() }
        val (inputs, outputs, kernelHeight, kernelWidth) = spec
        val scales = FloatArray(outputs) { buffer.getFloat() }
        val bias = FloatArray(outputs) { buffer.getFloat() }
        val perOutput = inputs / spec[6] * kernelHeight * kernelWidth
        val weights = FloatArray(outputs * perOutput) {
            val value = if (spec[9] ==
                8
            ) {
                buffer.get().toInt()
            } else {
                buffer.getShort().toInt()
            }
            value * scales[it / perOutput]
        }
        return Conv(
            inputs, outputs, kernelHeight, kernelWidth, spec[4], spec[5], spec[6],
            Activation.entries[spec[7]], spec[8], weights, bias,
        )
    }

    fun warmUp() = repeat(WARM_UP_READS) {
        val width = LINE_HEIGHT * 4
        read(Gray(width, LINE_HEIGHT, FloatArray(width * LINE_HEIGHT)))
    }

    fun read(line: Gray): Reading {
        var tensor = Tensor(1, line.height, line.width)
        for (index in tensor.data.indices) {
            tensor.data[index] =
                line.pixels[index] / 127.5f - 1
        }
        val inputs = ArrayList<Tensor>(layers.size)
        for (layer in layers) {
            inputs += tensor
            tensor = layer.run(tensor)
            if (layer.residual == 0) continue
            val source = inputs[inputs.size - layer.residual].data
            for (index in source.indices) tensor.data[index] += source[index]
        }
        return decode(tensor)
    }

    private fun decode(tensor: Tensor): Reading {
        val steps = tensor.width
        val data = tensor.data
        val text = StringBuilder()
        val positions = mutableListOf<Float>()
        var confidence = 0.0
        var previous = 0
        for (step in 0 until steps) {
            var best = 0
            for (channel in 1 until tensor.channels) {
                if (data[channel * steps + step] >
                    data[best * steps + step]
                ) {
                    best = channel
                }
            }
            if (best != 0 && best != previous) {
                text.append(alphabet[best - 1])
                positions += (step + 0.5f) / steps
                val top = data[best * steps + step]
                var total = 0.0
                for (channel in 0 until tensor.channels) {
                    total += exp((data[channel * steps + step] - top).toDouble())
                }
                confidence += 1 / total
            }
            previous = best
        }
        val mean = if (text.isEmpty()) 0f else (confidence / text.length).toFloat()
        return Reading(text.toString(), mean, positions)
    }
}
