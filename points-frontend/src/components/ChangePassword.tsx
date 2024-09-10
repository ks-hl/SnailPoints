import React, { useState, useEffect } from 'react';
import styles from './Login.module.scss';

interface Props {
  title: string;
  hasUsername: boolean;
  hasCurrentPassword: boolean;
  hasConfirmPassword: boolean;
  endpoint: string;
}

export default function Login(props: Props) {
  const [username, setUsername] = useState<string>('');
  const [currentPassword, setCurrentPassword] = useState<string>('');
  const [password, setPassword] = useState<string>('');
  const [password2, setPassword2] = useState<string>('');
  const [error, setError] = useState<string | undefined>();
  const [success, setSuccess] = useState<string | undefined>();
  const [passwordError, setPasswordError] = useState<string | undefined>();
  const [isSubmitDisabled, setIsSubmitDisabled] = useState(true);

  useEffect(() => {
    if (props.hasUsername && username.length == 0) {
      setPasswordError(undefined);
      setIsSubmitDisabled(true);
      return;
    }
    if (props.hasCurrentPassword && currentPassword.length == 0) {
      setPasswordError(undefined);
      setIsSubmitDisabled(true);
      return;
    }
    const isPasswordEmpty = password.length == 0;

    if (props.hasConfirmPassword) {
      const isPassword2Empty = password2.length == 0;
      if (isPasswordEmpty || isPassword2Empty) {
        setPasswordError(undefined);
        setIsSubmitDisabled(true);
      } else if (password !== password2) {
        setPasswordError("Passwords do not match");
        setIsSubmitDisabled(true);
      } else {
        setPasswordError(undefined);
        setIsSubmitDisabled(false);
      }
    } else {
      setIsSubmitDisabled(isPasswordEmpty);
    }
  }, [currentPassword, password, password2, username]);

  const handleSubmit = async (event: React.FormEvent) => {
    event.preventDefault();
    setError(undefined); // Reset error state
    try {
      const response = await fetch(props.endpoint, {
        method: 'POST',
        body: JSON.stringify({
          "user": `${props.hasUsername ? username : undefined}`,
          "current": `${props.hasCurrentPassword ? currentPassword : undefined}`,
          "new": `${password}`,
          "code": `${new URLSearchParams(window.location.search).get('code')}`
        })
      });
      const data = await response.json();
      if (data.error) {
        setError(data.error);
      } else if (data.success !== undefined) {
        setSuccess("Success!");
        setTimeout(() => {
          window.location.assign('/');
        }, 1000);
      }
    } catch (error) {
      setError('Submit failed');
      console.error('Submit failed', error);
    }
  };

  return (
    <div className={styles.loginContainer}>
      <form onSubmit={handleSubmit} className={styles.loginForm}>
        <h2>{props.title}</h2>
        {props.hasUsername && <div className={styles.formGroup}>
          <label htmlFor="username">Username</label>
          <input
            type="text"
            id="username"
            value={username}
            onChange={(e) => setUsername(e.target.value)}
          />
        </div>}
        {props.hasCurrentPassword && <div className={styles.formGroup}>
          <label htmlFor="password">Current Password</label>
          <input
            type="password"
            id="password"
            value={currentPassword}
            onChange={(e) => setCurrentPassword(e.target.value)}
          />
        </div>}
        <div className={styles.formGroup}>
          <label htmlFor="password">New Password</label>
          <input
            type="password"
            id="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
        </div>
        {props.hasConfirmPassword && <div className={styles.formGroup}>
          <label htmlFor="password2">Confirm Password</label>
          <input
            type="password"
            id="password2"
            value={password2}
            onChange={(e) => setPassword2(e.target.value)}
          />
        </div>}
        {passwordError && <div className={styles.error}>{passwordError}</div>}
        <button type="submit" disabled={isSubmitDisabled}>Submit</button>
        {error && <div className={styles.error}>{error}</div>}
        {success && <div className={styles.success}>{success}</div>}
      </form>
    </div>
  );
}
