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

## 2.1 Bug real encontrado em campo: "Unable to find compatible graphics device"

No 1º release com o seletor (moto g34 5G, Android 15), instalar o Turnip e
iniciar o jogo produzia a caixa **"Unable to find compatible graphics
device"** — o `hook` reportava `custom driver ativo`, mas o RT64 não
encontrava NENHUM dispositivo Vulkan.

Causa raiz (diagnosticada pelo logcat): o `nativeLibraryDir` era derivado de
`/proc/self/maps` recortando a linha no último `/` antes de `libmain.so`, o
que **manteve as colunas de endereço da linha no caminho**:

```text
"744e00e000-744f0d4000 r-xp 00000000 fe:00 3397824  /data/app/.../lib/arm64"
└────────── colunas de /proc/self/maps ──────────┘└─────── dir real ───────┘
```

O adrenotools usa esse valor como caminho de busca (`ld_library_path` /
`default_library_path`) do namespace do linker, que faz split por `:` — e o
campo device (`fe:00`) contém **dois pontos**: as duas metades viram caminhos
não absolutos e são descartadas (`normalize_path - invalid input ... ignoring`
no logcat). Consequência: o *preload* de `libhook_impl.so` dentro do namespace
do driver falha **silenciosamente** (`hook_impl.cpp` retorna `nullptr` sem
log), nenhum ICD é carregado e `vkEnumeratePhysicalDevices` devolve 0
dispositivos.

Correções aplicadas:

1. **Parsing correto** (`find_native_library_dir`): o pathname começa no
   primeiro `/` da linha; só são aceitos mapeamentos de arquivo que terminam
   exatamente em `/libmain.so` (excluindo os embutidos `base.apk!/...`), e o
   diretório resultante é validado com `stat` exigindo os hooks
   (`libmain_hook.so`, `libhook_impl.so`) antes de qualquer load.
2. **Probe de pré-voo** (seção 3.6): valida empiricamente o driver antes do
   jogo — se ele não expor GPU, é descartado automaticamente (fallback para o
   driver do sistema) e o Setup recusa a instalação com mensagem clara.
3. **stderr → logcat** (seção 3.7): mensagens `fprintf(stderr)` do plume/RT64
   (ex.: `Unable to find devices that support Vulkan.`, `Missing required
   extension: ...`) agora aparecem no logcat — antes sumiam em `/dev/null` em
   builds release, o que tornou este diagnóstico impossível no 1º round.

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

- Ponte em C (`extern "C"`):
  - `dk64_adrenotools_get_instance_proc_addr()` — resolve a seleção atual e
    devolve o `vkGetInstanceProcAddr` do driver via
    `dlsym(handle, "vkGetInstanceProcAddr")`;
  - `dk64_adrenotools_custom_driver_active()` — 1 se o driver custom está em
    uso nesta execução (usado pelo guard do display timing no plume).
- **Resolução por fingerprint**: a seleção (`selected.txt`) é relida em cada
  consulta e comparada à impressão digital já resolvida (`dir + library`);
  mudou, recarrega do zero (incluindo probe). Assim trocar de driver no Setup
  na mesma sessão do processo funciona — sem isso o resultado antigo ficaria
  em cache (`call_once` do 1º design).
- `hookLibDir` (`nativeLibraryDir`) é derivado de `/proc/self/maps` com o
  parsing corrigido (seção 2.1) e validado por `stat` dos hooks.
  `tmpLibDir` (`driver/tmp`) e `fileRedirectDir` (`driver/cache`, cache de
  shaders do Turnip) são criados sob o filesDir.
- Qualquer falha (sem seleção, diretório inválido, `adrenotools_open_libvulkan`
  nulo, `dlsym` vazio, probe sem GPU) → `NULL` e o jogo segue com o **driver
  do sistema**. Logs em `adb logcat -s DK64Recomp`.

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

### 3.6 Validação do driver (probe Vulkan)

Instalar um build Turnip que **não suporta a GPU do aparelho** (ex.: builds
`a7xx`/`a8xx` num Adreno 619/a6xx — os zips são por geração!) fazia o jogo
falhar lá na frente com "Unable to find compatible graphics device". Hoje o
driver é **testado antes de entrar em vigor**:

1. `run_probe_locked()` (custom_driver.cpp) cria uma `VkInstance` com o
   `vkGetInstanceProcAddr` do driver carregado (PFNs diretos, sem tocar na
   tabela global do volk) e chama `vkEnumeratePhysicalDevices` — exatamente o
   caminho que o RT64 executará.
2. **0 dispositivos** (ou `vkCreateInstance` falho) → o driver é **descartado**
   no load do jogo (fallback para o driver do sistema, com o display timing
   desligado) e o SetupActivity **recusa a instalação** com rollback da
   seleção.
3. Probe OK → o nome da GPU e a versão da API aparecem na UI ("Driver
   verificado: Adreno (TM) 619 (Vulkan 1.3.x)") e no logcat.

A JNI `SetupActivity.nativeProbeCustomDriver()` (mesmo libmain.so do jogo,
carregado no `static {}` do Setup) devolve o resultado em JSON:
`{"active":bool,"ok":bool,"devices":int,"device":str,"api":str,"error":str}`.
O probe roda uma vez por seleção (fingerprint), então instalar outro driver
na mesma sessão reavalia tudo.

### 3.7 Diagnóstico: stderr → logcat

O plume/RT64 reportam falhas de vídeo via `fprintf(stderr)` — invisível no
logcat de builds release. `androidport::redirect_stderr_to_logcat()`
(android_paths.cpp, chamada no início do `SDL_main`) cria um `pipe`, aponta o
`stderr` do processo para ele (`dup2`) e uma thread leitora repassa cada linha
para o logcat na tag **`DK64Recomp-stderr`** (nível WARN). Com isso, falhas
como `Unable to find devices that support Vulkan.` / `Missing required
extension: X` ficam visíveis em `adb logcat -s DK64Recomp-stderr`.

## 4. Como usar (resumo para o usuário)

1. Baixe um driver Turnip no formato adrenotools — ex.: releases
   [K11MCH1/AdrenoToolsDrivers](https://github.com/K11MCH1/AdrenoToolsDrivers)
   (`Turnip_vX.Y.Z_R*.zip`; existem variantes `Gmem`/`Sysmem`).
   **Escolha a pasta da geração da sua GPU**: Adreno 6xx (ex.: Adreno 619 do
   moto g34 5G) → builds **a6xx**; Adreno 7xx → **a7xx**. Builds de outra
   geração são recusados automaticamente pelo app.
2. Abra o app → **Instalar driver (.zip)…** → selecione o zip. O app instala,
   carrega e **testa** o driver: se listar a GPU (ex.: "Driver verificado:
   Adreno (TM) 619"), está pronto; se não listar nenhuma GPU Vulkan, a
   instalação é recusada com instruções.
3. Toque em **INICIAR JOGO**. O driver entra em vigor neste início (o nativo
   lê a seleção quando o `SDL_main` sobe).
4. Para voltar ao driver proprietário: **Remover driver**.

Logs úteis:

```bash
adb logcat -s DK64Recomp DK64Recomp-stderr hook_impl
```

Sucesso: `custom driver: '...' ativo (via adrenotools)` seguido de
`custom driver: probe OK — N dispositivo(s) Vulkan, GPU '...'`. Recusa:
`custom driver: '...' DESCARTADO — probe não expôs GPU Vulkan (...)`.
Falhas do RT64/plume aparecem na tag `DK64Recomp-stderr`.

## 5. Limitações conhecidas

- Somente **Adreno** em **arm64-v8a** — Turnip não existe para Mali/PowerVR;
  em outros GPUs o driver do sistema é usado normalmente.
- **Os zips de Turnip são por geração de GPU** (`vulkan.ad06xx.so` = a6xx,
  `vulkan.ad07xx.so` = a7xx, `vulkan.ad08xx.so` = a8xx; builds antigos usavam
  `libvulkan_freedreno.so`): instalar a geração errada não “quebra” o app
  hoje — o probe recusa — mas também não acelera nada. Consulte a geração do
  seu Adreno antes de baixar.
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
