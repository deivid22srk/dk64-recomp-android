# Suporte Touch Nativo (true touch) — DK64 Recomp Android

> Documentação da implementação do suporte a toque **verdadeiro** no port
> Android de Donkey Kong 64: Recompiled.
> Branch: `feature/touch-nativo`.

---

## 1. Objetivo e filosofia

O pedido era claro: **suporte a touch real**, não um gamepad virtual nem
emulação de mouse "passando por" toque. A diferença está na experiência:

| Abordagem rejeitada | O que seria | O que foi feito |
|---|---|---|
| Gamepad virtual | Desenhar joystick/botões sobre a tela e mapear dedos neles | **Nada é desenhado.** O dedo toca diretamente no elemento da tela |
| Mouse emulado | SDL sintetiza mouse a partir do toque e o jogo/UI recebem "cliques de mouse" | Nos menus do frontend o toque é entregue ao pipeline nativo de ponteiro do RmlUi; **no jogo** os toques são interpretados pela camada de toque conforme a **tela ativa** |

Como o DK64 é um jogo de N64 recompilado, dentro do jogo a única "interface"
que o código original entende são inputs de controle N64. O toque verdadeiro
aqui significa: **a camada de toque sabe qual tela do jogo está ativa**
(logo N64, abertura, DK Rap, DK TV/título, menu de barris, aventura) e
traduz cada toque na posição correta da tela para a ação nativa daquela tela
— sem overlay, sem cursor, sem intermediários visuais.

---

## 2. Arquitetura

```
dedo na tela (SDL_FINGERDOWN/MOTION/UP, multi-touch)
        |
        v
+---------------------------------------------------------------+
| src/main/touch_input.cpp  (SDL_AddEventWatch)                 |
|  - rastreia dedos individuais (id, posição, tempo, movimento) |
|  - detecta toque simples, toque de 2 dedos, arrastos          |
+------------------+--------------------------------------------+
                   |
     +-------------+---------------------+
     |                                   |
     v                                   v
[Menus do frontend]                [Telas do jogo]
RmlUi (launcher/config)            classificadas por game_mode_copy
toque = clique no elemento         lido da RAM do jogo (patch nativo)
sob o dedo; arrastar 1 dedo        logo/abertura/rap/DK TV/game over:
= ponteiro (sliders); 2 dedos      toque = START
= rolar listas (wheel sintético)   menu de barris: terço esquerdo/direito
                                   = girar anel; centro = A; 2 dedos = B
                                   aventura: 2 dedos = menu de config.
```

### 2.1 Como o frontend sabe qual tela do jogo está ativa

O estado vem **direto da RAM do jogo**, sem heurística:

1. **Patch nativo** — `patches/patches_touch.c` engata na função
   `func_global_asm_8060AA58` (reinício de input do jogo, que roda uma vez
   por frame em **todos** os modos) e chama o novo export
   `recomp_touch_frame_state()` a cada frame. O corpo é cópia fiel do decomp
   (`lib/dk64_decomp/src/global_asm/code_F600.c`).

2. **Export no frontend** — `recomp_touch_frame_state()`
   (`src/game/recomp_api.cpp`, registrado em `main.cpp` via `REGISTER_FUNC`)
   lê da RAM (endereços de `DK64Syms/data_dump.toml`):
   - `game_mode_copy` (u8 @ `0x80755314`) — modo atual (`GameModes` do decomp)
   - `current_map` (Maps @ `0x8076A0A8`) — mapa atual

3. **Camada de toque** — `touchlayer::notify_game_state()` guarda o estado e
   `action_for_tap()` decide o que um toque significa naquela tela.

### 2.2 Como as ações chegam ao jogo

As ações viram **pulsos curtos de input N64** aplicados no callback de input
do runtime (`ultramodern::input::callbacks_t::get_input`, agora
`touchlayer::get_n64_input` em `main.cpp`), que primeiro repassa o input
físico (gamepad/teclado) e depois aplica o toque:

| Ação do toque | Pulso gerado | Duração |
|---|---|---|
| Avançar tela (START) | `START` (0x1000) | 6 polls |
| Confirmar (A) | `A` (0x8000) | 5 polls |
| Voltar (B) | `B` (0x4000) | 5 polls |
| Girar anel do menu | eixo X ±0,65 | 3 polls + cooldown de 12 polls |

O **cooldown de 12 polls** existe porque o anel de barris anima a rotação em
~10 frames (`unk4 += 0.1f/frame` no decomp — `func_menu_8002FE08`); pulsar
antes disso reiniciaria a animação e poderia pular opções.

Os botões são **bordas** para o jogo (`newly_pressed_input` é derivado por
comparação frame a frame), então um pulso curto é suficiente e não "trava" o
botão para o input físico.

### 2.3 Como os menus do frontend recebem toque

Quando um contexto RmlUi está visível (`recompui::is_any_context_shown()` —
launcher antes do jogo, menu de configurações em jogo, prompt de saída), a
camada de toque entrega **pointer events direto na fila da UI**
(`recompui::queue_event`) com `which = SDL_TOUCH_MOUSEID`:

- **Toque** = botão esquerdo pressionado ao tocar e solto ao levantar → o
  RmlUi faz o hit-testing nativo e clica no elemento sob o dedo;
- **Arrastar 1 dedo** = mover o ponteiro pressionado → interage com
  sliders/controles arrastáveis;
- **Arrastar 2 dedos** = rolagem por wheel sintético (`SDL_MOUSEWHEEL` a cada
  32 px acumulados) → rola listas longas.

Três detalhes importantes desta via:

1. `SDL_HINT_TOUCH_MOUSE_EVENTS = "0"` é definido **antes do `SDL_Init`**
   (`create_gfx` em `main.cpp`): o SDL deixa de sintetizar mouse a partir de
   toques, garantindo **canal único** — sem clique duplo, sem cursor fantasma.
2. Os eventos sintéticos entram na fila da UI, **não** na fila global do SDL
   (`SDL_PushEvent` dentro de um event watch pode deadlockar o lock de
   eventos) e nunca alimentam o "mouse aiming" do jogo.
3. O RmlUi não tem API de toque própria: pointer é o modelo de input nativo
   do framework — toda a semântica (hover, clique, arrasto, scroll) do
   design das telas funciona como projetado.

### 2.4 Threads

- O watch de toque dispara na thread que entrega o evento (no Android, a
  thread Java do SDL para FINGER*, a thread gráfica para o pump de
  `SDL_PollEvent`). Todo estado compartilhado fica sob `g_touch.mutex`.
- `recompui::config::open()` (2 dedos em jogo) **não** é chamado do watch:
  fica pendente em um `std::atomic` e é executado pela thread gráfica em
  `update_gfx` → `touchlayer::process_pending_ui_actions()`.
- `get_n64_input` roda na thread de input do runtime e consome os pulsos
  sob o mesmo mutex.

---

## 3. Mapa de gestos por tela

### 3.1 Telas de abertura (screenshots 1–3 do relato)

| Tela | `game_mode_copy` | Toque em qualquer lugar | Toque com 2 dedos |
|---|---|---|---|
| Logo Nintendo 64 | `0` (NINTENDO_LOGO) | START¹ | — |
| Abertura ("SO THEY'RE FINALLY HERE") | `1` (OPENING_CUTSCENE) | START — pula a cutscene | — |
| DK Rap | `2` (DK_RAP) | START — pula o rap | — |
| Título / DK TV | `3` (DK_TV) | START — vai ao menu | — |

¹ No logo N64 o próprio jogo ignora START nos primeiros ~1,5 s e, após isso,
START vai **direto ao menu** (`func_global_asm_80712FC8` no decomp).

### 3.2 Menu principal (screenshot 4 — barris) e anéis internos

`game_mode_copy = 5` (MAIN_MENU). O menu do DK64 é um **anel/carrossel** de
opções (5 no anel principal: Aventura, Som (fones), Multiplayer, "?" e
Bônus; os submenus — seleção de arquivo, opções de som etc. — usam o mesmo
mecanismo de anel, `MaaD->unk17` = foco, stick esquerdo/direito = girar,
`func_menu_8002FD38`/`func_menu_8002FE08` no decomp):

| Gesto | Zona | Ação |
|---|---|---|
| Toque no **terço esquerdo** | x < 33% | Gira o anel para a esquerda (1 passo) |
| Toque no **terço direito** | x > 67% | Gira o anel para a direita (1 passo) |
| Toque no **centro** | 33%–67% | Confirma a opção em foco (**A**) |
| Toque rápido com **2 dedos** | qualquer | **B** — volta/sai do submenu |

Para selecionar um barril: toque do lado dele até o foco chegar (o barril em
foco é o destacado), depois toque no centro. Isso funciona **uniformemente em
todos os anéis** (principal, arquivos, som, multiplayer, "?"), sem depender
de coordenadas fixas por opção.

### 3.3 Em jogo (aventura)

| Gesto | Ação |
|---|---|
| Toque simples | **Nada** (evita inputs acidentais na gameplay) |
| Toque rápido com 2 dedos | Abre o menu de configurações do frontend |

### 3.4 Menus do frontend (launcher e configurações)

| Gesto | Ação |
|---|---|
| Toque | Clique no elemento sob o dedo (botões, abas, opções) |
| Arrastar 1 dedo | Move o ponteiro pressionado (sliders) |
| Arrastar 2 dedos | Rola a lista |

---

## 4. Arquivos da implementação

| Arquivo | Papel |
|---|---|
| `src/main/touch_input.cpp/.h` | Camada de toque: watch SDL, gestos, roteamento por tela, pulsos de input |
| `src/main/main.cpp` | Hint `SDL_TOUCH_MOUSE_EVENTS=0`, `touchlayer::init()` no `create_gfx`, wrapper em `.get_input`, `process_pending_ui_actions()` no `update_gfx`, `REGISTER_FUNC(recomp_touch_frame_state)` |
| `src/game/recomp_api.cpp` | Export `recomp_touch_frame_state` — lê `game_mode_copy`/`current_map` da RAM |
| `patches/patches_touch.c` | Patch nativo: publica o estado do jogo por frame (hook em `func_global_asm_8060AA58`) |
| `CMakeLists.txt` | Adiciona `src/main/touch_input.cpp` aos fontes |

Nenhum submódulo foi alterado — toda a mudança vive no repositório do port.

---

## 5. Decisões de projeto

1. **Estado do jogo via RAM, não heurística**: ler `game_mode_copy` do
   endereço simbolizado do decomp elimina adivinhação ("será que está no
   título?") e é estável para a ROM alvo do projeto (US 1.0, símbolos de
   `DK64Syms`).
2. **Canal único de toque**: com `SDL_TOUCH_MOUSE_EVENTS=0` só existe um
   produtor de pointer events (a camada touch), o que elimina duplo clique e
   permite multi-touch real (scroll + clique simultâneos, sem botão preso).
3. **Zonas por terço no anel do menu**: robustas (sem hipóteses sobre qual
   barril está em qual coordenada — o anel **rotaciona**, as posições mudam
   conforme o foco) e uniformes para todos os anéis do jogo. O toque direto
   "no barril X" exigiria ler o foco e a geometria projetada do anel
   (via patch adicional no ator do menu) — fica registrado como evolução.
4. **Pulsos, não estados**: toques geram pulsos com cooldown; nada "segura"
   botão para sempre. Se a camada falhar, o input físico continua intacto.
5. **Android-only**: tudo é compilado sob `__ANDROID__` (watch e hint) ou é
   passivo (o wrapper `get_input` no desktop apenas repassa quando não há
   pulsos pendentes — e pulsos nunca existem sem toque).

---

## 6. Limitações conhecidas (e caminhos futuros)

- **Ajustes verticais nos anéis de opções** (ex.: volume nas opções de som,
  bits `0x10/0x20` do stick Y) ainda não têm gesto dedicado — selecionar o
  item funciona (terços laterais), ajustar o valor requer controle físico.
  Evolução natural: zona superior/inferior ou gesto vertical no centro.
- **Jogabilidade na aventura**: mover o DK exige analógico; isso é um
  controle virtual por natureza e está fora do escopo "telas/menus" deste
  trabalho (o input físico — gamepad Bluetooth — segue funcionando).
- **Toque direto em barris específicos** (pular direto ao alvo em 1 gesto):
  requer ler o foco (`MaaD->unk17`) e a geometria do anel por patch; a
  fundação (estado por frame, pulsos com cooldown) já está pronta para isso.
- **Telas de fim de jogo** (`END_SEQUENCE`) não são puláveis por design do
  jogo (máscara de avanço vazia); toques nelas são inofensivos.
