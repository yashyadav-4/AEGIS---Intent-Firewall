import { NativeModules } from 'react-native';

const { NotificationService } = NativeModules;

export const HINGLISH_TEST_MESSAGES = [
  "OTP batao ya account band ho jayega",
  "Your SBI account will be blocked. Share OTP immediately",
  "UPI pin batao warna transaction fail ho jayega",
  "This is CBI officer. FIR registered against your number. Call back now.",
  "Income tax department notice pending. Verify details within 2 hours.",
  "Digital arrest warrant issued. Pay fine to avoid arrest.",
  "KYC expire ho gaya. Verify now or account suspend.",
  "Aadhaar verification required. Link now or SIM block.",
  "Your parcel seized at customs. Pay duty fee to release.",
  "Package from abroad held. Send processing fee Rs 5000.",
  "Last chance! Account block in 2 hours. Verify now.",
  "Urgent: Your number will be disconnected. Call within 1 hour.",
  "You won lottery Rs 10 lakh. Send processing fee Rs 1000.",
  "Investment opportunity: Double your money in 30 days.",
];

export const ENGLISH_TEST_MESSAGES = [
  "Your account has been compromised. Send OTP to verify.",
  "IRS Tax Refund: Click link to claim $5000.",
  "Your package is held at customs. Pay $50 to release.",
  "Urgent: Your Netflix subscription expires today.",
  "Congratulations! You've been selected for a free iPhone.",
];

export async function runDetectionTest(
  message: string,
  source: 'sms' | 'whatsapp' = 'sms'
): Promise<{ detected: boolean; confidence: number; categories: string[] }> {
  try {
    const result = await NotificationService.testDetection(message, source);
    return result;
  } catch (error) {
    console.error('Detection test failed:', error);
    throw error;
  }
}

export function getRandomTestMessage(language: 'hinglish' | 'english'): string {
  const messages = language === 'hinglish' ? HINGLISH_TEST_MESSAGES : ENGLISH_TEST_MESSAGES;
  return messages[Math.floor(Math.random() * messages.length)];
}
