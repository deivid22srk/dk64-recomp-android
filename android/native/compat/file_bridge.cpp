/*
 * file_bridge.cpp — implementação da ponte SAF <-> menus do recomp.
 *
 * Ver file_bridge.h para o desenho completo (não-bloqueante, dispatch no
 * draw_hook). Este arquivo é vinculado DENTRO do libmain.so junto com o resto
 * do port; os símbolos androidport::filedialog são consumidos:
 *   - por recompui/src/util/file.cpp (patch do port) para open_file_dialog;
 *   - por RecompFrontend/recompui/src/base/ui_state.cpp (patch do port) para
 *     o dispatch por frame no draw_hook;
 *   - por src/main/main.cpp para o fluxo do driver Turnip no launcher E
 *     para a opção "Logs de diagnóstico" do menu (open_diagnostics_screen),
 *     que reusa a MESMA infraestrutura JNI (JavaVM + cache de classe/método)
 *     num fluxo fire-and-forget — a tela de diagnóstico não devolve nada ao
 *     nativo, diferente do SAF que publica nativeOnFilePicked.
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

/*
 * Cache de classe/método JNI. POR QUE ISTO EXISTE: FindClass em thread NATIVA
 * anexada via AttachCurrentThread (a present thread do RT64 — nunca rodou
 * código Java, portanto sem frames Java) resolve através do classloader do
 * SISTEMA, que não conhece classes do app — FindClass("...MainActivity")
 * falhava SEMPRE ali, request() devolvia false e o DocumentsUI (gerenciador
 * de arquivos do Android) NUNCA abria, tanto para a ROM quanto para o driver
 * Turnip. O cache é populado em nativeBridgeInit — chamada JNI originada no
 * Java (MainActivity.onCreate, UI thread): o frame chamador pertence ao
 * PathClassLoader do app, então FindClass funciona — e uma referência GLOBAL
 * + methodID são válidos em qualquer thread daqui em diante.
 */
jclass g_main_class = nullptr;     // global ref de MainActivity
jmethodID g_mid_request = nullptr; // MainActivity.requestFilePicker(I)Z
jmethodID g_mid_diag = nullptr;    // MainActivity.openDiagnosticsScreen()Z

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
 * Popula o cache JNI (classe global + methodID) se ainda não existir.
 * Segura para qualquer thread: em thread nativa anexada o FindClass falha
 * (classloader do sistema), mas aí o cache já foi populado no
 * nativeBridgeInit (thread Java) e esta função é um no-op.
 */
bool ensure_jni_cache(JNIEnv *env) {
    if (env == nullptr) return false;

    {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (g_main_class != nullptr && g_mid_request != nullptr
                && g_mid_diag != nullptr) return true;
    }

    jclass local = env->FindClass(kMainActivity);
    if (local == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        ALOGE("file bridge: FindClass(%s) falhou (thread nativa sem cache "
              "populado? nativeBridgeInit não rodou)", kMainActivity);
        return false;
    }

    jclass global = static_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    if (global == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        ALOGE("file bridge: NewGlobalRef(MainActivity) falhou");
        return false;
    }

    jmethodID mid = env->GetStaticMethodID(global, "requestFilePicker", "(I)Z");
    if (mid == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteGlobalRef(global);
        ALOGE("file bridge: requestFilePicker(I)Z não encontrado");
        return false;
    }

    jmethodID mid_diag = env->GetStaticMethodID(global, "openDiagnosticsScreen", "()Z");
    if (mid_diag == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteGlobalRef(global);
        ALOGE("file bridge: openDiagnosticsScreen()Z não encontrado");
        return false;
    }

    {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (g_main_class == nullptr) {
            g_main_class = global;
            g_mid_request = mid;
            g_mid_diag = mid_diag;
        } else {
            // Outra thread venceu a corrida: descarta a cópia deste thread.
            env->DeleteGlobalRef(global);
        }
    }
    return true;
}

/*
 * Chama MainActivity.requestFilePicker(kind) (static, boolean). Pode rodar na
 * thread de present do RT64 (não anexada à JVM): anexa na 1ª chamada e mantém
 * anexada — o processo é curto e o número de threads é pequeno.
 *
 * IMPORTANTE: NÃO chamar FindClass aqui direto — veja ensure_jni_cache().
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

    if (!ensure_jni_cache(env)) return false;

    jclass cls = nullptr;
    jmethodID mid = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        cls = g_main_class;
        mid = g_mid_request;
    }

    jboolean res = env->CallStaticBooleanMethod(cls, mid, static_cast<jint>(kind));
    if (env->ExceptionCheck()) {
        ALOGE("file bridge: exceção Java em requestFilePicker");
        env->ExceptionDescribe();
        env->ExceptionClear();
        return false;
    }

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

/*
 * Abre a tela Java de diagnóstico (DiagnosticsActivity) — opção "Logs de
 * diagnóstico" do menu launcher (as Configurações do app). Fire-and-forget:
 * NÃO usa o slot único do SAF (nada volta pelo nativeOnFilePicked); só posta
 * o Intent no Java e devolve. Roda na thread de render (callback do
 * GameOption, segurando ui_state_mutex): mesma disciplina JNI de
 * call_java_request — anexa a thread na 1ª chamada e usa o cache de
 * classe/método populado no nativeBridgeInit (FindClass em thread nativa
 * falharia; ver ensure_jni_cache).
 */
bool open_diagnostics_screen() {
    JavaVM *vm = g_vm;
    if (vm == nullptr) {
        ALOGE("diagnostics: JavaVM ausente (nativeBridgeInit não rodou?)");
        return false;
    }

    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_4) != JNI_OK) {
        if (vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            ALOGE("diagnostics: AttachCurrentThread falhou");
            return false;
        }
    }

    if (!ensure_jni_cache(env)) return false;

    jclass cls = nullptr;
    jmethodID mid = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        cls = g_main_class;
        mid = g_mid_diag;
    }

    jboolean res = env->CallStaticBooleanMethod(cls, mid);
    if (env->ExceptionCheck()) {
        ALOGE("diagnostics: exceção Java em openDiagnosticsScreen");
        env->ExceptionDescribe();
        env->ExceptionClear();
        return false;
    }

    if (!res) ALOGW("diagnostics: Java recusou abrir a tela (Activity indisponível?)");
    return res == JNI_TRUE;
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
        /*
         * Popula o cache de classe/método AQUI, na thread que fez a chamada
         * Java (UI thread): FindClass funciona neste contexto (o frame
         * chamador — MainActivity.nativeBridgeInit — pertence ao classloader
         * do app). Na present thread do RT64 o FindClass NÃO encontraria a
         * classe e o gerenciador de arquivos nunca abriria.
         */
        if (androidport::filedialog::ensure_jni_cache(env)) {
            __android_log_print(ANDROID_LOG_INFO, "DK64Recomp",
                                "file bridge: JavaVM registrada + classe/método em cache");
        } else {
            __android_log_print(ANDROID_LOG_ERROR, "DK64Recomp",
                                "file bridge: JavaVM registrada, mas o cache JNI falhou");
        }
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
