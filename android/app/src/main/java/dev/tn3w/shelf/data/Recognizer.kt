package dev.tn3w.shelf.data

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

class Reading(
    val text: String,
    val confidence: Float,
    val height: Float,
    val positions: List<Float> = emptyList(),
    val dots: List<Dot> = emptyList(),
)

private const val RELU = 1
private const val GELU = 2
private const val HARD_SWISH = 3
private const val HARD_SIGMOID = 4

private sealed interface Layer

private class Conv(
    val inputs: Int,
    val outputs: Int,
    val kernelHeight: Int,
    val kernelWidth: Int,
    val strideHeight: Int,
    val strideWidth: Int,
    val groups: Int,
    val activation: Int,
    val residual: Int,
    val weights: FloatArray,
    val bias: FloatArray,
) : Layer {
    val patch = inputs / groups * kernelHeight * kernelWidth
}

private class Squeeze(val reduce: Conv, val expand: Conv) : Layer

private class Pool(val height: Int, val width: Int) : Layer

private class Tensor(
    val channels: Int,
    val height: Int,
    val width: Int,
    val data: FloatArray,
)

private fun erf(value: Float): Float {
    val t = 1 / (1 + 0.3275911f * abs(value))
    val polynomial = t * (
        0.25482959f +
            t * (-0.28449672f + t * (1.4214138f + t * (-1.4531521f + t * 1.0614054f)))
        )
    val result = 1 - polynomial * exp(-value * value)
    return if (value >= 0) result else -result
}

private fun hardSigmoid(value: Float) = (value / 6 + 0.5f).coerceIn(0f, 1f)

private fun activate(value: Float, activation: Int) = when (activation) {
    RELU -> max(value, 0f)
    GELU -> 0.5f * value * (1 + erf(value / sqrt(2f)))
    HARD_SWISH -> value * hardSigmoid(value)
    HARD_SIGMOID -> hardSigmoid(value)
    else -> value
}

private class Product(
    val layer: Conv,
    val columns: FloatArray,
    val offset: Int,
    val output: FloatArray,
    val area: Int,
) {
    private val patch = layer.patch
    private val weights = layer.weights

    private fun put(channel: Int, position: Int, value: Float) {
        output[channel * area + position] =
            activate(value + layer.bias[channel], layer.activation)
    }

    private fun dot(channel: Int, position: Int): Float {
        var total = 0f
        for (k in 0 until patch) {
            total += weights[channel * patch + k] * columns[offset + k * area + position]
        }
        return total
    }

    fun row(channel: Int) {
        val start = channel * area
        output.fill(0f, start, start + area)
        for (k in 0 until patch) {
            val weight = weights[channel * patch + k]
            val base = offset + k * area
            for (position in 0 until area) {
                output[start + position] += weight * columns[base + position]
            }
        }
        for (position in 0 until area) put(channel, position, output[start + position])
    }

    fun block(channel: Int) {
        var position = 0
        while (position + 4 <= area) {
            tile(channel, position)
            position += 4
        }
        for (rest in position until area) {
            for (row in channel until channel + 4) put(row, rest, dot(row, rest))
        }
    }

    private fun tile(channel: Int, position: Int) {
        val first = channel * patch
        var first0 = 0f
        var first1 = 0f
        var first2 = 0f
        var first3 = 0f
        var second0 = 0f
        var second1 = 0f
        var second2 = 0f
        var second3 = 0f
        var third0 = 0f
        var third1 = 0f
        var third2 = 0f
        var third3 = 0f
        var fourth0 = 0f
        var fourth1 = 0f
        var fourth2 = 0f
        var fourth3 = 0f
        for (k in 0 until patch) {
            val index = offset + k * area + position
            val column0 = columns[index]
            val column1 = columns[index + 1]
            val column2 = columns[index + 2]
            val column3 = columns[index + 3]
            val weight0 = weights[first + k]
            val weight1 = weights[first + patch + k]
            val weight2 = weights[first + 2 * patch + k]
            val weight3 = weights[first + 3 * patch + k]
            first0 += weight0 * column0
            first1 += weight0 * column1
            first2 += weight0 * column2
            first3 += weight0 * column3
            second0 += weight1 * column0
            second1 += weight1 * column1
            second2 += weight1 * column2
            second3 += weight1 * column3
            third0 += weight2 * column0
            third1 += weight2 * column1
            third2 += weight2 * column2
            third3 += weight2 * column3
            fourth0 += weight3 * column0
            fourth1 += weight3 * column1
            fourth2 += weight3 * column2
            fourth3 += weight3 * column3
        }
        val sums = floatArrayOf(
            first0, first1, first2, first3, second0, second1, second2, second3,
            third0, third1, third2, third3, fourth0, fourth1, fourth2, fourth3,
        )
        sums.forEachIndexed { index, sum ->
            put(channel + index / 4, position + index % 4, sum)
        }
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
        else -> Pool(buffer.getInt(), buffer.getInt())
    }

    private fun readConv(buffer: ByteBuffer): Conv {
        val spec = IntArray(10) { buffer.getInt() }
        val (inputs, outputs, kernelHeight, kernelWidth) = spec
        val scales = FloatArray(outputs) { buffer.getFloat() }
        val bias = FloatArray(outputs) { buffer.getFloat() }
        val perOutput = inputs / spec[6] * kernelHeight * kernelWidth
        val bytes = spec[9] == 8
        val weights = FloatArray(outputs * perOutput) {
            val value = if (bytes) buffer.get().toInt() else buffer.getShort().toInt()
            value * scales[it / perOutput]
        }
        return Conv(
            inputs, outputs, kernelHeight, kernelWidth, spec[4], spec[5], spec[6],
            spec[7], spec[8], weights, bias,
        )
    }

    fun read(line: Gray, height: Float = line.height.toFloat()): Reading {
        val pixels = FloatArray(line.pixels.size) { line.pixels[it] / 127.5f - 1f }
        var tensor = Tensor(1, line.height, line.width, pixels)
        val inputs = ArrayList<Tensor>(layers.size)
        for (layer in layers) {
            inputs += tensor
            tensor = when (layer) {
                is Conv -> apply(layer, tensor)
                is Squeeze -> excite(layer, tensor)
                is Pool -> pool(layer, tensor)
            }
            if (layer is Conv && layer.residual > 0) {
                add(tensor, inputs[inputs.size - layer.residual])
            }
        }
        return decode(tensor, height)
    }

    private fun add(target: Tensor, source: Tensor) {
        for (index in target.data.indices) target.data[index] += source.data[index]
    }

    private fun excite(layer: Squeeze, input: Tensor): Tensor {
        val area = input.height * input.width
        val means = FloatArray(input.channels) { channel ->
            var total = 0f
            for (index in channel * area until (channel + 1) * area) {
                total +=
                    input.data[index]
            }
            total / area
        }
        val gate =
            apply(layer.expand, apply(layer.reduce, Tensor(input.channels, 1, 1, means)))
        val data = FloatArray(input.data.size) { input.data[it] * gate.data[it / area] }
        return Tensor(input.channels, input.height, input.width, data)
    }

    private fun pool(layer: Pool, input: Tensor): Tensor {
        val height = input.height / layer.height
        val width = input.width / layer.width
        val data = FloatArray(input.channels * height * width) { index ->
            val plane = index / (height * width) * input.height * input.width
            val top = index / width % height * layer.height
            val left = index % width * layer.width
            var total = 0f
            for (y in top until top + layer.height) {
                for (x in left until left + layer.width) {
                    total += input.data[plane + y * input.width + x]
                }
            }
            total / (layer.height * layer.width)
        }
        return Tensor(input.channels, height, width, data)
    }

    private fun apply(layer: Conv, input: Tensor): Tensor {
        val padTop = (layer.kernelHeight - 1) / 2
        val padLeft = (layer.kernelWidth - 1) / 2
        val height =
            (input.height + 2 * padTop - layer.kernelHeight) / layer.strideHeight + 1
        val width =
            (input.width + 2 * padLeft - layer.kernelWidth) / layer.strideWidth + 1
        val area = height * width
        val output = FloatArray(layer.outputs * area)
        val groupInputs = layer.inputs / layer.groups
        val groupOutputs = layer.outputs / layer.groups
        val patch = layer.patch
        val pointwise =
            patch == groupInputs && layer.strideHeight * layer.strideWidth == 1
        val columns = if (pointwise) input.data else FloatArray(patch * area)
        for (group in 0 until layer.groups) {
            val offset = if (pointwise) group * groupInputs * area else 0
            if (!pointwise) {
                val first = group * groupInputs
                unfold(layer, input, first, groupInputs, height, width, columns)
            }
            val product = Product(layer, columns, offset, output, area)
            var channel = group * groupOutputs
            val end = channel + groupOutputs
            while (layer.groups == 1 && channel + 4 <= end) {
                product.block(channel)
                channel += 4
            }
            for (rest in channel until end) product.row(rest)
        }
        return Tensor(layer.outputs, height, width, output)
    }

    private fun unfold(
        layer: Conv,
        input: Tensor,
        firstChannel: Int,
        channels: Int,
        height: Int,
        width: Int,
        columns: FloatArray,
    ) {
        val padTop = (layer.kernelHeight - 1) / 2
        val padLeft = (layer.kernelWidth - 1) / 2
        var row = 0
        for (channel in firstChannel until firstChannel + channels) {
            val plane = channel * input.height * input.width
            for (kernelY in 0 until layer.kernelHeight) {
                for (kernelX in 0 until layer.kernelWidth) {
                    var position = row * height * width
                    for (y in 0 until height) {
                        val sourceY = y * layer.strideHeight + kernelY - padTop
                        val inside = sourceY in 0 until input.height
                        for (x in 0 until width) {
                            val sourceX = x * layer.strideWidth + kernelX - padLeft
                            columns[position++] =
                                if (inside && sourceX in 0 until input.width) {
                                    input.data[plane + sourceY * input.width + sourceX]
                                } else {
                                    0f
                                }
                        }
                    }
                    row++
                }
            }
        }
    }

    private fun decode(tensor: Tensor, height: Float): Reading {
        val steps = tensor.width
        val text = StringBuilder()
        var previous = 0
        var confidence = 0.0
        val positions = mutableListOf<Float>()
        for (step in 0 until steps) {
            var best = 0
            for (channel in 1 until tensor.channels) {
                if (tensor.data[channel * steps + step] >
                    tensor.data[best * steps + step]
                ) {
                    best = channel
                }
            }
            if (best != 0 && best != previous) {
                text.append(alphabet[best - 1])
                positions += (step + 0.5f) / steps
                confidence += probability(tensor, step, best)
            }
            previous = best
        }
        val mean = if (text.isEmpty()) 0.0 else confidence / text.length
        return Reading(text.toString(), mean.toFloat(), height, positions)
    }

    private fun probability(tensor: Tensor, step: Int, chosen: Int): Double {
        val steps = tensor.width
        val top = tensor.data[chosen * steps + step]
        var total = 0.0
        for (channel in 0 until tensor.channels) {
            total += exp((tensor.data[channel * steps + step] - top).toDouble())
        }
        return 1 / total
    }
}
