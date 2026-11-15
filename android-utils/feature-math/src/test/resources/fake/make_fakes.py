"""Generates the tiny fake VisionEncoderDecoder models used by VedRunnerTest.
Run: python make_fakes.py   (needs the `onnx` package). The decoder "predicts" token (sequence length so far + 2),
so a runner that feeds its cache wrongly produces a different token sequence.
Vocabulary: 3 '1', 4 '+', 5 '\\cdot', 6 'x', 7 '=', 8 '2', 9 eos.
"""
import json, os
import numpy as np
import onnx
from onnx import TensorProto as TP, helper as h, numpy_helper as nh

V = 10
OPSET = [h.make_opsetid("", 17)]


def const(name, arr):
    return nh.from_array(np.array(arr), name)


def save(graph, path):
    m = h.make_model(graph, opset_imports=OPSET)
    m.ir_version = 9
    onnx.checker.check_model(m)
    onnx.save(m, path)


def encoder(path):
    nodes = [
        h.make_node("ReduceMean", ["pixel_values"], ["mean"], axes=[1, 2, 3], keepdims=0),
        h.make_node("Reshape", ["mean", "shape_b11"], ["m3"]),
        h.make_node("Expand", ["m3", "shape_134"], ["last_hidden_state"]),
    ]
    g = h.make_graph(
        nodes, "enc",
        [h.make_tensor_value_info("pixel_values", TP.FLOAT, ["b", "c", "h", "w"])],
        [h.make_tensor_value_info("last_hidden_state", TP.FLOAT, ["b", 3, 4])],
        [const("shape_b11", np.array([-1, 1, 1], np.int64)), const("shape_134", np.array([1, 3, 4], np.int64))],
    )
    save(g, path)


def decoder(path, past):
    inits = [
        const("one_i", np.array([1], np.int64)), const("two", np.array(2, np.int64)),
        const("vocab_r", np.arange(V, dtype=np.int64)), const("v1", np.array([V], np.int64)),
        const("scalar_shape", np.array([], np.int64)), const("shape_11v", np.array([1, 1, V], np.int64)),
        const("c1", np.array([1], np.int64)), const("c2", np.array([2], np.int64)), const("idx2", np.array([2], np.int64)),
        const("idx1", np.array([1], np.int64)),
    ]
    nodes = [
        h.make_node("Shape", ["input_ids"], ["ids_shape"]),
        h.make_node("Gather", ["ids_shape", "idx1"], ["s1"], axis=0),
        h.make_node("Reshape", ["s1", "scalar_shape"], ["s"]),
    ]
    inputs = [
        h.make_tensor_value_info("input_ids", TP.INT64, ["b", "s"]),
        h.make_tensor_value_info("encoder_hidden_states", TP.FLOAT, ["b", "e", 4]),
    ]
    outputs = [h.make_tensor_value_info("logits", TP.FLOAT, ["b", "s", V])]
    if past:
        inputs.append(h.make_tensor_value_info("use_cache_branch", TP.BOOL, [1]))
        for kind in ("decoder", "encoder"):
            for kv in ("key", "value"):
                inputs.append(h.make_tensor_value_info(f"past_key_values.0.{kind}.{kv}", TP.FLOAT, ["b", 1, f"p_{kind}", 2]))
                outputs.append(h.make_tensor_value_info(f"present.0.{kind}.{kv}", TP.FLOAT, ["b", 1, f"q_{kind}", 2]))
        nodes += [
            h.make_node("Shape", ["past_key_values.0.decoder.key"], ["pk_shape"]),
            h.make_node("Gather", ["pk_shape", "idx2"], ["p1"], axis=0),
            h.make_node("Reshape", ["p1", "scalar_shape"], ["p"]),
            h.make_node("Add", ["p", "s"], ["n"]),
            h.make_node("Concat", ["c1", "c1", "s1", "c2"], ["ones_shape"], axis=0),
            h.make_node("ConstantOfShape", ["ones_shape"], ["new_kv"], value=nh.from_array(np.array([1.0], np.float32))),
            h.make_node("Concat", ["past_key_values.0.decoder.key", "new_kv"], ["present.0.decoder.key"], axis=2),
            h.make_node("Concat", ["past_key_values.0.decoder.value", "new_kv"], ["present.0.decoder.value"], axis=2),
            h.make_node("Shape", ["encoder_hidden_states"], ["eh_shape"]),
            h.make_node("Gather", ["eh_shape", "idx1"], ["e1"], axis=0),
            h.make_node("Concat", ["c1", "c1", "e1", "c2"], ["enc_shape"], axis=0),
            h.make_node("ConstantOfShape", ["enc_shape"], ["enc_kv"], value=nh.from_array(np.array([1.0], np.float32))),
            h.make_node("Identity", ["enc_kv"], ["present.0.encoder.key"]),
            h.make_node("Identity", ["enc_kv"], ["present.0.encoder.value"]),
        ]
    else:
        nodes.append(h.make_node("Identity", ["s"], ["n"]))
    nodes += [
        h.make_node("Add", ["n", "two"], ["pred"]),
        h.make_node("Equal", ["vocab_r", "pred"], ["eq"]),
        h.make_node("Cast", ["eq"], ["onehot"], to=TP.FLOAT),
        h.make_node("Reshape", ["onehot", "shape_11v"], ["oh3"]),
        h.make_node("Shape", ["input_ids"], ["ids_shape2"]),
        h.make_node("Concat", ["ids_shape2", "v1"], ["logit_shape"], axis=0),
        h.make_node("Expand", ["oh3", "logit_shape"], ["logits"]),
    ]
    save(h.make_graph(nodes, "dec", inputs, outputs, inits), path)


def tokenizer(path):
    vocab = {"<pad>": 0, "<s>": 1, "</s>": 2, "Ġ1": 3, "Ġ+": 4, "Ġ\\cdot": 5, "Ġx": 6, "Ġ=": 7, "Ġ2": 8, "<eos>": 9}
    tok = {
        "version": "1.0",
        "added_tokens": [{"id": i, "content": c, "special": True} for c, i in [("<pad>", 0), ("<s>", 1), ("</s>", 2), ("<eos>", 9)]],
        "pre_tokenizer": {"type": "ByteLevel"}, "decoder": {"type": "ByteLevel"},
        "model": {"type": "BPE", "vocab": vocab, "merges": []},
    }
    json.dump(tok, open(path, "w"), ensure_ascii=False)


for name, past in (("fake-none", False), ("fake-past", True)):
    os.makedirs(name, exist_ok=True)
    encoder(f"{name}/encoder.onnx")
    decoder(f"{name}/decoder.onnx", past)
    tokenizer(f"{name}/tokenizer.json")
    json.dump({
        "name": name, "encoder": "encoder.onnx", "decoder": "decoder.onnx", "tokenizer": "tokenizer.json",
        "input": {"width": 16, "height": 8, "channels": 1, "resize": "keep_aspect_pad", "padColor": 255, "mean": [0.5], "std": [0.5]},
        "bos": 1, "eos": 9, "pad": 0, "maxTokens": 32,
        "decoderCache": "merged_past" if past else "none",
        "postprocess": {"joinSpacedTokens": True, "stripWrappers": []},
    }, open(f"{name}/model.json", "w"), indent=1)
print("ok")
