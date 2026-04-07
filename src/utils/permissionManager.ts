import { Platform, PermissionsAndroid, Alert, Linking, NativeModules } from 'react-native';

const { NotificationService } = NativeModules;

export async function checkNotificationPermission(): Promise<boolean> {
  if (Platform.OS !== 'android') return true;

  if (Platform.Version >= 33) {
    return PermissionsAndroid.check(PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS);
  }

  return true;
}

export async function requestNotificationPermission(): Promise<boolean> {
  if (Platform.OS !== 'android') return true;

  if (Platform.Version >= 33) {
    const result = await PermissionsAndroid.request(
      PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS,
    );
    return result === PermissionsAndroid.RESULTS.GRANTED;
  }

  return true;
}

export async function checkNotificationListenerEnabled(): Promise<boolean> {
  if (Platform.OS !== 'android') return true;

  if (NotificationService?.checkNotificationListenerEnabled) {
    try {
      return await NotificationService.checkNotificationListenerEnabled();
    } catch (error) {
      console.warn('Native checkNotificationListenerEnabled failed:', error);
    }
  }

  return false;
}

export async function checkAccessibilityServiceEnabled(): Promise<boolean> {
  if (Platform.OS !== 'android') return true;

  if (NotificationService?.checkAccessibilityServiceEnabled) {
    try {
      return await NotificationService.checkAccessibilityServiceEnabled();
    } catch (error) {
      console.warn('Native checkAccessibilityServiceEnabled failed:', error);
    }
  }

  return false;
}

export async function promptNotificationListenerSetup(): Promise<void> {
  if (Platform.OS !== 'android') return;

  if (NotificationService?.promptNotificationListenerSetup) {
    try {
      await NotificationService.promptNotificationListenerSetup();
      return;
    } catch (error) {
      console.warn('Native promptNotificationListenerSetup failed:', error);
    }
  }

  Alert.alert(
    'Enable Notification Access',
    'Please enable notification access for Intent Firewall.',
    [
      { text: 'Cancel', style: 'cancel' },
      {
        text: 'Open Settings',
        onPress: () => Linking.openSettings().catch(() => {}),
      },
    ],
  );
}

export async function promptAccessibilityServiceSetup(): Promise<void> {
  if (Platform.OS !== 'android') return;

  if (NotificationService?.promptAccessibilityServiceSetup) {
    try {
      await NotificationService.promptAccessibilityServiceSetup();
      return;
    } catch (error) {
      console.warn('Native promptAccessibilityServiceSetup failed:', error);
    }
  }

  Alert.alert(
    'Enable Accessibility Access',
    'Please enable accessibility access for Intent Firewall to capture open-chat messages.',
    [
      { text: 'Cancel', style: 'cancel' },
      {
        text: 'Open Settings',
        onPress: () => Linking.openSettings().catch(() => {}),
      },
    ],
  );
}

export async function checkCallScreeningPermission(): Promise<boolean> {
  if (Platform.OS !== 'android') return true;

  if (NotificationService?.checkCallScreeningPermission) {
    try {
      return await NotificationService.checkCallScreeningPermission();
    } catch (error) {
      console.warn('Native checkCallScreeningPermission failed:', error);
    }
  }

  return false;
}

export async function requestCallScreeningPermission(): Promise<boolean> {
  if (Platform.OS !== 'android') return true;

  if (NotificationService?.requestCallScreeningPermission) {
    try {
      await NotificationService.requestCallScreeningPermission();
    } catch (error) {
      console.warn('Native requestCallScreeningPermission failed:', error);
    }
  }

  return checkCallScreeningPermission();
}

export async function checkSmsPermission(): Promise<boolean> {
  if (Platform.OS !== 'android') return true;
  return PermissionsAndroid.check(PermissionsAndroid.PERMISSIONS.RECEIVE_SMS);
}

export async function requestSmsPermission(): Promise<boolean> {
  if (Platform.OS !== 'android') return true;

  const result = await PermissionsAndroid.request(
    PermissionsAndroid.PERMISSIONS.RECEIVE_SMS,
    {
      title: 'SMS Access',
      message: 'Intent Firewall needs SMS permission to capture SMS scam attempts.',
      buttonPositive: 'Allow',
      buttonNegative: 'Deny',
    },
  );

  return result === PermissionsAndroid.RESULTS.GRANTED;
}

export async function checkAudioRecordingPermission(): Promise<boolean> {
  if (Platform.OS !== 'android') return true;
  return PermissionsAndroid.check(PermissionsAndroid.PERMISSIONS.RECORD_AUDIO);
}

export async function requestAudioRecordingPermission(): Promise<boolean> {
  if (Platform.OS !== 'android') return true;

  const result = await PermissionsAndroid.request(
    PermissionsAndroid.PERMISSIONS.RECORD_AUDIO,
  );

  return result === PermissionsAndroid.RESULTS.GRANTED;
}

export interface PermissionStatus {
  notification: boolean;
  notificationListener: boolean;
  accessibilityService: boolean;
  callScreening: boolean;
  audioRecording: boolean;
  sms: boolean;
}

export async function getAllPermissionStatus(): Promise<PermissionStatus> {
  const [notification, notificationListener, accessibilityService, callScreening, audioRecording, sms] = await Promise.all([
    checkNotificationPermission(),
    checkNotificationListenerEnabled(),
    checkAccessibilityServiceEnabled(),
    checkCallScreeningPermission(),
    checkAudioRecordingPermission(),
    checkSmsPermission(),
  ]);

  return {
    notification,
    notificationListener,
    accessibilityService,
    callScreening,
    audioRecording,
    sms,
  };
}

export async function requestAllRequiredPermissions(): Promise<PermissionStatus> {
  // Trigger Android runtime dialogs in sequence from a single button tap.
  await requestNotificationPermission();
  await requestAudioRecordingPermission();
  await requestSmsPermission();
  await requestCallScreeningPermission();

  const listenerEnabled = await checkNotificationListenerEnabled();
  if (!listenerEnabled) {
    await promptNotificationListenerSetup();
  }

  const accessibilityEnabled = await checkAccessibilityServiceEnabled();
  if (!accessibilityEnabled) {
    await promptAccessibilityServiceSetup();
  }

  return getAllPermissionStatus();
}

export const openPermissionSettings = async (): Promise<void> => {
  try {
    await Linking.openSettings();
  } catch (error) {
    Alert.alert('Unable to open settings', 'Please open app settings manually.');
  }
};
