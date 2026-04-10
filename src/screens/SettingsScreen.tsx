import React, {useState, useEffect, useCallback} from 'react';
import {Alert, View, Text, ScrollView, TouchableOpacity, Switch, StyleSheet, StatusBar} from 'react-native';
import {useNavigation, useFocusEffect} from '@react-navigation/native';
import {
  getAllPermissionStatus,
  PermissionStatus,
  promptNotificationListenerSetup,
  promptAccessibilityServiceSetup,
  openPermissionSettings,
  requestAllRequiredPermissions,
  startCallProtection,
  stopCallProtection,
  isCallProtectionRunning,
} from '../utils/permissionManager';
import {getSettings, saveSettings, clearThreats} from '../utils/storage';

const SettingsScreen = () => {
  const navigation = useNavigation();
  const [permissions, setPermissions] = useState<PermissionStatus>({
    notification: false,
    notificationListener: false,
    accessibilityService: false,
    callScreening: false,
    audioRecording: false,
    phoneState: false,
    sms: false,
  });

  const [notifications, setNotifications] = useState(true);
  const [autoBlock, setAutoBlock] = useState(false);
  const [vibration, setVibration] = useState(true);
  const [strictMode, setStrictMode] = useState(false);
  const [callProtectionRunning, setCallProtectionRunning] = useState(false);
  const [isLoading, setIsLoading] = useState(true);

  const loadPermissions = async () => {
    try {
      const status = await getAllPermissionStatus();
      setPermissions(status);
      const running = await isCallProtectionRunning();
      setCallProtectionRunning(running);
    } catch (error) {
      console.error('Failed to load permissions:', error);
    }
  };

  useFocusEffect(
    useCallback(() => {
      loadPermissions();
    }, [])
  );

  useEffect(() => {
    const loadSavedSettings = async () => {
      try {
        const saved = await getSettings();
        if (saved) {
          setAutoBlock(saved.autoBlock ?? false);
          setStrictMode(saved.strictMode ?? false);
          setNotifications(saved.notifications ?? true);
          setVibration(saved.vibration ?? true);
        }
        await loadPermissions();
      } catch (err) {
        console.error(err);
      } finally {
        setIsLoading(false);
      }
    };
    loadSavedSettings();
  }, []);

  const handleToggle = async (
    key: 'autoBlock' | 'strictMode' | 'notifications' | 'vibration',
    value: boolean,
    setter: (v: boolean) => void,
  ) => {
    setter(value);
    await saveSettings({[key]: value});
  };

  const handleRequestPermissions = async () => {
    const result = await requestAllRequiredPermissions();
    setPermissions(result);

    if (
      result.audioRecording &&
      result.phoneState &&
      result.callScreening &&
      result.notification &&
      result.sms &&
      result.notificationListener &&
      result.accessibilityService
    ) {
      Alert.alert('Permissions ready', 'All required runtime permissions are granted.');
      return;
    }

    Alert.alert(
      'Permissions still needed',
      'Some permissions are still denied. Tap "Open App Permission Settings" to allow them manually.',
    );
  };

  const handleClearHistory = async () => {
    try {
      if (clearThreats) await clearThreats();
      Alert.alert('Cleared', 'History has been cleared.');
    } catch (err) {
      console.error(err);
    }
  }

  const handleStartCallProtection = async () => {
    if (!permissions.audioRecording || !permissions.phoneState) {
      Alert.alert('Required permissions missing', 'Grant Microphone and Phone State permissions first.');
      return;
    }

    const ok = await startCallProtection();

    let running = false;
    for (let i = 0; i < 8; i += 1) {
      // Service startup on some OEM builds is asynchronous; poll briefly before declaring failure.
      // eslint-disable-next-line no-await-in-loop
      running = await isCallProtectionRunning();
      if (running) break;
      // eslint-disable-next-line no-await-in-loop
      await new Promise(resolve => setTimeout(resolve, 250));
    }

    setCallProtectionRunning(running);

    if (ok && running) {
      Alert.alert('Call protection active', 'Live call monitoring is ON. AI voice-risk analysis is active during calls.');
    } else {
      Alert.alert('Could not start', 'Start failed. Keep app in foreground and try again after granting permissions.');
    }
  };

  const handleStopCallProtection = async () => {
    await stopCallProtection();
    const running = await isCallProtectionRunning();
    setCallProtectionRunning(running);
    Alert.alert('Call protection stopped', 'Live call monitoring is OFF.');
  };

  const statusChip = (granted: boolean) => (
    <View style={[styles.statusChip, granted ? styles.grantedChip : styles.missingChip]}>
      <Text style={styles.statusChipText}>{granted ? 'Granted' : 'Missing'}</Text>
    </View>
  );

  if (isLoading) {
    return (
      <View style={[styles.container, {justifyContent: 'center', alignItems: 'center'}]}>
        <Text style={{color: '#fff'}}>Loading...</Text>
      </View>
    );
  }

  return (
    <View style={styles.container}>
      <StatusBar barStyle="light-content" backgroundColor="#0D0D0D" />

      <View style={styles.header}>
        <Text style={styles.headerTitle}>⚙️ Settings</Text>
        <Text style={styles.headerSub}>Configure your protection</Text>
      </View>

      <ScrollView style={styles.scroll} showsVerticalScrollIndicator={false}>
        <Text style={styles.sectionTitle}>PROTECTION</Text>
        {[
          {
            label: 'Auto Block Threats',
            sub: 'Block without asking',
            value: autoBlock,
            setter: setAutoBlock,
            key: 'autoBlock' as const,
          },
          {
            label: 'Strict Mode',
            sub: 'Flag low confidence threats too',
            value: strictMode,
            setter: setStrictMode,
            key: 'strictMode' as const,
          },
        ].map((item, i) => (
          <View key={i} style={styles.settingCard}>
            <View style={styles.settingLeft}>
              <Text style={styles.settingTitle}>{item.label}</Text>
              <Text style={styles.settingSub}>{item.sub}</Text>
            </View>
            <Switch
              value={item.value}
              onValueChange={v => handleToggle(item.key, v, item.setter)}
              trackColor={{false: '#333', true: '#E63946'}}
              thumbColor={item.value ? '#fff' : '#888'}
            />
          </View>
        ))}

        <Text style={styles.sectionTitle}>PERMISSIONS</Text>

        <View style={styles.permissionCardBox}>
          <View style={styles.permissionRow}>
            <Text style={styles.permissionLabel}>Microphone</Text>
            {statusChip(permissions.audioRecording)}
          </View>
          <View style={styles.permissionRow}>
            <Text style={styles.permissionLabel}>Phone State</Text>
            {statusChip(permissions.phoneState)}
          </View>
          <View style={styles.permissionRow}>
            <Text style={styles.permissionLabel}>Call Screening</Text>
            {statusChip(permissions.callScreening)}
          </View>
          <View style={styles.permissionRow}>
            <Text style={styles.permissionLabel}>Notifications</Text>
            {statusChip(permissions.notification)}
          </View>
          <View style={styles.permissionRow}>
            <Text style={styles.permissionLabel}>Notification Listener</Text>
            {statusChip(permissions.notificationListener)}
          </View>
          <View style={styles.permissionRow}>
            <Text style={styles.permissionLabel}>SMS</Text>
            {statusChip(permissions.sms)}
          </View>
          <View style={styles.permissionRow}>
            <Text style={styles.permissionLabel}>Open Chat Access</Text>
            {statusChip(permissions.accessibilityService)}
          </View>
          <Text style={styles.permissionHint}>
            Notification Access is a special Android setting needed for reading OTPs.
          </Text>
        </View>

        <TouchableOpacity style={styles.actionButton} onPress={handleRequestPermissions}>
          <Text style={styles.actionText}>Grant Required Permissions</Text>
        </TouchableOpacity>

        <TouchableOpacity
          style={styles.actionButtonSecondary}
          onPress={promptNotificationListenerSetup}>
          <Text style={styles.actionTextSecondary}>Open Notification Listener Access</Text>
        </TouchableOpacity>

        <TouchableOpacity
          style={styles.actionButtonSecondary}
          onPress={promptAccessibilityServiceSetup}>
          <Text style={styles.actionTextSecondary}>Open Accessibility Access</Text>
        </TouchableOpacity>

        <TouchableOpacity
          style={styles.actionButtonSecondary}
          onPress={openPermissionSettings}>
          <Text style={styles.actionTextSecondary}>Open App Permission Settings</Text>
        </TouchableOpacity>

        <Text style={styles.sectionTitle}>CALL PROTECTION</Text>
        <View style={styles.permissionCardBox}>
          <View style={styles.permissionRow}>
            <Text style={styles.permissionLabel}>Live Call Monitor</Text>
            {statusChip(callProtectionRunning)}
          </View>
          <Text style={styles.permissionHint}>
            Turn this on while app is open. Then incoming/ongoing calls can be analyzed with live AI voice-risk checks.
          </Text>
        </View>

        <TouchableOpacity style={styles.actionButton} onPress={handleStartCallProtection}>
          <Text style={styles.actionText}>Start Call Protection</Text>
        </TouchableOpacity>

        <TouchableOpacity style={styles.actionButtonSecondary} onPress={handleStopCallProtection}>
          <Text style={styles.actionTextSecondary}>Stop Call Protection</Text>
        </TouchableOpacity>

        <Text style={styles.sectionTitle}>NOTIFICATIONS</Text>

        {[
          {
            label: 'Push Notifications',
            sub: 'Alert when threat detected',
            value: notifications,
            setter: setNotifications,
            key: 'notifications' as const,
          },
          {
            label: 'Vibration',
            sub: 'Vibrate on threat detection',
            value: vibration,
            setter: setVibration,
            key: 'vibration' as const,
          },
        ].map((item, i) => (
          <View key={i} style={styles.settingCard}>
            <View style={styles.settingLeft}>
              <Text style={styles.settingTitle}>{item.label}</Text>
              <Text style={styles.settingSub}>{item.sub}</Text>
            </View>
            <Switch
              value={item.value}
              onValueChange={v => handleToggle(item.key, v, item.setter)}
              trackColor={{false: '#333', true: '#E63946'}}
              thumbColor={item.value ? '#fff' : '#888'}
            />
          </View>
        ))}

        <Text style={styles.sectionTitle}>ABOUT</Text>

        <View style={styles.aboutCard}>
          <Text style={styles.aboutTitle}>🛡️ Intent Firewall</Text>
          <Text style={styles.aboutSub}>Version 1.0.0</Text>
          <Text style={styles.aboutDesc}>
            Powered by on-device AI. Protects you from scams, phishing, and
            social engineering attacks in real-time.
          </Text>
        </View>
        
        <TouchableOpacity style={{marginTop: 20, padding: 15, backgroundColor: 'rgba(255,0,0,0.1)', borderRadius: 10, borderWidth: 1, borderColor: '#E63946'}} onPress={handleClearHistory}>
          <Text style={{color: '#E63946', textAlign: 'center', fontWeight: 'bold'}}>🗑️ Clear All History</Text>
        </TouchableOpacity>

        <View style={{height: 30}} />
      </ScrollView>

      {/* Bottom Nav */}
      <View style={styles.bottomNav}>
        {[
          {icon: '🏠', label: 'Home', screen: 'Home'},
          {icon: '📋', label: 'History', screen: 'History'},
          {icon: '⚙️', label: 'Settings', screen: 'Settings'},{icon: '🐞', label: 'Debug', screen: 'Debug'},
        ].map(item => (
          <TouchableOpacity
            key={item.label}
            style={styles.navItem}
            onPress={() => navigation.navigate(item.screen as never)}>
            <Text style={styles.navIcon}>{item.icon}</Text>
            <Text
              style={[
                styles.navLabel,
                item.screen === 'Settings' && styles.navLabelActive,
              ]}>
              {item.label}
            </Text>
          </TouchableOpacity>
        ))}
      </View>
    </View>
  );
};

const styles = StyleSheet.create({
  container: {flex: 1, backgroundColor: '#0D0D0D'},
  header: {
    paddingTop: 60, paddingHorizontal: 20, paddingBottom: 20,
    backgroundColor: '#1A1A2E', borderBottomWidth: 1, borderBottomColor: '#E63946',
  },
  headerTitle: {fontSize: 24, fontWeight: 'bold', color: '#fff'},
  headerSub: {fontSize: 12, color: '#A0AEC0', marginTop: 2},
  scroll: {flex: 1, paddingHorizontal: 20},
  
  sectionTitle: {
    fontSize: 11, fontWeight: 'bold', color: '#A0AEC0',
    marginTop: 24, marginBottom: 12, letterSpacing: 1.5,
  },
  settingCard: {
    backgroundColor: '#16213E', borderRadius: 12, padding: 16,
    flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between',
    marginBottom: 10, borderWidth: 1, borderColor: '#2D3748',
  },
  settingLeft: {flex: 1},
  settingTitle: {fontSize: 15, fontWeight: '600', color: '#fff'},
  settingSub: {fontSize: 12, color: '#A0AEC0', marginTop: 2},
  permissionCardBox: {
    backgroundColor: '#16213E', borderRadius: 12, padding: 16,
    marginBottom: 10, borderWidth: 1, borderColor: '#2D3748',
  },
  permissionRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 10,
  },
  permissionLabel: {fontSize: 14, color: '#fff', fontWeight: '600'},
  statusChip: {
    borderRadius: 999,
    paddingHorizontal: 10,
    paddingVertical: 4,
    borderWidth: 1,
  },
  grantedChip: {
    backgroundColor: '#0D2818',
    borderColor: '#2D6A4F',
  },
  missingChip: {
    backgroundColor: '#2D0D0D',
    borderColor: '#E63946',
  },
  statusChipText: {
    fontSize: 11,
    color: '#fff',
    fontWeight: '700',
    letterSpacing: 0.3,
  },
  permissionHint: {
    color: '#A0AEC0',
    fontSize: 12,
    lineHeight: 18,
    marginTop: 6,
  },
  actionButton: {
    backgroundColor: '#E63946',
    borderRadius: 12,
    paddingVertical: 14,
    alignItems: 'center',
    marginBottom: 10,
  },
  actionText: {
    color: '#fff',
    fontSize: 15,
    fontWeight: '700',
  },
  actionButtonSecondary: {
    backgroundColor: '#16213E',
    borderRadius: 12,
    paddingVertical: 14,
    alignItems: 'center',
    marginBottom: 10,
    borderWidth: 1,
    borderColor: '#2D3748',
  },
  actionTextSecondary: {
    color: '#fff',
    fontSize: 15,
    fontWeight: '600',
  },
  aboutCard: {
    backgroundColor: '#1A1A2E', borderRadius: 12, padding: 16,
    borderWidth: 1, borderColor: '#2D3748', alignItems: 'center'
  },
  aboutTitle: { fontSize: 18, fontWeight: 'bold', color: '#fff', marginBottom: 4 },
  aboutSub: { fontSize: 12, color: '#A0AEC0', marginBottom: 12 },
  aboutDesc: { fontSize: 14, color: '#A0AEC0', textAlign: 'center', lineHeight: 20 },
  bottomNav: {
    flexDirection: 'row',
    justifyContent: 'space-around',
    paddingVertical: 16,
    paddingBottom: 30, // iPhone spacing
    backgroundColor: '#16213E',
    borderTopWidth: 1,
    borderTopColor: '#2D3748',
  },
  navItem: {alignItems: 'center'},
  navIcon: {fontSize: 20, marginBottom: 4},
  navLabel: {fontSize: 10, color: '#888', fontWeight: '600'},
  navLabelActive: {color: '#E63946'},
});

export default SettingsScreen;
