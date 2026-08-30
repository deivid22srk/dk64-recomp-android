// ============================================================================
// patches_touch.c — Port DK64-Recomp Android
//
// Suporte touch nativo: expõe o estado do jogo (modo atual) ao frontend uma
// vez por frame, para que a camada de toque (src/main/touch_input.cpp) saiba
// em qual tela o jogo está (logo N64, abertura, DK Rap, DK TV/título, menu
// principal, aventura...) e traduza toques nas posições corretas da tela em
// ações nativas — sem gamepad virtual e sem emulação de mouse no jogo.
//
// Mecanismo: o frontend exporta recomp_touch_frame_state() (implementado em
// src/game/recomp_api.cpp, registrado em src/main/main.cpp via REGISTER_FUNC),
// e lê diretamente da RAM do jogo as variáveis globais do decomp:
//   - game_mode_copy (u8 @ 0x80755314) — modo atual do jogo (GameModes)
//   - current_map    (Maps @ 0x8076A0A8) — mapa atual
// Este patch chama esse export uma vez por frame, engatado na função de
// reinício de input do jogo (func_global_asm_8060AA58), que roda em TODOS os
// modos (logo, cutscene, rap, DK TV, menu, aventura, minigames) a cada ciclo
// do loop principal — ver src/global_asm/code_F600.c do decomp; o chamador é
// func_global_asm_805FC2B0 (loop da thread de jogo, via
// func_global_asm_8060A9BC).
//
// O corpo de func_global_asm_8060AA58 é uma cópia fiel do decomp
// (src/global_asm/code_F600.c:63-88); a única adição é a chamada ao export.
// ============================================================================

#include "patch_helpers.h"
#include "patches.h"

// Export do frontend (ver recomp_api.cpp). No lado MIPS a chamada é feita
// como função comum; a recompilação resolve pelo nome do export.
DECLARE_FUNC(void, recomp_touch_frame_state);

// Símbolos de dados do jogo (todos presentes em DK64Syms/data_dump.toml).
extern u16 newly_pressed_input[];       // 0x807ECD48
extern s16 D_global_asm_807ECD40[];     // 0x807ECD40
extern s16 D_global_asm_807ECD50[];     // 0x807ECD50
extern s16 D_global_asm_807ECD68[0x40]; // 0x807ECD68 — 4 entradas de 0x10 s16
extern u8 D_global_asm_807ECD09;        // 0x807ECD09
extern u8 D_global_asm_807ECDF8;        // 0x807ECDF8
extern u8 D_global_asm_807ECDFC[];      // 0x807ECDFC

// Reinício de input por frame (decomp: func_global_asm_8060AA58).
// Zera os buffers de input dos jogadores e remonta a lista de controladores
// conectados. Roda uma vez por ciclo do loop principal, ANTES da leitura dos
// pads — ponto ideal para publicar o estado do jogo ao frontend.
RECOMP_PATCH void func_global_asm_8060AA58(u8 arg0) {
    s32 i;
    u8 new_var;
    s32 j;
    u8 var;

    //@recomp: publica game_mode_copy/current_map para a camada touch nativa
    recomp_touch_frame_state();

    var = 0;
    D_global_asm_807ECDF8 = D_global_asm_807ECD09 & arg0;
    for (i = 0; i < 4; i++) {
        newly_pressed_input[i] = 0;
        D_global_asm_807ECD40[i] = 0;
        D_global_asm_807ECD50[i] = 0;
        D_global_asm_807ECDFC[i] = 0;
        for (j = 0; j < 0x10; j++) {
            D_global_asm_807ECD68[i * 0x10 + j] = 0;
        }

        new_var = D_global_asm_807ECDF8;
        if (new_var & (1 << i)) {
            D_global_asm_807ECDFC[var] = i;
            var++;
        }
    }
}
