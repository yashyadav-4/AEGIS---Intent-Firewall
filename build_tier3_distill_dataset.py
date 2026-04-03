"""
# Tier 3 distillation dataset builder
# Extracts CLS hidden states from fine-tuned DistilBERT
# Output: 768-dim features for each dialogue sample
"""

import pathlib
import json

import numpy as np
import torch
import transformers
import tqdm


Path = pathlib.Path

BASE_DIR = Path.home() / "Desktop/Hackaccino"
MODEL_DIR = BASE_DIR / "models/dialogrpt/final"
DATA_PATH = BASE_DIR / "dataset/scam_dialogues.jsonl"
OUT_DIR = BASE_DIR / "dataset/tier3"
MAX_LENGTH = 256
BATCH_SIZE = 32


def load_records(data_path):
    records = []
    with data_path.open("r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            row = json.loads(line)
            context = str(row.get("context", "") or "").strip()
            response = str(row.get("response", "") or "").strip()
            text = f"{context}\n{response}" if context else response

            records.append(
                {
                    "text": text,
                    "label": int(row.get("label", 0)),
                    "category": str(row.get("category", "") or ""),
                }
            )
    return records


def batch_iter(items, batch_size):
    for start in range(0, len(items), batch_size):
        yield items[start : start + batch_size]


def extract_features(records, tokenizer, base_model):
    all_features = []
    all_labels = []
    all_categories = []

    for batch in tqdm.tqdm(list(batch_iter(records, BATCH_SIZE)), desc="Extracting", unit="batch"):
        texts = [x["text"] for x in batch]
        labels = [int(x["label"]) for x in batch]
        categories = [x["category"] for x in batch]

        encoded = tokenizer(
            texts,
            padding=True,
            truncation=True,
            max_length=MAX_LENGTH,
            return_tensors="pt",
        )

        with torch.no_grad():
            outputs = base_model(
                input_ids=encoded["input_ids"],
                attention_mask=encoded["attention_mask"],
            )
            hidden = outputs.last_hidden_state
            pooled = hidden[:, 0, :]

        all_features.append(pooled.cpu().numpy().astype(np.float32))
        all_labels.extend(labels)
        all_categories.extend(categories)

    features = np.concatenate(all_features, axis=0)
    labels = np.asarray(all_labels, dtype=np.int64)
    categories = np.asarray(all_categories, dtype=object)
    return features, labels, categories


def split_train_val(features, labels, categories, val_ratio=0.15, seed=42):
    n = features.shape[0]
    rng = np.random.default_rng(seed)
    indices = np.arange(n)
    rng.shuffle(indices)

    val_size = int(n * val_ratio)
    if val_size <= 0 and n > 1:
        val_size = 1

    val_idx = indices[:val_size]
    train_idx = indices[val_size:]

    train_features = features[train_idx]
    train_labels = labels[train_idx]
    _train_categories = categories[train_idx]

    val_features = features[val_idx]
    val_labels = labels[val_idx]
    _val_categories = categories[val_idx]

    return train_features, train_labels, val_features, val_labels


def main():
    OUT_DIR.mkdir(parents=True, exist_ok=True)

    tokenizer = transformers.AutoTokenizer.from_pretrained(str(MODEL_DIR))
    model = transformers.AutoModelForSequenceClassification.from_pretrained(str(MODEL_DIR))
    model.eval()

    if hasattr(model, "pre_classifier"):
        model.pre_classifier = torch.nn.Identity()
    if hasattr(model, "classifier"):
        model.classifier = torch.nn.Identity()

    base_model = model.distilbert
    base_model.eval()

    records = load_records(DATA_PATH)
    features, labels, categories = extract_features(records, tokenizer, base_model)

    train_features, train_labels, val_features, val_labels = split_train_val(
        features,
        labels,
        categories,
        val_ratio=0.15,
        seed=42,
    )

    np.save(OUT_DIR / "tier3_train_features.npy", train_features)
    np.save(OUT_DIR / "tier3_train_labels.npy", train_labels)
    np.save(OUT_DIR / "tier3_val_features.npy", val_features)
    np.save(OUT_DIR / "tier3_val_labels.npy", val_labels)

    count_0 = int(np.sum(labels == 0))
    count_1 = int(np.sum(labels == 1))

    print(f"Total samples: {len(records)}")
    print(f"Train features shape: {train_features.shape}")
    print(f"Val features shape: {val_features.shape}")
    print(f"Label distribution — 0: {count_0}  1: {count_1}")
    print("Saved to: dataset/tier3/")


if __name__ == "__main__":
    main()
