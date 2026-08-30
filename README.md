# DK64: Recompiled — Port Android

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
2. Coloque a ROM (`.z64`, `.n64` ou `.v64` — a conversão de byteswap é automática) em:
   ```
   /storage/emulated/0/Android/data/com.deivid22srk.dk64recomp/files/
   ```
   (via cabo USB ou gerenciador de arquivos) — **ou** use o botão **"Selecionar ROM…"**
   na primeira abertura do app.
3. Abra o app. Na primeira execução os assets da UI são extraídos para o armazenamento
   interno e o jogo valida a ROM (a ROM é copiada para a pasta interna como `DK64.z64`).

### Requisitos

- Android 8.0+ (arm64-v8a)
- **GPU com Vulkan 1.1+** (o app avisa se o dispositivo não reportar)
- Gamepad Bluetooth/USB recomendado para jogar; touch navega os menus como mouse
- O mod store *online* está desativado no Android v1 (mods locais `.nrm`/`.rtz` funcionam:
  coloque-os em `Android/data/com.deivid22srk.dk64recomp/files/mods/`)

## Como compilar (resumo)

O build tem duas fases (ambas automatizadas no CI):

1. **Codegen no host** — baixa a ROM de input (release asset `build-inputs` deste repo,
   via `GITHUB_TOKEN`), descomprime com o script do [decomp do DK64](https://gitlab.com/dk64_decomp/dk64),
   compila [N64Recomp](https://github.com/N64Recomp/N64Recomp)/`RSPRecomp` e roda
   `us.toml`, `n_aspMain.toml` e `patches.toml`; além do `file_to_c` (host).
2. **APK via Gradle+NDK** — `android/gradlew assembleDebug` com `externalNativeBuild`
   (CMake orquestrado em `android/app/CMakeLists.txt`).

Detalhes completos: [DESIGN.md](DESIGN.md) · README original do upstream: [README-UPSTREAM.md](README-UPSTREAM.md)
· build desktop: [BUILDING.md](BUILDING.md).

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

## Notas técnicas do port

- `libmain.so` (SDL2 estático + runtime + RT64) carregado via `SDLActivity`
  (`System.loadLibrary("main")` → `SDL_main`)
- Paths do app injetados por `getArguments()` (filesDir/externalFilesDir)
- `nativefiledialog-extended` e `curl` substituídos por *stubs* no Android
  (diálogo de ROM = escaneamento de pasta + SAF; mod store offline)
- Patches aplicados aos submódulos `rt64`/`RecompFrontend` via `git apply`
  (`android/patches/*.patch`) — submódulos permanecem upstream
- Alinhamento 16KB pages; minSdk 26; arm64-v8a

## Créditos e licença

- Upstream: [Rainchus/Donkey-Kong-64-Recompiled](https://github.com/Rainchus/Donkey-Kong-64-Recompiled)
  (veja [LICENSE](LICENSE)) — baseado em [N64: Recompiled](https://github.com/Mr-Wiseguy/N64Recomp)
  de Mr-Wiseguy
- RT64 (DinnerHone/rt64), N64ModernRuntime e RecompFrontend (Killklli), RmlUi,
  SDL2, dk64_decomp team — detalhes no README original ([README-UPSTREAM.md](README-UPSTREAM.md))

*Este port é um trabalho técnico de fã, sem qualquer afiliação com a Nintendo.*
