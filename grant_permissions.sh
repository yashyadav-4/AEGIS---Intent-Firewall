#!/bin/bash
# Grant to all connected devices silently
DEVICES=$(adb devices | grep -w "device" | awk '{print $1}')
pkg="com.intentfirewall"

for d in $DEVICES; do
    echo "Running ADB setup on $d..."
    adb -s $d shell pm grant $pkg android.permission.POST_NOTIFICATIONS
    adb -s $d shell pm grant $pkg android.permission.RECORD_AUDIO
    adb -s $d shell cmd notification allow_listener $pkg/$pkg.NotificationService
    
    # Try adding the Call Screening Role silently
    adb -s $d shell cmd role add-role-holder android.app.role.CALL_SCREENING $pkg 2>/dev/null
    
    # Allow overlays
    adb -s $d shell appops set $pkg SYSTEM_ALERT_WINDOW allow
    
    echo "✅ Permissions injected on $d"
done
