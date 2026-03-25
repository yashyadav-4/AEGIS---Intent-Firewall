#!/usr/bin/env python3
"""Build train/dev feature datasets for Aegis-Zero.

Pipeline per split:
1) Parse ASVspoof protocol -> (file_id, label)
2) Parallel (Pool(4)): preprocess + phase + glottal
3) Sequential (main process): WavLM features
4) Save final .npy arrays to dataset/
5) Log skipped files with reasons
"""

from __future__ import annotations

import argparse
import multiprocessing as mp
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

import numpy as np
from tqdm import tqdm

from feature_extractors import (
    extract_glottal_features,
    extract_phase_coherence,
    extract_wavlm_features,
    preprocess_audio,
)


# Label mapping required by prompt.
BONAFIDE_LABEL = 0
SPOOF_LABEL = 1


@dataclass(frozen=True)
class Record:
    index: int
    file_id: str
    label: int
    audio_path: str


def parse_protocol(protocol_path: Path, audio_dir: Path) -> list[Record]:
    """Parse ASVspoof protocol file into ordered record list."""
    records: list[Record] = []

    with protocol_path.open("r", encoding="utf-8") as f:
        for idx, raw_line in enumerate(f):
            line = raw_line.strip()
            if not line:
                continue

            parts = line.split()
            if len(parts) < 5:
                raise ValueError(f"Malformed protocol line {idx + 1}: {line}")

            file_id = parts[1]
            label_str = parts[4].lower()
            if label_str == "bonafide":
                label = BONAFIDE_LABEL
            elif label_str == "spoof":
                label = SPOOF_LABEL
            else:
                raise ValueError(f"Unknown label on line {idx + 1}: {label_str}")

            audio_path = audio_dir / f"{file_id}.flac"
            records.append(Record(index=len(records), file_id=file_id, label=label, audio_path=str(audio_path)))

    return records


def _finite_feature(vec: np.ndarray, expected_dim: int) -> bool:
    return bool(vec.shape == (expected_dim,) and np.isfinite(vec).all())


def _phase_glottal_worker(args: tuple[int, str, int, str]) -> tuple[int, str, int, np.ndarray | None, np.ndarray | None, str | None]:
    """Worker for preprocess + phase + glottal extraction.

    Returns:
      (idx, file_id, label, phase_or_none, glottal_or_none, error_or_none)
    """
    idx, file_id, label, audio_path = args

    try:
        wave = preprocess_audio(audio_path)
        phase = extract_phase_coherence(wave)
        glottal = extract_glottal_features(wave)

        if not _finite_feature(phase, 64):
            return idx, file_id, label, None, None, "phase_non_finite_or_bad_shape"
        if not _finite_feature(glottal, 12):
            return idx, file_id, label, None, None, "glottal_non_finite_or_bad_shape"

        return idx, file_id, label, phase.astype(np.float32), glottal.astype(np.float32), None

    except Exception as exc:
        return idx, file_id, label, None, None, f"phase_glottal_exception: {type(exc).__name__}: {exc}"


def _ensure_dir(path: Path) -> None:
    path.mkdir(parents=True, exist_ok=True)


def _load_wavlm_model():
    """Load WavLM once in main process (non-pickleable)."""
    import torch
    from transformers import WavLMModel

    model = WavLMModel.from_pretrained("microsoft/wavlm-base-plus")
    model.eval()

    # CPU is expected on M1 for this script unless manually configured otherwise.
    if hasattr(model, "to"):
        model.to("cpu")

    # torch is imported here intentionally to keep worker process lean.
    _ = torch
    return model


def _write_skip_log(path: Path, lines: Iterable[str]) -> None:
    with path.open("w", encoding="utf-8") as f:
        for line in lines:
            f.write(f"{line}\n")


def _open_tmp_memmaps(tmp_dir: Path, split: str, n: int):
    phase_path = tmp_dir / f"{split}_phase.tmp.dat"
    glottal_path = tmp_dir / f"{split}_glottal.tmp.dat"
    labels_path = tmp_dir / f"{split}_labels.tmp.dat"

    phase_mm = np.memmap(phase_path, dtype=np.float32, mode="w+", shape=(n, 64))
    glottal_mm = np.memmap(glottal_path, dtype=np.float32, mode="w+", shape=(n, 12))
    labels_mm = np.memmap(labels_path, dtype=np.int32, mode="w+", shape=(n,))

    return phase_mm, glottal_mm, labels_mm, phase_path, glottal_path, labels_path


def _build_split(
    split: str,
    records: list[Record],
    output_dir: Path,
    workers: int,
    chunk_size: int,
) -> dict[str, int]:
    """Build one split and save final .npy outputs."""
    n_total = len(records)
    if n_total == 0:
        raise ValueError(f"No records parsed for split={split}")

    tmp_dir = output_dir / "_tmp_build"
    _ensure_dir(tmp_dir)

    skipped: list[str] = []

    # Stage 1: parallel preprocess + phase + glottal
    phase_mm, glottal_mm, labels_mm, phase_tmp_path, glottal_tmp_path, labels_tmp_path = _open_tmp_memmaps(
        tmp_dir, split, n_total
    )

    valid_stage1 = np.zeros(n_total, dtype=bool)
    valid_paths = [""] * n_total

    worker_inputs = [(r.index, r.file_id, r.label, r.audio_path) for r in records]

    with mp.Pool(processes=workers) as pool:
        it = pool.imap_unordered(_phase_glottal_worker, worker_inputs, chunksize=chunk_size)
        for idx, file_id, label, phase, glottal, err in tqdm(
            it, total=n_total, desc=f"{split}: phase+glottal", unit="file"
        ):
            if err is not None:
                skipped.append(f"{file_id}\t{err}")
                continue

            phase_mm[idx] = phase
            glottal_mm[idx] = glottal
            labels_mm[idx] = int(label)
            valid_stage1[idx] = True
            valid_paths[idx] = records[idx].audio_path

    phase_mm.flush()
    glottal_mm.flush()
    labels_mm.flush()

    valid_indices_stage1 = np.flatnonzero(valid_stage1)

    # Stage 2: sequential WavLM (cannot be parallelized via pickle)
    wavlm_model = _load_wavlm_model()

    wavlm_tmp_path = tmp_dir / f"{split}_wavlm.tmp.dat"
    wavlm_mm = np.memmap(wavlm_tmp_path, dtype=np.float32, mode="w+", shape=(valid_indices_stage1.size, 128))

    accepted_orig_indices: list[int] = []
    wavlm_write_pos = 0

    for orig_idx in tqdm(valid_indices_stage1, total=valid_indices_stage1.size, desc=f"{split}: wavlm", unit="file"):
        file_id = records[int(orig_idx)].file_id
        try:
            wave = preprocess_audio(valid_paths[int(orig_idx)])
            wavlm = extract_wavlm_features(wave, wavlm_model)
            if not _finite_feature(wavlm, 128):
                skipped.append(f"{file_id}\twavlm_non_finite_or_bad_shape")
                continue

            wavlm_mm[wavlm_write_pos] = wavlm.astype(np.float32)
            accepted_orig_indices.append(int(orig_idx))
            wavlm_write_pos += 1

        except Exception as exc:
            skipped.append(f"{file_id}\twavlm_exception: {type(exc).__name__}: {exc}")

    wavlm_mm.flush()

    final_count = len(accepted_orig_indices)
    accepted_orig = np.asarray(accepted_orig_indices, dtype=np.int64)

    # Allocate final output files directly as .npy (memmap-backed)
    train_or_dev = split
    phase_out = np.lib.format.open_memmap(
        output_dir / f"{train_or_dev}_phase.npy", dtype=np.float32, mode="w+", shape=(final_count, 64)
    )
    glottal_out = np.lib.format.open_memmap(
        output_dir / f"{train_or_dev}_glottal.npy", dtype=np.float32, mode="w+", shape=(final_count, 12)
    )
    wavlm_out = np.lib.format.open_memmap(
        output_dir / f"{train_or_dev}_wavlm.npy", dtype=np.float32, mode="w+", shape=(final_count, 128)
    )
    labels_out = np.lib.format.open_memmap(
        output_dir / f"{train_or_dev}_labels.npy", dtype=np.int32, mode="w+", shape=(final_count,)
    )

    # Copy in chunks to avoid large peak memory.
    for start in range(0, final_count, 1000):
        end = min(start + 1000, final_count)
        idx_batch = accepted_orig[start:end]
        phase_out[start:end] = phase_mm[idx_batch]
        glottal_out[start:end] = glottal_mm[idx_batch]
        labels_out[start:end] = labels_mm[idx_batch]

    if final_count > 0:
        wavlm_out[:final_count] = wavlm_mm[:final_count]

    phase_out.flush()
    glottal_out.flush()
    wavlm_out.flush()
    labels_out.flush()

    # Cleanup temp memmaps/files.
    del phase_mm, glottal_mm, labels_mm, wavlm_mm, phase_out, glottal_out, wavlm_out, labels_out
    for p in [phase_tmp_path, glottal_tmp_path, labels_tmp_path, wavlm_tmp_path]:
        try:
            p.unlink(missing_ok=True)
        except Exception:
            pass

    # Write skip log.
    _write_skip_log(output_dir / f"skipped_{split}.txt", skipped)

    labels_final = np.load(output_dir / f"{split}_labels.npy", mmap_mode="r")
    n_bonafide = int(np.sum(labels_final == BONAFIDE_LABEL))
    n_spoof = int(np.sum(labels_final == SPOOF_LABEL))

    return {
        "total_records": n_total,
        "final_records": final_count,
        "skipped_records": len(skipped),
        "bonafide": n_bonafide,
        "spoof": n_spoof,
    }


def _print_array_info(path: Path) -> None:
    arr = np.load(path, mmap_mode="r")
    print(f"{path.name}: shape={arr.shape}, dtype={arr.dtype}")


def main() -> None:
    parser = argparse.ArgumentParser(description="Build ASVspoof train/dev feature datasets")
    parser.add_argument("--train-audio-dir", type=Path, default=Path("Dataset/LA/ASVspoof2019_LA_train/flac"))
    parser.add_argument("--dev-audio-dir", type=Path, default=Path("Dataset/LA/ASVspoof2019_LA_dev/flac"))
    parser.add_argument(
        "--train-protocol",
        type=Path,
        default=Path("Dataset/LA/ASVspoof2019_LA_cm_protocols/ASVspoof2019.LA.cm.train.trn.txt"),
    )
    parser.add_argument(
        "--dev-protocol",
        type=Path,
        default=Path("Dataset/LA/ASVspoof2019_LA_cm_protocols/ASVspoof2019.LA.cm.dev.trl.txt"),
    )
    parser.add_argument("--output-dir", type=Path, default=Path("dataset"))
    parser.add_argument("--workers", type=int, default=4)
    parser.add_argument("--chunk-size", type=int, default=16)
    args = parser.parse_args()

    start_time = time.perf_counter()

    _ensure_dir(args.output_dir)

    train_records = parse_protocol(args.train_protocol, args.train_audio_dir)
    dev_records = parse_protocol(args.dev_protocol, args.dev_audio_dir)

    print(f"Parsed train records: {len(train_records)}")
    print(f"Parsed dev records: {len(dev_records)}")

    train_stats = _build_split(
        split="train",
        records=train_records,
        output_dir=args.output_dir,
        workers=args.workers,
        chunk_size=args.chunk_size,
    )

    dev_stats = _build_split(
        split="dev",
        records=dev_records,
        output_dir=args.output_dir,
        workers=args.workers,
        chunk_size=args.chunk_size,
    )

    # Scale glottal features using train-fit statistics, then overwrite saved arrays.
    from sklearn.preprocessing import StandardScaler
    import joblib

    train_glottal = np.load(args.output_dir / "train_glottal.npy")
    dev_glottal = np.load(args.output_dir / "dev_glottal.npy")

    scaler = StandardScaler()
    train_glottal_scaled = scaler.fit_transform(train_glottal)
    dev_glottal_scaled = scaler.transform(dev_glottal)

    joblib.dump(scaler, args.output_dir / "glottal_scaler.joblib")

    np.save(args.output_dir / "train_glottal.npy", train_glottal_scaled)
    np.save(args.output_dir / "dev_glottal.npy", dev_glottal_scaled)

    print(f"Glottal scaled mean (should be ~0): {float(np.mean(train_glottal_scaled)):.4f}")
    print(f"Glottal scaled std (should be ~1):  {float(np.std(train_glottal_scaled)):.4f}")

    # Scale phase features
    train_phase = np.load(args.output_dir / "train_phase.npy")
    dev_phase = np.load(args.output_dir / "dev_phase.npy")

    phase_scaler = StandardScaler()
    train_phase_scaled = phase_scaler.fit_transform(train_phase)
    dev_phase_scaled = phase_scaler.transform(dev_phase)

    joblib.dump(phase_scaler, args.output_dir / "phase_scaler.joblib")

    np.save(args.output_dir / "train_phase.npy", train_phase_scaled)
    np.save(args.output_dir / "dev_phase.npy", dev_phase_scaled)

    print(f"Phase scaled mean: {train_phase_scaled.mean():.6f}")
    print(f"Phase scaled std:  {train_phase_scaled.std():.6f}")

    # Scale WavLM features
    train_wavlm = np.load(args.output_dir / "train_wavlm.npy")
    dev_wavlm = np.load(args.output_dir / "dev_wavlm.npy")

    wavlm_scaler = StandardScaler()
    train_wavlm_scaled = wavlm_scaler.fit_transform(train_wavlm)
    dev_wavlm_scaled = wavlm_scaler.transform(dev_wavlm)

    joblib.dump(wavlm_scaler, args.output_dir / "wavlm_scaler.joblib")

    np.save(args.output_dir / "train_wavlm.npy", train_wavlm_scaled)
    np.save(args.output_dir / "dev_wavlm.npy", dev_wavlm_scaled)

    print(f"WavLM scaled mean: {train_wavlm_scaled.mean():.6f}")
    print(f"WavLM scaled std:  {train_wavlm_scaled.std():.6f}")

    # Required output summary.
    for name in [
        "train_phase.npy",
        "train_glottal.npy",
        "train_wavlm.npy",
        "train_labels.npy",
        "dev_phase.npy",
        "dev_glottal.npy",
        "dev_wavlm.npy",
        "dev_labels.npy",
    ]:
        _print_array_info(args.output_dir / name)

    print(
        f"Train class counts: {train_stats['bonafide']} bonafide, {train_stats['spoof']} spoof"
    )
    print(
        f"Dev class counts: {dev_stats['bonafide']} bonafide, {dev_stats['spoof']} spoof"
    )

    total_skipped = train_stats["skipped_records"] + dev_stats["skipped_records"]
    print(f"Total skipped files: {total_skipped}")

    elapsed_s = time.perf_counter() - start_time
    print(f"Elapsed time: {elapsed_s:.2f} seconds ({elapsed_s / 3600.0:.2f} hours)")


if __name__ == "__main__":
    # macOS spawn safety for multiprocessing.
    mp.set_start_method("spawn", force=True)
    main()
