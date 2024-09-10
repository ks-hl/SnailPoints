import { useEffect, useState } from 'react';
import ReactDOM from "react-dom/client";
import { BrowserRouter, Routes, Route } from "react-router-dom";
import './App.css';
import Login from './components/Login';
import ChangePassword from './components/ChangePassword';
import Points from './components/Points';
import Header from './components/Header';
import NotFound from './error/404';
import Unavailable from './error/Unavailable';
import { SettingsProvider } from './settings/SettingsContext';

const endpoint = "/api";

function App() {
  const [validSession, setValidSession] = useState<boolean | undefined>();
  const [verifyAccount, setVerifyAccount] = useState<boolean | undefined>();
  const [admin, setAdmin] = useState<boolean | undefined>();
  const [unavailable, setUnavailable] = useState<boolean>(false);
  useEffect(() => {
    fetch(`${endpoint}/`)
      .then(response => {
        if (response.status === 500) {
          setUnavailable(true);
          return null; // Stop further execution
        }
        if (!response.ok) {
          throw new Error('Network response was not ok');
        }
        return response.json(); // Proceed with JSON parsing only for successful responses
      })
      .then(data => {
        if (!data) return;
        if (data.error === "Please activate your account. Check your email.") {
          setVerifyAccount(true);
        } else {
          setValidSession(data.success !== undefined);
          setAdmin(data.admin === true);
        }
      })
      .catch(error => {
        console.error("Error:", error);
      });
  }, [validSession]);

  let header = <Header
    authenticated={validSession === true}
    admin={admin || false}
    endpoint={endpoint}
  />;

  const getContent = () => {
    if (unavailable) {
      return <Unavailable />
    }
    if (validSession === undefined) {
      return <h2>Loading...</h2>
    }
    if (verifyAccount) {
      return <>
        <h2>You have to verify your account. Check your email.</h2>
        {/* RESEND CODE BUTTON, move to component */}
      </>
    }
    return <Routes>
      <Route path="/" element={
        validSession === true ?
          <Points endpoint={endpoint} /> :
          <Login endpoint={endpoint} setValidSession={setValidSession} />
      } />
      <Route path="/reset" element={
        <ChangePassword
          title='Reset Password'
          hasUsername={false}
          hasCurrentPassword={false}
          hasConfirmPassword={true}
          endpoint={'/api/resetpassword'} />
      } />
      <Route path="/changepassword" element={
        <ChangePassword
          title='Change Password'
          hasUsername={false}
          hasCurrentPassword={true}
          hasConfirmPassword={true}
          endpoint={'/api/changepassword'} />
      } />
      <Route path="/setpassword" element={
        <ChangePassword
          title='Set User Password'
          hasUsername={true}
          hasCurrentPassword={false}
          hasConfirmPassword={false}
          endpoint={'/api/setpassword'} />
      } />
      <Route path="/createdemouser" element={
        <ChangePassword
          title='Create Demo User'
          hasUsername={true}
          hasCurrentPassword={false}
          hasConfirmPassword={false}
          endpoint={'/api/makedemo'} />
      } />
      <Route path="*" element={<NotFound />} />
    </Routes>
  };

  const router = <BrowserRouter>
    {header}
    <div className="content">
      {getContent()}
    </div>
  </BrowserRouter>

  if (validSession === true) {
    return <SettingsProvider>
      {router}
    </SettingsProvider>
  }

  return router
}

export default App;
