# AEGIS-ZERO: Call Intelligence Testing Protocol

Use this prompt/protocol for rigorous real-world testing of the Call Capture, Deepfake Detection, and Intent Classification system.

---

## 🥼 TESTER PROMPT: E2E Call Simulation

**Role:** You are testing the Aegis-Zero Call Intelligence system on a physical Android device. You will simulate extreme edge cases where a scammer combines deepfake audio and multi-turn phishing tactics in real-time.

### TEST 1: The Trigger Check (Call Capture Resilience)
**Objective:** Ensure the system wakes up and intercepts audio IMMEDIATELY upon a call state change, regardless of the app being in the background or killed.
**Steps:**
1. Force-close the Aegis-Zero app.
2. Initiate an incoming call from a known test number.
3. **Expected Behavior:** `AegisCallMonitor` must launch as a Foreground Service within 1000ms. The `BulletproofCallCapture` must acquire the `VOICE_COMMUNICATION` stream without crashing or returning silent buffers. Check logcat for `[AegisCapture] Strategy 1 Active` or fallback active notifications.

### TEST 2: Real-Time Hinglish/Hindi Phishing (Intent Detection)
**Objective:** Verify the sliding context buffer processes Hinglish/Hindi spoken natively and triggers the kill-chain intent graph.
**Scenario (Spoken over the call):**
- *Turn 1 (0:05):* "Namaste sir, main CBI branch Delhi se Inspector Sharma bol raha hu." (Wait 3 seconds)
- *Turn 2 (0:15):* "Aapke naam par ek FedEx parcel intercept hua hai jisme illegal items hain. Aapke upar arrest warrant issue ho gaya hai." (Wait 3 seconds)
- *Turn 3 (0:25):* "Abhi aapko fine bharne ke liye digital payment karna padega warna police turant aapke ghar aayegi. Payment link bhej raha hu."
**Expected Behavior:** 
1. The Real-time Transcript analyzer must map sequences into `AUTHORITY_HINDI` -> `URGENCY_HINGLISH` -> `FINANCIAL_HINGLISH`.
2. Overall threat score breaches `<0.90>` threshold.
3. The Full-Screen `FrictionWarningActivity` (BLOCK & HANG UP) must suddenly override the dialer screen, preventing the user from taking out their wallet or opening a payment app.

### TEST 3: Deepfake Detection (Audio Injection)
**Objective:** Validate that the system correctly profiles a synthetic, AI-generated voice in real time, independently of what is being said.
**Steps:**
1. Connect via a second device. Play an AI-cloned audio clip (e.g., ElevenLabs or VITS clone of a family member's voice) continuously.
2. The audio should say benign things: "Hey, it's me. I got a new phone."
**Expected Behavior:** 
1. Within 2-3 standard audio windows (approx. 4-6 seconds), the Phase LFCC and Glottal flow scalers must flag synthetic anomalies.
2. `Audio Deepfake Detector` AUC registers an `isSynthetic=true` flag.
3. A High-Friction UI interrupt launches, asserting: **"SYNTHETIC VOICE DETECTED: This is likely a deepfake clone. DO NOT trust caller identity."**

### TEST 4: The Bypass Stress Test (Accessibility Fallback)
**Objective:** Trigger the `AccessibilityService` fallback by forcing a microphone lock.
**Steps:**
1. Open a background app like a voice memo recorder that forcibly holds the `MIC` lock.
2. Receive a phone call.
**Expected Behavior:** 
1. Standard `VOICE_COMMUNICATION` and `MIC` routes will fail (`AudioCaptureException`).
2. `BulletproofCallCapture` dynamically re-routes to `AccessibilityService` to forcefully scrape Live Captions/Transcripts or execute telecom level bridging, reporting: `"System Mic Locked: Falling back to Accessibility Capture"`.

---
