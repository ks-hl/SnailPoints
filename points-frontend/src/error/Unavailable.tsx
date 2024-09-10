import styles from './Error.module.scss'

export default function NotFound() {
return <div className={styles.content}>
    <img src="/images/error.png"/>
    <h2>Service Unavailable</h2>
</div>
}