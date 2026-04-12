// __tests__/storage.test.ts
import { saveThreat, getThreats, clearThreats } from '../src/utils/storage';

describe('storage', () => {
  beforeEach(async () => {
    await clearThreats();
  });

  test('saves and retrieves threat', async () => {
    await saveThreat({
      app: 'WhatsApp',
      message: 'Test scam',
      category: 'OTP',
      confidence: 0.9,
      blocked: false,
      appIcon: '',
      time: 'Now',
    });
    
    const threats = await getThreats();
    expect(threats.length).toBe(1);
    expect(threats[0].message).toBe('Test scam');
  });

  test('clears all threats', async () => {
    await saveThreat({ 
      app: 'WhatsApp', 
      message: 'Test', 
      category: 'OTP', 
      confidence: 0.9, 
      blocked: false,
      appIcon: '',
      time: 'Now',
    });
    await clearThreats();
    
    const threats = await getThreats();
    expect(threats.length).toBe(0);
  });
});
