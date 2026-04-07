// __tests__/scamDetector.test.ts
import { detectScam } from '../src/utils/scamDetector';

describe('detectScam', () => {
  test('detects OTP scam message', () => {
    const result = detectScam('Your OTP is 123456. Do not share with anyone.');
    expect(result.isScam).toBe(true);
    expect(result.category).toBe('OTP');
  });

  test('detects financial fraud', () => {
    const result = detectScam('Your account has been compromised. Transfer money now to secure it.');
    expect(result.isScam).toBe(true);
    expect(result.category).toBe('Financial Fraud');
  });

  test('allows benign message', () => {
    const result = detectScam('Hey, are we still meeting for lunch?');
    expect(result.isScam).toBe(false);
  });
});
