import React, {useEffect} from 'react';
import {AppState, DeviceEventEmitter, NativeModules} from 'react-native';
import AppNavigator from './src/navigation/AppNavigator';
import ErrorBoundary from './src/utils/errorBoundary';
import {getSettings, saveMessageEvent, saveThreat} from './src/utils/storage';

const {NotificationService} = NativeModules;

const APP_ICONS: Record<string, string> = {
  WhatsApp: '💬',
  Telegram: '✈️',
  SMS: '📱',
  System: '⚙️',
  'Phone Call': '📞',
};

const toPercent = (value: unknown): number => {
  const n = Number(value);
  if (!Number.isFinite(n)) return 85;
  if (n <= 1) return Math.round(n * 100);
  return Math.round(n);
};

const normalizeThreatCategory = (matchedCategory: string): string => {
  if (!matchedCategory) return 'SCAM_SUSPECT';

  if (matchedCategory.startsWith('OPEN_CHAT_')) {
    return matchedCategory
      .replace('OPEN_CHAT_', '')
      .replace(/_/g, ' ')
      .trim() || 'SCAM_SUSPECT';
  }

  if (matchedCategory === 'OPEN_CHAT') {
    return 'SCAM_SUSPECT';
  }

  return matchedCategory;
};

const getMessageTitle = (data: any, isOpenChatCategory: boolean): string => {
  if (!isOpenChatCategory) {
    return data.title || data.appName || 'Message';
  }

  const text = String(data.text || '').trim();
  if (!text) {
    return data.appName ? `${data.appName} message` : 'Chat message';
  }

  const compact = text.replace(/\s+/g, ' ');
  const short = compact.slice(0, 48).trim();
  return short.length < compact.length ? `${short}...` : short;
};

const App = () => {
  useEffect(() => {
    let isDraining = false;

    const processIncomingEvent = async (data: any) => {
      if (!data || typeof data !== 'object') {
        console.warn('[HistoryPipeline] Ignoring malformed event:', data);
        return;
      }

      const isOpenChatCategory = String(data.matchedCategory || '').startsWith('OPEN_CHAT');
      const captureMethod = String(data.captureMethod || '').toLowerCase();
      const eventTimestamp = Number(data.timestamp);
      const normalizedEventTimestamp = Number.isFinite(eventTimestamp)
        ? Math.floor(eventTimestamp)
        : Date.now();
      const threatCategory = normalizeThreatCategory(String(data.matchedCategory || ''));

      const source =
        captureMethod === 'accessibility' || isOpenChatCategory
          ? 'open_chat'
          : captureMethod === 'sms_fallback'
            ? 'sms_direct'
            : 'notification';

      await saveMessageEvent({
        app: data.appName,
        appIcon: APP_ICONS[data.appName] || '📩',
        title: getMessageTitle(data, isOpenChatCategory),
        message: data.text || '',
        packageName: data.packageName || '',
        matchedCategory: threatCategory,
        flagged: data.flagged === true,
        source,
        time: 'Just now',
        eventTimestamp: normalizedEventTimestamp,
      });

      console.log(
        '[HistoryPipeline] Saved message event:',
        data.appName,
        source,
        normalizedEventTimestamp,
      );

      if (data.flagged === true) {
        const settings = await getSettings();
        await saveThreat({
          app: data.appName,
          appIcon: APP_ICONS[data.appName] || '📩',
          message: data.text || '',
          category: threatCategory,
          confidence: toPercent(data.confidence),
          blocked: settings.autoBlock,
          time: 'Just now',
          timestamp: normalizedEventTimestamp,
        });

        console.log('[HistoryPipeline] Saved threat event:', data.appName, threatCategory);
      }
    };

    const subscription = DeviceEventEmitter.addListener('onNotification', async data => {
      await processIncomingEvent(data);
    });

    const drainBuffered = async () => {
      if (isDraining) {
        return;
      }

      isDraining = true;
      try {
        let pendingList: any[] = [];

        const pendingJson = await NotificationService?.drainBufferedNotificationsJson?.();
        if (typeof pendingJson === 'string' && pendingJson.trim().length > 0) {
          try {
            const parsed = JSON.parse(pendingJson);
            if (Array.isArray(parsed)) {
              pendingList = parsed;
            }
          } catch (parseErr) {
            console.warn('[HistoryPipeline] Failed to parse buffered JSON:', parseErr);
          }
        }

        if (pendingList.length === 0) {
          const pending = await NotificationService?.drainBufferedNotifications?.();
          if (Array.isArray(pending)) {
            pendingList = pending;
          } else if (pending && typeof pending === 'object') {
            pendingList = Object.values(pending);
          }
        }

        if (pendingList.length === 0) {
          isDraining = false;
          return;
        }

        console.log('[HistoryPipeline] Draining buffered events count:', pendingList.length);

        for (const item of pendingList) {
          await processIncomingEvent(item);
        }
      } catch (error) {
        console.warn('Failed to drain buffered notifications:', error);
      } finally {
        isDraining = false;
      }
    };

    drainBuffered();

    const intervalId = setInterval(() => {
      drainBuffered();
    }, 1200);

    const appStateSub = AppState.addEventListener('change', state => {
      if (state === 'active') {
        drainBuffered();
      }
    });

    return () => {
      subscription.remove();
      clearInterval(intervalId);
      appStateSub.remove();
    };
  }, []);

  return (
    <ErrorBoundary>
      <AppNavigator />
    </ErrorBoundary>
  );
};

export default App; 