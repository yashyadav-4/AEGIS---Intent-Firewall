import React, {useState, useEffect} from 'react';
import {
  View, Text, StyleSheet, StatusBar,
  ScrollView, Switch, TouchableOpacity,
} from 'react-native';
import {useNavigation} from '@react-navigation/native';
import {clearThreats, getSettings, saveSettings} from '../utils/storage';

const SettingsScreen = () => {
  const navigation = useNavigation();
  const [notifications, setNotifications] = useState(true);
  const [autoBlock, setAutoBlock] = useState(false);
  const [vibration, setVibration] = useState(true);
  const [strictMode, setStrictMode] = useState(false);

  // ─── Load saved settings on mount ──────────────────────
  useEffect(() => {
    const loadSettings = async () => {
      const saved = await getSettings();
      setAutoBlock(saved.autoBlock);
      setStrictMode(saved.strictMode);
      setNotifications(saved.notifications);
      setVibration(saved.vibration);
    };
    loadSettings();
  }, []);

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
