import React, {useState, useEffect} from 'react';
import {
  View, Text, StyleSheet, StatusBar,
  ScrollView, Switch, TouchableOpacity,
  DeviceEventEmitter,
} from 'react-native';
import {useNavigation} from '@react-navigation/native';
import type {StackNavigationProp} from '@react-navigation/stack';
import {getSettings, saveSettings, saveThreat, getThreats, Threat} from '../utils/storage';
import { recordDetection } from '../utils/analyticsStore';
import {detectScam} from '../utils/scamDetector';
import type {RootStackParamList} from '../navigation/types';

type BottomNavScreen = Exclude<keyof RootStackParamList, 'Warning'>;

const APP_ICONS: Record<string, string> = {
  WhatsApp: '💬',
  Telegram: '✈️',
  SMS: '📱',
  'Phone Call': '📞',
};

const NAV_ITEMS: Array<{icon: string; label: string; screen: BottomNavScreen}> = [
  {icon: '🏠', label: 'Home', screen: 'Home'},
  {icon: '📋', label: 'History', screen: 'History'},
  {icon: '📊', label: 'Analytics', screen: 'Analytics'},
  {icon: '⚙️', label: 'Settings', screen: 'Settings'},{icon: '🐞', label: 'Debug', screen: 'Debug'},
];

const HomeScreen = () => {
  const navigation = useNavigation<StackNavigationProp<RootStackParamList>>();
  const [messageProtection, setMessageProtection] = useState(false);
  const [callProtection, setCallProtection] = useState(false);
  const [recentThreats, setRecentThreats] = useState<Threat[]>([]);
  const [isProtected, setIsProtected] = useState(false);
  const [isLoading, setIsLoading] = useState(true);

  useEffect(() => {
    const load = async () => {
      try {
        const s = await getSettings();
        setMessageProtection(s.messageProtection);
        setCallProtection(s.callProtection);
        setIsProtected(s.messageProtection || s.callProtection);
        await loadRecentThreats();
      } catch (err) {
        console.error('Error loading settings', err);
      } finally {
        setIsLoading(false);
      }
    };
    load();
  }, []);

  useEffect(() => {
    const subscription = DeviceEventEmitter.addListener('onNotification', async data => {
      const settings = await getSettings();
      if (!settings.messageProtection) return;

      // Use native Tier 1 signal if available, fall back to JS detector
      const nativeFlagged: boolean = data.flagged === true;
      const nativeCategory: string = data.matchedCategory || '';

      const jsResult = detectScam(data.text);
      const isScam = nativeFlagged || jsResult.isScam;
      const category = nativeFlagged && nativeCategory
        ? nativeCategory
        : jsResult.category;
      const confidence = nativeFlagged
        ? Math.max(jsResult.confidence, 85)
        : jsResult.confidence;

      const categories = [nativeCategory, jsResult.category].filter(Boolean);

      recordDetection(isScam, categories);

      console.log('[Aegis] native:', nativeFlagged, nativeCategory,
                  '| js:', jsResult.isScam, jsResult.category);

      if (isScam) {
        const savedThreat = await saveThreat({
          app: data.appName,
          appIcon: APP_ICONS[data.appName] || '📩',
          message: data.text,
          category,
          confidence,
          blocked: settings.autoBlock,
          time: 'Just now',
        });

        loadRecentThreats();

        if (!settings.autoBlock) {
          navigation.navigate('Warning', {
            category,
            confidence,
            message: data.text,
            app: data.appName,
            threatId: savedThreat?.id,
          });
        }
      }
    });

    return () => subscription.remove();
  }, [navigation]);

  const loadRecentThreats = async () => {
    try {
      const all = await getThreats();
      setRecentThreats(all.slice(0, 3));
    } catch (err) {
      console.error('Error loading threats', err);
    }
  };

  const simulateAttack = async () => {
    const mockNotification = {
      appName: 'WhatsApp',
      text: 'URGENT: Your bank account has been blocked. Verify now at http://bit.ly/fake-bank',
    };

    const jsResult = detectScam(mockNotification.text);

    if (!jsResult.isScam) {
      return;
    }

    const savedThreat = await saveThreat({
      app: mockNotification.appName,
      appIcon: APP_ICONS[mockNotification.appName] || '📩',
      message: mockNotification.text,
      category: jsResult.category,
      confidence: jsResult.confidence,
      blocked: false,
      time: 'Just now',
    });

    await loadRecentThreats();

    navigation.navigate('Warning', {
      category: jsResult.category,
      confidence: jsResult.confidence,
      message: mockNotification.text,
      app: mockNotification.appName,
      threatId: savedThreat?.id,
    });
  };

  const handleMessageToggle = async (val: boolean) => {
    setMessageProtection(val);
    setIsProtected(val || callProtection);
    await saveSettings({messageProtection: val});
  };

  const handleCallToggle = async (val: boolean) => {
    setCallProtection(val);
    setIsProtected(messageProtection || val);
    await saveSettings({callProtection: val});
  };

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
        <Text style={styles.headerTitle}>🛡️ Intent Firewall</Text>
        <Text style={styles.headerSub}>AI-Powered Scam Protection</Text>
      </View>

      <ScrollView style={styles.scroll} showsVerticalScrollIndicator={false}>

        <View style={[styles.statusCard, isProtected ? styles.statusActive : styles.statusInactive]}>
          <Text style={styles.statusIcon}>{isProtected ? '🛡️' : '⚠️'}</Text>
          <Text style={styles.statusTitle}>
            {isProtected ? 'Protected' : 'Not Protected'}
          </Text>
          <Text style={styles.statusSub}>
            {isProtected ? 'Monitoring your messages' : 'Enable protection below'}
          </Text>
        </View>

        <Text style={styles.sectionTitle}>PROTECTION</Text>

        <View style={styles.toggleCard}>
          <View style={styles.toggleLeft}>
            <Text style={styles.toggleTitle}>💬 Message Protection</Text>
            <Text style={styles.toggleSub}>WhatsApp, SMS, Telegram</Text>
          </View>
          <Switch
            value={messageProtection}
            onValueChange={handleMessageToggle}
            trackColor={{false: '#333', true: '#E63946'}}
            thumbColor={messageProtection ? '#fff' : '#888'}
          />
        </View>

        <View style={styles.toggleCard}>
          <View style={styles.toggleLeft}>
            <Text style={styles.toggleTitle}>📞 Call Protection</Text>
            <Text style={styles.toggleSub}>Detect scam callers</Text>
          </View>
          <Switch
            value={callProtection}
            onValueChange={handleCallToggle}
            trackColor={{false: '#333', true: '#E63946'}}
            thumbColor={callProtection ? '#fff' : '#888'}
          />
        </View>

        <Text style={styles.sectionTitle}>RECENT THREATS</Text>

        {recentThreats.length === 0 ? (
          <View style={styles.emptyCard}>
            <Text style={styles.emptyIcon}>✅</Text>
            <Text style={styles.emptyText}>No threats detected</Text>
          </View>
        ) : (
          recentThreats.map(threat => (
            <View key={threat.id} style={styles.threatCard}>
              <Text style={styles.threatIcon}>{threat.appIcon}</Text>
              <View style={styles.threatInfo}>
                <Text style={styles.threatApp}>{threat.app}</Text>
                <Text style={styles.threatCategory}>{threat.category}</Text>
              </View>
              <Text style={styles.threatConfidence}>{threat.confidence}%</Text>
            </View>
          ))
        )}

        <View style={styles.privacyCard}>
          <Text style={styles.privacyText}>
            🔒 All analysis happens on-device. No data is ever sent to any server.
          </Text>
        </View>

        {__DEV__ && (
          <TouchableOpacity style={styles.simulateButton} onPress={simulateAttack}>
            <Text style={styles.simulateButtonText}>Simulate Attack (Test)</Text>
          </TouchableOpacity>
        )}

        <View style={{height: 30}} />
      </ScrollView>

      <View style={styles.bottomNav}>
        {NAV_ITEMS.map(item => (
          <TouchableOpacity
            key={item.label}
            style={styles.navItem}
            onPress={() => navigation.navigate(item.screen)}>
            <Text style={styles.navIcon}>{item.icon}</Text>
            <Text style={[styles.navLabel, item.screen === 'Home' && styles.navLabelActive]}>
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
  statusCard: {
    borderRadius: 16, padding: 24, marginTop: 20,
    alignItems: 'center', borderWidth: 1,
  },
  statusActive: {backgroundColor: '#0D2818', borderColor: '#2D6A4F'},
  statusInactive: {backgroundColor: '#2D0D0D', borderColor: '#E63946'},
  statusIcon: {fontSize: 40},
  statusTitle: {fontSize: 22, fontWeight: 'bold', color: '#fff', marginTop: 8},
  statusSub: {fontSize: 13, color: '#A0AEC0', marginTop: 4},
  sectionTitle: {
    fontSize: 11, fontWeight: 'bold', color: '#A0AEC0',
    marginTop: 24, marginBottom: 12, letterSpacing: 1.5,
  },
  toggleCard: {
    backgroundColor: '#16213E', borderRadius: 12, padding: 16,
    flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between',
    marginBottom: 10, borderWidth: 1, borderColor: '#2D3748',
  },
  toggleLeft: {flex: 1},
  toggleTitle: {fontSize: 15, fontWeight: '600', color: '#fff'},
  toggleSub: {fontSize: 12, color: '#A0AEC0', marginTop: 2},
  emptyCard: {
    backgroundColor: '#16213E', borderRadius: 12, padding: 24,
    alignItems: 'center', borderWidth: 1, borderColor: '#2D3748',
  },
  emptyIcon: {fontSize: 32},
  emptyText: {fontSize: 14, color: '#A0AEC0', marginTop: 8},
  threatCard: {
    backgroundColor: '#16213E', borderRadius: 12, padding: 14,
    flexDirection: 'row', alignItems: 'center',
    marginBottom: 8, borderWidth: 1, borderColor: '#E6394630',
  },
  threatIcon: {fontSize: 24, marginRight: 12},
  threatInfo: {flex: 1},
  threatApp: {fontSize: 14, fontWeight: '600', color: '#fff'},
  threatCategory: {fontSize: 11, color: '#E63946', marginTop: 2},
  threatConfidence: {fontSize: 16, fontWeight: 'bold', color: '#E63946'},
  privacyCard: {
    backgroundColor: '#16213E', borderRadius: 12, padding: 16,
    marginTop: 8, borderWidth: 1, borderColor: '#2D3748',
  },
  privacyText: {fontSize: 12, color: '#A0AEC0', textAlign: 'center', lineHeight: 18},
  simulateButton: {
    backgroundColor: '#E63946', borderRadius: 12, paddingVertical: 14,
    marginTop: 12, alignItems: 'center',
  },
  simulateButtonText: {
    color: '#fff', fontSize: 14, fontWeight: '700', letterSpacing: 0.5,
  },
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

export default HomeScreen;