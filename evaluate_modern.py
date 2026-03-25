#!/usr/bin/env python3
"""Evaluate trained Aegis-Zero detectors on held-out modern attack audio."""

from __future__ import annotations

import sys
import time
from pathlib import Path

import numpy as np
import tensorflow as tf
from sklearn.metrics import (
    accuracy_score,
    confusion_matrix,
    f1_score,
    precision_score,
    recall_score,
    roc_auc_score,
    roc_curve,
)

from feature_extractors import (
    extract_glottal_features,
    extract_lfcc_features,
    extract_wavlm_features,
    preprocess_audio,
)

SYNTHETIC_DIR = Path("modern_attacks/synthetic_wav")
BONAFIDE_DIR = Path("modern_attacks/bonafide")
EVAL_DIR = Path("evaluation")
SCORES_PATH = EVAL_DIR / "modern_attack_scores.txt"

MODEL_PATHS = {
    "phase": Path("models/detectors/phase_best.h5"),
    "glottal": Path("models/detectors/glottal_best.h5"),
    "wavlm": Path("models/detectors/wavlm_best.h5"),
    "ensemble": Path("models/detectors/ensemble_best.h5"),
}


def compute_eer(y_true: np.ndarray, y_scores: np.ndarray) -> float:
    fpr, tpr, _ = roc_curve(y_true, y_scores)
    fnr = 1.0 - tpr
    idx = int(np.nanargmin(np.abs(fnr - fpr)))
    return float(fpr[idx])


def compute_eer_threshold(y_true: np.ndarray, y_scores: np.ndarray) -> float:
    """Compute the decision threshold at equal error rate."""
    fpr, tpr, thresholds = roc_curve(y_true, y_scores)
    fnr = 1.0 - tpr
    idx = int(np.nanargmin(np.abs(fnr - fpr)))
    return float(thresholds[idx])


def collect_test_files() -> list[tuple[Path, int]]:
    if not SYNTHETIC_DIR.exists() or not BONAFIDE_DIR.exists():
        print("ERROR: modern attack directories not found.")
        print(f"  expected synthetic dir: {SYNTHETIC_DIR}")
        print(f"  expected bonafide dir:  {BONAFIDE_DIR}")
        sys.exit(1)

    synthetic_files = sorted(p for p in SYNTHETIC_DIR.iterdir() if p.is_file())
    bonafide_files = sorted(p for p in BONAFIDE_DIR.iterdir() if p.is_file())

    files: list[tuple[Path, int]] = []
    files.extend((p, 1) for p in synthetic_files)
    files.extend((p, 0) for p in bonafide_files)

    return files


def validate_models_exist() -> None:
    missing = [name for name, p in MODEL_PATHS.items() if not p.exists()]
    if missing:
        print("ERROR: missing trained model files:")
        for name in missing:
            print(f"  {name}: {MODEL_PATHS[name]}")
        sys.exit(1)


def compute_metrics(y_true: np.ndarray, scores: np.ndarray) -> dict[str, float | np.ndarray]:
    eer = compute_eer(y_true, scores)
    eer_thresh = compute_eer_threshold(y_true, scores)
    preds = (scores >= eer_thresh).astype(np.int32)
    auc = float(roc_auc_score(y_true, scores))
    acc = float(accuracy_score(y_true, preds))
    prec = float(precision_score(y_true, preds, zero_division=0))
    rec = float(recall_score(y_true, preds, zero_division=0))
    f1 = float(f1_score(y_true, preds, zero_division=0))
    cm = confusion_matrix(y_true, preds, labels=[0, 1])

    return {
        "auc": auc,
        "eer": eer,
        "accuracy": acc,
        "precision": prec,
        "recall": rec,
        "f1": f1,
        "cm": cm,
    }


def main() -> None:
    start_time = time.perf_counter()
    EVAL_DIR.mkdir(parents=True, exist_ok=True)

    validate_models_exist()

    print("Loading trained detector models...")
    phase_model = tf.keras.models.load_model(MODEL_PATHS["phase"], compile=False)
    glottal_model = tf.keras.models.load_model(MODEL_PATHS["glottal"], compile=False)
    wavlm_model_probe = tf.keras.models.load_model(MODEL_PATHS["wavlm"], compile=False)
    ensemble_model = tf.keras.models.load_model(MODEL_PATHS["ensemble"], compile=False)

    print("Loading WavLM feature extractor...")
    from transformers import WavLMModel

    wavlm_feature_model = WavLMModel.from_pretrained("microsoft/wavlm-base-plus")
    wavlm_feature_model.eval()

    files = collect_test_files()
    total_files = len(files)
    print(f"Found {total_files} files for evaluation.")

    kept_names: list[str] = []
    kept_labels: list[int] = []
    phase_scores: list[float] = []
    glottal_scores: list[float] = []
    wavlm_scores: list[float] = []
    ensemble_scores: list[float] = []

    skipped: list[str] = []

    for i, (audio_path, label) in enumerate(files, start=1):
        try:
            wave = preprocess_audio(audio_path)

            lfcc_feat = extract_lfcc_features(wave)
            glottal_feat = extract_glottal_features(wave)
            wavlm_feat = extract_wavlm_features(wave, wavlm_feature_model)

            if not np.isfinite(lfcc_feat).all() or lfcc_feat.shape != (64,):
                raise ValueError("invalid lfcc feature")
            if not np.isfinite(glottal_feat).all() or glottal_feat.shape != (12,):
                raise ValueError("invalid glottal feature")
            if not np.isfinite(wavlm_feat).all() or wavlm_feat.shape != (128,):
                raise ValueError("invalid wavlm feature")

            phase_input = lfcc_feat.reshape(1, 64).astype(np.float32)
            glottal_input = glottal_feat.reshape(1, 12).astype(np.float32)
            wavlm_input = wavlm_feat.reshape(1, 128).astype(np.float32)

            phase_score = float(phase_model.predict(phase_input, verbose=0).reshape(-1)[0])
            glottal_score = float(glottal_model.predict(glottal_input, verbose=0).reshape(-1)[0])
            wavlm_score = float(wavlm_model_probe.predict(wavlm_input, verbose=0).reshape(-1)[0])

            ens_score = float(
                ensemble_model.predict(
                    [
                        np.array([[phase_score]], dtype=np.float32),
                        np.array([[glottal_score]], dtype=np.float32),
                        np.array([[wavlm_score]], dtype=np.float32),
                    ],
                    verbose=0,
                ).reshape(-1)[0]
            )

            kept_names.append(audio_path.name)
            kept_labels.append(int(label))
            phase_scores.append(phase_score)
            glottal_scores.append(glottal_score)
            wavlm_scores.append(wavlm_score)
            ensemble_scores.append(ens_score)

        except Exception as exc:
            skipped.append(f"{audio_path.name}\t{type(exc).__name__}: {exc}")

        if i % 20 == 0 or i == total_files:
            print(f"Processed {i}/{total_files} files...")

    if not kept_labels:
        print("ERROR: no valid files were evaluated.")
        if skipped:
            print(f"Skipped files: {len(skipped)}")
        sys.exit(1)

    y_true = np.asarray(kept_labels, dtype=np.int32)
    phase_scores_arr = np.asarray(phase_scores, dtype=np.float32)
    glottal_scores_arr = np.asarray(glottal_scores, dtype=np.float32)
    wavlm_scores_arr = np.asarray(wavlm_scores, dtype=np.float32)
    ensemble_scores_arr = np.asarray(ensemble_scores, dtype=np.float32)

    phase_metrics = compute_metrics(y_true, phase_scores_arr)
    glottal_metrics = compute_metrics(y_true, glottal_scores_arr)
    wavlm_metrics = compute_metrics(y_true, wavlm_scores_arr)
    ensemble_metrics = compute_metrics(y_true, ensemble_scores_arr)

    cm = ensemble_metrics["cm"]
    tn, fp, fn, tp = int(cm[0, 0]), int(cm[0, 1]), int(cm[1, 0]), int(cm[1, 1])

    with SCORES_PATH.open("w", encoding="utf-8") as f:
        for name, label, score in zip(kept_names, y_true, ensemble_scores_arr):
            f.write(f"{name} {int(label)} {float(score):.4f}\n")

    print("═" * 43)
    print("AEGIS-ZERO MODERN ATTACK EVALUATION REPORT")
    print("═" * 43)
    print("Test set: 100 synthetic (Edge TTS) + 100 bonafide")
    print()
    print("Individual detectors:")
    print(
        f"  Phase Coherence:      AUC={phase_metrics['auc']:.2f}  "
        f"EER={phase_metrics['eer'] * 100.0:.1f}%  "
        f"Acc={phase_metrics['accuracy'] * 100.0:.1f}%"
    )
    print(
        f"  Glottal Irregularity: AUC={glottal_metrics['auc']:.2f}  "
        f"EER={glottal_metrics['eer'] * 100.0:.1f}%  "
        f"Acc={glottal_metrics['accuracy'] * 100.0:.1f}%"
    )
    print(
        f"  WavLM Consistency:    AUC={wavlm_metrics['auc']:.2f}  "
        f"EER={wavlm_metrics['eer'] * 100.0:.1f}%  "
        f"Acc={wavlm_metrics['accuracy'] * 100.0:.1f}%"
    )
    print()
    print("Ensemble (final):")
    print(f"  AUC:       {ensemble_metrics['auc']:.4f}")
    print(f"  EER:       {ensemble_metrics['eer'] * 100.0:.1f}%")
    print(f"  Accuracy:  {ensemble_metrics['accuracy'] * 100.0:.1f}%")
    print(f"  Precision: {ensemble_metrics['precision'] * 100.0:.1f}%")
    print(f"  Recall:    {ensemble_metrics['recall'] * 100.0:.1f}%")
    print(f"  F1:        {ensemble_metrics['f1'] * 100.0:.1f}%")
    print()
    print("Confusion matrix (ensemble):")
    print(f"  True Positives  (synthetic caught):  {tp}/100")
    print(f"  True Negatives  (bonafide passed):   {tn}/100")
    print(f"  False Positives (bonafide flagged):  {fp}/100")
    print(f"  False Negatives (synthetic missed):  {fn}/100")
    print()

    verdict = "PRODUCTION READY" if ensemble_metrics["eer"] < 0.15 else "NEEDS IMPROVEMENT"
    print(f"Verdict: {verdict}")
    print("Threshold: PRODUCTION READY if EER < 15%")

    if skipped:
        print(f"Skipped files: {len(skipped)}")
    else:
        print("Skipped files: 0")

    elapsed = time.perf_counter() - start_time
    print(f"Scores saved to: {SCORES_PATH}")
    print(f"Total elapsed time: {elapsed:.2f} seconds ({elapsed / 60.0:.2f} minutes)")


if __name__ == "__main__":
    main()
