/*
 * Virtual gamepad Android — ponte entre o overlay de toque (Java/Kotlin) e o
 * runtime do DK64: Recompiled Android.
 *
 * Este módulo NÃO altera os submódulos (RecompFrontend/N64ModernRuntime):
 *  - O estado do pad (botões + analógico) é mantido aqui em atômicos,
 *    escritos pela thread de UI do Android via JNI e lidos pelo callback
 *    `get_input` do ultramodern (merge em src/main/main.cpp).
 *  - Para navegar as interfaces RmlUi (launcher/menu de configuração),
 *    os toques são espelhados como eventos SDL sintéticos
 *    (SDL_CONTROLLERBUTTONDOWN/UP + SDL_CONTROLLERAXISMOTION) via
 *    SDL_PushEvent — o fluxo normal do recompinput processa esses eventos
 *    e o `cont_button_to_key`/`cont_axis_to_key` da UI os converte em
 *    navegação, pois os mapeamentos de menu são consultados pelo perfil e
 *    não pelo `which` do evento.
 *
 * Botões enviados pelo Java (ids de VirtualPadView):
 *   0=A 1=B 2=Z 3=L 4=R 5=START 6=C↑ 7=C↓ 8=C← 9=C→
 *   10=D↑ 11=D↓ 12=D← 13=D→ 14=MENU (só UI: equivale ao botão BACK/Select)
 */
#pragma once

#include <cstdint>

namespace androidport::virtualpad {

// Ids de botão enviados pelo overlay Java (devem casar com VirtualPadView.kt).
enum ButtonId : int {
    PAD_A = 0,
    PAD_B = 1,
    PAD_Z = 2,
    PAD_L = 3,
    PAD_R = 4,
    PAD_START = 5,
    PAD_C_UP = 6,
    PAD_C_DOWN = 7,
    PAD_C_LEFT = 8,
    PAD_C_RIGHT = 9,
    PAD_DPAD_UP = 10,
    PAD_DPAD_DOWN = 11,
    PAD_DPAD_LEFT = 12,
    PAD_DPAD_RIGHT = 13,
    PAD_MENU = 14, // abre/fecha o menu do port (TOGGLE_MENU) — sem efeito no jogo
    PAD_COUNT = 15,
};

// Chamado pelo Java na criação do overlay: cacheia JavaVM/jclass para o
// callback onGameStarted. Seguro chamar mais de uma vez (reinit é ignorado).
bool init_jni(void* env, void* clazz);

// True se o overlay Java já inicializou a ponte.
bool is_bridge_ready();

// Estado de botão (chamado da thread de UI do Android via JNI).
void set_button(int button_id, bool pressed);

// Estado do analógico no intervalo [-1, 1] (up = +y, já invertido pelo Java).
void set_stick(float x, float y);

// Mescla o estado do pad virtual no input N64 consolidado.
// Chamado pelo wrapper de `get_input` em main.cpp (thread de input do jogo).
void merge_input(int controller_num, uint16_t* buttons, float* x, float* y);

// Detecta transição "jogo iniciado" e notifica o overlay Java (mostra o pad).
// Chamado pelo wrapper de `poll_input` em main.cpp.
void notify_game_started(bool game_started);

// Libera as refs globais JNI (chamado no fim do main; opcional/inofensivo).
void shutdown_jni();

} // namespace androidport::virtualpad
