/*
 * Virtual gamepad Android — implementação (ver virtual_pad.h).
 *
 * Compilado dentro de libmain.so (alvo `main` do android/app/CMakeLists.txt).
 * Os símbolos JNI são vinculados automaticamente ao carregar a biblioteca
 * (System.loadLibrary("main") do SDLActivity), pois o nome casa com a classe
 * com.deivid22srk.dk64recomp.VirtualPadView.
 */
#include "virtual_pad.h"

#include <atomic>
#include <cmath>
#include <jni.h>
#include <android/log.h>

#ifdef __ANDROID__

#include "SDL2/SDL.h"
#include "recompinput/input_state.h" // recompinput::game_input_disabled()

#define VP_LOG(...) __android_log_print(ANDROID_LOG_INFO, "DK64Recomp/VirtualPad", __VA_ARGS__)

namespace androidport::virtualpad {

namespace {

// Bitmask N64 (mesmos valores de DEFINE_N64_BUTTON_INPUTS em
// RecompFrontend/recompinput/include/recompinput/input_types.h).
constexpr uint16_t N64_MASK[PAD_COUNT] = {
    0x8000, // A
    0x4000, // B
    0x2000, // Z
    0x0020, // L
    0x0010, // R
    0x1000, // START
    0x0008, // C_UP
    0x0004, // C_DOWN
    0x0002, // C_LEFT
    0x0001, // C_RIGHT
    0x0800, // DPAD_UP
    0x0400, // DPAD_DOWN
    0x0200, // DPAD_LEFT
    0x0100, // DPAD_RIGHT
    0x0000, // MENU — apenas navegação de UI
};

// Botão SDL sintético correspondente para navegação das interfaces RmlUi.
// Valores default dos bindings de menu: ACCEPT=SOUTH(A), BACK=WEST(X),
// TOGGLE=BACK(Select). -1 = não gera evento de UI.
constexpr int8_t SDL_BUTTON_MAP[PAD_COUNT] = {
    SDL_CONTROLLER_BUTTON_A,         // A  -> aceitar
    SDL_CONTROLLER_BUTTON_X,         // B  -> voltar (WEST)
    -1, -1, -1,                      // Z, L, R
    -1,                              // START (uso apenas in-game)
    -1, -1, -1, -1,                  // C buttons
    SDL_CONTROLLER_BUTTON_DPAD_UP,
    SDL_CONTROLLER_BUTTON_DPAD_DOWN,
    SDL_CONTROLLER_BUTTON_DPAD_LEFT,
    SDL_CONTROLLER_BUTTON_DPAD_RIGHT,
    SDL_CONTROLLER_BUTTON_BACK,      // MENU -> abre/fecha o menu do port
};

// `which` sintético para os eventos SDL (não colide com instance ids reais,
// que começam em 0 e crescem; o valor é ignorado pelos mapeamentos de menu,
// que consultam o perfil do jogador 1).
constexpr SDL_JoystickID VP_SYNTHETIC_DEVICE_ID = 0x7650;

struct VirtualPadState {
    std::atomic<uint16_t> buttons{0};
    std::atomic<float> stick_x{0.0f};
    std::atomic<float> stick_y{0.0f};
    std::atomic<bool> bridge_ready{false};
    std::atomic<bool> game_started{false};

    // Cache JNI para o callback onGameStarted.
    JavaVM* vm = nullptr;
    jclass view_class = nullptr;
    jmethodID method_on_game_started = nullptr;
};

VirtualPadState& state() {
    static VirtualPadState s;
    return s;
}

void push_synth_button(int sdl_button, bool pressed) {
    if (sdl_button < 0 || !SDL_WasInit(SDL_INIT_GAMECONTROLLER)) {
        return;
    }
    SDL_Event ev{};
    ev.type = pressed ? SDL_CONTROLLERBUTTONDOWN : SDL_CONTROLLERBUTTONUP;
    ev.cbutton.which = VP_SYNTHETIC_DEVICE_ID;
    ev.cbutton.button = static_cast<uint8_t>(sdl_button);
    ev.cbutton.state = pressed ? SDL_PRESSED : SDL_RELEASED;
    SDL_PushEvent(&ev);
}

// Envia os dois eixos do analógico esquerdo como eventos sintéticos para a
// navegação de menus (cont_axis_to_key converte em setas quando cruza 0.5).
void push_synth_axis(float x, float y) {
    if (!SDL_WasInit(SDL_INIT_GAMECONTROLLER)) {
        return;
    }
    auto push_axis = [](uint8_t axis, float value) {
        SDL_Event ev{};
        ev.type = SDL_CONTROLLERAXISMOTION;
        ev.caxis.which = VP_SYNTHETIC_DEVICE_ID;
        ev.caxis.axis = axis;
        ev.caxis.value = static_cast<Sint16>(value * 32767.0f);
        SDL_PushEvent(&ev);
    };
    push_axis(SDL_CONTROLLER_AXIS_LEFTX, x);
    push_axis(SDL_CONTROLLER_AXIS_LEFTY, y);
}

bool attach_env(JNIEnv** out_env) {
    JavaVM* vm = state().vm;
    if (vm == nullptr) {
        return false;
    }
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) {
        *out_env = env;
        return true; // thread já anexada (ex.: thread do SDL_main)
    }
    // Threads nativas do ultramodern: anexa sem desanexar depois — vivem até
    // o fim do processo e a chamada acontece apenas em transições de estado.
    if (vm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
        *out_env = env;
        return true;
    }
    return false;
}

void call_java_game_started(bool started) {
    JNIEnv* env = nullptr;
    if (!attach_env(&env)) {
        return;
    }
    VirtualPadState& s = state();
    if (s.view_class != nullptr && s.method_on_game_started != nullptr) {
        env->CallStaticVoidMethod(s.view_class, s.method_on_game_started,
                                  static_cast<jboolean>(started ? JNI_TRUE : JNI_FALSE));
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
        }
    }
}

} // namespace

bool init_jni(void* env_ptr, void* thiz_ptr) {
    JNIEnv* env = static_cast<JNIEnv*>(env_ptr);
    jobject thiz = static_cast<jobject>(thiz_ptr);
    if (env == nullptr || thiz == nullptr) {
        return false;
    }

    VirtualPadState& s = state();
    if (s.bridge_ready.load(std::memory_order_acquire)) {
        return true;
    }

    if (s.vm == nullptr) {
        env->GetJavaVM(&s.vm);
    }
    if (s.view_class == nullptr) {
        // IMPORTANTE: nativeInit() é um método de INSTÂNCIA no Kotlin, então o
        // JNI entrega `thiz` (o objeto VirtualPadView) no 2º parâmetro — NÃO a
        // classe. Passar esse objeto como jclass ao GetStaticMethodID aborta o
        // runtime (CheckJNI: "jclass has wrong type"). Obtenha o jclass por
        // FindClass, com fallback para GetObjectClass(thiz).
        jclass local = env->FindClass("com/deivid22srk/dk64recomp/VirtualPadView");
        if (local == nullptr) {
            env->ExceptionClear();
            local = env->GetObjectClass(thiz);
        }
        if (local == nullptr) {
            env->ExceptionClear();
            VP_LOG("classe VirtualPadView nao encontrada");
            return false;
        }
        jclass global = static_cast<jclass>(env->NewGlobalRef(local));
        env->DeleteLocalRef(local);
        s.view_class = global;
        s.method_on_game_started = env->GetStaticMethodID(
            global, "onGameStarted", "(Z)V");
        if (s.method_on_game_started == nullptr) {
            VP_LOG("metodo VirtualPadView.onGameStarted(boolean) nao encontrado");
            env->ExceptionClear();
        }
    }
    s.bridge_ready.store(true, std::memory_order_release);
    VP_LOG("ponte JNI do gamepad virtual inicializada");
    return true;
}

bool is_bridge_ready() {
    return state().bridge_ready.load(std::memory_order_acquire);
}

void set_button(int button_id, bool pressed) {
    if (button_id < 0 || button_id >= PAD_COUNT) {
        return;
    }
    const uint16_t mask = N64_MASK[button_id];
    uint16_t cur = state().buttons.load(std::memory_order_relaxed);
    while (true) {
        uint16_t next = pressed ? (cur | mask) : static_cast<uint16_t>(cur & ~mask);
        if (state().buttons.compare_exchange_weak(cur, next, std::memory_order_relaxed)) {
            break;
        }
    }
    // Espelha na UI (RmlUi) via evento SDL sintético.
    push_synth_button(SDL_BUTTON_MAP[button_id], pressed);
}

void set_stick(float x, float y) {
    // Mantém o vetor dentro do círculo unitário.
    float mag2 = x * x + y * y;
    if (mag2 > 1.0f) {
        float inv = 1.0f / sqrtf(mag2);
        x *= inv;
        y *= inv;
    }
    state().stick_x.store(x, std::memory_order_relaxed);
    state().stick_y.store(y, std::memory_order_relaxed);
    push_synth_axis(x, y);
}

void merge_input(int controller_num, uint16_t* buttons, float* x, float* y) {
    if (controller_num != 0 || buttons == nullptr || x == nullptr || y == nullptr) {
        return;
    }
    // Respeita os menus do port: quando a UI captura input o jogo não deve
    // receber nada do pad virtual (mesma regra do get_n64_input).
    if (recompinput::game_input_disabled()) {
        return;
    }

    uint16_t pad_buttons = state().buttons.load(std::memory_order_relaxed);
    *buttons = static_cast<uint16_t>(*buttons | pad_buttons);

    float px = state().stick_x.load(std::memory_order_relaxed);
    float py = state().stick_y.load(std::memory_order_relaxed);
    float pad_mag2 = px * px + py * py;
    float cur_mag2 = (*x) * (*x) + (*y) * (*y);
    // O analógico de maior magnitude vence (comportamento aditivo padrão dos
    // ports: múltiplas fontes convivem sem "brigar" pelo stick).
    if (pad_mag2 > cur_mag2) {
        *x = px;
        *y = py;
    }
}

void notify_game_started(bool game_started) {
    // Guarda o estado atual: o overlay consulta is_game_started() no init para
    // se auto-recuperar quando a Activity é recriada com o jogo rodando
    // (caso em que a transição abaixo não re-dispara).
    state().game_started.store(game_started, std::memory_order_relaxed);

    static std::atomic<bool> last_state{false};
    bool prev = last_state.exchange(game_started, std::memory_order_relaxed);
    if (prev != game_started && state().bridge_ready.load(std::memory_order_acquire)) {
        VP_LOG("jogo %s -> notificando overlay", game_started ? "iniciado" : "encerrado");
        call_java_game_started(game_started);
    }
}

bool is_game_started() {
    return state().game_started.load(std::memory_order_relaxed);
}

void shutdown_jni() {
    VirtualPadState& s = state();
    s.bridge_ready.store(false, std::memory_order_release);
    // Refs globais são liberadas pelo fim do processo; manter simples.
}

} // namespace androidport::virtualpad

// ---------------------------------------------------------------------------
// JNI: com.deivid22srk.dk64recomp.VirtualPadView
// ---------------------------------------------------------------------------

#define VP_JNI extern "C" __attribute__((visibility("default"))) JNIEXPORT

// Métodos externos de instância: o 2º parâmetro JNI é o objeto (`thiz`),
// jamais um jclass.

VP_JNI jboolean JNICALL
Java_com_deivid22srk_dk64recomp_VirtualPadView_nativeInit(JNIEnv* env, jobject thiz) {
    return androidport::virtualpad::init_jni(env, thiz) ? JNI_TRUE : JNI_FALSE;
}

VP_JNI void JNICALL
Java_com_deivid22srk_dk64recomp_VirtualPadView_nativeButton(JNIEnv*, jobject, jint id, jboolean pressed) {
    androidport::virtualpad::set_button(static_cast<int>(id), pressed == JNI_TRUE);
}

VP_JNI void JNICALL
Java_com_deivid22srk_dk64recomp_VirtualPadView_nativeAxis(JNIEnv*, jobject, jfloat x, jfloat y) {
    androidport::virtualpad::set_stick(static_cast<float>(x), static_cast<float>(y));
}

VP_JNI jboolean JNICALL
Java_com_deivid22srk_dk64recomp_VirtualPadView_nativeIsGameStarted(JNIEnv*, jobject) {
    return androidport::virtualpad::is_game_started() ? JNI_TRUE : JNI_FALSE;
}

#endif // __ANDROID__
