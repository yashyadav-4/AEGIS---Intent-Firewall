import React, {useEffect, useRef} from 'react';
import {
  View,
  Text,
  StyleSheet,
  TouchableOpacity,
  Animated,
  StatusBar,
} from 'react-native';

type Props = {
  category?: string;
  confidence?: number;
  onBlock: () => void;
  onDismiss: () => void;
  onCallHelp: () => void;
};

const WarningScreen = ({
  category = 'Urgency / Financial Scam',
  confidence = 94,
  onBlock,
  onDismiss,
  onCallHelp,
}: Props) => {
  const pulseAnim = useRef(new Animated.Value(1)).current;
  const slideAnim = useRef(new Animated.Value(50)).current;
  const fadeAnim = useRef(new Animated.Value(0)).current;

  useEffect(() => {
    // Slide up + fade in on mount
    Animated.parallel([
      Animated.timing(slideAnim, {
        toValue: 0,
        duration: 300,
        useNativeDriver: true,
      }),
      Animated.timing(fadeAnim, {
        toValue: 1,
        duration: 300,
        useNativeDriver: true,
      }),
    ]).start();

    // Pulse animation on warning icon
    Animated.loop(
      Animated.sequence([
        Animated.timing(pulseAnim, {
          toValue: 1.2,
          duration: 600,
          useNativeDriver: true,
        }),
        Animated.timing(pulseAnim, {
          toValue: 1,
          duration: 600,
          useNativeDriver: true,
        }),
      ]),
    ).start();
  }, [fadeAnim, pulseAnim, slideAnim]);

  return (
    <Animated.View
      style={[
        styles.container,
        {opacity: fadeAnim, transform: [{translateY: slideAnim}]},
      ]}>
      <StatusBar barStyle="light-content" backgroundColor="#E63946" />

      {/* Top Section */}
      <View style={styles.topSection}>
        {/* Pulsing Warning Icon */}
        <Animated.Text
          style={[styles.warningIcon, {transform: [{scale: pulseAnim}]}]}>
          ⚠️
        </Animated.Text>

        <Text style={styles.warningTitle}>SCAM DETECTED</Text>
        <Text style={styles.warningSubtitle}>
          This message shows signs of psychological manipulation
        </Text>

        {/* Category Badge */}
        <View style={styles.categoryBadge}>
          <Text style={styles.categoryText}>{category.toUpperCase()}</Text>
        </View>
      </View>

      {/* Confidence Meter */}
      <View style={styles.confidenceContainer}>
        <View style={styles.confidenceHeader}>
          <Text style={styles.confidenceLabel}>Threat Confidence</Text>
          <Text style={styles.confidenceValue}>{confidence}%</Text>
        </View>
        <View style={styles.confidenceBarBg}>
          <View style={[styles.confidenceBarFill, {width: `${confidence}%`}]} />
        </View>
      </View>

      {/* Explanation Box */}
      <View style={styles.explanationBox}>
        <Text style={styles.explanationTitle}>🧠 What's happening?</Text>
        <Text style={styles.explanationText}>
          This message creates artificial time pressure to force a hasty
          decision. This is a common financial scam tactic designed to bypass
          your rational thinking.
        </Text>
        <View style={styles.tacticBadge}>
          <Text style={styles.tacticText}>URGENCY MANIPULATION</Text>
        </View>
      </View>

      {/* Action Buttons */}
      <View style={styles.buttonContainer}>
        <TouchableOpacity style={styles.blockButton} onPress={onBlock}>
          <Text style={styles.blockButtonText}>🚫 BLOCK SENDER</Text>
        </TouchableOpacity>

        <TouchableOpacity style={styles.helpButton} onPress={onCallHelp}>
          <Text style={styles.helpButtonText}>📞 CALL TRUSTED PERSON</Text>
        </TouchableOpacity>

        <TouchableOpacity style={styles.dismissButton} onPress={onDismiss}>
          <Text style={styles.dismissButtonText}>
            I understand the risk — Dismiss
          </Text>
        </TouchableOpacity>
      </View>
    </Animated.View>
  );
};

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#E63946',
  },
  topSection: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    paddingTop: 60,
    paddingHorizontal: 24,
  },
  warningIcon: {
    fontSize: 80,
    marginBottom: 16,
  },
  warningTitle: {
    fontSize: 36,
    fontWeight: 'bold',
    color: '#fff',
    letterSpacing: 2,
    textAlign: 'center',
  },
  warningSubtitle: {
    fontSize: 14,
    color: 'rgba(255,255,255,0.85)',
    textAlign: 'center',
    marginTop: 8,
    lineHeight: 20,
  },
  categoryBadge: {
    marginTop: 16,
    backgroundColor: 'rgba(0,0,0,0.25)',
    borderRadius: 20,
    paddingHorizontal: 16,
    paddingVertical: 6,
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.4)',
  },
  categoryText: {
    color: '#fff',
    fontWeight: 'bold',
    fontSize: 12,
    letterSpacing: 1,
  },
  confidenceContainer: {
    marginHorizontal: 24,
    marginBottom: 16,
  },
  confidenceHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    marginBottom: 8,
  },
  confidenceLabel: {
    color: 'rgba(255,255,255,0.85)',
    fontSize: 13,
  },
  confidenceValue: {
    color: '#fff',
    fontWeight: 'bold',
    fontSize: 13,
  },
  confidenceBarBg: {
    height: 8,
    backgroundColor: 'rgba(0,0,0,0.25)',
    borderRadius: 4,
  },
  confidenceBarFill: {
    height: 8,
    backgroundColor: '#fff',
    borderRadius: 4,
  },
  explanationBox: {
    marginHorizontal: 24,
    backgroundColor: 'rgba(0,0,0,0.2)',
    borderRadius: 16,
    padding: 16,
    marginBottom: 24,
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.2)',
  },
  explanationTitle: {
    color: '#fff',
    fontWeight: 'bold',
    fontSize: 14,
    marginBottom: 8,
  },
  explanationText: {
    color: 'rgba(255,255,255,0.85)',
    fontSize: 13,
    lineHeight: 20,
  },
  tacticBadge: {
    marginTop: 12,
    backgroundColor: 'rgba(255,255,255,0.2)',
    borderRadius: 6,
    paddingHorizontal: 10,
    paddingVertical: 4,
    alignSelf: 'flex-start',
  },
  tacticText: {
    color: '#fff',
    fontWeight: 'bold',
    fontSize: 11,
    letterSpacing: 1,
  },
  buttonContainer: {
    paddingHorizontal: 24,
    paddingBottom: 40,
    gap: 10,
  },
  blockButton: {
    backgroundColor: '#fff',
    borderRadius: 12,
    padding: 16,
    alignItems: 'center',
  },
  blockButtonText: {
    color: '#E63946',
    fontWeight: 'bold',
    fontSize: 16,
  },
  helpButton: {
    backgroundColor: 'rgba(0,0,0,0.25)',
    borderRadius: 12,
    padding: 16,
    alignItems: 'center',
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.4)',
  },
  helpButtonText: {
    color: '#fff',
    fontWeight: 'bold',
    fontSize: 16,
  },
  dismissButton: {
    padding: 12,
    alignItems: 'center',
  },
  dismissButtonText: {
    color: 'rgba(255,255,255,0.6)',
    fontSize: 13,
    textDecorationLine: 'underline',
  },
});

export default WarningScreen;