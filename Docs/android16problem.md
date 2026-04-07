# Android 16 Message Capture Problem and Countermeasures

Date: 2026-04-06

## 1) Problem Summary

On Android 16 and many modern OEM builds, direct and complete message access is restricted for non-default messaging apps.

Observed impact in this project:
- Some notifications arrive with redacted text (for example: "Sensitive notification content hidden").
- Some message events are missed when React context is not ready.
- Message notifications can be duplicated or arrive as multiple rapid updates.
- Active in-chat conversations may not produce notifications, so listener-only capture misses those messages.

---

## 2) What We Implemented to Counter This

### A) Notification payload extraction hardening
Implemented in Android notification service to support multiple payload styles used by different apps and Android versions.

Added extraction fallbacks for:
- Notification.EXTRA_TEXT
- Notification.EXTRA_BIG_TEXT
- Notification.EXTRA_TEXT_LINES
- Notification.EXTRA_MESSAGES
- Notification.EXTRA_REMOTE_INPUT_HISTORY
- tickerText fallback

Result:
- Higher chance of capturing visible message text when available.

### B) Redaction-aware handling
Added logic to detect privacy-redacted text and classify it as hidden instead of treating it as normal content.

Result:
- System still records event metadata, even when text is hidden.

### C) Native queue for bridge downtime
When JS/React context is unavailable, native layer queues events instead of dropping them.

Result:
- Better reliability for events arriving during app startup or temporary bridge unavailability.

### D) Native persistent message store (background-safe)
Added native persistence of captured notification events so data survives when app is closed and can be read later by UI.

Result:
- Message history no longer depends only on app-open JS listeners.

### E) SMS fallback capture channel
Added SMS broadcast receiver path for direct incoming SMS capture (when permission allowed by device policy).

Result:
- Better coverage for incoming SMS even when notification preview is hidden.

### F) Consistency improvements
- Duplicate suppression for rapid repeated events.
- Cross-channel correlation between SMS broadcast and notification path.
- Metadata forwarding for hidden/no-preview events.

Result:
- Cleaner timeline and fewer dropped/duplicated records.

---

## 3) What Is Working Now

- Notification-based capture is functional across targeted messaging apps and general domains.
- Hidden/redacted notifications are still recorded as metadata events.
- Background native persistence captures events even while app UI is not active.
- History Message screen supports viewing and clearing stored events.

---

## 4) Remaining Platform Limits (Not a Bug)

These limits are imposed by Android/app privacy and cannot be fully bypassed via normal public APIs:

1. Redacted notifications
- If OS/app marks content sensitive, body text remains unavailable.

2. Active open-chat messages with no notification
- If user is in chat and source app suppresses notification, notification listener receives nothing.

3. OEM restricted permission behavior
- Some devices block or gate SMS/phone sensitive permissions for sideloaded apps.

4. Full historical inbox access
- Not guaranteed for non-default SMS app architecture.

---

## 5) Open-Chat Problem: Planned Hybrid Solution

To cover active in-chat messages where no notification is posted:

Hybrid design:
1. Keep current NotificationListener path for background and normal cases.
2. Add optional Accessibility Enhanced Mode (toggle in Settings).
3. When enabled, accessibility service monitors selected chat apps and captures visible chat text events while user is actively chatting.
4. Deduplicate and merge accessibility + notification + SMS channels into one message timeline.

Why this solves the gap:
- Notification listener cannot capture messages that never produce notifications.
- Accessibility path fills this exact hole for active conversations.

Important note:
- This mode must be explicit opt-in with clear privacy disclosure.

---

## 6) Demo/Branch Plan

Current step:
- This documentation captures Android 16 challenges and implemented countermeasures.

Next planned steps after pulling friend code:
1. Review friend branch text-intent model integration.
2. Re-implement robust Message History section under friend codebase:
	- show incoming messages
	- show threat-marked messages
	- preserve local persistence and clear controls
3. Implement hybrid open-chat capture solution on top of friend implementation.

---

## 7) Recommended Demo Language

Use this positioning in demo:
- "We provide high-coverage real-time message risk detection using a multi-channel pipeline (notification + SMS fallback + native persistence)."
- "On Android 16, privacy redaction and open-chat suppression are platform constraints; hidden events are still tracked as metadata."
- "Next enhancement adds optional accessibility hybrid mode for active in-chat capture where notifications are not posted."

