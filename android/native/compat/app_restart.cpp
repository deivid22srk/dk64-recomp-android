/*
 * app_restart.cpp — implementação do pedido de reinício do app pelo nativo.
 * Ver app_restart.h para o desenho completo e a motivação (o plume/RT64
 * só inicializa o Vulkan uma vez por processo; trocar o driver sem
 * reiniciar deixa a VkInstance ativa em mismatch com o ICD que o
 * adrenotools acabou de injetar via hooks — SIGSEGV no thread de jogo).
 */
#include "app_restart.h"

#if defined(__ANDROID__)

#include <android/log.h>
#include <jni.h>

#include <atomic>

namespace {
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, "DK64Recomp", __VA_ARGS__)
#define ALOGW(...) __android_log_print(ANDROID_LOG_WARN, "DK64Recomp", __VA_ARGS__)
std::atomic<bool> g_restart_requested{false};

// JavaVM cacheada — registrada pelo Java em MainActivity.nativeBridgeInit
// (chamada a partir de onCreate, que já tem GetJavaVM válido). Necessária
// porque o NDK Android NÃO exporta JNI_GetCreatedJavaVMs para a linkedição
// padrão do libmain.so (precisaríamos de libnativehelper ou dlopen de
// libart.so — frágil entre versões do Android).
JavaVM *g_vm = nullptr;

// Lógica compartilhada: acha MainActivity, chama handleNativeAppRestart().
// Idempotente via g_restart_requested. Retorna true se o Java foi notificado.
bool dispatch_restart_to_java(JNIEnv *env) {
    if (env == nullptr) return false;
    bool expected = false;
    if (!g_restart_requested.compare_exchange_strong(expected, true,
                                                     std::memory_order_acq_rel)) {
        return false;
    }
    ALOGI("app_restart: reinício solicitado pelo nativo (driver mudou; "
          "Vulkan foi inicializado com o driver anterior)");

    jclass cls = env->FindClass("com/deivid22srk/dk64recomp/MainActivity");
    if (cls == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        ALOGW("app_restart: MainActivity não encontrada — reinício ignorado; "
              "reabra o app manualmente para usar o novo driver");
        return false;
    }

    jmethodID mid = env->GetStaticMethodID(cls, "handleNativeAppRestart", "()V");
    if (mid == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        ALOGW("app_restart: MainActivity.handleNativeAppRestart()V ausente — "
              "reabra o app manualmente");
        env->DeleteLocalRef(cls);
        return false;
    }

    env->CallStaticVoidMethod(cls, mid);
    if (env->ExceptionCheck()) {
        ALOGW("app_restart: exceção Java em handleNativeAppRestart");
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
    env->DeleteLocalRef(cls);
    return true;
}

} // namespace

namespace dk64 {
void set_java_vm(void *vm) {
    g_vm = static_cast<JavaVM *>(vm);
}

void native_request_app_restart() {
    /*
     * Wrapper C++ consumido pelo menu "GPU Driver" em src/main/main.cpp
     * (request_app_restart_after_delay). Usa a JavaVM cacheada em g_vm
     * (registrada via set_java_vm chamada pelo Java em nativeBridgeInit).
     *
     * A thread de render do RT64 JÁ está anexada à JVM (a SDL anexa a
     * main thread automaticamente). Se não estiver — caso improvável
     * numa thread std::thread — logamos e o reinício não acontece; a
     * seleção já está persistida em selected.txt.
     */
    if (g_vm == nullptr) {
        ALOGW("app_restart: JavaVM não registrada (nativeBridgeInit não rodou?) — "
              "reinicie o app manualmente para usar o driver novo");
        return;
    }

    JNIEnv *env = nullptr;
    if (g_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_4) != JNI_OK) {
        ALOGW("app_restart: thread chamadora não está anexada à JVM — "
              "reinicie o app manualmente para usar o driver novo");
        return;
    }

    dispatch_restart_to_java(env);
}
} // namespace dk64

extern "C" {

/*
 * Inicialização do módulo de reinício: registra a JavaVM para uso posterior
 * em dk64::native_request_app_restart(). Chamado pelo Java em
 * MainActivity.onCreate (mesmo padrão do nativeBridgeInit do file_bridge).
 *
 * Necessário porque o NDK Android NÃO exporta JNI_GetCreatedJavaVMs para
 * a linkedição padrão do libmain.so (precisaríamos de libnativehelper
 * ou dlopen de libart.so — frágil entre versões). Cachear via este
 * export resolve o problema de forma robusta.
 */
JNIEXPORT void JNICALL
Java_com_deivid22srk_dk64recomp_MainActivity_nativeRestartInit(JNIEnv *env, jclass /*clazz*/) {
    JavaVM *vm = nullptr;
    if (env != nullptr && env->GetJavaVM(&vm) == JNI_OK && vm != nullptr) {
        dk64::set_java_vm(vm);
        __android_log_print(ANDROID_LOG_INFO, "DK64Recomp",
                            "app_restart: JavaVM registrada para reinício");
    }
}

} // extern "C"

#endif // __ANDROID__
