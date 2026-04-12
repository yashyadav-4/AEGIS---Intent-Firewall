#!/usr/bin/env python3
"""Export Aegis-Zero Keras models to optimized TFLite files.

Converts Phase (LFCC), Glottal, WavLM Probe, and Ensemble models to INT8
with INT8 input/output quantization for Android compatibility.
Converts WavLM Student CNN to standard FLOAT32 TFLite.
"""

import sys
from pathlib import Path

import numpy as np
import tensorflow as tf

DATASET_DIR = Path("dataset")
MODELS_DIR = Path("models/detectors")
TFLITE_DIR = Path("models/tflite")

def _load_array(name: str):
    path = DATASET_DIR / f"{name}.npy"
    if not path.exists():
        print(f"ERROR: missing {path}")
        sys.exit(1)
    return np.load(path).astype(np.float32)

def make_single_rep_gen(data_array, num_samples=100):
    """Generator for single-input models."""
    def generator():
        for i in range(num_samples):
            yield [data_array[i:i+1]]
    return generator

def make_ensemble_rep_gen(phase_arr, glottal_arr, wavlm_arr, num_samples=100):
    """Generator for 3-input ensemble model."""
    def generator():
        for i in range(num_samples):
            # Must yield a list of inputs matching model's input signature
            yield [
                phase_arr[i:i+1],
                glottal_arr[i:i+1],
                wavlm_arr[i:i+1]
            ]
    return generator

def convert_int8(model, rep_gen, out_path: Path):
    """Convert model to full INT8 with INT8 I/O."""
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    converter.representative_dataset = rep_gen
    converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS_INT8]
    converter.inference_input_type = tf.int8
    converter.inference_output_type = tf.int8
    
    print(f"Converting {out_path.name} to INT8 TFLite...")
    tflite_model = converter.convert()
    out_path.write_bytes(tflite_model)
    print(f"Saved {out_path} ({len(tflite_model) / 1024.0:.2f} KB)")

def convert_float32(model, out_path: Path):
    """Convert model to standard FLOAT32."""
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    print(f"Converting {out_path.name} to standard FLOAT32 TFLite...")
    tflite_model = converter.convert()
    out_path.write_bytes(tflite_model)
    print(f"Saved {out_path} ({len(tflite_model) / 1024.0 / 1024.0:.2f} MB)")

def main():
    if not MODELS_DIR.exists():
        print(f"ERROR: {MODELS_DIR} not found. Run train_detectors.py first.")
        sys.exit(1)
        
    TFLITE_DIR.mkdir(parents=True, exist_ok=True)
    
    print("Loading calibration datasets...")
    train_phase = _load_array("train_phase")
    train_glottal = _load_array("train_glottal")
    train_wavlm = _load_array("train_wavlm")
    
    scores_phase = None
    scores_glottal = None
    scores_wavlm = None

    # 1. Phase Coherence (LFCC) -> INT8
    try:
        phase_model = tf.keras.models.load_model(MODELS_DIR / "phase_best.h5", compile=False)
        convert_int8(
            phase_model, 
            make_single_rep_gen(train_phase),
            TFLITE_DIR / "phase.tflite"
        )
        # Generate scores for ensemble calibration
        scores_phase = phase_model.predict(train_phase[:100], batch_size=100, verbose=0)
    except OSError:
        print("Warning: phase_best.h5 not found, skipping.")

    # 2. Glottal Irregularity -> INT8
    try:
        glottal_model = tf.keras.models.load_model(MODELS_DIR / "glottal_best.h5", compile=False)
        convert_int8(
            glottal_model, 
            make_single_rep_gen(train_glottal),
            TFLITE_DIR / "glottal.tflite"
        )
        scores_glottal = glottal_model.predict(train_glottal[:100], batch_size=100, verbose=0)
    except OSError:
        print("Warning: glottal_best.h5 not found, skipping.")

    # 3. WavLM Linear Probe -> INT8
    try:
        wavlm_model = tf.keras.models.load_model(MODELS_DIR / "wavlm_best.h5", compile=False)
        convert_int8(
            wavlm_model, 
            make_single_rep_gen(train_wavlm),
            TFLITE_DIR / "wavlm_probe.tflite"
        )
        scores_wavlm = wavlm_model.predict(train_wavlm[:100], batch_size=100, verbose=0)
    except OSError:
        print("Warning: wavlm_best.h5 not found, skipping.")

    # 4. Ensemble Fusion -> INT8
    try:
        if scores_phase is not None and scores_glottal is not None and scores_wavlm is not None:
            ensemble_model = tf.keras.models.load_model(MODELS_DIR / "ensemble_best.h5", compile=False)
            convert_int8(
                ensemble_model,
                make_ensemble_rep_gen(scores_phase, scores_glottal, scores_wavlm),
                TFLITE_DIR / "ensemble.tflite"
            )
    except OSError:
        print("Warning: ensemble_best.h5 not found, skipping.")

    # 5. WavLM Student (Distillation CNN) -> FLOAT32
    try:
        student_model = tf.keras.models.load_model(MODELS_DIR / "wavlm_student_best.h5", compile=False)
        convert_float32(
            student_model,
            TFLITE_DIR / "wavlm_student.tflite"
        )
    except OSError:
        print("Warning: wavlm_student_best.h5 not found, skipping.")

    print("\n--- ALL EXPORTS COMPLETE ---")
    print(f"Models successfully assembled in {TFLITE_DIR}/")

if __name__ == "__main__":
    main()
