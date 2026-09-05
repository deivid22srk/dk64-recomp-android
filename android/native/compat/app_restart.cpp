/*
 * app_restart.cpp — implementação do pedido de reinício do app pelo nativo.
 * Ver app_restart.h para o desenho completo e a motivação (o plume/RT64
 * só inicializa o Vulkan uma vez por processo; trocar o driver sem
 * reiniciar deixa a VkInstance ativa em mismatch com o ICD que o
 * adrenotools acabou de injetar via hooks — SIGSEGV no thread de jogo).
 * O MESMO mecanismo é usado após a seleção de uma ROM nova no launcher:
 * o ciclo do seletor (SAF) destrói/recria a surface Vulkan e deixa o
 * "Start Game" na mesma sessão em um estado frágil — reiniciar põe o
 * início do jogo num processo limpo (ver ui_launcher.cpp do patch do
 * RecompFrontend).
 */
#include "app_restart.h"

#if defined(__ANDROID__)

#include <android/log.h>
#include <jni.h>

#include <atomic>
#include <mutex>

namespace {
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, "DK64Recomp", __VA_ARGS__)
#define ALOGW(...) __android_log_print(ANDROID_LOG_WARN, "DK64Recomp", __VA_ARGS__)
std::atomic<bool> g_restart_requested{false};

// JavaVM cacheada — registrada pelo Java em MainActivity.nativeRestartInit
// (chamada a partir de onCreate, que já tem GetJavaVM válido). Necessária
// porque o NDK Android NÃO exporta JNI_GetCreatedJavaVMs para a linkedição
// padrão do libmain.so (precisaríamos de libnativehelper ou dlopen de
// libart.so — frágil entre versões do Android).
JavaVM *g_vm = nullptr;

/*
 * Cache de classe/método JNI para o despacho do reinício.
 *
 * BUG CORRIGIDO (2ª metade do "restart nunca disparava"): o despacho rodava
 * FindClass("...MainActivity") na thread de reinício — uma thread NATIVA
 * anexada via AttachCurrentThread, SEM frames Java. FindClass nesse contexto
 * resolve pelo classloader do SISTEMA, que não conhece classes do app (o
 * mesmo achado de file_bridge.cpp/ensure_jni_cache, commit cc41f96), então
 * mesmo com a thread anexada o despacho falharia em "MainActivity não
 * encontrada". O cache é populado em nativeRestartInit — chamada JNI
 * originada no MainActivity.onCreate (UI thread, frame chamador do app):
 * o jclass recebido é a própria MainActivity, e a referência GLOBAL +
 * methodID são válidos em qualquer thread daí em diante.
 */
std::mutex g_cacheMutex;
jclass g_main_class = nullptr;      // global ref de MainActivity
jmethodID g_mid_restart = nullptr;  // MainActivity.handleNativeAppRestart()V

// Lógica compartilhada: chama MainActivity.handleNativeAppRestart() usando o
// cache JNI. Idempotente via g_restart_requested. Retorna true se o Java foi
// notificado.
bool dispatch_restart_to_java(JNIEnv *env) {
    if (env == nullptr) return false;
    bool expected = false;
    if (!g_restart_requested.compare_exchange_strong(expected, true,
                                                     std::memory_order_acq_rel)) {
        return false;
    }
    ALOGI("app_restart: reinício solicitado pelo nativo (estado que exige "
          "processo novo — troca de driver ou seleção de ROM)");

    jclass cls = nullptr;
    jmethodID mid = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_cacheMutex);
        cls = g_main_class;
        mid = g_mid_restart;
    }

    if (cls == nullptr || mid == nullptr) {
        ALOGW("app_restart: cache JNI vazio (nativeRestartInit não rodou?) — "
              "reabra o app manualmente para usar o novo driver");
        return false;
    }

    env->CallStaticVoidMethod(cls, mid);
    if (env->ExceptionCheck()) {
        ALOGW("app_restart: exceção Java em handleNativeAppRestart");
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
    return true;
}

} // namespace

namespace dk64 {
void set_java_vm(void *vm) {
    g_vm = static_cast<JavaVM *>(vm);
}

void cache_restart_entrypoints(void *env, void *clazz) {
    /*
     * Popula o cache JNI do reinício (chamado por nativeRestartInit, que roda
     * com frame Java do app — o jclass recebido É a MainActivity). Referência
     * global sobrevive à morte do local ref e é válida em qualquer thread.
     */
    JNIEnv *jni = static_cast<JNIEnv *>(env);
    jclass local = static_cast<jclass>(clazz);
    if (jni == nullptr || local == nullptr) return;

    jmethodID mid = jni->GetStaticMethodID(local, "handleNativeAppRestart", "()V");
    if (mid == nullptr) {
        if (jni->ExceptionCheck()) jni->ExceptionClear();
        ALOGW("app_restart: MainActivity.handleNativeAppRestart()V não encontrado "
              "para o cache");
        return;
    }

    jclass global = static_cast<jclass>(jni->NewGlobalRef(local));
    if (global == nullptr) {
        if (jni->ExceptionCheck()) jni->ExceptionClear();
        ALOGW("app_restart: NewGlobalRef(MainActivity) falhou");
        return;
    }

    std::lock_guard<std::mutex> lock(g_cacheMutex);
    g_main_class = global;
    g_mid_restart = mid;
}

void native_request_app_restart() {
    /*
     * Wrapper C++ consumido pelo menu "GPU Driver" em src/main/main.cpp
     * (request_app_restart_after_delay). Usa a JavaVM cacheada em g_vm
     * (registrada via set_java_vm chamada pelo Java em nativeBridgeInit).
     *
     * BUG CORRIGIDO (driver "perdido" ao fechar/reabrir): o chamador real é a
     * std::thread destacada por request_app_restart_after_delay — uma thread
     * NATIVA que NUNCA foi anexada à JVM. Para ela, GetEnv devolve
     * JNI_EDETACHED e a versão anterior abandonava o pedido com apenas um
     * ALOGW ("thread chamadora não está anexada à JVM") — o reinício NUNCA
     * acontecia: o app continuava rodando com o Vulkan já inicializado pelo
     * driver anterior e o usuário tinha que fechar/reabrir manualmente (o
     * "driver não é carregado" do relato). A thread de render até estaria
     * anexada (a SDL anexa quem cria), mas quem dispara aqui é a std::thread.
     * Correção: anexar a thread à JVM (AttachCurrentThread, mesmo padrão do
     * file_bridge.cpp/call_java_request), despachar e desanexar ao fim.
     */
    if (g_vm == nullptr) {
        ALOGW("app_restart: JavaVM não registrada (nativeBridgeInit não rodou?) — "
              "reinicie o app manualmente para usar o driver novo");
        return;
    }

    JNIEnv *env = nullptr;
    bool attachedHere = false;
    if (g_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_4) != JNI_OK) {
        JavaVMAttachArgs args{};
        args.version = JNI_VERSION_1_4;
        args.name = "dk64-app-restart";
        args.group = nullptr;
        if (g_vm->AttachCurrentThread(&env, &args) != JNI_OK || env == nullptr) {
            ALOGW("app_restart: AttachCurrentThread falhou — "
                  "reinicie o app manualmente para usar o driver novo");
            return;
        }
        attachedHere = true;
    }

    const bool dispatched = dispatch_restart_to_java(env);

    if (attachedHere) {
        g_vm->DetachCurrentThread();
    }

    if (!dispatched) {
        ALOGW("app_restart: reinício não despachado — "
              "reabra o app manualmente para usar o driver novo");
    }
}
} // namespace dk64

extern "C" {

/*
 * Inicialização do módulo de reinício: registra a JavaVM E a entrada Java
 * (classe + método) para uso posterior em dk64::native_request_app_restart().
 * Chamado pelo Java em MainActivity.onCreate (mesmo padrão do nativeBridgeInit
 * do file_bridge).
 *
 * Necessário porque o NDK Android NÃO exporta JNI_GetCreatedJavaVMs para
 * a linkedição padrão do libmain.so (precisariamos de libnativehelper
 * ou dlopen de libart.so — frágil entre versões). Cachear via este
 * export resolve o problema de forma robusta.
 *
 * O cache da classe/método também é essencial: o despacho do reinício roda em
 * thread NATIVA sem frames Java, onde FindClass NÃO resolve classes do app
 * (classloader do sistema — achado do commit cc41f96). Aqui o frame chamador
 * é MainActivity.onCreate — o jclass recebido é a classe certa para a
 * referência global.
 */
JNIEXPORT void JNICALL
Java_com_deivid22srk_dk64recomp_MainActivity_nativeRestartInit(JNIEnv *env, jclass clazz) {
    JavaVM *vm = nullptr;
    if (env != nullptr && env->GetJavaVM(&vm) == JNI_OK && vm != nullptr) {
        dk64::set_java_vm(vm);
        dk64::cache_restart_entrypoints(env, clazz);
        __android_log_print(ANDROID_LOG_INFO, "DK64Recomp",
                            "app_restart: JavaVM + entrada de reinício registradas");
    }
}

} // extern "C"

#endif // __ANDROID__
