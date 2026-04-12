#!/usr/bin/env python3
"""Train all Aegis-Zero detectors and fusion ensemble in four phases.

This script expects dataset arrays produced by build_dataset.py in dataset/.
"""

from __future__ import annotations

import sys
import time
from pathlib import Path

import numpy as np
import tensorflow as tf
from sklearn.metrics import roc_curve
from tensorflow.keras.callbacks import EarlyStopping, ModelCheckpoint, ReduceLROnPlateau
from tensorflow.keras.metrics import AUC
from tensorflow.keras.models import load_model
from tensorflow.keras.optimizers import Adam

from models import (
    build_ensemble_model,
    build_glottal_detector,
    build_phase_coherence_detector,
    build_wavlm_detector,
)

DATASET_DIR = Path("dataset")
OUTPUT_DIR = Path("models/detectors")

def focal_loss(gamma: float = 2.0, alpha: float = 0.25):
    """Binary focal loss closure for handling class imbalances effectively."""
    def loss(y_true, y_pred):
        y_true_f = tf.cast(y_true, tf.float32)
        y_pred_f = tf.cast(y_pred, tf.float32)
        y_pred_f = tf.clip_by_value(y_pred_f, 1e-7, 1.0 - 1e-7)
        bce = -y_true_f * tf.math.log(y_pred_f) - (1.0 - y_true_f) * tf.math.log(1.0 - y_pred_f)
        p_t = y_true_f * y_pred_f + (1.0 - y_true_f) * (1.0 - y_pred_f)
        alpha_t = y_true_f * alpha + (1.0 - y_true_f) * (1.0 - alpha)
        focal_val = alpha_t * tf.pow(1.0 - p_t, gamma) * bce
        return tf.reduce_mean(focal_val)
    return loss


def _load_array(name: str, dtype):
    path = DATASET_DIR / f"{name}.npy"
    if not path.exists():
        print(f"ERROR: missing dataset file: {path}")
        sys.exit(1)
    print(f"Loading {path}...")
    return np.load(path).astype(dtype)


def make_balanced_tf_dataset(features, labels: np.ndarray, batch_size: int = 256):
    """Create balanced tf.data.Dataset with 50% bonafide, 50% spoof per batch."""
    bonafide_idx = np.where(labels == 0)[0]
    spoof_idx = np.where(labels == 1)[0]

    def _slice_features(idx: np.ndarray):
        if isinstance(features, list) or isinstance(features, tuple):
            return tuple(feat[idx] for feat in features)
        return features[idx]

    ds_bonafide = tf.data.Dataset.from_tensor_slices(
        (_slice_features(bonafide_idx), labels[bonafide_idx].astype(np.float32))
    ).repeat().shuffle(4000)

    ds_spoof = tf.data.Dataset.from_tensor_slices(
        (_slice_features(spoof_idx), labels[spoof_idx].astype(np.float32))
    ).repeat().shuffle(40000)

    ds = tf.data.Dataset.sample_from_datasets(
        [ds_bonafide, ds_spoof], weights=[0.5, 0.5]
    ).batch(batch_size).prefetch(tf.data.AUTOTUNE)

    return ds


def _compute_eer(y_true: np.ndarray, y_scores: np.ndarray) -> tuple[float, float]:
    fpr, tpr, thresholds = roc_curve(y_true, y_scores)
    fnr = 1.0 - tpr
    idx = int(np.nanargmin(np.abs(fnr - fpr)))
    return float(fpr[idx]), float(thresholds[idx])


def main() -> None:
    total_start = time.perf_counter()
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

    print("Loading datasets...")
    arrays = {
        "train_phase": _load_array("train_phase", np.float32),
        "train_glottal": _load_array("train_glottal", np.float32),
        "train_wavlm": _load_array("train_wavlm", np.float32),
        "train_labels": _load_array("train_labels", np.int32),
        "dev_phase": _load_array("dev_phase", np.float32),
        "dev_glottal": _load_array("dev_glottal", np.float32),
        "dev_wavlm": _load_array("dev_wavlm", np.float32),
        "dev_labels": _load_array("dev_labels", np.int32),
    }

    y_train = arrays["train_labels"].astype(np.float32)
    y_dev = arrays["dev_labels"].astype(np.float32)
    
    # Approx steps per epoch based on overall train size. It runs continuously given dataset `.repeat()`
    steps_per_epoch = max(100, len(y_train) // 256)
    max_epochs = 100

    # Phase 1: LFCC / PhaseCoherenceDetector
    print("\n--- PHASE 1: Phase Coherence (LFCC) Detector ---")
    phase_model = build_phase_coherence_detector()
    phase_model.compile(
        optimizer=Adam(learning_rate=1e-3),
        loss=focal_loss(gamma=2.0, alpha=0.25),
        metrics=["accuracy", AUC(name="auc")],
    )

    train_ds_phase = make_balanced_tf_dataset(arrays["train_phase"], y_train, batch_size=256)
    phase_callbacks = [
        EarlyStopping(monitor="val_auc", patience=10, mode="max", restore_best_weights=True, verbose=1),
        ModelCheckpoint(filepath=str(OUTPUT_DIR / "phase_best.h5"), monitor="val_auc", mode="max", save_best_only=True, verbose=1),
        ReduceLROnPlateau(monitor="val_auc", factor=0.5, patience=4, mode="max", min_lr=1e-6, verbose=1),
    ]

    phase_model.fit(
        train_ds_phase,
        validation_data=(arrays["dev_phase"], y_dev),
        steps_per_epoch=steps_per_epoch,
        epochs=max_epochs,
        callbacks=phase_callbacks,
        verbose=1,
    )

    # Phase 2: Glottal Detector
    print("\n--- PHASE 2: Glottal Irregularity Detector ---")
    glottal_model = build_glottal_detector()
    glottal_model.compile(
        optimizer=Adam(learning_rate=1e-3),
        loss=focal_loss(gamma=2.0, alpha=0.25),
        metrics=["accuracy", AUC(name="auc")],
    )

    train_ds_glottal = make_balanced_tf_dataset(arrays["train_glottal"], y_train, batch_size=256)
    glottal_callbacks = [
        EarlyStopping(monitor="val_auc", patience=10, mode="max", restore_best_weights=True, verbose=1),
        ModelCheckpoint(filepath=str(OUTPUT_DIR / "glottal_best.h5"), monitor="val_auc", mode="max", save_best_only=True, verbose=1),
        ReduceLROnPlateau(monitor="val_auc", factor=0.5, patience=4, mode="max", min_lr=1e-6, verbose=1),
    ]

    glottal_model.fit(
        train_ds_glottal,
        validation_data=(arrays["dev_glottal"], y_dev),
        steps_per_epoch=steps_per_epoch,
        epochs=max_epochs,
        callbacks=glottal_callbacks,
        verbose=1,
    )

    # Phase 3: WavLM Detector
    print("\n--- PHASE 3: WavLM Linear Probe ---")
    wavlm_model = build_wavlm_detector()
    wavlm_model.compile(
        optimizer=Adam(learning_rate=1e-4),
        loss=focal_loss(gamma=2.0, alpha=0.25),
        metrics=["accuracy", AUC(name="auc")],
    )

    train_ds_wavlm = make_balanced_tf_dataset(arrays["train_wavlm"], y_train, batch_size=256)
    wavlm_callbacks = [
        EarlyStopping(monitor="val_auc", patience=10, mode="max", restore_best_weights=True, verbose=1),
        ModelCheckpoint(filepath=str(OUTPUT_DIR / "wavlm_best.h5"), monitor="val_auc", mode="max", save_best_only=True, verbose=1),
        ReduceLROnPlateau(monitor="val_auc", factor=0.5, patience=4, mode="max", min_lr=1e-6, verbose=1),
    ]

    wavlm_model.fit(
        train_ds_wavlm,
        validation_data=(arrays["dev_wavlm"], y_dev),
        steps_per_epoch=steps_per_epoch,
        epochs=max_epochs,
        callbacks=wavlm_callbacks,
        verbose=1,
    )

    # Phase 4: Ensemble Fusion
    print("\n--- PHASE 4: Ensemble Fusion Model ---")
    print("Generating predictions from best base models...")
    
    # Explicitly compile=False during load as we use custom loss
    phase_best = load_model(OUTPUT_DIR / "phase_best.h5", compile=False)
    glottal_best = load_model(OUTPUT_DIR / "glottal_best.h5", compile=False)
    wavlm_best = load_model(OUTPUT_DIR / "wavlm_best.h5", compile=False)

    p_scores_train = phase_best.predict(arrays["train_phase"], batch_size=512, verbose=1)
    g_scores_train = glottal_best.predict(arrays["train_glottal"], batch_size=512, verbose=1)
    w_scores_train = wavlm_best.predict(arrays["train_wavlm"], batch_size=512, verbose=1)

    p_scores_dev = phase_best.predict(arrays["dev_phase"], batch_size=512, verbose=1)
    g_scores_dev = glottal_best.predict(arrays["dev_glottal"], batch_size=512, verbose=1)
    w_scores_dev = wavlm_best.predict(arrays["dev_wavlm"], batch_size=512, verbose=1)

    train_ensemble_inputs = [p_scores_train, g_scores_train, w_scores_train]
    dev_ensemble_inputs = [p_scores_dev, g_scores_dev, w_scores_dev]

    ensemble_model = build_ensemble_model()
    ensemble_model.compile(
        optimizer=Adam(learning_rate=1e-4),
        loss=focal_loss(gamma=2.0, alpha=0.25),
        metrics=["accuracy", AUC(name="auc")],
    )

    train_ds_ensemble = make_balanced_tf_dataset(train_ensemble_inputs, y_train, batch_size=256)
    
    ensemble_callbacks = [
        EarlyStopping(monitor="val_auc", patience=15, mode="max", restore_best_weights=True, verbose=1),
        ModelCheckpoint(filepath=str(OUTPUT_DIR / "ensemble_best.h5"), monitor="val_auc", mode="max", save_best_only=True, verbose=1),
        ReduceLROnPlateau(monitor="val_auc", factor=0.5, patience=5, mode="max", min_lr=1e-6, verbose=1),
    ]

    ensemble_model.fit(
        train_ds_ensemble,
        validation_data=(dev_ensemble_inputs, y_dev),
        steps_per_epoch=steps_per_epoch,
        epochs=max_epochs,
        callbacks=ensemble_callbacks,
        verbose=1,
    )

    print("\n--- FINAL EVALUATION ---")
    val_preds = ensemble_model.predict(dev_ensemble_inputs, batch_size=512, verbose=0).ravel()
    eer, eer_threshold = _compute_eer(y_dev, val_preds)
    
    total_elapsed = time.perf_counter() - total_start
    print(f"Total pipeline complete in {total_elapsed / 60.0:.2f} minutes.")
    print(f"Final Ensemble Equal Error Rate (EER): {eer * 100:.2f}%")
    print(f"Optimal EER Threshold: {eer_threshold:.4f}")
    print(f"All base and ensemble `.h5` models successfully saved to {OUTPUT_DIR}/.")

if __name__ == "__main__":
    main()
