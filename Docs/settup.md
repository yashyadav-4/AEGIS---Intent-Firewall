# Intent Firewall Setup Guide (Phone + Laptop)

Date: 2026-04-06

## 1) Laptop Setup

### Prerequisites
- Node.js LTS installed.
- Java (JDK 17 or compatible for this React Native setup).
- Android Studio + Android SDK + platform tools.
- USB debugging enabled phone connected via cable.

### Verify device connection
Command:
```bash
adb devices
```
Use for:
- Confirming phone is visible to adb before build/deploy.

### Install app build on phone
Command:
```bash
npx react-native run-android
```
Use for:
- Building + installing latest app with native code changes.

### Rebuild native Kotlin quickly (validation)
Command:
```bash
Set-Location "c:\Hackathon\Intent firewall\IntentFirewall\android"; .\gradlew.bat :app:compileDebugKotlin
```
Use for:
- Fast compile check after editing Android native files.

### Optional clean reinstall
Commands:
```bash
adb uninstall com.intentfirewall
npx react-native run-android
```
Use for:
- Resetting install if stale permissions/listener state causes odd behavior.

---

## 2) Phone Setup (Required)

### App permissions (inside app Settings screen)
Grant these runtime permissions:
- Microphone
- Phone State
- Receive SMS
- Notifications

### Android special access
Enable Notification Access for Intent Firewall:
- Android Settings -> Notifications -> Special app access -> Notification access -> Intent Firewall -> Allow

### Lock screen privacy settings (recommended)
To improve content visibility on newer Android:
- Settings -> Notifications -> Lock screen notifications -> Show all notification content
- Disable "Hide sensitive notifications" if present

### Messaging app preview settings (recommended)
- In WhatsApp/other apps, enable message preview in notifications.

---

## 3) Listener Health Commands

### Check enabled listeners
```bash
adb shell settings get secure enabled_notification_listeners
```
Use for:
- Confirming `com.intentfirewall/com.intentfirewall.NotificationService` is present.

### Re-enable listener if missing
```bash
adb shell cmd notification allow_listener com.intentfirewall/com.intentfirewall.NotificationService
```
Use for:
- Restoring notification listener after OEM/system removes it.

### Verify listener bind state
```bash
adb shell dumpsys notification listeners | findstr /I "com.intentfirewall NotificationService"
```
Use for:
- Confirming listener service is registered and visible in notification manager state.

---

## 4) Runtime Testing Commands

### Focused app logs (best for debugging)
```bash
adb logcat -c
adb logcat -v time IntentFirewall:D *:S
```
Use for:
- Seeing only Intent Firewall native logs.

Expected log examples:
- Visible notification:
	- `Notification received from: com.whatsapp | ... | Redacted: false`
- Hidden notification metadata forwarding:
	- `Metadata-only event forwarded for ... (redacted=true, empty=false)`
- SMS broadcast fallback:
	- `SMS broadcast captured from: <sender> | Text: <message>`

---

## 5) What Works vs Limitations

### Works reliably now
- WhatsApp/Telegram/SMS notification interception (metadata always, content when visible).
- SMS body fallback through telephony broadcast receiver.
- Deduplication and delayed hidden-forwarding for cleaner records.

### Still limited by Android/app privacy
- Some notifications remain redacted (`Sensitive notification content hidden`).
- Full message body is not guaranteed on Android 16 for all apps/scenarios.
- For hidden content, app still captures event metadata and labels visibility state.

---

## 6) Quick End-to-End Test Checklist

1. Run `npx react-native run-android`.
2. Open app once after install.
3. Grant runtime permissions from app Settings.
4. Enable Notification Access for Intent Firewall.
5. Start logs:
	 - `adb logcat -c`
	 - `adb logcat -v time IntentFirewall:D *:S`
6. Send:
	 - WhatsApp message (visible and/or hidden case)
	 - SMS message (validate SMS broadcast fallback)
7. Confirm expected logs appear.

