import { NativeModules, NativeEventEmitter, Platform } from 'react-native';

const { NotificationService } = NativeModules;

export interface CallRiskEvent {
  source: 'call';
  type: 'call_blocked' | 'call_suspicious' | 'call_scam_alert' | 'call_speakerphone_prompt';
  number: string;
  riskScore: number;
  reason: string;
  captureMethod: string;
  tierUsed: string;
  message?: string;
  timestamp: number;
}

export const CallProtectionManager = {
  async requestCallScreeningRole(): Promise<'already_held' | 'requested' | 'unavailable'> {
    if (Platform.OS !== 'android') return 'unavailable';
    try {
      const result = await NotificationService.requestCallScreeningRole();
      return result;
    } catch (e: any) {
      console.warn('Call screening role request failed:', e?.message ?? e);
      return 'unavailable';
    }
  },

  async isCallScreeningRoleHeld(): Promise<boolean> {
    if (Platform.OS !== 'android') return false;
    try {
      return await NotificationService.isCallScreeningRoleHeld();
    } catch {
      return false;
    }
  },

  async startCallAudioMonitor(callerNumber: string = 'unknown'): Promise<boolean> {
    if (Platform.OS !== 'android') return false;
    try {
      return await NotificationService.startCallAudioMonitor(callerNumber);
    } catch (e: any) {
      console.warn('Failed to start call audio monitor:', e?.message ?? e);
      return false;
    }
  },

  async stopCallAudioMonitor(): Promise<void> {
    if (Platform.OS !== 'android') return;
    try {
      await NotificationService.stopCallAudioMonitor();
    } catch (e: any) {
      console.warn('Failed to stop call audio monitor:', e?.message ?? e);
    }
  },

  onCallRiskEvent(
    emitter: NativeEventEmitter,
    callback: (event: CallRiskEvent) => void
  ) {
    return emitter.addListener('onNotification', (event: any) => {
      if (event?.source === 'call') {
        callback(event as CallRiskEvent);
      }
    });
  }
};
