export type RootStackParamList = {
  Home: undefined;
  Warning: {
    category: string;
    confidence: number;
    message: string;
    app: string;
    threatId?: string;
  };
  History: undefined;
  Settings: undefined;
  Debug: undefined;
};
