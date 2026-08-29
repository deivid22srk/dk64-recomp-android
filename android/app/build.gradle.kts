plugins {
    id("com.android.application")
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
                abiFilters += listOf("arm64-v8a")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        debug {
            isJniDebuggable = true
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            // v1 entrega APK debug; release assinado com debug p/ ser instalável
            signingConfig = signingConfigs.getByName("debug")
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
            // assets do jogo (UI/fonts) empacotados no APK
            assets.srcDirs(files("../../assets"))
        }
    }
}

dependencies {
    // Sem dependências externas: SDLActivity vendored, sem AndroidX
}
