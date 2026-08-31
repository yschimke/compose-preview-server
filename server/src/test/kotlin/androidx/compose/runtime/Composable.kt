package androidx.compose.runtime

/** Minimal compile-lane fixture; production resolves the real annotation from the catalog. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class Composable
