import AsyncStorage from '@react-native-async-storage/async-storage';

const ANALYTICS_KEY = '@intentfirewall_analytics';

export interface DetectionStats {
  totalScanned: number;
  threatsBlocked: number;
  falsePositives: number;
  lastScanTime: number;
  categoryBreakdown: Record<string, number>;
}

const defaultStats: DetectionStats = {
  totalScanned: 0,
  threatsBlocked: 0,
  falsePositives: 0,
  lastScanTime: 0,
  categoryBreakdown: {},
};

export const getStats = async (): Promise<DetectionStats> => {
  try {
    const data = await AsyncStorage.getItem(ANALYTICS_KEY);
    return data ? JSON.parse(data) : defaultStats;
  } catch {
    return defaultStats;
  }
};

export const recordDetection = async (
  detected: boolean,
  categories: string[]
): Promise<void> => {
  const stats = await getStats();
  
  stats.totalScanned += 1;
  if (detected) {
    stats.threatsBlocked += 1;
    categories.forEach(cat => {
      stats.categoryBreakdown[cat] = (stats.categoryBreakdown[cat] || 0) + 1;
    });
  }
  stats.lastScanTime = Date.now();
  
  await AsyncStorage.setItem(ANALYTICS_KEY, JSON.stringify(stats));
};

export const recordFalsePositive = async (): Promise<void> => {
  const stats = await getStats();
  stats.falsePositives += 1;
  await AsyncStorage.setItem(ANALYTICS_KEY, JSON.stringify(stats));
};

export const resetStats = async (): Promise<void> => {
  await AsyncStorage.setItem(ANALYTICS_KEY, JSON.stringify(defaultStats));
};