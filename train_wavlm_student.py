#!/usr/bin/env python3
"""Train the WavLM Student CNN via Distillation.

Extracts features from the raw 32000-sample audio and mimics the 128-dim
WavLM-Base+ teacher outputs using a combined MSE + Cosine Distance loss.
"""

import sys
import time
from pathlib import Path

import numpy as np
import tensorflow as tf
from tensorflow.keras.callbacks import EarlyStopping, ModelCheckpoint, ReduceLROnPlateau
from tensorflow.keras.optimizers import Adam
from tensorflow.keras import Input, Model
from tensorflow.keras.layers import (
    BatchNormalization,
    Conv1D,
    Dense,
    GlobalAveragePooling1D,
    Dropout,
)

DATASET_DIR = Path("dataset")
OUTPUT_DIR = Path("models/detectors")

def build_wavlm_student(input_length: int = 32000) -> tf.keras.Model:
    """1D CNN Student Architecture to process raw audio to 128-dim WavLM embedding."""
    x_in = Input(shape=(input_length, 1), name="audio_input")
    
    # Block 1
    x = Conv1D(32, kernel_size=11, strides=5, padding="same", activation="relu", name="stu_conv1")(x_in)
    x = BatchNormalization(name="stu_bn1")(x)
    
    # Block 2
    x = Conv1D(64, kernel_size=11, strides=4, padding="same", activation="relu", name="stu_conv2")(x)
    x = BatchNormalization(name="stu_bn2")(x)
    
    # Block 3
    x = Conv1D(128, kernel_size=7, strides=4, padding="same", activation="relu", name="stu_conv3")(x)
    x = BatchNormalization(name="stu_bn3")(x)
    
    # Block 4
    x = Conv1D(128, kernel_size=7, strides=2, padding="same", activation="relu", name="stu_conv4")(x)
    x = BatchNormalization(name="stu_bn4")(x)
    x = Dropout(0.2, name="stu_drop4")(x)

    x = GlobalAveragePooling1D(name="stu_gap")(x)
    
    # Output matches the exactly 128-dim continuous features from the WavLM teacher
    y = Dense(128, activation="linear", name="wavlm_embedding_out")(x)

    return Model(inputs=x_in, outputs=y, name="WavLMStudent")

def mse_cosine_loss(alpha_cosine=0.5):
    """Combined MSE and Cosine Distance Loss for representation distillation."""
    def loss(y_true, y_pred):
        # Mean Squared Error for magnitude scale alignment
        mse = tf.keras.losses.mean_squared_error(y_true, y_pred)
        
        # Cosine distance for angular feature alignment
        y_true_norm = tf.math.l2_normalize(y_true, axis=-1)
        y_pred_norm = tf.math.l2_normalize(y_pred, axis=-1)
        cos_sim = tf.reduce_sum(y_true_norm * y_pred_norm, axis=-1)
        cos_loss = 1.0 - cos_sim
        
        return mse + (alpha_cosine * cos_loss)
    return loss

def _load_distill_array(name: str, dtype):
    path = DATASET_DIR / f"{name}.npy"
    if not path.exists():
        print(f"ERROR: missing distillation file: {path}")
        print("Please ensure you ran build_wavlm_distill_dataset.py first.")
        sys.exit(1)
    print(f"Loading {path}...")
    return np.load(path).astype(dtype)

def main():
    total_start = time.perf_counter()
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

    print("Loading distillation datasets...")
    # Raw waveform inputs shape: (batch, 32000)
    train_audio = _load_distill_array("distill_train_audio", np.float32)
    dev_audio = _load_distill_array("distill_dev_audio", np.float32)
    
    # Needs to be reshaped for Conv1D to (batch, 32000, 1)
    train_audio = np.expand_dims(train_audio, axis=-1)
    dev_audio = np.expand_dims(dev_audio, axis=-1)

    # Teacher targets shape: (batch, 128)
    train_targets = _load_distill_array("distill_train_wavlm", np.float32)
    dev_targets = _load_distill_array("distill_dev_wavlm", np.float32)

    student_model = build_wavlm_student(input_length=32000)
    student_model.compile(
        optimizer=Adam(learning_rate=1e-3),
        loss=mse_cosine_loss(alpha_cosine=0.5),
        metrics=["mae", "mse"]
    )
    
    student_model.summary()

    callbacks = [
        EarlyStopping(monitor="val_loss", patience=15, mode="min", restore_best_weights=True, verbose=1),
        ModelCheckpoint(filepath=str(OUTPUT_DIR / "wavlm_student_best.h5"), monitor="val_loss", mode="min", save_best_only=True, verbose=1),
        ReduceLROnPlateau(monitor="val_loss", factor=0.5, patience=5, mode="min", min_lr=1e-6, verbose=1),
    ]

    print("\n--- DISTILLING WAVLM STUDENT CNN ---")
    student_model.fit(
        x=train_audio,
        y=train_targets,
        validation_data=(dev_audio, dev_targets),
        epochs=150,
        batch_size=128,
        callbacks=callbacks,
        verbose=1,
    )
    
    total_elapsed = time.perf_counter() - total_start
    print(f"\nWavLM Student distillation complete in {total_elapsed / 60.0:.2f} minutes.")
    print(f"Student model successfully saved to {OUTPUT_DIR}/wavlm_student_best.h5.")

if __name__ == "__main__":
    main()
