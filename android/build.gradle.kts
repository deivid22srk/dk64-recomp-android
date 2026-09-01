plugins {
    id("com.android.application") version "8.5.2" apply false
    // Kotlin: VirtualPadView (gamepad virtual de toque) é escrito em Kotlin.
    // 1.9.24 é a versão homologada com AGP 8.5.2 (mesma do projeto N64Pad2).
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}
