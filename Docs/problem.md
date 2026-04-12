# Problem Analysis: False Detections and Missed Detections

## Summary
Scam detection errors in the app do not come from a single issue. They are caused by a chain of factors across input capture, model reasoning, network reliability, and post-processing rules.

## Why False Detections Happen

1. Input is noisy or incomplete
- Notification and accessibility capture can include UI strings (for example: read more, calling, open chat) instead of user intent text.
- Messages can be truncated or partially available.
- If model input is bad, model output can be wrong.

2. Ambiguous short messages
- Text like hlo, yes sir, ok, noted has weak semantic evidence.
- LLMs can over-infer risk on low-information messages unless hard-safe guards are applied.

3. Context leakage
- Recent conversation context can contain risky words.
- The model may mistakenly apply previous risk context to a harmless current line.

4. Category mapping errors
- Even if model output is uncertain, downstream mapping can force a category.
- This can produce wrong labels like OTP_SCAM without OTP evidence in the current message.

5. Precision vs recall tradeoff
- Aggressive scam catching increases false positives.
- Aggressive false-positive reduction increases misses.

## Why Missed Detections Happen

1. Over-correction after false positives
- Strong safe gates can block true scams when scam language is indirect.

2. Hinglish/local phrasing variability
- Real scams use transliteration, slang, bad grammar, and local phrasing.
- Model may miss intent if patterns are not explicit.

3. Multi-turn scam behavior
- Some scams become clear only over multiple messages.
- Single-message analysis may not be sufficient.

4. Incomplete capture
- Critical scam tokens may not be present in the captured snippet.

5. API/runtime failures
- Timeouts, offline state, and rate limits produce no decision.
- Conservative fallback paths can mark risky text as safe.

## Gemini API Key vs Model Accuracy

API key problems usually affect availability, not reasoning quality.

- API key issues cause: no decision, retries, queued analysis, delayed analysis.
- API key issues do not directly cause: semantic misclassification.

Semantic errors are mostly from noisy input, ambiguous text, context contamination, and threshold/mapping policy.

## Why We Do Not Send Every Raw Message to Gemini

1. Cost and quota pressure
- High-volume chats quickly consume API budget and trigger rate limits.

2. Latency and UX impact
- Per-message network calls can create delays and stale alerts.

3. Privacy concerns
- Sending all raw text off-device increases privacy exposure.

4. Battery/data usage
- Always-online processing increases runtime overhead.

5. Noise amplification
- Sending UI/system noise to Gemini increases false inferences.

## Practical Architecture

Best balance for production:

1. Input quality layer
- Drop UI/status/noise text early.

2. Gemini-first reasoning on meaningful text
- Analyze the current message with constrained context.

3. Deterministic safety/evidence guards
- Require direct evidence for high-risk categories (for example OTP_SCAM).
- Enforce safe outcomes for benign short replies and call-status text.

4. Transparent decision trace
- Store why a message was flagged/suppressed for debugging and trust.

## Current Direction Implemented

- Tier3-first message analysis with constrained category taxonomy.
- False-positive controls for greetings/acknowledgements and call-status strings.
- Hard-evidence overrides for obvious scams (authority + verification ask, friend-in-distress money asks).
- OTP category allowed only when OTP-style evidence exists in current message.
