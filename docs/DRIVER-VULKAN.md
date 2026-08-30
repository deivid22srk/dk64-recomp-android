# Driver Vulkan customizável (Turnip via libadrenotools)

Este documento descreve **como foi implementado** o suporte a drivers Vulkan
customizáveis (Mesa **Turnip**) no port Android do DK64: Recompiled — o mesmo
mecanismo usado por emuladores como Vita3K, yuzu/sudachi e Skyline, e que
inspira os ports Android de recompilados com RT64.

---

## 1. Motivação: o bug do driver proprietário

Em dispositivos Qualcomm com drivers Adreno antigos (ex.: **moto g34 5G**,
Snapdragon 695, driver de vendor da branch `AU_LINUX_ANDROID_LA.UM.9.14` —
base Android 9 rodando sob Android 15), o jogo crashava logo após selecionar a
ROM, na thread `RT64 Present`:

```text
Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0
Cause: null pointer dereference
  #00 libvulkan.so (vulkan::driver::GetRefreshCycleDurationGOOGLE+36)
  #01..#03 libmain.so (RT64 — PresentQueue::threadLoop)
```

Causa raiz: no Android, a extensão `VK_GOOGLE_display_timing` é **emulada pelo
framework** (`libvulkan.so` do sistema implementa `vulkan::driver::…` em cima
do ANativeWindow/SurfaceFlinger) e é sempre reportada como suportada. O RT64
(plume) habilita a extensão e consulta a taxa real do display com
`vkGetRefreshCycleDurationGOOGLE`; a implementação do framework/driver
proprietário nesse dispositivo dereferencia um ponteiro nulo.

A solução definitiva não é contornar cada bug do driver proprietário, e sim
**permitir substituí-lo**: o driver Mesa **Turnip** (open source, para
Adreno a6xx/a7xx) implementa Vulkan moderno (e as extensões usadas pelo
RT64) de forma correta. Com o Turnip ativo, `VK_GOOGLE_display_timing`
funciona e o crash desaparece.

## 2. Como funciona o libadrenotools

O [libadrenotools](https://github.com/bylaws/libadrenotools) (bylaws) carrega
um driver Vulkan alternativo sem root. A chamada central é:

```c
void *adrenotools_open_libvulkan(
    int dlopenMode,          // RTLD_NOW
    int featureFlags,        // ADRENOTOOLS_DRIVER_CUSTOM | ADRENOTOOLS_DRIVER_FILE_REDIRECT
    const char *tmpLibDir,   // dir gravável (api < 29, sem memfd)
    const char *hookLibDir,  // DEVE ser o nativeLibraryDir do app
    const char *customDriverDir,  // diretório do driver extraído (barra final)
    const char *customDriverName, // soname do .so (ex.: "vulkan.ad07xx.so")
    const char *fileRedirectDir,  // cache de shaders do driver
    void **userMappingHandle);    // não usado (sem GPU mapping import)
```

Ele não "dlopen'a" o Turnip diretamente (o linker namespace do Android
bloquearia): em vez disso abre a **`libvulkan.so` do sistema dentro de um
namespace isolado com hooks ELF** (ByteHook/linkernsbypass) que desviam o
carregamento do driver ICI interno para o diretório do driver custom. O
resultado é um *handle* de `libvulkan.so` "envenenado": qualquer `VkInstance`
criado a partir dele usa o Turnip.

Requisitos atendidos pelo port:

| Requisito | Como atendemos |
|---|---|
| Hooks (`libmain_hook.so`, `libhook_impl.so`, `libfile_redirect_hook.so`) como arquivos reais em `nativeLibraryDir` | Os hooks são targets `SHARED` do mesmo build CMake; o Gradle empacota todo `.so` do diretório de saída e o `useLegacyPackaging = true` (build.gradle.kts) extrai para `nativeLibraryDir`. O CI verifica o conteúdo do APK |
| Driver em armazenamento **interno** do app (dlopen bloqueia sdcard) | Extração sempre em `filesDir/driver/installed/<id>/`; o nativo revalida o prefixo |
| `libadrenotools` só faz sentido em Adreno/arm64 | `abiFilters = arm64-v8a` (o CMake do adrenotools aborta em outra ABI) |

## 3. Arquitetura da integração

```text
SetupActivity (Kotlin)                    custom_driver.cpp (nativo)              plume (patch)
┌───────────────────────────┐             ┌───────────────────────────┐           ┌──────────────────────────┐
│ SAF: abrir .zip           │             │ Lê filesDir/driver/       │           │ VulkanInterface ctor:    │
│ 2× passada no zip:        │  selected.  │   selected.txt            │           │  dk64_adrenotools_get_   │
│  1) achar/ler meta.json   ├────────────>│ adrenotools_open_libvulkan├──────────>│  instance_proc_addr()    │
│  2) extrair p/ installed/ │    .txt     │  → handle da libvulkan    │ proc addr │  volkInitializeCustom(p) │
│ valida libraryName/minApi │             │  → dlsym vkGetInstance-   │  ou NULL  │  volkLoadInstance(inst)  │
│ grava selected.txt        │             │    ProcAddr               │           │ → TODAS as vk* via volk  │
└───────────────────────────┘             └───────────────────────────┘           └──────────────────────────┘
```

### 3.1 Kotlin — `SetupActivity.java` (seleção do .zip)

- Botões **“Instalar driver (.zip)…”** / **“Remover driver (voltar ao sistema)”**
  + status do driver ativo. O início do jogo passou a ser **manual**
  (botão **INICIAR JOGO**) para que esta tela continue acessível em todo
  launch — antes o handoff era automático e, num device com crash, o usuário
  jamais veria o seletor (loop de crash).
- O zip (formato do ecossistema adrenotools) pode ter os arquivos na raiz ou
  em subdiretório; o parser usa o **diretório do `meta.json`** como raiz e
  extrai tudo abaixo dele. Campos suportados do `meta.json`:
  `libraryName` (fallback: `library`), `name`, `driverVersion`, `vendor`,
  `author`, `minApi` (validado contra `Build.VERSION.SDK_INT`),
  `schemaVersion` (ignorado).
- Duas passadas no zip (`ZipInputStream`): 1ª localiza/lê o `meta.json`,
  2ª extrai. Limites anti zip-bomb: 128 entradas, 512 MB descompactados.
- Proteção **zip-slip**: rejeita `..`, caminhos absolutos e valida
  `canonicalPath` de cada arquivo extraído.
- Falha em qualquer validação → apaga o diretório parcial e reporta erro.
- Seleção gravada em `filesDir/driver/selected.txt` no formato `KEY=VALUE`
  (`dir=`, `library=`, `name=`) — mesmo formato que o nativo lê; instalar um
  novo driver limpa os anteriores; “Remover” apaga seleção, instalações e
  caches.

### 3.2 Nativo — `android/native/compat/custom_driver.cpp`

- Ponte em C puro (`extern "C"`, zero dependência de headers Vulkan):
  - `dk64_adrenotools_get_instance_proc_addr()` — carrega o driver
    (lazy, `std::once`) e devolve o `vkGetInstanceProcAddr` dele via
    `dlsym(handle, "vkGetInstanceProcAddr")`;
  - `dk64_adrenotools_custom_driver_active()` — 1 se o driver custom está em
    uso nesta execução.
- `hookLibDir` (`nativeLibraryDir`) é derivado de `/proc/self/maps`
  (diretório que contém `libmain.so`) — sem alterar o contrato de argv do
  SDL. `tmpLibDir` (`driver/tmp`) e `fileRedirectDir` (`driver/cache`,
  redirect de cache de shaders do Turnip) são criados sob o filesDir.
- Qualquer falha (sem seleção, diretório inválido, `adrenotools_open_libvulkan`
  nulo, `dlsym` vazio) → `NULL` e o jogo segue com o **driver do sistema**,
  exatamente como antes. Logs em `adb logcat -s DK64Recomp`.

### 3.3 Renderer — patch `android/patches/plume-android.patch`

O plume (camada Vulkan do RT64) usa **volk** com `VOLK_IMPLEMENTATION`
(compilado dentro de `plume_vulkan.cpp`). Três mudanças, todas confinadas a
`#if defined(__ANDROID__)`:

1. **Roteamento do driver** — no construtor de `VulkanInterface`, se a ponte
   devolver um proc addr, chamamos `volkInitializeCustom(proc)`, que inicializa
   a tabela global do volk com o `vkGetInstanceProcAddr` do Turnip; caso
   contrário, o `volkInitialize()` padrão (dlopen da `libvulkan.so` do
   sistema). Como `volkLoadInstance()` usa essa tabela, **todas** as chamadas
   `vk*` do binário (plume, RT64, imgui, VMA) passam pelo driver ativo.
2. **Surface pela instância ativa** — o caminho `PLUME_SDL_VULKAN_ENABLED`
   chamava `SDL_Vulkan_CreateSurface()`, que usa o **loader próprio do SDL**
   (a `libvulkan.so` do sistema) e criaria uma *surface* “cross-driver”
   incompatível com a instância do Turnip. No Android criamos a surface
   diretamente com o `vkCreateAndroidSurfaceKHR` da tabela do volk, obtendo o
   `ANativeWindow*` via `SDL_GetWindowWMInfo` (`SDL_SYSWM_ANDROID`).
3. **Guard do `VK_GOOGLE_display_timing`** — `capabilities.displayTiming` só
   fica habilitada com driver custom ativo (Turnip implementa a extensão de
   verdade); com o driver proprietário do sistema o RT64 fica no pacing pelo
   refresh rate do SDL, eliminando o `SIGSEGV` descrito na seção 1 mesmo
   **sem** instalar Turnip.

### 3.4 Build — `android/app/CMakeLists.txt`

- `VK_NO_PROTOTYPES` definido **globalmente**: as chamadas `vk*` deixam de
  linkar na `libvulkan.so` do NDK e passam a ser indireções pelos ponteiros do
  volk (definidos em `plume_vulkan.o`). O link explícito do `vulkan` do NDK
  foi removido de propósito — qualquer chamada que escapasse da tabela viraria
  **erro de link** visível, nunca um bug silencioso de driver misto.
- `add_subdirectory(lib/adrenotools)` (submodule) → lib estática `adrenotools`
  + hooks compartilhados empacotados no APK; `custom_driver.cpp` entra em
  `MAIN_SOURCES` e linka em `libmain.so`.

### 3.5 CI — `.github/workflows/build.yml`

- `lib/adrenotools` adicionado ao `git submodule update --init --recursive`.
- Novo passo **“Verificar conteúdo do APK”**: falha o build se
  `libmain_hook.so`/`libhook_impl.so`/`libfile_redirect_hook.so` não estiverem
  empacotados (protege o requisito de `nativeLibraryDir` do adrenotools).

## 4. Como usar (resumo para o usuário)

1. Baixe um driver Turnip no formato adrenotools — ex.: releases
   [K11MCH1/AdrenoToolsDrivers](https://github.com/K11MCH1/AdrenoToolsDrivers)
   (`Turnip_vX.Y.Z_R*.zip`; existem variantes `Gmem`/`Sysmem`).
2. Abra o app → **Instalar driver (.zip)…** → selecione o zip.
3. Toque em **INICIAR JOGO**. O driver entra em vigor neste início (o nativo
   lê a seleção quando o `SDL_main` sobe).
4. Para voltar ao driver proprietário: **Remover driver**.

Logs úteis: `adb logcat -s DK64Recomp` mostra
`custom driver: 'Mesa Turnip driver v26.0.0 - R8' ativo (via adrenotools)` em
caso de sucesso, ou o motivo da queda para o driver do sistema.

## 5. Limitações conhecidas

- Somente **Adreno** (a6xx/a7xx) em **arm64-v8a** — Turnip não existe para
  Mali/PowerVR; em outros GPUs o driver do sistema é usado normalmente.
- Android 10+ recomendado: abaixo da API 29 o adrenotools precisa do
  `tmpLibDir` gravável (suportado, mas memfd é o caminho preferencial).
- A variante do Turnip importa: se um build apresentar artefatos gráficos,
  teste a variante `Gmem` ou `Sysmem` mais recente.
- `ADRENOTOOLS_DRIVER_GPU_MAPPING_IMPORT` (importação de memória GPU) não é
  usado — o RT64 não precisa dele e ele adiciona requisitos de hooks extra.

## 6. Arquivos envolvidos

| Arquivo | Papel |
|---|---|
| `lib/adrenotools` (submodule, bylaws/libadrenotools @ 8fae8ce) | Carregamento do driver via hooks |
| `android/native/compat/custom_driver.h/.cpp` | Ponte nativa (seleção → proc addr) |
| `android/patches/plume-android.patch` | volkInitializeCustom + surface própria + guard displayTiming |
| `android/app/CMakeLists.txt` | `VK_NO_PROTOTYPES`, adrenotools, remoção do link `vulkan` |
| `android/app/src/main/java/…/SetupActivity.java` | Seletor do zip (SAF), extração, validação, status |
| `android/app/build.gradle.kts` | `useLegacyPackaging = true` (requisito dos hooks) |
| `.github/workflows/build.yml` | submodule novo + verificação de hooks no APK |
