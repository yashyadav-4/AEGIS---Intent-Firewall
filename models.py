#!/usr/bin/env python3
"""Model definitions for Aegis-Zero deepfake voice detection."""

from __future__ import annotations

import numpy as np
import tensorflow as tf
from tensorflow.keras import Input, Model
from tensorflow.keras.layers import (
    BatchNormalization,
    Concatenate,
    Conv1D,
    Dense,
    Dropout,
    GlobalAveragePooling1D,
    Multiply,
    Reshape,
)
from tensorflow.keras.metrics import AUC
from tensorflow.keras.optimizers import Adam
from tensorflow.keras.regularizers import l2


def _compile_binary_model(model: tf.keras.Model, learning_rate: float = 1e-3) -> tf.keras.Model:
    model.compile(
        optimizer=Adam(learning_rate=learning_rate),
        loss="binary_crossentropy",
        metrics=["accuracy", AUC(name="auc")],
    )
    return model


def build_phase_coherence_detector() -> tf.keras.Model:
    """Build PhaseCoherenceDetector.

    Input: (batch, 64)
    Output: (batch, 1)
    """
    x_in = Input(shape=(64,), name="phase_input")
    x = Reshape((64, 1), name="phase_reshape")(x_in)
    x = Conv1D(
        32,
        kernel_size=3,
        padding="same",
        activation="relu",
        kernel_regularizer=l2(0.001),
        name="phase_conv1",
    )(x)
    x = BatchNormalization(name="phase_bn1")(x)
    x = Conv1D(
        64,
        kernel_size=3,
        padding="same",
        activation="relu",
        kernel_regularizer=l2(0.001),
        name="phase_conv2",
    )(x)
    x = BatchNormalization(name="phase_bn2")(x)
    x = GlobalAveragePooling1D(name="phase_gap")(x)
    x = Dense(32, activation="relu", kernel_regularizer=l2(0.001), name="phase_dense1")(x)
    x = Dropout(0.3, name="phase_dropout")(x)
    y = Dense(1, activation="sigmoid", name="phase_output")(x)

    model = Model(inputs=x_in, outputs=y, name="PhaseCoherenceDetector")
    return _compile_binary_model(model)


def build_glottal_detector() -> tf.keras.Model:
    """Build GlottalDetector.

    Input: (batch, 12)
    Output: (batch, 1)
    """
    x_in = Input(shape=(12,), name="glottal_input")
    x = Dense(64, activation="relu", kernel_regularizer=l2(0.001), name="glottal_dense1")(x_in)
    x = BatchNormalization(name="glottal_bn1")(x)
    x = Dropout(0.4, name="glottal_dropout1")(x)
    x = Dense(32, activation="relu", kernel_regularizer=l2(0.001), name="glottal_dense2")(x)
    x = BatchNormalization(name="glottal_bn2")(x)
    x = Dropout(0.3, name="glottal_dropout2")(x)
    y = Dense(1, activation="sigmoid", name="glottal_output")(x)

    model = Model(inputs=x_in, outputs=y, name="GlottalDetector")
    return _compile_binary_model(model)


def build_wavlm_detector() -> tf.keras.Model:
    """Build WavLMDetector linear probe.

    Input: (batch, 128)
    Output: (batch, 1)
    """
    x_in = Input(shape=(128,), name="wavlm_input")
    y = Dense(1, activation="sigmoid", name="wavlm_output")(x_in)

    model = Model(inputs=x_in, outputs=y, name="WavLMDetector")
    return _compile_binary_model(model)


def build_ensemble_model() -> tf.keras.Model:
    """Build EnsembleModel.

    Inputs: phase_score, glottal_score, wavlm_score each with shape (batch, 1)
    Concatenates [p, g, w, p*g, p*w, g*w] then Dense(16)->Dropout(0.2)->Dense(1)
    Output: (batch, 1)
    """
    p_in = Input(shape=(1,), name="phase_score")
    g_in = Input(shape=(1,), name="glottal_score")
    w_in = Input(shape=(1,), name="wavlm_score")

    p_mul_g = Multiply(name="pg")([p_in, g_in])
    p_mul_w = Multiply(name="pw")([p_in, w_in])
    g_mul_w = Multiply(name="gw")([g_in, w_in])

    fused = Concatenate(name="fusion_concat")([p_in, g_in, w_in, p_mul_g, p_mul_w, g_mul_w])
    x = Dense(32, activation="relu", name="fusion_dense0")(fused)
    x = Dropout(0.15, name="fusion_dropout0")(x)
    x = Dense(16, activation="relu", name="fusion_dense1")(x)
    x = Dropout(0.2, name="fusion_dropout1")(x)
    y = Dense(1, activation="sigmoid", name="fusion_output")(x)

    model = Model(inputs=[p_in, g_in, w_in], outputs=y, name="EnsembleModel")
    return _compile_binary_model(model)


def build_all_models() -> dict[str, tf.keras.Model]:
    """Build and return all models."""
    phase_model = build_phase_coherence_detector()
    glottal_model = build_glottal_detector()
    wavlm_model = build_wavlm_detector()
    ensemble_model = build_ensemble_model()

    return {
        "phase": phase_model,
        "glottal": glottal_model,
        "wavlm": wavlm_model,
        "ensemble": ensemble_model,
    }


def _verify_models() -> None:
    models = build_all_models()
    batch = 4

    for name in ["phase", "glottal", "wavlm", "ensemble"]:
        print(f"\n=== {name.upper()} MODEL SUMMARY ===")
        models[name].summary()

    phase_out = models["phase"](np.random.rand(batch, 64).astype(np.float32), training=False)
    glottal_out = models["glottal"](np.random.rand(batch, 12).astype(np.float32), training=False)
    wavlm_out = models["wavlm"](np.random.rand(batch, 128).astype(np.float32), training=False)
    ensemble_out = models["ensemble"](
        [
            np.random.rand(batch, 1).astype(np.float32),
            np.random.rand(batch, 1).astype(np.float32),
            np.random.rand(batch, 1).astype(np.float32),
        ],
        training=False,
    )

    checks = {
        "phase": tuple(phase_out.shape) == (batch, 1),
        "glottal": tuple(glottal_out.shape) == (batch, 1),
        "wavlm": tuple(wavlm_out.shape) == (batch, 1),
        "ensemble": tuple(ensemble_out.shape) == (batch, 1),
    }

    print("\nForward-pass output shapes:")
    print(f"phase: {tuple(phase_out.shape)}")
    print(f"glottal: {tuple(glottal_out.shape)}")
    print(f"wavlm: {tuple(wavlm_out.shape)}")
    print(f"ensemble: {tuple(ensemble_out.shape)}")

    if all(checks.values()):
        print("Models verified: PASS")
    else:
        raise RuntimeError(f"Model verification failed: {checks}")


if __name__ == "__main__":
    _verify_models()
