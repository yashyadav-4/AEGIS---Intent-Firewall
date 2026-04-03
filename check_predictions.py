import os
os.environ['TF_USE_LEGACY_KERAS'] = '1'
import tensorflow as tf
import numpy as np
from data_pipeline import get_dataset, load_protocol
from model import build_aegis_model

# Load model
model, _ = build_aegis_model()
model.load_weights('aegis_audio_tflite_ready.weights.h5')

# Get dev set predictions
dev_ds = get_dataset('dev', batch_size=32)
predictions = []
labels = []

for batch_x, batch_y in dev_ds:
    preds = model.predict(batch_x, verbose=0)
    predictions.extend(preds.flatten())
    labels.extend(batch_y.numpy().flatten())

predictions = np.array(predictions)
labels = np.array(labels)

print("Prediction stats:")
print(f"  Mean: {predictions.mean():.6f}")
print(f"  Std: {predictions.std():.6f}")
print(f"  Min: {predictions.min():.6f}")
print(f"  Max: {predictions.max():.6f}")
print(f"  Range: {predictions.max() - predictions.min():.6f}")
print("\nSample predictions (first 20):")
print(predictions[:20])
print("\nCorresponding labels (first 20):")
print(labels[:20])

# Check if model actually distinguishes bonafide vs spoof
bonafide_preds = predictions[labels == 0]
spoof_preds = predictions[labels == 1]

print(f"\nBonafide predictions: mean={bonafide_preds.mean():.6f}, std={bonafide_preds.std():.6f}")
print(f"Spoof predictions: mean={spoof_preds.mean():.6f}, std={spoof_preds.std():.6f}")
print(f"Difference: {abs(bonafide_preds.mean() - spoof_preds.mean()):.6f}")
