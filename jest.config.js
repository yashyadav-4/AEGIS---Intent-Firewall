module.exports = {
  preset: 'react-native',
  setupFilesAfterEnv: ['@testing-library/jest-native/extend-expect', '<rootDir>/jest.setup.js'],
  testPathIgnorePatterns: ['/node_modules/', '/AEGIS---Intent-Firewall/'],
  transformIgnorePatterns: [
    'node_modules/(?!(react-native|@react-native|@react-navigation|react-native-splash-screen|@react-native-async-storage)/)'
  ]
};
