import React, { useState } from 'react';
import { useSettings } from './SettingsContext';
import styles from './SettingsList.module.scss'

function SettingsList() {
    const { settings, updateSetting, loading } = useSettings();
    const [localValues, setLocalValues] = useState<{ [key: string]: any }>({});
    const [errors, setErrors] = useState<{ [key: string]: string | null }>({});

    if (loading) {
        return <div>Loading...</div>;
    }

    const handleInputChange = async (key: string, value: any) => {
        console.log(`${value} => ${key}`)
        const previousValue = settings[key].value; // Store the previous value for rollback

        // Optimistically update the local value
        setLocalValues(prev => ({
            ...prev,
            [key]: value,
        }));

        try {
            // Attempt to update the setting on the backend
            await updateSetting(key, value);

            // Clear any errors if the update was successful
            setErrors(prev => ({
                ...prev,
                [key]: null,
            }));
        } catch (error) {
            console.error('Failed to update setting:', error);

            // Display an error message
            setErrors(prev => ({
                ...prev,
                [key]: 'Failed to update setting. Reverted to previous value.',
            }));
        }
        setLocalValues({});
    };

    const handleSpinnerChange = (key: string, e: React.ChangeEvent<HTMLInputElement>) => {
        if (!e.target.value.match('\\d*')) {
            e.preventDefault();
            setLocalValues(prev => ({
                ...prev,
                [key]: e.target.value.replaceAll("[^\\d]*", ""),
            }));
            return;
        }
        handleInputChange(key, e.target.value.length == 0 ? 0 : parseInt(e.target.value, 10));
    }

    const renderInput = (key: string, value: any) => {
        const inputValue = localValues[key] !== undefined ? localValues[key] : value;

        if (typeof value === 'boolean') {
            return (
                <input
                    type="checkbox"
                    className={styles.check}
                    checked={inputValue}
                    onChange={e => handleInputChange(key, e.target.checked)}
                />
            );
        }

        if (typeof value === 'number') {
            return (
                <input
                    type="text"
                    className={styles.spinner}
                    value={inputValue}
                    onChange={e => handleSpinnerChange(key, e)}
                />
            );
        }

        return (
            <input
                type="text"
                className={styles.text}
                value={inputValue}
                onChange={e => handleInputChange(key, e.target.value)}
            />
        );
    };

    return (
        <>
            {Object.keys(settings).map(key => <>
                <div key={key} className={styles.setting}>
                    <label>{settings[key].formatted}:</label>
                    {renderInput(key, settings[key].value)}
                </div>
                {errors[key] && <p className={styles.error}>{errors[key]}</p>}
            </>)}
        </>
    );
}

export default SettingsList;
