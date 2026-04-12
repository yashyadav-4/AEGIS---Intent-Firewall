# Aegis Zero / Hackaccino - Complete System Architecture & Context

## 1. Project Vision
The vision of this project is to build an uncompromisable, **100% on-device, privacy-first intent firewall** for Android. Focusing on vulnerable demographics (especially in the Indian subcontinent using Hinglish), the application acts as a background sentry intercepting incoming text messages, VOIP calls, and carrier telecom calls. It analyzes audio and text streams in real-time to detect social engineering, financial scams, and deepfake AI-cloned voices, instantly deploying a disruptive "Friction UI" to stop the user from fulfilling a malicious request (like sharing an OTP). 

Because audio is highly sensitive, no raw audio is ever sent to the cloud. Everything from deepfake detection to intent analysis relies heavily on on-device Edge ML (TensorFlow Lite, DistilBERT, Whisper).

---

## 2. Core Detection Pipelines & Logic

### A. Message & Notification Detection Logic (`NotificationService.kt`)
The app operates a `NotificationListenerService` that intercepts all incoming banners. 
**The Interception Logic:**
1. **Extraction:** It reads the `StatusBarNotification`. Because modern Android hides sensitive messages (like OTPs) deep inside nested bundles, the app runs a recursive `extractDeepText()` function to pull raw text out of hidden notification arrays.
2. **Filtering:** It explicitly ignores its own notifications (to prevent infinite boot loops) and bypasses telecom/VoIP notifications for text processing. Short messages (<4 characters) are ignored.
3. **The 3-Tier Text Pipeline:**
   * **Tier 1 (Regex & Hinglish):** Driven by `RegexSentinel.kt`. It performs hyper-fast (0.1ms) checks against explicitly known fraudulent patterns and a highly tuned Hinglish scam matrix (e.g., "give me otp", "bank account block").
   * **Tier 2 (Keyword/DistilBERT):** `Tier2Classifier.kt` measures contextual threat scoring.
   * **Tier 3 (Semantic TFLite):** `Tier3Classifier.kt` loads the `tier3_classifier.tflite` model. It takes quantized input features, factoring in preceding conversation history contexts stored in `ContextBuffer.kt` to catch evasive, conversational social engineering over multiple messages.
4. **Triggering:** If combined confidence exceeds thresholds, it triggers the `FrictionWarningActivity` (Red UI Overlay) and emits a payload down the React Native bridge via `NotificationEventEmitter`.

### B. Call Triggering & Interception Logic
How the app knows a call is happening and taps the line:
1. **The Telephony Manager:** The app registers a `PhoneStateListener`. When `TelephonyManager.CALL_STATE_OFFHOOK` triggers, it knows a live call has begun. 
2. **The Notification Sniffer (Fallback):** If VoIP calls (WhatsApp) come in, `NotificationService.kt` sniffs for `incallui`, `telecom`, or WhatsApp voice strings, booting the Call Monitor.
3. **The Default Dialer Integration (The Future Route):** To gain absolute control over the call screen, the app integrates `AegisInCallService` tied to Android's `RoleManager.ROLE_DIALER`. By becoming the default phone app, it avoids OEM-specific limits completely.
4. **The Capture Execution:** Once triggered, it fires an Intent to `AegisCallMonitor.kt`.

### C. Live Audio Capture & Deepfake Detection (`AegisCallMonitor.kt`)
The workhorse of the audio security layer.
1. **Foreground Service:** Bootstrapped using `FOREGROUND_SERVICE_TYPE_MICROPHONE`. Android requires this explicit declaration so the OS doesn't kill the background mic request.
2. **Audio Buffer Ingestion:** An `AudioRecord` instance opens a 16kHz mono PCM stream (`VOICE_COMMUNICATION`). It pulls audio arrays into a `WINDOW_SAMPLES` buffer. 
3. **Deepfake Gatekeeper:** Before running expensive ML inference, it calculates mathematical audio properties (Pitch Variance, Zero-Crossing Rate, Spectral Flux). If it sounds like human speech (not silence), `detector.shouldActivate()` returns true.
4. **TFLite Deepfake Analysis:** The raw audio chunk is passed to `AegisAudioDetector` (`detector_phase.tflite`).
5. **Consensus Window:** To avoid rapid false positives, `processCallConsensus()` tracks a rolling window of detections. If $N$ consecutive seconds are flagged as Synthetic/AI-generated with >75% confidence, it triggers the Red Friction UI.
6. **Graceful Teardown:** Upon `CALL_STATE_IDLE`, an explicit `record.stop()` and `textAnalyzer.stop()` are issued, immediately releasing WakeLocks and the microphone, plugging a major hardware leak that previously locked the mic post-call.

### D. Speech-To-Text (Transcription) & Intent Detection (`AegisCallTextAnalyzer.kt`)
1. **The Google OS Failure (Error 7):** Originally, the app tried to use the native Google `SpeechRecognizer`. However, because Android 11+ restricts background apps from concurrently accessing the microphone while the Google Voice/Telecom handler acts, it constantly crashed internally with `Error code: 7` (Silence/No Match).
2. **The Whisper Architecture Pivot:** To bypass OS-level muting, the `AegisCallMonitor` takes the raw PCM `window` it is *already* successfully recording, and directly bypasses the OS by piping it into `textAnalyzer.processAudioChunk(window)`.
3. **Local Whisper Engine:** The `processAudioChunk` is structurally wired to accept a Local Whisper TFLite model. Because the native layer has direct access to the `AudioRecord` buffer, it transcribes the words physically within the app's memory wall.
4. **Intent Evaluation:** Once transcribing is complete, the strings are flushed immediately into `RegexSentinel.kt` to see if the caller demands an OTP, money, or remote access apps (AnyDesk) in English or Hinglish. If a hit occurs, the Friction UI stops the user.

---

## 3. Current Work State

### ✅ Work Completed
- **Full Base React Native UI Structure:** Dashboards, analytics logic, threat timeline bridging.
- **Microphone & Service Leak Resolutions:** Call monitor tears down cleanly without holding the device mic hostage out of calls.
- **Android 14 Structural Crashes:** Added `FOREGROUND_SERVICE_TYPE_MICROPHONE` and `Manifest <queries>` to circumvent Android 14 `SecurityExceptions`.
- **Text Pipeline:** 3-tier deep detection with memory-context buffering and Hinglish optimization fully built and tested.
- **Audio Overhaul:** Uncoupled the deepfake inference logic from the STT logic. Dropped the dysfunctional `SpeechRecognizer` in favor of a raw audio injection pipeline. 
- **Role Framework Stubs:** Added all Manifest boilerplate (`MockDialActivity`, `AegisInCallService`) and the React Native Method bridge `requestDefaultRoles()` required to ask the user to make the app the Default Dialer.
- **Live Pipeline Testing Mock:** Implemented a loud-audio/RMS trigger in `AegisCallTextAnalyzer.kt` that fakes a "give me otp" string generation to prove the system UI overlay works natively over top of a phone call.

### 🚧 Work Left (Pending)
1. **Embed Whisper Mini:** Place the quantized `whisper-tiny.tflite` model into `/models` and implement the C++ JNI/TFLite invocation in `AegisCallTextAnalyzer` so `processAudioChunk` turns real PCM into English/Hinglish strings.
2. **Deepfake TFLite Fix:** The console reported an error loading `detector_phase.tflite` (likely missing from assets or corrupted). The `AegisAudioDetector` needs the legitimate model asset placed in `android/app/src/main/assets`.
3. **React Native Role UI Hook:** The React Native frontend needs a button wired to the `AegisDetectionModule.requestDefaultRoles()` promise so the actual user can click "Set as Default Caller ID / Dialer".
4. **Final E2E Benchmark:** Doing a real end-to-end call test with real Whisper translation firing the Intent Firewall.

---

## 4. Simplified Project Structure

```
Hackaccino/
├── App.tsx                     # Main React Native Entrypoint
├── package.json                # JS Dependencies
├── models.py / train_*.py      # Python scripts to distill/train the on-device models
├── models/                     # Raw model weights from training
└── android/
    ├── app/build.gradle        # NDK and Android Build Flags
    └── app/src/main/
        ├── AndroidManifest.xml # Defines Foreground Services, Manifest Queries, Default App Receivers
        ├── assets/             # WHERE TFLite models MUST go (tier3_classifier.tflite)
        └── java/com/intentfirewall/
            ├── MainActivity.kt           # React Native Activity Handler
            ├── AegisDetectionModule.kt   # Bridge exposing Native Kotlin methods to JS
            ├── AegisCallMonitor.kt       # The Foreground Service capturing PCM mic audio
            ├── AegisAudioDetector.kt     # Deepfake TFLite interface (PCM -> AI -> Fake/Real)
            ├── AegisCallTextAnalyzer.kt  # STT architecture (PCM -> Whisper -> Intent String)
            ├── NotificationService.kt    # Notification sniffing for messages and VoIP detection
            ├── RegexSentinel.kt          # Lightning-fast Regex / Hinglish threat dictionary
            ├── Tier2Classifier.kt        # Keyword/DistilBERT proxy analyzer
            ├── Tier3Classifier.kt        # Neural Network Semantic threat analyzer
            ├── DefaultAppStubs.kt        # The RoleManager / InCallService overrides for Default Dialer
            ├── FrictionWarning.kt        # The Native Red Screen Overlay Activity
            └── ContextBuffer.kt          # Keeps short-term memory of a conversation's history
```
