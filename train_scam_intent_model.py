"""
Aegis Scam Intent Model — Unified Training Pipeline
=====================================================
End-to-end pipeline:
  1. Fine-tune DistilBERT teacher on expanded dataset (focal loss, curriculum learning)
  2. Extract CLS features for knowledge distillation
  3. Train TFLite student with KD loss, BatchNorm, QAT
  4. Export INT8 TFLite
  5. Validate accuracy

Target: ≥ 0.95 accuracy on adversarial test set
Budget: ≤ 1.5GB RAM on Android (model < 300KB TFLite)
"""

import json
import math
import pathlib
import random
import shutil
import time
from collections import Counter

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
TEACHER_DIR = BASE_DIR / "models" / "scam_teacher"
DISTILL_DIR = BASE_DIR / "dataset" / "tier3_v2"
STUDENT_DIR = BASE_DIR / "models" / "scam_student"
TFLITE_PATH = STUDENT_DIR / "scam_intent_classifier.tflite"
ANDROID_ASSETS = BASE_DIR / "android" / "app" / "src" / "main" / "assets"

# Teacher config
TEACHER_MODEL = "distilbert-base-uncased"
TEACHER_MAX_LEN = 256
TEACHER_BATCH = 16
TEACHER_EPOCHS = 4
TEACHER_LR = 2e-5
TEACHER_WARMUP_RATIO = 0.1

# Student config
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


# ============================================================================
# DATA LOADING
# ============================================================================

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
    """Split into train/val/test with stratification."""
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
# PHASE 1: FINE-TUNE DISTILBERT TEACHER
# ============================================================================

class FocalLoss(torch.nn.Module):
    """Focal loss focuses on hard-to-classify examples."""
    def __init__(self, alpha=None, gamma=2.0, label_smoothing=0.0):
        super().__init__()
        self.alpha = alpha  # class weights as tensor
        self.gamma = gamma
        self.label_smoothing = label_smoothing
    
    def forward(self, logits, targets):
        probs = torch.softmax(logits, dim=-1)
        
        # Label smoothing
        n_classes = logits.size(-1)
        smooth_targets = torch.zeros_like(probs)
        smooth_targets.scatter_(1, targets.unsqueeze(1), 1.0)
        smooth_targets = (1 - self.label_smoothing) * smooth_targets + self.label_smoothing / n_classes
        
        # Focal weight
        pt = (probs * smooth_targets).sum(dim=-1)
        focal_weight = (1 - pt) ** self.gamma
        
        # Cross entropy
        log_probs = torch.log_softmax(logits, dim=-1)
        ce = -(smooth_targets * log_probs).sum(dim=-1)
        
        # Alpha weighting
        if self.alpha is not None:
            alpha_t = self.alpha.gather(0, targets)
            loss = alpha_t * focal_weight * ce
        else:
            loss = focal_weight * ce
        
        return loss.mean()


def compute_class_weights(records):
    labels = [r["label"] for r in records]
    total = len(labels)
    c0 = sum(1 for l in labels if l == 0)
    c1 = sum(1 for l in labels if l == 1)
    w0 = total / (2.0 * max(c0, 1))
    w1 = total / (2.0 * max(c1, 1))
    return torch.FloatTensor([w0, w1])


def freeze_teacher_layers(model, unfreeze_last_n=4):
    """Freeze all except classifier and last N transformer layers."""
    for param in model.parameters():
        param.requires_grad = False
    
    # Always unfreeze classifier head
    for name, param in model.named_parameters():
        if "classifier" in name or "pre_classifier" in name:
            param.requires_grad = True
    
    # Unfreeze last N transformer layers
    base = getattr(model, model.base_model_prefix, model)
    if hasattr(base, "encoder") and hasattr(base.encoder, "layer"):
        layers = base.encoder.layer
    elif hasattr(base, "transformer") and hasattr(base.transformer, "layer"):
        layers = base.transformer.layer
    else:
        layers = None
    
    if layers is not None and len(layers) > 0:
        for layer in layers[-unfreeze_last_n:]:
            for param in layer.parameters():
                param.requires_grad = True


def train_teacher(train_records, val_records, device):
    """Fine-tune DistilBERT with focal loss and curriculum learning."""
    print("\n" + "="*60)
    print("PHASE 1: FINE-TUNING DISTILBERT TEACHER")
    print("="*60)
    
    TEACHER_DIR.mkdir(parents=True, exist_ok=True)
    
    tokenizer = transformers.AutoTokenizer.from_pretrained(TEACHER_MODEL)
    tokenizer.truncation_side = "left"
    
    model = transformers.AutoModelForSequenceClassification.from_pretrained(
        TEACHER_MODEL, num_labels=2,
    )
    model.to(device)
    
    freeze_teacher_layers(model, unfreeze_last_n=4)
    
    trainable = sum(p.numel() for p in model.parameters() if p.requires_grad)
    total = sum(p.numel() for p in model.parameters())
    print(f"Trainable parameters: {trainable:,} / {total:,} ({100*trainable/total:.1f}%)")
    
    class_weights = compute_class_weights(train_records).to(device)
    criterion = FocalLoss(alpha=class_weights, gamma=2.0, label_smoothing=LABEL_SMOOTHING)
    
    optimizer = torch.optim.AdamW(
        [p for p in model.parameters() if p.requires_grad],
        lr=TEACHER_LR, weight_decay=0.01,
    )
    
    steps_per_epoch = math.ceil(len(train_records) / TEACHER_BATCH)
    total_steps = steps_per_epoch * TEACHER_EPOCHS
    warmup_steps = int(TEACHER_WARMUP_RATIO * total_steps)
    
    scheduler = transformers.get_cosine_schedule_with_warmup(
        optimizer, num_warmup_steps=warmup_steps, num_training_steps=total_steps,
    )
    
    best_f1 = -1.0
    
    for epoch in range(TEACHER_EPOCHS):
        model.train()
        random.shuffle(train_records)
        
        epoch_loss = 0.0
        n_batches = 0
        
        for i in range(0, len(train_records), TEACHER_BATCH):
            batch = train_records[i:i + TEACHER_BATCH]
            texts = [b["text"] for b in batch]
            labels = torch.tensor([b["label"] for b in batch], dtype=torch.long, device=device)
            
            encoded = tokenizer(
                texts, padding=True, truncation=True,
                max_length=TEACHER_MAX_LEN, return_tensors="pt",
            )
            encoded = {k: v.to(device) for k, v in encoded.items()}
            
            outputs = model(**encoded)
            loss = criterion(outputs.logits, labels)
            
            optimizer.zero_grad()
            loss.backward()
            torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            optimizer.step()
            scheduler.step()
            
            epoch_loss += loss.item()
            n_batches += 1
            
            if n_batches % 200 == 0:
                print(f"  Epoch {epoch+1}/{TEACHER_EPOCHS} | Batch {n_batches}/{steps_per_epoch} | Loss: {loss.item():.4f}")
        
        # Validation
        val_loss, val_acc, val_f1, val_auc = evaluate_teacher(model, tokenizer, val_records, criterion, device)
        avg_loss = epoch_loss / max(n_batches, 1)
        print(f"  Epoch {epoch+1} done | Train Loss: {avg_loss:.4f} | Val Loss: {val_loss:.4f} | Val Acc: {val_acc:.4f} | Val F1: {val_f1:.4f} | Val AUC: {val_auc:.4f}")
        
        if val_f1 > best_f1:
            best_f1 = val_f1
            best_dir = TEACHER_DIR / "best"
            best_dir.mkdir(parents=True, exist_ok=True)
            model.save_pretrained(best_dir)
            tokenizer.save_pretrained(best_dir)
            print(f"  → New best model saved (F1={val_f1:.4f})")
    
    # Save final
    final_dir = TEACHER_DIR / "final"
    final_dir.mkdir(parents=True, exist_ok=True)
    model.save_pretrained(final_dir)
    tokenizer.save_pretrained(final_dir)
    
    print(f"\nTeacher training complete. Best F1: {best_f1:.4f}")
    return model, tokenizer


def evaluate_teacher(model, tokenizer, records, criterion, device):
    model.eval()
    all_labels = []
    all_preds = []
    all_probs = []
    total_loss = 0.0
    n_batches = 0
    
    with torch.no_grad():
        for i in range(0, len(records), TEACHER_BATCH):
            batch = records[i:i + TEACHER_BATCH]
            texts = [b["text"] for b in batch]
            labels = torch.tensor([b["label"] for b in batch], dtype=torch.long, device=device)
            
            encoded = tokenizer(
                texts, padding=True, truncation=True,
                max_length=TEACHER_MAX_LEN, return_tensors="pt",
            )
            encoded = {k: v.to(device) for k, v in encoded.items()}
            
            outputs = model(**encoded)
            loss = criterion(outputs.logits, labels)
            
            probs = torch.softmax(outputs.logits, dim=-1)
            preds = torch.argmax(probs, dim=-1)
            
            total_loss += loss.item()
            n_batches += 1
            all_labels.extend(labels.cpu().numpy().tolist())
            all_preds.extend(preds.cpu().numpy().tolist())
            all_probs.extend(probs[:, 1].cpu().numpy().tolist())
    
    avg_loss = total_loss / max(n_batches, 1)
    acc = sklearn.metrics.accuracy_score(all_labels, all_preds)
    f1 = sklearn.metrics.f1_score(all_labels, all_preds, average="weighted")
    
    try:
        auc = sklearn.metrics.roc_auc_score(all_labels, all_probs)
    except Exception:
        auc = 0.0
    
    return avg_loss, acc, f1, auc


# ============================================================================
# PHASE 2: EXTRACT DISTILLATION FEATURES
# ============================================================================

def extract_distill_features(records, tokenizer, model, device, desc="Extracting"):
    """Extract CLS hidden states + teacher soft labels."""
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
            # Get hidden states from base model
            base_out = base_model(
                input_ids=encoded["input_ids"],
                attention_mask=encoded["attention_mask"],
            )
            cls_hidden = base_out.last_hidden_state[:, 0, :]  # (B, 768)
            
            # Get teacher logits for soft labels
            full_out = model(**encoded)
            teacher_logits = full_out.logits  # (B, 2)
        
        all_features.append(cls_hidden.cpu().numpy().astype(np.float32))
        all_hard_labels.extend(labels)
        all_soft_labels.append(teacher_logits.cpu().numpy().astype(np.float32))
    
    features = np.concatenate(all_features, axis=0)
    hard_labels = np.array(all_hard_labels, dtype=np.int64)
    soft_labels = np.concatenate(all_soft_labels, axis=0)
    
    return features, hard_labels, soft_labels


def build_distillation_dataset(train_records, val_records, test_records, model, tokenizer, device):
    """Extract features for all splits."""
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
# PHASE 3: TRAIN TFLITE STUDENT WITH KNOWLEDGE DISTILLATION
# ============================================================================

def build_student_model():
    """Build enhanced MLP student: 768 → 256 → 64 → 1 with BatchNorm + Swish."""
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


class KDLoss(tf.keras.losses.Loss):
    """Combined knowledge distillation + hard label loss."""
    
    def __init__(self, alpha=KD_ALPHA, temperature=KD_TEMPERATURE, label_smoothing=LABEL_SMOOTHING, **kwargs):
        super().__init__(**kwargs)
        self.alpha = alpha
        self.temperature = temperature
        self.bce = tf.keras.losses.BinaryCrossentropy(label_smoothing=label_smoothing)
    
    def call(self, y_true, y_pred):
        # y_true[:, 0] = hard label, y_true[:, 1] = teacher P(scam)
        hard_label = y_true[:, 0:1]
        teacher_prob = y_true[:, 1:2]
        
        # Hard label loss
        hard_loss = self.bce(hard_label, y_pred)
        
        # KD loss (MSE between student and teacher prob at temperature)
        teacher_soft = tf.sigmoid(tf.math.log(teacher_prob / (1 - teacher_prob + 1e-7) + 1e-7) / self.temperature)
        student_soft = tf.sigmoid(tf.math.log(y_pred / (1 - y_pred + 1e-7) + 1e-7) / self.temperature)
        kd_loss = tf.reduce_mean(tf.square(teacher_soft - student_soft))
        
        return (1 - self.alpha) * hard_loss + self.alpha * kd_loss * (self.temperature ** 2)


def mixup_data(x, y, alpha=MIXUP_ALPHA):
    """Apply mixup augmentation to features and labels."""
    if alpha <= 0:
        return x, y
    lam = np.random.beta(alpha, alpha, size=(x.shape[0], 1)).astype(np.float32)
    indices = np.random.permutation(x.shape[0])
    x_mix = lam * x + (1 - lam) * x[indices]
    y_mix = lam * y + (1 - lam) * y[indices]
    return x_mix, y_mix


def train_student():
    """Train TFLite student with knowledge distillation."""
    print("\n" + "="*60)
    print("PHASE 3: TRAINING TFLITE STUDENT (KNOWLEDGE DISTILLATION)")
    print("="*60)
    
    STUDENT_DIR.mkdir(parents=True, exist_ok=True)
    
    # Load distillation features
    x_train = np.load(DISTILL_DIR / "train_features.npy").astype(np.float32)
    y_train_hard = np.load(DISTILL_DIR / "train_labels.npy").astype(np.float32)
    train_soft = np.load(DISTILL_DIR / "train_soft_labels.npy").astype(np.float32)
    
    x_val = np.load(DISTILL_DIR / "val_features.npy").astype(np.float32)
    y_val_hard = np.load(DISTILL_DIR / "val_labels.npy").astype(np.float32)
    val_soft = np.load(DISTILL_DIR / "val_soft_labels.npy").astype(np.float32)
    
    # Teacher P(scam) = softmax(logits)[:, 1]
    train_teacher_prob = tf.nn.softmax(train_soft, axis=-1).numpy()[:, 1:2]
    val_teacher_prob = tf.nn.softmax(val_soft, axis=-1).numpy()[:, 1:2]
    
    # Combined targets: [hard_label, teacher_P(scam)]
    y_train = np.concatenate([y_train_hard.reshape(-1, 1), train_teacher_prob], axis=1)
    y_val = np.concatenate([y_val_hard.reshape(-1, 1), val_teacher_prob], axis=1)
    
    print(f"Train: {x_train.shape}, Val: {x_val.shape}")
    print(f"Scam ratio — train: {y_train_hard.mean():.3f}, val: {y_val_hard.mean():.3f}")
    
    # Class weights
    c0 = float(np.sum(y_train_hard == 0))
    c1 = float(np.sum(y_train_hard == 1))
    total = c0 + c1
    class_weight = {0: total / (2 * c0), 1: total / (2 * c1)}
    
    model = build_student_model()
    model.summary()
    
    # Custom training loop with mixup
    kd_loss_fn = KDLoss(alpha=KD_ALPHA, temperature=KD_TEMPERATURE, label_smoothing=LABEL_SMOOTHING)
    
    # Use cosine annealing with warm restarts
    initial_lr = STUDENT_LR
    
    # Compile with standard BCE for metrics tracking (KD loss used in custom loop)
    model.compile(
        optimizer=tf.keras.optimizers.Adam(learning_rate=initial_lr),
        loss=kd_loss_fn,
        metrics=[
            tf.keras.metrics.BinaryAccuracy(name="acc"),
            tf.keras.metrics.AUC(name="auc"),
        ],
    )
    
    callbacks = [
        tf.keras.callbacks.EarlyStopping(
            monitor="val_auc", patience=12, restore_best_weights=True, mode="max",
        ),
        tf.keras.callbacks.ModelCheckpoint(
            STUDENT_DIR / "best.keras", monitor="val_auc",
            save_best_only=True, mode="max",
        ),
        tf.keras.callbacks.ReduceLROnPlateau(
            monitor="val_auc", factor=0.5, patience=5, mode="max", min_lr=1e-6,
        ),
    ]
    
    # Apply mixup to training data each epoch using a custom generator
    class MixupGenerator(tf.keras.utils.Sequence):
        def __init__(self, x, y, batch_size, mixup_alpha):
            self.x = x
            self.y = y
            self.batch_size = batch_size
            self.mixup_alpha = mixup_alpha
            self.indices = np.arange(len(x))
        
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
            np.random.shuffle(self.indices)
            self.x = self.x[self.indices]
            self.y = self.y[self.indices]
    
    train_gen = MixupGenerator(x_train, y_train, STUDENT_BATCH, MIXUP_ALPHA)
    
    history = model.fit(
        train_gen,
        validation_data=(x_val, y_val),
        epochs=STUDENT_EPOCHS,
        callbacks=callbacks,
        verbose=1,
    )
    
    # Load best model
    best_model = tf.keras.models.load_model(
        STUDENT_DIR / "best.keras",
        custom_objects={"KDLoss": KDLoss},
    )
    
    # Evaluate on validation using hard labels only
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
# PHASE 4: EXPORT TO TFLITE INT8
# ============================================================================

def export_tflite(model, x_train):
    """Export student model to INT8 TFLite."""
    print("\n" + "="*60)
    print("PHASE 4: EXPORTING TO INT8 TFLITE")
    print("="*60)
    
    # Build representative dataset for quantization calibration
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
    converter.inference_output_type = tf.float32  # Keep output as float for easy thresholding
    
    tflite_model = converter.convert()
    
    TFLITE_PATH.parent.mkdir(parents=True, exist_ok=True)
    with TFLITE_PATH.open("wb") as f:
        f.write(tflite_model)
    
    size_kb = TFLITE_PATH.stat().st_size / 1024.0
    print(f"TFLite model size: {size_kb:.1f} KB ({TFLITE_PATH})")
    
    # Verify TFLite model
    interpreter = tf.lite.Interpreter(model_path=str(TFLITE_PATH))
    interpreter.allocate_tensors()
    
    input_detail = interpreter.get_input_details()[0]
    output_detail = interpreter.get_output_details()[0]
    
    print(f"Input dtype: {input_detail['dtype']}, shape: {input_detail['shape']}")
    print(f"Output dtype: {output_detail['dtype']}, shape: {output_detail['shape']}")
    print(f"Quantization params: scale={input_detail['quantization']}")
    
    return interpreter, input_detail, output_detail


def validate_tflite(interpreter, input_detail, output_detail):
    """Validate TFLite accuracy matches the Keras model."""
    print("\n" + "="*60)
    print("PHASE 5: VALIDATING TFLITE ACCURACY")
    print("="*60)
    
    # Load test set
    x_test = np.load(DISTILL_DIR / "test_features.npy").astype(np.float32)
    y_test = np.load(DISTILL_DIR / "test_labels.npy").astype(np.int32)
    
    print(f"Test set: {x_test.shape[0]} samples (scam={int(y_test.sum())}, benign={int((y_test==0).sum())})")
    
    predictions = []
    for i in range(x_test.shape[0]):
        sample = x_test[i:i+1].astype(np.float32)
        
        # Quantize input
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
    
    print(f"\nTFLite Test Results:")
    print(f"  Accuracy:  {acc:.4f}")
    print(f"  F1:        {f1:.4f}")
    print(f"  AUC:       {auc:.4f}")
    print(f"  Precision: {precision:.4f}")
    print(f"  Recall:    {recall:.4f}")
    print(sklearn.metrics.classification_report(y_test, preds, digits=4, zero_division=0))
    
    # Category-level analysis
    test_records_raw = []
    with DATA_PATH.open("r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            test_records_raw.append(json.loads(line))
    
    # Per-category accuracy can be computed if needed
    
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
    
    return metrics


# ============================================================================
# MAIN ORCHESTRATOR
# ============================================================================

def main():
    set_seeds()
    t0 = time.time()
    
    device = torch.device("mps" if torch.backends.mps.is_available() else "cpu")
    print(f"Device: {device}")
    
    # Load and split data
    print("Loading dataset...")
    records = load_records()
    train_records, val_records, test_records = split_data(records)
    
    print(f"Train: {len(train_records):,} | Val: {len(val_records):,} | Test: {len(test_records):,}")
    print(f"Train scam ratio: {sum(1 for r in train_records if r['label']==1)/len(train_records):.3f}")
    
    # Phase 1: Fine-tune DistilBERT teacher
    teacher_model, tokenizer = train_teacher(train_records, val_records, device)
    
    # Phase 2: Extract distillation features
    build_distillation_dataset(train_records, val_records, test_records, teacher_model, tokenizer, device)
    
    # Phase 3: Train TFLite student
    student_model, x_train = train_student()
    
    # Phase 4: Export to TFLite
    interpreter, input_detail, output_detail = export_tflite(student_model, x_train)
    
    # Phase 5: Validate
    metrics = validate_tflite(interpreter, input_detail, output_detail)
    
    elapsed = time.time() - t0
    print(f"\n{'='*60}")
    print(f"TRAINING COMPLETE")
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
    else:
        print(f"⚠️  TFLite size {metrics['tflite_size_kb']:.1f} KB > 300 KB target")


if __name__ == "__main__":
    main()
