# ============================================================================
# DK64: Recompiled — build Android (NDK via Gradle)
# Este CMake NÃO inclui o CMakeLists raiz do upstream (que depende de vcpkg e
# SDL2 do sistema). Ele orquestra todas as dependências vendidas/submódulos e
# produz `libmain.so` (carregado pelo SDLActivity via System.loadLibrary).
#
# Requisito: HOST_FILE_TO_C=<caminho do file_to_c pré-compilado para o HOST>
# (tool de `lib/rt64/src/tools/file_to_c`, compilada no CI antes do Gradle).
# Custom commands do rt64/recompui rodam no host e chamam `file_to_c` pelo
# PATH — garantimos isso via CMAKE_PROGRAM_PATH.
# ============================================================================
cmake_minimum_required(VERSION 3.22)
project(DK64RecompAndroid LANGUAGES C CXX)

set(REPO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../..")
set(RT64_DIR        "${REPO_ROOT}/lib/rt64")
set(NMR_DIR         "${REPO_ROOT}/lib/N64ModernRuntime")
set(FRONTEND_DIR    "${REPO_ROOT}/lib/RecompFrontend")
set(ANDROID_PORT_DIR "${CMAKE_CURRENT_SOURCE_DIR}/..")

set(CMAKE_C_STANDARD 17)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_EXPORT_COMPILE_COMMANDS ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

list(APPEND CMAKE_MODULE_PATH "${ANDROID_PORT_DIR}/cmake/Modules")

# ----------------------------------------------------------------------------
# 0) Tool de host: file_to_c (obrigatória; roda no host durante o build)
#    Aceita FILE_TO_C_EXE (nome canônico, lido também pelo patch do rt64) ou
#    HOST_FILE_TO_C (alias passado pelo build.gradle.kts: -PHOST_FILE_TO_C=...).
# ----------------------------------------------------------------------------
set(FILE_TO_C_EXE "" CACHE FILEPATH "file_to_c pré-compilado para o host")
set(HOST_FILE_TO_C "" CACHE FILEPATH "alias de FILE_TO_C_EXE (build.gradle.kts)")
if(NOT FILE_TO_C_EXE AND HOST_FILE_TO_C)
    set(FILE_TO_C_EXE "${HOST_FILE_TO_C}" CACHE FILEPATH "file_to_c pré-compilado para o host" FORCE)
endif()
if(NOT FILE_TO_C_EXE OR NOT EXISTS "${FILE_TO_C_EXE}")
    message(FATAL_ERROR
        "HOST_FILE_TO_C/FILE_TO_C_EXE não definido: compile lib/rt64/src/tools/file_to_c no host e passe -DHOST_FILE_TO_C=<bin>")
endif()
set(HOST_TOOLS_DIR "${CMAKE_BINARY_DIR}/hosttools")
file(MAKE_DIRECTORY "${HOST_TOOLS_DIR}")
file(COPY "${FILE_TO_C_EXE}" DESTINATION "${HOST_TOOLS_DIR}")
file(CHMOD "${HOST_TOOLS_DIR}/file_to_c" PERMISSIONS OWNER_READ OWNER_WRITE OWNER_EXECUTE GROUP_READ GROUP_EXECUTE WORLD_READ WORLD_EXECUTE)
list(APPEND CMAKE_PROGRAM_PATH "${HOST_TOOLS_DIR}")

# ----------------------------------------------------------------------------
# 1) Definições globais (equivalentes às dos CMakeLists desktop)
# ----------------------------------------------------------------------------
set(RT64_STATIC TRUE)
set(RT64_SDL_WINDOW_VULKAN TRUE)
# Shadow de variável normal: vence o FORCE do cmake_dependent_option do plume no Android
set(PLUME_SDL_VULKAN_ENABLED TRUE)
# DXC no escopo TOP (como faz o CMake raiz desktop): rt64 define DXC apenas no
# PRÓPRIO escopo (subárvore dele); o RecompFrontend (irmão) compila shaders via
# build_*_shader do rt64 e precisa enxergar ${DXC} — sem isso o comando do DXC
# fica vazio e o .hlsl vira o executável do custom command.
set(DXC "LD_LIBRARY_PATH=${RT64_DIR}/src/contrib/dxc/lib/x64" "${RT64_DIR}/src/contrib/dxc/bin/x64/dxc-linux")
# Opções do DXC no escopo TOP (espelha o CMake raiz desktop, de onde o recompui
# as herda ao chamar build_*_shader do rt64). rt64 define as SUAS próprias no
# escopo dele para os shaders do rt64.
set(DXC_COMMON_OPTS "-I${REPO_ROOT}/src")
set(DXC_DXIL_OPTS "-Wno-ignored-attributes")
set(DXC_SPV_OPTS "-spirv" "-fspv-target-env=vulkan1.0" "-fvk-use-dx-layout")
set(DXC_PS_OPTS "${DXC_COMMON_OPTS}" "-E" "PSMain" "-T ps_6_0" "-D DYNAMIC_RENDER_PARAMS")
set(DXC_VS_OPTS "${DXC_COMMON_OPTS}" "-E" "VSMain" "-T vs_6_0" "-D DYNAMIC_RENDER_PARAMS" "-fvk-invert-y")
set(DXC_CS_OPTS "${DXC_COMMON_OPTS}" "-E" "CSMain" "-T cs_6_0")
set(DXC_GS_OPTS "${DXC_COMMON_OPTS}" "-E" "GSMain" "-T gs_6_0")
set(DXC_RT_OPTS "${DXC_COMMON_OPTS}" "-D" "RT_SHADER" "-T" "lib_6_3" "-fspv-target-env=vulkan1.1spirv1.4" "-fspv-extension=SPV_KHR_ray_tracing" "-fspv-extension=SPV_EXT_descriptor_indexing")
set(DXC_LB_OPTS "${DXC_COMMON_OPTS}" "-D" "LIBRARY" "-T" "lib_6_3")
add_compile_definitions(
    HLSL_CPU
    FFX_GCC
    IMGUI_IMPL_VULKAN_NO_PROTOTYPES
    PLUME_SDL_VULKAN_ENABLED
    RT64_SDL_WINDOW_VULKAN
    RECOMP_ANDROID
    # Driver Vulkan custom (Turnip via adrenotools): com VK_NO_PROTOTYPES todas
    # as chamadas vk* (plume/rt64/imgui/VMA) passam pela tabela de ponteiros do
    # volk, que é inicializada pelo volkInitializeCustom() com o
    # vkGetInstanceProcAddr do driver escolhido (ou dlopen padrão do sistema).
    VK_NO_PROTOTYPES
)
# __PRFCHWINTRIN_H: ver comentário do CMake raiz upstream (só afeta clang-cl, inofensivo aqui)

# ----------------------------------------------------------------------------
# 2) SDL2 2.30.8 (estático) — série 2.30 do upstream com fix ALooper p/ NDK r27
# ----------------------------------------------------------------------------
include(FetchContent)
set(SDL2_VERSION "2.30.8" CACHE STRING
    "Versão da SDL2 — o Java vendored em org/libsdl/app PRECISA ser o mesmo release")
set(SDL_SHARED OFF CACHE BOOL "" FORCE)
set(SDL_STATIC ON CACHE BOOL "" FORCE)
# SDL_TEST é o nome real da opção de testes no CMake da série 2.30 (não existe SDL_TEST_LIBRARY)
set(SDL_TEST OFF CACHE BOOL "" FORCE)
set(SDL2_DISABLE_SDL2MAIN ON CACHE BOOL "" FORCE)
set(SDL2_DISABLE_INSTALL ON CACHE BOOL "" FORCE)
# SDL 2.30.8: igual à série 2.30 usada pelo upstream desktop, mas inclui a
# correção oficial do ALooper_pollAll -> ALooper_pollOnce (obrigatória com
# NDK r27; 2.30.3 falha ao compilar SDL_androidsensor.c no Android).

# DRIFT GUARD — Java vendored x SDL C. O SDLActivity chama nativeGetVersion()
# no startup e aborta com "SDL C/Java version mismatch" se as versões
# divergirem (falha que só aparece NO DEVICE, não no build). Ao subir a
# SDL2_VERSION, sincronize android/app/src/main/java/org/libsdl/app com o
# android-project/app/src/main/java de dentro do tarball da SDL.
set(SDL_JAVA_ACTIVITY "${CMAKE_CURRENT_SOURCE_DIR}/src/main/java/org/libsdl/app/SDLActivity.java")
file(READ "${SDL_JAVA_ACTIVITY}" _sdl_java_src)
set(_sdl_java_ver "")
foreach(_comp MAJOR MINOR MICRO)
    string(REGEX MATCH "SDL_${_comp}_VERSION *= *([0-9]+)" _sdl_java_m "${_sdl_java_src}")
    if(NOT _sdl_java_m)
        message(FATAL_ERROR
            "SDLActivity.java vendored não declara SDL_${_comp}_VERSION — "
            "restaure os arquivos android-project da SDL ${SDL2_VERSION} em "
            "android/app/src/main/java/org/libsdl/app")
    endif()
    list(APPEND _sdl_java_ver "${CMAKE_MATCH_1}")
endforeach()
string(REPLACE "." ";" _sdl_c_ver "${SDL2_VERSION}")
list(GET _sdl_c_ver 0 _sdl_c_major)
list(GET _sdl_c_ver 1 _sdl_c_minor)
list(GET _sdl_c_ver 2 _sdl_c_micro)
list(GET _sdl_java_ver 0 _sdl_j_major)
list(GET _sdl_java_ver 1 _sdl_j_minor)
list(GET _sdl_java_ver 2 _sdl_j_micro)
if(NOT (_sdl_j_major STREQUAL _sdl_c_major
        AND _sdl_j_minor STREQUAL _sdl_c_minor
        AND _sdl_j_micro STREQUAL _sdl_c_micro))
    message(FATAL_ERROR
        "SDL Java vendored (${_sdl_j_major}.${_sdl_j_minor}.${_sdl_j_micro}) != SDL C ${SDL2_VERSION} "
        "— o app quebraria no startup com 'SDL C/Java version mismatch'. "
        "Sincronize android/app/src/main/java/org/libsdl/app com o android-project da SDL ${SDL2_VERSION}")
endif()
message(STATUS "SDL2 ${SDL2_VERSION}: Java vendored confere (${_sdl_j_major}.${_sdl_j_minor}.${_sdl_j_micro})")

FetchContent_Declare(sdl2
    URL https://github.com/libsdl-org/SDL/releases/download/release-${SDL2_VERSION}/SDL2-${SDL2_VERSION}.tar.gz
    URL_HASH SHA256=380c295ea76b9bd72d90075793971c8bcb232ba0a69a9b14da4ae8f603350058
)
FetchContent_MakeAvailable(sdl2)

# Compat de includes: código do recomp/rt64 usa "SDL2/SDL.h"; o fonte do SDL2
# expõe "SDL.h". Copiamos os headers do fonte para <build>/sdl2inc/SDL2/*.
# SDL_config.h e SDL_revision.h NÃO são copiados: usamos o config GERADO pelo
# CMake do SDL (include-config-<config>/SDL2/SDL_config.h, escrito no generate
# via file(GENERATE) — por isso entra como generator expression na lista de
# includes abaixo, garantindo que consumidores e a própria lib vejam o MESMO
# config). O fallback do tarball (SDL_config.h -> SDL_config_android.h) fica
# como último recurso em ${sdl2_SOURCE_DIR}/include.
set(SDL2_COMPAT_INCLUDE "${CMAKE_BINARY_DIR}/sdl2inc")
file(MAKE_DIRECTORY "${SDL2_COMPAT_INCLUDE}/SDL2")
file(GLOB _sdl2_headers "${sdl2_SOURCE_DIR}/include/*.h")
foreach(_h ${_sdl2_headers})
    get_filename_component(_sdl2_hdr_name "${_h}" NAME)
    if(_sdl2_hdr_name MATCHES "^(SDL_config|SDL_revision)")
        continue()
    endif()
    file(COPY "${_h}" DESTINATION "${SDL2_COMPAT_INCLUDE}/SDL2")
endforeach()

# Ordem importa: o config gerado PRIMEIRO (SDL_config.h via genex de config),
# depois as cópias planas (SDL2/SDL.h), depois SDL_revision.h gerado e o fonte.
set(SDL2_ALL_INCLUDES
    "${sdl2_BINARY_DIR}/include-config-$<LOWER_CASE:$<CONFIG>>/SDL2"
    "${SDL2_COMPAT_INCLUDE}"
    "${sdl2_BINARY_DIR}/include"
    "${sdl2_SOURCE_DIR}/include"
)

# Fake SDL2Config para os find_package(SDL2 REQUIRED) de rt64/plume/recompui
set(SDL2_FAKE_CONFIG_DIR "${CMAKE_BINARY_DIR}/fakesdl2")
file(MAKE_DIRECTORY "${SDL2_FAKE_CONFIG_DIR}")
file(WRITE "${SDL2_FAKE_CONFIG_DIR}/SDL2Config.cmake" "
if(NOT TARGET SDL2::SDL2)
    add_library(SDL2::SDL2 INTERFACE IMPORTED GLOBAL)
    set_target_properties(SDL2::SDL2 PROPERTIES
        INTERFACE_INCLUDE_DIRECTORIES \"${SDL2_ALL_INCLUDES}\")
endif()
set(SDL2_INCLUDE_DIRS \"${SDL2_ALL_INCLUDES}\")
set(SDL2_LIBRARIES SDL2-static)
")
set(SDL2_DIR "${SDL2_FAKE_CONFIG_DIR}" CACHE PATH "" FORCE)
set(SDL2_INCLUDE_DIRS "${SDL2_ALL_INCLUDES}" CACHE STRING "" FORCE)
set(SDL2_LIBRARIES "SDL2-static" CACHE STRING "" FORCE)

# ----------------------------------------------------------------------------
# 3) Stubs: nfd (sem backend Android) e curl (mod store offline na v1)
# ----------------------------------------------------------------------------
add_library(nfd STATIC
    "${ANDROID_PORT_DIR}/native/stubs/nfd_stub.c")
target_include_directories(nfd PUBLIC
    "${RT64_DIR}/src/contrib/nativefiledialog-extended/src/include")

add_library(curl_stub STATIC
    "${ANDROID_PORT_DIR}/native/stubs/curl_stub.cpp")
target_include_directories(curl_stub PUBLIC
    "${ANDROID_PORT_DIR}/native/stubs/include")

# Fake CURLConfig para find_package(CURL REQUIRED) do recompui
set(CURL_FAKE_CONFIG_DIR "${CMAKE_BINARY_DIR}/fakecurl")
file(MAKE_DIRECTORY "${CURL_FAKE_CONFIG_DIR}")
file(WRITE "${CURL_FAKE_CONFIG_DIR}/CURLConfig.cmake" "
if(NOT TARGET CURL::libcurl)
    add_library(CURL::libcurl INTERFACE IMPORTED GLOBAL)
    set_target_properties(CURL::libcurl PROPERTIES INTERFACE_LINK_LIBRARIES curl_stub)
endif()
set(CURL_FOUND TRUE)
")
set(CURL_DIR "${CURL_FAKE_CONFIG_DIR}" CACHE PATH "" FORCE)

# ----------------------------------------------------------------------------
# 4) freetype (para RmlUi) — fonte, com FindFreetype próprio
# ----------------------------------------------------------------------------
FetchContent_Declare(freetype
    URL https://github.com/freetype/freetype/archive/refs/tags/VER-2-13-2.tar.gz
    URL_HASH SHA256=427201f5d5151670d05c1f5b45bef5dda1f2e7dd971ef54f0feaaa7ffd2ab90c
)
set(FT_DISABLE_ZLIB TRUE CACHE BOOL "" FORCE)
set(FT_DISABLE_BZIP2 TRUE CACHE BOOL "" FORCE)
set(FT_DISABLE_PNG TRUE CACHE BOOL "" FORCE)
set(FT_DISABLE_HARFBUZZ TRUE CACHE BOOL "" FORCE)
set(FT_DISABLE_BROTLI TRUE CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(freetype)

# RmlUi (Source/Core/CMakeLists.txt) linka `Freetype::Freetype` e o freetype
# 2.13.2 NÃO cria esse alias in-tree (só no install/export). Chamamos o
# find_package em modo módulo -> nosso FindFreetype.cmake cria o target
# Freetype::Freetype apontando para o `freetype` do FetchContent.
find_package(Freetype REQUIRED)

# ----------------------------------------------------------------------------
# 5) libadrenotools (driver Vulkan Turnip instalável via .zip)
# ----------------------------------------------------------------------------
# O volk (submodule aninhado do plume) JÁ é compilado dentro de plume_vulkan.cpp
# (VOLK_IMPLEMENTATION) e, com o VK_NO_PROTOTYPES global (definido acima), todas
# as chamadas vk* do binário passam pela tabela de ponteiros dele. O plume
# inicializa essa tabela com volkInitializeCustom() usando o vkGetInstanceProcAddr
# do driver Turnip carregado via adrenotools (ver patch do plume e
# native/compat/custom_driver.cpp) ou, sem driver custom, com o volkInitialize()
# padrão (driver do sistema).
#
# libadrenotools (bylaws) abre a libvulkan.so do sistema com hooks que
# redirecionam o carregamento do driver para um Turnip instalado pelo usuário.
# Os hooks (libmain_hook.so/libhook_impl.so/libfile_redirect_hook.so) são
# targets SHARED deste build e vão parar no mesmo dir de saída do libmain.so,
# sendo empacotados no APK e extraídos em nativeLibraryDir
# (useLegacyPackaging=true no build.gradle.kts — requisito do adrenotools).
if(NOT EXISTS "${REPO_ROOT}/lib/adrenotools/CMakeLists.txt")
    message(FATAL_ERROR "lib/adrenotools ausente — rode 'git submodule update --init lib/adrenotools'")
endif()
add_subdirectory("${REPO_ROOT}/lib/adrenotools" "${CMAKE_BINARY_DIR}/adrenotools")

# ----------------------------------------------------------------------------
# 5-bis) Dependências do recomp (mesma ordem do CMake raiz upstream)
# ----------------------------------------------------------------------------
add_subdirectory("${RT64_DIR}" "${CMAKE_BINARY_DIR}/rt64")
add_subdirectory("${NMR_DIR}" "${CMAKE_BINARY_DIR}/N64ModernRuntime")
set(RECOMP_FRONTEND_N64MODERNRUNTIME_PATH "${NMR_DIR}")
set(RECOMP_FRONTEND_RT64_PATH "${RT64_DIR}")
add_subdirectory("${FRONTEND_DIR}" "${CMAKE_BINARY_DIR}/RecompFrontend")

# Injeção de includes SDL2 nos alvos dos submódulos (branches Linux não disparam em Android).
# plume: injetado explicitamente porque o próprio CMake dele decide os includes do SDL
# dentro de um bloco dependente de variáveis de escopo (cmake_dependent_option +
# find_package tardio do rt64) e, no cross Android, o include do SDL_vulkan.h não
# chegou ao alvo no 1º run do CI (erro: SDL_vulkan.h not found).
foreach(_t rt64 recompui recompinput ultramodern librecomp)
    if(TARGET ${_t})
        target_include_directories(${_t} PRIVATE ${SDL2_ALL_INCLUDES})
    endif()
endforeach()
if(TARGET plume)
    target_include_directories(plume PRIVATE ${SDL2_ALL_INCLUDES})
endif()
# Patch do RecompFrontend (file.cpp) usa android_paths.h
if(TARGET recompui)
    target_include_directories(recompui PRIVATE "${ANDROID_PORT_DIR}/native/compat")
endif()

# ----------------------------------------------------------------------------
# 6) Código recompilado (gerado no host antes deste build — ver CI)
#    RecompiledFuncs/*.c, rsp/n_aspMain.cpp, RecompiledPatches/*
# ----------------------------------------------------------------------------
add_library(RecompiledFuncs STATIC)
target_compile_options(RecompiledFuncs PRIVATE -fno-strict-aliasing -Wno-unused-variable -Wno-implicit-function-declaration)
target_include_directories(RecompiledFuncs PRIVATE
    "${REPO_ROOT}/include"
    "${NMR_DIR}/ultramodern/include"
    "${NMR_DIR}/librecomp/include"
    "${NMR_DIR}/N64Recomp/include"
)
file(GLOB FUNC_C_SOURCES "${REPO_ROOT}/RecompiledFuncs/*.c")
file(GLOB FUNC_CXX_SOURCES "${REPO_ROOT}/RecompiledFuncs/*.cpp")
if(NOT FUNC_C_SOURCES)
    message(FATAL_ERROR "RecompiledFuncs/*.c vazio — rode o codegen (N64Recomp us.toml) antes do build Android")
endif()
target_sources(RecompiledFuncs PRIVATE ${FUNC_C_SOURCES} ${FUNC_CXX_SOURCES})

add_library(PatchesLib STATIC)
target_compile_options(PatchesLib PRIVATE -fno-strict-aliasing -Wno-unused-variable -Wno-implicit-function-declaration)
target_include_directories(PatchesLib PRIVATE
    "${REPO_ROOT}/include"
    "${NMR_DIR}/ultramodern/include"
    "${NMR_DIR}/librecomp/include"
    "${NMR_DIR}/N64Recomp/include"
)
# patches_bin.c: embutimento do patches.bin (mesmo fluxo do CMake raiz desktop,
# que usa file_to_c do host). patches.bin é gerado pelo N64Recomp patches.toml
# no passo de codegen do CI.
if(NOT EXISTS "${REPO_ROOT}/patches/patches.bin")
    message(FATAL_ERROR "patches/patches.bin ausente — rode 'N64Recomp patches.toml' (codegen) antes do build Android")
endif()
add_custom_command(OUTPUT "${REPO_ROOT}/RecompiledPatches/patches_bin.c" "${REPO_ROOT}/RecompiledPatches/patches_bin.h"
    COMMAND file_to_c "${REPO_ROOT}/patches/patches.bin" bk_patches_bin
            "${REPO_ROOT}/RecompiledPatches/patches_bin.c" "${REPO_ROOT}/RecompiledPatches/patches_bin.h"
    DEPENDS "${REPO_ROOT}/patches/patches.bin"
    COMMENT "Gerando patches_bin.c via file_to_c do host")
target_sources(PatchesLib PRIVATE
    "${REPO_ROOT}/RecompiledPatches/patches.c"
    "${REPO_ROOT}/RecompiledPatches/patches_bin.c"
)
set_source_files_properties("${REPO_ROOT}/RecompiledPatches/patches.c" PROPERTIES COMPILE_FLAGS -fno-strict-aliasing)

# icon_bytes.c (mesmo fluxo do desktop: file_to_c do host)
set(ICON_GEN_DIR "${CMAKE_BINARY_DIR}/genicon")
file(MAKE_DIRECTORY "${ICON_GEN_DIR}")
add_custom_command(OUTPUT "${ICON_GEN_DIR}/icon_bytes.c" "${ICON_GEN_DIR}/icon_bytes.h"
    COMMAND file_to_c "${REPO_ROOT}/icons/app.png" icon_bytes
            "${ICON_GEN_DIR}/icon_bytes.c" "${ICON_GEN_DIR}/icon_bytes.h"
    DEPENDS "${REPO_ROOT}/icons/app.png"
    COMMENT "Gerando icon_bytes.c (host file_to_c)")

# ----------------------------------------------------------------------------
# 7) libmain.so — ponto de entrada SDL (SDLActivity -> System.loadLibrary("main"))
# ----------------------------------------------------------------------------
add_library(main SHARED)
set(MAIN_SOURCES
    "${REPO_ROOT}/src/main/launcher_animation.cpp"
    "${REPO_ROOT}/src/main/main.cpp"
    "${REPO_ROOT}/src/main/register_overlays.cpp"
    "${REPO_ROOT}/src/main/register_patches.cpp"
    "${REPO_ROOT}/src/main/theme.cpp"
    "${REPO_ROOT}/src/main/touch_input.cpp"

    "${REPO_ROOT}/src/game/config.cpp"
    "${REPO_ROOT}/src/game/debug.cpp"
    "${REPO_ROOT}/src/game/recomp_api.cpp"
    "${REPO_ROOT}/src/game/recomp_extension_api.cpp"
    "${REPO_ROOT}/src/game/recomp_data_api.cpp"
    "${REPO_ROOT}/src/game/rom_decompression.cpp"

    "${REPO_ROOT}/rsp/n_aspMain.cpp"

    "${ANDROID_PORT_DIR}/native/compat/android_paths.cpp"
    "${ANDROID_PORT_DIR}/native/compat/custom_driver.cpp"
)
target_sources(main PRIVATE ${MAIN_SOURCES} "${ICON_GEN_DIR}/icon_bytes.c")

target_include_directories(main PRIVATE
    "${REPO_ROOT}/include"
    "${REPO_ROOT}/src"
    "${NMR_DIR}/N64Recomp/include"
    "${NMR_DIR}/thirdparty/sse2neon"
    "${RT64_DIR}/src/contrib"
    "${RT64_DIR}/src/contrib/hlslpp/include"
    "${RT64_DIR}/src/contrib/dxc/inc"
    # Headers Vulkan para o probe do driver custom (custom_driver.cpp):
    # VkInstance + vkEnumeratePhysicalDevices para validar o Turnip instalado.
    "${RT64_DIR}/src/contrib/plume/contrib/Vulkan-Headers/include"
    "${RT64_DIR}/src"
    "${RT64_DIR}/src/rhi"
    "${RT64_DIR}/src/render"
    "${FRONTEND_DIR}/recompinput/include"
    "${FRONTEND_DIR}/recompui/include"
    "${FRONTEND_DIR}/recompui/src"
    "${RT64_DIR}/src/contrib/nativefiledialog-extended/src/include"
    "${FRONTEND_DIR}/lib/SlotMap"
    "${ANDROID_PORT_DIR}/native/compat"
    "${CMAKE_CURRENT_BINARY_DIR}"
    "${ICON_GEN_DIR}"
)

target_compile_options(main PRIVATE
    -fno-strict-aliasing
    -fms-extensions
    -Wno-unused-variable
    -Wno-unused-parameter
)

target_link_libraries(main PRIVATE
    PatchesLib
    RecompiledFuncs
    recompui
    recompinput
    librecomp
    ultramodern
    rt64
    nfd
    SDL2-static
    # NÃO linkamos 'vulkan' do NDK: com VK_NO_PROTOTYPES todas as chamadas vk*
    # passam pela tabela do volk (definida em plume_vulkan.cpp via
    # VOLK_IMPLEMENTATION), inicializada com o driver do sistema
    # (volkInitialize) ou com o Turnip (volkInitializeCustom via adrenotools).
    # Linkar a libvulkan.so do NDK esconderia chamadas que escaparam da tabela
    # — melhor virar erro de link e corrigir na fonte.
    adrenotools
    android
    log
    ${CMAKE_DL_LIBS}
)

# Alinhamento 16KB (Play/requisitos futuros; NDK r27)
target_link_options(main PRIVATE "-Wl,-z,max-page-size=16384")
