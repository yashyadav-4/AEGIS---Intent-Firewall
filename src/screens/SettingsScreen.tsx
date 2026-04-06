import React, {useState, useEffect} from 'react';
import {
  View, Text, StyleSheet, StatusBar,
  ScrollView, Switch, TouchableOpacity, Alert,
} from 'react-native';
import {useNavigation, useFocusEffect} from '@react-navigation/native';
import {clearThreats, getSettings, saveSettings} from '../utils/storage';
import {
  getPermissionSnapshot,
  requestRequiredPermissions,
  openNotificationAccessSettings,
  openAppPermissionSettings,
  PermissionSnapshot,
} from '../utils/permissionManager';

const SettingsScreen = () => {
  const navigation = useNavigation();
  const [notifications, setNotifications] = useState(true);
  const [autoBlock, setAutoBlock] = useState(false);
  const [vibration, setVibration] = useState(true);
  const [strictMode, setStrictMode] = useState(false);
  const [permissions, setPermissions] = useState<PermissionSnapshot>({
    recordAudio: false,
    readPhoneState: false,
    postNotifications: true,
  });

  const refreshPermissions = async () => {
    const snapshot = await getPermissionSnapshot();
    setPermissions(snapshot);
  };

  // ─── Load saved settings on mount ──────────────────────
  useEffect(() => {
    const loadSettings = async () => {
      const saved = await getSettings();
      setAutoBlock(saved.autoBlock);
      setStrictMode(saved.strictMode);
      setNotifications(saved.notifications);
      setVibration(saved.vibration);
      await refreshPermissions();
    };
    loadSettings();
  }, []);

  useFocusEffect(
    React.useCallback(() => {
      refreshPermissions();
    }, []),
  );

  // ─── Save a single setting when toggled ────────────────
  const handleToggle = async (
    key: 'autoBlock' | 'strictMode' | 'notifications' | 'vibration',
    value: boolean,
    setter: (v: boolean) => void,
  ) => {
    setter(value);
    await saveSettings({[key]: value});
  };

  const handleClearHistory = async () => {
    await clearThreats();
  };

  const handleRequestPermissions = async () => {
    const result = await requestRequiredPermissions();
    setPermissions(result);

    if (result.recordAudio && result.readPhoneState && result.postNotifications) {
      Alert.alert('Permissions ready', 'All required runtime permissions are granted.');
      return;
    }

    Alert.alert(
      'Permissions still needed',
      'Some permissions are still denied. Tap "Open App Permission Settings" to allow them manually.',
    );
  };

  const statusChip = (granted: boolean) => (
    <View style={[styles.statusChip, granted ? styles.grantedChip : styles.missingChip]}>
      <Text style={styles.statusChipText}>{granted ? 'Granted' : 'Missing'}</Text>
    </View>
  );

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

        <View style={styles.permissionCard}>
          <View style={styles.permissionRow}>
            <Text style={styles.permissionLabel}>Microphone</Text>
            {statusChip(permissions.recordAudio)}
          </View>
          <View style={styles.permissionRow}>
            <Text style={styles.permissionLabel}>Phone State</Text>
            {statusChip(permissions.readPhoneState)}
          </View>
          <View style={styles.permissionRow}>
            <Text style={styles.permissionLabel}>Notifications</Text>
            {statusChip(permissions.postNotifications)}
          </View>
          <Text style={styles.permissionHint}>
            Notification Access is a special Android setting. Use the button below and enable Intent Firewall.
          </Text>
        </View>

        <TouchableOpacity style={styles.actionButton} onPress={handleRequestPermissions}>
          <Text style={styles.actionText}>Grant Required Permissions</Text>
        </TouchableOpacity>

        <TouchableOpacity
          style={styles.actionButtonSecondary}
          onPress={openNotificationAccessSettings}>
          <Text style={styles.actionTextSecondary}>Open Notification Access</Text>
        </TouchableOpacity>

        <TouchableOpacity
          style={styles.actionButtonSecondary}
          onPress={openAppPermissionSettings}>
          <Text style={styles.actionTextSecondary}>Open App Permission Settings</Text>
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

        <TouchableOpacity style={styles.dangerButton} onPress={handleClearHistory}>
          <Text style={styles.dangerText}>🗑️ Clear All History</Text>
        </TouchableOpacity>

        <View style={{height: 30}} />
      </ScrollView>

      {/* Bottom Nav */}
      <View style={styles.bottomNav}>
        {[
          {icon: '🏠', label: 'Home', screen: 'Home'},
          {icon: '📋', label: 'History', screen: 'History'},
          {icon: '⚙️', label: 'Settings', screen: 'Settings'},
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
  permissionCard: {
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
    padding: 14,
    alignItems: 'center',
    marginBottom: 10,
  },
  actionText: {color: '#fff', fontWeight: '700', fontSize: 14},
  actionButtonSecondary: {
    backgroundColor: '#1A1A2E',
    borderRadius: 12,
    padding: 14,
    alignItems: 'center',
    marginBottom: 10,
    borderWidth: 1,
    borderColor: '#2D3748',
  },
  actionTextSecondary: {color: '#A0AEC0', fontWeight: '700', fontSize: 13},
  aboutCard: {
    backgroundColor: '#16213E', borderRadius: 12, padding: 20,
    borderWidth: 1, borderColor: '#2D3748', alignItems: 'center',
  },
  aboutTitle: {fontSize: 20, fontWeight: 'bold', color: '#fff'},
  aboutSub: {fontSize: 12, color: '#A0AEC0', marginTop: 4},
  aboutDesc: {
    fontSize: 12, color: '#A0AEC0', textAlign: 'center',
    marginTop: 12, lineHeight: 18,
  },
  dangerButton: {
    marginTop: 16, backgroundColor: '#2D0D0D', borderRadius: 12,
    padding: 16, alignItems: 'center', borderWidth: 1, borderColor: '#E63946',
  },
  dangerText: {fontSize: 14, color: '#E63946', fontWeight: 'bold'},
  bottomNav: {
    flexDirection: 'row', backgroundColor: '#1A1A2E',
    borderTopWidth: 1, borderTopColor: '#2D3748',
    paddingBottom: 20, paddingTop: 10,
  },
  navItem: {flex: 1, alignItems: 'center'},
  navIcon: {fontSize: 22},
  navLabel: {fontSize: 11, color: '#A0AEC0', marginTop: 4},
  navLabelActive: {color: '#E63946', fontWeight: 'bold'},
});

export default SettingsScreen;
