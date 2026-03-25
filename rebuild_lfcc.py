#!/usr/bin/env python3
"""Rebuild only LFCC-based phase feature arrays for train/dev splits.

Outputs:
- dataset/train_phase.npy (scaled, float32, shape [N, 64])
- dataset/dev_phase.npy   (scaled, float32, shape [M, 64])
- dataset/phase_scaler.joblib
"""

from __future__ import annotations

import multiprocessing as mp
from pathlib import Path

import joblib
import numpy as np
from sklearn.preprocessing import StandardScaler
from tqdm import tqdm

from feature_extractors import extract_lfcc_features, preprocess_audio

BONAFIDE_LABEL = 0
SPOOF_LABEL = 1

TRAIN_PROTOCOL = Path("Dataset/LA/ASVspoof2019_LA_cm_protocols/ASVspoof2019.LA.cm.train.trn.txt")
DEV_PROTOCOL = Path("Dataset/LA/ASVspoof2019_LA_cm_protocols/ASVspoof2019.LA.cm.dev.trl.txt")
TRAIN_AUDIO_DIR = Path("Dataset/LA/ASVspoof2019_LA_train/flac")
DEV_AUDIO_DIR = Path("Dataset/LA/ASVspoof2019_LA_dev/flac")
OUTPUT_DIR = Path("dataset")


def parse_protocol(protocol_path: Path, audio_dir: Path) -> list[tuple[int, str, int, Path]]:
    records: list[tuple[int, str, int, Path]] = []

    with protocol_path.open("r", encoding="utf-8") as f:
        for line_idx, raw in enumerate(f, start=1):
            line = raw.strip()
            if not line:
                continue

            parts = line.split()
            if len(parts) < 5:
                continue

            file_id = parts[1]
            label_str = parts[4].lower()
            if label_str == "bonafide":
                label = BONAFIDE_LABEL
            elif label_str == "spoof":
                label = SPOOF_LABEL
            else:
                continue

            audio_path = audio_dir / f"{file_id}.flac"
            records.append((len(records), file_id, label, audio_path))

    return records


def _extract_one(args: tuple[int, str, int, str]) -> tuple[int, int, np.ndarray] | None:
    idx, _file_id, label, audio_path = args

    try:
        waveform = preprocess_audio(audio_path)
        feat = extract_lfcc_features(waveform)

        if feat.shape != (64,) or not np.isfinite(feat).all():
            return None

        return idx, label, feat.astype(np.float32)
    except Exception:
        # Skip failed files silently.
        return None


def build_split(split_name: str, protocol_path: Path, audio_dir: Path) -> tuple[np.ndarray, np.ndarray]:
    records = parse_protocol(protocol_path, audio_dir)
    tasks = [(idx, file_id, label, str(audio_path)) for idx, file_id, label, audio_path in records]

    features_by_idx: dict[int, np.ndarray] = {}
    labels_by_idx: dict[int, int] = {}

    with mp.Pool(4) as pool:
        iterator = pool.imap_unordered(_extract_one, tasks, chunksize=16)
        for out in tqdm(iterator, total=len(tasks), desc=f"{split_name} LFCC", unit="file"):
            if out is None:
                continue
            idx, label, feat = out
            features_by_idx[idx] = feat
            labels_by_idx[idx] = label

    kept_indices = sorted(features_by_idx.keys())
    if not kept_indices:
        return np.empty((0, 64), dtype=np.float32), np.empty((0,), dtype=np.int32)

    x = np.stack([features_by_idx[i] for i in kept_indices]).astype(np.float32)
    y = np.array([labels_by_idx[i] for i in kept_indices], dtype=np.int32)

    return x, y


def print_class_separation(split_name: str, x: np.ndarray, y: np.ndarray) -> None:
    b_mask = y == BONAFIDE_LABEL
    s_mask = y == SPOOF_LABEL

    b_mean = float(np.mean(x[b_mask])) if np.any(b_mask) else float("nan")
    s_mean = float(np.mean(x[s_mask])) if np.any(s_mask) else float("nan")

    print(f"{split_name} class separation (feature mean): bonafide={b_mean:.6f}, spoof={s_mean:.6f}")


def main() -> None:
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

    print("Building LFCC train split...")
    train_phase, train_labels = build_split("train", TRAIN_PROTOCOL, TRAIN_AUDIO_DIR)

    print("Building LFCC dev split...")
    dev_phase, dev_labels = build_split("dev", DEV_PROTOCOL, DEV_AUDIO_DIR)

    # Save raw first.
    np.save(OUTPUT_DIR / "train_phase.npy", train_phase.astype(np.float32))
    np.save(OUTPUT_DIR / "dev_phase.npy", dev_phase.astype(np.float32))

    # Scale and overwrite.
    scaler = StandardScaler()
    if train_phase.shape[0] > 0:
        train_phase_scaled = scaler.fit_transform(train_phase).astype(np.float32)
        dev_phase_scaled = scaler.transform(dev_phase).astype(np.float32) if dev_phase.shape[0] > 0 else dev_phase.astype(np.float32)
    else:
        train_phase_scaled = train_phase.astype(np.float32)
        dev_phase_scaled = dev_phase.astype(np.float32)

    joblib.dump(scaler, OUTPUT_DIR / "phase_scaler.joblib")

    np.save(OUTPUT_DIR / "train_phase.npy", train_phase_scaled)
    np.save(OUTPUT_DIR / "dev_phase.npy", dev_phase_scaled)

    print(f"Scaled train mean: {float(train_phase_scaled.mean()):.6f}")
    print(f"Scaled train std:  {float(train_phase_scaled.std()):.6f}")
    print(f"Scaled dev mean:   {float(dev_phase_scaled.mean()):.6f}")
    print(f"Scaled dev std:    {float(dev_phase_scaled.std()):.6f}")

    print(f"train_phase shape: {train_phase_scaled.shape}")
    print(f"dev_phase shape:   {dev_phase_scaled.shape}")

    print_class_separation("train", train_phase_scaled, train_labels)
    print_class_separation("dev", dev_phase_scaled, dev_labels)

    print("Estimated runtime on Apple Silicon: ~20 minutes for full rebuild.")


if __name__ == "__main__":
    mp.set_start_method("spawn", force=True)
    main()
