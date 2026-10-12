import hashlib
import struct
import sys
import unicodedata
import urllib.request
from pathlib import Path

import numpy as np
import onnx
import yaml
from onnx import numpy_helper

REPOSITORY = (
    "https://huggingface.co/PaddlePaddle/PP-OCRv6_tiny_rec_onnx/resolve/"
    "2612ab37152ae0a677521bae4e1e3d4fb4cf7c30"
)
CHECKSUMS = {
    "inference.onnx": "9ef676d6ed3c88256a2d92c640c44f25b0c40947e111b14b8be8f594091563e6",
    "inference.yml": "66170210bad538e83fff3c4a3867e547d6bf20b50d64b20347c4b913f3034ea1",
}
OUTPUT = Path(__file__).resolve().parent.parent / "android/app/src/main/assets/scan.bin"
HEIGHT = 48
BITS = 16
EXTRA = "’‘“”–—…«»·•"
CONV, SQUEEZE, POOL = 0, 1, 2
NONE, RELU, GELU, HARD_SWISH, HARD_SIGMOID = 0, 1, 2, 3, 4


def download(name):
    with urllib.request.urlopen(f"{REPOSITORY}/{name}") as response:
        data = response.read()
    if hashlib.sha256(data).hexdigest() != CHECKSUMS[name]:
        sys.exit(f"{name}: SHA-256 mismatch")
    return data


def latin(character):
    if character.isascii() and character.isprintable() or character in EXTRA:
        return True
    return character.isalpha() and unicodedata.name(character, "").startswith("LATIN")


def attributes(node):
    return {item.name: onnx.helper.get_attribute_value(item) for item in node.attribute}


class Converter:
    def __init__(self, model):
        self.constants = {
            item.name: numpy_helper.to_array(item).astype(np.float64)
            for item in model.graph.initializer
        }
        self.nodes = model.graph.node
        self.layers = []
        self.owner = {model.graph.input[0].name: -1}
        self.gates = set()
        self.pending = None

    def constant(self, node):
        names = [name for name in node.input if name in self.constants]
        return self.constants[names[0]] if names else None

    def tensors(self, node):
        return [name for name in node.input if name not in self.constants]

    def new_conv(self, node, weight):
        return {
            "kind": CONV, "weight": weight, "bias": np.zeros(weight.shape[0], np.float64),
            "stride": attributes(node)["strides"], "groups": attributes(node)["group"],
            "activation": NONE, "residual": 0,
        }

    def convert(self):
        for node in self.nodes:
            getattr(self, node.op_type.lower())(node)
        return self.layers

    def alias(self, node):
        source = self.tensors(node)[0]
        if self.pending and source in self.pending["outputs"]:
            self.pending["outputs"].add(node.output[0])
        if source in self.owner:
            self.owner[node.output[0]] = self.owner[source]

    identity = squeeze = unsqueeze = transpose = softmax = erf = alias

    def conv(self, node):
        weight = self.constant(node)
        source = self.tensors(node)[0]
        if self.pending is not None and source in self.pending["outputs"]:
            self.pending["convs"].append(self.new_conv(node, weight))
            self.pending["outputs"].add(node.output[0])
            return
        if self.owner[source] == -1:
            weight = weight.sum(axis=1, keepdims=True)
        self.layers.append(self.new_conv(node, weight))
        self.owner[node.output[0]] = len(self.layers) - 1

    def batchnormalization(self, node):
        scale, shift, mean, variance = (self.constants[name] for name in node.input[1:])
        layer = self.layers[self.owner[node.input[0]]]
        factor = scale / np.sqrt(variance + 1e-5)
        layer["weight"] = layer["weight"] * factor[:, None, None, None]
        layer["bias"] = (layer["bias"] - mean) * factor + shift
        self.alias(node)

    def add(self, node):
        constant = self.constant(node)
        source = self.tensors(node)
        if constant is not None and source[0].startswith("Erf"):
            return self.alias(node)
        if constant is not None and self.pending and source[0] in self.pending["outputs"]:
            self.pending["convs"][-1]["bias"] += constant.reshape(-1)
            self.pending["outputs"].add(node.output[0])
            return
        if constant is not None:
            index = self.owner[source[0]]
            self.layers[index]["bias"] = self.layers[index]["bias"] + constant.reshape(-1)
            return self.alias(node)
        last = max(self.owner[name] for name in source)
        earliest = min(self.owner[name] for name in source)
        self.layers[last]["residual"] = last - earliest
        self.owner[node.output[0]] = last

    def div(self, node):
        self.layers[self.owner[node.input[0]]]["activation"] = GELU
        self.alias(node)

    def relu(self, node):
        if self.pending and node.input[0] in self.pending["outputs"]:
            self.pending["convs"][-1]["activation"] = RELU
            self.pending["outputs"].add(node.output[0])
            return
        self.layers[self.owner[node.input[0]]]["activation"] = RELU
        self.alias(node)

    def hardsigmoid(self, node):
        if self.pending and node.input[0] in self.pending["outputs"]:
            self.pending["convs"][-1]["activation"] = HARD_SIGMOID
            self.gates.add(node.output[0])
            return
        self.layers[self.owner[node.input[0]]]["activation"] = HARD_SWISH
        self.alias(node)

    def mul(self, node):
        source = self.tensors(node)
        gate = next((name for name in source if name in self.gates), None)
        if gate is not None:
            self.layers.append({"kind": SQUEEZE, "convs": self.pending["convs"]})
            self.pending = None
            self.owner[node.output[0]] = len(self.layers) - 1
            return
        self.owner[node.output[0]] = max(self.owner[name] for name in source)

    def reducemean(self, node):
        self.pending = {"convs": [], "outputs": {node.output[0]}}

    def averagepool(self, node):
        self.layers.append({"kind": POOL, "kernel": attributes(node)["kernel_shape"]})
        self.owner[node.output[0]] = len(self.layers) - 1

    def matmul(self, node):
        matrix = self.constant(node)
        layer = self.layers[self.owner[self.tensors(node)[0]]]
        if layer["kind"] == CONV and layer.get("linear"):
            weight = layer["weight"][:, :, 0, 0]
            layer["weight"] = (matrix.T @ weight)[:, :, None, None]
            layer["bias"] = layer["bias"] @ matrix
        else:
            self.layers.append({
                "kind": CONV, "weight": matrix.T[:, :, None, None],
                "bias": np.zeros(matrix.shape[1], np.float64), "stride": [1, 1],
                "groups": 1, "activation": NONE, "residual": 0, "linear": True,
            })
        self.owner[node.output[0]] = len(self.layers) - 1


def prune(layers, alphabet):
    keep = [0] + [index + 1 for index, letter in enumerate(alphabet) if latin(letter)]
    head = layers[-1]
    head["weight"] = head["weight"][keep]
    head["bias"] = head["bias"][keep]
    return "".join(alphabet[index - 1] for index in keep[1:])


def conv_bytes(layer):
    weight = layer["weight"]
    outputs, per_group, kernel_height, kernel_width = weight.shape
    groups = layer["groups"]
    limit = 2 ** (BITS - 1) - 1
    scales = np.abs(weight).reshape(outputs, -1).max(axis=1).clip(1e-8) / limit
    values = np.round(weight / scales[:, None, None, None]).clip(-limit, limit)
    header = struct.pack(
        "<10i", per_group * groups, outputs, kernel_height, kernel_width,
        *layer["stride"], groups, layer["activation"], layer["residual"], BITS,
    )
    return (
        header + scales.astype("<f4").tobytes() + layer["bias"].astype("<f4").tobytes()
        + values.astype("<i2").tobytes()
    )


def serialize(layers, alphabet):
    encoded = alphabet.encode()
    output = bytearray(b"SCAN") + struct.pack("<iii", 3, HEIGHT, len(encoded)) + encoded
    output += struct.pack("<i", len(layers))
    for layer in layers:
        output += struct.pack("<i", layer["kind"])
        if layer["kind"] == POOL:
            output += struct.pack("<ii", *layer["kernel"])
        elif layer["kind"] == SQUEEZE:
            output += b"".join(conv_bytes(conv) for conv in layer["convs"])
        else:
            output += conv_bytes(layer)
    return bytes(output)


def main():
    model = onnx.load_from_string(download("inference.onnx"))
    config = yaml.safe_load(download("inference.yml"))
    alphabet = config["PostProcess"]["character_dict"] + [" "]
    layers = Converter(model).convert()
    data = serialize(layers, prune(layers, alphabet))
    output = Path(sys.argv[1]) if len(sys.argv) > 1 else OUTPUT
    output.write_bytes(data)
    print(f"{output.name}: {len(data)} bytes, sha256 {hashlib.sha256(data).hexdigest()}")


if __name__ == "__main__":
    main()
