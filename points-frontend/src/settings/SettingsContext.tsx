import React, { createContext, useContext, useState, useEffect, ReactNode } from 'react';

// Define the shape of the context
interface SettingValue {
    value: any;
    formatted: string;
}

interface SettingsContextType {
    settings: { [key: string]: SettingValue };
    updateSetting: (key: string, value: any) => Promise<void>;
    loading: boolean;
}

const defaultSettings: SettingsContextType = {
    settings: {},
    updateSetting: async () => {}, // No-op function
    loading: true,
};

// Create the context with a default value
export const SettingsContext = createContext<SettingsContextType>(defaultSettings);

// Define the type for the provider props
interface SettingsProviderProps {
    children: ReactNode;
}

// Create a provider component
export function SettingsProvider({ children }: SettingsProviderProps) {
    const [settings, setSettings] = useState<{ [key: string]: SettingValue }>({});
    const [loading, setLoading] = useState(true);

    useEffect(() => {
        fetch('/api/')
            .then(response => response.json())
            .then(data => {
                if (data.success) {
                    setSettings(data.settings);
                }
                setLoading(false);
            });
    }, []);

    // Function to update a setting on the server
    const updateSetting = async (key: string, value: any) => {
        try {
            const response = await fetch('/api/settings/set', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                },
                body: JSON.stringify({ key, value }),
            });

            if (!response.ok) {
                // TODO error log
            }

            const updatedSetting = await response.json();

            setSettings(prevSettings => ({
                ...prevSettings,
                [key]: {
                    ...prevSettings[key],
                    value: updatedSetting.setting.value,
                },
            }));
        } catch (error) {
            console.error('Error updating setting:', error);
            // Optionally: Show error feedback to the user here
        }
    };

    return (
        <SettingsContext.Provider value={{ settings, updateSetting, loading }}>
            {children}
        </SettingsContext.Provider>
    );
}

// Custom hook to use settings
export function useSettings() {
    return useContext(SettingsContext);
}
