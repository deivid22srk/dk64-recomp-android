/*
 * file_bridge.cpp — implementação da ponte SAF <-> menus do recomp.
 *
 * Ver file_bridge.h para o desenho completo (não-bloqueante, dispatch no
 * draw_hook). Este arquivo é vinculado DENTRO do libmain.so junto com o resto
 * do port; os símbolos androidport::filedialog são consumidos:
 *   - por recompui/src/util/file.cpp (patch do port) para open_file_dialog;
 *   - por RecompFrontend/recompui/src/base/ui_state.cpp (patch do port) para
 *     o dispatch por frame no draw_hook;
 *   - por src/main/main.cpp para o fluxo do driver Turnip no launcher.
 */
#include "file_bridge.h"

#if defined(__ANDROID__)

#include <android/log.h>
#include <jni.h>

#include <mutex>
#include <utility>

// Os exports JNI ficam DENTRO deste namespace para enxergar os statics do
// namespace anônimo; extern "C" preserva os nomes literais esperados pelo JNI.
namespace androidport::filedialog {

namespace {

#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, "DK64Recomp", __VA_ARGS__)
#define ALOGW(...) __android_log_print(ANDROID_LOG_WARN, "DK64Recomp", __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, "DK64Recomp", __VA_ARGS__)

constexpr const char *kMainActivity = "com/deivid22srk/dk64recomp/MainActivity";

std::mutex g_mutex;
JavaVM *g_vm = nullptr;

enum class State {
    Idle,    // nenhum pedido em aberto
    Waiting, // SAF picker aberto (ou resultado em processamento no Java)
    Result,  // resultado publicado pelo Java, aguardando dispatch no draw_hook
};

State g_state = State::Idle;
Kind g_kind = Kind::Rom;
bool g_ok = false;
std::string g_payload;
Callback g_callback;

/*
 * Chama MainActivity.requestFilePicker(kind) (static, boolean). Pode rodar na
 * thread de present do RT64 (não anexada à JVM): anexa na 1ª chamada e mantém
 * anexada — o processo é curto e o número de threads é pequeno.
 */
bool call_java_request(Kind kind) {
    JavaVM *vm = g_vm;
    if (vm == nullptr) {
        ALOGE("file bridge: JavaVM ausente (nativeBridgeInit não rodou?)");
        return false;
    }

    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_4) != JNI_OK) {
        if (vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            ALOGE("file bridge: AttachCurrentThread falhou");
            return false;
        }
    }

    jclass cls = env->FindClass(kMainActivity);
    if (cls == nullptr) {
        ALOGE("file bridge: FindClass(%s) falhou", kMainActivity);
        if (env->ExceptionCheck()) env->ExceptionClear();
        return false;
    }

    jmethodID mid = env->GetStaticMethodID(cls, "requestFilePicker", "(I)Z");
    if (mid == nullptr) {
        ALOGE("file bridge: requestFilePicker(I)Z não encontrado");
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteLocalRef(cls);
        return false;
    }

    jboolean res = env->CallStaticBooleanMethod(cls, mid, static_cast<jint>(kind));
    if (env->ExceptionCheck()) {
        ALOGE("file bridge: exceção Java em requestFilePicker");
        env->ExceptionDescribe();
        env->ExceptionClear();
        env->DeleteLocalRef(cls);
        return false;
    }

    env->DeleteLocalRef(cls);
    if (!res) ALOGE("file bridge: Java recusou o pedido (Activity indisponível?)");
    return res == JNI_TRUE;
}

} // namespace

void set_java_vm(void *vm) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_vm = static_cast<JavaVM *>(vm);
}

bool request(Kind kind, Callback callback) {
    // Slot único. Produtor único (thread de render no draw_hook): a checagem
    // e a publicação do slot não precisam ser atômicas entre si.
    Callback rejected;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (g_state != State::Idle) {
            // Ocupado: falha imediata fora da lock (o callback pode reentrar).
            rejected = std::move(callback);
        } else {
            g_state = State::Waiting;
            g_kind = kind;
            g_ok = false;
            g_payload.clear();
            g_callback = std::move(callback);
        }
    }

    if (rejected) {
        ALOGE("file bridge: pedido ignorado — outro já pendente");
        rejected(false, std::string{});
        return false;
    }

    ALOGI("file bridge: abrindo SAF picker (kind=%d)", static_cast<int>(kind));
    if (call_java_request(kind)) {
        return true;
    }

    // Falha ao postar o Intent: devolve o slot e notifica o chamador.
    Callback failed;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        g_state = State::Idle;
        failed = std::move(g_callback);
        g_callback = nullptr;
    }
    if (failed) failed(false, std::string{});
    return false;
}

void process_pending() {
    Callback cb;
    bool ok = false;
    std::string payload;

    // Troca o resultado para fora da seção crítica; o callback roda solto,
    // no mesmo contexto (thread + ui_state_mutex) de um clique de menu.
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (g_state != State::Result) return;
        cb = std::move(g_callback);
        ok = g_ok;
        payload = std::move(g_payload);
        g_callback = nullptr;
        g_payload.clear();
        g_state = State::Idle;
    }

    ALOGI("file bridge: despachando resultado (ok=%d, payload=%.120s)",
          ok ? 1 : 0, payload.c_str());
    if (cb) cb(ok, payload);
}

// ---------------------------------------------------------------------------
// Exports JNI (chamados por MainActivity) — extern "C" dentro do namespace
// preserva os nomes literais exigidos pelo JNI.
// ---------------------------------------------------------------------------

extern "C" {

/*
 * Chamado no onCreate (após loadLibraries do SDLActivity): guarda a JavaVM
 * para que a thread de render consiga chamar Java (requestFilePicker).
 */
JNIEXPORT void JNICALL
Java_com_deivid22srk_dk64recomp_MainActivity_nativeBridgeInit(JNIEnv *env, jclass /*clazz*/) {
    JavaVM *vm = nullptr;
    if (env->GetJavaVM(&vm) == JNI_OK && vm != nullptr) {
        androidport::filedialog::set_java_vm(vm);
        __android_log_print(ANDROID_LOG_INFO, "DK64Recomp", "file bridge: JavaVM registrada");
    }
}

/*
 * Publica o resultado processado pelo Java (cópia da ROM concluída, driver
 * instalado + probe, cancelamento, erro). Ignorado se não houver pedido
 * pendente (ex.: resultado órfão de um picker antigo).
 */
JNIEXPORT void JNICALL
Java_com_deivid22srk_dk64recomp_MainActivity_nativeOnFilePicked(JNIEnv *env, jclass /*clazz*/,
                                                                jint /*kind*/, jboolean ok,
                                                                jstring payload) {
    const char *utf = payload ? env->GetStringUTFChars(payload, nullptr) : nullptr;

    {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (g_state == State::Waiting) {
            g_ok = (ok == JNI_TRUE);
            g_payload = utf ? utf : "";
            g_state = State::Result;
            ALOGI("file bridge: resultado publicado (ok=%d, %.80s)",
                  g_ok ? 1 : 0, g_payload.c_str());
        } else {
            ALOGW("file bridge: resultado ignorado (slot não está Waiting)");
        }
    }

    if (utf) env->ReleaseStringUTFChars(payload, utf);
}

} // extern "C"

} // namespace androidport::filedialog

#endif // __ANDROID__
