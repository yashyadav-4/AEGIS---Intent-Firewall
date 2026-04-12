"""
Resume training from saved teacher model.
Teacher is saved at models/scam_teacher/best/ (Epoch 2, F1=0.9994).
This script:
  1. Extracts CLS features from saved teacher
  2. Trains TFLite student with knowledge distillation
  3. Exports INT8 TFLite
  4. Validates on test set
"""

import json
import math
import pathlib
import random
import shutil
import time

import joblib
import numpy as np
import sklearn.metrics
import tensorflow as tf
import torch
import transformers
import tqdm


SEED = 42
BASE_DIR = pathlib.Path.home() / "Desktop" / "Hackaccino"
DATA_PATH = BASE_DIR / "dataset" / "scam_dialogues_v2.jsonl"
TEACHER_DIR = BASE_DIR / "models" / "scam_teacher" / "best"
DISTILL_DIR = BASE_DIR / "dataset" / "tier3_v2"
STUDENT_DIR = BASE_DIR / "models" / "scam_student"
TFLITE_PATH = STUDENT_DIR / "scam_intent_classifier.tflite"
ANDROID_ASSETS = BASE_DIR / "android" / "app" / "src" / "main" / "assets"

TEACHER_MAX_LEN = 256
TEACHER_BATCH = 16
STUDENT_EPOCHS = 60
STUDENT_BATCH = 128
STUDENT_LR = 1e-3
KD_ALPHA = 0.7
KD_TEMPERATURE = 4.0
LABEL_SMOOTHING = 0.05
MIXUP_ALPHA = 0.2


def set_seeds():
    random.seed(SEED)
    np.random.seed(SEED)
    torch.manual_seed(SEED)
    tf.random.set_seed(SEED)


def load_records():
    records = []
    with DATA_PATH.open("r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            row = json.loads(line)
            context = str(row.get("context", "") or "").strip()
            response = str(row.get("response", "") or "").strip()
            text = f"{context}\n{response}" if context else response
            records.append({
                "text": text,
                "label": int(row.get("label", 0)),
                "category": str(row.get("category", "") or ""),
            })
    return records


def split_data(records, val_ratio=0.12, test_ratio=0.08):
    rng = random.Random(SEED)
    scam = [r for r in records if r["label"] == 1]
    benign = [r for r in records if r["label"] == 0]
    rng.shuffle(scam)
    rng.shuffle(benign)

    def split_list(lst, val_r, test_r):
        n = len(lst)
        n_test = max(1, int(n * test_r))
        n_val = max(1, int(n * val_r))
        return lst[n_test + n_val:], lst[n_test:n_test + n_val], lst[:n_test]

    scam_train, scam_val, scam_test = split_list(scam, val_ratio, test_ratio)
    benign_train, benign_val, benign_test = split_list(benign, val_ratio, test_ratio)

    train = scam_train + benign_train
    val = scam_val + benign_val
    test = scam_test + benign_test
    rng.shuffle(train)
    rng.shuffle(val)
    rng.shuffle(test)
    return train, val, test


# ============================================================================
# PHASE 2: Feature Extraction
# ============================================================================

def extract_distill_features(records, tokenizer, model, device, desc="Extracting"):
    model.eval()
    base_model = model.distilbert if hasattr(model, "distilbert") else model.bert
    base_model.eval()

    all_features = []
    all_hard_labels = []
    all_soft_labels = []

    for i in tqdm.tqdm(range(0, len(records), TEACHER_BATCH), desc=desc, unit="batch"):
        batch = records[i:i + TEACHER_BATCH]
        texts = [b["text"] for b in batch]
        labels = [b["label"] for b in batch]

        encoded = tokenizer(
            texts, padding=True, truncation=True,
            max_length=TEACHER_MAX_LEN, return_tensors="pt",
        )
        encoded = {k: v.to(device) for k, v in encoded.items()}

        with torch.no_grad():
            base_out = base_model(
                input_ids=encoded["input_ids"],
                attention_mask=encoded["attention_mask"],
            )
            cls_hidden = base_out.last_hidden_state[:, 0, :]

            full_out = model(**encoded)
            teacher_logits = full_out.logits

        all_features.append(cls_hidden.cpu().numpy().astype(np.float32))
        all_hard_labels.extend(labels)
        all_soft_labels.append(teacher_logits.cpu().numpy().astype(np.float32))

    features = np.concatenate(all_features, axis=0)
    hard_labels = np.array(all_hard_labels, dtype=np.int64)
    soft_labels = np.concatenate(all_soft_labels, axis=0)
    return features, hard_labels, soft_labels


def build_distillation_dataset(train_records, val_records, test_records, model, tokenizer, device):
    print("\n" + "="*60)
    print("PHASE 2: EXTRACTING DISTILLATION FEATURES")
    print("="*60)

    DISTILL_DIR.mkdir(parents=True, exist_ok=True)

    for split_name, records in [("train", train_records), ("val", val_records), ("test", test_records)]:
        print(f"\nExtracting {split_name} features ({len(records)} samples)...")
        features, hard_labels, soft_labels = extract_distill_features(
            records, tokenizer, model, device, desc=f"{split_name}",
        )
        np.save(DISTILL_DIR / f"{split_name}_features.npy", features)
        np.save(DISTILL_DIR / f"{split_name}_labels.npy", hard_labels)
        np.save(DISTILL_DIR / f"{split_name}_soft_labels.npy", soft_labels)
        print(f"  → {split_name}: features={features.shape}, scam={int(hard_labels.sum())}, benign={int((hard_labels==0).sum())}")


# ============================================================================
# PHASE 3: Student Training
# ============================================================================

class KDLoss(tf.keras.losses.Loss):
    def __init__(self, alpha=KD_ALPHA, temperature=KD_TEMPERATURE, label_smoothing=LABEL_SMOOTHING, **kwargs):
        super().__init__(**kwargs)
        self.alpha = alpha
        self.temperature = temperature
        self.bce = tf.keras.losses.BinaryCrossentropy(label_smoothing=label_smoothing)

    def call(self, y_true, y_pred):
        hard_label = y_true[:, 0:1]
        teacher_prob = y_true[:, 1:2]
        hard_loss = self.bce(hard_label, y_pred)

        # Clamp to avoid log(0)
        teacher_prob_c = tf.clip_by_value(teacher_prob, 1e-7, 1.0 - 1e-7)
        y_pred_c = tf.clip_by_value(y_pred, 1e-7, 1.0 - 1e-7)

        teacher_soft = tf.sigmoid(tf.math.log(teacher_prob_c / (1 - teacher_prob_c)) / self.temperature)
        student_soft = tf.sigmoid(tf.math.log(y_pred_c / (1 - y_pred_c)) / self.temperature)
        kd_loss = tf.reduce_mean(tf.square(teacher_soft - student_soft))
        return (1 - self.alpha) * hard_loss + self.alpha * kd_loss * (self.temperature ** 2)

    def get_config(self):
        config = super().get_config()
        config.update({"alpha": self.alpha, "temperature": self.temperature})
        return config


def build_student_model():
    model = tf.keras.Sequential([
        tf.keras.layers.Input(shape=(768,), name="input"),
        tf.keras.layers.Dense(256, name="dense1"),
        tf.keras.layers.BatchNormalization(name="bn1"),
        tf.keras.layers.Activation("swish", name="swish1"),
        tf.keras.layers.Dropout(0.3, name="drop1"),
        tf.keras.layers.Dense(64, name="dense2"),
        tf.keras.layers.BatchNormalization(name="bn2"),
        tf.keras.layers.Activation("swish", name="swish2"),
        tf.keras.layers.Dropout(0.2, name="drop2"),
        tf.keras.layers.Dense(1, activation="sigmoid", name="output"),
    ], name="ScamIntentStudent")
    return model


def mixup_data(x, y, alpha=MIXUP_ALPHA):
    if alpha <= 0:
        return x, y
    lam = np.random.beta(alpha, alpha, size=(x.shape[0], 1)).astype(np.float32)
    indices = np.random.permutation(x.shape[0])
    x_mix = lam * x + (1 - lam) * x[indices]
    y_mix = lam * y + (1 - lam) * y[indices]
    return x_mix, y_mix


class MixupGenerator(tf.keras.utils.Sequence):
    def __init__(self, x, y, batch_size, mixup_alpha):
        self.x = x.copy()
        self.y = y.copy()
        self.batch_size = batch_size
        self.mixup_alpha = mixup_alpha

    def __len__(self):
        return math.ceil(len(self.x) / self.batch_size)

    def __getitem__(self, idx):
        start = idx * self.batch_size
        end = min(start + self.batch_size, len(self.x))
        batch_x = self.x[start:end].copy()
        batch_y = self.y[start:end].copy()
        batch_x, batch_y = mixup_data(batch_x, batch_y, self.mixup_alpha)
        return batch_x, batch_y

    def on_epoch_end(self):
        indices = np.random.permutation(len(self.x))
        self.x = self.x[indices]
        self.y = self.y[indices]


class ValAUCCallback(tf.keras.callbacks.Callback):
    """Custom callback to evaluate AUC on hard labels and handle early stopping + checkpointing."""

    def __init__(self, x_val, y_val_hard, save_path, patience=12, lr_patience=5, lr_factor=0.5, min_lr=1e-6):
        super().__init__()
        self.x_val = x_val
        self.y_val_hard = y_val_hard
        self.save_path = str(save_path)
        self.patience = patience
        self.lr_patience = lr_patience
        self.lr_factor = lr_factor
        self.min_lr = min_lr
        self.best_auc = -1.0
        self.wait = 0
        self.lr_wait = 0
        self.best_weights = None

    def on_epoch_end(self, epoch, logs=None):
        scores = self.model.predict(self.x_val, batch_size=STUDENT_BATCH, verbose=0).reshape(-1)
        preds = (scores >= 0.5).astype(np.int32)
        labels = self.y_val_hard.astype(np.int32)

        acc = sklearn.metrics.accuracy_score(labels, preds)
        auc = sklearn.metrics.roc_auc_score(labels, scores)
        f1 = sklearn.metrics.f1_score(labels, preds)

        logs = logs or {}
        logs["val_acc"] = acc
        logs["val_auc"] = auc
        logs["val_f1"] = f1

        print(f"  → Val Acc: {acc:.4f} | Val AUC: {auc:.4f} | Val F1: {f1:.4f}")

        if auc > self.best_auc:
            self.best_auc = auc
            self.wait = 0
            self.lr_wait = 0
            self.best_weights = self.model.get_weights()
            self.model.save(self.save_path)
            print(f"  → New best model saved (AUC={auc:.4f})")
        else:
            self.wait += 1
            self.lr_wait += 1

            # ReduceLROnPlateau equivalent
            if self.lr_wait >= self.lr_patience:
                old_lr = float(self.model.optimizer.learning_rate)
                new_lr = max(old_lr * self.lr_factor, self.min_lr)
                if new_lr < old_lr:
                    self.model.optimizer.learning_rate = new_lr
                    print(f"  → Reducing LR: {old_lr:.2e} → {new_lr:.2e}")
                self.lr_wait = 0

            # Early stopping
            if self.wait >= self.patience:
                print(f"  → Early stopping at epoch {epoch+1}")
                self.model.stop_training = True
                if self.best_weights is not None:
                    self.model.set_weights(self.best_weights)


def train_student():
    print("\n" + "="*60)
    print("PHASE 3: TRAINING TFLITE STUDENT (KNOWLEDGE DISTILLATION)")
    print("="*60)

    STUDENT_DIR.mkdir(parents=True, exist_ok=True)

    x_train = np.load(DISTILL_DIR / "train_features.npy").astype(np.float32)
    y_train_hard = np.load(DISTILL_DIR / "train_labels.npy").astype(np.float32)
    train_soft = np.load(DISTILL_DIR / "train_soft_labels.npy").astype(np.float32)

    x_val = np.load(DISTILL_DIR / "val_features.npy").astype(np.float32)
    y_val_hard = np.load(DISTILL_DIR / "val_labels.npy").astype(np.float32)
    val_soft = np.load(DISTILL_DIR / "val_soft_labels.npy").astype(np.float32)

    train_teacher_prob = tf.nn.softmax(train_soft, axis=-1).numpy()[:, 1:2]
    val_teacher_prob = tf.nn.softmax(val_soft, axis=-1).numpy()[:, 1:2]

    # 2-column targets for KD loss: [hard_label, teacher_P(scam)]
    y_train = np.concatenate([y_train_hard.reshape(-1, 1), train_teacher_prob], axis=1)
    y_val = np.concatenate([y_val_hard.reshape(-1, 1), val_teacher_prob], axis=1)

    print(f"Train: {x_train.shape}, Val: {x_val.shape}")
    print(f"Scam ratio — train: {y_train_hard.mean():.3f}, val: {y_val_hard.mean():.3f}")

    model = build_student_model()
    model.summary()

    kd_loss_fn = KDLoss(alpha=KD_ALPHA, temperature=KD_TEMPERATURE, label_smoothing=LABEL_SMOOTHING)

    # Compile with ONLY the KD loss — no inline metrics (they can't handle 2-col targets)
    model.compile(
        optimizer=tf.keras.optimizers.Adam(learning_rate=STUDENT_LR),
        loss=kd_loss_fn,
    )

    # Custom callback handles val evaluation, checkpointing, early stopping, LR reduction
    val_callback = ValAUCCallback(
        x_val=x_val,
        y_val_hard=y_val_hard,
        save_path=STUDENT_DIR / "best.keras",
        patience=12,
        lr_patience=5,
        lr_factor=0.5,
        min_lr=1e-6,
    )

    train_gen = MixupGenerator(x_train, y_train, STUDENT_BATCH, MIXUP_ALPHA)

    history = model.fit(
        train_gen,
        validation_data=(x_val, y_val),
        epochs=STUDENT_EPOCHS,
        callbacks=[val_callback],
        verbose=1,
    )

    # Restore best weights (already done by callback, but load from file for safety)
    try:
        best_model = tf.keras.models.load_model(
            str(STUDENT_DIR / "best.keras"),
            custom_objects={"KDLoss": KDLoss},
        )
    except Exception:
        best_model = model  # Fall back to in-memory best weights

    val_scores = best_model.predict(x_val, batch_size=STUDENT_BATCH, verbose=0).reshape(-1)
    val_preds = (val_scores >= 0.5).astype(np.int32)
    val_labels = y_val_hard.astype(np.int32)

    acc = sklearn.metrics.accuracy_score(val_labels, val_preds)
    f1 = sklearn.metrics.f1_score(val_labels, val_preds)
    auc = sklearn.metrics.roc_auc_score(val_labels, val_scores)
    precision = sklearn.metrics.precision_score(val_labels, val_preds)
    recall = sklearn.metrics.recall_score(val_labels, val_preds)

    print(f"\nStudent Validation Results:")
    print(f"  Accuracy:  {acc:.4f}")
    print(f"  F1:        {f1:.4f}")
    print(f"  AUC:       {auc:.4f}")
    print(f"  Precision: {precision:.4f}")
    print(f"  Recall:    {recall:.4f}")
    print(sklearn.metrics.classification_report(val_labels, val_preds, digits=4, zero_division=0))

    return best_model, x_train


# ============================================================================
# PHASE 4: TFLite Export
# ============================================================================

def export_tflite(model, x_train):
    print("\n" + "="*60)
    print("PHASE 4: EXPORTING TO INT8 TFLITE")
    print("="*60)

    n_rep = min(500, x_train.shape[0])
    rng = np.random.default_rng(SEED)
    rep_indices = rng.choice(x_train.shape[0], size=n_rep, replace=False)
    rep_data = x_train[rep_indices]

    def representative_dataset():
        for i in range(rep_data.shape[0]):
            yield [rep_data[i:i+1].astype(np.float32)]

    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    converter.representative_dataset = representative_dataset
    converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS_INT8]
    converter.inference_input_type = tf.int8
    converter.inference_output_type = tf.float32

    tflite_model = converter.convert()

    TFLITE_PATH.parent.mkdir(parents=True, exist_ok=True)
    with TFLITE_PATH.open("wb") as f:
        f.write(tflite_model)

    size_kb = TFLITE_PATH.stat().st_size / 1024.0
    print(f"TFLite model size: {size_kb:.1f} KB ({TFLITE_PATH})")

    interpreter = tf.lite.Interpreter(model_path=str(TFLITE_PATH))
    interpreter.allocate_tensors()
    input_detail = interpreter.get_input_details()[0]
    output_detail = interpreter.get_output_details()[0]

    print(f"Input dtype: {input_detail['dtype']}, shape: {input_detail['shape']}")
    print(f"Output dtype: {output_detail['dtype']}, shape: {output_detail['shape']}")
    print(f"Quantization params: scale={input_detail['quantization']}")

    return interpreter, input_detail, output_detail


# ============================================================================
# PHASE 5: Validation
# ============================================================================

def validate_tflite(interpreter, input_detail, output_detail):
    print("\n" + "="*60)
    print("PHASE 5: VALIDATING TFLITE ACCURACY")
    print("="*60)

    x_test = np.load(DISTILL_DIR / "test_features.npy").astype(np.float32)
    y_test = np.load(DISTILL_DIR / "test_labels.npy").astype(np.int32)

    print(f"Test set: {x_test.shape[0]} samples (scam={int(y_test.sum())}, benign={int((y_test==0).sum())})")

    predictions = []
    for i in tqdm.tqdm(range(x_test.shape[0]), desc="TFLite inference"):
        sample = x_test[i:i+1].astype(np.float32)

        if input_detail["dtype"] == np.int8:
            scale, zero_point = input_detail["quantization"]
            if scale > 0:
                q = np.round(sample / scale + zero_point)
                q = np.clip(q, -128, 127).astype(np.int8)
            else:
                q = sample.astype(np.int8)
            interpreter.set_tensor(input_detail["index"], q)
        else:
            interpreter.set_tensor(input_detail["index"], sample)

        interpreter.invoke()
        output = interpreter.get_tensor(output_detail["index"])
        predictions.append(float(output.reshape(-1)[0]))

    scores = np.array(predictions)
    preds = (scores >= 0.5).astype(np.int32)

    acc = sklearn.metrics.accuracy_score(y_test, preds)
    f1 = sklearn.metrics.f1_score(y_test, preds)
    auc = sklearn.metrics.roc_auc_score(y_test, scores)
    precision = sklearn.metrics.precision_score(y_test, preds)
    recall = sklearn.metrics.recall_score(y_test, preds)

    print(f"\n{'='*50}")
    print(f"TFLITE TEST RESULTS")
    print(f"{'='*50}")
    print(f"  Accuracy:  {acc:.4f}")
    print(f"  F1:        {f1:.4f}")
    print(f"  AUC:       {auc:.4f}")
    print(f"  Precision: {precision:.4f}")
    print(f"  Recall:    {recall:.4f}")
    print(sklearn.metrics.classification_report(y_test, preds, digits=4, zero_division=0))

    metrics = {
        "accuracy": acc, "f1": f1, "auc": auc,
        "precision": precision, "recall": recall,
        "tflite_size_kb": TFLITE_PATH.stat().st_size / 1024.0,
        "test_samples": len(y_test),
    }
    joblib.dump(metrics, STUDENT_DIR / "test_metrics.joblib")

    # Copy to Android assets
    if ANDROID_ASSETS.exists():
        dest = ANDROID_ASSETS / "scam_intent_classifier.tflite"
        shutil.copy2(TFLITE_PATH, dest)
        print(f"\n✅ Copied TFLite to Android assets: {dest}")
    else:
        print(f"\n⚠️  Android assets dir not found: {ANDROID_ASSETS}")

    return metrics


# ============================================================================
# MAIN
# ============================================================================

def main():
    set_seeds()
    t0 = time.time()

    device = torch.device("mps" if torch.backends.mps.is_available() else "cpu")
    print(f"Device: {device}")

    # Load data with SAME splits as original training
    print("Loading dataset...")
    records = load_records()
    train_records, val_records, test_records = split_data(records)
    print(f"Train: {len(train_records):,} | Val: {len(val_records):,} | Test: {len(test_records):,}")

    # Check if distillation features already exist
    features_exist = (DISTILL_DIR / "train_features.npy").exists()

    if not features_exist:
        # Load saved teacher
        print(f"\nLoading saved teacher from {TEACHER_DIR}...")
        tokenizer = transformers.AutoTokenizer.from_pretrained(str(TEACHER_DIR))
        model = transformers.AutoModelForSequenceClassification.from_pretrained(str(TEACHER_DIR))
        model.to(device)
        model.eval()

        # Phase 2: Extract features
        build_distillation_dataset(train_records, val_records, test_records, model, tokenizer, device)

        # Free teacher memory
        del model
        del tokenizer
        if torch.backends.mps.is_available():
            torch.mps.empty_cache()
        import gc
        gc.collect()
    else:
        print(f"\n✅ Distillation features already exist at {DISTILL_DIR}")

    # Phase 3: Train student
    student_model, x_train = train_student()

    # Phase 4: Export TFLite
    interpreter, input_detail, output_detail = export_tflite(student_model, x_train)

    # Phase 5: Validate
    metrics = validate_tflite(interpreter, input_detail, output_detail)

    elapsed = time.time() - t0
    print(f"\n{'='*60}")
    print(f"PIPELINE COMPLETE")
    print(f"{'='*60}")
    print(f"Total time: {elapsed/60:.1f} minutes")
    print(f"TFLite size: {metrics['tflite_size_kb']:.1f} KB")
    print(f"Test Accuracy: {metrics['accuracy']:.4f}")
    print(f"Test F1: {metrics['f1']:.4f}")
    print(f"Test AUC: {metrics['auc']:.4f}")

    if metrics['accuracy'] >= 0.95:
        print("✅ TARGET MET: Accuracy ≥ 0.95")
    else:
        print(f"⚠️  Accuracy {metrics['accuracy']:.4f} < 0.95 target")

    if metrics['tflite_size_kb'] < 300:
        print("✅ SIZE OK: TFLite < 300 KB")


if __name__ == "__main__":
    main()
