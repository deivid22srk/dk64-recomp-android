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
void native_request_app_restart() {
    /*
     * Wrapper C++ consumido pelo menu "GPU Driver" em src/main/main.cpp
     * (request_app_restart_after_delay). A thread de render do RT64 JÁ está
     * anexada à JVM (a SDL anexa a main thread automaticamente). Se não
     * estiver — caso improvável numa thread std::thread — logamos e o
     * reinício não acontece; a seleção já está persistida em selected.txt.
     */
    JavaVM *vm = nullptr;
    jsize n = 0;
    if (JNI_GetCreatedJavaVMs(&vm, 1, &n) != JNI_OK || vm == nullptr) {
        ALOGW("app_restart: nenhuma JavaVM disponível — reinício ignorado; "
              "reabra o app manualmente");
        return;
    }

    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_4) != JNI_OK) {
        ALOGW("app_restart: thread chamadora não está anexada à JVM — "
              "reinicie o app manualmente para usar o driver novo");
        return;
    }

    dispatch_restart_to_java(env);
}
} // namespace dk64

#endif // __ANDROID__
