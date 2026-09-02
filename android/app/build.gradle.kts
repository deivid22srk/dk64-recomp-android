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
        versionCode = 1
        versionName = "1.0.1-android"

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
        // Assinatura de release usada no CI (release.yml): a keystore vem dos
        // secrets do GitHub (ANDROID_RELEASE_KEYSTORE_B64 + senhas/alias) e é
        // decodificada para um arquivo temporário. Em builds locais sem as env
        // vars o config fica sem storeFile e o buildType release cai no
        // fallback da debug keystore (APK continua instalável p/ testes).
        create("ciRelease") {
            val storeB64 = System.getenv("ANDROID_RELEASE_KEYSTORE_B64")
            val storePass = System.getenv("ANDROID_RELEASE_KEYSTORE_PASSWORD")
            val alias = System.getenv("ANDROID_RELEASE_KEY_ALIAS")
            val keyPass = System.getenv("ANDROID_RELEASE_KEY_PASSWORD")
            if (!storeB64.isNullOrBlank() && !storePass.isNullOrBlank() &&
                !alias.isNullOrBlank() && !keyPass.isNullOrBlank()
            ) {
                val tmp = File.createTempFile("release-keystore", ".jks")
                tmp.writeBytes(java.util.Base64.getDecoder().decode(storeB64))
                tmp.deleteOnExit()
                storeFile = tmp
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
