# DK64: Recompiled — Port Android

[![build](https://github.com/deivid22srk/dk64-recomp-android/actions/workflows/build.yml/badge.svg)](https://github.com/deivid22srk/dk64-recomp-android/actions/workflows/build.yml)

Port do [Donkey Kong 64: Recompiled](https://github.com/Rainchus/Donkey-Kong-64-Recompiled)
(Rainchus) para **Android nativo** via NDK, mantendo toda a stack original:
[N64ModernRuntime](https://github.com/Killklli/N64ModernRuntime) +
[RT64](https://github.com/rt64/rt64) (Vulkan) + RmlUi + SDL2.

> ⚠️ **Este repositório não contém game assets.** Você precisa da ROM original de
> **Donkey Kong 64 (EUA, NTSC-U 1.0)** — sha1 `cf806ff2603640a748fca5026ded28802f1f4a50`.
> Este projeto é um port de código aberto; nenhuma ROM é distribuída aqui.

## APK pronto

Cada push gera um APK automaticamente no GitHub Actions (workflow `build.yml`):
**Actions → build → artefato `dk64recomp-android-debug`** (`app-debug.apk`).

## Instalação no aparelho

1. Instale o `app-debug.apk` (habilite "Instalar apps desconhecidos").
2. Abra o app — ele vai direto para o **menu do jogo** (a tela Java de setup foi
   desativada). Na primeira execução os assets da UI são extraídos sozinhos.
3. Se ainda não houver ROM, a primeira opção do menu é **“Load ROM”**: toque,
   escolha o arquivo (`.z64`, `.n64` ou `.v64` — a conversão de byteswap é
   automática) no seletor de arquivos do Android e aguarde a validação — a
   opção vira **“Start Game”**.

   > A ROM escolhida é copiada para o armazenamento interno do app e validada
   > por hash (pick-once: nas próximas aberturas o menu já abre em “Start
   > Game”).

### Requisitos

- Android 8.0+ (arm64-v8a)
- **GPU com Vulkan 1.1+**
- Gamepad Bluetooth/USB recomendado para jogar; touch navega os menus como mouse
- O mod store *online* está desativado no Android v1 (mods locais `.nrm`/`.rtz` funcionam:
  coloque-os em `Android/data/com.deivid22srk.dk64recomp/files/mods/`)

## Gamepad virtual (touch)

Para jogar **sem controle físico**, o port inclui um gamepad virtual desenhado
sobre o jogo (`VirtualPadView`), no mesmo estilo visual do projeto
[N64Pad2](https://github.com/deivid22srk/N64Pad2) (desenho 100% em `Canvas`,
realce verde no toque, vibração leve, multi-touch real) com o HUD posicionado
no estilo do **Dolphin**, em posições confortáveis para os polegares:

| Região | Controles |
|---|---|
| Topo | `L` (esq) · `Z` (centro) · `R` (dir) |
| Esquerda | Analógico único com setas de 8 direções e zona morta |
| Base | D-pad em cruz · `START` vermelho · botão `☰` (abre o menu do port) |
| Direita | Losango de 4 botões **C** amarelos · `A` grande · `B` (diagonal do A, como no N64) |
| Quina inferior direita | Mostrar/esconder o HUD (fica ativo mesmo escondido) |

Comportamento:

- O HUD **só aparece quando o jogo inicia** — a launcher e os menus continuam
  usando o touch como mouse. Toques em áreas livres do HUD também passam para
  o jogo normalmente.
- Botões A/B/C/START/L/R/Z/D-pad são repassados ao runtime como input N64
  (mesma máscara de botões de um controle físico), então **todos os modos de
  jogo, menus internos do DK64 e combinações funcionam igual**.
- `A` aceita e `B` volta nas interfaces do port (launcher/menu de configuração);
  o analógico e o D-pad navegam; `☰` equivale ao botão Select/Back
  (abre e fecha o menu de configurações do port em jogo).
- Controles semi-transparentes (estilo Dolphin) para não esconder a ação.

## Como compilar (resumo)

O build tem duas fases (ambas automatizadas no CI):

1. **Codegen no host** — baixa a ROM de input do release `build-inputs` de um
   **repositório privado separado** (`dk64-recomp-build-inputs`, via secret
   `PRIVATE_REPO_TOKEN`), descomprime com o script do [decomp do DK64](https://gitlab.com/dk64_decomp/dk64),
   compila [N64Recomp](https://github.com/N64Recomp/N64Recomp)/`RSPRecomp` e roda
   `us.toml`, `n_aspMain.toml` e `patches.toml`; além do `file_to_c` (host).
2. **APK via Gradle+NDK** — `android/gradlew assembleDebug` com `externalNativeBuild`
   (CMake orquestrado em `android/app/CMakeLists.txt`).

Detalhes completos: [DESIGN.md](DESIGN.md) · README original do upstream: [README-UPSTREAM.md](README-UPSTREAM.md)
· build desktop: [BUILDING.md](BUILDING.md).

### Repositório privado de inputs (setup único para quem compila)

O zip com a ROM de build **não fica neste repo público** (nem em releases dele):
fica em um repositório **privado** que o CI acessa via PAT. Configure tudo com o
script pronto (requer [gh CLI](https://cli.github.com/), `zip`/`unzip` e um PAT
classic com escopo `repo` — de preferência um token novo, não compartilhado em chats):

```bash
GH_TOKEN=ghp_... ROM_ZIP=caminho/para/Donkey.Kong.64.zip \
  ./scripts/setup-build-inputs-repo.sh --purge-public
```

O script: valida o sha1 da ROM (NTSC-U 1.0), cria o repo privado
`dk64-recomp-build-inputs`, publica o release `build-inputs` com o asset
`Donkey.Kong.64.zip`, define o secret `PRIVATE_REPO_TOKEN` neste repo e
( com `--purge-public` ) remove qualquer release/tag `build-inputs` antiga
deste repo público. Se você rotacionar o PAT depois, basta rodar o script
de novo (ou `gh secret set PRIVATE_REPO_TOKEN`) para o CI continuar funcionando.

### Local (sem CI)

```bash
# 1) Codegen (host Linux com clang/lld/ninja + python3)
git submodule update --init --recursive   # vcpkg não é necessário: update=none
<baixe sua ROM e coloque em rom-work/baserom.us.z64>
(cd rom-work && python3 ../lib/dk64_decomp/tools/generate_decompressed_rom.py)
cp rom-work/baserom.us.decompressed.z64 donkeykong64.decompressed.us.z64
# build N64Recomp/RSPRecomp (commit 2b6f0568...) e rode:
./N64Recomp us.toml && ./RSPRecomp n_aspMain.toml
make -C patches CC=clang LD=ld.lld && ./N64Recomp patches.toml
cmake -S lib/rt64/src/tools/file_to_c -B build-filetoc -G Ninja && cmake --build build-filetoc

# 2) APK
git -C lib/rt64 apply ../../android/patches/rt64-android.patch
git -C lib/rt64/src/contrib/plume apply ../../../../../android/patches/plume-android.patch
git -C lib/RecompFrontend apply ../../android/patches/recompfrontend-android.patch
cd android && ./gradlew assembleDebug -PHOST_FILE_TO_C="$PWD/../build-filetoc/file_to_c"
```

## Driver Vulkan (Turnip) — opcional

Em alguns dispositivos o driver proprietário Adreno tem bugs de Vulkan (ex.:
crash `SIGSEGV` em `vkGetRefreshCycleDurationGOOGLE` na thread `RT64 Present`,
visto no moto g34 5G). O port integra o **libadrenotools** (como Vita3K,
yuzu/sudachi e os ports de recompilados com RT64): você pode instalar um
driver **Mesa Turnip** em .zip e o jogo passa a usá-lo no lugar do driver do
sistema — **direto pelo menu do jogo**, na opção **“GPU Driver”** (embaixo de
Exit, no launcher).

1. Baixe um driver Turnip — formatos aceitos: `.zip` **adrenotools** (ex.:
   [K11MCH1/AdrenoToolsDrivers](https://github.com/K11MCH1/AdrenoToolsDrivers),
   `Turnip_vX.Y.Z_R*.zip`, variantes `Gmem`/`Sysmem`), `.zip` **Winlator/**
   **WN-Turnip** (sem `meta.json` — o app autodetecta o soname) ou `.so` solto.
   **Atenção à geração da GPU**: os zips são por geração — Adreno 6xx
   (ex.: Adreno 619 do moto g34 5G) usa os builds **a6xx**; builds a7xx/a8xx
   não expõem GPU neste aparelho.
2. No menu do jogo: **GPU Driver → Install driver (.zip)...** → escolha o zip
   (ou `.so`) no seletor do Android. O app **testa** o driver na hora (cria uma
   `VkInstance` e enumera as GPUs, o mesmo caminho do RT64): se listar a GPU
   (“installed and verified: Adreno (TM) 619…”), está pronto; se não listar
   nenhuma GPU Vulkan, a instalação é **recusada** com instruções — sem isso o
   jogo falharia com “Unable to find compatible graphics device”.
3. **Sair do app e abrir de novo** (o driver é carregado no início do RT64;
   o menu Exit encerra o processo, então reabrir já aplica o novo driver).
4. Para voltar ao driver do sistema: **GPU Driver → Use system driver**
   (botão só aparece quando há driver custom ativo).

Requer Adreno + arm64; Android 10+ recomendado. Detalhes da implementação
(incl. diagnóstico do bug “Unable to find compatible graphics device” e o
probe de validação): [docs/DRIVER-VULKAN.md](docs/DRIVER-VULKAN.md).

## Notas técnicas do port

- `libmain.so` (SDL2 estático + runtime + RT64) carregado via `SDLActivity`
  (`System.loadLibrary("main")` → `SDL_main`)
- Paths do app injetados por `getArguments()` (filesDir/externalFilesDir)
- **Toda a interação de arquivos acontece na UI do próprio jogo**: a opção
  “Load ROM” e o menu “GPU Driver” abrem o DocumentsUI do Android via ponte
  JNI não-bloqueante (`android/native/compat/file_bridge.cpp`); o resultado é
  despachado por frame no `draw_hook` (patch do `ui_state.cpp`), no mesmo
  contexto dos callbacks de menu. A tela Java de setup foi removida — o app
  abre direto no SDL e só copia assets na 1ª execução (`AppSetup`)
- `nativefiledialog-extended` e `curl` substituídos por *stubs* no Android
  (diálogo de ROM = SAF via DocumentsUI; mod store offline)
- Patches aplicados aos submódulos `rt64`/`RecompFrontend` via `git apply`
  (`android/patches/*.patch`) — submódulos permanecem upstream
- Vulkan via volk (`VK_NO_PROTOTYPES`): driver do sistema ou Turnip
  customizável via libadrenotools (ver [docs/DRIVER-VULKAN.md](docs/DRIVER-VULKAN.md))
- Alinhamento 16KB pages; minSdk 26; arm64-v8a

## Créditos e licença

- Upstream: [Rainchus/Donkey-Kong-64-Recompiled](https://github.com/Rainchus/Donkey-Kong-64-Recompiled)
  (veja [LICENSE](LICENSE)) — baseado em [N64: Recompiled](https://github.com/Mr-Wiseguy/N64Recomp)
  de Mr-Wiseguy
- RT64 (DinnerHone/rt64), N64ModernRuntime e RecompFrontend (Killklli), RmlUi,
  SDL2, dk64_decomp team — detalhes no README original ([README-UPSTREAM.md](README-UPSTREAM.md))

*Este port é um trabalho técnico de fã, sem qualquer afiliação com a Nintendo.*

### Trocar a ROM depois

O runtime guarda a ROM validada no config interno e a valida por hash a cada
início. Para escolher outra ROM, limpe os dados do app (ou apenas o armazenamento
interno) — o menu volta a oferecer **“Load ROM”** na próxima abertura.
