import React, {useState, useEffect, useCallback} from 'react';
import {Alert, View, Text, ScrollView, TouchableOpacity, Switch, StyleSheet, StatusBar, TextInput, Linking, Platform} from 'react-native';
import {useNavigation, useFocusEffect} from '@react-navigation/native';
import {
  getAllPermissionStatus,
  PermissionStatus,
  promptNotificationListenerSetup,
  openPermissionSettings,
  requestAllRequiredPermissions
} from '../utils/permissionManager';
import {getSettings, saveSettings, clearThreats} from '../utils/storage';
import {getScamAssistBridgeConfig, updateScamAssistBridgeConfig} from '../utils/scamAssistBridge';

const SettingsScreen = () => {
  const navigation = useNavigation();
  const [permissions, setPermissions] = useState<PermissionStatus>({
    notification: false,
    notificationListener: false,
    callScreening: false,
    defaultCallingApp: false,
    audioRecording: false,
    sms: false,
  });

  const [notifications, setNotifications] = useState(true);
  const [autoBlock, setAutoBlock] = useState(false);
  const [vibration, setVibration] = useState(true);
  const [strictMode, setStrictMode] = useState(false);
  const [scamAssistBridgeEnabled, setScamAssistBridgeEnabled] = useState(false);
  const [scamAssistBridgeEndpoint, setScamAssistBridgeEndpoint] = useState('');
  const [isLoading, setIsLoading] = useState(true);

  const loadPermissions = async () => {
    try {
      const status = await getAllPermissionStatus();
      setPermissions(status);
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
        const bridgeConfig = await getScamAssistBridgeConfig();
        if (saved) {
          setAutoBlock(saved.autoBlock ?? false);
          setStrictMode(saved.strictMode ?? false);
          setNotifications(saved.notifications ?? true);
          setVibration(saved.vibration ?? true);
          setScamAssistBridgeEnabled(saved.scamAssistBridgeEnabled ?? bridgeConfig.enabled ?? false);
          setScamAssistBridgeEndpoint(saved.scamAssistBridgeEndpoint ?? bridgeConfig.endpoint ?? '');
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
      result.callScreening &&
      result.notification &&
      result.sms &&
      result.notificationListener
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

  const handleSaveBridgeConfig = async () => {
    const endpoint = scamAssistBridgeEndpoint.trim();
    if (scamAssistBridgeEnabled && endpoint.length === 0) {
      Alert.alert('Bridge endpoint missing', 'Enter a valid Scam Assist endpoint before enabling bridge mode.');
      return;
    }

    const applied = await updateScamAssistBridgeConfig({
      enabled: scamAssistBridgeEnabled,
      endpoint,
    });

    await saveSettings({
      scamAssistBridgeEnabled,
      scamAssistBridgeEndpoint: endpoint,
    });

    if (!applied) {
      Alert.alert('Bridge config saved locally', 'Native bridge config could not be updated right now.');
      return;
    }

    Alert.alert('Bridge configuration saved', 'Scam Assist bridge settings are active for upcoming calls.');
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
      <StatusBar barStyle="light-content" backgroundColor="#0B0C10" />

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
              trackColor={{false: '#333', true: '#FF2A2A'}}
              thumbColor={item.value ? '#fff' : '#888'}
            />
          </View>
        ))}

        <Text style={styles.sectionTitle}>SCAM ASSIST BRIDGE</Text>

        <View style={styles.settingCard}>
          <View style={styles.settingLeft}>
            <Text style={styles.settingTitle}>Enable Bridge Upload</Text>
            <Text style={styles.settingSub}>Send call WAV + transcript metadata to backend for reliable STT and scam scoring.</Text>
          </View>
          <Switch
            value={scamAssistBridgeEnabled}
            onValueChange={setScamAssistBridgeEnabled}
            trackColor={{false: '#333', true: '#FF2A2A'}}
            thumbColor={scamAssistBridgeEnabled ? '#fff' : '#888'}
          />
        </View>

        <View style={styles.inputCard}>
          <Text style={styles.inputLabel}>Bridge Endpoint URL</Text>
          <TextInput
            style={styles.input}
            value={scamAssistBridgeEndpoint}
            onChangeText={setScamAssistBridgeEndpoint}
            placeholder="https://your-server.example.com/scam-assist/upload"
            placeholderTextColor="#8C8C8C"
            autoCapitalize="none"
            autoCorrect={false}
          />
          <Text style={styles.permissionHint}>This endpoint receives WAV + transcript metadata after a call ends.</Text>
        </View>

        <TouchableOpacity style={styles.actionButtonSecondary} onPress={handleSaveBridgeConfig}>
          <Text style={styles.actionTextSecondary}>Save Bridge Configuration</Text>
        </TouchableOpacity>

        <Text style={styles.sectionTitle}>PERMISSIONS</Text>

        <View style={styles.permissionCardBox}>
          <View style={styles.permissionRow}>
            <Text style={styles.permissionLabel}>Microphone</Text>
            {statusChip(permissions.audioRecording)}
          </View>
          <View style={styles.permissionRow}>
            <Text style={styles.permissionLabel}>Call Screening</Text>
            {statusChip(permissions.callScreening)}
          </View>
          <View style={styles.permissionRow}>
            <Text style={styles.permissionLabel}>Default Calling App</Text>
            {statusChip(permissions.defaultCallingApp)}
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
          <Text style={styles.permissionHint}>
            Notification Access is a special Android setting needed for reading OTPs.
          </Text>
        </View>

        <TouchableOpacity style={styles.actionButton} onPress={handleRequestPermissions}>
          <Text style={styles.actionText}>Grant Required Permissions</Text>
        </TouchableOpacity>

        <TouchableOpacity
          style={styles.actionButtonSecondary}
          onPress={async () => {
            const {requestDefaultCallingAppPermission} = await import('../utils/permissionManager');
            await requestDefaultCallingAppPermission();
            await loadPermissions();
          }}>
          <Text style={styles.actionTextSecondary}>Make Default Calling App</Text>
        </TouchableOpacity>

        <TouchableOpacity
          style={styles.actionButtonSecondary}
          onPress={promptNotificationListenerSetup}>
          <Text style={styles.actionTextSecondary}>Open Notification Listener Access</Text>
        </TouchableOpacity>

        <TouchableOpacity
          onPress={() => {
            Linking.openSettings()
          }}
          style={styles.setupButton}>
          <Text style={styles.setupButtonText}>
            Set as Call Screening App
          </Text>
          <Text style={styles.setupButtonSub}>
            Required for pre-ring scam detection
          </Text>
        </TouchableOpacity>

        <TouchableOpacity
          onPress={async () => {
            if (Platform.OS === 'android') {
              const pkg = 'com.intentfirewall'
              Linking.openURL(
                `android.settings.action.MANAGE_OVERLAY_PERMISSION?package=${pkg}`
              )
            }
          }}
          style={styles.setupButton}>
          <Text style={styles.setupButtonText}>
            Grant Overlay Permission
          </Text>
          <Text style={styles.setupButtonSub}>
            Required for call warning screen
          </Text>
        </TouchableOpacity>

        <TouchableOpacity
          style={styles.actionButtonSecondary}
          onPress={openPermissionSettings}>
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
              trackColor={{false: '#333', true: '#FF2A2A'}}
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
        
        <TouchableOpacity style={{marginTop: 20, padding: 15, backgroundColor: 'rgba(255,0,0,0.1)', borderRadius: 10, borderWidth: 1, borderColor: '#FF2A2A'}} onPress={handleClearHistory}>
          <Text style={{color: '#FF2A2A', textAlign: 'center', fontFamily: 'SpaceGrotesk', fontWeight: 'bold'}}>🗑️ Clear All History</Text>
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
  container: {flex: 1, backgroundColor: '#0B0C10'},
  header: {
    paddingTop: 60, paddingHorizontal: 20, paddingBottom: 20,
    backgroundColor: '#0B0C10', borderBottomWidth: 1, borderBottomColor: '#FF2A2A',
  },
  headerTitle: {fontSize: 24, fontFamily: 'SpaceGrotesk', fontWeight: 'bold', color: '#fff'},
  headerSub: {fontSize: 12, color: '#C5C6C7', marginTop: 2},
  scroll: {flex: 1, paddingHorizontal: 20},
  
  sectionTitle: {
    fontSize: 11, fontFamily: 'SpaceGrotesk', fontWeight: 'bold', color: '#C5C6C7',
    marginTop: 24, marginBottom: 12, letterSpacing: 1.5,
  },
  settingCard: {
    backgroundColor: '#1C1D24', borderRadius: 12, padding: 16,
    flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between',
    marginBottom: 10, borderWidth: 1, borderColor: '#C5C6C750',
  },
  settingLeft: {flex: 1},
  settingTitle: {fontSize: 15, fontFamily: 'SpaceGrotesk', fontWeight: '600', color: '#fff'},
  settingSub: {fontSize: 12, color: '#C5C6C7', marginTop: 2},
  permissionCardBox: {
    backgroundColor: '#1C1D24', borderRadius: 12, padding: 16,
    marginBottom: 10, borderWidth: 1, borderColor: '#C5C6C750',
  },
  permissionRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 10,
  },
  inputCard: {
    backgroundColor: '#1C1D24', borderRadius: 12, padding: 16,
    marginBottom: 10, borderWidth: 1, borderColor: '#C5C6C750',
  },
  inputLabel: {
    fontSize: 13, color: '#C5C6C7', marginBottom: 8, fontFamily: 'SpaceGrotesk',
  },
  input: {
    borderWidth: 1,
    borderColor: '#C5C6C750',
    backgroundColor: '#101116',
    color: '#fff',
    borderRadius: 10,
    paddingHorizontal: 12,
    paddingVertical: 10,
    fontSize: 13,
  },
  permissionLabel: {fontSize: 14, color: '#fff', fontFamily: 'SpaceGrotesk', fontWeight: '600'},
  statusChip: {
    borderRadius: 999,
    paddingHorizontal: 10,
    paddingVertical: 4,
    borderWidth: 1,
  },
  grantedChip: {
    backgroundColor: '#0B0C10',
    borderColor: '#45F3FF',
  },
  missingChip: {
    backgroundColor: '#2D0D0D',
    borderColor: '#FF2A2A',
  },
  statusChipText: {
    fontSize: 11,
    color: '#fff',
    fontFamily: 'SpaceGrotesk', fontWeight: '700',
    letterSpacing: 0.3,
  },
  permissionHint: {
    color: '#C5C6C7',
    fontSize: 12,
    lineHeight: 18,
    marginTop: 6,
  },
  actionButton: {
    backgroundColor: '#FF2A2A',
    borderRadius: 12,
    paddingVertical: 14,
    alignItems: 'center',
    marginBottom: 10,
  },
  actionText: {
    color: '#fff',
    fontSize: 15,
    fontFamily: 'SpaceGrotesk', fontWeight: '700',
  },
  actionButtonSecondary: {
    backgroundColor: '#1C1D24',
    borderRadius: 12,
    paddingVertical: 14,
    alignItems: 'center',
    marginBottom: 10,
    borderWidth: 1,
    borderColor: '#C5C6C750',
  },
  actionTextSecondary: {
    color: '#fff',
    fontSize: 15,
    fontFamily: 'SpaceGrotesk', fontWeight: '600',
  },
  setupButton: {
    backgroundColor: '#1C1D24',
    borderRadius: 12,
    paddingVertical: 14,
    paddingHorizontal: 14,
    alignItems: 'flex-start',
    marginBottom: 10,
    borderWidth: 1,
    borderColor: '#45F3FF40',
  },
  setupButtonText: {
    color: '#fff',
    fontSize: 15,
    fontFamily: 'SpaceGrotesk',
    fontWeight: '700',
    marginBottom: 4,
  },
  setupButtonSub: {
    color: '#C5C6C7',
    fontSize: 12,
    lineHeight: 16,
  },
  aboutCard: {
    backgroundColor: '#0B0C10', borderRadius: 12, padding: 16,
    borderWidth: 1, borderColor: '#C5C6C750', alignItems: 'center'
  },
  aboutTitle: { fontSize: 18, fontFamily: 'SpaceGrotesk', fontWeight: 'bold', color: '#fff', marginBottom: 4 },
  aboutSub: { fontSize: 12, color: '#C5C6C7', marginBottom: 12 },
  aboutDesc: { fontSize: 14, color: '#C5C6C7', textAlign: 'center', lineHeight: 20 },
  bottomNav: {
    flexDirection: 'row',
    justifyContent: 'space-around',
    paddingVertical: 16,
    paddingBottom: 30, // iPhone spacing
    backgroundColor: '#1C1D24',
    borderTopWidth: 1,
    borderTopColor: '#C5C6C750',
  },
  navItem: {alignItems: 'center'},
  navIcon: {fontSize: 20, marginBottom: 4},
  navLabel: {fontSize: 10, color: '#888', fontFamily: 'SpaceGrotesk', fontWeight: '600'},
  navLabelActive: {color: '#FF2A2A'},
});

export default SettingsScreen;
