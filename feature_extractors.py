#!/usr/bin/env python3
"""Feature extraction pipelines for Aegis-Zero deepfake voice detection.

This module provides:
- LFCC features (64-dim)
- Glottal irregularity features (12-dim)
- WavLM cross-layer consistency features (128-dim)
- Audio preprocessing to fixed 2-second 16 kHz waveform (32000 samples)
"""

from __future__ import annotations

import argparse
import math
import time
import warnings
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

import numpy as np
from scipy import signal, stats

try:
    import librosa
except Exception:  # pragma: no cover - optional dependency fallback
    librosa = None

try:
    import soundfile as sf
except Exception:  # pragma: no cover - optional dependency fallback
    sf = None

try:
    import torch
except Exception:  # pragma: no cover - optional dependency fallback
    torch = None

SAMPLE_RATE = 16000
WINDOW_SAMPLES = 32000
EPS = 1e-8


@dataclass
class FeatureRuntime:
    phase_ms: float
    glottal_ms: float
    wavlm_ms: float


def preprocess_audio(path: str | Path | np.ndarray) -> np.ndarray:
    """Load audio, convert to mono 16 kHz, normalize and pad/trim to 32000 samples."""
    if isinstance(path, np.ndarray):
        waveform = np.asarray(path, dtype=np.float32)
    else:
        audio_path = Path(path)
        if not audio_path.exists():
            raise FileNotFoundError(f"Audio path does not exist: {audio_path}")

        if librosa is not None:
            waveform, _ = librosa.load(str(audio_path), sr=SAMPLE_RATE, mono=True)
            waveform = waveform.astype(np.float32)
        elif sf is not None:
            waveform, sr = sf.read(str(audio_path), dtype="float32", always_2d=False)
            if waveform.ndim > 1:
                waveform = waveform.mean(axis=1)
            if sr != SAMPLE_RATE:
                gcd = math.gcd(sr, SAMPLE_RATE)
                up = SAMPLE_RATE // gcd
                down = sr // gcd
                waveform = signal.resample_poly(waveform, up=up, down=down).astype(np.float32)
        else:
            raise RuntimeError(
                "No audio backend available. Install librosa or soundfile to load files."
            )

    waveform = np.nan_to_num(waveform, nan=0.0, posinf=0.0, neginf=0.0).astype(np.float32)
    max_abs = float(np.max(np.abs(waveform)))
    if max_abs > 0.0:
        waveform = waveform / max_abs

    if waveform.shape[0] < WINDOW_SAMPLES:
        waveform = np.pad(waveform, (0, WINDOW_SAMPLES - waveform.shape[0]))
    elif waveform.shape[0] > WINDOW_SAMPLES:
        waveform = waveform[:WINDOW_SAMPLES]

    return waveform.astype(np.float32)


def _estimate_f0_autocorr(waveform: np.ndarray, sr: int = SAMPLE_RATE) -> float:
    x = waveform.astype(np.float64)
    x -= np.mean(x)
    if np.max(np.abs(x)) < EPS:
        return 120.0

    min_f0, max_f0 = 50.0, 500.0
    min_lag = int(sr / max_f0)
    max_lag = int(sr / min_f0)

    corr = signal.correlate(x, x, mode="full", method="fft")
    corr = corr[corr.size // 2 :]
    corr[:min_lag] = 0.0

    search = corr[min_lag : max_lag + 1]
    if search.size == 0 or np.max(search) <= 0:
        return 120.0

    lag = int(np.argmax(search)) + min_lag
    return float(np.clip(sr / max(lag, 1), min_f0, max_f0))


def extract_lfcc_features(waveform: np.ndarray) -> np.ndarray:
    import numpy as np
    from scipy.fft import dct

    sr = 16000
    n_fft = 512
    hop = 160
    n_filters = 20
    n_coeffs = 20

    pre = np.append(waveform[0], waveform[1:] - 0.97 * waveform[:-1])

    frames = []
    for i in range(0, len(pre) - n_fft, hop):
        frame = pre[i : i + n_fft]
        window = np.hamming(n_fft)
        frames.append(frame * window)

    if len(frames) == 0:
        return np.zeros(64, dtype=np.float32)

    frames = np.array(frames)

    spec = np.abs(np.fft.rfft(frames, n=n_fft)) ** 2

    freq_bins = np.linspace(0, sr // 2, n_fft // 2 + 1)
    filter_centers = np.linspace(0, sr // 2, n_filters + 2)
    filterbank = np.zeros((n_filters, n_fft // 2 + 1))
    for m in range(1, n_filters + 1):
        f_m_minus = filter_centers[m - 1]
        f_m = filter_centers[m]
        f_m_plus = filter_centers[m + 1]
        for k, f in enumerate(freq_bins):
            if f_m_minus <= f <= f_m:
                filterbank[m - 1, k] = (f - f_m_minus) / (f_m - f_m_minus)
            elif f_m <= f <= f_m_plus:
                filterbank[m - 1, k] = (f_m_plus - f) / (f_m_plus - f_m)

    filter_energies = np.dot(spec, filterbank.T)
    log_energies = np.log(filter_energies + 1e-8)

    lfcc = dct(log_energies, type=2, axis=1, norm="ortho")[:, :n_coeffs]

    def delta(feat, N=2):
        pad = np.pad(feat, ((N, N), (0, 0)), mode="edge")
        d = np.zeros_like(feat)
        denom = 2 * sum(i**2 for i in range(1, N + 1))
        for i in range(1, N + 1):
            d += i * (pad[N + i : N + i + len(feat)] - pad[N - i : N - i + len(feat)])
        return d / denom

    delta1 = delta(lfcc)
    delta2 = delta(delta1)

    full = np.concatenate([lfcc, delta1, delta2], axis=1)

    mean = full.mean(axis=0)
    std = full.std(axis=0)

    energy = spec.mean(axis=1)
    global_stats = np.array([
        energy.mean(),
        energy.std(),
        energy.max(),
        energy.min(),
    ])

    features = np.concatenate([mean[:30], std[:30], global_stats])

    return features.astype(np.float32)


def _gci_candidates_from_waveform(waveform: np.ndarray, sr: int = SAMPLE_RATE) -> np.ndarray:
    """SEDREAMS-inspired deterministic GCI candidate extraction."""
    x = waveform.astype(np.float64)
    x -= np.mean(x)
    if np.max(np.abs(x)) < EPS:
        return np.array([], dtype=np.int32)

    # Pre-emphasis and smoothed group-delay proxy from derivative signal.
    pre = signal.lfilter([1.0, -0.97], [1.0], x)
    dg = np.diff(pre, prepend=pre[0])
    env = signal.savgol_filter(np.abs(dg), window_length=31, polyorder=3, mode="interp")
    score = -dg * (env + EPS)

    min_period = int(sr / 500.0)
    peaks, props = signal.find_peaks(
        score,
        distance=max(min_period, 1),
        prominence=np.percentile(score, 65) if score.size else 0.0,
    )

    if peaks.size == 0:
        return np.array([], dtype=np.int32)

    # Reject low-energy candidates to stabilize jitter/shimmer statistics.
    prominences = props.get("prominences", np.ones_like(peaks, dtype=np.float64))
    keep = prominences > (0.25 * np.median(prominences))
    return peaks[keep].astype(np.int32)


def _dfa_alpha(sequence: np.ndarray) -> float:
    """Detrended fluctuation analysis alpha exponent."""
    x = np.asarray(sequence, dtype=np.float64)
    if x.size < 16:
        return 0.5

    x = x - np.mean(x)
    y = np.cumsum(x)

    scales = np.unique(np.floor(np.logspace(np.log10(4), np.log10(max(8, x.size // 2)), 8)).astype(int))
    fluctuations = []

    for n in scales:
        if n < 4 or n >= y.size:
            continue
        n_segments = y.size // n
        if n_segments < 2:
            continue

        y_seg = y[: n_segments * n].reshape(n_segments, n)
        t = np.arange(n, dtype=np.float64)

        f_n = []
        for seg in y_seg:
            coeff = np.polyfit(t, seg, deg=1)
            trend = np.polyval(coeff, t)
            f_n.append(np.sqrt(np.mean((seg - trend) ** 2) + EPS))

        fluctuations.append(float(np.sqrt(np.mean(np.square(f_n)) + EPS)))

    if len(fluctuations) < 2:
        return 0.5

    log_scales = np.log(np.asarray(scales[: len(fluctuations)], dtype=np.float64) + EPS)
    log_fluct = np.log(np.asarray(fluctuations, dtype=np.float64) + EPS)
    alpha, _ = np.polyfit(log_scales, log_fluct, 1)
    return float(alpha)


def _approx_entropy(sequence: np.ndarray, m: int = 2, r_ratio: float = 0.2) -> float:
    """Approximate entropy for 1D sequence."""
    x = np.asarray(sequence, dtype=np.float64)
    n = x.size
    if n <= m + 1:
        return 0.0

    r = r_ratio * (np.std(x) + EPS)

    def _phi(mm: int) -> float:
        patterns = np.array([x[i : i + mm] for i in range(n - mm + 1)])
        c = np.zeros(patterns.shape[0], dtype=np.float64)
        for i, p in enumerate(patterns):
            dist = np.max(np.abs(patterns - p), axis=1)
            c[i] = np.sum(dist <= r) / patterns.shape[0]
        return float(np.mean(np.log(c + EPS)))

    return float(_phi(m) - _phi(m + 1))


def extract_glottal_features(waveform: np.ndarray) -> np.ndarray:
    """Extract 12-dim GCI timing and stochasticity features."""
    x = preprocess_audio(waveform)
    gci_idx = _gci_candidates_from_waveform(x, SAMPLE_RATE)

    # Fall back to quasi-periodic landmarks if speech segment is too short.
    if gci_idx.size < 4:
        f0 = _estimate_f0_autocorr(x, SAMPLE_RATE)
        period = max(int(SAMPLE_RATE / max(f0, 1.0)), 1)
        gci_idx = np.arange(period, x.shape[0], period, dtype=np.int32)

    if gci_idx.size < 4:
        return np.zeros(12, dtype=np.float32)

    intervals = np.diff(gci_idx).astype(np.float64) / SAMPLE_RATE
    intervals = np.clip(intervals, 1.0 / 600.0, 1.0 / 40.0)

    mean_int = float(np.mean(intervals))
    var_int = float(np.var(intervals))
    skew_int = float(stats.skew(intervals, bias=False)) if intervals.size > 2 else 0.0
    kurt_int = float(stats.kurtosis(intervals, fisher=True, bias=False)) if intervals.size > 3 else 0.0

    centered = intervals - np.mean(intervals)
    denom = float(np.dot(centered, centered) + EPS)

    def lag_autocorr(lag: int) -> float:
        if intervals.size <= lag:
            return 0.0
        num = float(np.dot(centered[:-lag], centered[lag:]))
        return num / denom

    ac1 = lag_autocorr(1)
    ac2 = lag_autocorr(2)
    ac3 = lag_autocorr(3)

    dfa = _dfa_alpha(intervals)
    apen = _approx_entropy(intervals)

    amps = np.abs(x[gci_idx])
    if amps.size > 1 and np.mean(amps) > EPS:
        shimmer = float(np.mean(np.abs(np.diff(amps))) / (np.mean(amps) + EPS))
    else:
        shimmer = 0.0

    iqr = float(np.percentile(intervals, 75) - np.percentile(intervals, 25))
    gci_rate = float(gci_idx.size / (x.shape[0] / SAMPLE_RATE))

    feats = np.array(
        [
            mean_int,
            var_int,
            skew_int,
            kurt_int,
            ac1,
            ac2,
            ac3,
            dfa,
            apen,
            shimmer,
            iqr,
            gci_rate,
        ],
        dtype=np.float32,
    )

    return np.nan_to_num(feats, nan=0.0, posinf=0.0, neginf=0.0)


def _topk_pca_basis(x: np.ndarray, k: int) -> np.ndarray:
    x0 = x - np.mean(x, axis=0, keepdims=True)
    if x0.shape[0] < 2:
        return np.eye(x.shape[1], k, dtype=np.float64)
    _, _, vt = np.linalg.svd(x0, full_matrices=False)
    return vt[:k].T


def _safe_hist(values: np.ndarray, bins: int, low: float, high: float) -> np.ndarray:
    hist, _ = np.histogram(values, bins=bins, range=(low, high), density=False)
    hist = hist.astype(np.float32)
    return hist / (np.sum(hist) + EPS)


def _summarize_vector(v: np.ndarray) -> np.ndarray:
    q = np.quantile(v, [0.1, 0.25, 0.5, 0.75, 0.9])
    out = np.array(
        [
            np.mean(v),
            np.std(v),
            np.min(v),
            np.max(v),
            q[0],
            q[1],
            q[2],
            q[3],
            q[4],
            stats.skew(v, bias=False) if v.size > 2 else 0.0,
        ],
        dtype=np.float32,
    )
    return np.nan_to_num(out, nan=0.0, posinf=0.0, neginf=0.0)


def _build_wavlm_consistency_vector(l6: np.ndarray, l9: np.ndarray) -> np.ndarray:
    """Construct a 128-dim cross-layer consistency feature vector."""
    l6 = np.asarray(l6, dtype=np.float64)
    l9 = np.asarray(l9, dtype=np.float64)

    if l6.ndim != 2 or l9.ndim != 2:
        raise ValueError("WavLM hidden states must be 2D arrays [frames, dim].")

    t = min(l6.shape[0], l9.shape[0])
    d = min(l6.shape[1], l9.shape[1])
    l6 = l6[:t, :d]
    l9 = l9[:t, :d]

    n6 = l6 / (np.linalg.norm(l6, axis=1, keepdims=True) + EPS)
    n9 = l9 / (np.linalg.norm(l9, axis=1, keepdims=True) + EPS)

    cos_frame = np.sum(n6 * n9, axis=1)
    l2_frame = np.linalg.norm(l6 - l9, axis=1)

    hist_cos = _safe_hist(cos_frame, bins=24, low=-1.0, high=1.0)
    l2_hi = float(max(np.percentile(l2_frame, 99), 1e-3))
    hist_l2 = _safe_hist(l2_frame, bins=24, low=0.0, high=l2_hi)

    basis6 = _topk_pca_basis(l6, k=8)
    basis9 = _topk_pca_basis(l9, k=8)
    svals = np.linalg.svd(basis6.T @ basis9, compute_uv=False)
    pc_align = np.clip(svals, 0.0, 1.0).astype(np.float32)

    m6 = np.mean(l6, axis=1)
    m9 = np.mean(l9, axis=1)
    temporal_stats = np.concatenate(
        [
            _summarize_vector(cos_frame),
            _summarize_vector(l2_frame),
            _summarize_vector(m6),
            _summarize_vector(m9),
        ]
    ).astype(np.float32)

    m6z = (m6 - np.mean(m6)) / (np.std(m6) + EPS)
    m9z = (m9 - np.mean(m9)) / (np.std(m9) + EPS)
    xcorr_full = np.correlate(m6z, m9z, mode="full") / max(t, 1)
    mid = xcorr_full.shape[0] // 2
    xcorr_profile = xcorr_full[mid - 16 : mid + 16].astype(np.float32)
    if xcorr_profile.size < 32:
        xcorr_profile = np.pad(xcorr_profile, (0, 32 - xcorr_profile.size))

    feat = np.concatenate([hist_cos, hist_l2, pc_align, temporal_stats, xcorr_profile])
    if feat.size != 128:
        raise RuntimeError(f"WavLM feature vector size mismatch: {feat.size}")

    return np.nan_to_num(feat.astype(np.float32), nan=0.0, posinf=0.0, neginf=0.0)


def extract_wavlm_features(waveform, wavlm_model):
    import torch
    import numpy as np

    waveform_t = torch.tensor(waveform).unsqueeze(0).float()

    with torch.no_grad():
        out = wavlm_model(waveform_t, output_hidden_states=True)

    # Layer 9 is most discriminative for spoofing on ASVspoof 2019
    # Shape: (1, T, 768) where T ~ 99 frames for 2-second audio
    layer9 = out.hidden_states[9].squeeze(0).numpy()  # (T, 768)

    # Extract 4 statistics across time for each dimension
    mean  = layer9.mean(axis=0)   # (768,)
    std   = layer9.std(axis=0)    # (768,)
    
    # Reduce 768 dims to 32 each using uniform subsampling
    # Gives 32+32 = 64 dims total, then duplicate for 128
    idx = np.linspace(0, 767, 32, dtype=int)
    mean_r = mean[idx]   # (32,)
    std_r  = std[idx]    # (32,)
    
    # Also extract layer 6 mean for cross-layer comparison
    layer6 = out.hidden_states[6].squeeze(0).numpy()
    mean6  = layer6.mean(axis=0)[idx]   # (32,)
    std6   = layer6.std(axis=0)[idx]    # (32,)
    
    # Concatenate: [layer9_mean, layer9_std, layer6_mean, layer6_std]
    features = np.concatenate([mean_r, std_r, mean6, std6])  # (128,)
    
    return features.astype(np.float32)


class _DummyWavLMModel:
    """Fast deterministic stand-in used only for local verification runs."""

    def __init__(self, frames: int = 50, dim: int = 128):
        self.frames = frames
        self.dim = dim

    def __call__(self, x, output_hidden_states=True):
        if torch is not None and hasattr(x, "detach"):
            arr = x.squeeze(0).detach().cpu().numpy()
        else:
            arr = np.asarray(x, dtype=np.float32).reshape(-1)
        pad = max(0, self.frames * 4 - arr.shape[0])
        if pad > 0:
            arr = np.pad(arr, (0, pad))
        arr = arr[: self.frames * 4].reshape(self.frames, 4)

        seed = int(np.sum(np.abs(arr)) * 1e6) % (2**31 - 1)
        rng = np.random.default_rng(seed)

        base = rng.standard_normal((self.frames, self.dim), dtype=np.float32)
        layer6 = base + 0.03 * rng.standard_normal((self.frames, self.dim), dtype=np.float32)
        layer9 = base + 0.06 * rng.standard_normal((self.frames, self.dim), dtype=np.float32)

        hidden_states = []
        for i in range(10):
            if i == 6:
                h = layer6
            elif i == 9:
                h = layer9
            else:
                h = base + (0.01 * i) * rng.standard_normal((self.frames, self.dim), dtype=np.float32)
            if torch is not None:
                hidden_states.append(torch.from_numpy(h).unsqueeze(0))
            else:
                hidden_states.append(h[np.newaxis, ...])

        class Out:
            pass

        out = Out()
        out.hidden_states = tuple(hidden_states)
        return out


def _load_real_wavlm() -> Optional[object]:
    if torch is None:
        return None
    try:
        from transformers import WavLMModel

        model = WavLMModel.from_pretrained("microsoft/wavlm-base-plus")
        model.eval()
        return model
    except Exception as exc:  # pragma: no cover - runtime environment dependent
        warnings.warn(f"Falling back to dummy WavLM model: {exc}")
        return None


def _run_self_check(test_audio: Optional[str], use_real_wavlm: bool) -> FeatureRuntime:
    if test_audio:
        waveform = preprocess_audio(test_audio)
    else:
        t = np.arange(WINDOW_SAMPLES, dtype=np.float32) / SAMPLE_RATE
        waveform = (
            0.6 * np.sin(2 * np.pi * 180.0 * t)
            + 0.2 * np.sin(2 * np.pi * 360.0 * t + 0.7)
            + 0.03 * np.random.default_rng(7).standard_normal(WINDOW_SAMPLES)
        ).astype(np.float32)
        waveform = preprocess_audio(waveform)

    t0 = time.perf_counter()
    phase = extract_lfcc_features(waveform)
    t1 = time.perf_counter()

    glottal = extract_glottal_features(waveform)
    t2 = time.perf_counter()

    wavlm_model = None
    if use_real_wavlm:
        wavlm_model = _load_real_wavlm()
    if wavlm_model is None:
        wavlm_model = _DummyWavLMModel()

    wavlm = extract_wavlm_features(waveform, wavlm_model)
    t3 = time.perf_counter()

    phase_ms = (t1 - t0) * 1000.0
    glottal_ms = (t2 - t1) * 1000.0
    wavlm_ms = (t3 - t2) * 1000.0

    print(f"phase shape: {phase.shape}")
    print(f"glottal shape: {glottal.shape}")
    print(f"wavlm shape: {wavlm.shape}")

    print(f"phase has finite values: {bool(np.isfinite(phase).all())}")
    print(f"glottal has finite values: {bool(np.isfinite(glottal).all())}")
    print(f"wavlm has finite values: {bool(np.isfinite(wavlm).all())}")

    print(f"phase runtime ms: {phase_ms:.3f}")
    print(f"glottal runtime ms: {glottal_ms:.3f}")
    print(f"wavlm runtime ms: {wavlm_ms:.3f}")

    if use_real_wavlm:
        print("wavlm backend: real")
    else:
        print("wavlm backend: dummy")

    return FeatureRuntime(phase_ms=phase_ms, glottal_ms=glottal_ms, wavlm_ms=wavlm_ms)


def main() -> None:
    parser = argparse.ArgumentParser(description="Aegis-Zero feature extractor self-check")
    parser.add_argument("--audio", type=str, default=None, help="Optional path to test audio file")
    parser.add_argument(
        "--use-real-wavlm",
        action="store_true",
        help="Try loading microsoft/wavlm-base-plus from transformers",
    )
    args = parser.parse_args()

    _run_self_check(test_audio=args.audio, use_real_wavlm=args.use_real_wavlm)


if __name__ == "__main__":
    main()
