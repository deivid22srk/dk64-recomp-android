plugins {
    id("com.android.application")
    // Kotlin para o overlay do gamepad virtual (VirtualPadView.kt).
    id("org.jetbrains.kotlin.android")
}

// Caminho opcional para o binário file_to_c pré-compilado no host (necessário no CI).
// Informe via: ./gradlew -PHOST_FILE_TO_C=/abs/path/file_to_c assembleDebug
val hostFileToC: String? = (project.findProperty("HOST_FILE_TO_C") as String?)?.takeIf { it.isNotBlank() }

android {
    namespace = "com.deivid22srk.dk64recomp"
    compileSdk = 34
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.deivid22srk.dk64recomp"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "1.0.3-android"

        ndk {
            // 64-bit apenas (decisão do usuário: memória e tempo de build)
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                val args = mutableListOf(
                    "-DANDROID_STL=c++_shared",
                    "-DCMAKE_BUILD_TYPE=Release"
                )
                if (hostFileToC != null) {
                    args += "-DHOST_FILE_TO_C=$hostFileToC"
                }
                arguments += args
                cppFlags += listOf("-std=c++20", "-fexceptions", "-frtti")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        // Assinatura de release usada no CI (release.yml): o workflow decodifica
        // a keystore (secret ANDROID_RELEASE_KEYSTORE_B64) para um arquivo e
        // informa o CAMINHO via env ANDROID_RELEASE_KEYSTORE_FILE (+ senhas e
        // alias). Em builds locais sem as env vars o config fica sem storeFile
        // e o buildType release cai no fallback da debug keystore (APK segue
        // instalável p/ testes).
        // Obs.: a decodificação fica no shell do CI de propósito — no Kotlin DSL
        // "java" em posição de expressão colide com o acessor da extensão `java`
        // do Project (java.util.Base64 não resolve aqui).
        create("ciRelease") {
            val storePath = System.getenv("ANDROID_RELEASE_KEYSTORE_FILE")
            val storePass = System.getenv("ANDROID_RELEASE_KEYSTORE_PASSWORD")
            val alias = System.getenv("ANDROID_RELEASE_KEY_ALIAS")
            val keyPass = System.getenv("ANDROID_RELEASE_KEY_PASSWORD")
            if (!storePath.isNullOrBlank() && !storePass.isNullOrBlank() &&
                !alias.isNullOrBlank() && !keyPass.isNullOrBlank()
            ) {
                storeFile = file(storePath)
                storePassword = storePass
                keyAlias = alias
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        debug {
            isJniDebuggable = true
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            // CI (release.yml): assina com a keystore de release dos secrets.
            // Local (sem env vars): fallback p/ debug keystore.
            signingConfig =
                if (signingConfigs.getByName("ciRelease").storeFile != null) {
                    signingConfigs.getByName("ciRelease")
                } else {
                    signingConfigs.getByName("debug")
                }
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") {
            // assets do jogo (UI/fonts) empacotados no APK, MESCLADOS com o
            // default src/main/assets (recompcontrollerdb.txt).
            // srcDir (singular) ADICIONA ao default; srcDirs (plural) substituiria
            // o src/main/assets e tiraria o recompcontrollerdb.txt do APK.
            // files() resolve relativo ao diretório do MÓDULO (android/app):
            // "../../assets" -> <repo>/assets (Cloud1.svg, icons/, promptfont/, ...).
            assets.srcDir(files("../../assets"))
        }
    }
}

dependencies {
    // Sem dependências externas: SDLActivity vendored, sem AndroidX
}
