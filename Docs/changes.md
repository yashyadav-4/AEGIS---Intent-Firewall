# Intent Firewall Changes Log (Android 16 Notification Compatibility)

Date: 2026-04-06

## Goal
Improve message notification handling for Android 16+ where direct SMS/message content access is restricted or redacted.

## What Was Changed

### 1) Notification parsing was upgraded for modern Android payloads
File changed:
- android/app/src/main/java/com/intentfirewall/NotificationService.kt

Changes:
- Replaced narrow text extraction with multi-source extraction.
- Added support for these notification fields:
	- Notification.EXTRA_TEXT
	- Notification.EXTRA_BIG_TEXT
	- Notification.EXTRA_TEXT_LINES
	- Notification.EXTRA_MESSAGES (MessagingStyle)
	- Notification.EXTRA_REMOTE_INPUT_HISTORY
	- notification.tickerText fallback
- Added support for Samsung messaging package:
	- com.samsung.android.messaging

Why:
- Android 14/15/16 and different OEM/message apps provide content in different extras.
- This increases chances of reading visible content when it is actually provided by the OS.

---

### 2) Native event caching was added so notifications are not lost
File changed:
- android/app/src/main/java/com/intentfirewall/NotificationEventEmitter.kt

Changes:
- Added persistent pending queue in SharedPreferences.
- If React context is not ready, notification payload is cached.
- Added flushPending(context) to emit cached events when app is active.
- Added max queue size guard (MAX_PENDING = 50).

Why:
- On modern Android, notifications can arrive while RN bridge is not initialized.
- Without cache, those events are dropped.

---

### 3) App resume now flushes pending native notifications
File changed:
- android/app/src/main/java/com/intentfirewall/MainActivity.kt

Changes:
- Added onResume() hook to call:
	- NotificationEventEmitter.flushPending(this)

Why:
- Ensures cached notifications are delivered after app returns to foreground.

---

### 4) Redaction-aware diagnostics were added
File changed:
- android/app/src/main/java/com/intentfirewall/NotificationService.kt

Changes:
- Logs now include package, title, text, and redaction status in one line.
- Added isLikelyRedacted() check for patterns like:
	- "Sensitive notification content hidden"
- If content is empty/redacted, classifier is skipped and debug keys are logged.

Why:
- Makes it obvious whether issue is parser failure vs OS-level redaction.

## Countermeasures for Android 16+

### A) Platform-safe ingestion path
- Use NotificationListenerService instead of direct SMS inbox reads.
- This is the intended and policy-safe path for non-default SMS apps.

### B) Permission/listener recovery
- Listener can silently be removed by system/OEM.
- Recovery command:
	- adb shell cmd notification allow_listener com.intentfirewall/com.intentfirewall.NotificationService

### C) Redaction handling
- If OS/app marks notification as sensitive, full content is not available via public APIs.
- App now detects redaction and avoids false/garbage AI classification.
- App waits for later unredacted updates when available.

### D) Validation and monitoring commands
- Check listener enabled:
	- adb shell settings get secure enabled_notification_listeners
- Check listener binding:
	- adb shell dumpsys notification listeners | findstr /I "com.intentfirewall NotificationService"
- Focused app logs:
	- adb logcat -v time IntentFirewall:D *:S

## Known Limitation (Important)
- On Android 16+, if framework/app privacy redacts content, no legal/public API can force-decrypt notification text.
- Root/system-level access may bypass some checks, but normal production apps cannot rely on that.

## Build Verification
- Kotlin compile task executed successfully after patches:
	- :app:compileDebugKotlin -> BUILD SUCCESSFUL

## Is Reading Messages on Android 16 Impossible?
- Direct inbox reading for SMS/chat content is heavily restricted for normal apps on Android 16.
- For non-default SMS apps, reading full SMS database content is generally not allowed.
- NotificationListenerService is still valid, but it only receives what Android and the source app expose.
- If notification text is redacted as sensitive by OS/app/privacy settings, content cannot be programmatically recovered via public APIs.

Practical conclusion:
- Not fully impossible to detect message risk on Android 16.
- But full message text is not guaranteed for every notification.
- Best production approach is:
	- NotificationListenerService ingestion
	- redaction detection
	- graceful fallback when content is hidden

## Successful Commands Used and Why

1. Build validation
- Command:
	- Set-Location "c:\Hackathon\Intent firewall\IntentFirewall\android"; .\gradlew.bat :app:compileDebugKotlin
- Used for:
	- Verifying Kotlin/Android code compiles after each patch.
- Result:
	- BUILD SUCCESSFUL

2. Device connectivity check
- Command:
	- adb devices
- Used for:
	- Confirming target Android device is connected and available to adb.
- Result:
	- Device detected in device state.

3. Check enabled notification listeners
- Command:
	- adb shell settings get secure enabled_notification_listeners
- Used for:
	- Verifying whether Intent Firewall listener is currently authorized.
- Result:
	- Initially missing, later included com.intentfirewall/com.intentfirewall.NotificationService.

4. Inspect listener bind/connect status
- Command:
	- adb shell dumpsys notification listeners
- Used for:
	- Deep inspection of listener registration and service binding state.
- Result:
	- Confirmed listener records and service presence in notification manager state.

5. Re-enable listener permission
- Command:
	- adb shell cmd notification allow_listener com.intentfirewall/com.intentfirewall.NotificationService
- Used for:
	- Restoring listener access after it was removed/disabled.
- Result:
	- Command completed and subsequent checks showed listener enabled.

6. Focused listener verification output
- Command:
	- adb shell dumpsys notification listeners | findstr /I "intentfirewall NotificationService"
- Used for:
	- Quickly confirming service is listed without reading full dumpsys output.
- Result:
	- Matched entries for com.intentfirewall NotificationService found.

7. Notification visibility settings checks
- Commands:
	- adb shell settings get secure lock_screen_show_notifications
	- adb shell settings get secure lock_screen_allow_private_notifications
	- adb shell settings get global heads_up_notifications_enabled
- Used for:
	- Confirming device-level notification visibility and private content settings.
- Result:
	- Returned enabled values on this test device.

## Additional Consistency Improvements (Implemented)

Date: 2026-04-06 (latest update)

### 1) WhatsApp and chat notification consistency updates
File changed:
- android/app/src/main/java/com/intentfirewall/NotificationService.kt

Changes:
- Added duplicate suppression window to avoid repeated identical events from rapid notification updates.
- Added metadata forwarding for hidden/empty notifications instead of dropping them.
- Added fallback text payloads:
	- [Hidden by Android privacy settings]
	- [No preview available for this notification]
- Added clear metadata categories:
	- HIDDEN_BY_OS
	- NO_PREVIEW

Result:
- WhatsApp notifications are surfaced more consistently in the app even when text body is hidden.

### 2) Direct SMS fallback receiver for consistency
Files changed:
- android/app/src/main/AndroidManifest.xml
- android/app/src/main/java/com/intentfirewall/SmsReceiver.kt (new)

Changes:
- Added RECEIVE_SMS permission.
- Added SmsReceiver for android.provider.Telephony.SMS_RECEIVED.
- Captures SMS body from telephony broadcast and forwards to RN pipeline via NotificationEventEmitter.

Result:
- SMS messages can still be captured consistently even when notification previews are hidden.
- This helps Google Messages SMS cases where notification text may be redacted.

### 3) Runtime permission support for SMS receive
Files changed:
- src/utils/permissionManager.ts
- src/screens/SettingsScreen.tsx

Changes:
- Added RECEIVE_SMS to runtime permission request/check list.
- Added Receive SMS status row in Settings screen.
- Updated permission readiness check to include receiveSms.

Result:
- SMS fallback channel is now permission-complete from UI and runtime flow.

### Build status after latest implementation
- Command:
	- Set-Location "c:\Hackathon\Intent firewall\IntentFirewall\android"; .\gradlew.bat :app:compileDebugKotlin
- Result:
	- BUILD SUCCESSFUL

## Reliability Upgrade (Redacted-to-Visible + Cross-Channel Correlation)

Date: 2026-04-06 (latest)

### 1) Redacted-to-visible upgrade window
File changed:
- android/app/src/main/java/com/intentfirewall/NotificationService.kt

Changes:
- Added short delay window for hidden/empty notification forwarding (UPGRADE_WINDOW_MS = 900 ms).
- Hidden metadata event is not emitted immediately.
- If a richer visible update for the same notification key arrives in that window, pending hidden event is cancelled.

Result:
- Reduced noisy "hidden" entries when WhatsApp posts a redacted update followed by visible text update.

### 2) Cross-channel dedup and correlation
Files changed:
- android/app/src/main/java/com/intentfirewall/MessageConsistencyCoordinator.kt (new)
- android/app/src/main/java/com/intentfirewall/NotificationService.kt
- android/app/src/main/java/com/intentfirewall/SmsReceiver.kt

Changes:
- Added centralized signature dedup logic for rapid duplicate notification events.
- Added recent SMS memory window for correlation with notification channel.
- If same SMS body was already captured via broadcast, redundant SMS notification event can be skipped.
- Added duplicate SMS broadcast suppression by sender+body.

Result:
- Cleaner timeline, fewer duplicate records, and more consistent capture across NotificationListener + SMS broadcast channels.

