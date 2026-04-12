import { NativeModules, Platform } from 'react-native';

const { NotificationService } = NativeModules;

export interface ScamAssistBridgeConfig {
	enabled: boolean;
	endpoint: string;
	authToken?: string;
}

const DEFAULT_CONFIG: ScamAssistBridgeConfig = {
	enabled: false,
	endpoint: '',
};

export async function getScamAssistBridgeConfig(): Promise<ScamAssistBridgeConfig> {
	if (Platform.OS !== 'android' || !NotificationService?.getScamAssistBridgeConfig) {
		return DEFAULT_CONFIG;
	}

	try {
		const raw = await NotificationService.getScamAssistBridgeConfig();
		return {
			enabled: raw?.enabled === true,
			endpoint: typeof raw?.endpoint === 'string' ? raw.endpoint : '',
			authToken: typeof raw?.authToken === 'string' && raw.authToken.length > 0 ? raw.authToken : undefined,
		};
	} catch {
		return DEFAULT_CONFIG;
	}
}

export async function updateScamAssistBridgeConfig(config: ScamAssistBridgeConfig): Promise<boolean> {
	if (Platform.OS !== 'android' || !NotificationService?.updateScamAssistBridgeConfig) {
		return false;
	}

	try {
		await NotificationService.updateScamAssistBridgeConfig({
			enabled: config.enabled,
			endpoint: config.endpoint,
			authToken: config.authToken ?? '',
		});
		return true;
	} catch {
		return false;
	}
}
