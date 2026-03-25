import numpy as np
import torch
from transformers import WavLMModel
from tqdm import tqdm
import os
import sys
sys.path.insert(0, '.')
from feature_extractors import preprocess_audio

print("Loading WavLM teacher...")
wavlm = WavLMModel.from_pretrained('microsoft/wavlm-base-plus')
wavlm.eval()
print("Teacher ready")

def extract_wavlm_raw(waveform):
    wt = torch.tensor(waveform).unsqueeze(0).float()
    with torch.no_grad():
        out = wavlm(wt, output_hidden_states=True)
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

# Process all training files — store raw waveforms + WavLM targets
proto_train = 'Dataset/LA/ASVspoof2019_LA_cm_protocols/ASVspoof2019.LA.cm.train.trn.txt'
proto_dev   = 'Dataset/LA/ASVspoof2019_LA_cm_protocols/ASVspoof2019.LA.cm.dev.trl.txt'
train_dir   = 'Dataset/LA/ASVspoof2019_LA_train/flac'
dev_dir     = 'Dataset/LA/ASVspoof2019_LA_dev/flac'

os.makedirs('dataset/distill', exist_ok=True)

for split, proto, audio_dir in [
    ('train', proto_train, train_dir),
    ('dev',   proto_dev,   dev_dir)
]:
    print(f"\nProcessing {split} split...")
    
    entries = []
    with open(proto) as f:
        for line in f:
            parts = line.strip().split()
            entries.append(parts[1])

    waveforms = []
    wavlm_targets = []
    skipped = 0

    for file_id in tqdm(entries, desc=split):
        path = os.path.join(audio_dir, file_id + '.flac')
        try:
            wave = preprocess_audio(path)
            target = extract_wavlm_raw(wave)
            waveforms.append(wave)
            wavlm_targets.append(target)
        except Exception as e:
            skipped += 1

    waveforms_arr = np.array(waveforms, dtype=np.float32)
    targets_arr   = np.array(wavlm_targets, dtype=np.float32)

    np.save(f'dataset/distill/{split}_waveforms.npy', waveforms_arr)
    np.save(f'dataset/distill/{split}_wavlm_targets.npy', targets_arr)

    print(f"{split}: waveforms={waveforms_arr.shape} targets={targets_arr.shape} skipped={skipped}")

print("\nDistillation data ready in dataset/distill/")
