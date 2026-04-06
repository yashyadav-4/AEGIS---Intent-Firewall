import { Platform, PermissionsAndroid, Alert, Linking, NativeModules } from 'react-native';

const { NotificationService } = NativeModules;

export async function checkNotificationPermission(): Promise<boolean> {
  if (Platform.OS === 'android') {
    try {
      return await NotificationService.checkNotificationPermission();
    } catch {
      return false;
    }
  }
  return true;
}

export async function requestNotificationPermission(): Promise<boolean> {
  if (Platform.OS === 'android') {
    try {
      return await NotificationService.requestNotificationPermission();
    } catch {
      return false;
    }
  }
  return true;
}

export async function checkNotificationListenerEnabled(): Promise<boolean> {
  if (Platform.OS === 'android' && NotificationService.checkNotificationListenerEnabled) {
    try {
      return await NotificationService.checkNotificationListenerEnabled();
    } catch {
      return false;
    }
  }
  return false;
}

export async function promptNotificationListenerSetup(): Promise<void> {
  if (Platform.OS === 'android') {
    await NotificationService.promptNotificationListenerSetup();
  }
}

export async function checkCallScreeningPermission(): Promise<boolean> {
  if (Platform.OS === 'android' && NotificationService.checkCallScreeningPermission) {
    try {
      return await NotificationService.checkCallScreeningPermission();
    } catch {
      return false;
    }
  }
  return false;
}

export async function requestCallScreeningPermission(): Promise<void> {
  if (Platform.OS === 'android' && NotificationService.requestCallScreeningPermission) {
    try {
      await NotificationService.requestCallScreeningPermission();
    } catch (e) {
      console.error(e);
    }
  }
}

export async function checkAudioRecordingPermission(): Promise<boolean> {
  if (Platform.OS === 'android') {
    const audio = await PermissionsAndroid.check(PermissionsAndroid.PERMISSIONS.RECORD_AUDIO);
    const phoneState = await PermissionsAndroid.check(PermissionsAndroid.PERMISSIONS.READ_PHONE_STATE);
    return audio && phoneState;
  }
  return true;
}

export async function requestAudioRecordingPermission(): Promise<boolean> {
  if (Platform.OS === 'android') {
    const results = await PermissionsAndroid.requestMultiple([
      PermissionsAndroid.PERMISSIONS.RECORD_AUDIO,
      PermissionsAndroid.PERMISSIONS.READ_PHONE_STATE,
    ]);
    return (
      results[PermissionsAndroid.PERMISSIONS.RECORD_AUDIO] === PermissionsAndroid.RESULTS.GRANTED &&
      results[PermissionsAndroid.PERMISSIONS.READ_PHONE_STATE] === PermissionsAndroid.RESULTS.GRANTED
    );
  }
  return true;
}

export interface PermissionStatus {
  notification: boolean;
  notificationListener: boolean;
  callScreening: boolean;
  audioRecording: boolean;
}

export async function getAllPermissionStatus(): Promise<PermissionStatus> {
  const notification = await checkNotificationPermission();
  const notificationListener = await checkNotificationListenerEnabled();
  const callScreening = await checkCallScreeningPermission();
  const audioRecording = await checkAudioRecordingPermission();
  return { notification, notificationListener, callScreening, audioRecording };
}

export async function requestAllRequiredPermissions(): Promise<PermissionStatus> {
  const audioState = await requestAudioRecordingPermission();
  await requestNotificationPermission();
  await requestCallScreeningPermission();
  return await getAllPermissionStatus();
}

export const openPermissionSettings = async (): Promise<void> => {
  await Linking.openSettings();
};
