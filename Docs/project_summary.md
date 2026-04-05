# Intent Firewall - Project Summary

## Overview
**Intent Firewall** (also referred to internally as **Aegis-Zero**) is a complete mobile application built with React Native and native Android components to detect, intercept, and classify malicious intents such as scams, phishing links, and deepfakes. It actively listens to notifications on the device and leverages a multi-tiered artificial intelligence (AI) pipeline to determine threat scores in real time.

## System Architecture

The project is structured into three main ecosystems:

### 1. User Interface (React Native Frontend)
- **Framework**: Built with React Native and TypeScript (`App.tsx`, `src/screens`).
- **Screens**: 
  - `HomeScreen`: Displays general system status and analytics.
  - `HistoryScreen`: Logs intercepted intents and their classification history.
  - `SettingsScreen`: Allows the user to configure app protections.
  - `WarningScreen`: Alert view presented to the user when a high-risk notification or deepfake is detected.
- **Role**: Provides a modern, responsive user experience to configure the firewall and review blocked or flagged activities safely.

### 2. Notification Interception (Android Native Layer)
- **NotificationListenerService (`NotificationService.kt`)**: Binds natively to Android OS to listen and capture incoming notifications in the background. It focuses on communication apps such as WhatsApp, Telegram, and SMS.
- **Multi-Tier Classification Pipeline**:
  - **Tier 1 (Regex Sentinel)**: Performs near-instant (~0.1ms latency) pattern matching to flag basic scam criteria.
  - **Tier 2 (DistilBERT)**: Evaluates conversation text leveraging natural language processing heuristics.
  - **Tier 3 (AI Flagging)**: Uses confidence scores and hidden states from the prior tiers to run final confirmation algorithms.
- **EventEmitter**: Threat results and contextual data are sent back into the React Native layer via React Native bridges/emitters for user action.

### 3. Machine Learning Platform (Python Training Pipeline)
The repository features an advanced Python-based Deepfake Voice / Pattern detection training and evaluation setup (`models.py`, `train_detectors.py`, `export_tflite_v2.py`). 
- **Audio & Detection Models**:
  - **PhaseCoherenceDetector**: Analyzes phase coherence across 64-dimensional feature inputs.
  - **GlottalDetector**: Analyzes 12-dimensional glottal characteristics.
  - **WavLMDetector**: A linear probe built over high-dimensional voice transformer embeddings (128-dim).
  - **Fusion Ensemble Model**: Concatenates and multiplies the outputs of the smaller detectors to make a highly accurate final prediction on voice spoofing or scams.
- **On-Device Exporting (`export_tflite_v2.py`)**: Uses the TensorFlow Lite converter to quantize and package these models into `INT8` `.tflite` files suitable for lightweight, edge execution on Android.

## Workflow
1. A message or voice piece is received on the phone.
2. The `NotificationService` captures the text/metrics and maintains a `ContextBuffer` of the conversation.
3. The content is passed synchronously through the local Tier 1 (Regex), Tier 2 (NLP), and Tier 3 (TFLite Models) processes.
4. If the intent crosses the `isScam` threshold or gets flagged, React Native intercepts the UI flow to warn the user, effectively acting as an "Intent Firewall".
