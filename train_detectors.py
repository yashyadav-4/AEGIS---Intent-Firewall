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
    """Binary focal loss closure."""

    def loss(y_true, y_pred):
        y_true_f = tf.cast(y_true, tf.float32)
        y_pred_f = tf.cast(y_pred, tf.float32)
        y_pred_f = tf.clip_by_value(y_pred_f, 1e-7, 1.0 - 1e-7)
        bce = -y_true_f * tf.math.log(y_pred_f) - (1.0 - y_true_f) * tf.math.log(1.0 - y_pred_f)
        p_t = y_true_f * y_pred_f + (1.0 - y_true_f) * (1.0 - y_pred_f)
        alpha_t = y_true_f * alpha + (1.0 - y_true_f) * (1.0 - alpha)
        focal = alpha_t * tf.pow(1.0 - p_t, gamma) * bce
        return tf.reduce_mean(focal)

    return loss


def _load_array(name: str, dtype):
    path = DATASET_DIR / f"{name}.npy"
    if not path.exists():
        print(f"ERROR: missing dataset file: {path}")
        sys.exit(1)
    arr = np.load(path)
    if arr.dtype != dtype:
        arr = arr.astype(dtype)
    return arr


def _validate_shapes(arrays: dict[str, np.ndarray]) -> None:
    train_count = arrays["train_phase"].shape[0]
    dev_count = arrays["dev_phase"].shape[0]

    train_keys = ["train_phase", "train_glottal", "train_wavlm", "train_labels"]
    dev_keys = ["dev_phase", "dev_glottal", "dev_wavlm", "dev_labels"]

    for key in train_keys:
        if arrays[key].shape[0] != train_count:
            print(
                f"ERROR: train arrays must share the same row count; "
                f"{key} has {arrays[key].shape[0]} vs train_phase {train_count}"
            )
            sys.exit(1)

    for key in dev_keys:
        if arrays[key].shape[0] != dev_count:
            print(
                f"ERROR: dev arrays must share the same row count; "
                f"{key} has {arrays[key].shape[0]} vs dev_phase {dev_count}"
            )
            sys.exit(1)

    if arrays["train_phase"].ndim != 2 or arrays["train_phase"].shape[1] != 64:
        print(f"ERROR: train_phase must be 2D with second dim 64, got {arrays['train_phase'].shape}")
        sys.exit(1)
    if arrays["dev_phase"].ndim != 2 or arrays["dev_phase"].shape[1] != 64:
        print(f"ERROR: dev_phase must be 2D with second dim 64, got {arrays['dev_phase'].shape}")
        sys.exit(1)

    if arrays["train_glottal"].ndim != 2 or arrays["train_glottal"].shape[1] != 12:
        print(f"ERROR: train_glottal must be 2D with second dim 12, got {arrays['train_glottal'].shape}")
        sys.exit(1)
    if arrays["dev_glottal"].ndim != 2 or arrays["dev_glottal"].shape[1] != 12:
        print(f"ERROR: dev_glottal must be 2D with second dim 12, got {arrays['dev_glottal'].shape}")
        sys.exit(1)

    if arrays["train_wavlm"].ndim != 2 or arrays["train_wavlm"].shape[1] != 128:
        print(f"ERROR: train_wavlm must be 2D with second dim 128, got {arrays['train_wavlm'].shape}")
        sys.exit(1)
    if arrays["dev_wavlm"].ndim != 2 or arrays["dev_wavlm"].shape[1] != 128:
        print(f"ERROR: dev_wavlm must be 2D with second dim 128, got {arrays['dev_wavlm'].shape}")
        sys.exit(1)

    if arrays["train_labels"].ndim != 1:
        print(f"ERROR: train_labels must be 1D, got {arrays['train_labels'].shape}")
        sys.exit(1)
    if arrays["dev_labels"].ndim != 1:
        print(f"ERROR: dev_labels must be 1D, got {arrays['dev_labels'].shape}")
        sys.exit(1)


def _print_dataset_overview(arrays: dict[str, np.ndarray]) -> None:
    print("Loaded dataset arrays:")
    for key in [
        "train_phase",
        "train_glottal",
        "train_wavlm",
        "train_labels",
        "dev_phase",
        "dev_glottal",
        "dev_wavlm",
        "dev_labels",
    ]:
        print(f"  {key}.npy -> shape={arrays[key].shape}, dtype={arrays[key].dtype}")

    train_labels = arrays["train_labels"]
    dev_labels = arrays["dev_labels"]
    print(f"  Train labels -> bonafide={(train_labels == 0).sum()}, spoof={(train_labels == 1).sum()}")
    print(f"  Dev labels   -> bonafide={(dev_labels == 0).sum()}, spoof={(dev_labels == 1).sum()}")


def _train_single_detector(
    phase_name: str,
    model: tf.keras.Model,
    train_x: np.ndarray,
    train_y: np.ndarray,
    dev_x: np.ndarray,
    dev_y: np.ndarray,
    lr: float,
    epochs: int,
    patience_es: int,
    patience_lr: int,
    save_path: Path,
) -> dict[str, float]:
    start = time.perf_counter()

    model.compile(
        optimizer=Adam(learning_rate=lr),
        loss=focal_loss(gamma=2.0, alpha=0.25),
        metrics=["accuracy", AUC(name="auc")],
    )

    callbacks = [
        EarlyStopping(
            monitor="val_auc",
            patience=patience_es,
            mode="max",
            restore_best_weights=True,
            verbose=1,
        ),
        ModelCheckpoint(
            filepath=str(save_path),
            monitor="val_auc",
            mode="max",
            save_best_only=True,
            verbose=1,
        ),
        ReduceLROnPlateau(
            monitor="val_auc",
            factor=0.5,
            patience=patience_lr,
            mode="max",
            min_lr=1e-6,
            verbose=1,
        ),
    ]

    history = model.fit(
        train_x,
        train_y,
        validation_data=(dev_x, dev_y),
        epochs=epochs,
        batch_size=256,
        callbacks=callbacks,
        class_weight={0: 4.9186, 1: 0.5566},
        verbose=2,
    )

    val_auc = np.asarray(history.history.get("val_auc", []), dtype=np.float64)
    val_acc = np.asarray(history.history.get("val_accuracy", []), dtype=np.float64)
    best_val_auc = float(np.max(val_auc)) if val_auc.size else float("nan")
    best_val_acc = float(np.max(val_acc)) if val_acc.size else float("nan")
    epochs_run = int(len(history.history.get("loss", [])))

    elapsed = time.perf_counter() - start

    print(f"\n{phase_name} results:")
    print(f"  Best val_auc: {best_val_auc:.4f}")
    print(f"  Best val_accuracy: {best_val_acc:.4f}")
    print(f"  Epochs run: {epochs_run}")
    print(f"  Elapsed: {elapsed:.2f} seconds ({elapsed / 60.0:.2f} minutes)")

    return {
        "best_val_auc": best_val_auc,
        "best_val_accuracy": best_val_acc,
        "epochs_run": float(epochs_run),
        "elapsed_s": elapsed,
    }


def _compute_eer(y_true: np.ndarray, y_scores: np.ndarray) -> tuple[float, float]:
    fpr, tpr, thresholds = roc_curve(y_true, y_scores)
    fnr = 1.0 - tpr
    idx = int(np.nanargmin(np.abs(fnr - fpr)))
    eer_threshold = float(thresholds[idx])
    eer = float(fpr[idx])
    return eer, eer_threshold


def make_balanced_tf_dataset(features, labels: np.ndarray, batch_size: int = 256):
    """Create balanced tf.data.Dataset with 50% bonafide, 50% spoof per batch."""
    bonafide_idx = np.where(labels == 0)[0]
    spoof_idx = np.where(labels == 1)[0]

    def _slice_features(idx: np.ndarray):
        if isinstance(features, (list, tuple)):
            return tuple(feat[idx] for feat in features)
        return features[idx]

    # Separate datasets for each class
    ds_bonafide = tf.data.Dataset.from_tensor_slices(
        (_slice_features(bonafide_idx), labels[bonafide_idx].astype(np.float32))
    ).repeat().shuffle(1000)

    ds_spoof = tf.data.Dataset.from_tensor_slices(
        (_slice_features(spoof_idx), labels[spoof_idx].astype(np.float32))
    ).repeat().shuffle(10000)

    # Sample 50% from each class in every batch
    ds = tf.data.Dataset.sample_from_datasets(
        [ds_bonafide, ds_spoof], weights=[0.5, 0.5]
    ).batch(batch_size).prefetch(tf.data.AUTOTUNE)

    return ds


def main() -> None:
    total_start = time.perf_counter()
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

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

    _validate_shapes(arrays)
    _print_dataset_overview(arrays)

    y_train = arrays["train_labels"].astype(np.float32)
    y_dev = arrays["dev_labels"].astype(np.float32)

    print("\nPHASE 1 - Train PhaseCoherenceDetector")
    phase_model = build_phase_coherence_detector()
    phase_model.compile(
        optimizer=Adam(learning_rate=1e-3),
        loss=focal_loss(gamma=2.0, alpha=0.25),
        metrics=["accuracy", AUC(name="auc")],
    )

    train_ds_phase = make_balanced_tf_dataset(arrays["train_phase"], y_train, batch_size=256)
    steps_per_epoch_phase = len(y_train) // 256

    callbacks_phase = [
        EarlyStopping(monitor="val_auc", patience=7, mode="max", restore_best_weights=True, verbose=1),
        ModelCheckpoint(filepath=str(OUTPUT_DIR / "phase_best.h5"), monitor="val_auc", mode="max", save_best_only=True, verbose=1),
        ReduceLROnPlateau(monitor="val_auc", factor=0.5, patience=3, mode="max", min_lr=1e-6, verbose=1),
    ]

    phase_start = time.perf_counter()
    phase_history = phase_model.fit(
        train_ds_phase,
        validation_data=(arrays["dev_phase"], y_dev),
        steps_per_epoch=steps_per_epoch_phase,
        epochs=30,
        callbacks=callbacks_phase,
        class_weight={0: 4.9186, 1: 0.5566},
        verbose=2,
    )

    phase_elapsed = time.perf_counter() - phase_start
    val_auc_phase = np.asarray(phase_history.history.get("val_auc", []), dtype=np.float64)
    val_acc_phase = np.asarray(phase_history.history.get("val_accuracy", []), dtype=np.float64)
    phase_metrics = {
        "best_val_auc": float(np.max(val_auc_phase)) if val_auc_phase.size else float("nan"),
        "best_val_accuracy": float(np.max(val_acc_phase)) if val_acc_phase.size else float("nan"),
        "epochs_run": float(len(phase_history.history.get("loss", []))),
        "elapsed_s": phase_elapsed,
    }

    print(f"\nPhaseCoherenceDetector results:")
    print(f"  Best val_auc: {phase_metrics['best_val_auc']:.4f}")
    print(f"  Best val_accuracy: {phase_metrics['best_val_accuracy']:.4f}")
    print(f"  Epochs run: {int(phase_metrics['epochs_run'])}")
    print(f"  Elapsed: {phase_elapsed:.2f} seconds ({phase_elapsed / 60.0:.2f} minutes)")

    print("\nPHASE 2 - Train GlottalDetector")
    glottal_model = build_glottal_detector()
    glottal_model.compile(
        optimizer=Adam(learning_rate=1e-3),
        loss=focal_loss(gamma=2.0, alpha=0.25),
        metrics=["accuracy", AUC(name="auc")],
    )

    train_ds_glottal = make_balanced_tf_dataset(arrays["train_glottal"], y_train, batch_size=256)
    steps_per_epoch_glottal = len(y_train) // 256

    callbacks_glottal = [
        EarlyStopping(monitor="val_auc", patience=7, mode="max", restore_best_weights=True, verbose=1),
        ModelCheckpoint(filepath=str(OUTPUT_DIR / "glottal_best.h5"), monitor="val_auc", mode="max", save_best_only=True, verbose=1),
        ReduceLROnPlateau(monitor="val_auc", factor=0.5, patience=3, mode="max", min_lr=1e-6, verbose=1),
    ]

    glottal_start = time.perf_counter()
    glottal_history = glottal_model.fit(
        train_ds_glottal,
        validation_data=(arrays["dev_glottal"], y_dev),
        steps_per_epoch=steps_per_epoch_glottal,
        epochs=30,
        callbacks=callbacks_glottal,
        class_weight={0: 4.9186, 1: 0.5566},
        verbose=2,
    )

    glottal_elapsed = time.perf_counter() - glottal_start
    val_auc_glottal = np.asarray(glottal_history.history.get("val_auc", []), dtype=np.float64)
    val_acc_glottal = np.asarray(glottal_history.history.get("val_accuracy", []), dtype=np.float64)
    glottal_metrics = {
        "best_val_auc": float(np.max(val_auc_glottal)) if val_auc_glottal.size else float("nan"),
        "best_val_accuracy": float(np.max(val_acc_glottal)) if val_acc_glottal.size else float("nan"),
        "epochs_run": float(len(glottal_history.history.get("loss", []))),
        "elapsed_s": glottal_elapsed,
    }

    print(f"\nGlottalDetector results:")
    print(f"  Best val_auc: {glottal_metrics['best_val_auc']:.4f}")
    print(f"  Best val_accuracy: {glottal_metrics['best_val_accuracy']:.4f}")
    print(f"  Epochs run: {int(glottal_metrics['epochs_run'])}")
    print(f"  Elapsed: {glottal_elapsed:.2f} seconds ({glottal_elapsed / 60.0:.2f} minutes)")

    print("\nPHASE 3 - Train WavLMDetector")
    wavlm_model = build_wavlm_detector()
    wavlm_model.compile(
        optimizer=Adam(learning_rate=1e-4),
        loss=focal_loss(gamma=2.0, alpha=0.25),
        metrics=["accuracy", AUC(name="auc")],
    )

    train_ds_wavlm = make_balanced_tf_dataset(arrays["train_wavlm"], y_train, batch_size=256)
    steps_per_epoch_wavlm = len(y_train) // 256

    callbacks_wavlm = [
        EarlyStopping(monitor="val_auc", patience=7, mode="max", restore_best_weights=True, verbose=1),
        ModelCheckpoint(filepath=str(OUTPUT_DIR / "wavlm_best.h5"), monitor="val_auc", mode="max", save_best_only=True, verbose=1),
        ReduceLROnPlateau(monitor="val_auc", factor=0.5, patience=3, mode="max", min_lr=1e-6, verbose=1),
    ]

    wavlm_start = time.perf_counter()
    wavlm_history = wavlm_model.fit(
        train_ds_wavlm,
        validation_data=(arrays["dev_wavlm"], y_dev),
        steps_per_epoch=steps_per_epoch_wavlm,
        epochs=20,
        callbacks=callbacks_wavlm,
        class_weight={0: 4.9186, 1: 0.5566},
        verbose=2,
    )

    wavlm_elapsed = time.perf_counter() - wavlm_start
    val_auc_wavlm = np.asarray(wavlm_history.history.get("val_auc", []), dtype=np.float64)
    val_acc_wavlm = np.asarray(wavlm_history.history.get("val_accuracy", []), dtype=np.float64)
    wavlm_metrics = {
        "best_val_auc": float(np.max(val_auc_wavlm)) if val_auc_wavlm.size else float("nan"),
        "best_val_accuracy": float(np.max(val_acc_wavlm)) if val_acc_wavlm.size else float("nan"),
        "epochs_run": float(len(wavlm_history.history.get("loss", []))),
        "elapsed_s": wavlm_elapsed,
    }

    print(f"\nWavLMDetector results:")
    print(f"  Best val_auc: {wavlm_metrics['best_val_auc']:.4f}")
    print(f"  Best val_accuracy: {wavlm_metrics['best_val_accuracy']:.4f}")
    print(f"  Epochs run: {int(wavlm_metrics['epochs_run'])}")
    print(f"  Elapsed: {wavlm_elapsed:.2f} seconds ({wavlm_elapsed / 60.0:.2f} minutes)")

    print("\nPHASE 4 - Train EnsembleModel")
    phase_loaded = load_model(OUTPUT_DIR / "phase_best.h5", compile=False)
    glottal_loaded = load_model(OUTPUT_DIR / "glottal_best.h5", compile=False)
    wavlm_loaded = load_model(OUTPUT_DIR / "wavlm_best.h5", compile=False)

    phase_train_scores = phase_loaded.predict(arrays["train_phase"], batch_size=512, verbose=1).astype(np.float32)
    glottal_train_scores = glottal_loaded.predict(arrays["train_glottal"], batch_size=512, verbose=1).astype(np.float32)
    wavlm_train_scores = wavlm_loaded.predict(arrays["train_wavlm"], batch_size=512, verbose=1).astype(np.float32)

    phase_dev_scores = phase_loaded.predict(arrays["dev_phase"], batch_size=512, verbose=1).astype(np.float32)
    glottal_dev_scores = glottal_loaded.predict(arrays["dev_glottal"], batch_size=512, verbose=1).astype(np.float32)
    wavlm_dev_scores = wavlm_loaded.predict(arrays["dev_wavlm"], batch_size=512, verbose=1).astype(np.float32)

    ensemble_train_input = [
        phase_train_scores,
        glottal_train_scores,
        wavlm_train_scores,
    ]
    ensemble_val_input = [
        phase_dev_scores,
        glottal_dev_scores,
        wavlm_dev_scores,
    ]

    ensemble_model = build_ensemble_model()
    ensemble_model.compile(
        optimizer=Adam(learning_rate=1e-4),
        loss="binary_crossentropy",
        metrics=["accuracy", AUC(name="auc")],
    )

    train_ds_ensemble = make_balanced_tf_dataset(ensemble_train_input, y_train, batch_size=256)
    steps_per_epoch_ensemble = len(y_train) // 256

    phase4_start = time.perf_counter()
    ensemble_callbacks = [
        EarlyStopping(monitor="val_auc", patience=15, mode="max", restore_best_weights=True, verbose=1),
        ModelCheckpoint(filepath=str(OUTPUT_DIR / "ensemble_best.h5"), monitor="val_auc", mode="max", save_best_only=True, verbose=1),
        ReduceLROnPlateau(monitor="val_auc", factor=0.5, patience=5, mode="max", min_lr=1e-6, verbose=1),
    ]

    ensemble_history = ensemble_model.fit(
        train_ds_ensemble,
        validation_data=(ensemble_val_input, y_dev.astype(np.float32)),
        steps_per_epoch=steps_per_epoch_ensemble,
        epochs=100,
        callbacks=ensemble_callbacks,
        class_weight={0: 4.9186, 1: 0.5566},
        verbose=2,
    )

    ens_val_auc = np.asarray(ensemble_history.history.get("val_auc", []), dtype=np.float64)
    ens_val_acc = np.asarray(ensemble_history.history.get("val_accuracy", []), dtype=np.float64)
    ens_best_auc = float(np.max(ens_val_auc)) if ens_val_auc.size else float("nan")
    ens_best_acc = float(np.max(ens_val_acc)) if ens_val_acc.size else float("nan")
    ens_epochs_run = int(len(ensemble_history.history.get("loss", [])))

    ensemble_dev_scores_out = ensemble_model.predict(ensemble_val_input, batch_size=512, verbose=1).reshape(-1).astype(np.float32)
    ensemble_eer, eer_threshold = _compute_eer(arrays["dev_labels"], ensemble_dev_scores_out)

    phase4_elapsed = time.perf_counter() - phase4_start

    print("\nEnsembleModel results:")
    print(f"  Best val_auc: {ens_best_auc:.4f}")
    print(f"  Best val_accuracy: {ens_best_acc:.4f}")
    print(f"  Epochs run: {ens_epochs_run}")
    print(f"  Final EER on dev: {ensemble_eer * 100.0:.2f}%")
    print(f"  EER threshold: {eer_threshold:.6f}")
    print(f"  Elapsed: {phase4_elapsed:.2f} seconds ({phase4_elapsed / 60.0:.2f} minutes)")

    total_elapsed = time.perf_counter() - total_start

    print("\nFull training summary:")
    print(f"  Phase detector val_auc:   {phase_metrics['best_val_auc']:.4f}")
    print(f"  Glottal detector val_auc: {glottal_metrics['best_val_auc']:.4f}")
    print(f"  WavLM detector val_auc:   {wavlm_metrics['best_val_auc']:.4f}")
    print(f"  Ensemble val_auc:         {ens_best_auc:.4f}")
    print(f"  Ensemble EER on dev:      {ensemble_eer * 100.0:.2f}%")
    print(f"  All models saved to {OUTPUT_DIR}/")
    print(f"  Total elapsed time: {total_elapsed:.2f} seconds ({total_elapsed / 3600.0:.2f} hours)")


if __name__ == "__main__":
    main()
