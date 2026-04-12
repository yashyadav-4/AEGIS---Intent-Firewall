## IntentFirewall V2 Call Detection: Current Implementation Approach

This document describes the current end-to-end approach used in the app to track calls, capture call audio signals, analyze scam risk, and surface results to the app UI and notifications.

It reflects the implementation currently in the Android native layer plus the React Native control layer.

## April 2026 Update (Current Active Path)

The active call protection path now uses websocket streaming via Gemini Live as the primary runtime call analysis engine:

1. `AegisDetectionModule.startCallProtection()` arms `CallAudioMonitorService` (not `AegisCallMonitor`) and waits for call state transitions.
2. On `CALL_STATE_OFFHOOK`, `CallAudioMonitorService` opens one persistent `GeminiLiveConnection` websocket.
3. Microphone audio is captured with `AudioRecord(VOICE_COMMUNICATION, 16kHz, PCM16)` and streamed in `500ms` chunks (`8000` samples) continuously during the active call.
4. `RiskAggregator` consumes websocket risk responses and emits call events (`call_scam_alert`, etc.) through `NotificationEventEmitter.sendCallEvent`.
5. Tier 0 pre-answer screening still runs through `CallScamScreener` + `NumberRiskEngine` for block/silence decisions.

Notes:
- Legacy `AegisCallMonitor` remains in codebase for backward compatibility but is explicitly stopped when websocket call protection is started.
- Normal app call-status notifications (`calling`, `ringing`, `ongoing voice call`) are now bypassed from scam classification and emitted as non-flagged `CALL_STATUS` metadata.

---

## 1) High-Level Architecture

The call protection pipeline is built as a hybrid of:

1. Android telecom interception path (InCallService).
2. Foreground call-monitoring service for live audio analysis.
3. Local on-device deepfake/synthetic voice detector.
4. Cloud Tier3 voice-intent classifier (Gemini voice endpoint).
5. Native->JS diagnostics + event bridge for UI visibility.

Main components:

- `ScamCallService` (InCallService): receives call lifecycle callbacks from Android telecom stack when role and permissions allow.
- `AegisCallMonitor` (foreground service): owns call-state monitoring, audio capture, local detector loop, Tier3 voice loop, and alerting.
- `AegisAudioDetector` (on-device ML): phase/glottal/WavLM student + ensemble scoring for synthetic voice risk.
- `GeminiVoiceScamClassifier` (Tier3 voice): remote intent analysis on short audio windows.
- `CallProtectionPrefs`: persisted armed/floating states.
- `FloatingCallControlService`: overlay bubble to arm/disarm monitoring quickly.
- `AegisDetectionModule` (React Native bridge named `NotificationService`): permission checks, start/stop commands, diagnostics exposure.

Key files:

- `android/app/src/main/java/com/intentfirewall/ScamCallService.kt`
- `android/app/src/main/java/com/intentfirewall/AegisCallMonitor.kt`
- `android/app/src/main/java/com/intentfirewall/AegisAudioDetector.kt`
- `android/app/src/main/java/com/intentfirewall/GeminiVoiceScamClassifier.kt`
- `android/app/src/main/java/com/intentfirewall/AegisDetectionModule.kt`
- `android/app/src/main/java/com/intentfirewall/CallProtectionPrefs.kt`
- `android/app/src/main/java/com/intentfirewall/FloatingCallControlService.kt`
- `android/app/src/main/AndroidManifest.xml`
- `src/utils/permissionManager.ts`
- `src/screens/SettingsScreen.tsx`
- `src/screens/DebugScreen.tsx`

---

## 2) Android Manifest and Platform Wiring

The call stack relies on these manifest declarations:

- Permissions:
	- `RECORD_AUDIO`
	- `READ_PHONE_STATE`
	- `FOREGROUND_SERVICE`
	- `FOREGROUND_SERVICE_MICROPHONE`
	- `SYSTEM_ALERT_WINDOW`
	- network + wake lock permissions
- Services:
	- `ScamCallService` with permission `android.permission.BIND_INCALL_SERVICE`
	- `AegisCallMonitor` as foreground service with `foregroundServiceType="microphone"`
	- `FloatingCallControlService` for overlay control

Important note:

- `ScamCallService` can only receive call callbacks reliably when telecom role requirements are satisfied (dialer/call-screening OEM behavior varies by Android version and vendor).

---

## 3) Control Plane (React Native -> Native)

React Native controls call protection through the native module exported as `NotificationService` (class `AegisDetectionModule`).

JS-side control functions:

- `checkCallScreeningPermission()`
- `requestCallScreeningPermission()`
- `checkAudioRecordingPermission()`
- `requestAudioRecordingPermission()`
- `checkPhoneStatePermission()`
- `requestPhoneStatePermission()`
- `startCallProtection()`
- `stopCallProtection()`
- `isCallProtectionRunning()`
- `getProtectionDiagnostics()`

UI touchpoints:

- `SettingsScreen` exposes start/stop and permission flow.
- `DebugScreen` exposes diagnostics and test hooks.

Native start behavior:

- `startCallProtection()` checks mic + phone-state runtime permissions.
- If granted, it sets `CallProtectionPrefs.setArmed(true)` and sends `ACTION_START` to `AegisCallMonitor`.

Native stop behavior:

- `stopCallProtection()` sets armed false and sends `ACTION_STOP`.

---

## 4) Call Interception Strategy (How We Detect a Live Call)

We intentionally use multiple signals for robustness across OEM behavior:

### 4.1 Telecom callback path

`ScamCallService` receives `onCallAdded` and `onCallRemoved` when Android telecom binds our service.

- On `onCallAdded`:
	- Reads caller handle (number if available).
	- Computes `caller_unknown`.
	- Starts `AegisCallMonitor` with `ACTION_START` and call metadata.
	- Registers callback to stop monitor when disconnected.
- On `onCallRemoved`:
	- Stops monitor with `ACTION_STOP`.

### 4.2 Telephony listener path

Inside `AegisCallMonitor.onCreate()`:

- Registers `PhoneStateListener(LISTEN_CALL_STATE)`.
- Handles `CALL_STATE_RINGING`, `CALL_STATE_OFFHOOK`, `CALL_STATE_IDLE`.

This path sets in-call flags and starts/stops capture as state changes.

### 4.3 Audio-mode poller fallback path

`AegisCallMonitor` also runs a 1.5s poller that checks:

- Telephony offhook state.
- Audio mode (`MODE_IN_CALL` / `MODE_IN_COMMUNICATION`).

If an active voice session is detected, capture starts even if callback timing is unreliable.

Why this exists:

- Some OEM builds delay or suppress telecom callbacks; audio-mode polling improves practical resilience.

---

## 5) Foreground Service Runtime Model

`AegisCallMonitor` is long-lived while armed and uses:

- Foreground notification (`Aegis Active`).
- Partial wake lock.
- Coroutine scopes for capture and async analyzers.
- Internal state flags exposed through diagnostics:
	- `isServiceRunning`
	- `monitorArmed`
	- `inCallDetected`
	- `audioCaptureRunning`
	- `lastCaptureStatus`
	- `lastTier3Status`

Lifecycle:

1. `ACTION_START`:
	 - update caller metadata (`caller_unknown`)
	 - set monitor armed/enabled
	 - determine if currently in-call
	 - start capture now or wait for call
2. `ACTION_STOP`:
	 - disable monitor
	 - stop capture
	 - stop foreground + service

---

## 6) Audio Capture and Windowing

Audio source and format:

- `AudioRecord` source: `VOICE_COMMUNICATION`
- Sample rate: `16000 Hz`
- Mono 16-bit PCM
- Chunk size: `3200` samples
- Analysis window: `48000` samples (~3s)

At runtime:

1. Read PCM chunks into an accumulator.
2. When 3s window is ready:
	 - compute pitch variance
	 - compute zero-crossing rate (ZCR)
	 - compute RMS speech activity
	 - update spectral trigger signal
3. Drive two analysis lanes:
	 - local detector lane (throttled)
	 - Tier3 voice lane via queued chunks (throttled + single in-flight)

Backpressure:

- Queue max pending chunks is bounded.
- Oldest chunks are dropped when full (`queue_overflow_drop_oldest`).

---

## 7) Local Detector Lane (On-Device ML)

`AegisAudioDetector` performs synthetic/deepfake-oriented analysis using:

- `detector_phase.tflite`
- `detector_glottal.tflite`
- `wavlm_student.tflite`
- `detector_wavlm.tflite`
- `ensemble.tflite`

Flow:

1. Normalize waveform.
2. Extract LFCC and glottal features.
3. Run INT8 models for phase and glottal signals.
4. Run WavLM student and linear probe.
5. Fuse via ensemble score with fallback fusion rule near flat-zone.
6. Produce `DetectionResult` with confidence and per-branch scores.

Activation gating:

- Local detector only runs when `shouldActivate()` is true.
- `shouldActivate()` requires both:
	- metadata trigger (unknown caller or video-call hint)
	- spectral trigger (flat pitch heuristic)

Output handling:

- Synthetic hit emits local broadcast `com.aegis.DEEPFAKE_DETECTED`.

---

## 8) Tier3 Voice Lane (Remote Intent Analysis)

`GeminiVoiceScamClassifier` analyzes short live windows with multimodal prompt + inline WAV data.

Request characteristics:

- Endpoint: `v1beta/models/{voice_model}:generateContent`
- Response requested as strict JSON (`responseMimeType=application/json`)
- API key round-robin with cursor persistence in shared prefs
- Retry on retryable errors (429/503 and similar)

Prompt intent:

- Focuses on real-time social-engineering intent (OTP, payment pressure, impersonation, KYC panic).
- Uses caller metadata + local acoustic hints as context.
- Returns JSON schema with `isScam`, `confidence`, `intent`, `reason`, `action`.

Decision smoothing in monitor:

- A rolling window of recent Gemini decisions is maintained.
- Alert policy:
	- immediate alert on high-confidence scam
	- or alert when scam count crosses threshold across recent windows

Notification/event emission:

- Every voice tier3 result is emitted via `NotificationEventEmitter.sendNotification` with:
	- `captureMethod=call_audio`
	- `tierUsed=tier3-voice`
	- `tier3Reason`, `tier3Model`, `tier3KeyIndex`
- App-local broadcast sent with `ACTION_VOICE_SCAM_INTENT`.
- High-risk results trigger a high-priority Android notification.

---

## 9) Result Surfacing and Observability

Runtime observability is built into native module diagnostics:

- from `getProtectionDiagnostics()`:
	- running/armed/in-call/capture state
	- last capture status + timestamp
	- last tier3 status + timestamp
	- permission and role state
	- configured tier3 models and key presence

Text and voice event emission path:

- `NotificationEventEmitter` stores buffered events for JS-side consumption.
- JS uses this stream for history/debug views.

---

## 10) Why We Use Multiple Detection Layers

The current approach intentionally combines deterministic and probabilistic layers:

- deterministic call-state and audio-session detection for interception reliability
- local on-device ML for low-latency synthetic voice signal
- cloud Tier3 for semantic intent and scam strategy classification

This gives:

- faster first-pass signals
- better robustness to noisy channels
- better scam-intent understanding than pure acoustic-only detection

---

## 11) Current Known Constraints

1. InCallService behavior is OEM/role sensitive.
- If dialer/call-screening role is not properly held, callbacks may be partial.

2. Call audio capture quality depends on device/OEM policy.
- Some devices degrade or restrict call-path audio accessibility.

3. Tier3 voice is network-dependent.
- Offline or degraded network can delay/skip cloud intent decisions.

4. Runtime permission friction exists.
- Mic, phone state, overlay, and role grants must align for full feature behavior.

---

## 12) Practical Runtime Sequence (End-to-End)

1. User enables call protection from settings.
2. RN calls native `startCallProtection()`.
3. Native arms prefs and starts `AegisCallMonitor` foreground service.
4. Incoming/active call signal arrives via InCallService and/or telephony/audio poller.
5. Monitor starts audio capture.
6. Local detector periodically scores synthetic risk.
7. Tier3 voice classifier periodically scores scam intent on queued windows.
8. Rolling decision converts windows into alert vs monitor vs safe actions.
9. Results are pushed to notifications, local broadcasts, and buffered event stream.
10. JS screens read diagnostics/events and show status/history.

---

## 13) Summary

Our current call tracking and scam analysis architecture is service-oriented and layered:

- interception layer: `ScamCallService` + telephony/audio fallback
- capture layer: `AegisCallMonitor` foreground audio pipeline
- local intelligence: `AegisAudioDetector`
- cloud intelligence: `GeminiVoiceScamClassifier`
- control and diagnostics: `AegisDetectionModule` + RN permission manager/screens

This is the complete current approach used to intercept calls, analyze scam risk in near real-time, and present actionable protection signals to the user.

---

## 14) Diagnostic Mission: Gemini Live WebSocket Pipeline (2026-04-12)

This section answers the requested diagnostic checklist with exact code, exact command outputs, and exact observed logs.

### Data Sources Used

- Source code in Android and React Native files.
- Device log sequences provided during runtime tests.
- Live device commands run from ADB in this workspace.

Command outputs captured during this mission:

```text
adb shell getprop ro.build.version.release -> 16
adb shell getprop ro.build.version.sdk -> 36
adb shell getprop ro.product.manufacturer -> realme
adb shell getprop ro.product.model -> RMX3868
adb shell getprop ro.product.brand -> realme
adb shell getprop ro.build.fingerprint -> realme/RMX3868IN/RE5C86L1:16/BP2A.250605.015/U.R4T2.14ec6da_db6736:user/release-keys

adb shell ping -c 1 generativelanguage.googleapis.com
PING generativelanguage.googleapis.com (142.250.71.106) ...
64 bytes from ... time=27.6 ms
0% packet loss

Generated BuildConfig (debug)
GEMINI_API_KEY_MASKED=AIzaSy..._8T0
GEMINI_API_KEY_LENGTH=39
GEMINI_VOICE_MODEL=gemini-2.0-flash
```

Note on test triggering: This service is not exported, so it cannot be started directly via external ADB intent for synthetic OFFHOOK tests.

```text
Error: Requires permission not exported from uid 10336
```

### Chapter 1: WebSocket Connection Lifecycle

1. Exact WebSocket URL string constructed in code:

```kotlin
val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$apiKey"
```

API key non-empty evidence:

```text
GEMINI_API_KEY_MASKED=AIzaSy..._8T0
GEMINI_API_KEY_LENGTH=39
```

2. OkHttpClient timeout values in code:

```kotlin
private val client = OkHttpClient.Builder()
		.connectTimeout(10, TimeUnit.SECONDS)
		.readTimeout(0, TimeUnit.MILLISECONDS)
		.writeTimeout(10, TimeUnit.SECONDS)
		.build()
```

readTimeout is exactly 0 ms.

3. Temporary callback-entry logs were added at the first line of:
- onOpen
- onMessage(text)
- onMessage(binary)
- onFailure
- onClosed

From available call logs before these temporary callback-entry markers existed, the observed callback sequence was:

```text
GeminiLiveConnection: WebSocket opened - sending setup
GeminiLiveConnection: Setup message sent
GeminiLiveConnection: Received Gemini binary frame bytes=26 (responses=1, serverMessages=1)
GeminiLiveConnection: WebSocket closed: 1000  (model=models/gemini-2.5-flash-native-audio-latest)
```

onFailure did not appear in these traces.

4. isSetupComplete set to true location:

```kotlin
ws.send(setup.toString())
isSetupComplete = true
onReadyChanged(true)
Log.d(tag, "Setup message sent")
```

Relative ordering in observed logs: setup sent before the first binary response line.

### Chapter 2: Setup Message

5. Exact setup JSON sent by current code (pretty-printed template):

```json
{
	"setup": {
		"model": "models/gemini-2.5-flash-native-audio-latest",
		"system_instruction": {
			"parts": [
				{
					"text": "You are a live scam detection agent for Indian phone calls. Listen to incoming call audio and detect social engineering patterns. If content appears scam-like, respond briefly and clearly with the suspicious reason. Caller number context: unknown."
				}
			]
		},
		"generation_config": {
			"response_modalities": [
				"AUDIO"
			]
		},
		"input_audio_transcription": {},
		"output_audio_transcription": {}
	}
}
```

Model check result: it is not `models/gemini-2.0-flash-live-001` in the active path. It is `models/gemini-2.5-flash-native-audio-latest`.

6. setup acknowledgment messages after setup:

Observed immediately after setup:

```text
GeminiLiveConnection: Received Gemini binary frame bytes=26 (responses=1, serverMessages=1)
```

No text setupComplete payload has been captured in available logs so far.

7. Ordering of setup vs audio send:

Observed race in one run:

```text
CallAudioMonitorService: Audio capture started at 16kHz, chunk=8000 samples
GeminiLiveConnection: Cannot send chunk - connection not ready
GeminiLiveConnection: WebSocket opened - sending setup
GeminiLiveConnection: Setup message sent
GeminiLiveConnection: Sent audio chunks=1
```

Conclusion from logs: yes, there is a start-of-session race where capture can begin before setup completes.

### Chapter 3: Audio Chunk Transmission

8. `CHUNK_SEND` temporary log was added right before send.

Current available logs (from runs before this exact marker) show chunk sending began after setup in most runs, with one pre-setup warning shown above.

9. Actual chunk byte size:

Current code path:

```kotlin
val bytesRead = audioRecord?.read(buffer, 0, chunkBytes) ?: -1
if (bytesRead > 0) {
		val chunk = buffer.copyOf(bytesRead)
		geminiConnection?.sendAudioChunk(chunk)
}
```

So `pcmBytes.size` is variable by `bytesRead` at runtime; not hardcoded. Existing logs do not include explicit size values yet.

10. mime_type and base64 non-empty:

```kotlin
put("mime_type", "audio/pcm;rate=16000")
put("data", b64)
```

mime_type is exactly `audio/pcm;rate=16000`.

11. speakerphone state evidence:

Observed logs:

```text
CallAudioMonitorService: Speakerphone is OFF - waiting to start audio capture
CallAudioMonitorService: Speakerphone turned ON - starting audio capture
```

Temporary exact marker `SPEAKER_STATE: ...` was added in this mission.

### Chapter 4: Response Parsing

12. `RAW_MSG`/`RAW_ONMESSAGE` marker added as first line in text handler.

From available runtime evidence so far, data is arriving as binary frame(s):

```text
GeminiLiveConnection: Received Gemini binary frame bytes=26 (responses=1, serverMessages=1)
```

No full text JSON message payload has been captured in provided logs.

13. top-level key structure for first non-setup response:

Not available from provided logs because the first observed response is binary frame bytes=26 and was not decoded to JSON.

14. path to model output text in current parser code:

```kotlin
response["serverContent"]["modelTurn"]["parts"][i]["text"]
response["serverContent"]["inputTranscription"]["text"]
response["serverContent"]["outputTranscription"]["text"]
```

Also snake_case fallback:

```kotlin
serverContent["input_transcription"]["text"]
serverContent["output_transcription"]["text"]
```

15. parseRiskResponse reachability:

Temporary `PARSE_INPUT` marker added. In available logs, there are no `PARSE_INPUT` lines, so this method has not been reached in those runs.

### Chapter 5: Risk Callback Chain

16. `PARSED: risk=...` marker:

Temporary marker added. No `PARSED:` lines are present in provided logs yet.

17. Temporary always-call behavior:

Current code now always calls callback inside parseRiskResponse:

```kotlin
// Temporary diagnostic behavior: always route parsed scores into aggregator.
onRiskScore(risk, intent, reason)
```

But since parseRiskResponse has not been reached in available logs, `RiskAggregator.addGeminiScore()` still has no new parse-driven evidence.

18. `AGGREGATOR:` marker:

Temporary marker added in RiskAggregator. No `AGGREGATOR:` lines appear in provided logs yet.

### Chapter 6: Event Emission to JS

19. emitRiskAlert marker:

Temporary marker added:

```kotlin
Log.d(tag, "EMIT_ALERT: score=$score reason=$reason")
```

No `EMIT_ALERT` lines are present in provided logs.

20. Event names in native emitter:

Text notification emit call:

```kotlin
.emit("onNotification", params)
```

Call event emit call:

```kotlin
.emit("onNotification", params)
```

They are identical (`onNotification`).

21. JS listener setup:

- Home screen uses `NativeEventEmitter` and subscribes to `onNotification` in a `useEffect` hook.
- Call helper also uses `NativeEventEmitter` and `onNotification`.
- Listener is mounted only when the screen/component is mounted; it is not a global background listener independent of UI lifecycle.

### Chapter 7: Environment and Build

22. Device details from ADB:

```text
Android release: 16
API level: 36
OEM: realme
Model: RMX3868
```

23. Host reachability test from device:

```text
adb shell ping -c 1 generativelanguage.googleapis.com
64 bytes received, 0% packet loss
```

No direct evidence of VPN/proxy restrictions was found in this mission.

24. API key type / Live access:

- Workspace cannot directly query AI Studio console capability flags.
- Runtime evidence shows websocket opens and closes with code 1000 in many sessions (not immediate 403 auth rejection), which indicates the key is accepted by the endpoint in current tests.

25. OkHttp version:

From app Gradle dependencies:

```gradle
implementation 'com.squareup.okhttp3:okhttp:4.12.0'
```

Result: version is 4.12.0 (meets requested threshold).

### Current Blocking Finding

Most critical observed issue in recent real call logs:

```text
CallAudioMonitorService: Capture RMS chunk=1 rms=0
...
CallAudioMonitorService: Capture RMS chunk=80 rms=0
```

This means the pipeline is often streaming silent PCM to Gemini. The websocket and setup complete, but payload quality is effectively zero-energy audio in these sessions.

---

## 15) Applied Fixes From 3-Bug Stack Review (2026-04-12)

The requested fix sequence was implemented with runtime-safe fallbacks.

### A) Response Modality Handling

- Connection now attempts `TEXT` response modality first, then falls back to `AUDIO` if modality rejection is returned by the endpoint.
- Setup no longer includes `input_audio_transcription` / `output_audio_transcription` fields.

Code path changed in:
- `GeminiLiveConnection.sendSetup(...)`
- `GeminiLiveConnection.maybeFallback(...)`

### B) Model Selection / Fallback Chain

- Added `BuildConfig.GEMINI_LIVE_MODEL` field (from `GEMINI_LIVE_MODEL`, default `models/gemini-2.0-flash-live-001`).
- Model candidate order now includes requested model first, but automatically falls back when model is unavailable.

Verified live model check on current API key:

```text
models/gemini-2.0-flash-live-001 -> 404 NOT_FOUND on v1beta
models/gemini-2.5-flash-native-audio-latest -> available, bidiGenerateContent
models/gemini-3.1-flash-live-preview -> available, bidiGenerateContent
```

So the requested 2.0 model is attempted first but cannot be used on this key in current environment.

### C) Pre-Setup Audio Queue (Race Fix)

- Implemented pending chunk queue in `GeminiLiveConnection`.
- If setup is not complete, chunks are queued instead of dropped.
- Queue is flushed immediately after setup completion.

New methods:
- `enqueuePendingChunk(...)`
- `flushPendingChunks(...)`
- `sendChunkInternal(...)`

### D) Silent-Chunk RMS Gate

- Added RMS gate in capture loop:
	- chunks with `rms < 50` are skipped (not sent to Gemini)
	- source-switch logic remains active for sustained zero-RMS capture

Code changed in:
- `CallAudioMonitorService.startAudioCapture(...)` capture loop

### Net Result

- Startup race no longer drops early call audio.
- Silent frames are filtered to reduce wasted quota.
- Modality and model selection now degrade gracefully based on real endpoint responses.
- Remaining blocker, when present, is still hardware/OEM audio capture quality (RMS=0 sessions).

### Final Model Selection Update (Post-Diagnostic)

After validation, the startup model path was adjusted to avoid unnecessary 2.0 probing:

1. `models/gemini-2.0-flash-live-001` is not available for the active key on `v1beta` in this environment.
2. Primary startup model is now `models/gemini-2.5-flash-native-audio-latest`.
3. Hardcoded 2.0 default probing was removed from fallback defaults.

Code changes applied:

- `android/app/build.gradle`
	- `GEMINI_LIVE_MODEL` default changed to `models/gemini-2.5-flash-native-audio-latest`.
- `android/app/src/main/java/com/intentfirewall/GeminiLiveConnection.kt`
	- removed `models/gemini-2.0-flash-live-001` from default fallback list.

Deployment evidence:

```text
Gradle task: :app:compileDebugKotlin :app:installDebug
Result: BUILD SUCCESSFUL
Install: Installed on 1 device (RMX3868 - 16)
```

Operational outcome:

- Connection now starts directly on 2.5 native audio instead of spending an initial failed hop on 2.0.

---

## 16) WebSocket Version/Model Matrix (Requested Investigation)

This section records the exact matrix requested for model/version diagnostics.

### Step 1: Base WebSocket Path Connectivity

Both endpoints successfully completed WebSocket upgrade (HTTP 101):

```text
CONNECT_TEST url=wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent http=101 state=OPEN
CONNECT_TEST url=wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent http=101 state=OPEN
```

### Steps 1+2: 2.0 Model IDs on Both Paths

Tested with setup + `response_modalities: ["TEXT"]` + one `audio/pcm;rate=16000` chunk.

| Path | Model | WebSocket Upgrade | First Frame | Close Status | Close Description |
|---|---|---|---|---|---|
| v1beta BidiGenerateContent | models/gemini-2.0-flash-live-001 | 101 | close | PolicyViolation | model not found / not supported for bidiGenerateContent |
| v1beta BidiGenerateContent | models/gemini-live-2.0-flash-001 | 101 | close | PolicyViolation | model not found / not supported for bidiGenerateContent |
| v1alpha BidiGenerateContent | models/gemini-2.0-flash-live-001 | 101 | close | PolicyViolation | model not found / not supported for bidiGenerateContent |
| v1alpha BidiGenerateContent | models/gemini-live-2.0-flash-001 | 101 | close | PolicyViolation | model not found / not supported for bidiGenerateContent |

Exact output samples:

```text
MODEL_TEST ... model=models/gemini-2.0-flash-live-001 http=101 firstFrame=close sawText=False closeStatus=PolicyViolation closeDesc=models/gemini-2.0-flash-live-001 is not found for API version v1beta, or is not supported for bidiGenerateContent.

MODEL_TEST ... model=models/gemini-live-2.0-flash-001 http=101 firstFrame=close sawText=False closeStatus=PolicyViolation closeDesc=models/gemini-live-2.0-flash-001 is not found for API version v1alpha, or is not supported for bidiGenerateContent.
```

### Step 4: models/gemini-2.0-flash-exp on v1alpha

```text
MODEL_TEST path=wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent model=models/gemini-2.0-flash-exp http=101 firstFrame=close sawText=False closeStatus=PolicyViolation closeDesc=models/gemini-2.0-flash-exp is not found for API version v1alpha, or is not supported for bidiGenerateContent.
```

### Step 5: Verify Text vs Binary Callback Behavior

No 2.0 candidate produced any text frame because all 2.0 candidates closed immediately with PolicyViolation after setup.

Additional live-capable probes with TEXT modality:

| Path | Model | First Frame | Close |
|---|---|---|---|
| v1beta / v1alpha | models/gemini-2.5-flash-native-audio-latest | close | InvalidPayloadData: Cannot extract voices from a non-audio request |
| v1beta / v1alpha | models/gemini-2.5-flash-native-audio-preview-12-2025 | close | InvalidPayloadData: Cannot extract voices from a non-audio request |
| v1beta / v1alpha | models/gemini-3.1-flash-live-preview | close | InternalServerError: Internal error encountered |

Exact output samples:

```text
CANDIDATE ... model=models/gemini-2.5-flash-native-audio-latest firstType=close firstClose=InvalidPayloadData:Cannot extract voices from a non-audio request.

CANDIDATE ... model=models/gemini-3.1-flash-live-preview firstType=close firstClose=InternalServerError:Internal error encountered.
```

### Outcome of Requested Fix Logic

1. 2.0 model IDs were tested on both v1beta and v1alpha: all failed with PolicyViolation (not found / not supported for bidiGenerateContent).
2. Alternate 2.0 ID (`models/gemini-live-2.0-flash-001`) also failed on both versions.
3. `models/gemini-2.0-flash-exp` on v1alpha also failed.
4. Therefore, there is currently no validated model on this key/path matrix that satisfies all required conditions:
	- audio streaming input accepted,
	- TEXT response returned (onMessage(text)),
	- no binary-only audio response dependency.

Because no such model validated in this matrix, the implementation keeps the currently available native-audio model path operational and logs the tested failures above.

---

## 17) 2026-04-12 Follow-Up: Hard Pin To models/gemini-3.1-flash-live-preview

This follow-up applies the new model list finding for this key.

Code updates applied:

- `android/app/build.gradle`
	- `buildConfigField "String", "GEMINI_LIVE_MODEL", '"models/gemini-3.1-flash-live-preview"'`
- `android/app/src/main/java/com/intentfirewall/GeminiLiveConnection.kt`
	- Model selection simplified to one value only:
		- `private val model = BuildConfig.GEMINI_LIVE_MODEL`
	- Removed model/modality fallback probing.
	- `setup` payload kept minimal and TEXT-only.

Verified setup payload shape in runtime logs:

```json
{
	"setup": {
		"model": "models/gemini-3.1-flash-live-preview",
		"system_instruction": {
			"parts": [
				{
					"text": "..."
				}
			]
		},
		"generation_config": {
			"response_modalities": [
				"TEXT"
			]
		}
	}
}
```

No `input_audio_transcription` and no `output_audio_transcription` fields are present.

Build/deploy evidence:

```text
Task: :app:installDebug
Result: BUILD SUCCESSFUL
Install target: RMX3868 (Android 16)
```

Runtime call test evidence (speakerphone ON, live chunks flowing):

```text
GeminiLiveConnection: WebSocket opened - sending setup
GeminiLiveConnection: Setup message sent
CallAudioMonitorService: Speakerphone turned ON - starting audio capture
GeminiLiveConnection: CHUNK_SEND: bytes=16000 ...
CallAudioMonitorService: Gemini stream telemetry: chunks=30 responses=0
```

Observed status from this run:

1. WebSocket opened successfully and setup was sent.
2. Continuous non-zero audio chunks were sent for an extended window.
3. No websocket response callbacks were observed:
	- no `CB:onMessage(text)`
	- no `CB:onMessage(binary)`
	- no `RAW_ONMESSAGE`
	- no `PARSE_INPUT`
	- no `PARSED`
	- no `AGGREGATOR`
4. Telemetry remained `responses=0` while chunk count increased.

Conclusion for this pass:

- Transport and audio uplink are working.
- End-to-end parse chain is not yet confirmed because server response frames were not observed in this run.
- Since confirmation is incomplete, temporary diagnostics (`RAW_ONMESSAGE`, `PARSE_INPUT`, `PARSED`, `AGGREGATOR`, `EMIT_ALERT`, `CHUNK_SEND`) were intentionally left in place for the next validation run.

---

## 18) 2026-04-12 Follow-Up: Explicit turn_complete Signaling

This follow-up adds explicit client turn finalization to force model responses during live audio sessions.

### Code updates applied

- `android/app/src/main/java/com/intentfirewall/GeminiLiveConnection.kt`
	- Added:
		- `fun sendTurnComplete()`
	- Payload sent:
		- `{"client_content":{"turn_complete":true}}`
	- Log marker:
		- `Sent turn_complete signal`
	- Updated callback log text for verification:
		- `CB:onMessage(text) fired`
	- Updated system prompt to strict JSON-only response format:
		- Required keys: `risk`, `intent`, `flag`, `reason`

- `android/app/src/main/java/com/intentfirewall/CallAudioMonitorService.kt`
	- Added periodic turn-complete job:
		- `private var turnCompleteJob: Job? = null`
	- Starts with audio capture and fires every 8 seconds:
		- `geminiConnection?.sendTurnComplete()`
	- Log marker:
		- `Periodic turn_complete sent`
	- Canceled in teardown and capture-stop paths.

### Runtime validation (post-change)

Observed in logcat:

```text
GeminiLiveConnection: Setup message sent
CallAudioMonitorService: Audio capture started at 16kHz, chunk=8000 samples
GeminiLiveConnection: CHUNK_SEND: bytes=16000 ...
GeminiLiveConnection: Sent turn_complete signal
CallAudioMonitorService: Periodic turn_complete sent
CallAudioMonitorService: Gemini stream telemetry: chunks=110 responses=0
```

Also confirmed:

1. WebSocket stayed open for an extended session.
2. Multiple `turn_complete` signals were sent successfully (8-second cadence).
3. Audio chunks continued streaming with non-zero RMS windows.

### Current blocker status

Even after explicit `turn_complete` signaling, this run still did not produce server response frames:

- no `CB:onMessage(text) fired`
- no `RAW_ONMESSAGE`
- no `PARSE_INPUT`
- no `PARSED`
- no `AGGREGATOR`

So the first `RAW_ONMESSAGE` line is still not available from this validation pass.

### Test utterance set used for this pass

- `your OTP is 847291 please share it now`
- `your bank account has been blocked immediately`
- `please install AnyDesk on your phone`

These were injected during an active speakerphone call while periodic `turn_complete` was running.
