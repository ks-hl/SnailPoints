import React, { useState, useContext } from 'react';
import styles from './Card.module.scss';
import { SettingsContext } from '../settings/SettingsContext'; // Assuming this is your context

interface CardProps {
  id: number;
  name: string;
  points: number;
  onPointsChange: (id: number, points: number) => void;
  onNameChange: (id: number, newName: string) => void;
  deleteMode: boolean;
  deletePoint: (id: number) => void;
  move: (id: number, up: boolean) => void;
  first: boolean;
  last: boolean;
  levelUpSound: HTMLAudioElement;
  levelDownSound: HTMLAudioElement;
  redeemSound: HTMLAudioElement;
}

export default function Card(props: CardProps) {
  const { settings } = useContext(SettingsContext);
  const allowNegative = settings.ALLOW_NEGATIVE?.value;
  const redeemCost = settings.REDEEM_COST?.value || 20; // Default to 20 if not set

  const [isEditingName, setIsEditingName] = useState(false);
  const [newName, setNewName] = useState(props.name);

  const [isEditingPoints, setIsEditingPoints] = useState(false);
  const [newPoints, setNewPoints] = useState(String(props.points));

  const handleIncrease = () => {
    props.onPointsChange(props.id, props.points + 1);
    setNewPoints(String(props.points + 1));
    let from, len;
    if ((props.points + 1) % 10 == 0) {
      from = 0.7;
      len = 4000;
    } else {
      from = 0.05;
      len = 900;
    }
    props.levelUpSound.pause();
    props.levelUpSound.currentTime = from;
    props.levelUpSound.volume = 0.7;
    props.levelUpSound.play();
    setTimeout(() => {
      props.levelUpSound.pause();
    }, len);
  };

  const handleDecrease = () => {
    if (props.points > 0 || allowNegative) {
      props.onPointsChange(props.id, props.points - 1);
      setNewPoints(String(props.points - 1));
      props.levelDownSound.pause();
      props.levelDownSound.currentTime = 0.15;
      props.levelDownSound.volume = 1.0;
      props.levelDownSound.play();
    }
  };

  const handleRedeem = () => {
    if (props.points >= redeemCost) {
      props.redeemSound.pause();
      props.redeemSound.currentTime = 0;
      props.redeemSound.volume = 1.0;
      props.redeemSound.play();
      setTimeout(() => {
        props.redeemSound.pause();
      }, 1200);
      props.onPointsChange(props.id, props.points - redeemCost);
    }
  };

  const handleNameSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (newName.length > 0 && newName !== props.name) props.onNameChange(props.id, newName);
    setIsEditingName(false);
  };

  const handlePointsSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    const pointsNumber = Number(newPoints);
    if (!Number.isNaN(pointsNumber) && pointsNumber !== props.points) {
      props.onPointsChange(props.id, pointsNumber);
    }
    setIsEditingPoints(false);
  };

  const handleDelete = () => {
    props.deletePoint(props.id);
  };

  return (
    <div className={styles.card}>
      <button onClick={() => props.move(props.id, true)} className={styles.move} disabled={props.first}>{`<`}</button>

      <div>
        {isEditingName || !props.name || props.name.length == 0 ? (
          <form onSubmit={handleNameSubmit}>
            <input
              type="text"
              value={newName}
              onChange={e => setNewName(e.target.value)}
              onBlur={handleNameSubmit}
              autoFocus
            />
          </form>
        ) : (
          <h3 onClick={() => setIsEditingName(true)}>{props.name}</h3>
        )}
        {isEditingPoints ? (
          <form onSubmit={handlePointsSubmit}>
            <input
              type="text"
              value={newPoints}
              onChange={e => setNewPoints(e.target.value)}
              onBlur={handlePointsSubmit}
              autoFocus
            />
          </form>
        ) : (
          <p className={styles.points} onClick={() => setIsEditingPoints(true)}>{props.points}</p>
        )}
        {props.deleteMode ?
          <div className={styles.buttonGroup}>
            <button onClick={handleDelete} className={styles.down}>DELETE FOREVER</button>
          </div> :
          <>
            <div className={styles.buttonGroup}>
              <button onClick={handleIncrease} className={styles.up}>👍</button>
              <button onClick={handleDecrease} className={styles.down} disabled={props.points <= 0 && !allowNegative}>👎</button>
            </div>
            <div className={styles.buttonGroup}>
              <button onClick={handleRedeem} className={styles.redeem} disabled={props.points < redeemCost}>Redeem! (-{redeemCost})</button>
            </div></>
        }
      </div>

      <button onClick={() => props.move(props.id, false)} className={styles.move} disabled={props.last}>{`>`}</button>
    </div>
  );
}
