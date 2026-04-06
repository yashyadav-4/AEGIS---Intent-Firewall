export interface ScamResult {
  isScam: boolean;
  confidence: number;
  category: string;
  matchedKeywords: string[];
}

// ─── Keyword Categories ─────────────────────────────────────────

const URGENCY_FINANCIAL = [
  'urgent', 'immediately', 'expire', 'expires', 'expiring',
  'last chance', 'act now', 'limited time', 'deadline',
  'bank account', 'account blocked', 'account suspended',
  'transaction failed', 'payment failed', 'kyc', 'kyc update',
  'your account', 'debit card', 'credit card', 'blocked',
  'frozen', 'suspended', 'verify now', 'verify your',
];

const OTP_PHISHING = [
  'otp', 'one time password', 'verification code', 'verify code',
  'enter code', 'share otp', 'do not share', 'never share',
  'pin', 'passcode', '6 digit', '4 digit',
];

const AUTHORITY_SCAM = [
  'rbi', 'reserve bank', 'income tax', 'it department',
  'police', 'cybercrime', 'court', 'legal action',
  'arrest', 'fir', 'case filed', 'government',
  'aadhaar', 'aadhar', 'pan card', 'uidai',
];

const IMPERSONATION = [
  'amazon', 'flipkart', 'paytm', 'phonepe', 'google pay',
  'gpay', 'sbi', 'hdfc', 'icici', 'axis bank',
  'customer care', 'support team', 'helpline',
  'refund', 'cashback', 'prize', 'won', 'winner',
  'lottery', 'reward', 'gift',
];

const FINANCIAL_FRAUD = [
  'send money', 'transfer money', 'upi', 'click here',
  'link', 'http', 'www', 'bit.ly', 'tinyurl',
  'invest', 'profit', 'earning', 'double', 'triple',
  'crypto', 'bitcoin', 'trading', 'scheme',
];

// ─── Main Detector ──────────────────────────────────────────────

export const detectScam = (text: string): ScamResult => {
  const lower = text.toLowerCase();
  const matched: string[] = [];
  let score = 0;

  const check = (keywords: string[], weight: number) => {
    keywords.forEach(kw => {
      if (lower.includes(kw)) {
        matched.push(kw);
        score += weight;
      }
    });
  };

  check(URGENCY_FINANCIAL, 15);
  check(OTP_PHISHING, 25);
  check(AUTHORITY_SCAM, 20);
  check(IMPERSONATION, 10);
  check(FINANCIAL_FRAUD, 15);

  const confidence = Math.min(score, 100);
  const isScam = confidence >= 25;

  const category = getCategory(lower, matched);

  return {isScam, confidence, category, matchedKeywords: matched};
};

const getCategory = (text: string, matched: string[]): string => {
  if (matched.some(k => OTP_PHISHING.includes(k))) return 'OTP Phishing';
  if (matched.some(k => AUTHORITY_SCAM.includes(k))) return 'Authority Scam';
  if (matched.some(k => URGENCY_FINANCIAL.includes(k))) return 'Urgency/Financial';
  if (matched.some(k => IMPERSONATION.includes(k))) return 'Impersonation';
  if (matched.some(k => FINANCIAL_FRAUD.includes(k))) return 'Financial Fraud';
  return 'Suspicious';
};