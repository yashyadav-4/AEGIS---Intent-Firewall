import {NativeModules} from 'react-native';

export interface NativeStoredMessage {
  appName: string;
  title: string;
  text: string;
  packageName: string;
  flagged: boolean;
  matchedCategory: string;
  context: string;
  timestamp: number;
}

const storeModule = NativeModules.NotificationStoreModule as {
  getStoredMessages?: () => Promise<string>;
  clearStoredMessages?: () => Promise<boolean>;
};

export const getStoredMessages = async (): Promise<NativeStoredMessage[]> => {
  if (!storeModule?.getStoredMessages) {
    return [];
  }

  try {
    const raw = await storeModule.getStoredMessages();
    const parsed = JSON.parse(raw ?? '[]');
    return Array.isArray(parsed) ? parsed : [];
  } catch (_error) {
    return [];
  }
};

export const clearStoredMessages = async (): Promise<void> => {
  if (!storeModule?.clearStoredMessages) {
    return;
  }

  try {
    await storeModule.clearStoredMessages();
  } catch (_error) {
    // Ignore clear errors to avoid blocking UI.
  }
};
