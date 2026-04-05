#!/usr/bin/env python3
"""Export WavLM student to TFLite with INT8 input and FLOAT32 output.

Requested paths:
- Input model:  models/student/wavlm_student_best.keras
- Output model: models/tflite/wavlm_student.tflite

Verification after export:
- File size in MB (target < 2MB)
- Input dtype (must be int8)
- Output dtype (must be float32)
- Cosine similarity between Keras and TFLite outputs on 10 dev samples (target > 0.99)
- PASS or FAIL
"""

from __future__ import annotations

from pathlib import Path
from typing import Iterator

import numpy as np
import tensorflow as tf


MODEL_PATH = Path("models/student/wavlm_student_best.keras")
OUTPUT_PATH = Path("models/tflite/wavlm_student.tflite")

# Use requested lowercase path first, then robust fallback for this workspace.
REP_PATH_CANDIDATES = [
    Path("dataset/distill/train_waveforms.npy"),
    Path("Dataset/distill/train_waveforms.npy"),
]
DEV_PATH_CANDIDATES = [
    Path("dataset/distill/dev_waveforms.npy"),
    Path("Dataset/distill/dev_waveforms.npy"),
]

REP_SAMPLES = 200
DEV_SAMPLES = 10

SIZE_TARGET_MB = 2.0
COSINE_TARGET = 0.99


def resolve_existing_path(candidates: list[Path]) -> Path:
    for p in candidates:
        if p.exists():
            return p
    tried = ", ".join(str(p) for p in candidates)
    raise FileNotFoundError(f"None of the candidate paths exist: {tried}")


def ensure_wave_channel_dim(waves: np.ndarray) -> np.ndarray:
    if waves.ndim == 2:
        return np.expand_dims(waves, axis=-1)
    if waves.ndim == 3 and waves.shape[-1] == 1:
        return waves
    raise ValueError(f"Expected waveform array shape (N, T) or (N, T, 1), got {waves.shape}")


def representative_dataset_generator(rep_waves: np.ndarray) -> Iterator[list[np.ndarray]]:
    n = min(REP_SAMPLES, rep_waves.shape[0])
    for i in range(n):
        sample = rep_waves[i : i + 1].astype(np.float32)
        yield [sample]


def cosine_similarity(a: np.ndarray, b: np.ndarray) -> float:
    a = np.asarray(a, dtype=np.float32)
    b = np.asarray(b, dtype=np.float32)
    a_norm = np.linalg.norm(a)
    b_norm = np.linalg.norm(b)
    if a_norm == 0.0 or b_norm == 0.0:
        return 0.0
    return float(np.dot(a, b) / (a_norm * b_norm))


def main() -> None:
    if not MODEL_PATH.exists():
        raise FileNotFoundError(f"Missing model file: {MODEL_PATH}")

    rep_path = resolve_existing_path(REP_PATH_CANDIDATES)
    dev_path = resolve_existing_path(DEV_PATH_CANDIDATES)

    rep_waves = np.load(rep_path, mmap_mode="r")
    dev_waves = np.load(dev_path, mmap_mode="r")

    rep_waves = ensure_wave_channel_dim(rep_waves)
    dev_waves = ensure_wave_channel_dim(dev_waves)

    model = tf.keras.models.load_model(str(MODEL_PATH), compile=False)

    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS_INT8]
    converter.representative_dataset = lambda: representative_dataset_generator(rep_waves)
    converter.inference_input_type = tf.int8
    converter.inference_output_type = tf.float32

    tflite_model = converter.convert()
    OUTPUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT_PATH.write_bytes(tflite_model)

    interpreter = tf.lite.Interpreter(model_path=str(OUTPUT_PATH))
    interpreter.allocate_tensors()

    input_details = interpreter.get_input_details()[0]
    output_details = interpreter.get_output_details()[0]

    input_dtype = input_details["dtype"]
    output_dtype = output_details["dtype"]

    input_scale, input_zero_point = input_details["quantization"]
    if input_scale == 0.0:
        raise RuntimeError("Invalid input quantization scale (0.0).")

    n_eval = min(DEV_SAMPLES, dev_waves.shape[0])
    cosine_values: list[float] = []

    for i in range(n_eval):
        x = dev_waves[i : i + 1].astype(np.float32)

        keras_out = model.predict(x, verbose=0)[0].astype(np.float32)

        x_q = np.round(x / input_scale + input_zero_point)
        x_q = np.clip(x_q, -128, 127).astype(np.int8)

        interpreter.set_tensor(input_details["index"], x_q)
        interpreter.invoke()
        tflite_out = interpreter.get_tensor(output_details["index"])[0].astype(np.float32)

        cosine_values.append(cosine_similarity(keras_out, tflite_out))

    mean_cosine = float(np.mean(cosine_values)) if cosine_values else 0.0
    size_mb = OUTPUT_PATH.stat().st_size / (1024.0 * 1024.0)

    size_ok = size_mb < SIZE_TARGET_MB
    in_ok = input_dtype == np.int8
    out_ok = output_dtype == np.float32
    cosine_ok = mean_cosine > COSINE_TARGET
    passed = size_ok and in_ok and out_ok and cosine_ok

    print(f"Model input: {MODEL_PATH}")
    print(f"TFLite output: {OUTPUT_PATH}")
    print(f"Representative data used: {rep_path}")
    print(f"Dev samples used: {dev_path} (n={n_eval})")
    print()
    print(f"File size (MB): {size_mb:.4f} (target < {SIZE_TARGET_MB})")
    print(f"Input dtype: {np.dtype(input_dtype).name} (must be int8)")
    print(f"Output dtype: {np.dtype(output_dtype).name} (must be float32)")
    print(f"Cosine similarity (mean over {n_eval} dev samples): {mean_cosine:.6f} (target > {COSINE_TARGET})")
    print("PASS" if passed else "FAIL")


if __name__ == "__main__":
    main()
