import React, { useEffect } from 'react';
import { DeviceEventEmitter } from 'react-native';
import AppNavigator from './src/navigation/AppNavigator';
import ErrorBoundary from './src/utils/errorBoundary';
import { saveMessageEvent } from './src/utils/storage';

const APP_ICONS: Record<string, string> = {
  WhatsApp: '💬',
  Telegram: '✈️',
  SMS: '📱',
  System: '⚙️',
  'Phone Call': '📞',
};

const App = () => {
  useEffect(() => {
    const subscription = DeviceEventEmitter.addListener('onNotification', async data => {
      await saveMessageEvent({
        app: data.appName,
        appIcon: APP_ICONS[data.appName] || '📩',
        title: data.title || data.appName || 'Message',
        message: data.text || '',
        packageName: data.packageName || '',
        matchedCategory: data.matchedCategory || '',
        flagged: data.flagged === true,
        time: 'Just now',
      });
    });

    return () => subscription.remove();
  }, []);

  return (
    <ErrorBoundary>
      <AppNavigator />
    </ErrorBoundary>
  );
};

export default App;
