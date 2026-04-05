import React from 'react';
import {NavigationContainer} from '@react-navigation/native';
import {createStackNavigator} from '@react-navigation/stack';
import HomeScreen from '../screens/HomeScreen';
import WarningScreen from '../screens/WarningScreen';
import HistoryScreen from '../screens/HistoryScreen';
import SettingsScreen from '../screens/SettingsScreen';

const Stack = createStackNavigator();

const WarningScreenWrapper = ({route, navigation}: any) => {
  const {
    category = 'Suspicious Message',
    confidence = 85,
  } = route.params || {};

  return (
    <WarningScreen
      category={category}
      confidence={confidence}
      onBlock={() => navigation.goBack()}
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