import React, {useState, useCallback, useEffect} from 'react';
import {
  View, Text, StyleSheet, StatusBar,
  ScrollView, TouchableOpacity,
  DeviceEventEmitter,
} from 'react-native';
import {useNavigation, useFocusEffect} from '@react-navigation/native';
import {
  getThreats,
  clearThreats,
  getMessageEvents,
  clearMessageEvents,
  formatTime,
  Threat,
  MessageEvent,
} from '../utils/storage';

const FILTERS = ['All', 'WhatsApp', 'SMS', 'Calls'];
const MESSAGE_FILTERS = ['All', 'WhatsApp', 'Telegram', 'SMS', 'Hidden'];

const HistoryScreen = () => {
  const navigation = useNavigation();
  const [activeTab, setActiveTab] = useState<'Threats' | 'Messages'>('Threats');
  const [activeFilter, setActiveFilter] = useState('All');
  const [activeMessageFilter, setActiveMessageFilter] = useState('All');
  const [threats, setThreats] = useState<Threat[]>([]);
  const [messages, setMessages] = useState<MessageEvent[]>([]);
  const [isLoading, setIsLoading] = useState(true);

  useFocusEffect(
    useCallback(() => {
      loadThreats();
      loadMessages();
    }, []),
  );

  useEffect(() => {
    const sub = DeviceEventEmitter.addListener('onNotification', () => {
      loadMessages();
    });
    return () => sub.remove();
  }, []);

  useEffect(() => {
    if (activeTab !== 'Messages') {
      return;
    }

    const timer = setInterval(() => {
      loadMessages();
    }, 2000);

    return () => clearInterval(timer);
  }, [activeTab]);

  const loadThreats = async () => {
    try {
      const data = await getThreats();
      setThreats(data || []);
    } catch (err) {
      console.error(err);
    } finally {
      setIsLoading(false);
    }
  };

  const loadMessages = async () => {
    try {
      const data = await getMessageEvents();
      setMessages(data || []);
    } catch (err) {
      console.error(err);
    }
  };

  const handleClearAll = async () => {
    try {
      await clearThreats();
      setThreats([]);
    } catch (err) {
      console.error(err);
    }
  };

  const handleClearMessages = async () => {
    try {
      await clearMessageEvents();
      setMessages([]);
    } catch (err) {
      console.error(err);
    }
  };

  const filtered = threats.filter(t =>
    activeFilter === 'All' ? true :
    activeFilter === 'Calls' ? t.app === 'Phone Call' :
    t.app === activeFilter,
  );

  const filteredMessages = messages.filter(m => {
    if (activeMessageFilter === 'All') return true;
    if (activeMessageFilter === 'Hidden') {
      return (
        m.matchedCategory === 'HIDDEN_BY_OS' ||
        m.message.includes('[Hidden by Android privacy settings]')
      );
    }
    return m.app === activeMessageFilter;
  });

  const getMessageChip = (item: MessageEvent) => {
    if (item.matchedCategory === 'SMS_BROADCAST_DIRECT') return 'SMS Direct';
    if (item.matchedCategory === 'HIDDEN_BY_OS') return 'Hidden';
    if (item.matchedCategory === 'NO_PREVIEW') return 'No Preview';
    return 'Notification';
  };

  if (isLoading) {
    return (
      <View style={[styles.container, {justifyContent: 'center', alignItems: 'center'}]}>
        <Text style={{color: '#fff'}}>Loading history...</Text>
      </View>
    );
  }

  return (
    <View style={styles.container}>
      <StatusBar barStyle="light-content" backgroundColor="#0B0C10" />

      <View style={styles.header}>
        <Text style={styles.headerTitle}>📋 Threat History</Text>
        <Text style={styles.headerSub}>
          {activeTab === 'Threats'
            ? `${threats.length} threats blocked total`
            : `${messages.length} captured messages total`}
        </Text>
      </View>

      <View style={styles.tabRow}>
        {(['Threats', 'Messages'] as const).map(tab => (
          <TouchableOpacity
            key={tab}
            style={[styles.tabPill, activeTab === tab && styles.tabPillActive]}
            onPress={() => setActiveTab(tab)}>
            <Text style={[styles.tabPillText, activeTab === tab && styles.tabPillTextActive]}>
              {tab}
            </Text>
          </TouchableOpacity>
        ))}
      </View>

      {activeTab === 'Threats' ? (
        <View style={styles.filterRow}>
          {FILTERS.map(f => (
            <TouchableOpacity
              key={f}
              style={[styles.filterTab, activeFilter === f && styles.filterTabActive]}
              onPress={() => setActiveFilter(f)}>
              <Text style={[styles.filterText, activeFilter === f && styles.filterTextActive]}>
                {f}
              </Text>
            </TouchableOpacity>
          ))}
        </View>
      ) : (
        <View style={styles.filterRow}>
          {MESSAGE_FILTERS.map(f => (
            <TouchableOpacity
              key={f}
              style={[styles.filterTab, activeMessageFilter === f && styles.filterTabActive]}
              onPress={() => setActiveMessageFilter(f)}>
              <Text style={[styles.filterText, activeMessageFilter === f && styles.filterTextActive]}>
                {f}
              </Text>
            </TouchableOpacity>
          ))}
        </View>
      )}

      <ScrollView style={styles.scroll} showsVerticalScrollIndicator={false}>
        {activeTab === 'Threats' ? (
          <>
            {filtered.length === 0 ? (
              <View style={styles.emptyState}>
                <Text style={styles.emptyIcon}>✅</Text>
                <Text style={styles.emptyText}>No threats found</Text>
                <Text style={styles.emptySubText}>You're all clear!</Text>
              </View>
            ) : (
              filtered.map((threat) => (
                <View key={threat.id} style={styles.threatCard}>
                  <View style={styles.threatLeft}>
                    <Text style={styles.threatAppIcon}>{threat.appIcon}</Text>
                    <View>
                      <Text style={styles.threatApp}>{threat.app}</Text>
                      <Text style={styles.threatTime}>{threat.time}</Text>
                    </View>
                  </View>
                  <View style={styles.rightCol}>
                    <View style={styles.threatBadge}>
                      <Text style={styles.threatBadgeText}>{threat.category}</Text>
                    </View>
                    <Text style={[styles.status, threat.blocked ? styles.blocked : styles.allowed]}>
                      {threat.blocked ? '🚫 Blocked' : '⚠️ Allowed'}
                    </Text>
                  </View>
                </View>
              ))
            )}

            {threats.length > 0 && (
              <TouchableOpacity style={styles.dangerButton} onPress={handleClearAll}>
                <Text style={styles.dangerText}>🗑️ Clear Threat History</Text>
              </TouchableOpacity>
            )}
          </>
        ) : (
          <>
            {filteredMessages.length === 0 ? (
              <View style={styles.emptyState}>
                <Text style={styles.emptyIcon}>📭</Text>
                <Text style={styles.emptyText}>No message events yet</Text>
                <Text style={styles.emptySubText}>Incoming notifications will appear here.</Text>
              </View>
            ) : (
              filteredMessages.map(item => (
                <View key={item.id} style={styles.messageCard}>
                  <View style={styles.messageHead}>
                    <View style={styles.threatLeft}>
                      <Text style={styles.threatAppIcon}>{item.appIcon}</Text>
                      <View>
                        <Text style={styles.threatApp}>{item.app}</Text>
                        <Text style={styles.threatTime}>{formatTime(item.timestamp)}</Text>
                      </View>
                    </View>
                    <View style={styles.messageChip}>
                      <Text style={styles.messageChipText}>{getMessageChip(item)}</Text>
                    </View>
                  </View>

                  <Text style={styles.messageTitle}>{item.title || 'Unknown sender'}</Text>
                  <Text style={styles.messageBody}>{item.message || '[Empty message]'}</Text>
                </View>
              ))
            )}

            {messages.length > 0 && (
              <TouchableOpacity style={styles.dangerButton} onPress={handleClearMessages}>
                <Text style={styles.dangerText}>🗑️ Clear Message History</Text>
              </TouchableOpacity>
            )}
          </>
        )}

        <View style={{height: 30}} />
      </ScrollView>

      <View style={styles.bottomNav}>
        {[
          {icon: '🏠', label: 'Home', screen: 'Home'},
          {icon: '📋', label: 'History', screen: 'History'},
          {icon: '⚙️', label: 'Settings', screen: 'Settings'},
          {icon: '🐞', label: 'Debug', screen: 'Debug'},
        ].map((item) => (
          <TouchableOpacity
            key={item.label}
            style={styles.navItem}
            onPress={() => navigation.navigate(item.screen as never)}>
            <Text style={styles.navIcon}>{item.icon}</Text>
            <Text style={[styles.navLabel, item.screen === 'History' && styles.navLabelActive]}>
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
  tabRow: {
    flexDirection: 'row',
    paddingHorizontal: 20,
    paddingTop: 10,
    paddingBottom: 6,
    backgroundColor: '#0B0C10',
    gap: 8,
  },
  tabPill: {
    flex: 1,
    borderRadius: 999,
    paddingVertical: 10,
    alignItems: 'center',
    borderWidth: 1,
    borderColor: '#C5C6C750',
    backgroundColor: '#141E35',
  },
  tabPillActive: {backgroundColor: '#FF2A2A', borderColor: '#FF2A2A'},
  tabPillText: {fontSize: 12, color: '#C5C6C7', fontFamily: 'SpaceGrotesk', fontWeight: '700'},
  tabPillTextActive: {color: '#fff'},
  filterRow: {
    flexDirection: 'row', paddingHorizontal: 20,
    paddingVertical: 12, backgroundColor: '#0B0C10', gap: 8,
    flexWrap: 'wrap',
  },
  filterTab: {
    paddingHorizontal: 16, paddingVertical: 6,
    borderRadius: 20, borderWidth: 1, borderColor: '#C5C6C750',
  },
  filterTabActive: {backgroundColor: '#FF2A2A', borderColor: '#FF2A2A'},
  filterText: {fontSize: 12, color: '#C5C6C7'},
  filterTextActive: {color: '#fff', fontFamily: 'SpaceGrotesk', fontWeight: 'bold'},
  scroll: {flex: 1, paddingHorizontal: 20, paddingTop: 12},
  emptyState: {alignItems: 'center', marginTop: 60},
  emptyIcon: {fontSize: 48},
  emptyText: {fontSize: 16, color: '#fff', marginTop: 12, fontFamily: 'SpaceGrotesk', fontWeight: 'bold'},
  emptySubText: {fontSize: 13, color: '#C5C6C7', marginTop: 4},
  threatCard: {
    backgroundColor: '#1C1D24', borderRadius: 12, padding: 14,
    flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between',
    marginBottom: 8, borderWidth: 1, borderColor: '#FF2A2A20',
  },
  threatLeft: {flexDirection: 'row', alignItems: 'center'},
  threatAppIcon: {fontSize: 24, marginRight: 12},
  threatApp: {fontSize: 14, fontFamily: 'SpaceGrotesk', fontWeight: '600', color: '#fff'},
  threatTime: {fontSize: 11, color: '#C5C6C7', marginTop: 2},
  rightCol: {alignItems: 'flex-end', gap: 4},
  messageCard: {
    backgroundColor: '#1C1D24',
    borderRadius: 12,
    padding: 14,
    marginBottom: 8,
    borderWidth: 1,
    borderColor: '#C5C6C750',
  },
  messageHead: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 10,
  },
  messageChip: {
    backgroundColor: '#2B3550',
    borderWidth: 1,
    borderColor: '#4A5A88',
    borderRadius: 999,
    paddingVertical: 4,
    paddingHorizontal: 8,
  },
  messageChipText: {
    color: '#D6E4FF',
    fontSize: 10,
    fontFamily: 'SpaceGrotesk', fontWeight: '700',
  },
  messageTitle: {
    color: '#fff',
    fontSize: 13,
    fontFamily: 'SpaceGrotesk', fontWeight: '700',
    marginBottom: 6,
  },
  messageBody: {
    color: '#D0D7E7',
    fontSize: 12,
    lineHeight: 18,
  },
  threatBadge: {
    backgroundColor: '#FF2A2A20', borderRadius: 6,
    paddingHorizontal: 8, paddingVertical: 4,
    borderWidth: 1, borderColor: '#FF2A2A',
  },
  threatBadgeText: {fontSize: 10, color: '#FF2A2A', fontFamily: 'SpaceGrotesk', fontWeight: 'bold'},
  status: {fontSize: 10, fontFamily: 'SpaceGrotesk', fontWeight: 'bold'},
  blocked: {color: '#FF2A2A'},
  allowed: {color: '#F6AD55'},
  dangerButton: {
    marginTop: 8, backgroundColor: '#2D0D0D', borderRadius: 12,
    padding: 16, alignItems: 'center', borderWidth: 1, borderColor: '#FF2A2A',
    marginBottom: 8,
  },
  dangerText: {fontSize: 14, color: '#FF2A2A', fontFamily: 'SpaceGrotesk', fontWeight: 'bold'},
  bottomNav: {
    flexDirection: 'row', backgroundColor: '#0B0C10',
    borderTopWidth: 1, borderTopColor: '#C5C6C750',
    paddingBottom: 20, paddingTop: 10,
  },
  navItem: {flex: 1, alignItems: 'center'},
  navIcon: {fontSize: 22},
  navLabel: {fontSize: 11, color: '#C5C6C7', marginTop: 4},
  navLabelActive: {color: '#FF2A2A', fontFamily: 'SpaceGrotesk', fontWeight: 'bold'},
});

export default HistoryScreen;
