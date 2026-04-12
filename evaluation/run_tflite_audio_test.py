#!/usr/bin/env python3
"""Run audio spoof/deepfake tests with packaged Android TFLite assets.

This script evaluates audio files with the same model stack used by Android assets:
- detector_phase.tflite
- detector_glottal.tflite
- wavlm_student.tflite
- detector_wavlm.tflite
- ensemble.tflite

Outputs:
- Console summary table
- evaluation/tflite_audio_test_report.json
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import numpy as np
import tensorflow as tf

from feature_extractors import extract_glottal_features, extract_lfcc_features, preprocess_audio

ASSETS_DIR = Path("android/app/src/main/assets")
OUT_PATH = Path("evaluation/tflite_audio_test_report.json")
ENSEMBLE_FLAT_ZONE_EPS = 0.008
FALLBACK_SYNTHETIC_THRESHOLD = 0.55

SAMPLES = [
    {
        "name": "Morgan Freeman Deepfake (YouTube)",
        "path": Path("evaluation/samples/morgan_deepfake_RsRZ_expsG4.wav"),
        "kind": "online_deepfake",
    },
    {
        "name": "Morgan Freeman Deepfake #2 (YouTube)",
        "path": Path("evaluation/samples/morgan_deepfake2_oxXpB9pSETo.wav"),
        "kind": "online_deepfake",
    },
    {
        "name": "Morgan Freeman Clip (Likely Real)",
        "path": Path("evaluation/samples/morgan_real_Y5iENTEkZTM.wav"),
        "kind": "online_real_clip",
    },
    {
        "name": "Spoof Call (macOS TTS Samantha)",
        "path": Path("evaluation/samples/spoof_call_samantha.aiff"),
        "kind": "local_tts_spoof",
    },
]


class TFLiteRunner:
    def __init__(self) -> None:
        self.phase = self._load_interpreter("detector_phase.tflite")
        self.glottal = self._load_interpreter("detector_glottal.tflite")
        self.student = self._load_interpreter("wavlm_student.tflite")
        self.wavlm_probe = self._load_interpreter("detector_wavlm.tflite")
        self.ensemble = self._load_interpreter("ensemble.tflite")

        self.phase_scaler = self._load_scaler("phase_scaler.json")
        self.glottal_scaler = self._load_scaler("glottal_scaler.json")
        self.wavlm_scaler = self._load_scaler("wavlm_scaler.json")

    def _load_interpreter(self, name: str) -> tf.lite.Interpreter:
        path = ASSETS_DIR / name
        if not path.exists():
            raise FileNotFoundError(f"Missing TFLite model: {path}")
        interp = tf.lite.Interpreter(model_path=str(path))
        interp.allocate_tensors()
        return interp

    def _load_scaler(self, name: str) -> dict[str, np.ndarray]:
        path = ASSETS_DIR / name
        if not path.exists():
            raise FileNotFoundError(f"Missing scaler JSON: {path}")
        with path.open("r", encoding="utf-8") as f:
            raw = json.load(f)
        mean = np.asarray(raw["mean"], dtype=np.float32)
        scale = np.asarray(raw["scale"], dtype=np.float32)
        scale = np.where(np.abs(scale) < 1e-9, 1.0, scale)
        return {"mean": mean, "scale": scale}

    def _apply_scaler(self, feat: np.ndarray, scaler: dict[str, np.ndarray]) -> np.ndarray:
        return ((feat.astype(np.float32) - scaler["mean"]) / scaler["scale"]).astype(np.float32)

    def _quantize_if_needed(self, arr: np.ndarray, detail: dict[str, Any]) -> np.ndarray:
        dtype = detail["dtype"]
        if dtype == np.int8:
            scale, zero = detail["quantization"]
            if scale == 0:
                scale = 1.0
            q = np.round(arr / scale + zero)
            return np.clip(q, -128, 127).astype(np.int8)
        return arr.astype(dtype)

    def _dequantize_if_needed(self, arr: np.ndarray, detail: dict[str, Any]) -> np.ndarray:
        dtype = detail["dtype"]
        if dtype == np.int8:
            scale, zero = detail["quantization"]
            return ((arr.astype(np.float32) - zero) * scale).astype(np.float32)
        return arr.astype(np.float32)

    def _run_single_input_single_output(self, interp: tf.lite.Interpreter, vec: np.ndarray) -> float:
        in_detail = interp.get_input_details()[0]
        out_detail = interp.get_output_details()[0]

        in_shape = tuple(int(x) for x in in_detail["shape"])
        expected = int(np.prod(in_shape))
        flat = vec.astype(np.float32).reshape(-1)

        if flat.size != expected:
            raise ValueError(f"Input size mismatch: got {flat.size}, expected {expected}, shape={in_shape}")

        inp = flat.reshape(in_shape)
        inp = self._quantize_if_needed(inp, in_detail)

        interp.set_tensor(in_detail["index"], inp)
        interp.invoke()
        out = interp.get_tensor(out_detail["index"])
        out = self._dequantize_if_needed(out, out_detail)
        return float(out.reshape(-1)[0])

    def _run_wavlm_student(self, waveform: np.ndarray) -> np.ndarray:
        in_detail = self.student.get_input_details()[0]
        out_detail = self.student.get_output_details()[0]

        in_shape = tuple(int(x) for x in in_detail["shape"])
        if len(in_shape) == 2:
            inp = waveform.reshape(1, -1).astype(np.float32)
        elif len(in_shape) == 3:
            inp = waveform.reshape(1, -1, 1).astype(np.float32)
        else:
            raise ValueError(f"Unexpected wavlm_student input shape: {in_shape}")

        inp = self._quantize_if_needed(inp, in_detail)
        self.student.set_tensor(in_detail["index"], inp)
        self.student.invoke()

        out = self.student.get_tensor(out_detail["index"])
        out = self._dequantize_if_needed(out, out_detail)
        flat = out.reshape(-1).astype(np.float32)

        if flat.size != 128:
            raise ValueError(f"wavlm_student output must be 128-d, got {flat.size}")

        return flat

    def run_sample(self, audio_path: Path) -> dict[str, Any]:
        waveform = preprocess_audio(audio_path)

        phase = extract_lfcc_features(waveform)
        glottal = extract_glottal_features(waveform)

        phase_s = self._apply_scaler(phase, self.phase_scaler)
        glottal_s = self._apply_scaler(glottal, self.glottal_scaler)

        wavlm_raw = self._run_wavlm_student(waveform)
        wavlm_s = self._apply_scaler(wavlm_raw, self.wavlm_scaler)

        phase_score = self._run_single_input_single_output(self.phase, phase_s)
        glottal_score = self._run_single_input_single_output(self.glottal, glottal_s)
        wavlm_score = self._run_single_input_single_output(self.wavlm_probe, wavlm_s)

        e_in = self.ensemble.get_input_details()
        e_out = self.ensemble.get_output_details()[0]

        inputs = [
            np.array([[phase_score]], dtype=np.float32),
            np.array([[glottal_score]], dtype=np.float32),
            np.array([[wavlm_score]], dtype=np.float32),
        ]

        for d, arr in zip(e_in, inputs):
            q = self._quantize_if_needed(arr, d)
            self.ensemble.set_tensor(d["index"], q)

        self.ensemble.invoke()
        final = self.ensemble.get_tensor(e_out["index"])
        final = self._dequantize_if_needed(final, e_out)
        ensemble_score = float(final.reshape(-1)[0])

        fallback_score = self._compute_fallback_score(phase_score, glottal_score, wavlm_score)
        use_fallback = abs(ensemble_score - 0.5) <= ENSEMBLE_FLAT_ZONE_EPS
        threshold = FALLBACK_SYNTHETIC_THRESHOLD if use_fallback else 0.5
        final_score = fallback_score if use_fallback else ensemble_score

        return {
            "phase_score": phase_score,
            "glottal_score": glottal_score,
            "wavlm_score": wavlm_score,
            "ensemble_score": ensemble_score,
            "fallback_score": float(fallback_score),
            "used_fallback": bool(use_fallback),
            "decision_threshold": float(threshold),
            "final_score": float(final_score),
            "is_synthetic": bool(final_score >= threshold),
        }

    def _compute_fallback_score(self, phase_score: float, glottal_score: float, wavlm_score: float) -> float:
        score = 0.6 * wavlm_score + 0.3 * phase_score + 0.1 * glottal_score

        if phase_score >= 0.55 and wavlm_score >= 0.52:
            score += 0.05
        if phase_score < 0.5 and glottal_score < 0.35 and wavlm_score < 0.55:
            score -= 0.04

        return float(max(0.0, min(1.0, score)))


def main() -> None:
    runner = TFLiteRunner()
    results = []

    for sample in SAMPLES:
        path = sample["path"]
        if not path.exists():
            results.append(
                {
                    "name": sample["name"],
                    "kind": sample["kind"],
                    "path": str(path),
                    "error": "file_not_found",
                }
            )
            continue

        try:
            out = runner.run_sample(path)
            results.append(
                {
                    "name": sample["name"],
                    "kind": sample["kind"],
                    "path": str(path),
                    **out,
                }
            )
        except Exception as exc:
            results.append(
                {
                    "name": sample["name"],
                    "kind": sample["kind"],
                    "path": str(path),
                    "error": f"{type(exc).__name__}: {exc}",
                }
            )

    OUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    with OUT_PATH.open("w", encoding="utf-8") as f:
        json.dump({"results": results}, f, indent=2)

    print("TFLite Audio Test Results")
    print("=" * 80)
    for r in results:
        print(f"Name: {r['name']}")
        print(f"Kind: {r['kind']}")
        print(f"Path: {r['path']}")
        if "error" in r:
            print(f"Error: {r['error']}")
        else:
            print(f"Phase score:    {r['phase_score']:.4f}")
            print(f"Glottal score:  {r['glottal_score']:.4f}")
            print(f"WavLM score:    {r['wavlm_score']:.4f}")
            print(f"Ensemble score: {r['ensemble_score']:.4f}")
            print(f"Fallback score: {r['fallback_score']:.4f}")
            print(f"Used fallback:  {r['used_fallback']}")
            print(f"Final score:    {r['final_score']:.4f} (threshold {r['decision_threshold']:.2f})")
            print(f"Synthetic:      {r['is_synthetic']}")
        print("-" * 80)

    print(f"Saved JSON report to: {OUT_PATH}")


if __name__ == "__main__":
    main()
