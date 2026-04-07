# Hackaccino Project Context (Full Technical Dossier)

## 1) Document Purpose
This file is a complete engineering context reference for the Hackaccino workspace. It is intended to answer:
- What the project is trying to do (vision and product intent)
- How the application works end to end
- What each runtime layer does (React Native, Android native, ML pipeline, server)
- How major functions behave and how data moves across components
- What is currently implemented versus placeholder/stale code
- How to run, test, and troubleshoot the system

This document reflects the repository state in the current workspace snapshot.

---

## 2) Vision and Product Intent
Project identity:
- Product name in app runtime: IntentFirewall (Android app label currently IntentFirewall V2)
- Security theme: On-device scam and deepfake defense
- Architectural concept in code/comments: Aegis-Zero, multi-tier detection

Core intent:
1. Protect users from social engineering/scam messages in messaging notifications.
2. Detect synthetic/deepfake call audio using lightweight on-device ML.
3. Keep sensitive inference local on device where possible.
4. Optionally federate risk signals through a privacy-preserving server (Traverse server).

Defense tiers represented in code:
- Tier 1: Regex sentinel and keyword/heuristic detection.
- Tier 2: Distilled text classifier (Android native placeholder class, plus Python DistilBERT training/export pipeline).
- Tier 3: Additional classifier stage (Android native placeholder class, plus Python distillation training/export scripts).
- Audio deepfake path: TFLite models + signal features (LFCC, glottal, WavLM-student pathway).

---

## 3) Workspace Topology (High-Level)
Top-level major areas:
- React Native app shell: App.tsx, index.js, src/
- Android native implementation: android/app/src/main/java/com/intentfirewall/
- iOS shell: ios/IntentFirewall/
- Python ML pipeline scripts: root *.py files
- Pretrained/exported assets: android/app/src/main/assets/
- Data folders: dataset/, modern_attacks/, evaluation/
- DialogRPT artifacts: models/dialogrpt/
- External/vendor research code: TCN/, google-research/, models/research/

Current quick counts:
- TS/TSX files under src/: 9
- Kotlin files under android/app/src/main/java/com/intentfirewall: 10
- Root Python scripts: 20

---

## 4) Runtime Architecture Overview

### 4.1 Mobile app composition
- Entry point: index.js registers React root component App.
- App.tsx returns AppNavigator.
- Navigation stack routes: Home, Warning, History, Settings.
- Persistent local storage via AsyncStorage for settings and threat history.

### 4.2 Android native text pipeline
NotificationService listens to posted notifications, then:
1. Filters tracked apps (WhatsApp, Telegram, SMS providers).
2. Pushes incoming text into ContextBuffer per package.
3. Runs Tier 1 RegexSentinel.
4. Runs Tier 2 classifier (current implementation is keyword-based heuristic class).
5. Conditionally runs Tier 3 classifier (current implementation computes confidence from proxy vector mean).
6. Combines final decision and emits event to RN through NotificationEventEmitter event onNotification.

### 4.3 Android native audio deepfake pipeline
AegisCallMonitor foreground service:
1. Hooks phone state transitions.
2. Starts microphone capture during active call (off-hook).
3. Buffers overlapping 2-second windows.
4. Computes pitch variance trigger signal.
5. If trigger conditions are met, runs AegisAudioDetector analyze.
6. Sends deepfake-detected local broadcast when synthetic confidence is high.

AegisAudioDetector:
- Loads TFLite models and scaler JSON files from assets.
- Extracts LFCC and glottal handcrafted features.
- Uses WavLM student TFLite model to generate compact 128-d features.
- Scores phase, glottal, wavlm probe, then ensemble fusion.

### 4.4 Python ML pipeline
Python scripts implement dataset building, training, evaluation, and export:
- Feature extraction and preprocess functions
- Detector model definitions and staged training
- Distillation workflows for compact student models
- ONNX and TFLite export scripts
- Modern attack evaluation script

### 4.5 Federated reputation server (optional component)
traverse_server.py provides:
- HMAC-validated submissions
- Epoch-partitioned Bloom filters
- Differential privacy noise on labels (Laplace)
- Device consensus gating and basic reputation scoring

---

## 5) React Native Layer (Detailed)

### 5.1 App wiring
Files:
- App.tsx
- index.js
- src/navigation/AppNavigator.tsx
- src/navigation/types.ts

Behavior:
- App.tsx only mounts AppNavigator.
- AppNavigator wraps stack navigation with four screens.
- Warning route accepts category, confidence, message, app, optional threatId.
- Warning wrapper handles block action by calling updateThreatBlocked(threatId, true) and navigating back.

### 5.2 Home screen behavior
File: src/screens/HomeScreen.tsx

State:
- messageProtection, callProtection
- recentThreats
- isProtected

Primary flows:
1. On mount, load saved settings and recent threats.
2. Subscribe to native onNotification event via global `DeviceEventEmitter` with strict cleanup.
3. On notification event:
   - Read settings.
   - Skip if messageProtection disabled.
   - Combine native flag and JS detector result.
   - Save threat in storage.
   - If not auto-block, navigate to Warning with threatId.
4. Manual dev test path:
   - simulateAttack creates mock WhatsApp scam message.
   - Runs detectScam.
   - Saves threat and navigates Warning with threatId.
   - Rendered only in __DEV__ mode.

### 5.3 Warning screen behavior
File: src/screens/WarningScreen.tsx

Functionality:
- Visual warning UI with mount animations (cleanly unmounted by capturing `Animated.loop`).
- Shows category and confidence.
- Buttons:
  - BLOCK SENDER (delegated callback)
  - CALL TRUSTED PERSON (delegated callback)
  - Dismiss (delegated callback)

Note:
- Actual block-state persistence is handled by WarningScreenWrapper in AppNavigator.

### 5.4 History screen behavior
File: src/screens/HistoryScreen.tsx

Functionality:
- Loads threats on focus.
- Filter tabs: All, WhatsApp, SMS, Calls.
- Shows threat category and blocked/allowed status.
- Can clear all history.

### 5.5 Settings screen behavior
File: src/screens/SettingsScreen.tsx

Functionality:
- Loads and persists user preferences:
  - autoBlock
  - strictMode
  - notifications
  - vibration
- Displays permission snapshot status chips.
- Requests runtime permissions.
- Opens notification listener settings and app settings pages.
- Includes clear history action.

### 5.6 RN utilities

#### storage.ts
Data model:
- Threat: id, app, appIcon, message, category, confidence, blocked, time, timestamp
- Settings: autoBlock, strictMode, notifications, vibration, messageProtection, callProtection

Functions:
- saveThreat: prepend new threat record to stored list
- getThreats: read all threats
- clearThreats: remove threats key
- updateThreatBlocked: update blocked field by threat id
- saveSettings: merge-partial update for settings
- getSettings: read with defaults fallback
- formatTime: helper formatter based on timestamp age

#### scamDetector.ts
Keyword weighted detector:
- Category dictionaries: urgency/financial, OTP, authority, impersonation, financial fraud
- detectScam:
  - lowercases input
  - accumulates score by keyword groups with fixed weights
  - confidence = min(score, 100)
  - isScam threshold = confidence >= 25
  - category resolved by matched category precedence
- getCategory precedence:
  OTP -> Authority -> Urgency/Financial -> Impersonation -> Financial Fraud -> Suspicious

#### permissionManager.ts
Functions:
- getRuntimePermissionList: Android permissions list with API-level handling for POST_NOTIFICATIONS
- getPermissionSnapshot: checks granted status
- requestRequiredPermissions: request multiple permissions
- openNotificationAccessSettings: Android listener settings intent fallback to app settings
- openAppPermissionSettings: open app settings

---

## 6) Android Native Layer (Detailed)

### 6.1 Build and manifest
Files:
- android/app/build.gradle
- android/build.gradle
- android/gradle.properties
- android/app/src/main/AndroidManifest.xml

Notable configuration:
- compileSdk 36, targetSdk 36, minSdk 24
- Hermes enabled
- TensorFlow Lite, TFLite GPU, ONNX Runtime, JTransforms included
- Notification listener service declared
- Foreground microphone service declared

### 6.2 Main app lifecycle
Files:
- MainApplication.kt
- MainActivity.kt

Behavior:
- Standard React Native host setup.
- Main component name: IntentFirewall.

### 6.3 Notification event bridge
File: NotificationEventEmitter.kt

Function:
- sendNotification:
  - obtains current React context
  - builds parameter map with app/message metadata and classification fields
  - emits event name onNotification via RCTDeviceEventEmitter

### 6.4 Text notification processing pipeline
File: NotificationService.kt

Functionality sequence:
1. onNotificationPosted captures package, title, text.
2. Filters supported packages.
3. Adds message to ContextBuffer.
4. Tier 1: RegexSentinel.analyze(text).
5. Tier 2: Tier2Classifier.analyze(context or text).
6. Tier 3 conditional escalate when tier1 or tier2 positive:
   - Tier3Classifier.analyze inference
7. finalFlagged is OR of tier outputs.
8. Emits payload to RN via NotificationEventEmitter.
9. Models are safely initialized via `lazy` properties and disposed in `onDestroy()` to prevent memory leaks.

Category resolution sent to RN:
- AI_CONFIRMED if tier3 flagged.
- tier2 label if tier2 positive.
- tier1 category if tier1 positive.

### 6.5 Tier 1 regex sentinel
File: RegexSentinel.kt

- Precompiled regex categories include OTP_HARVEST, FINANCIAL_PRESSURE, ACCOUNT_SCARE, PHISHING_LINK, GIFT_CARD_SCAM, IMPERSONATION.
- analyze(text) returns first matched category/pattern and flagged status.

### 6.6 Tier 2 classifier
File: Tier2Classifier.kt

Current status:
- ONNX DistilBERT model integration via `OrtSession`.
- Uses safe try/catch blocks dropping back to `isScam = false` on failure to prevent crashes.
- Safely instantiates and manages `OrtEnvironment`.

### 6.7 Tier 3 classifier
File: Tier3Classifier.kt

Current status:
- TFLite Interpreter integration for vector evaluation.
- Uses safe try/catch fallback dropping back to `isScam = false`.
- Manages `Interpreter` lifecycle safely.

### 6.8 Context buffer
File: ContextBuffer.kt

Behavior:
- Maintains up to 8 turns per package.
- addTurn appends speaker-tagged lines.
- getContext returns newline-joined turn history.

### 6.9 Audio call monitor service
File: AegisCallMonitor.kt

Responsibilities:
- Foreground service management and notification channel.
- Phone state listener registration.
- Starts/stops AudioRecord for off-hook calls.
- Buffers 2-second windows with overlap.
- Computes energy-variance proxy for pitch flatness.
- Triggers AegisAudioDetector when gating condition true.
- Broadcasts deepfake detection details via LocalBroadcastManager.

### 6.10 Audio detector engine
File: AegisAudioDetector.kt

Core responsibilities:
1. Load model assets:
   - detector_phase.tflite
   - detector_glottal.tflite
   - wavlm_student.tflite
   - detector_wavlm.tflite
   - ensemble.tflite
2. Load scaler JSON files:
   - phase_scaler.json
   - glottal_scaler.json
   - wavlm_scaler.json
3. Quantized inference handling:
   - int8 input/output for detectors
   - dequantization and quantization utilities
4. Feature extraction:
   - LFCC 64-d
   - Glottal 12-d
   - Student-derived WavLM 128-d
5. Fusion:
   - [p,g,w,p*g,p*w,g*w] fed into ensemble
6. Output:
   - DetectionResult with confidence and per-branch scores

Activation gating states:
- metadataHeuristicFired set by onCallMetadataUpdate
- spectralEnergyFired set by onSpectralEnergyUpdate
- shouldActivate requires both true

### 6.11 Call Screening Service
File: CallScreeningService.kt

Responsibilities:
- Extends `android.telecom.CallScreeningService`.
- Hooks directly into native OS call screening (requires `READ_CALL_LOG` permission and manifest configuration).
- Designed to intercept unknown numbers: responds with `setDisallowCall(false)` to let safe calls through unless flagged by the ML pipeline.

---

## 7) Python ML Stack (Detailed)

## 7.1 Core model definitions
File: models.py

Models:
- PhaseCoherenceDetector: Conv1D over 64-d inputs.
- GlottalDetector: Dense network over 12-d inputs.
- WavLMDetector: Linear probe over 128-d inputs.
- EnsembleModel: input scores with pairwise products, dense fusion head.

Compile helper:
- _compile_binary_model uses Adam + BCE + accuracy + AUC.

Verification helper:
- _verify_models runs shape checks with random data.

### 7.2 Feature extraction
File: feature_extractors.py

Main APIs:
- preprocess_audio(path_or_array): mono 16k, normalized, fixed 32000 samples.
- extract_lfcc_features: returns 64-d feature vector.
- extract_glottal_features: returns 12-d GCI-derived stats.
- extract_wavlm_features: returns 128-d vector from hidden states.

Auxiliary methods include:
- F0 estimation
- GCI candidate extraction
- DFA alpha
- Approximate entropy
- Hist/stat builders
- Dummy WavLM self-check backend

### 7.3 Dataset build for detectors
File: build_dataset.py

Pipeline:
1. Parse ASVspoof protocol entries.
2. Parallel preprocess + phase + glottal extraction.
3. Sequential WavLM feature extraction.
4. Save arrays:
   - train_phase.npy, train_glottal.npy, train_wavlm.npy, train_labels.npy
   - dev_phase.npy, dev_glottal.npy, dev_wavlm.npy, dev_labels.npy
5. Fit and apply StandardScaler for glottal, phase, wavlm.
6. Save scalers as joblib files.

### 7.4 Detector training
File: train_detectors.py

Training phases:
1. Phase detector
2. Glottal detector
3. WavLM detector
4. Ensemble fusion model

Notable details:
- Uses focal loss for single-detector phases.
- Uses balanced tf.data sampling.
- Applies class weights.
- Saves best checkpoints to models/detectors/*.h5
- Computes EER for ensemble on dev set.

Expected outputs:
- models/detectors/phase_best.h5
- models/detectors/glottal_best.h5
- models/detectors/wavlm_best.h5
- models/detectors/ensemble_best.h5

### 7.5 Modern attack evaluation
File: evaluate_modern.py

Expected inputs:
- models/detectors/*.h5 files
- modern_attacks/synthetic_wav
- modern_attacks/bonafide

Behavior:
- Loads models.
- Extracts phase/glottal/wavlm features per file.
- Computes AUC, EER, Accuracy, Precision, Recall, F1.
- Writes per-file ensemble scores to evaluation/modern_attack_scores.txt.

### 7.6 TFLite export for detector stack
File: export_tflite_v2.py

Purpose:
- Convert trained detector and ensemble Keras models to int8 TFLite.
- Validate input/output dtypes and basic invocation.

Target exports:
- detector_phase.tflite
- detector_glottal.tflite
- detector_wavlm.tflite
- ensemble.tflite

### 7.7 WavLM distillation dataset and student
Files:
- build_wavlm_distill_dataset.py
- train_wavlm_student.py
- export_wavlm_student_tflite.py

Flow:
1. Build distillation dataset from ASVspoof audio and WavLM teacher outputs.
2. Train compact student network mapping waveform -> 128-d representation.
3. Export student to TFLite int8-in/float32-out and validate cosine similarity.

### 7.8 Tier 2 text model pipeline
Files:
- build_scam_dataset.py
- fine_tune_dialogrpt.py
- export_dialogrpt_onnx.py

Flow:
1. Build mixed scam/benign text dataset JSONL.
2. Fine-tune DistilBERT sequence classifier.
3. Export ONNX and quantized int8 ONNX model.

### 7.9 Tier 3 distillation/classifier pipeline
Files:
- build_tier3_distill_dataset.py
- train_tier3_classifier.py

Flow:
1. Extract DistilBERT CLS hidden states as 768-d features.
2. Train compact MLP binary classifier.
3. Export quantized tier3_classifier.tflite.

### 7.10 Additional legacy/diagnostic scripts
Files:
- check_predictions.py
- validate_tflite.py
- verify_data_split.py
- rebuild_lfcc.py

Some of these reference missing modules in current repo (see consistency section).

---

## 8) Traverse Server (Federated Reputation)
File: traverse_server.py

Provides:
- /submit endpoint:
  - HMAC verification
  - epoch validation
  - DP noise application
  - Bloom insertion and consensus bookkeeping
- /sync endpoint:
  - returns bloom filter hex and summary for epoch
- /health endpoint:
  - basic service stats

Important mechanics:
- Epoch window: 3600 seconds
- Consensus threshold: minimum 3 distinct devices
- Differential privacy epsilon: 1.0
- Bloom settings: capacity 10000, error_rate 0.01

---

## 9) Assets and Model Artifact State (Current Workspace)

Present in Android assets:
- detector_phase.tflite
- detector_glottal.tflite
- detector_wavlm.tflite
- ensemble.tflite
- wavlm_student.tflite
- tier3_classifier.tflite
- scam_classifier_int8.onnx
- scaler JSON files
- tokenizer.model

Present under models/dialogrpt:
- final/ with config, tokenizer, model.safetensors
- best_checkpoint/ with same artifact pattern
- onnx/ with scam_classifier.onnx and scam_classifier_int8.onnx

Not present currently:
- models/detectors directory with phase_best.h5, glottal_best.h5, wavlm_best.h5, ensemble_best.h5
- models/tflite folder at root

Data state observed:
- dataset contains only scam_dialogues.jsonl currently.
- modern_attacks/bonafide and modern_attacks/synthetic_wav are placeholders and empty.
- evaluation contains modern_attack_scores.txt.

Implication:
- Python deepfake evaluation and retraining scripts requiring ASVspoof arrays/models are not runnable without restoring expected data/model files.
- Android app can still run using already-packaged assets in android/app/src/main/assets.

---

## 10) End-to-End Functional Flows

### 10.1 Notification scam flow
1. External app posts notification.
2. NotificationService receives it.
3. Tier 1 regex scan.
4. Tier 2 heuristic scan.
5. Tier 3 conditional scan.
6. Event emitted to RN onNotification.
7. HomeScreen receives event, combines native and JS detector outputs.
8. Threat saved to AsyncStorage.
9. If not auto-block, Warning screen shown.
10. If BLOCK pressed, blocked state updated for that threat id.
11. History screen shows status as Blocked or Allowed.

### 10.2 Manual dev simulation flow
1. On Home screen in debug builds, press Simulate Attack.
2. Mock WhatsApp scam text analyzed by detectScam.
3. Threat persisted with blocked false.
4. Warning screen opened with threatId.
5. Block action updates stored blocked state through AppNavigator wrapper.

### 10.3 Call deepfake flow
1. Call enters off-hook state.
2. AegisCallMonitor starts microphone foreground capture.
3. Sliding windows extracted.
4. Pitch variance heuristic updated.
5. Activation requires both metadata and spectral gates.
6. AegisAudioDetector analyzes window through all model heads.
7. If synthetic confidence high, local broadcast emitted.

---

## 11) Function Inventory by Component
This section lists first-party functions and their responsibilities.

### 11.1 TypeScript/TSX (src)

#### src/navigation/AppNavigator.tsx
- WarningScreenWrapper(route, navigation): maps route params to WarningScreen handlers, performs blocked-state persistence when threatId exists.
- AppNavigator(): creates navigation container/stack.

#### src/screens/HomeScreen.tsx
- HomeScreen(): main home view component.
- load() inside mount effect: loads settings and threat preview.
- onNotification listener callback: processes native notifications and saves/navigates warnings.
- loadRecentThreats(): loads latest 3 threats.
- simulateAttack(): debug-only mocked scam injection path.
- handleMessageToggle(val): persists message protection setting.
- handleCallToggle(val): persists call protection setting.

#### src/screens/WarningScreen.tsx
- WarningScreen(props): warning UI + animations + action callbacks.

#### src/screens/HistoryScreen.tsx
- HistoryScreen(): history view component.
- loadThreats(): fetches threat list from storage.
- handleClearAll(): clears stored threat history.
- filtered expression: computes list by active tab.

#### src/screens/SettingsScreen.tsx
- SettingsScreen(): settings and permissions screen.
- refreshPermissions(): refresh runtime permission snapshot.
- loadSettings() in mount effect: initial state hydration.
- handleToggle(key, value, setter): shared state persistence helper.
- handleClearHistory(): clears threats.
- handleRequestPermissions(): requests required runtime permissions.
- statusChip(granted): returns granted/missing visual indicator.

#### src/utils/storage.ts
- saveThreat(threat): append new threat with id/timestamp.
- getThreats(): retrieve all saved threats.
- clearThreats(): remove threats key.
- updateThreatBlocked(id, blocked): update blocked flag by id.
- saveSettings(partial): merge and persist settings.
- getSettings(): read settings with defaults fallback.
- formatTime(timestamp): human-readable relative time formatting.

#### src/utils/scamDetector.ts
- detectScam(text): weighted keyword detector returning ScamResult.
- check(keywords, weight) inner helper: adds score/matches.
- getCategory(text, matched): category selection by priority.

#### src/utils/permissionManager.ts
- getRuntimePermissionList(): compute required Android runtime permissions.
- getPermissionSnapshot(): read grant status.
- requestRequiredPermissions(): request runtime permissions and return snapshot.
- openNotificationAccessSettings(): open notification listener settings.
- openAppPermissionSettings(): open app settings.

### 11.2 Kotlin native (android/app/src/main/java/com/intentfirewall)

#### NotificationService.kt
- onNotificationPosted(sbn): full text-scam native pipeline entrypoint.
- onNotificationRemoved(sbn): no-op currently.
- getAppName(packageName): package-to-human app mapping.

#### NotificationEventEmitter.kt
- sendNotification(...): bridge notification data to RN event bus.

#### RegexSentinel.kt
- analyze(text): Tier 1 regex matching and category assignment.

#### Tier2Classifier.kt
- analyze(text): heuristic suspicious-term confidence scoring.

#### Tier3Classifier.kt
- analyze(features): placeholder numeric confidence classifier.

#### ContextBuffer.kt
- addTurn(packageName, speaker, message): append bounded conversation context.
- getContext(packageName): get joined context string.

#### AegisCallMonitor.kt
- onCreate(): initialize detector, wake lock, call-state listener.
- onStartCommand(intent, flags, startId): start/stop command handling.
- onDestroy(): cleanup listeners and detector resources.
- onBind(intent): returns null (started service).
- createNotificationChannel(): setup foreground notification channel.
- buildNotification(): build ongoing service notification.
- startCapture(): start AudioRecord loop and inference scheduling.
- stopCapture(): stop capture and release resources.
- computePitchVariance(window): energy variance heuristic.
- onDetectionResult(result): handles detection result and local broadcast.

#### AegisAudioDetector.kt
Public API:
- onCallMetadataUpdate(isUnknownNumber, isVideoCall): metadata gate update.
- onSpectralEnergyUpdate(pitchVariance): spectral gate update.
- shouldActivate(): gate decision.
- analyze(waveform): full deepfake inference pipeline.
- close(): interpreter/delegate cleanup.

Internal helper groups include:
- Scaler loading/parsing/fallback
- Quantized run and (de)quantization
- Waveform normalization
- LFCC extraction and delta stats
- GCI/glottal statistical features
- DFA/entropy/shimmer computations
- Model file mapping and interpreter creation

#### MainApplication.kt
- onCreate(): initializes React native app loading.

#### MainActivity.kt
- getMainComponentName(): returns IntentFirewall.
- createReactActivityDelegate(): new architecture delegate.

### 11.3 Python scripts (root first-party)

#### build_dataset.py
- parse_protocol
- _finite_feature
- _phase_glottal_worker
- _ensure_dir
- _load_wavlm_model
- _write_skip_log
- _open_tmp_memmaps
- _build_split
- _print_array_info
- main

#### feature_extractors.py
- preprocess_audio
- _estimate_f0_autocorr
- extract_lfcc_features
- _gci_candidates_from_waveform
- _dfa_alpha
- _approx_entropy
- extract_glottal_features
- _topk_pca_basis
- _safe_hist
- _summarize_vector
- _build_wavlm_consistency_vector
- extract_wavlm_features
- _load_real_wavlm
- _run_self_check
- main

#### models.py
- _compile_binary_model
- build_phase_coherence_detector
- build_glottal_detector
- build_wavlm_detector
- build_ensemble_model
- build_all_models
- _verify_models

#### train_detectors.py
- focal_loss
- _load_array
- _validate_shapes
- _print_dataset_overview
- _train_single_detector
- _compute_eer
- make_balanced_tf_dataset
- main

#### evaluate_modern.py
- compute_eer
- compute_eer_threshold
- collect_test_files
- validate_models_exist
- compute_metrics
- main

#### export_tflite_v2.py
- _load_rep_data
- _representative_gen
- _verify_tflite_model
- export_model
- main

#### build_wavlm_distill_dataset.py
- extract_wavlm_teacher_raw
- _read_protocol_ids
- _load_waveform_safe
- _batched
- _validate_finite
- _process_split
- main

#### train_wavlm_student.py
- combined_loss
- build_wavlm_student
- make_dataset
- evaluate_dev_metrics
- main

#### export_wavlm_student_tflite.py
- resolve_existing_path
- ensure_wave_channel_dim
- representative_dataset_generator
- cosine_similarity
- main

#### build_scam_dataset.py
- normalize_text
- download_sms_zip
- parse_sms_spam_from_zip
- category_specs
- render_template
- generate_synthetic_single_turns
- build_scam_dialogue
- build_benign_dialogue
- generate_multi_turn_dialogues
- write_jsonl
- summarize
- main

#### fine_tune_dialogrpt.py
- load_records
- split_train_val
- compute_class_weights
- freeze_for_fast_finetune
- batch_iter
- evaluate
- main

#### export_dialogrpt_onnx.py
- softmax
- main

#### build_tier3_distill_dataset.py
- load_records
- batch_iter
- extract_features
- split_train_val
- main

#### train_tier3_classifier.py
- EpochPrinter.on_epoch_end
- load_data
- compute_class_weights
- build_model
- build_representative_dataset
- quantize_input_for_tflite
- main

#### traverse_server.py
- BloomFilter methods: __init__, _hashes, add, contains, to_bytes, size_bytes
- dataclass helper and generated __init__/__repr__
- EpochPartition.__init__
- get_current_epoch
- _uniform_minus_half_to_half
- add_laplace_noise
- verify_hmac
- compute_consensus
- _json_response
- _is_valid_simhash
- _parse_epoch
- submit route handler
- sync route handler
- health route handler

#### Additional diagnostics/legacy scripts
- rebuild_lfcc.py functions for phase-only rebuild
- distill_wavlm.py helper and split loop
- check_predictions.py evaluation helper
- validate_tflite.py comparison helper
- verify_data_split.py split diagnostics helper

---

## 12) Current Consistency, Gaps, and Risks

### 12.1 Data/model availability gaps
- models/detectors directory is missing, so evaluate_modern.py and some export scripts cannot run as-is.
- modern_attacks audio subfolders are empty placeholders.
- dataset lacks expected detector arrays train_phase.npy, train_glottal.npy, train_wavlm.npy, etc.

### 12.2 Script/reference inconsistencies
- distill_wavlm.py writes train_wavlm_targets.npy/dev_wavlm_targets.npy.
- train_wavlm_student.py expects train_wavlm_teacher.npy/dev_wavlm_teacher.npy.
- check_predictions.py, validate_tflite.py, verify_data_split.py import modules data_pipeline/model that do not exist in current root.
- Some scripts reference ASVspoof2021 paths while active pipeline references ASVspoof2019 LA layout.

### 12.3 Runtime implementation mismatch versus intended architecture
- (Resolved) Native Tier2Classifier and Tier3Classifier are now wired up to perform actual ONNX and TFLite model inference, safely managed with fallbacks.

### 12.4 Duplicate native code copies
- There are duplicate Kotlin files at android/AegisAudioDetector.kt and android/AegisCallMonitor.kt with package com.aegis.audio.
- Active app module uses android/app/src/main/java/com/intentfirewall package tree.
- Root-level duplicate files are likely historical or staging copies.

### 12.5 Security concerns
- Release keystore passwords and aliases are present in android/app/build.gradle and android/gradle.properties.
- These credentials should be treated as compromised and rotated.

---

## 13) How To Run (Operational Commands)

React Native app:
1. npm start
2. npm run android

Python environment:
- Preferred interpreter in this workspace:
  /Users/dakshrathore/Desktop/Hackaccino/.venv/bin/python

Deepfake detector data build and training path (when full data exists):
1. python build_dataset.py
2. python train_detectors.py
3. python export_tflite_v2.py
4. python evaluate_modern.py

Text pipeline path:
1. python build_scam_dataset.py
2. python fine_tune_dialogrpt.py
3. python export_dialogrpt_onnx.py

Tier3 distillation path:
1. python build_tier3_distill_dataset.py
2. python train_tier3_classifier.py

WavLM student path:
1. python build_wavlm_distill_dataset.py
2. python train_wavlm_student.py
3. python export_wavlm_student_tflite.py

Traverse server:
- python traverse_server.py
- Optional env: TRAVERSE_HMAC_SECRET

---

## 14) Testing and Validation Status in Repo

Current automated test file:
- __tests__/App.test.tsx

What it does:
- Smoke-renders App while mocking AppNavigator.

Implication:
- Test coverage for business logic, native bridges, and ML flows is minimal in current JS test suite.

---

## 15) Build/Toolchain Summary

Node/React Native stack:
- react-native 0.84.1
- react 19.2.3
- @react-navigation/native 7.x
- TypeScript + ESLint + Jest configured

Python stack:
- tensorflow 2.21.0
- transformers
- torch/torchaudio
- librosa/scipy/scikit-learn

Android stack highlights:
- compileSdk 36 / targetSdk 36
- Kotlin 2.1.20
- TFLite + TFLite GPU + ONNX Runtime

---

## 16) iOS State
Files indicate baseline React Native iOS app shell exists (AppDelegate, Info.plist).
No equivalent iOS deepfake native processing layer was identified in this snapshot matching Android NotificationService/AegisCallMonitor functionality.

---

## 17) Practical Project Interpretation
As currently implemented, the most operationally complete path in runtime app behavior is:
1. Android notification capture
2. Native + JS text scam signaling
3. RN warning/interaction flow
4. Local threat history persistence

The deepfake audio stack has substantial implementation and model assets on Android, but Python training/evaluation reproducibility in this workspace is currently blocked by missing detector checkpoints and missing modern attack audio dataset content.

---

## 18) Suggested Next Engineering Steps
1. Unify and document canonical dataset locations and filenames across all scripts.
2. Consolidate or remove legacy scripts referencing missing modules.
3. Decide whether native Tier2/Tier3 should be placeholder heuristic or true ONNX/TFLite inference and implement consistently.
4. Move secrets (keystore credentials) out of tracked files and rotate keys.
5. Add integration tests covering:
   - Notification event to warning flow
   - BLOCK action persistence
   - Permission settings behaviors
   - Native bridge payload contract
6. Add a single pipeline orchestration doc/script to reduce workflow ambiguity.

---

## 19) Appendix: Quick File-to-Responsibility Map
- App.tsx, index.js: RN bootstrap
- src/navigation/*: route contracts and warning action persistence
- src/screens/*: user-visible protection UI and controls
- src/utils/scamDetector.ts: JS keyword scam scoring
- src/utils/storage.ts: local persistence for threats/settings
- src/utils/permissionManager.ts: Android permission orchestration
- android/app/src/main/java/com/intentfirewall/NotificationService.kt: text detection pipeline entrypoint
- android/app/src/main/java/com/intentfirewall/CallScreeningService.kt: caller-ID style incoming call gate
- android/app/src/main/java/com/intentfirewall/AegisCallMonitor.kt: call audio monitoring service
- android/app/src/main/java/com/intentfirewall/AegisAudioDetector.kt: deepfake audio inference engine
- feature_extractors.py: all audio feature engineering
- models.py: detector architectures
- train_detectors.py: staged detector training
- evaluate_modern.py: modern attack benchmark
- export_tflite_v2.py: detector TFLite export
- build_scam_dataset.py + fine_tune_dialogrpt.py + export_dialogrpt_onnx.py: text model pipeline
- build_tier3_distill_dataset.py + train_tier3_classifier.py: tier3 model pipeline
- build_wavlm_distill_dataset.py + train_wavlm_student.py + export_wavlm_student_tflite.py: WavLM student pipeline
- traverse_server.py: optional federated privacy-preserving reputation backend

End of context dossier.
