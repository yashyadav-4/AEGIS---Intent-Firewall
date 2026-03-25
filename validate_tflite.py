import os
os.environ['TF_USE_LEGACY_KERAS'] = '1'

import tensorflow as tf
import numpy as np
from data_pipeline import get_dataset
from model import build_aegis_model

def compare_tflite_to_keras():
    print("Loading Keras Model...")
    keras_model, _ = build_aegis_model()
    keras_model.load_weights('aegis_audio_tflite_ready.weights.h5')
    
    print("Loading TFLite Model...")
    interpreter = tf.lite.Interpreter(model_path="aegis_audio.tflite")
    interpreter.allocate_tensors()
    
    in_idx = interpreter.get_input_details()[0]['index']
    out_idx = interpreter.get_output_details()[0]['index']
    
    # Needs scaling parameters since input/output is INT8
    input_scale, input_zero_point = interpreter.get_input_details()[0]['quantization']
    output_scale, output_zero_point = interpreter.get_output_details()[0]['quantization']
    
    print(f"I/O Scales: Input({input_scale}, {input_zero_point}) Output({output_scale}, {output_zero_point})")
    
    eval_ds = get_dataset('eval', batch_size=1)
    
    print("\n--- Comparing 10 samples ---")
    mse_accum = 0.0
    
    for i, (feature, label) in enumerate(eval_ds.take(10)):
        # Keras Prediction (Float32)
        keras_pred = keras_model.predict(feature, verbose=0)[0][0]
        
        # TFlite Prediction (INT8 -> Float32 cast)
        # Quantize the input explicitly to int8 bounds
        feature_q = feature / input_scale + input_zero_point
        feature_q = np.clip(feature_q, -128, 127).astype(np.int8)
        
        interpreter.set_tensor(in_idx, feature_q)
        interpreter.invoke()
        tflite_out_q = interpreter.get_tensor(out_idx)[0][0]
        
        # Dequantize to float32
        tflite_pred = (tflite_out_q - output_zero_point) * output_scale
        
        diff = abs(keras_pred - tflite_pred)
        mse_accum += diff**2
        print(f"Sample {i+1} | Label: {label.numpy()[0]} | Keras: {keras_pred:.4f} | TFLite: {tflite_pred:.4f} | Diff: {diff:.4f}")
        
    print(f"\nMean Squared Error over 10 samples: {mse_accum / 10:.6f}")

if __name__ == "__main__":
    compare_tflite_to_keras()
