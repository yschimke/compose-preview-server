package androidx.compose.ui.tooling.preview

/** Minimal discovery fixture; production resolves the real annotation from the catalog. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Preview(val widthDp: Int = -1, val heightDp: Int = -1)
