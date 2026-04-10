import React, { useState, useCallback } from 'react';
import {
  View,
  Text,
  StyleSheet,
  ScrollView,
  TextInput,
  TouchableOpacity,
  ActivityIndicator,
} from 'react-native';
import { NativeModules } from 'react-native';
import { useFocusEffect, useNavigation } from '@react-navigation/native';

const { NotificationService } = NativeModules;

interface TestResult {
  id: string;
  timestamp: Date;
  type: 'sms' | 'whatsapp' | 'call';
  input: string;
  detected: boolean;
  confidence: number;
  categories: string[];
}

type Diagnostics = Record<string, unknown>;

const sanitizeDiagnostics = (input: unknown): unknown => {
  if (Array.isArray(input)) {
    return input.map(sanitizeDiagnostics);
  }
  if (input && typeof input === 'object') {
    const out: Record<string, unknown> = {};
    for (const [key, value] of Object.entries(input as Record<string, unknown>)) {
      const renamed = key
        .replace(/gemini/gi, 'tier3')
        .replace(/Gemini/g, 'Tier3');
      out[renamed] = sanitizeDiagnostics(value);
    }
    return out;
  }
  if (typeof input === 'string') {
    return input.replace(/gemini/gi, 'tier3');
  }
  return input;
};

const TEST_MESSAGES = {
  sms: [
    'OTP batao ya account band ho jayega',
    'Your SBI account will be blocked. Share OTP immediately',
    'KYC expire ho gaya. Verify now or account suspend.',
    'Congratulations! You won lottery Rs 10 lakh. Send processing fee Rs 1000.',
    'Your package seized at customs. Pay duty to release.',
  ],
  whatsapp: [
    'Hey, urgent! Transfer Rs 5000 to this UPI ID immediately',
    'Papa, police aa rahi hai, turant Rs 10,000 bhejo',
    'Your friend recommended you. Earn Rs 50,000 per month from home!',
  ],
  call: [
    '+919876543210',
    '+911234567890',
    '+919999999999',
  ],
};

export const DebugScreen: React.FC = () => {
  const [testType, setTestType] = useState<'sms' | 'whatsapp' | 'call'>('sms');
  const [customInput, setCustomInput] = useState('');
  const [results, setResults] = useState<TestResult[]>([]);
  const [isRunning, setIsRunning] = useState(false);
  const [nativeAvailable, setNativeAvailable] = useState(true);
  const [diagnostics, setDiagnostics] = useState<Diagnostics>({});
  const navigation = useNavigation();

  useFocusEffect(
    useCallback(() => {
      try {
        console.log("NOTIF_SERVICE=", Object.keys(NotificationService || {}));
        setNativeAvailable(!!NotificationService?.testDetection);
        NotificationService?.getProtectionDiagnostics?.().then((d: Diagnostics) =>
          setDiagnostics((sanitizeDiagnostics(d || {}) as Diagnostics) || {}),
        );
      } catch {
        setNativeAvailable(false);
      }
    }, [])
  );

  const refreshDiagnostics = async () => {
    try {
      const d = await NotificationService?.getProtectionDiagnostics?.();
      setDiagnostics((sanitizeDiagnostics(d || {}) as Diagnostics) || {});
    } catch (e) {
      setDiagnostics({ error: String(e) });
    }
  };

  const runTest = async (input: string): Promise<TestResult> => {
    const id = Date.now().toString();
    try {
      if (testType === 'call') {
        const result = await NotificationService.simulateCall(input, true, 3000);
        return {
          id,
          timestamp: new Date(),
          type: 'call',
          input,
          detected: result.deepfakeDetected || result.keywordDetected,
          confidence: Math.max(result.deepfakeScore || 0, result.keywordScore || 0),
          categories: [
            ...(result.deepfakeDetected ? ['DEEPFAKE'] : []),
            ...(result.keywords || []),
          ],
        };
      } else {
        const result = await NotificationService.testDetection(input, testType);
        return {
          id,
          timestamp: new Date(),
          type: testType,
          input,
          detected: result.detected,
          confidence: result.confidence,
          categories: result.categories || [],
        };
      }
    } catch (error) {
      return {
        id,
        timestamp: new Date(),
        type: testType,
        input,
        detected: false,
        confidence: 0,
        categories: ['ERROR: ' + (error as Error).message],
      };
    }
  };

  const runCustomTest = async () => {
    if (!customInput.trim()) return;
    setIsRunning(true);
    const result = await runTest(customInput.trim());
    setResults(prev => [result, ...prev]);
    setCustomInput('');
    setIsRunning(false);
  };

  const runAllTests = async () => {
    setIsRunning(true);
    const messages = TEST_MESSAGES[testType];
    const newResults: TestResult[] = [];
    for (const msg of messages) {
      const result = await runTest(msg);
      newResults.push(result);
      await new Promise(resolve => setTimeout(resolve, 500));
    }
    setResults(prev => [...newResults, ...prev]);
    setIsRunning(false);
  };

  const getResultColor = (result: TestResult) => {
    if (result.confidence > 0.85) return '#ff4444';
    if (result.confidence > 0.70) return '#ff8800';
    if (result.detected) return '#ffaa00';
    return '#44bb44';
  };

  return (
    <ScrollView style={styles.container}>
      <View style={styles.headerRow}>
        <TouchableOpacity style={styles.backBtn} onPress={() => navigation.goBack()}>
            <Text style={{color: '#fff', fontSize: 24}}>←</Text>
        </TouchableOpacity>
        <Text style={styles.title}>🧪 Debug Console</Text>
      </View>
      
      {!nativeAvailable && (
        <View style={styles.warning}>
          <Text style={styles.warningText}>⚠️ Native module not available.</Text>
        </View>
      )}

      <View style={styles.selector}>
        {(['sms', 'whatsapp', 'call'] as const).map(type => (
          <TouchableOpacity
            key={type}
            style={[styles.selectorButton, testType === type && styles.selectorButtonActive]}
            onPress={() => setTestType(type)}>
            <Text style={[styles.selectorText, testType === type && styles.selectorTextActive]}>
              {type.toUpperCase()}
            </Text>
          </TouchableOpacity>
        ))}
      </View>

      <View style={styles.inputCard}>
        <TextInput
          style={styles.input}
          value={customInput}
          onChangeText={setCustomInput}
          placeholder={testType === 'call' ? 'Enter phone number...' : 'Enter test message...'}
          placeholderTextColor="#666"
          multiline={testType !== 'call'}
          numberOfLines={testType === 'call' ? 1 : 3}
        />
        <TouchableOpacity
          style={[styles.button, isRunning && styles.buttonDisabled]}
          onPress={runCustomTest}
          disabled={isRunning || !customInput.trim()}>
          <Text style={styles.buttonText}>Run Test</Text>
        </TouchableOpacity>
      </View>

      <View style={styles.quickActions}>
        <TouchableOpacity
          style={[styles.button, styles.buttonPrimary, isRunning && styles.buttonDisabled]}
          onPress={runAllTests} disabled={isRunning}>
          {isRunning ? <ActivityIndicator color="#fff" /> : <Text style={styles.buttonText}>🚀 Run All {testType.toUpperCase()} Tests</Text>}
        </TouchableOpacity>
        <TouchableOpacity style={[styles.button, {marginTop: 10, backgroundColor: '#2D3748'}]} onPress={refreshDiagnostics}>
          <Text style={styles.buttonText}>Refresh Diagnostics</Text>
        </TouchableOpacity>
      </View>

      <View style={styles.resultCard}>
        <Text style={styles.resultsTitle}>Diagnostics</Text>
        <Text style={{color: '#A0AEC0', marginTop: 8, fontFamily: 'monospace'}}>
          {JSON.stringify(diagnostics, null, 2)}
        </Text>
      </View>

      <View style={styles.resultsHeader}>
        <Text style={styles.resultsTitle}>Results ({results.length})</Text>
        {results.length > 0 && <TouchableOpacity onPress={() => setResults([])}><Text style={styles.clearText}>Clear</Text></TouchableOpacity>}
      </View>

      {results.map(result => (
        <View key={result.id} style={[styles.resultCard, { borderLeftColor: getResultColor(result) }]}>
          <View style={styles.resultHeader}>
            <View style={styles.resultBadges}>
              <Text style={[styles.resultType, { color: getResultColor(result) }]}>{result.detected ? '🚨 DETECTED' : '✅ CLEAR'}</Text>
              <Text style={styles.resultType}>{result.type.toUpperCase()}</Text>
            </View>
            <Text style={styles.resultTime}>{result.timestamp.toLocaleTimeString()}</Text>
          </View>
          <Text style={styles.resultInput} numberOfLines={2}>{result.input}</Text>
          <View style={styles.resultDetails}>
            <Text style={styles.confidence}>Confidence: {(result.confidence * 100).toFixed(1)}%</Text>
          </View>
          {result.categories.length > 0 && (
            <View style={styles.categories}>
              {result.categories.map((cat, i) => <Text key={i} style={styles.categoryTag}>{cat}</Text>)}
            </View>
          )}
        </View>
      ))}
      <View style={{height: 60}} />
    </ScrollView>
  );
};

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#0D0D0D', padding: 16 },
  headerRow: { flexDirection: 'row', alignItems: 'center', marginBottom: 16, marginTop: 40 },
  backBtn: { marginRight: 16 },
  title: { fontSize: 24, fontWeight: 'bold', color: '#fff' },
  warning: { backgroundColor: '#ff8800', padding: 12, borderRadius: 8, marginBottom: 16 },
  warningText: { color: '#000', fontWeight: '600' },
  selector: { flexDirection: 'row', marginBottom: 16 },
  selectorButton: { flex: 1, padding: 12, backgroundColor: '#1A1A2E', marginHorizontal: 4, borderRadius: 8, alignItems: 'center' },
  selectorButtonActive: { backgroundColor: '#E63946' },
  selectorText: { color: '#A0AEC0', fontWeight: '600' },
  selectorTextActive: { color: '#fff' },
  inputCard: { backgroundColor: '#16213E', borderRadius: 12, padding: 16, marginBottom: 16 },
  input: { backgroundColor: '#0D0D0D', color: '#fff', borderRadius: 8, padding: 12, minHeight: 60, marginBottom: 12, fontSize: 14 },
  button: { backgroundColor: '#E63946', padding: 14, borderRadius: 8, alignItems: 'center' },
  buttonPrimary: { backgroundColor: '#E63946' },
  buttonDisabled: { opacity: 0.6 },
  buttonText: { color: '#fff', fontWeight: '600', fontSize: 16 },
  quickActions: { marginBottom: 20 },
  resultsHeader: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 },
  resultsTitle: { fontSize: 18, fontWeight: 'bold', color: '#fff' },
  clearText: { color: '#E63946', fontSize: 14 },
  resultCard: { backgroundColor: '#16213E', borderRadius: 12, padding: 16, marginBottom: 12, borderLeftWidth: 4 },
  resultHeader: { flexDirection: 'row', justifyContent: 'space-between', marginBottom: 8 },
  resultBadges: { flexDirection: 'row', gap: 8 },
  resultType: { fontSize: 12, fontWeight: 'bold', color: '#888' },
  resultTime: { fontSize: 12, color: '#666' },
  resultInput: { color: '#ddd', fontSize: 14, marginBottom: 8, fontFamily: 'monospace' },
  resultDetails: { flexDirection: 'row', justifyContent: 'space-between' },
  confidence: { color: '#aaa', fontSize: 12 },
  categories: { flexDirection: 'row', flexWrap: 'wrap', marginTop: 8, gap: 6 },
  categoryTag: { backgroundColor: '#E6394620', color: '#E63946', paddingHorizontal: 8, paddingVertical: 4, borderRadius: 4, fontSize: 10, fontWeight: '600' },
});
