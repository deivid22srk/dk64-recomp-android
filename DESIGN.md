# DESIGN — Port Android do DK64-Recompiled (decisões de arquitetura)

> Leia também: `/home/z/my-project/port-docs/INVESTIGATION.md` (diagnóstico do upstream).

## Decisões do usuário (fechadas)
- ROM runtime: APK **sem** ROM; scan de `Android/data/com.deivid22srk.dk64recomp/files/` + seletor SAF
- ROM build: release asset `build-inputs` no próprio repo (workflow baixa via GITHUB_TOKEN)
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
    recompfrontend-android.patch
  app/src/main/            AndroidManifest.xml, MainActivity.java, res/, java org.libsdl.app (vendored SDL2 2.30.3)
.github/workflows/build.yml  build completo (codegen host + gradle APK debug)
```

## Estratégia CMake (android/app/CMakeLists.txt) — SEM editar submódulos
- Root próprio (NÃO inclui o CMakeLists raiz do upstream, que exige vcpkg/SDL do sistema)
- SDL2 2.30.3 via FetchContent (SDL_STATIC=ON, SDL_SHARED=OFF). Compat de includes:
  `${CMAKE_BINARY_DIR}/sdl2inc/SDL2/*` (cópia dos headers) p/ `#include "SDL2/SDL.h"`
  + `${sdl2_BINARY_DIR}/include` (SDL_config.h gerado) + `${sdl2_SOURCE_DIR}/include`
- **Fake config packages** (targets GLOBAL IMPORTED) para find_package dos submódulos:
  - SDL2: `SDL2_DIR` -> SDL2Config.cmake que define `SDL2::SDL2` (INTERFACE IMPORTED GLOBAL) + vars SDL2_INCLUDE_DIRS/SDL2_LIBRARIES
  - CURL: `CURL_DIR` -> CURLConfig.cmake definindo `CURL::libcurl` (GLOBAL) -> `curl_stub`
  - freetype: `CMAKE_MODULE_PATH` += `android/cmake/Modules/FindFreetype.cmake` (usa target `freetype` do FetchContent)
- Stubs compilados aqui e linkados: `nfd` (estático, substitui add_subdirectory do rt64 via patch) e `curl_stub`
- Patch nos submódulos (git apply no CI ANTES do build):
  - rt64: (1) file_to_c/tools `if(NOT ANDROID)`; (2) nfd `if(NOT ANDROID)`; (3) DXC p/ ANDROID usa CMAKE_HOST_SYSTEM_PROCESSOR; (4) `PLUME_SDL_VULKAN_ENABLED`/`RT64_SDL_WINDOW_VULKAN` também em ANDROID (rt64 CMake L77+L304); (5) rt64_application_window.cpp: branches `__ANDROID__` com static_assert -> implementação SDL
  - RecompFrontend: file.cpp (get_app_folder_path/get_program_path/get_asset_path/open_file_dialog) com branches `__ANDROID__` chamando android_paths.h; CMake do recompui: nada (find_packages resolvidos via fakes)
- Definições globais antes dos add_subdirectory: HLSL_CPU, FFX_GCC, IMGUI_IMPL_VULKAN_NO_PROTOTYPES, PLUME_SDL_VULKAN_ENABLED, RT64_SDL_WINDOW_VULKAN, RT64_STATIC=TRUE, RECOMP_ANDROID
- Depois dos add_subdirectory: injetar includes SDL2 em recompui/recompinput/rt64/main
- Target final: `add_library(main SHARED ...)` com fontes do upstream (src/main/*.cpp, src/game/*.cpp, rsp/n_aspMain.cpp gerado, RecompiledFuncs/*.c glob, PatchesLib, icon_bytes.c gerado via file_to_c host em PATH)
- Link: PatchesLib RecompiledFuncs recompui recompinput librecomp ultramodern rt64 nfd SDL2-static vulkan android log ${CMAKE_DL_LIBS}; flags: -fno-strict-aliasing (fontes recomp), `-Wl,-z,max-page-size=16384`, STL c++_shared (via gradle)
- src/main/main.cpp (arquivo do repo — EDITÁVEL): `#ifdef __ANDROID__ #include <SDL_main.h> #else #define SDL_MAIN_HANDLED #endif` + parse de argv (filesDir, extFilesDir) -> android_paths

## Runtime Android
- MainActivity extends SDLActivity (java vendored de SDL2 2.30.3): getMainLibName()="main",
  getArguments() = {"dk64recomp", filesDir, extFilesDir}
- onCreate: (1) checa Vulkan >=1.1 (aviso se ausente); (2) se não há ROM em filesDir/extFilesDir
  -> UI própria com botão SAF (ACTION_OPEN_DOCUMENT, copia p/ extFilesDir) e NÃO chama super.onCreate;
  após ROM presente -> recreate() -> fluxo SDL normal
- Cópia de assets do APK (assets/ + recompcontrollerdb.txt) para filesDir na 1ª execução (marcador .assets_version)
- `recompui::file::open_file_dialog` (Android): escaneia extFilesDir/filesDir por *.z64/*.n64/*.v64 ->
  callback(path) — o runtime valida hash, faz byteswap e guarda em <config>/DK64.z64
- Manifest: landscape sensor, largeHeap, hardwareAccelerated=false, configChanges completos

## CI (build.yml, ubuntu-latest)
1. checkout submodules recursive (vcpkg skip via update=none)
2. apt: ninja-build lld clang llvm (patches ELF mips) ; java 17
3. ROM: gh release download build-inputs (GITHUB_TOKEN) -> unzip -> valida sha1 cf806ff... 
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
  (grep final no ui_mod_discovery_http.cpp antes de fechar; `curl_global_initialize` era falso positivo — confirmar)

## Riscos abertos (monitorar no CI)
- plume Vulkan em Android: código __ANDROID__ existe; caminho principal usa PLUME_SDL_VULKAN_ENABLED (SDL surface)
- sse2neon: main.cpp/recomp usam intrinsics x86? sse2neon no include path do N64ModernRuntime (arm64 Linux upstream compila — boa referência)
- curl headers no recompui: stub curl/curl.h próprio (android/native/stubs/include/curl/curl.h) p/ compilar ui_mod_discovery_http
- Warnings: -Wno-* nos alvos recomp (como upstream); "sem warnings relevantes" = sem erros e warnings novos do nosso código tratados
