"""
# Tier 3 — Distilled scam classifier
# Input: 768-dim DistilBERT CLS hidden state
# Output: sigmoid score (>0.5 = scam)
# Target: <50KB TFLite INT8
"""

import pathlib

import joblib
import numpy as np
import sklearn.metrics
import tensorflow as tf


Path = pathlib.Path

BASE_DIR = Path.home() / "Desktop/Hackaccino"
TIER3_DIR = BASE_DIR / "dataset/tier3"
OUT_DIR = BASE_DIR / "models/tier3"
TFLITE_PATH = OUT_DIR / "tier3_classifier.tflite"
EPOCHS = 50
BATCH_SIZE = 64
LR = 1e-3


class EpochPrinter(tf.keras.callbacks.Callback):
    def on_epoch_end(self, epoch, logs=None):
        logs = logs or {}
        val_auc = float(logs.get("val_auc", 0.0))
        val_acc = float(logs.get("val_acc", 0.0))
        print(f"Epoch {epoch + 1} | val_auc={val_auc:.4f} | val_acc={val_acc:.4f}")


def load_data():
    x_train = np.load(TIER3_DIR / "tier3_train_features.npy").astype(np.float32)
    y_train = np.load(TIER3_DIR / "tier3_train_labels.npy").astype(np.int32)
    x_val = np.load(TIER3_DIR / "tier3_val_features.npy").astype(np.float32)
    y_val = np.load(TIER3_DIR / "tier3_val_labels.npy").astype(np.int32)
    return x_train, y_train, x_val, y_val


def compute_class_weights(y_train):
    total = int(y_train.shape[0])
    count_0 = int(np.sum(y_train == 0))
    count_1 = int(np.sum(y_train == 1))

    if count_0 == 0 or count_1 == 0:
        raise ValueError("Both classes must be present in training labels")

    weight_0 = total / (2.0 * count_0)
    weight_1 = total / (2.0 * count_1)
    return weight_0, weight_1


def build_model():
    model = tf.keras.Sequential(
        [
            tf.keras.layers.Input(shape=(768,)),
            tf.keras.layers.Dense(256, activation="relu"),
            tf.keras.layers.Dropout(0.3),
            tf.keras.layers.Dense(64, activation="relu"),
            tf.keras.layers.Dropout(0.2),
            tf.keras.layers.Dense(1, activation="sigmoid"),
        ]
    )

    model.compile(
        optimizer=tf.keras.optimizers.Adam(learning_rate=LR),
        loss=tf.keras.losses.BinaryCrossentropy(),
        metrics=[
            tf.keras.metrics.AUC(name="auc"),
            tf.keras.metrics.BinaryAccuracy(name="acc"),
        ],
    )
    return model


def build_representative_dataset(features):
    n = int(features.shape[0])
    sample_count = min(100, n)
    rng = np.random.default_rng(42)
    indices = rng.choice(n, size=sample_count, replace=False)

    def representative_dataset():
        for idx in indices:
            yield [features[idx : idx + 1].astype(np.float32)]

    return representative_dataset


def quantize_input_for_tflite(sample_f32, input_detail):
    if input_detail["dtype"] != np.int8:
        return sample_f32.astype(input_detail["dtype"])

    scale, zero_point = input_detail["quantization"]
    if scale == 0:
        return sample_f32.astype(np.int8)

    q = np.round(sample_f32 / scale + zero_point)
    q = np.clip(q, -128, 127).astype(np.int8)
    return q


def main():
    OUT_DIR.mkdir(parents=True, exist_ok=True)

    x_train, y_train, x_val, y_val = load_data()
    weight_0, weight_1 = compute_class_weights(y_train)

    model = build_model()

    callbacks = [
        tf.keras.callbacks.EarlyStopping(
            monitor="val_auc",
            patience=8,
            restore_best_weights=True,
            mode="max",
        ),
        tf.keras.callbacks.ModelCheckpoint(
            OUT_DIR / "best.keras",
            monitor="val_auc",
            save_best_only=True,
            mode="max",
        ),
        EpochPrinter(),
    ]

    history = model.fit(
        x_train,
        y_train,
        validation_data=(x_val, y_val),
        epochs=EPOCHS,
        batch_size=BATCH_SIZE,
        class_weight={0: weight_0, 1: weight_1},
        callbacks=callbacks,
        verbose=0,
    )

    best_model = tf.keras.models.load_model(OUT_DIR / "best.keras")

    val_scores = best_model.predict(x_val, batch_size=BATCH_SIZE, verbose=0).reshape(-1)
    val_preds = (val_scores >= 0.5).astype(np.int32)

    auc = float(sklearn.metrics.roc_auc_score(y_val, val_scores))
    acc = float(sklearn.metrics.accuracy_score(y_val, val_preds))
    f1 = float(sklearn.metrics.f1_score(y_val, val_preds, zero_division=0))
    precision = float(sklearn.metrics.precision_score(y_val, val_preds, zero_division=0))
    recall = float(sklearn.metrics.recall_score(y_val, val_preds, zero_division=0))

    print(f"AUC: {auc:.4f}")
    print(f"Accuracy: {acc:.4f}")
    print(f"F1: {f1:.4f}")
    print(f"Precision: {precision:.4f}")
    print(f"Recall: {recall:.4f}")
    print("Classification report:")
    print(sklearn.metrics.classification_report(y_val, val_preds, digits=4, zero_division=0))

    converter = tf.lite.TFLiteConverter.from_keras_model(best_model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    converter.representative_dataset = build_representative_dataset(x_train)
    converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS_INT8]
    converter.inference_input_type = tf.int8
    converter.inference_output_type = tf.float32

    tflite_model = converter.convert()
    with TFLITE_PATH.open("wb") as f:
        f.write(tflite_model)

    tflite_size_kb = int(round(TFLITE_PATH.stat().st_size / 1024.0))
    print(f"TFLite file size: {tflite_size_kb} KB")

    interpreter = tf.lite.Interpreter(model_path=str(TFLITE_PATH))
    interpreter.allocate_tensors()
    input_detail = interpreter.get_input_details()[0]
    output_detail = interpreter.get_output_details()[0]

    for i in range(min(5, x_val.shape[0])):
        sample = x_val[i : i + 1].astype(np.float32)
        sample_input = quantize_input_for_tflite(sample, input_detail)

        interpreter.set_tensor(input_detail["index"], sample_input)
        interpreter.invoke()

        output = interpreter.get_tensor(output_detail["index"])
        pred_score = float(output.reshape(-1)[0])
        pred_label = 1 if pred_score >= 0.5 else 0
        actual_label = int(y_val[i])

        print(f"Sample {i + 1}: predicted={pred_label} actual={actual_label}")

    best_val_auc = max(float(v) for v in history.history.get("val_auc", [0.0]))

    joblib.dump(
        {
            "best_val_auc": best_val_auc,
            "auc": auc,
            "accuracy": acc,
            "f1": f1,
            "precision": precision,
            "recall": recall,
        },
        OUT_DIR / "metrics.joblib",
    )

    print(f"Best val AUC:  {best_val_auc:.4f}")
    print(f"TFLite size:   {tflite_size_kb} KB")
    print("Saved to:      models/tier3/tier3_classifier.tflite")


if __name__ == "__main__":
    main()
