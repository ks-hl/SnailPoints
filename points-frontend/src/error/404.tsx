import styles from './Error.module.scss'

export default function NotFound() {
return <div className={styles.content}>
    <img src="/images/error.png"/>
    <h1>404</h1>
    <h2>Not Found</h2>
</div>
}