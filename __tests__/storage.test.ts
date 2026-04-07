// __tests__/storage.test.ts
import { saveThreat, getThreats, clearThreats } from '../src/utils/storage';

describe('storage', () => {
  beforeEach(async () => {
    await clearThreats();
  });

  test('saves and retrieves threat', async () => {
    await saveThreat({
      id: 'test-1',
      app: 'WhatsApp',
      message: 'Test scam',
      category: 'OTP',
      confidence: 0.9,
      blocked: false,
      appIcon: '',
      time: 'Now',
      timestamp: Date.now()
    });
    
    const threats = await getThreats();
    expect(threats.length).toBe(1);
    expect(threats[0].message).toBe('Test scam');
  });

  test('clears all threats', async () => {
    await saveThreat({ 
      id: 'test-1', 
      app: 'WhatsApp', 
      message: 'Test', 
      category: 'OTP', 
      confidence: 0.9, 
      blocked: false,
      appIcon: '',
      time: 'Now',
      timestamp: Date.now()
    });
    await clearThreats();
    
    const threats = await getThreats();
    expect(threats.length).toBe(0);
  });
});
