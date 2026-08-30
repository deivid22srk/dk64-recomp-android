// ============================================================================
// touch_input.h — Port DK64-Recomp Android
//
// Camada de TOQUE NATIVO (true touch) para Android.
//
// Princípios do projeto (a pedido do usuário):
//   - NÃO é um gamepad virtual: nada é desenhado na tela e nenhum toque é
//     traduzido "cegamente" para botões de um controle imaginário.
//   - NÃO é emulação de mouse para o JOGO: dentro do jogo, os toques são
//     interpretados pela camada de toque conforme a TELA ATIVA do jogo
//     (logo N64, abertura, DK Rap, DK TV/título, menu de barris...).
//   - Nos menus do frontend (RmlUi: launcher e configurações), o toque é
//     entregue ao pipeline nativo de ponteiro do RmlUi — tocar diretamente
//     em um elemento o ativa; arrastar com dois dedos rola listas.
//
// Estado do jogo: o patch patches/patches_touch.c publica game_mode_copy e
// current_map uma vez por frame via o export recomp_touch_frame_state()
// (implementado em src/game/recomp_api.cpp), que lê a RAM do jogo.
// ============================================================================

#ifndef __TOUCH_INPUT_H__
#define __TOUCH_INPUT_H__

#include <cstdint>

namespace touchlayer {

// Instala o event watch de toque (chamar após SDL_Init, somente __ANDROID__).
void init();

// Processa ações de UI pendentes na thread gráfica (chamar de update_gfx):
// ex. abertura do menu de configurações com toque de 2 dedos em jogo.
void process_pending_ui_actions();

// Função registrada em ultramodern::input::callbacks_t::get_input.
// Chama recompinput::profiles::get_n64_input e aplica as ações de toque
// pendentes (pulsos de START/A/B e passos do direcional derivados dos toques).
bool get_n64_input(int player_index, uint16_t* buttons_out, float* x_out, float* y_out);

// Chamado pelo export recomp_touch_frame_state (ver patches/patches_touch.c)
// com o estado do jogo lido diretamente da RAM.
void notify_game_state(uint8_t game_mode_copy, uint8_t current_map);

} // namespace touchlayer

#endif
