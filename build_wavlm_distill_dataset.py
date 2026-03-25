#!/usr/bin/env python3
"""Build offline WavLM distillation dataset.

Generates raw waveform inputs and 128-dim WavLM teacher targets for train/dev.
Audio loading is parallelized with multiprocessing, while WavLM inference remains
in the main process to avoid pickling issues.
"""

from __future__ import annotations

import argparse
import multiprocessing as mp
from pathlib import Path
from typing import List, Sequence, Tuple

import numpy as np
import torch
from tqdm import tqdm
from transformers import WavLMModel

from feature_extractors import preprocess_audio


TRAIN_AUDIO_DIR = Path("Dataset/LA/ASVspoof2019_LA_train/flac")
DEV_AUDIO_DIR = Path("Dataset/LA/ASVspoof2019_LA_dev/flac")
TRAIN_PROTO = Path("Dataset/LA/ASVspoof2019_LA_cm_protocols/ASVspoof2019.LA.cm.train.trn.txt")
DEV_PROTO = Path("Dataset/LA/ASVspoof2019_LA_cm_protocols/ASVspoof2019.LA.cm.dev.trl.txt")
OUT_DIR = Path("dataset/distill")

WAVEFORM_LEN = 32000
FEATURE_DIM = 128
DEFAULT_BATCH_SIZE = 100


def extract_wavlm_teacher_raw(waveform: np.ndarray, wavlm_model: WavLMModel) -> np.ndarray:
    """Copy of feature_extractors.extract_wavlm_features without scaler/L2."""
    waveform_t = torch.tensor(waveform).unsqueeze(0).float()

    with torch.no_grad():
        out = wavlm_model(waveform_t, output_hidden_states=True)

    layer9 = out.hidden_states[9].squeeze(0).numpy()
    mean = layer9.mean(axis=0)
    std = layer9.std(axis=0)

    idx = np.linspace(0, 767, 32, dtype=int)
    mean_r = mean[idx]
    std_r = std[idx]

    layer6 = out.hidden_states[6].squeeze(0).numpy()
    mean6 = layer6.mean(axis=0)[idx]
    std6 = layer6.std(axis=0)[idx]

    features = np.concatenate([mean_r, std_r, mean6, std6])
    return features.astype(np.float32)


def _read_protocol_ids(protocol_path: Path) -> List[str]:
    ids: List[str] = []
    with protocol_path.open("r", encoding="utf-8") as f:
        for line in f:
            parts = line.strip().split()
            if len(parts) >= 2:
                ids.append(parts[1])
    return ids


def _load_waveform_safe(audio_path: str) -> Tuple[bool, np.ndarray | None]:
    try:
        waveform = preprocess_audio(audio_path)
        if waveform.shape[0] != WAVEFORM_LEN:
            waveform = waveform[:WAVEFORM_LEN]
            if waveform.shape[0] < WAVEFORM_LEN:
                waveform = np.pad(waveform, (0, WAVEFORM_LEN - waveform.shape[0]))
        return True, waveform.astype(np.float32)
    except Exception:
        return False, None


def _batched(seq: Sequence[str], batch_size: int):
    for i in range(0, len(seq), batch_size):
        yield seq[i : i + batch_size]


def _validate_finite(name: str, arr: np.ndarray) -> None:
    finite = bool(np.isfinite(arr).all())
    print(f"{name}: shape={arr.shape}, finite={finite}")
    if not finite:
        raise RuntimeError(f"Non-finite values detected in {name}.")


def _process_split(
    split_name: str,
    protocol_path: Path,
    audio_dir: Path,
    wavlm_model: WavLMModel,
    batch_size: int,
    num_workers: int,
    mp_context: mp.context.BaseContext,
) -> Tuple[np.ndarray, np.ndarray, int]:
    ids = _read_protocol_ids(protocol_path)
    audio_paths = [str(audio_dir / f"{fid}.flac") for fid in ids]

    waveforms: List[np.ndarray] = []
    teachers: List[np.ndarray] = []
    skipped = 0

    with mp_context.Pool(processes=num_workers) as pool:
        pbar = tqdm(total=len(audio_paths), desc=f"{split_name}", unit="file")

        for batch_paths in _batched(audio_paths, batch_size):
            loaded_batch = pool.map(_load_waveform_safe, batch_paths)

            for ok, waveform in loaded_batch:
                if not ok or waveform is None:
                    skipped += 1
                    continue

                try:
                    teacher = extract_wavlm_teacher_raw(waveform, wavlm_model)
                except Exception:
                    skipped += 1
                    continue

                if teacher.shape != (FEATURE_DIM,):
                    skipped += 1
                    continue

                waveforms.append(waveform)
                teachers.append(teacher)

            pbar.update(len(batch_paths))

        pbar.close()

    if waveforms:
        waveforms_arr = np.stack(waveforms).astype(np.float32)
    else:
        waveforms_arr = np.empty((0, WAVEFORM_LEN), dtype=np.float32)

    if teachers:
        teachers_arr = np.stack(teachers).astype(np.float32)
    else:
        teachers_arr = np.empty((0, FEATURE_DIM), dtype=np.float32)

    return waveforms_arr, teachers_arr, skipped


def main() -> None:
    parser = argparse.ArgumentParser(description="Build WavLM distillation dataset.")
    parser.add_argument("--batch-size", type=int, default=DEFAULT_BATCH_SIZE, help="Batch size for loading files.")
    parser.add_argument(
        "--workers",
        type=int,
        default=max(1, (mp.cpu_count() or 2) - 1),
        help="Number of audio loading workers.",
    )
    args = parser.parse_args()

    OUT_DIR.mkdir(parents=True, exist_ok=True)

    print("Loading WavLM teacher: microsoft/wavlm-base-plus")
    wavlm = WavLMModel.from_pretrained("microsoft/wavlm-base-plus")
    wavlm.eval()
    print("Teacher model ready.")
    print("Estimated runtime on Apple Silicon (M1): ~90 minutes")

    mp_context = mp.get_context("spawn")

    train_waveforms, train_teacher, train_skipped = _process_split(
        split_name="train",
        protocol_path=TRAIN_PROTO,
        audio_dir=TRAIN_AUDIO_DIR,
        wavlm_model=wavlm,
        batch_size=args.batch_size,
        num_workers=args.workers,
        mp_context=mp_context,
    )

    dev_waveforms, dev_teacher, dev_skipped = _process_split(
        split_name="dev",
        protocol_path=DEV_PROTO,
        audio_dir=DEV_AUDIO_DIR,
        wavlm_model=wavlm,
        batch_size=args.batch_size,
        num_workers=args.workers,
        mp_context=mp_context,
    )

    train_wave_path = OUT_DIR / "train_waveforms.npy"
    train_teacher_path = OUT_DIR / "train_wavlm_teacher.npy"
    dev_wave_path = OUT_DIR / "dev_waveforms.npy"
    dev_teacher_path = OUT_DIR / "dev_wavlm_teacher.npy"

    np.save(train_wave_path, train_waveforms)
    np.save(train_teacher_path, train_teacher)
    np.save(dev_wave_path, dev_waveforms)
    np.save(dev_teacher_path, dev_teacher)

    print("\nSaved files:")
    print(f"- {train_wave_path}")
    print(f"- {train_teacher_path}")
    print(f"- {dev_wave_path}")
    print(f"- {dev_teacher_path}")

    _validate_finite("train_waveforms", train_waveforms)
    _validate_finite("train_wavlm_teacher", train_teacher)
    _validate_finite("dev_waveforms", dev_waveforms)
    _validate_finite("dev_wavlm_teacher", dev_teacher)

    print("\nSkip summary:")
    print(f"train skipped: {train_skipped}")
    print(f"dev skipped: {dev_skipped}")


if __name__ == "__main__":
    mp.set_start_method("spawn", force=True)
    main()
