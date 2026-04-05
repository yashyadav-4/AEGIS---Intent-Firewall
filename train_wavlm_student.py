#!/usr/bin/env python3
"""Train a compact WavLM student model from offline distillation targets."""

from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np
import tensorflow as tf

TRAIN_WAVE_PATH = Path("dataset/distill/train_waveforms.npy")
TRAIN_TEACHER_PATH = Path("dataset/distill/train_wavlm_teacher.npy")
DEV_WAVE_PATH = Path("dataset/distill/dev_waveforms.npy")
DEV_TEACHER_PATH = Path("dataset/distill/dev_wavlm_teacher.npy")
OUT_MODEL_PATH = Path("models/student/wavlm_student_best.keras")

WAVEFORM_LEN = 32000
FEATURE_DIM = 128
BATCH_SIZE = 64
EPOCHS = 50
LEARNING_RATE = 1e-3


def combined_loss(y_true: tf.Tensor, y_pred: tf.Tensor) -> tf.Tensor:
    mse = tf.reduce_mean(tf.square(y_true - y_pred))
    y_true_n = tf.nn.l2_normalize(y_true, axis=1)
    y_pred_n = tf.nn.l2_normalize(y_pred, axis=1)
    cosine_dist = 1.0 - tf.reduce_mean(tf.reduce_sum(y_true_n * y_pred_n, axis=1))
    return mse + 0.1 * cosine_dist


def build_wavlm_student() -> tf.keras.Model:
    inputs = tf.keras.Input(shape=(WAVEFORM_LEN, 1), dtype=tf.float32)

    x = tf.keras.layers.Conv1D(32, kernel_size=400, strides=8, padding="same")(inputs)
    x = tf.keras.layers.BatchNormalization()(x)
    x = tf.keras.layers.ReLU()(x)

    x = tf.keras.layers.DepthwiseConv1D(kernel_size=100, strides=8, padding="same")(x)
    x = tf.keras.layers.Conv1D(64, kernel_size=1, padding="same")(x)
    x = tf.keras.layers.BatchNormalization()(x)
    x = tf.keras.layers.ReLU()(x)

    x = tf.keras.layers.DepthwiseConv1D(kernel_size=25, strides=4, padding="same")(x)
    x = tf.keras.layers.Conv1D(128, kernel_size=1, padding="same")(x)
    x = tf.keras.layers.BatchNormalization()(x)
    x = tf.keras.layers.ReLU()(x)

    x = tf.keras.layers.DepthwiseConv1D(kernel_size=10, strides=8, padding="same")(x)
    x = tf.keras.layers.Conv1D(128, kernel_size=1, padding="same")(x)
    x = tf.keras.layers.BatchNormalization()(x)
    x = tf.keras.layers.ReLU()(x)

    x = tf.keras.layers.GlobalAveragePooling1D()(x)
    x = tf.keras.layers.Dense(256, activation="relu")(x)
    x = tf.keras.layers.Dropout(0.2)(x)
    outputs = tf.keras.layers.Dense(128)(x)

    model = tf.keras.Model(inputs=inputs, outputs=outputs, name="wavlm_student")
    return model


def make_dataset(wave_memmap: np.ndarray, teacher_memmap: np.ndarray, batch_size: int, shuffle: bool) -> tf.data.Dataset:
    n = wave_memmap.shape[0]

    def _gen():
        idxs = np.arange(n)
        if shuffle:
            np.random.shuffle(idxs)
        for i in idxs:
            x = wave_memmap[i].astype(np.float32)
            y = teacher_memmap[i].astype(np.float32)
            yield np.expand_dims(x, axis=-1), y

    output_signature = (
        tf.TensorSpec(shape=(WAVEFORM_LEN, 1), dtype=tf.float32),
        tf.TensorSpec(shape=(FEATURE_DIM,), dtype=tf.float32),
    )

    ds = tf.data.Dataset.from_generator(_gen, output_signature=output_signature)
    ds = ds.batch(batch_size)
    ds = ds.prefetch(tf.data.AUTOTUNE)
    return ds


def evaluate_dev_metrics(model: tf.keras.Model, dev_ds: tf.data.Dataset) -> tuple[float, float]:
    cosine_values = []
    total_sqerr = 0.0
    total_count = 0

    for x_batch, y_batch in dev_ds:
        preds = model(x_batch, training=False)
        y_true_n = tf.nn.l2_normalize(y_batch, axis=1)
        y_pred_n = tf.nn.l2_normalize(preds, axis=1)
        cos = tf.reduce_sum(y_true_n * y_pred_n, axis=1)
        cosine_values.append(cos.numpy())

        sqerr = tf.reduce_sum(tf.square(y_batch - preds)).numpy()
        total_sqerr += float(sqerr)
        total_count += int(np.prod(y_batch.shape))

    cosine_all = np.concatenate(cosine_values, axis=0)
    median_cosine = float(np.median(cosine_all))
    mse = float(total_sqerr / max(total_count, 1))
    return median_cosine, mse


def main() -> None:
    parser = argparse.ArgumentParser(description="Train WavLM student distillation model.")
    parser.add_argument("--batch-size", type=int, default=BATCH_SIZE)
    parser.add_argument("--epochs", type=int, default=EPOCHS)
    parser.add_argument("--lr", type=float, default=LEARNING_RATE)
    args = parser.parse_args()

    OUT_MODEL_PATH.parent.mkdir(parents=True, exist_ok=True)

    train_wave = np.load(TRAIN_WAVE_PATH, mmap_mode="r")
    train_teacher = np.load(TRAIN_TEACHER_PATH, mmap_mode="r")
    dev_wave = np.load(DEV_WAVE_PATH, mmap_mode="r")
    dev_teacher = np.load(DEV_TEACHER_PATH, mmap_mode="r")

    if train_wave.shape[1] != WAVEFORM_LEN or dev_wave.shape[1] != WAVEFORM_LEN:
        raise ValueError("Waveform arrays must have shape (N, 32000).")
    if train_teacher.shape[1] != FEATURE_DIM or dev_teacher.shape[1] != FEATURE_DIM:
        raise ValueError("Teacher arrays must have shape (N, 128).")

    print(f"Train waveforms: {train_wave.shape}")
    print(f"Train teachers:  {train_teacher.shape}")
    print(f"Dev waveforms:   {dev_wave.shape}")
    print(f"Dev teachers:    {dev_teacher.shape}")

    train_ds = make_dataset(train_wave, train_teacher, batch_size=args.batch_size, shuffle=True)
    dev_ds = make_dataset(dev_wave, dev_teacher, batch_size=args.batch_size, shuffle=False)

    model = build_wavlm_student()
    model.compile(
        optimizer=tf.keras.optimizers.Adam(learning_rate=args.lr),
        loss=combined_loss,
        metrics=[tf.keras.metrics.MeanSquaredError(name="mse")],
    )

    model.summary()
    print(f"Total parameters: {model.count_params()}")

    callbacks = [
        tf.keras.callbacks.EarlyStopping(monitor="val_loss", patience=10, restore_best_weights=True),
        tf.keras.callbacks.ReduceLROnPlateau(monitor="val_loss", patience=5, factor=0.5, verbose=1),
        tf.keras.callbacks.ModelCheckpoint(
            filepath=str(OUT_MODEL_PATH),
            monitor="val_loss",
            save_best_only=True,
            save_weights_only=False,
            verbose=1,
        ),
    ]

    history = model.fit(
        train_ds,
        validation_data=dev_ds,
        epochs=args.epochs,
        callbacks=callbacks,
        verbose=1,
    )

    if OUT_MODEL_PATH.exists():
        best_model = tf.keras.models.load_model(
            OUT_MODEL_PATH,
            custom_objects={"combined_loss": combined_loss},
            compile=False,
        )
    else:
        best_model = model

    median_cosine, mse = evaluate_dev_metrics(best_model, dev_ds)
    print(f"Median cosine similarity (dev): {median_cosine:.6f}")
    print(f"MSE (dev): {mse:.6f}")

    if OUT_MODEL_PATH.exists():
        print(f"Model saved to: {OUT_MODEL_PATH}")
    else:
        print("Warning: best model file was not created.")

    if median_cosine > 0.85 and OUT_MODEL_PATH.exists():
        print("SUCCESS: cosine similarity > 0.85 and model saved.")
    else:
        print("FAIL: success condition not met.")

    print(f"Training epochs completed: {len(history.history.get('loss', []))}")


if __name__ == "__main__":
    tf.keras.utils.set_random_seed(42)
    main()
