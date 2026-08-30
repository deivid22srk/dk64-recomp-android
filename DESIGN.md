# DESIGN — Port Android do DK64-Recompiled (decisões de arquitetura)


## Decisões do usuário (fechadas)
- ROM runtime: APK **sem** ROM; scan de `Android/data/com.deivid22srk.dk64recomp/files/` + seletor SAF
- ROM build: release asset `build-inputs` em repo PRIVADO separado (`dk64-recomp-build-inputs`;
  workflow baixa via secret `PRIVATE_REPO_TOKEN` — a ROM nunca fica no repo público)
- Gamepad prioridade; touch só menus · minSdk 26 · arm64-v8a · APK Debug · repo `dk64-recomp-android` público · docs PT-BR

## Árvore do port (repo novo)
```
/                          upstream code (submódulos pinados, vcpkg com update=none)
android/                   projeto Gradle
  app/CMakeLists.txt       ORQUESTRAÇÃO NATIVA DO ANDROID (não edita CMake de submódulos!)
  app/build.gradle.kts     AGP 8.5.2, externalNativeBuild -> app/CMakeLists.txt
  native/stubs/            nfd_stub.c, curl_stub.cpp (símbolos exatos, ver abaixo)
  native/compat/           android_paths.cpp/.h — globais de paths injetados via argv
  patches/                 .patch aplicados via `git apply` nos submódulos (CI + local)
    rt64-android.patch
    plume-android.patch
    recompfrontend-android.patch
  app/src/main/            AndroidManifest.xml, MainActivity.java, res/, java org.libsdl.app (vendored SDL2 2.30.8)
.github/workflows/build.yml  build completo (codegen host + gradle APK debug)
```

## Estratégia CMake (android/app/CMakeLists.txt) — SEM editar submódulos
- Root próprio (NÃO inclui o CMakeLists raiz do upstream, que exige vcpkg/SDL do sistema)
- SDL2 2.30.8 via FetchContent (SDL_STATIC=ON, SDL_SHARED=OFF, SDL_TEST=OFF;
  o nome real da opção de testes na série 2.30 é SDL_TEST — SDL_TEST_LIBRARY não existe).
  Drift guard no CMake: as constantes SDL_MAJOR/MINOR/MICRO_VERSION do Java vendored
  têm que ser iguais à versão da SDL C (o SDLActivity checa nativeGetVersion() no
  startup e aborta com 'SDL C/Java version mismatch' — erro que só aparece no device).
  Ao subir a versão, copie o android-project/app/src/main/java do tarball da SDL.
  Compat de includes:
  `${CMAKE_BINARY_DIR}/sdl2inc/SDL2/*` (cópia dos headers, EXCETO
  SDL_config.h/SDL_revision.h) p/ `#include "SDL2/SDL.h"`
  + `${sdl2_BINARY_DIR}/include-config-$<LOWER_CASE:$<CONFIG>>/SDL2`
  (SDL_config.h gerado — escrito só no GENERATE via file(GENERATE), por isso
  entra como genex; consumidores e a lib usam o MESMO config)
  + `${sdl2_BINARY_DIR}/include` (SDL2/SDL_revision.h gerado)
  + `${sdl2_SOURCE_DIR}/include` (fallback: SDL_config.h do tarball faz dispatch
  p/ SDL_config_android.h — correto p/ Android, mas último da ordem)
- **Fake config packages** (targets GLOBAL IMPORTED) para find_package dos submódulos:
  - SDL2: `SDL2_DIR` -> SDL2Config.cmake que define `SDL2::SDL2` (INTERFACE IMPORTED GLOBAL) + vars SDL2_INCLUDE_DIRS/SDL2_LIBRARIES
  - CURL: `CURL_DIR` -> CURLConfig.cmake definindo `CURL::libcurl` (GLOBAL) -> `curl_stub`
  - freetype: `CMAKE_MODULE_PATH` += `android/cmake/Modules/FindFreetype.cmake` (usa target `freetype` do FetchContent). O port chama `find_package(Freetype REQUIRED)` logo após o MakeAvailable — obrigatório: o RmlUi linka `Freetype::Freetype` e o freetype 2.13.2 não cria esse alias in-tree (só no install/export)
- Stubs compilados aqui e linkados: `nfd` (estático, substitui add_subdirectory do rt64 via patch) e `curl_stub`
- Patch nos submódulos (git apply no CI ANTES do build):
  - rt64: (1) file_to_c/tools `if(NOT ANDROID)`; (2) nfd `if(NOT ANDROID)`; (3) DXC p/ ANDROID usa CMAKE_HOST_SYSTEM_PROCESSOR; (4) `PLUME_SDL_VULKAN_ENABLED`/`RT64_SDL_WINDOW_VULKAN` também em ANDROID (rt64 CMake L77+L304); (5) rt64_application_window.cpp: branches `__ANDROID__` com static_assert -> implementação SDL
  - plume: reordena o typedef de `RenderWindow` — `PLUME_SDL_VULKAN_ENABLED` (SDL_Window*) passa a vencer ANTES de `__ANDROID__` (ANativeWindow*), eliminando os 6 usos de ANativeWindow* nos caminhos ativos (rt64_application_window.cpp e os sites SDL_Vulkan_* de plume_vulkan.cpp compilam com SDL_Window*); com isso o `appCore.window = window_handle` de recompui/rt64_render_context.cpp (branch `__linux__ || __ANDROID__`) também compila sem cast
  - RecompFrontend: file.cpp (get_app_folder_path/get_program_path/get_asset_path/open_file_dialog) com branches `__ANDROID__` chamando android_paths.h; CMake do recompui: nada (find_packages resolvidos via fakes)
- Definições globais antes dos add_subdirectory: HLSL_CPU, FFX_GCC, IMGUI_IMPL_VULKAN_NO_PROTOTYPES, PLUME_SDL_VULKAN_ENABLED, RT64_SDL_WINDOW_VULKAN, RT64_STATIC=TRUE, RECOMP_ANDROID
- Depois dos add_subdirectory: injetar includes SDL2 em recompui/recompinput/rt64/main
- Target final: `add_library(main SHARED ...)` com fontes do upstream (src/main/*.cpp, src/game/*.cpp, rsp/n_aspMain.cpp gerado, RecompiledFuncs/*.c glob, PatchesLib, icon_bytes.c gerado via file_to_c host em PATH)
- Link: PatchesLib RecompiledFuncs recompui recompinput librecomp ultramodern rt64 nfd SDL2-static vulkan android log ${CMAKE_DL_LIBS}; flags: -fno-strict-aliasing (fontes recomp), `-Wl,-z,max-page-size=16384`, STL c++_shared (via gradle)
- src/main/main.cpp (arquivo do repo — EDITÁVEL): `#ifdef __ANDROID__ #include <SDL_main.h> #else #define SDL_MAIN_HANDLED #endif` + parse de argv (filesDir, extFilesDir) -> android_paths

## Runtime Android (após revisão do agente 6-b)
- **SetupActivity** (launcher): checa Vulkan >=1.1 (aviso), garante ROM (.z64/.n64/.v64 via
  scan de filesDir/extFilesDir ou SAF picker com cópia em background) e assets copiados,
  então faz handoff para a MainActivity e finish()
- **MainActivity extends SDLActivity**: ciclo SDL padrão (super.onCreate sempre chamado);
  getLibraries()={"main"}; getArguments() = {filesDir, extFilesDir}
  ⚠️ SDL_android.c (nativeRunMain) define argv[0]="app_process" e copia getArguments()
  a partir de argv[1] — por isso NÃO enviamos nome do app em getArguments()
- Cópia de assets do APK (assets/ + recompcontrollerdb.txt) para filesDir na 1ª execução
  (marcador .assets_version); mapping: APK assets/<x> -> filesDir/assets/<x>, exceto
  recompcontrollerdb.txt -> filesDir/recompcontrollerdb.txt
- `recompui::file::open_file_dialog` (Android): escaneia extFilesDir/filesDir por *.z64/*.n64/*.v64 ->
  callback(path) — o runtime valida hash, faz byteswap e guarda em <config>/DK64.z64
- RT64 userPaths (rt64_user_paths.cpp): branch `__linux__` ativo no Android usa
  getenv("HOME") e cairia em getpwuid()->pw_dir (NULL p/ uid de app -> crash).
  main.cpp faz `SDL_setenv("HOME", filesDir)` antes de qualquer init → RT64 grava
  rt64-imgui.ini/rt64.log/games em filesDir/.config/rt64 (shader cache do RT64 é
  só em RAM nesta versão; nada de cwd, que é "/")
- Framerate alvo: jogo chama `recomp_get_target_framerate` (src/game/recomp_api.cpp)
  -> `ultramodern::get_target_framerate(60/frame_divisor)` (ultramodern/src/events.cpp)
  -> rr_option: Original (20 FPS), Manual (rr_manual_value) ou Display (Hz real
  via `renderer_context->get_display_framerate()`; no Android = SDL_GetWindowDisplayIndex)
- Threads: ultramodern usa pthread_setname_np (bionic API 26+, minSdk 26 OK) e
  não usa sem_open — sem problema de API level
- Manifest: landscape sensor, largeHeap, hardwareAccelerated=true (padrão do template SDL 2.30.8),
  VIBRATE p/ haptics do SDL, uses-feature opcionais, configChanges completos

## CI (build.yml, ubuntu-latest)
1. checkout submodules recursive (vcpkg skip via update=none)
2. apt: ninja-build lld clang llvm (patches ELF mips) ; java 17
3. ROM: gh release download build-inputs no repo PRIVADO (secret PRIVATE_REPO_TOKEN) -> unzip -> valida sha1 cf806ff... 
4. codegen host: clone N64Recomp @ 2b6f05688de2abc7d86da5b4a89b84c2c6acbabe, build N64RecompCLI+RSPRecomp (ninja)
5. descompressão: cp ROM -> baserom.us.z64 (tmp) ; python3 generate_decompressed_rom.py ; mv -> donkeykong64.decompressed.us.z64
6. ./N64Recomp us.toml ; ./RSPRecomp n_aspMain.toml ; make -C patches CC=clang LD=ld.lld ; ./N64Recomp patches.toml
7. host file_to_c: cmake lib/rt64/src/tools/file_to_c -> PATH
8. git apply patches nos submódulos
9. ./gradlew assembleDebug --no-daemon ; upload-artifact app-debug.apk
10. (Opcional p/ debug de falhas: gradle --scan? não; logs no CI)

## Stubs (símbolos exatos levantados por grep)
- nfd: NFD_Init, NFD_Quit, NFD_OpenDialogN, NFD_OpenDialogMultipleN, NFD_PickFolderN, NFD_SaveDialogN,
  NFD_FreePathN, NFD_PathSet_GetCount, NFD_PathSet_GetPathN, NFD_PathSet_Free (retornam NFD_ERROR/NFD_OKAY vazio)
- curl: curl_global_init, curl_easy_init/setopt/perform/getinfo/cleanup/strerror, curl_slist_append/free_all
  (verificado: o stub cobre todos os usos de ui_mod_discovery_http.cpp; `curl_global_initialize` é método próprio do ui_mod_discovery, não símbolo do libcurl)

## Riscos abertos (monitorar no CI)
- sse2neon: main.cpp/recomp usam intrinsics x86? sse2neon no include path do N64ModernRuntime (arm64 Linux upstream compila — boa referência)
- curl headers no recompui: stub curl/curl.h próprio (android/native/stubs/include/curl/curl.h) p/ compilar ui_mod_discovery_http — símbolos conferidos: o stub cobre todos os usos (curl_global_init/easy_*/slist_*; `curl_global_initialize` é método próprio do ui_mod_discovery, não símbolo do libcurl)
- Warnings: -Wno-* nos alvos recomp (como upstream); "sem warnings relevantes" = sem erros e warnings novos do nosso código tratados
- volk/Vulkan-Headers/VulkanMemoryAllocator/D3D12MemoryAllocator são SUBMÓDULOS aninhados de plume — CI precisa de `submodules: recursive` (já no build.yml)
- rt64 CMake L102-103 seta ANDROID_PLATFORM=android-24/ANDROID_ABI=arm64-v8a incondicionalmente (override morto pós-toolchain NDK, inerte; minSdk real vem do Gradle: 26)
