import os
os.environ['TF_USE_LEGACY_KERAS'] = '1'
import numpy as np
import librosa
from data_pipeline import load_protocol, get_dataset

base_dir = '/Users/dakshrathore/Desktop/Hackaccino/ASVspoof2021_LA'

# Check training data
print("=== TRAINING SET ANALYSIS ===")
train_protocol_path = os.path.join(base_dir, 'keys', 'ASVspoof2021.LA.cm.trn.txt')
train_files, train_labels = load_protocol(train_protocol_path)
bonafide_count = train_labels.count(0)
spoof_count = train_labels.count(1)
print(f"Total samples: {len(train_labels)}")
print(f"Bonafide: {bonafide_count}, Spoof: {spoof_count}")
print(f"Ratio: {bonafide_count}:{spoof_count}")

# Check first few
print("\nFirst 10 training samples:")
for i in range(min(10, len(train_files))):
    label_text = "BONAFIDE" if train_labels[i] == 0 else "SPOOF"
    print(f"  {train_files[i]} -> {label_text}")

# Check dev data
print("\n=== DEV SET ANALYSIS ===")
dev_protocol_path = os.path.join(base_dir, 'keys', 'ASVspoof2021.LA.cm.dev.txt')
dev_files, dev_labels = load_protocol(dev_protocol_path)
bonafide_dev_count = dev_labels.count(0)
spoof_dev_count = dev_labels.count(1)
print(f"Total samples: {len(dev_labels)}")
print(f"Bonafide: {bonafide_dev_count}, Spoof: {spoof_dev_count}")
print(f"Ratio: {bonafide_dev_count}:{spoof_dev_count}")

# Check for overlap between train and dev
overlap_list = []
for filename in train_files:
    if filename in dev_files:
        overlap_list.append(filename)

if len(overlap_list) == 0:
    print("\nGood: No overlap between train and dev!")
else:
    print(f"\nERROR: {len(overlap_list)} samples overlap between train and dev:")
    for f in overlap_list[:5]:
        print(f"  {f}")
    if len(overlap_list) > 5:
        print(f"  ... and {len(overlap_list) - 5} more")

# Verify actual batch data
print("\n=== ACTUAL BATCH VERIFICATION ===")
train_ds = get_dataset('trn', batch_size=32)
first_batch_x, first_batch_y = next(iter(train_ds))
print(f"Batch shape: X {first_batch_x.shape}, Y {first_batch_y.shape}")
print(f"Batch labels: {first_batch_y.numpy().flatten()}")
print(f"Batch label sum: {first_batch_y.numpy().sum()} (expected ~16 for balanced)")
print(f"Batch label mean: {first_batch_y.numpy().mean():.4f} (expected 0.5 for balanced)")

# Check if feature extraction is working
print(f"\nFeature stats:")
print(f"  X mean: {first_batch_x.numpy().mean():.6f}")
print(f"  X std: {first_batch_x.numpy().std():.6f}")
print(f"  X min: {first_batch_x.numpy().min():.6f}")
print(f"  X max: {first_batch_x.numpy().max():.6f}")

# Get another batch from dev set
print("\n=== DEV SET BATCH VERIFICATION ===")
dev_ds = get_dataset('dev', batch_size=32)
dev_batch_x, dev_batch_y = next(iter(dev_ds))
print(f"Dev batch shape: X {dev_batch_x.shape}, Y {dev_batch_y.shape}")
print(f"Dev batch labels: {dev_batch_y.numpy().flatten()}")
print(f"Dev batch label mean: {dev_batch_y.numpy().mean():.4f} (expected 0.5 for balanced)")

# Check if dev and train features look similar or different
print(f"\nFeature stats comparison:")
print(f"Train X mean: {first_batch_x.numpy().mean():.6f}")
print(f"Dev X mean: {dev_batch_x.numpy().mean():.6f}")
print(f"Train X std: {first_batch_x.numpy().std():.6f}")
print(f"Dev X std: {dev_batch_x.numpy().std():.6f}")

