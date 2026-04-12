import AsyncStorage from '@react-native-async-storage/async-storage';

export interface Threat {
  id: string;
  app: string;
  appIcon: string;
  message: string;
  context?: string;
  category: string;
  confidence: number;
  blocked: boolean;
  time: string;
  timestamp: number;
}

export interface MessageEvent {
  id: string;
  app: string;
  appIcon: string;
  title: string;
  message: string;
  packageName: string;
  matchedCategory: string;
  flagged: boolean;
  source: 'notification' | 'open_chat' | 'sms_direct';
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
const MESSAGES_KEY = 'messages';
const SETTINGS_KEY = 'settings';
let messageWriteQueue: Promise<void> = Promise.resolve();

const normalizeForDedupe = (value: string): string =>
  value.toLowerCase().replace(/\s+/g, ' ').trim();

const detectMessageSource = (
  event: Omit<MessageEvent, 'id' | 'timestamp' | 'source'> & {source?: MessageEvent['source']},
): MessageEvent['source'] => {
  if (event.source) {
    return event.source;
  }
  if (event.matchedCategory === 'OPEN_CHAT') {
    return 'open_chat';
  }
  if (event.matchedCategory === 'SMS_BROADCAST_DIRECT') {
    return 'sms_direct';
  }
  return 'notification';
};

const DEFAULT_SETTINGS: Settings = {
  autoBlock: false,
  strictMode: false,
  notifications: true,
  vibration: true,
  messageProtection: false,
  callProtection: false,
};

// ─── Threats ───────────────────────────────────────────

export const saveThreat = async (
  threat: Omit<Threat, 'id' | 'timestamp'> & {timestamp?: number},
) => {
  console.log("[saveThreat] saving threat:", threat);
  try {
    const existing = await getThreats();
    const ts =
      typeof threat.timestamp === 'number' && Number.isFinite(threat.timestamp)
        ? Math.floor(threat.timestamp)
        : Date.now();

    const normalizedIncoming = normalizeForDedupe(threat.message || '');
    const duplicate = existing.slice(0, 40).find(item => {
      const sameApp = item.app === threat.app;
      const sameCategory = item.category === threat.category;
      const sameMessage = normalizeForDedupe(item.message || '') === normalizedIncoming;
      const closeInTime = Math.abs(ts - item.timestamp) <= 10_000;
      return sameApp && sameCategory && sameMessage && closeInTime;
    });

    if (duplicate) {
      return duplicate;
    }

    const newThreat: Threat = {
      ...threat,
      id: Date.now().toString(),
      timestamp: ts,
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
    const parsed: Threat[] = data ? JSON.parse(data) : [];
    return parsed.sort((a, b) => (b.timestamp || 0) - (a.timestamp || 0));
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

export const deleteThreatById = async (id: string) => {
  try {
    const existing = await getThreats();
    const updated = existing.filter(t => t.id !== id);
    await AsyncStorage.setItem(THREATS_KEY, JSON.stringify(updated));
  } catch (e) {
    console.error('Error deleting threat:', e);
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

// ─── Message Events ───────────────────────────────────

export const saveMessageEvent = async (
  event: Omit<MessageEvent, 'id' | 'timestamp' | 'source'> & {
    source?: MessageEvent['source'];
    eventTimestamp?: number;
  },
) => {
  let savedEvent: MessageEvent | undefined;

  messageWriteQueue = messageWriteQueue.then(async () => {
    try {
      const existing = await getMessageEvents();
      const normalizedMessage = normalizeForDedupe(event.message || '');
      const normalizedTitle = normalizeForDedupe(event.title || '');
      const source = detectMessageSource(event);
      const eventTs =
        typeof event.eventTimestamp === 'number' && Number.isFinite(event.eventTimestamp)
          ? Math.floor(event.eventTimestamp)
          : Date.now();

      if (!normalizedMessage && !normalizedTitle) {
        return;
      }

      const duplicate = existing.slice(0, 40).find(item => {
        const closeInTime = Math.abs(eventTs - item.timestamp) <= 8000;
        if (!closeInTime) return false;

        const sameApp = item.app === event.app;
        const sameMessage = normalizeForDedupe(item.message || '') === normalizedMessage;
        const sameTitle = normalizeForDedupe(item.title || '') === normalizedTitle;

        if (sameApp && source === 'open_chat' && item.source === 'open_chat') {
          const existingMessage = normalizeForDedupe(item.message || '');
          const partialOverlap =
            existingMessage.includes(normalizedMessage) ||
            normalizedMessage.includes(existingMessage);
          if (partialOverlap) {
            return true;
          }
        }

        return sameApp && sameMessage && sameTitle;
      });

      if (duplicate) {
        return;
      }

      const newEvent: MessageEvent = {
        ...event,
        source,
        id: Date.now().toString() + Math.random().toString(36).slice(2, 7),
        timestamp: eventTs,
      };
      const updated = [newEvent, ...existing].slice(0, 500);
      await AsyncStorage.setItem(MESSAGES_KEY, JSON.stringify(updated));
      savedEvent = newEvent;
    } catch (e) {
      console.error('Error saving message event:', e);
    }
  });

  await messageWriteQueue;
  return savedEvent;
};

export const getMessageEvents = async (): Promise<MessageEvent[]> => {
  try {
    const data = await AsyncStorage.getItem(MESSAGES_KEY);
    const parsed: MessageEvent[] = data ? JSON.parse(data) : [];
    return parsed.sort((a, b) => (b.timestamp || 0) - (a.timestamp || 0));
  } catch (e) {
    console.error('Error getting message events:', e);
    return [];
  }
};

export const clearMessageEvents = async () => {
  try {
    await AsyncStorage.removeItem(MESSAGES_KEY);
  } catch (e) {
    console.error('Error clearing message events:', e);
  }
};