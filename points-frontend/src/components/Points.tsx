import React, { useEffect, useState } from 'react';
import Card from './Card';
import styles from './Points.module.scss';

interface Point {
  name: string;
  id: number;
  points: number;
}

interface Props {
  endpoint: string;
}

export default function Points(props: Props) {

  const levelUpSound = new Audio(process.env.PUBLIC_URL + '/sounds/xp.mp3');
  const levelDownSound = new Audio(process.env.PUBLIC_URL + '/sounds/no.mp3');
  const redeemSound = new Audio(process.env.PUBLIC_URL + '/sounds/enchant.mp3');

  const [points, setPoints] = useState<Point[]>([]);
  const [deleteMode, setDeleteMode] = useState<boolean>();

  const fetchPoints = async () => {
    try {
      const response = await fetch(`${props.endpoint}/points/list`, {
        method: 'GET',
      });
      const data = await response.json();
      setPoints(data.points);
    } catch (error) {
      console.error('Error fetching points:', error);
    }
  };

  useEffect(() => {
    fetchPoints();
  }, []);

  const handlePointsChange = async (id: number, newPoints: number) => {
    try {
      const response = await fetch(`${props.endpoint}/points/set/points?id=${id}&points=${newPoints}`, {
        method: 'POST',
      });
      if (response.ok) {
        setPoints((prevPoints) =>
          prevPoints.map((point) =>
            point.id === id ? { ...point, points: newPoints } : point
          )
        );
      }
    } catch (error) {
      console.error('Error updating points:', error);
    }
  };

  const handleNameChange = async (id: number, newName: string) => {
    try {
      const response = await fetch(`${props.endpoint}/points/set/name?id=${id}&name=${encodeURIComponent(newName)}`, {
        method: 'POST',
      });
      if (response.ok) {
        setPoints((prevPoints) =>
          prevPoints.map((point) =>
            point.id === id ? { ...point, name: newName } : point
          )
        );
      }
    } catch (error) {
      console.error('Error updating name:', error);
    }
  };

  const handleAddPoint = async () => {
    try {
      const response = await fetch(`${props.endpoint}/points/new`, {
        method: 'POST',
      });
      const data = await response.json();
      setPoints([...points, data]);
    } catch (error) {
      console.error('Error adding new point:', error);
    }
  };

  const deletePoint = async (id: number) => {
    try {
      const response = await fetch(`${props.endpoint}/points/delete?id=${id}`, {
        method: 'POST',
      });
      fetchPoints();
    } catch (error) {
      console.error('Error deleting point:', error);
    }
  };

  const move = async (id: number, up: boolean) => {
    try {
      const response = await fetch(`${props.endpoint}/points/set/priority?id=${id}&up=${up}`, {
        method: 'POST',
      });
      fetchPoints();
    } catch (error) {
      console.error('Error setting priority:', error);
    }
  };

  return (
    <div className={styles.pointsListContainer}>
      <div className={styles.buttonRow}>
        {!deleteMode &&
          <div className={styles.newPointButton}>
            <button onClick={handleAddPoint}>+</button>
          </div>
        }
        {deleteMode ?
          <div className={styles.cancelDelete}>
            <button onClick={() => setDeleteMode(!deleteMode)}>Cancel</button>
          </div> :
          <div className={styles.deleteButton}>
            <button onClick={() => setDeleteMode(!deleteMode)}>{'-'}</button>
          </div>
        }
      </div>
      <div className={styles.pointsGrid}>
        {points && points.map((point, index) => (
          <Card
            key={point.id}
            id={point.id}
            name={point.name}
            points={point.points}
            onPointsChange={handlePointsChange}
            onNameChange={handleNameChange}
            deleteMode={deleteMode || false}
            deletePoint={deletePoint}
            move={move}
            first={index == 0}
            last={index == points.length - 1}
            levelUpSound={levelUpSound}
            levelDownSound={levelDownSound}
            redeemSound={redeemSound}
          />
        ))}
      </div>
    </div>
  );
};