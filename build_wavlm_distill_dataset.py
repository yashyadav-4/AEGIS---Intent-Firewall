#!/usr/bin/env python3
import os
import numpy as np
import multiprocessing as mp
from tqdm import tqdm
from transformers import WavLMModel
import torch

def preprocess_audio(path):
    import soundfile as sf
    import numpy as np
    audio, sr = sf.read(path, dtype='float32')
    if len(audio.shape) > 1:
        audio = audio.mean(axis=1)
    target = 32000
    if len(audio) < target:
        audio = np.pad(audio, (0, target - len(audio)))
    else:
        audio = audio[:target]
    max_abs = np.abs(audio).max()
    if max_abs > 1e-8:
        audio = audio / max_abs
    return audio.astype(np.float32)

def extract_wavlm_features(waveform, wavlm_model):
    import torch
    import numpy as np
    wt = torch.tensor(waveform).unsqueeze(0).float()
    with torch.no_grad():
        out = wavlm_model(wt, output_hidden_states=True)
    layer9 = out.hidden_states[9].squeeze(0).numpy()
    layer6 = out.hidden_states[6].squeeze(0).numpy()
    idx = np.linspace(0, 767, 32, dtype=int)
    features = np.concatenate([
        layer9.mean(axis=0)[idx],
        layer9.std(axis=0)[idx],
        layer6.mean(axis=0)[idx],
        layer6.std(axis=0)[idx]
    ])
    return features.astype(np.float32)

def parse_protocol(protocol_file, flac_dir):
    tasks = []
    with open(protocol_file, 'r') as f:
        for line in f:
            parts = line.strip().split()
            if len(parts) >= 5:
                file_id = parts[1]
                path = os.path.join(flac_dir, f"{file_id}.flac")
                if os.path.exists(path):
                    tasks.append(path)
    return tasks

def process_split(tasks, wavlm_model, desc):
    waveforms = []
    teacher_features = []
    skipped = 0
    
    for path in tqdm(tasks, desc=desc):
        try:
            waveform = preprocess_audio(path)
            feats = extract_wavlm_features(waveform, wavlm_model)
            waveforms.append(waveform)
            teacher_features.append(feats)
        except Exception:
            skipped += 1
            
    return (np.array(waveforms, dtype=np.float32),
            np.array(teacher_features, dtype=np.float32),
            skipped)

def save_numpy(dataset_dir, prefix, waveforms, teacher_features):
    np.save(os.path.join(dataset_dir, f"{prefix}_waveforms.npy"), waveforms)
    np.save(os.path.join(dataset_dir, f"{prefix}_wavlm_teacher.npy"), teacher_features)

if __name__ == '__main__':
    mp.set_start_method('spawn')
    
    BASE_DIR = os.path.expanduser("~/Desktop/Hackaccino/Dataset/LA")
    TRAIN_DIR = os.path.join(BASE_DIR, "ASVspoof2019_LA_train/flac")
    DEV_DIR = os.path.join(BASE_DIR, "ASVspoof2019_LA_dev/flac")
    TRAIN_PROT = os.path.join(BASE_DIR, "ASVspoof2019_LA_cm_protocols/ASVspoof2019.LA.cm.train.trn.txt")
    DEV_PROT = os.path.join(BASE_DIR, "ASVspoof2019_LA_cm_protocols/ASVspoof2019.LA.cm.dev.trl.txt")
    
    DATASET_OUT = os.path.expanduser("~/Desktop/Hackaccino/dataset/distill")
    os.makedirs(DATASET_OUT, exist_ok=True)
    
    print("[1] Loading WavLM Teacher Model (CPU)...")
    wavlm_model = WavLMModel.from_pretrained('microsoft/wavlm-base-plus')
    wavlm_model.eval()

    print("[2] Parsing protocols...")
    train_tasks = parse_protocol(TRAIN_PROT, TRAIN_DIR)
    dev_tasks = parse_protocol(DEV_PROT, DEV_DIR)
    print(f"Found {len(train_tasks)} train files, {len(dev_tasks)} dev files")
    
    print("\n[3] Processing Train Split...")
    t_w, t_f, t_skipped = process_split(train_tasks, wavlm_model, "Train Distill")
    
    print("\n[4] Processing Dev Split...")
    d_w, d_f, d_skipped = process_split(dev_tasks, wavlm_model, "Dev Distill")
    
    print("[5] Saving arrays to dataset/distill/...")
    save_numpy(DATASET_OUT, "train", t_w, t_f)
    save_numpy(DATASET_OUT, "dev", d_w, d_f)
    
    print("\n" + "="*50)
    print("FINISHED BUILD_WAVLM_DISTILL_DATASET")
    print("="*50)
    print(f"Train waveforms: {t_w.shape}, Train teacher targets: {t_f.shape}")
    print(f"Train skipped: {t_skipped}")
    print("-" * 50)
    print(f"Dev waveforms: {d_w.shape}, Dev teacher targets: {d_f.shape}")
    print(f"Dev skipped: {d_skipped}")
