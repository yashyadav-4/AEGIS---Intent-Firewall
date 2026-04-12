// IntentFirewall Debug Logger

export const Logger = {
  d: (tag: string, message: string, data?: any) => {
    console.log(`[IntentFirewall][${tag}] ${message}`, data ?? '');
  },

  e: (tag: string, message: string, error?: any) => {
    console.error(`[IntentFirewall][${tag}] ${message}`, error ?? '');
  },

  w: (tag: string, message: string, data?: any) => {
    console.warn(`[IntentFirewall][${tag}] ${message}`, data ?? '');
  },

  i: (tag: string, message: string, data?: any) => {
    console.log(`[IntentFirewall][${tag}] ${message}`, data ?? '');
  },
};

export const Tags = {
  NOTIFICATION: 'NOTIF',
  DETECTION: 'DETECT',
  TIER2: 'TIER2',
  TIER3: 'TIER3',
  AUDIO: 'AUDIO',
  DEEPFAKE: 'DEEPFAKE',
  UI: 'UI',
  NAVIGATION: 'NAV',
  PERMISSION: 'PERM',
} as const;