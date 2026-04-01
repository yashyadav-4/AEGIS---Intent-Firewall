"""
# Tier 2 — DistilBERT fine-tuned scam classifier
# (distilbert-base-uncased, public, no auth required)
# Runtime: ~2-4 hours on M1 CPU
# Output: models/dialogrpt/final/
"""

import json
import pathlib
import random

import numpy as np
import sklearn
import torch
import transformers


BASE_DIR = pathlib.Path.home() / "Desktop/Hackaccino"
DATA_PATH = BASE_DIR / "dataset/scam_dialogues.jsonl"
MODEL_NAME = "distilbert-base-uncased"
OUTPUT_DIR = BASE_DIR / "models/dialogrpt"
MAX_LENGTH = 256
BATCH_SIZE = 8
EPOCHS = 3
LR = 2e-5
SEED = 42
VAL_SPLIT = 0.15


def load_records(tokenizer):
    records = []
    special_tokens = tokenizer.num_special_tokens_to_add(pair=False)
    keep_tokens = max(1, MAX_LENGTH - special_tokens)

    with DATA_PATH.open("r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            row = json.loads(line)

            context = str(row.get("context", "") or "").strip()
            response = str(row.get("response", "") or "").strip()
            label = int(row.get("label", 0))
            category = str(row.get("category", "") or "")

            text = f"{context}\n{response}" if context else response
            token_ids = tokenizer.encode(text, add_special_tokens=False)
            if len(token_ids) > keep_tokens:
                token_ids = token_ids[-keep_tokens:]
                text = tokenizer.decode(
                    token_ids,
                    skip_special_tokens=True,
                    clean_up_tokenization_spaces=False,
                )

            records.append(
                {
                    "text": text,
                    "label": label,
                    "category": category,
                }
            )

    return records


def split_train_val(records):
    indices = list(range(len(records)))
    rng = random.Random(SEED)
    rng.shuffle(indices)

    val_size = int(len(records) * VAL_SPLIT)
    if val_size <= 0 and len(records) > 1:
        val_size = 1

    val_idx = set(indices[:val_size])
    train_records = [records[i] for i in range(len(records)) if i not in val_idx]
    val_records = [records[i] for i in range(len(records)) if i in val_idx]
    return train_records, val_records


def compute_class_weights(train_records):
    labels = [int(r["label"]) for r in train_records]
    total = len(labels)
    count_benign = sum(1 for x in labels if x == 0)
    count_scam = sum(1 for x in labels if x == 1)

    if count_benign == 0 or count_scam == 0:
        raise ValueError("Training split must contain both classes to compute class weights")

    weight_0 = total / (2.0 * count_benign)
    weight_1 = total / (2.0 * count_scam)
    return torch.FloatTensor([weight_0, weight_1])


def freeze_for_fast_finetune(model):
    for param in model.parameters():
        param.requires_grad = False

    for name, param in model.named_parameters():
        if (
            "classifier" in name
            or "score" in name
            or "classification_head" in name
        ):
            param.requires_grad = True

    base_model = getattr(model, model.base_model_prefix, model)
    layers = None

    if hasattr(base_model, "encoder") and hasattr(base_model.encoder, "layer"):
        layers = base_model.encoder.layer
    elif hasattr(base_model, "h"):
        layers = base_model.h
    elif hasattr(base_model, "decoder") and hasattr(base_model.decoder, "layers"):
        layers = base_model.decoder.layers
    elif hasattr(base_model, "layers"):
        layers = base_model.layers

    if layers is not None and len(layers) > 0:
        for layer in layers[-2:]:
            for param in layer.parameters():
                param.requires_grad = True


def batch_iter(records, batch_size):
    total = len(records)
    for start in range(0, total, batch_size):
        yield records[start : start + batch_size]


def evaluate(model, tokenizer, val_records, criterion, device):
    model.eval()
    losses = []
    y_true = []
    y_pred = []

    with torch.no_grad():
        for batch in batch_iter(val_records, BATCH_SIZE):
            texts = [item["text"] for item in batch]
            labels = torch.tensor([int(item["label"]) for item in batch], dtype=torch.long, device=device)

            encoded = tokenizer(
                texts,
                padding=True,
                truncation=True,
                max_length=MAX_LENGTH,
                return_tensors="pt",
            )
            encoded = {k: v.to(device) for k, v in encoded.items()}

            outputs = model(**encoded)
            logits = outputs.logits
            loss = criterion(logits, labels)

            losses.append(float(loss.item()))
            preds = torch.argmax(logits, dim=-1)
            y_true.extend(labels.detach().cpu().numpy().tolist())
            y_pred.extend(preds.detach().cpu().numpy().tolist())

    val_loss = float(np.mean(losses)) if losses else 0.0
    acc = float(sklearn.metrics.accuracy_score(y_true, y_pred)) if y_true else 0.0
    f1 = float(sklearn.metrics.f1_score(y_true, y_pred, average="weighted")) if y_true else 0.0

    return val_loss, acc, f1


def main():
    random.seed(SEED)
    np.random.seed(SEED)
    torch.manual_seed(SEED)

    device = torch.device("cpu")

    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

    tokenizer = transformers.AutoTokenizer.from_pretrained(MODEL_NAME)
    tokenizer.truncation_side = "left"
    if tokenizer.pad_token is None:
        tokenizer.pad_token = tokenizer.eos_token

    records = load_records(tokenizer)
    train_records, val_records = split_train_val(records)

    class_weights = compute_class_weights(train_records).to(device)

    model = transformers.AutoModelForSequenceClassification.from_pretrained(
        MODEL_NAME,
        num_labels=2,
    )
    if model.config.pad_token_id is None and tokenizer.pad_token_id is not None:
        model.config.pad_token_id = tokenizer.pad_token_id
    model.to(device)

    freeze_for_fast_finetune(model)

    trainable_params = [p for p in model.parameters() if p.requires_grad]
    optimizer = torch.optim.AdamW(trainable_params, lr=LR, weight_decay=0.01)

    steps_per_epoch = (len(train_records) + BATCH_SIZE - 1) // BATCH_SIZE
    total_steps = max(1, steps_per_epoch * EPOCHS)
    warmup_steps = int(0.1 * total_steps)

    scheduler = transformers.get_linear_schedule_with_warmup(
        optimizer,
        num_warmup_steps=warmup_steps,
        num_training_steps=total_steps,
    )

    criterion = torch.nn.CrossEntropyLoss(weight=class_weights)

    best_f1 = -1.0
    best_epoch = -1

    for epoch in range(EPOCHS):
        model.train()
        random.shuffle(train_records)
        total_batches = (len(train_records) + BATCH_SIZE - 1) // BATCH_SIZE

        for batch_idx, batch in enumerate(batch_iter(train_records, BATCH_SIZE), start=1):
            texts = [item["text"] for item in batch]
            labels = torch.tensor([int(item["label"]) for item in batch], dtype=torch.long, device=device)

            encoded = tokenizer(
                texts,
                padding=True,
                truncation=True,
                max_length=MAX_LENGTH,
                return_tensors="pt",
            )
            encoded = {k: v.to(device) for k, v in encoded.items()}

            outputs = model(**encoded)
            logits = outputs.logits
            loss = criterion(logits, labels)

            optimizer.zero_grad()
            loss.backward()
            optimizer.step()
            scheduler.step()

            print(
                f"Epoch {epoch + 1}/{EPOCHS} | Batch {batch_idx}/{total_batches} | Loss: {loss.item():.4f}"
            )

        val_loss, val_acc, val_f1 = evaluate(model, tokenizer, val_records, criterion, device)
        print(f"Val Loss: {val_loss:.4f} | Acc: {val_acc:.4f} | F1: {val_f1:.4f}")

        if val_f1 > best_f1:
            best_f1 = val_f1
            best_epoch = epoch + 1
            checkpoint_dir = OUTPUT_DIR / "best_checkpoint"
            checkpoint_dir.mkdir(parents=True, exist_ok=True)
            model.save_pretrained(checkpoint_dir)
            tokenizer.save_pretrained(checkpoint_dir)

    final_dir = OUTPUT_DIR / "final"
    final_dir.mkdir(parents=True, exist_ok=True)
    model.save_pretrained(final_dir)
    tokenizer.save_pretrained(final_dir)

    print(f"Best val F1: {best_f1:.4f}")
    print(f"Best epoch: {best_epoch}")
    print(f"Model saved to: {final_dir}")


if __name__ == "__main__":
    main()
