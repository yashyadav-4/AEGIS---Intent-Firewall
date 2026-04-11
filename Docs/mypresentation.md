# Intent Firewall: Android Architecture & Implementation

Hi everyone, my name is [Your Name], and I'll be walking you through the Android architecture of the **Intent Firewall** project. While my teammates focused on the DeepSeek AI model and the UI, my responsibility was bringing the core engine to life on the Android platform. 

Our goal was to create a robust, device-level firewall that catches scam attempts across various channels before the user can even fall for them.

---

## 1. What We Do
The Android app acts as the frontline defense mechanism. It silently runs in the background and contextually intercepts incoming communication—whether that's an SMS, a WhatsApp message, a Telegram chat, or even phone calls. It extracts the raw conversational data, feeds it to our detection pipeline, and triggers a real-time Scam Alert if it suspects Phishing, OTP stealing, Authority Impersonation, or Payment scams.

---

## 2. Which Services We Implemented

To reliably capture data across a heavily sandboxed OS like Android, we had to implement a suite of specialized background services:

*   **`OpenChatAccessibilityService` (Accessibility Service):**
    *   **Purpose:** Reads dynamic screen content in real-time.
    *   **Why:** Traditional apps only rely on Notifications. If the user already has WhatsApp open and is actively chatting with a scammer, there are no notifications. By parsing the UI node tree, we extract raw text candidates right off the screen.
*   **`NotificationService` (NotificationListenerService):**
    *   **Purpose:** Captures incoming push notifications.
    *   **Why:** For broad coverage of incoming messages while the device is locked or the user is inside another app.
*   **`SmsReceiver` (BroadcastReceiver):**
    *   **Purpose:** Intercepts traditional SMS texts natively.
*   **Audio/Call Monitors (`AegisCallMonitor`, `AegisAudioDetector`):**
    *   **Purpose:** Hooks into telephony states and audio streams to detect active calls, analyzing conversational context for real-time voice scams.

---

## 3. How the Architecture Works

Our architecture is designed around a **Multi-Tiered Detection Pipeline** (`DetectionPipeline.kt`):

1.  **Data Ingestion & Cleaning:**
    *   When the Accessibility Service picks up a screen change, it recursively scores text nodes. It prefers texts near the bottom of the screen (latest messages) and filters out UI noise like "online", "typing", or "Search".
2.  **Context Buffering (`ContextBuffer`):**
    *   A single message like "Send it now" isn't inherently bad. We store recent message turns in a temporary conversational memory buffer.
3.  **Tier 1 / Legacy Filtering (Regex & Heuristics):**
    *   We use immediate regex rules (`RegexSentinel.kt`) to check for hard-evidence scams (like obvious phishing URLs or blatant authority impersonations + OTP requests) or fast-pass obvious benign greetings.
4.  **Tier 3 AI Hand-off:**
    *   If a message is ambiguous but has medium-risk signals, we hand off the context buffer to the AI classifier (`GeminiScamClassifier.kt` / DeepSeek).
5.  **Alerting & Emission (`ScamAlertNotifier`):**
    *   Once a flag is raised with high confidence, an immediate blocking alert is pushed over the UI.

---

## 4. Why We Did It This Way

*   **Zero-Day Threat Coverage:** Using Accessibility services allows us to bypass SDK restrictions. We don't need official API access to WhatsApp or Telegram to protect the user.
*   **Context over Keywords:** By buffering conversations, the AI model has enough context to realize the difference between a friend asking for 50 bucks and a scammer impersonating a friend asking for 50 bucks.
*   **Performance & Battery:** Running AI models on every single screen update would kill the battery. The multi-tiered approach ensures we drop 90% of safe UI interactions locally, only engaging the heavy AI processing when risk signals trigger.

---

## 5. What Can We Optimize Next?

While the current architecture is highly effective, there are key areas for optimization as we scale:

1.  **Battery Efficiency in DOM Traversal:** 
    Currently, our Accessibility Service triggers on every window state change. We can optimize the tree-search algorithm by caching static node IDs instead of recursively traversing the whole view hierarchy every time.
2.  **On-Device Small Language Models (SLMs):**
    Right now we rely on API calls for the Tier 3 analysis. By quantizing a smaller LLM and running it locally (e.g., via ONNX Runtime or MediaPipe), we could achieve zero-latency detections completely offline, guaranteeing 100% privacy and zero server costs.
3.  **Cross-App Context Stitching:**
    If a scammer texts via SMS, then moves the user to WhatsApp, tracking that continuity is hard. We can optimize our `ContextBuffer` to track "Threat Actors" across different apps using phone numbers or handles as unified identifiers.
