import React from 'react';
import { View, Text, TouchableOpacity, StyleSheet } from 'react-native';

interface Props {
  children: React.ReactNode;
}

interface State {
  hasError: boolean;
  error: Error | null;
}

class ErrorBoundary extends React.Component<Props, State> {
  constructor(props: Props) {
    super(props);
    this.state = { hasError: false, error: null };
  }

  static getDerivedStateFromError(error: Error): State {
    return { hasError: true, error };
  }

  componentDidCatch(error: Error, errorInfo: React.ErrorInfo) {
    console.error('IntentFirewall Error:', error, errorInfo.componentStack);
  }

  handleReset = () => {
    this.setState({ hasError: false, error: null });
  };

  render() {
    if (this.state.hasError) {
      return (
        <View style={styles.container}>
          <Text style={styles.title}>⚠️ Something went wrong</Text>
          <Text style={styles.message}>
            The app encountered an error. This may be due to:
          </Text>
          <Text style={styles.reason}>• ML model loading failure</Text>
          <Text style={styles.reason}>• Permission issues</Text>
          <Text style={styles.reason}>• Memory constraints</Text>
          <TouchableOpacity style={styles.button} onPress={this.handleReset}>
            <Text style={styles.buttonText}>Try Again</Text>
          </TouchableOpacity>
        </View>
      );
    }
    return this.props.children;
  }
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    backgroundColor: '#1a1a2e',
    padding: 20,
  },
  title: { fontSize: 22, color: '#ff6b6b', marginBottom: 16 },
  message: { color: '#aaa', marginBottom: 12 },
  reason: { color: '#888', fontSize: 14, marginLeft: 10 },
  button: {
    marginTop: 24,
    backgroundColor: '#4ecdc4',
    paddingHorizontal: 32,
    paddingVertical: 12,
    borderRadius: 8,
  },
  buttonText: { color: '#000', fontWeight: 'bold' },
});

export default ErrorBoundary;
