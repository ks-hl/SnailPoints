import React, { useState, useEffect } from 'react';
import styles from './Login.module.scss';

interface Props {
  endpoint: string;
  setValidSession: (validSession: boolean) => void;
}

export default function Login(props: Props) {
  const [username, setUsername] = useState<string>('');
  const [password, setPassword] = useState<string>('');
  const [password2, setPassword2] = useState<string>('');
  const [email, setEmail] = useState<string>('');
  const [isSignUp, setIsSignUp] = useState(false);
  const [error, setError] = useState<string | undefined>();
  const [passwordError, setPasswordError] = useState<string | undefined>();
  const [isSubmitDisabled, setIsSubmitDisabled] = useState(true);

  useEffect(() => {
    if (username.length == 0) {
      setPasswordError(undefined);
      setIsSubmitDisabled(true);
      return;
    }
    const isPasswordEmpty = password.length == 0;
    if (isSignUp) {
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
  }, [password, password2, username, email, isSignUp]);

  const handleLogin = async (event: React.FormEvent) => {
    event.preventDefault();
    setError(undefined); // Reset error state
    try {
      const response = await fetch(`${props.endpoint}/login`, {
        method: 'POST',
        headers: {
          "Content-Type": "application/json"
        },
        body: JSON.stringify({
          "username": `${username}`,
          "password": `${password}`
        })
      });
      const data = await response.json();
      if (data.error) {
        setError(data.error);
      } else if (data.success !== undefined) {
        props.setValidSession(true);
      }
    } catch (error) {
      setError('Login failed');
      console.error('Login failed', error);
    }
  };

  const handleSignUp = async (event: React.FormEvent) => {
    event.preventDefault();
    setError(undefined); // Reset error state
    try {
      const response = await fetch(`${props.endpoint}/createaccount`, {
        method: 'POST',
        headers: {
          "Content-Type": "application/json"
        },
        body: JSON.stringify({
          "email": `${email}`,
          "username": `${username}`,
          "password": `${password}`
        })
      });
      const data = await response.json();
      if (data.error) {
        setError(data.error);
      } else if (data.success !== undefined) {
        props.setValidSession(true);
      }
    } catch (error) {
      setError('Sign up failed');
      console.error('Sign up failed', error);
    }
  };

  const toggleSignUp = () => {
    setIsSignUp(!isSignUp);
    setEmail('');
    setError(undefined);
    setPasswordError(undefined);
  };

  return (
    <div className={styles.content}>
      <div className={styles.loginContainer}>
        <form onSubmit={isSignUp ? handleSignUp : handleLogin} className={styles.loginForm}>
          <div className={styles.formGroup}>
            <label htmlFor="username">Username</label>
            <input
              type="text"
              id="username"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
            />
          </div>
          <div className={styles.formGroup}>
            <label htmlFor="password">Password</label>
            <input
              type="password"
              id="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
            />
          </div>
          {isSignUp && <>
            <div className={styles.formGroup}>
              <label htmlFor="password2">Confirm Password</label>
              <input
                type="password"
                id="password2"
                value={password2}
                onChange={(e) => setPassword2(e.target.value)}
              />
            </div>
            <div className={styles.formGroup}>
              <label htmlFor="email">Email</label>
              <input
                type="email"
                id="email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
              />
            </div>
          </>}
          {passwordError && <div className={styles.error}>{passwordError}</div>}
          <button type="submit" disabled={isSubmitDisabled}>{isSignUp ? 'Sign Up' : 'Login'}</button>
          <button type="button" onClick={toggleSignUp}>
            {isSignUp ? 'Login Instead' : 'Sign Up Instead'}
          </button>
          {error && <div className={styles.error}>{error}</div>}
        </form>
      </div>
    </div>
  );
}
