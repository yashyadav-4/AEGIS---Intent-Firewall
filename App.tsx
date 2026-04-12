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

  const normalized = matchedCategory.trim().toUpperCase();
  if (normalized === 'OTHER') {
    return 'SCAM_SUSPECT';
  }

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

    const asSafeString = (value: unknown): string => {
      if (value == null) {
        return '';
      }
      try {
        return String(value);
      } catch {
        return '';
      }
    };

    const processIncomingEvent = async (data: any) => {
      if (!data || typeof data !== 'object') {
        console.warn('[HistoryPipeline] Ignoring malformed event:', data);
        return;
      }

      const appName = asSafeString(data.appName) || 'Unknown';
      const text = asSafeString(data.text);
      const packageName = asSafeString(data.packageName);
      const rawMatchedCategory = asSafeString(data.matchedCategory);
      const isOpenChatCategory = rawMatchedCategory.startsWith('OPEN_CHAT');
      const captureMethod = asSafeString(data.captureMethod).toLowerCase();
      const eventTimestamp = Number(data.timestamp);
      const normalizedEventTimestamp = Number.isFinite(eventTimestamp)
        ? Math.floor(eventTimestamp)
        : Date.now();
      const threatCategory = normalizeThreatCategory(rawMatchedCategory);

      const source =
        captureMethod === 'accessibility' || isOpenChatCategory
          ? 'open_chat'
          : captureMethod === 'sms_fallback'
            ? 'sms_direct'
            : 'notification';

      try {
        await saveMessageEvent({
          app: appName,
          appIcon: APP_ICONS[appName] || '📩',
          title: getMessageTitle({...data, appName, text}, isOpenChatCategory),
          message: text,
          packageName,
          matchedCategory: threatCategory,
          flagged: data.flagged === true,
          source,
          time: 'Just now',
          eventTimestamp: normalizedEventTimestamp,
        });
      } catch (saveErr) {
        console.warn('[HistoryPipeline] saveMessageEvent failed, using fallback:', saveErr);
        await saveMessageEvent({
          app: appName,
          appIcon: APP_ICONS[appName] || '📩',
          title: appName,
          message: text || '[Empty message]',
          packageName,
          matchedCategory: rawMatchedCategory || 'UNKNOWN',
          flagged: data.flagged === true,
          source,
          time: 'Just now',
          eventTimestamp: normalizedEventTimestamp,
        });
      }

      console.log(
        '[HistoryPipeline] Saved message event:',
        appName,
        source,
        normalizedEventTimestamp,
      );

      console.log(
        '[TierTrace] tierUsed=',
        data.tierUsed || 'tier1',
        'tier1Decision=',
        data.tier1Decision || 'ALLOW',
        'tier1Score=',
        Number(data.tier1Score || 0).toFixed(3),
        'tier1Category=',
        data.tier1Category || 'NONE',
        'tier3Model=',
        data.tier3Model || data.geminiModel || 'n/a',
        'tier3KeyIndex=',
        Number(data.tier3KeyIndex || data.geminiKeyIndex || 0),
        'tier3Reason=',
        data.tier3Reason || data.geminiReason || '',
        'app=',
        appName,
        'capture=',
        captureMethod || 'notification',
        'flagged=',
        data.flagged === true,
      );

      if (data.flagged === true) {
        const settings = await getSettings();
        const contextText = String(data.context || '').trim();
        await saveThreat({
          app: appName,
          appIcon: APP_ICONS[appName] || '📩',
          message: text,
          context: contextText,
          category: threatCategory,
          confidence: toPercent(data.confidence),
          blocked: settings.autoBlock,
          time: 'Just now',
          timestamp: normalizedEventTimestamp,
        });

        console.log('[HistoryPipeline] Saved threat event:', appName, threatCategory);
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
          try {
            await processIncomingEvent(item);
          } catch (itemErr) {
            console.warn('[HistoryPipeline] Failed to process drained event:', itemErr, item);
          }
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