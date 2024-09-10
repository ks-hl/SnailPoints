import { useState, useEffect, useRef } from 'react';
import styles from './Header.module.scss';
import { useNavigate } from 'react-router-dom';
import SettingsList from '../settings/SettingsList';

interface Props {
    authenticated: boolean;
    admin: boolean;
    endpoint: string;
}

export default function Headers(props: Props) {
    const [headerOpen, setHeaderOpen] = useState<boolean | undefined>(false);
    const menuRef = useRef<HTMLDivElement>(null);
    const navigate = useNavigate();

    const handleClickOutside = (event: MouseEvent) => {
        if (menuRef.current && !menuRef.current.contains(event.target as Node)) {
            setHeaderOpen(false);
        }
    };

    const logout = () => {
        fetch(`${props.endpoint}/logout`, { method: 'POST' })
            .then(response => response.json())
            .then(json => {
                if (!json.error) {
                    window.location.assign('/'); // Not using navigate because we want a refresh
                }
            });
    };

    useEffect(() => {
        document.addEventListener('mouseup', handleClickOutside);
        return () => {
            document.removeEventListener('mouseup', handleClickOutside);
        };
    }, []);

    return (
        <div className={styles.header}>
            <div className={styles.headerContainer}>
                <div className={styles.title} onClick={() => {
                    navigate('/');
                }}>
                    <img src="/images/logo.png" />
                    <h1>Snail Points</h1>
                </div>
                {props.authenticated && <div className={styles.settings}>
                    <img
                        src="/images/hamburger.svg"
                        className={
                            headerOpen ? styles.hamburgerDisabled : styles.hamburger
                        }
                        onClick={() => {
                            setHeaderOpen(!headerOpen)
                        }}
                    />
                    {headerOpen && (
                        <div ref={menuRef} className={styles.menu}>
                            <SettingsList />
                            <div className={styles.button} onClick={() => navigate('/changepassword')}>Change Password</div>
                            {props.admin && <>
                                <div className={styles.adminButton} onClick={() => navigate('/setpassword')}>Set Password</div>
                                <div className={styles.adminButton} onClick={() => navigate('/createdemouser')}>Create Demo User</div>
                            </>}
                            <div className={styles.button} onClick={logout}>Logout</div>
                        </div>
                    )}
                </div>}
            </div>
        </div>
    );
}
