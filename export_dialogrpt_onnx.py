"""
# Tier 2 ONNX Export — DistilBERT scam classifier
# Exports fine-tuned model to INT8 ONNX for Android
# Run after fine_tune_dialogrpt.py completes
"""

import pathlib

import numpy as np
import onnx
import onnxruntime
import torch
import transformers


BASE_DIR = pathlib.Path.home() / "Desktop/Hackaccino"
MODEL_DIR = BASE_DIR / "models/dialogrpt/final"
ONNX_DIR = BASE_DIR / "models/dialogrpt/onnx"
ONNX_PATH = ONNX_DIR / "scam_classifier.onnx"
QUANT_PATH = ONNX_DIR / "scam_classifier_int8.onnx"
MAX_LENGTH = 256


def softmax(logits):
    shifted = logits - np.max(logits)
    exps = np.exp(shifted)
    return exps / np.sum(exps)


def main():
    ONNX_DIR.mkdir(parents=True, exist_ok=True)

    print(f"Loading tokenizer and model from: {MODEL_DIR}")
    tokenizer = transformers.AutoTokenizer.from_pretrained(str(MODEL_DIR))
    model = transformers.AutoModelForSequenceClassification.from_pretrained(str(MODEL_DIR))
    model.eval()

    dummy_text = "Your account has been blocked. Share OTP immediately."
    inputs = tokenizer(
        dummy_text,
        return_tensors="pt",
        max_length=MAX_LENGTH,
        padding="max_length",
        truncation=True,
    )

    with torch.no_grad():
        torch.onnx.export(
            model,
            (inputs["input_ids"], inputs["attention_mask"]),
            str(ONNX_PATH),
            input_names=["input_ids", "attention_mask"],
            output_names=["logits"],
            dynamic_axes={
                "input_ids": {0: "batch_size", 1: "sequence"},
                "attention_mask": {0: "batch_size", 1: "sequence"},
                "logits": {0: "batch_size"},
            },
            opset_version=14,
        )

    onnx_model = onnx.load(str(ONNX_PATH))
    onnx.checker.check_model(onnx_model)

    onnx_size_mb = ONNX_PATH.stat().st_size / (1024 * 1024)
    print(f"ONNX model size: {onnx_size_mb:.2f} MB")

    quantization_mod = getattr(onnxruntime, "quantization", None)
    if quantization_mod is None:
        quantization_mod = __import__("onnxruntime.quantization", fromlist=["quantize_dynamic", "QuantType"])

    quantization_mod.quantize_dynamic(
        model_input=str(ONNX_PATH),
        model_output=str(QUANT_PATH),
        weight_type=quantization_mod.QuantType.QInt8,
    )

    quant_size_mb = QUANT_PATH.stat().st_size / (1024 * 1024)
    reduction_pct = 100.0 * (onnx_size_mb - quant_size_mb) / max(onnx_size_mb, 1e-9)
    print(f"Quantized model size: {quant_size_mb:.2f} MB")
    print(f"Size reduction: {reduction_pct:.2f}%")

    session = onnxruntime.InferenceSession(str(QUANT_PATH), providers=["CPUExecutionProvider"])

    test_sentences = [
        "Your OTP is 482910. Share with agent to avoid suspension.",
        "Your order #ORD123456 has been shipped. Expected Monday.",
        "URGENT: Send ₹5000 to upi@paytm or account blocked.",
    ]

    for text in test_sentences:
        encoded = tokenizer(
            text,
            return_tensors="np",
            max_length=MAX_LENGTH,
            padding="max_length",
            truncation=True,
        )
        ort_inputs = {
            "input_ids": encoded["input_ids"].astype(np.int64),
            "attention_mask": encoded["attention_mask"].astype(np.int64),
        }
        logits = session.run(["logits"], ort_inputs)[0][0]
        probs = softmax(logits)

        pred_idx = int(np.argmax(probs))
        label = "SCAM" if pred_idx == 1 else "BENIGN"
        confidence = float(probs[pred_idx] * 100.0)

        print(f"Text: {text}")
        print(f"Predicted: {label}")
        print(f"Confidence: {confidence:.1f}%")


if __name__ == "__main__":
    main()
