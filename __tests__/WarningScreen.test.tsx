// __tests__/WarningScreen.test.tsx
import React from 'react';
import { render } from '@testing-library/react-native';
import WarningScreen from '../src/screens/WarningScreen';

jest.mock('@react-navigation/native', () => ({
  useNavigation: () => ({ goBack: jest.fn() }),
  useRoute: () => ({ params: {} }),
}));


test('WarningScreen displays threat info', () => {
  const mockProps = {
    category: 'OTP',
    confidence: 85,
    message: 'Your OTP is 123456',
    onBlock: jest.fn(),
    onDismiss: jest.fn(),
    onCallHelp: jest.fn(),
  };

  const { getByText, getAllByText } = render(<WarningScreen {...mockProps} />);
  
  expect(getByText('SCAM DETECTED')).toBeTruthy();
  expect(getAllByText(/85/).length).toBeGreaterThan(0); // confidence
});
