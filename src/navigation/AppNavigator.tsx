import React from 'react';
import {NavigationContainer} from '@react-navigation/native';
import {createStackNavigator, StackScreenProps} from '@react-navigation/stack';
import HomeScreen from '../screens/HomeScreen';
import WarningScreen from '../screens/WarningScreen';
import HistoryScreen from '../screens/HistoryScreen';
import SettingsScreen from '../screens/SettingsScreen';
import {RootStackParamList} from './types';
import {updateThreatBlocked} from '../utils/storage';

const Stack = createStackNavigator<RootStackParamList>();

type WarningScreenProps = StackScreenProps<RootStackParamList, 'Warning'>;

const WarningScreenWrapper = ({route, navigation}: WarningScreenProps) => {
  const {
    category = 'Suspicious Message',
    confidence = 85,
    threatId,
  } = route.params;

  return (
    <WarningScreen
      category={category}
      confidence={confidence}
      onBlock={() => {
        if (!threatId) {
          navigation.goBack();
          return;
        }

        updateThreatBlocked(threatId, true).finally(() => navigation.goBack());
      }}
      onDismiss={() => navigation.goBack()}
      onCallHelp={() => navigation.goBack()}
    />
  );
};

const AppNavigator = () => {
  return (
    <NavigationContainer>
      <Stack.Navigator
        screenOptions={{
          headerShown: false,
          cardStyle: {backgroundColor: '#0D0D0D'},
        }}>
        <Stack.Screen name="Home" component={HomeScreen} />
        <Stack.Screen name="Warning" component={WarningScreenWrapper} />
        <Stack.Screen name="History" component={HistoryScreen} />
        <Stack.Screen name="Settings" component={SettingsScreen} />
      </Stack.Navigator>
    </NavigationContainer>
  );
};

export default AppNavigator;