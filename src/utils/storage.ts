import AsyncStorage from '@react-native-async-storage/async-storage';

export interface Threat {
  id: string;
  app: string;
  appIcon: string;
  message: string;
  category: string;
  confidence: number;
  blocked: boolean;
  time: string;
  timestamp: number;
}

export interface Settings {
  autoBlock: boolean;
  strictMode: boolean;
  notifications: boolean;
  vibration: boolean;
  messageProtection: boolean;
  callProtection: boolean;
}

const THREATS_KEY = 'threats';
const SETTINGS_KEY = 'settings';

const DEFAULT_SETTINGS: Settings = {
  autoBlock: false,
  strictMode: false,
  notifications: true,
  vibration: true,
  messageProtection: false,
  callProtection: false,
};

// ─── Threats ───────────────────────────────────────────

export const saveThreat = async (threat: Omit<Threat, 'id' | 'timestamp'>) => {
  try {
    const existing = await getThreats();
    const newThreat: Threat = {
      ...threat,
      id: Date.now().toString(),
      timestamp: Date.now(),
    };
    const updated = [newThreat, ...existing];
    await AsyncStorage.setItem(THREATS_KEY, JSON.stringify(updated));
    return newThreat;
  } catch (e) {
    console.error('Error saving threat:', e);
  }
};

export const getThreats = async (): Promise<Threat[]> => {
  try {
    const data = await AsyncStorage.getItem(THREATS_KEY);
    return data ? JSON.parse(data) : [];
  } catch (e) {
    console.error('Error getting threats:', e);
    return [];
  }
};

export const clearThreats = async () => {
  try {
    await AsyncStorage.removeItem(THREATS_KEY);
  } catch (e) {
    console.error('Error clearing threats:', e);
  }
};

export const updateThreatBlocked = async (id: string, blocked = true) => {
  try {
    const existing = await getThreats();
    const updated = existing.map(t => (t.id === id ? {...t, blocked} : t));
    await AsyncStorage.setItem(THREATS_KEY, JSON.stringify(updated));
  } catch (e) {
    console.error('Error updating threat blocked state:', e);
  }
};

// ─── Settings ──────────────────────────────────────────

export const saveSettings = async (settings: Partial<Settings>) => {
  try {
    const current = await getSettings();
    const updated = {...current, ...settings};
    await AsyncStorage.setItem(SETTINGS_KEY, JSON.stringify(updated));
    return updated;
  } catch (e) {
    console.error('Error saving settings:', e);
  }
};

export const getSettings = async (): Promise<Settings> => {
  try {
    const data = await AsyncStorage.getItem(SETTINGS_KEY);
    return data ? {...DEFAULT_SETTINGS, ...JSON.parse(data)} : DEFAULT_SETTINGS;
  } catch (e) {
    console.error('Error getting settings:', e);
    return DEFAULT_SETTINGS;
  }
};

// ─── Helpers ───────────────────────────────────────────

export const formatTime = (timestamp: number): string => {
  const diff = Date.now() - timestamp;
  const minutes = Math.floor(diff / 60000);
  const hours = Math.floor(diff / 3600000);
  const days = Math.floor(diff / 86400000);

  if (minutes < 1) return 'Just now';
  if (minutes < 60) return `${minutes} min ago`;
  if (hours < 24) return `${hours} hours ago`;
  if (days === 1) return 'Yesterday';
  return `${days} days ago`;
};