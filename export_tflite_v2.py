#!/usr/bin/env python3
"""Export trained detector models to INT8 TFLite for Android deployment."""

from __future__ import annotations

import warnings
from dataclasses import dataclass
from pathlib import Path
from typing import Callable

import numpy as np
import tensorflow as tf

warnings.filterwarnings("ignore")

MODELS_DIR = Path("models/detectors")
OUTPUT_DIR = Path("models/tflite")
DATASET_DIR = Path("dataset")


@dataclass
class ExportResult:
    name: str
    output_path: Path
    size_mb: float
    input_dtype: str
    output_dtype: str
    passed: bool
    message: str


def _load_rep_data(path: Path, expected_dim: int, num_samples: int = 200) -> np.ndarray | None:
    if not path.exists():
        print(f"WARNING: representative data not found: {path}")
        return None

    arr = np.load(path, mmap_mode="r")
    if arr.ndim != 2 or arr.shape[1] != expected_dim:
        print(
            f"WARNING: representative data shape mismatch for {path}: "
            f"expected (*, {expected_dim}), got {arr.shape}"
        )
        return None

    n = min(num_samples, arr.shape[0])
    return np.asarray(arr[:n], dtype=np.float32)


def _representative_gen(rep_data: np.ndarray):
    for i in range(rep_data.shape[0]):
        sample = rep_data[i : i + 1].astype(np.float32)
        yield [sample]


def _verify_tflite_model(output_path: Path) -> tuple[bool, str, str, str]:
    interpreter = tf.lite.Interpreter(model_path=str(output_path))
    interpreter.allocate_tensors()

    input_details = interpreter.get_input_details()
    output_details = interpreter.get_output_details()[0]

    input_dtype = ",".join(str(d["dtype"]) for d in input_details)
    output_dtype = str(output_details["dtype"])

    if any(d["dtype"] != np.int8 for d in input_details):
        return False, input_dtype, output_dtype, "input_dtype_not_int8"
    if output_details["dtype"] != np.int8:
        return False, input_dtype, output_dtype, "output_dtype_not_int8"

    for d in input_details:
        input_shape = tuple(int(x) for x in d["shape"])
        if len(input_shape) < 2:
            return False, input_dtype, output_dtype, f"unexpected_input_shape:{input_shape}"
        zero_input = np.zeros(input_shape, dtype=np.int8)
        interpreter.set_tensor(d["index"], zero_input)

    interpreter.invoke()
    _ = interpreter.get_tensor(output_details["index"])

    return True, input_dtype, output_dtype, "ok"


def export_model(
    model_path: Path,
    output_path: Path,
    rep_data: np.ndarray | None,
    input_shape: tuple[int, ...],
) -> ExportResult:
    model_name = model_path.name

    if not model_path.exists():
        msg = f"missing_model_file:{model_path}"
        print(f"WARNING: {msg}")
        return ExportResult(
            name=model_name,
            output_path=output_path,
            size_mb=0.0,
            input_dtype="N/A",
            output_dtype="N/A",
            passed=False,
            message=msg,
        )

    if rep_data is None:
        msg = "missing_or_invalid_representative_data"
        print(f"WARNING: {model_name}: {msg}")
        return ExportResult(
            name=model_name,
            output_path=output_path,
            size_mb=0.0,
            input_dtype="N/A",
            output_dtype="N/A",
            passed=False,
            message=msg,
        )

    try:
        model = tf.keras.models.load_model(str(model_path), compile=False)

        if tuple(model.input_shape[1:]) != input_shape:
            msg = f"input_shape_mismatch:model={model.input_shape[1:]},expected={input_shape}"
            print(f"WARNING: {model_name}: {msg}")
            return ExportResult(
                name=model_name,
                output_path=output_path,
                size_mb=0.0,
                input_dtype="N/A",
                output_dtype="N/A",
                passed=False,
                message=msg,
            )

        converter = tf.lite.TFLiteConverter.from_keras_model(model)
        converter.optimizations = [tf.lite.Optimize.DEFAULT]
        converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS_INT8]
        converter.inference_input_type = tf.int8
        converter.inference_output_type = tf.int8
        converter.representative_dataset = lambda: _representative_gen(rep_data)

        tflite_model = converter.convert()
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_bytes(tflite_model)

        passed, in_dtype, out_dtype, verify_msg = _verify_tflite_model(output_path)
        size_mb = output_path.stat().st_size / (1024.0 * 1024.0)

        if passed:
            status_msg = "PASS"
        else:
            status_msg = f"FAIL ({verify_msg})"

        print(f"Model: {model_name}")
        print(f"  Output: {output_path}")
        print(f"  File size: {size_mb:.2f} MB")
        print(f"  Input dtype: {in_dtype}")
        print(f"  Output dtype: {out_dtype}")
        print(f"  Verification: {status_msg}")

        return ExportResult(
            name=model_name,
            output_path=output_path,
            size_mb=size_mb,
            input_dtype=in_dtype,
            output_dtype=out_dtype,
            passed=passed,
            message=verify_msg,
        )

    except Exception as exc:
        print(f"Model: {model_name}")
        print(f"  Verification: FAIL ({type(exc).__name__}: {exc})")
        return ExportResult(
            name=model_name,
            output_path=output_path,
            size_mb=0.0,
            input_dtype="N/A",
            output_dtype="N/A",
            passed=False,
            message=f"exception:{type(exc).__name__}:{exc}",
        )


def main() -> None:
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

    rep_phase = _load_rep_data(DATASET_DIR / "train_phase.npy", expected_dim=64, num_samples=200)
    rep_glottal = _load_rep_data(DATASET_DIR / "train_glottal.npy", expected_dim=12, num_samples=200)
    rep_wavlm = _load_rep_data(DATASET_DIR / "train_wavlm.npy", expected_dim=128, num_samples=200)

    # WavLM export here is only the 128->1 linear probe, not the full WavLM backbone.
    specs: list[tuple[str, Path, Path, np.ndarray | None, tuple[int, ...]]] = [
        (
            "detector_phase.tflite",
            MODELS_DIR / "phase_best.h5",
            OUTPUT_DIR / "detector_phase.tflite",
            rep_phase,
            (64,),
        ),
        (
            "detector_glottal.tflite",
            MODELS_DIR / "glottal_best.h5",
            OUTPUT_DIR / "detector_glottal.tflite",
            rep_glottal,
            (12,),
        ),
        (
            "detector_wavlm.tflite",
            MODELS_DIR / "wavlm_best.h5",
            OUTPUT_DIR / "detector_wavlm.tflite",
            rep_wavlm,
            (128,),
        ),
        (
            "ensemble.tflite",
            MODELS_DIR / "ensemble_best.h5",
            OUTPUT_DIR / "ensemble.tflite",
            rep_wavlm,
            (128,),
        ),
    ]

    results: list[ExportResult] = []

    for friendly_name, model_path, output_path, rep_data, input_shape in specs:
        if friendly_name == "ensemble.tflite":
            # Ensemble has 3 scalar inputs; build a dedicated representative set.
            ensemble_rep = np.zeros((200, 3), dtype=np.float32)
            if rep_phase is not None and rep_glottal is not None and rep_wavlm is not None:
                n = min(200, rep_phase.shape[0], rep_glottal.shape[0], rep_wavlm.shape[0])
                # Use simple proxy scores in [0,1] as calibration samples.
                ensemble_rep = np.column_stack(
                    [
                        np.clip(np.mean(rep_phase[:n], axis=1), 0.0, 1.0),
                        np.clip(np.mean(rep_glottal[:n], axis=1), 0.0, 1.0),
                        np.clip(np.mean(rep_wavlm[:n], axis=1), 0.0, 1.0),
                    ]
                ).astype(np.float32)

            # Convert 3-feature row into 3 single-input tensors expected by the ensemble model.
            def ensemble_rep_gen() -> Callable[[], list[np.ndarray]]:
                def _gen():
                    for i in range(ensemble_rep.shape[0]):
                        p = ensemble_rep[i, 0:1].reshape(1, 1).astype(np.float32)
                        g = ensemble_rep[i, 1:2].reshape(1, 1).astype(np.float32)
                        w = ensemble_rep[i, 2:3].reshape(1, 1).astype(np.float32)
                        yield [p, g, w]

                return _gen

            if not model_path.exists():
                result = ExportResult(
                    name=model_path.name,
                    output_path=output_path,
                    size_mb=0.0,
                    input_dtype="N/A",
                    output_dtype="N/A",
                    passed=False,
                    message=f"missing_model_file:{model_path}",
                )
                print(f"WARNING: {result.message}")
                results.append(result)
                continue

            try:
                model = tf.keras.models.load_model(str(model_path), compile=False)
                converter = tf.lite.TFLiteConverter.from_keras_model(model)
                converter.optimizations = [tf.lite.Optimize.DEFAULT]
                converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS_INT8]
                converter.inference_input_type = tf.int8
                converter.inference_output_type = tf.int8
                converter.representative_dataset = ensemble_rep_gen()

                tflite_model = converter.convert()
                output_path.parent.mkdir(parents=True, exist_ok=True)
                output_path.write_bytes(tflite_model)

                passed, in_dtype, out_dtype, verify_msg = _verify_tflite_model(output_path)
                size_mb = output_path.stat().st_size / (1024.0 * 1024.0)

                print("Model: ensemble_best.h5")
                print(f"  Output: {output_path}")
                print(f"  File size: {size_mb:.2f} MB")
                print(f"  Input dtype: {in_dtype}")
                print(f"  Output dtype: {out_dtype}")
                print(f"  Verification: {'PASS' if passed else f'FAIL ({verify_msg})'}")

                results.append(
                    ExportResult(
                        name="ensemble_best.h5",
                        output_path=output_path,
                        size_mb=size_mb,
                        input_dtype=in_dtype,
                        output_dtype=out_dtype,
                        passed=passed,
                        message=verify_msg,
                    )
                )

            except Exception as exc:
                print("Model: ensemble_best.h5")
                print(f"  Verification: FAIL ({type(exc).__name__}: {exc})")
                results.append(
                    ExportResult(
                        name="ensemble_best.h5",
                        output_path=output_path,
                        size_mb=0.0,
                        input_dtype="N/A",
                        output_dtype="N/A",
                        passed=False,
                        message=f"exception:{type(exc).__name__}:{exc}",
                    )
                )

            continue

        result = export_model(
            model_path=model_path,
            output_path=output_path,
            rep_data=rep_data,
            input_shape=input_shape,
        )
        results.append(result)

    # Final summary requested.
    phase_res = next((r for r in results if r.output_path.name == "detector_phase.tflite"), None)
    glottal_res = next((r for r in results if r.output_path.name == "detector_glottal.tflite"), None)
    wavlm_res = next((r for r in results if r.output_path.name == "detector_wavlm.tflite"), None)
    ensemble_res = next((r for r in results if r.output_path.name == "ensemble.tflite"), None)

    def line(label: str, res: ExportResult | None):
        if res is None:
            print(f"{label}: N/A MB  N/A  [FAIL]")
            return
        input_int8 = all(x.strip() == "<class 'numpy.int8'>" for x in res.input_dtype.split(","))
        output_int8 = res.output_dtype == "<class 'numpy.int8'>"
        dtype_text = "INT8" if (input_int8 and output_int8) else "NON-INT8"
        status = "PASS" if res.passed else "FAIL"
        print(f"{label}: {res.size_mb:.2f} MB  {dtype_text}  [{status}]")

    print("\nExport summary:")
    line("detector_phase.tflite", phase_res)
    line("detector_glottal.tflite", glottal_res)
    line("detector_wavlm.tflite", wavlm_res)
    line("ensemble.tflite", ensemble_res)


if __name__ == "__main__":
    main()
