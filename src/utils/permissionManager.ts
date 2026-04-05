import {Linking, PermissionsAndroid, Platform} from 'react-native';

export type PermissionSnapshot = {
  recordAudio: boolean;
  readPhoneState: boolean;
  receiveSms: boolean;
  postNotifications: boolean;
};

const getRuntimePermissionList = (): string[] => {
  if (Platform.OS !== 'android') {
    return [];
  }

  const list: string[] = [
    PermissionsAndroid.PERMISSIONS.RECORD_AUDIO,
    PermissionsAndroid.PERMISSIONS.READ_PHONE_STATE,
    PermissionsAndroid.PERMISSIONS.RECEIVE_SMS,
  ];

  if (Platform.Version >= 33) {
    list.push(PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS);
  }

  return list;
};

export const getPermissionSnapshot = async (): Promise<PermissionSnapshot> => {
  if (Platform.OS !== 'android') {
    return {
      recordAudio: true,
      readPhoneState: true,
      receiveSms: true,
      postNotifications: true,
    };
  }

  const recordAudio = await PermissionsAndroid.check(
    PermissionsAndroid.PERMISSIONS.RECORD_AUDIO,
  );
  const readPhoneState = await PermissionsAndroid.check(
    PermissionsAndroid.PERMISSIONS.READ_PHONE_STATE,
  );
  const receiveSms = await PermissionsAndroid.check(
    PermissionsAndroid.PERMISSIONS.RECEIVE_SMS,
  );

  const postNotifications =
    Platform.Version < 33
      ? true
      : await PermissionsAndroid.check(
          PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS,
        );

  return {
    recordAudio,
    readPhoneState,
    receiveSms,
    postNotifications,
  };
};

export const requestRequiredPermissions = async (): Promise<PermissionSnapshot> => {
  if (Platform.OS !== 'android') {
    return {
      recordAudio: true,
      readPhoneState: true,
      receiveSms: true,
      postNotifications: true,
    };
  }

  const permissionList = getRuntimePermissionList();
  if (permissionList.length > 0) {
    await PermissionsAndroid.requestMultiple(permissionList);
  }

  return getPermissionSnapshot();
};

export const openNotificationAccessSettings = async (): Promise<void> => {
  if (Platform.OS !== 'android') {
    return;
  }

  try {
    await Linking.sendIntent('android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS');
  } catch (_error) {
    await Linking.openSettings();
  }
};

export const openAppPermissionSettings = async (): Promise<void> => {
  await Linking.openSettings();
};