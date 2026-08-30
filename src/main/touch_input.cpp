// ============================================================================
// touch_input.cpp — Port DK64-Recomp Android
//
// Implementação da camada de toque nativo. Veja touch_input.h para os
// princípios. Visão geral do fluxo:
//
//   dedo na tela (SDL_FINGER*)
//        |
//        v
//   SDL_AddEventWatch (dispara no bombeamento de eventos SDL)
//        |
//        +-- menu do frontend (RmlUi) visível?
//        |       sim -> pointer events diretos ao pipeline do RmlUi:
//        |              tocar = clicar no elemento sob o dedo;
//        |              arrastar 1 dedo = mover ponteiro (sliders);
//        |              arrastar 2 dedos = rolar listas (wheel sintético)
//        +-- senão, jogo rodando? classifica pela tela ativa do jogo:
//                logo N64/abertura/DK Rap/DK TV/game over -> toque = START
//                menu principal (barris) -> terço esquerdo/direito gira o
//                anel; centro confirma com A; toque com 2 dedos volta com B
//                aventura -> 2 dedos abre o menu de configurações do
//                frontend; toque simples é ignorado (gameplay)
//
//   As ações do jogo tornam-se pulsos curtos de input N64 aplicados em
//   get_n64_input() (START/A/B) ou passos únicos do direcional (eixo X),
//   com cooldown para respeitar a animação de rotação do anel do menu.
// ============================================================================

#include <algorithm>
#include <atomic>
#include <cmath>
#include <mutex>
#include <unordered_map>

#include "SDL.h"

#include "recompui/recompui.h"
#include "recompui/config.h"
#include "recompinput/profiles.h"
#include "ultramodern/ultramodern.hpp"

#include "touch_input.h"

extern SDL_Window* window;

namespace {

// ---------------------------------------------------------------------------
// Modos do jogo (GameModes do decomp DK64 — lib/dk64_decomp/include/enums.h).
// ---------------------------------------------------------------------------
enum TouchGameMode : uint8_t {
    GM_NINTENDO_LOGO         = 0,
    GM_OPENING_CUTSCENE      = 1,
    GM_DK_RAP                = 2,
    GM_DK_TV                 = 3,
    GM_UNKNOWN_4             = 4,
    GM_MAIN_MENU             = 5,
    GM_ADVENTURE             = 6,
    GM_QUIT_GAME             = 7,
    GM_UNKNOWN_8             = 8,
    GM_GAME_OVER             = 9,
    GM_END_SEQUENCE          = 10,
    GM_DK_THEATRE            = 11,
    GM_MYSTERY_MENU_MINIGAME = 12,
    GM_SNIDES_BONUS_GAME     = 13,
    GM_END_SEQ_DK_THEATRE    = 14,
};

// Máscaras de botões N64 (formato OSContPad; ver
// recompinput/input_types.h DEFINE_N64_BUTTON_INPUTS).
constexpr uint16_t N64_BTN_A     = 0x8000;
constexpr uint16_t N64_BTN_B     = 0x4000;
constexpr uint16_t N64_BTN_START = 0x1000;

// Durações em polls de input (≈ frames do jogo).
constexpr int PULSE_START_POLLS = 6;
constexpr int PULSE_A_POLLS     = 5;
constexpr int PULSE_B_POLLS     = 5;
constexpr int STICK_STEP_POLLS  = 3;
// O anel do menu leva ~10 frames por passo de rotação (unk4 += 0.1/frame no
// decomp); o cooldown evita pulsar de novo no meio da animação.
constexpr int STICK_STEP_COOLDOWN_POLLS = 12;

// Janelas de gestos.
constexpr Uint32 TAP_MAX_DURATION_MS     = 500;
constexpr float  TAP_MAX_MOVEMENT_FRAC   = 0.06f; // fração da menor dimensão
constexpr Uint32 MULTI_FINGER_WINDOW_MS  = 250;
constexpr Uint32 MULTI_TAP_MAX_MS        = 400;   // gesto de 2 dedos rápido
constexpr float  SCROLL_STEP_PX          = 32.0f;
constexpr float  STICK_STEP_VALUE        = 0.65f; // > limiar 40/80 do jogo

enum class PendingAction {
    None,
    StartPulse,
    APulse,
    BPulse,
    StickLeft,
    StickRight,
};

struct FingerState {
    float x = 0.0f;      // normalizado 0..1 no espaço da janela
    float y = 0.0f;
    float start_x = 0.0f;
    float start_y = 0.0f;
    Uint32 down_ms = 0;
    bool moved_beyond_tap = false;
};

struct TouchState {
    std::mutex mutex;
    std::unordered_map<SDL_FingerID, FingerState> fingers;
    Uint32 first_finger_ms = 0;
    Uint32 multi_start_ms = 0;
    bool multi_gesture = false;   // 2+ dedos ativos
    bool multi_was_tap = true;    // gesto multi ainda elegível a "toque de 2 dedos"
    float scroll_accum = 0.0f;    // arrasto de 2 dedos acumulado (px)

    bool rml_button_down = false; // botão esquerdo sintetizado pressionado
    float rml_last_x = 0.0f;
    float rml_last_y = 0.0f;

    uint8_t game_mode = GM_NINTENDO_LOGO;
    uint8_t game_map = 0;
    bool game_state_known = false;

    PendingAction pending = PendingAction::None;
    int polls_left = 0;
    int cooldown_left = 0;
};

TouchState g_touch;

// Pedido de abertura do menu de configurações, processado na thread gráfica
// (os eventos de toque chegam na thread Java do SDL Android; o RmlUi só pode
// ser manipulado na thread de render).
static std::atomic<bool> g_config_open_requested{false};

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------
bool frontend_menu_visible() {
    return recompui::is_any_context_shown();
}

int window_width() {
    int w = 1280, h = 720;
    if (window != nullptr) {
        SDL_GetWindowSize(window, &w, &h);
    }
    return w;
}

int window_height() {
    int w = 1280, h = 720;
    if (window != nullptr) {
        SDL_GetWindowSize(window, &w, &h);
    }
    return h;
}

// Ação de um toque simples conforme a tela ativa do jogo.
PendingAction action_for_tap(float x_norm) {
    switch (g_touch.game_mode) {
        // Telas de abertura/fim: o próprio jogo define START como avanço
        // (máscara D_global_asm_80755308 no decomp). No logo N64 o START só
        // surte efeito após ~1,5 s, indo direto ao menu (comportamento do
        // jogo em func_global_asm_80712FC8).
        case GM_NINTENDO_LOGO:
        case GM_OPENING_CUTSCENE:
        case GM_DK_RAP:
        case GM_DK_TV:
        case GM_QUIT_GAME:
        case GM_GAME_OVER:
            return PendingAction::StartPulse;

        case GM_MAIN_MENU:
            // Anel de barris: terços laterais giram o anel; centro confirma.
            if (x_norm < (1.0f / 3.0f)) {
                return PendingAction::StickLeft;
            }
            if (x_norm > (2.0f / 3.0f)) {
                return PendingAction::StickRight;
            }
            return PendingAction::APulse;

        default:
            // Aventura e modos de jogo: toque simples não gera input.
            return PendingAction::None;
    }
}

// Enfileira um pulso de input, se não houver nenhum em curso.
void queue_action(PendingAction action) {
    if (g_touch.pending != PendingAction::None || g_touch.cooldown_left > 0) {
        return;
    }
    g_touch.pending = action;
    switch (action) {
        case PendingAction::StartPulse: g_touch.polls_left = PULSE_START_POLLS; break;
        case PendingAction::APulse:     g_touch.polls_left = PULSE_A_POLLS; break;
        case PendingAction::BPulse:     g_touch.polls_left = PULSE_B_POLLS; break;
        case PendingAction::StickLeft:
        case PendingAction::StickRight: g_touch.polls_left = STICK_STEP_POLLS; break;
        default: g_touch.pending = PendingAction::None; break;
    }
}

// Ação do "toque rápido com 2 dedos" conforme a tela ativa.
void perform_two_finger_tap() {
    if (frontend_menu_visible()) {
        return; // nos menus RmlUi, 2 dedos são rolagem
    }

    if (g_touch.game_mode == GM_MAIN_MENU) {
        queue_action(PendingAction::BPulse); // voltar (B) no anel do menu
        return;
    }

    if (ultramodern::is_game_started()) {
        // Em jogo: abrir configurações — a abertura em si é feita pela
        // thread gráfica (ver touchlayer::process_pending_ui_actions).
        g_config_open_requested.store(true, std::memory_order_release);
    }
}

// ---------------------------------------------------------------------------
// Pointer events sintéticos para o pipeline do RmlUi (menus do frontend).
// which = SDL_TOUCH_MOUSEID: consumidos apenas pela UI (fila recompui ->
// RmlSDL::InputEventHandler quando um contexto está visível); nunca alimentam
// o mouse aiming do jogo (e o jogo não está rodando enquanto isso importa).
// ---------------------------------------------------------------------------
void push_rml_button(bool down, float x_norm, float y_norm) {
    SDL_Event ev{};
    ev.type = down ? SDL_MOUSEBUTTONDOWN : SDL_MOUSEBUTTONUP;
    ev.button.timestamp = SDL_GetTicks();
    ev.button.which = SDL_TOUCH_MOUSEID;
    ev.button.button = SDL_BUTTON_LEFT;
    ev.button.state = down ? SDL_PRESSED : SDL_RELEASED;
    ev.button.clicks = 1;
    ev.button.x = static_cast<Sint32>(x_norm * static_cast<float>(window_width()));
    ev.button.y = static_cast<Sint32>(y_norm * static_cast<float>(window_height()));
    // Entrega direto na fila da UI (mesmo caminho do filtro de eventos do
    // RecompFrontend); NÃO usar SDL_PushEvent dentro de um event watch.
    recompui::queue_event(ev);
}

void push_rml_motion(float x_norm, float y_norm, float dx_norm, float dy_norm) {
    SDL_Event ev{};
    ev.type = SDL_MOUSEMOTION;
    ev.motion.timestamp = SDL_GetTicks();
    ev.motion.which = SDL_TOUCH_MOUSEID;
    ev.motion.state = g_touch.rml_button_down ? SDL_BUTTON_LMASK : 0;
    ev.motion.x = static_cast<Sint32>(x_norm * static_cast<float>(window_width()));
    ev.motion.y = static_cast<Sint32>(y_norm * static_cast<float>(window_height()));
    ev.motion.xrel = static_cast<Sint32>(dx_norm * static_cast<float>(window_width()));
    ev.motion.yrel = static_cast<Sint32>(dy_norm * static_cast<float>(window_height()));
    recompui::queue_event(ev);
}

void push_rml_wheel(Sint32 dy) {
    SDL_Event ev{};
    ev.type = SDL_MOUSEWHEEL;
    ev.wheel.timestamp = SDL_GetTicks();
    ev.wheel.which = SDL_TOUCH_MOUSEID;
    ev.wheel.x = 0;
    ev.wheel.y = dy;
    ev.wheel.direction = SDL_MOUSEWHEEL_NORMAL;
    ev.wheel.mouseX = static_cast<Sint32>(g_touch.rml_last_x * static_cast<float>(window_width()));
    ev.wheel.mouseY = static_cast<Sint32>(g_touch.rml_last_y * static_cast<float>(window_height()));
    recompui::queue_event(ev);
}

// ---------------------------------------------------------------------------
// Watch de eventos SDL (instalado em touchlayer::init).
// Retorna 0 para não interferir na cadeia de eventos.
// ---------------------------------------------------------------------------
int SDLCALL touch_event_watch(void* userdata, SDL_Event* event) {
    (void)userdata;

    switch (event->type) {
        case SDL_FINGERDOWN: {
            SDL_TouchFingerEvent& e = event->tfinger;
            std::lock_guard<std::mutex> lock(g_touch.mutex);

            Uint32 now = SDL_GetTicks();
            if (g_touch.fingers.empty()) {
                g_touch.first_finger_ms = now;
                g_touch.multi_gesture = false;
                g_touch.multi_was_tap = true;
                g_touch.scroll_accum = 0.0f;
            }
            else if (now - g_touch.first_finger_ms <= MULTI_FINGER_WINDOW_MS) {
                if (!g_touch.multi_gesture) {
                    g_touch.multi_gesture = true;
                    g_touch.multi_start_ms = now;
                    // Um segundo dedo chegou: cancela estado de clique único
                    // do pipeline RmlUi (evita botão esquerdo preso).
                    if (g_touch.rml_button_down) {
                        push_rml_button(false, g_touch.rml_last_x, g_touch.rml_last_y);
                        g_touch.rml_button_down = false;
                    }
                }
            }
            else {
                // Dedo extra tarde demais: apenas marca gesto multi.
                g_touch.multi_gesture = true;
                g_touch.multi_was_tap = false;
            }

            FingerState f;
            f.x = f.start_x = e.x;
            f.y = f.start_y = e.y;
            f.down_ms = now;
            g_touch.fingers[e.fingerId] = f;

            // Menus do frontend: pressiona o botão esquerdo sob o 1º dedo
            // (tap curto = clique; arrastar = interagir com sliders).
            if (!g_touch.multi_gesture && g_touch.fingers.size() == 1 && frontend_menu_visible()) {
                g_touch.rml_button_down = true;
                g_touch.rml_last_x = e.x;
                g_touch.rml_last_y = e.y;
                push_rml_button(true, e.x, e.y);
            }
            break;
        }

        case SDL_FINGERMOTION: {
            SDL_TouchFingerEvent& e = event->tfinger;
            std::lock_guard<std::mutex> lock(g_touch.mutex);

            auto it = g_touch.fingers.find(e.fingerId);
            if (it == g_touch.fingers.end()) {
                break;
            }

            FingerState& f = it->second;
            float dx = e.x - f.x;
            float dy = e.y - f.y;
            f.x = e.x;
            f.y = e.y;

            float dist = std::sqrt((f.x - f.start_x) * (f.x - f.start_x) +
                                   (f.y - f.start_y) * (f.y - f.start_y));
            if (dist > TAP_MAX_MOVEMENT_FRAC) {
                f.moved_beyond_tap = true;
                if (g_touch.multi_gesture) {
                    g_touch.multi_was_tap = false;
                }
            }

            if (frontend_menu_visible()) {
                if (g_touch.fingers.size() >= 2 && g_touch.multi_gesture) {
                    // Rolagem por arrasto de dois dedos (wheel sintético).
                    g_touch.scroll_accum += dy * static_cast<float>(window_height());
                    while (g_touch.scroll_accum >= SCROLL_STEP_PX) {
                        g_touch.scroll_accum -= SCROLL_STEP_PX;
                        push_rml_wheel(-1); // arrastar para cima = rolar para cima
                    }
                    while (g_touch.scroll_accum <= -SCROLL_STEP_PX) {
                        g_touch.scroll_accum += SCROLL_STEP_PX;
                        push_rml_wheel(+1);
                    }
                }
                else if (g_touch.fingers.size() == 1 && g_touch.rml_button_down) {
                    // Arrasto de um dedo = mover o ponteiro pressionado.
                    g_touch.rml_last_x = e.x;
                    g_touch.rml_last_y = e.y;
                    push_rml_motion(e.x, e.y, dx, dy);
                }
            }
            break;
        }

        case SDL_FINGERUP: {
            SDL_TouchFingerEvent& e = event->tfinger;
            {
                std::lock_guard<std::mutex> lock(g_touch.mutex);

                auto it = g_touch.fingers.find(e.fingerId);
                if (it == g_touch.fingers.end()) {
                    break;
                }
                FingerState f = it->second;
                g_touch.fingers.erase(it);

                if (frontend_menu_visible()) {
                    if (g_touch.rml_button_down && g_touch.fingers.empty()) {
                        // Solta o botão sintetizado quando o último dedo sai.
                        g_touch.rml_button_down = false;
                        push_rml_button(false, f.x, f.y);
                    }
                }

                if (g_touch.fingers.empty()) {
                    // Último dedo levantado: gesto concluído.
                    bool was_multi = g_touch.multi_gesture;
                    bool multi_tap = was_multi && g_touch.multi_was_tap &&
                                     (SDL_GetTicks() - g_touch.multi_start_ms) <= MULTI_TAP_MAX_MS;
                    g_touch.multi_gesture = false;
                    g_touch.multi_was_tap = true;
                    g_touch.scroll_accum = 0.0f;

                    if (multi_tap) {
                        perform_two_finger_tap();
                    }
                    else if (!was_multi && !f.moved_beyond_tap &&
                             (SDL_GetTicks() - f.down_ms) <= TAP_MAX_DURATION_MS &&
                             !g_touch.rml_button_down &&
                             !frontend_menu_visible()) {
                        // Toque simples válido fora dos menus RmlUi.
                        if (ultramodern::is_game_started()) {
                            PendingAction action = action_for_tap(f.x);
                            queue_action(action);
                        }
                    }
                }
            }
            break;
        }

        default:
            break;
    }

    return 0;
}

} // namespace

namespace touchlayer {

void init() {
    // Canal único de input de toque: desativa a síntese de mouse nativa do
    // SDL para toques — a camada touch sintetiza pointer events apenas onde
    // faz sentido (menus do frontend). Sem isto haveria clique duplo.
    SDL_SetHint(SDL_HINT_TOUCH_MOUSE_EVENTS, "0");
    SDL_AddEventWatch(touch_event_watch, nullptr);
}

void process_pending_ui_actions() {
    // Roda na thread gráfica (update_gfx): executa ações de UI pendentes que
    // não podem ser feitas direto no watch de eventos (thread Java).
    if (g_config_open_requested.exchange(false, std::memory_order_acq_rel)) {
        if (ultramodern::is_game_started() && !frontend_menu_visible()) {
            recompui::config::open();
        }
    }
}

void notify_game_state(uint8_t game_mode_copy, uint8_t current_map) {
    std::lock_guard<std::mutex> lock(g_touch.mutex);
    g_touch.game_mode = game_mode_copy;
    g_touch.game_map = current_map;
    g_touch.game_state_known = true;
}

bool get_n64_input(int player_index, uint16_t* buttons_out, float* x_out, float* y_out) {
    bool ret = recompinput::profiles::get_n64_input(player_index, buttons_out, x_out, y_out);

    if (ret && player_index == 0) {
        std::lock_guard<std::mutex> lock(g_touch.mutex);

        if (g_touch.cooldown_left > 0) {
            g_touch.cooldown_left--;
        }

        if (g_touch.pending != PendingAction::None && g_touch.polls_left > 0) {
            switch (g_touch.pending) {
                case PendingAction::StartPulse:
                    *buttons_out |= N64_BTN_START;
                    break;
                case PendingAction::APulse:
                    *buttons_out |= N64_BTN_A;
                    break;
                case PendingAction::BPulse:
                    *buttons_out |= N64_BTN_B;
                    break;
                case PendingAction::StickLeft:
                    *x_out = std::clamp(*x_out - STICK_STEP_VALUE, -1.0f, 1.0f);
                    break;
                case PendingAction::StickRight:
                    *x_out = std::clamp(*x_out + STICK_STEP_VALUE, -1.0f, 1.0f);
                    break;
                default:
                    break;
            }

            g_touch.polls_left--;
            if (g_touch.polls_left <= 0) {
                bool was_stick = g_touch.pending == PendingAction::StickLeft ||
                                 g_touch.pending == PendingAction::StickRight;
                g_touch.pending = PendingAction::None;
                if (was_stick) {
                    g_touch.cooldown_left = STICK_STEP_COOLDOWN_POLLS;
                }
            }
        }
    }

    return ret;
}

} // namespace touchlayer
