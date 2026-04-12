import React, { useState, useCallback } from 'react';
import {
  View,
  Text,
  StyleSheet,
  ScrollView,
  TouchableOpacity,
} from 'react-native';
import { useFocusEffect } from '@react-navigation/native';
import { getStats, resetStats, DetectionStats } from '../utils/analyticsStore';

export const AnalyticsScreen: React.FC = () => {
  const [stats, setStats] = useState<DetectionStats | null>(null);
  const [loading, setLoading] = useState(true);

  useFocusEffect(
    useCallback(() => {
      loadStats();
    }, [])
  );

  const loadStats = async () => {
    setLoading(true);
    const data = await getStats();
    setStats(data);
    setLoading(false);
  };

  const handleReset = () => {
    resetStats().then(loadStats);
  };

  if (loading || !stats) {
    return (
      <View style={styles.container}>
        <Text style={styles.loading}>Loading stats...</Text>
      </View>
    );
  }

  const detectionRate = stats.totalScanned > 0 
    ? ((stats.threatsBlocked / stats.totalScanned) * 100).toFixed(1)
    : '0';

  return (
    <ScrollView style={styles.container}>
      <Text style={styles.title}>🛡️ Protection Stats</Text>
      
      {/* Main Stats Cards */}
      <View style={styles.statsGrid}>
        <View style={styles.statCard}>
          <Text style={styles.statValue}>{stats.totalScanned}</Text>
          <Text style={styles.statLabel}>Messages Scanned</Text>
        </View>
        
        <View style={[styles.statCard, styles.statCardHighlight]}>
          <Text style={[styles.statValue, styles.statValueHighlight]}>
            {stats.threatsBlocked}
          </Text>
          <Text style={styles.statLabel}>Threats Blocked</Text>
        </View>
        
        <View style={styles.statCard}>
          <Text style={styles.statValue}>{detectionRate}%</Text>
          <Text style={styles.statLabel}>Detection Rate</Text>
        </View>
        
        <View style={styles.statCard}>
          <Text style={styles.statValue}>{stats.falsePositives}</Text>
          <Text style={styles.statLabel}>False Positives</Text>
        </View>
      </View>

      {/* Category Breakdown */}
      <View style={styles.section}>
        <Text style={styles.sectionTitle}>Threat Categories</Text>
        
        {Object.keys(stats.categoryBreakdown).length === 0 ? (
          <Text style={styles.emptyText}>
            No threats detected yet. Stay safe! 🎉
          </Text>
        ) : (
          Object.entries(stats.categoryBreakdown)
            .sort(([, a], [, b]) => b - a)
            .map(([category, count]) => (
              <View key={category} style={styles.categoryRow}>
                <Text style={styles.categoryName}>{category}</Text>
                <View style={styles.categoryBarContainer}>
                  <View
                    style={[
                      styles.categoryBar,
                      { width: `${(count / stats.threatsBlocked) * 100}%` },
                    ]}
                  />
                </View>
                <Text style={styles.categoryCount}>{count}</Text>
              </View>
            ))
        )}
      </View>

      {/* Last Activity */}
      <View style={styles.section}>
        <Text style={styles.sectionTitle}>Last Activity</Text>
        <Text style={styles.lastScan}>
          {stats.lastScanTime > 0
            ? new Date(stats.lastScanTime).toLocaleString()
            : 'No scans yet'}
        </Text>
      </View>

      {/* Reset Button */}
      <TouchableOpacity style={styles.resetButton} onPress={handleReset}>
        <Text style={styles.resetButtonText}>Reset Statistics</Text>
      </TouchableOpacity>
    </ScrollView>
  );
};

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#0B0C10',
    padding: 16,
  },
  title: {
    fontSize: 28,
    fontFamily: 'SpaceGrotesk', fontWeight: 'bold',
    color: '#fff',
    marginBottom: 20,
  },
  loading: {
    color: '#888',
    textAlign: 'center',
    marginTop: 40,
  },
  statsGrid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    justifyContent: 'space-between',
    marginBottom: 20,
  },
  statCard: {
    width: '48%',
    backgroundColor: '#2a2a4e',
    borderRadius: 12,
    padding: 16,
    marginBottom: 12,
    alignItems: 'center',
  },
  statCardHighlight: {
    backgroundColor: '#3a2a4e',
    borderWidth: 1,
    borderColor: '#ff6b6b',
  },
  statValue: {
    fontSize: 32,
    fontFamily: 'SpaceGrotesk', fontWeight: 'bold',
    color: '#fff',
  },
  statValueHighlight: {
    color: '#ff6b6b',
  },
  statLabel: {
    fontSize: 12,
    color: '#888',
    marginTop: 4,
  },
  section: {
    marginBottom: 20,
  },
  sectionTitle: {
    fontSize: 18,
    fontFamily: 'SpaceGrotesk', fontWeight: 'bold',
    color: '#fff',
    marginBottom: 12,
  },
  categoryRow: {
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: 8,
  },
  categoryName: {
    width: 100,
    color: '#aaa',
    fontSize: 12,
  },
  categoryBarContainer: {
    flex: 1,
    height: 8,
    backgroundColor: '#2a2a4e',
    borderRadius: 4,
    marginHorizontal: 8,
  },
  categoryBar: {
    height: '100%',
    backgroundColor: '#ff6b6b',
    borderRadius: 4,
  },
  categoryCount: {
    width: 30,
    color: '#aaa',
    fontSize: 12,
    textAlign: 'right',
  },
  emptyText: {
    color: '#44dd88',
    textAlign: 'center',
    padding: 20,
  },
  lastScan: {
    color: '#aaa',
    fontSize: 14,
  },
  resetButton: {
    backgroundColor: '#3a2a3a',
    padding: 16,
    borderRadius: 8,
    alignItems: 'center',
    marginTop: 20,
  },
  resetButtonText: {
    color: '#ff6b6b',
    fontSize: 14,
  },
});