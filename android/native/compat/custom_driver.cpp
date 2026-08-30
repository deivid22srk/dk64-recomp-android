/*
 * custom_driver.cpp — implementação da ponte libadrenotools (driver Turnip).
 *
 * Referências de uso do adrenotools_open_libvulkan(): Vita3K
 * (vita3k/util/src/android_driver.cpp), yuzu/sudachi (GpuDriverHelper) e o
 * port redahm-android (deivid22srk) — este último validado no moto g34 5G
 * (Adreno 619) e usado como base de paridade: paths vindo do Java,
 * hookLibDir = ApplicationInfo.nativeLibraryDir (exigência documentada do
 * adrenotools), flags apenas ADRENOTOOLS_DRIVER_CUSTOM (Turnip/kgsl não
 * precisa de FILE_REDIRECT — um hook a menos, um modo de falha a menos) e
 * importação que tolera zips Winlator/WN-Turnip sem meta.json.
 *
 * Requisito do adrenotools: o app PRECISA empacotar os hooks
 * (libmain_hook.so/libhook_impl.so/libfile_redirect_hook.so) e extrair os
 * .so do APK (useLegacyPackaging = true, já configurado no build.gradle.kts),
 * pois eles são dlopen'ados de dentro de nativeLibraryDir.
 *
 * Além do carregamento, este arquivo implementa o PROBE de validação: uma
 * VkInstance é criada com o driver e os dispositivos físicos são enumerados.
 * Se nenhum dispositivo aparecer (build sem suporte à GPU do aparelho), o
 * driver é descartado e o jogo usa o driver do sistema — e o SetupActivity
 * recusa o driver com mensagem clara via JNI (nativeProbeCustomDriver).
 *
 * BUG CORRIGIDO (2º release do driver): o probe roda na SetupActivity via
 * JNI, ANTES do main() do jogo. internal_files_dir() só é preenchido por
 * init_from_args() (argv do SDL), que ainda não executou nesse momento —
 * com o files dir vazio, selected.txt nunca era encontrado, o nativo nem
 * tentava carregar o driver e o probe devolvia "driver não carregado" em
 * ~1 ms, rejeitando QUALQUER driver (rollback automático no Java). Correção:
 * o JNI agora recebe filesDir e nativeLibraryDir do Java (set_runtime_paths)
 * e funciona tanto no Setup quanto no jogo (mesmo processo).
 *
 * BUG CORRIGIDO (3º release): o probe pedia as PFNs de nível de instância
 * (vkEnumeratePhysicalDevices etc.) com instância VK_NULL_HANDLE. O contrato
 * da API Vulkan só permite nullptr para funções globais; o loader do sistema
 * (que é o que o adrenotools devolve — um dlopen_unique de libvulkan.so com
 * os hooks aplicados) recusa as demais e retorna nullptr, então o probe
 * abortava ANTES da vkCreateInstance e descartava o driver recém-carregado.
 * Correção: vkCreateInstance com nullptr; as demais PFNs obtidas com a
 * instância recém-criada (ver run_probe_locked).
 */
#include "custom_driver.h"

#ifdef __ANDROID__

#include <android/log.h>
#include <cerrno>
#include <dlfcn.h>
#include <jni.h>
#include <sys/stat.h>
#include <unistd.h>

#include <cstdio>
#include <cstring>
#include <fstream>
#include <mutex>
#include <string>
#include <vector>

#include <vulkan/vulkan.h>

#include <adrenotools/driver.h>

#include "android_paths.h"

namespace {

#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, "DK64Recomp", __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, "DK64Recomp", __VA_ARGS__)

struct DriverSelection {
    std::string dir;     // <files>/driver/installed/<id> (sem barra final)
    std::string library; // soname do .so do driver (ex.: vulkan.ad07xx.so)
    std::string name;    // nome amigável (campo "name" do meta.json)
};

// Resultado do probe (ver run_probe_locked).
struct ProbeResult {
    bool ok = false;
    int deviceCount = 0;
    std::string firstName;
    std::string apiVersion;
    std::string error;
};

struct CustomDriverState {
    bool attempted = false;
    bool active = false;
    void *proc_addr = nullptr;
    // Impressão digital da seleção JÁ RESOLVIDA (dir + library). Se o
    // selected.txt mudar (usuário instalou outro driver na mesma sessão),
    // recarregamos do zero — sem isso, o call_once original devolveria o
    // resultado em cache do driver antigo após a troca.
    std::string fingerprint;
};

CustomDriverState g_state;
std::mutex g_stateMutex;
ProbeResult g_probe;

bool read_key_value_file(const std::string &path, DriverSelection &out) {
    std::ifstream file(path);
    if (!file) return false;

    std::string line;
    while (std::getline(file, line)) {
        if (!line.empty() && line.back() == '\r') line.pop_back();
        const size_t eq = line.find('=');
        if (eq == std::string::npos) continue;
        const std::string key = line.substr(0, eq);
        const std::string value = line.substr(eq + 1);
        if (value.empty()) continue;
        if (key == "dir") out.dir = value;
        else if (key == "library") out.library = value;
        else if (key == "name") out.name = value;
    }
    return !out.dir.empty() && !out.library.empty();
}

bool is_safe_install_dir(const std::string &dir, const std::string &base) {
    // O driver PRECISA estar no armazenamento interno do app (dlopen bloqueia
    // .so em armazenamento externo/sdcard). Garante prefixo + sem "..".
    if (dir.compare(0, base.size(), base) != 0) return false;
    if (dir.find("..") != std::string::npos) return false;
    return dir.size() > base.size();
}

/*
 * nativeLibraryDir na prática é o diretório que contém libmain.so (o APK usa
 * useLegacyPackaging=true, então os .so são extraídos para lá). Derivamos de
 * /proc/self/maps para não precisar de um novo parâmetro no argv do Java.
 *
 * CORREÇÃO IMPORTANTE (bug do 1º release do driver Turnip): uma linha de
 * /proc/self/maps tem a forma
 *   "744e00e000-744f0d4000 r-xp 00000000 fe:00 3397824  /data/app/.../lib/arm64/libmain.so"
 * A versão anterior recortava a partir do último '/' ANTES de "/libmain.so",
 * mantendo TODAS as colunas de endereço no resultado:
 *   "744e00e000-744f0d4000 r-xp 00000000 fe:00 3397824  /data/app/.../lib/arm64"
 * O adrenotools usa esse valor como ld_library_path/default_library_path do
 * namespace do linker, que faz split por ':' — e o device "fe:00" contém
 * DOIS PONTOS: as duas metades viram caminhos não absolutos e são descartadas
 * ("normalize_path - invalid input ... ignoring" no logcat). Com isso o
 * preload de libhook_impl.so dentro do namespace do driver falha
 * silenciosamente (hook_impl.cpp retorna nullptr sem log), NENHUM driver
 * Vulkan é carregado e o RT64 falha com "Unable to find compatible graphics
 * device" (0 dispositivos físicos).
 */
bool find_native_library_dir(std::string &out) {
    std::ifstream maps("/proc/self/maps");
    if (!maps) return false;

    static const char kSuffix[] = "/libmain.so";

    std::string line;
    while (std::getline(maps, line)) {
        // A coluna do pathname começa no primeiro '/' da linha (depois de
        // endereço/permissões/offset/device/inode).
        const size_t pathStart = line.find('/');
        if (pathStart == std::string::npos) continue;
        const std::string path = line.substr(pathStart);

        // Ignora mapeamentos embutidos no APK ("base.apk!/lib/..."): não são
        // diretórios reais de filesystem e o dlopen neles falha.
        if (path.find("!/") != std::string::npos) continue;

        // Só aceita mapeamentos de arquivo que terminam exatamente em /libmain.so.
        const size_t suffixLen = std::strlen(kSuffix);
        if (path.length() <= suffixLen) continue;
        if (path.compare(path.length() - suffixLen, suffixLen, kSuffix) != 0) continue;

        std::string dir = path.substr(0, path.length() - suffixLen); // com '/' final
        if (dir.empty() || dir.back() != '/') continue;
        dir.pop_back(); // sem a barra final

        // Sanidade: é NATIVELIBRARYDIR que o adrenotools precisa (hookLibDir) —
        // exigimos os hooks empacotados ali antes de tentar qualquer load.
        struct stat st;
        if (stat((dir + "/libmain_hook.so").c_str(), &st) != 0 ||
            stat((dir + "/libhook_impl.so").c_str(), &st) != 0) {
            ALOGE("custom driver: hooks ausentes em '%s' (APK sem useLegacyPackaging?) — "
                  "usando driver do sistema", dir.c_str());
            continue;
        }

        out = dir;
        return true;
    }
    return false;
}

// ---------------------------------------------------------------------------
// Probe (pré-voo) do driver: valida empiricamente o driver carregado criando
// uma VkInstance e enumerando os dispositivos físicos. É o mesmo caminho que
// o RT64/plume executará; se não listar nenhuma GPU, o jogo certamente
// falharia com "Unable to find compatible graphics device".
// ---------------------------------------------------------------------------

static std::string format_vk_version(uint32_t v) {
    char buf[32];
    std::snprintf(buf, sizeof(buf), "%u.%u.%u",
                  (unsigned)VK_API_VERSION_MAJOR(v),
                  (unsigned)VK_API_VERSION_MINOR(v),
                  (unsigned)VK_API_VERSION_PATCH(v));
    return buf;
}

static void run_probe_locked() {
    g_probe = ProbeResult{};
    g_probe.ok = false;
    if (g_state.proc_addr == nullptr) {
        g_probe.error = "driver não carregado";
        return;
    }

    // Usamos PFNs diretas do vkGetInstanceProcAddr do driver (sem tocar na
    // tabela global do volk — o plume inicializa a própria mais tarde).
    //
    // CONTRATO DA API VULKAN (bug do 3º release — "entry points Vulkan
    // ausentes no driver"): apenas funções GLOBAIS podem ser obtidas com
    // instância VK_NULL_HANDLE (vkCreateInstance, vkEnumerateInstance*,
    // vkGetInstanceProcAddr). Para funções de NÍVEL DE INSTÂNCIA
    // (vkEnumeratePhysicalDevices, vkGetPhysicalDeviceProperties,
    // vkDestroyInstance) o loader EXIGE uma VkInstance válida — o loader do
    // sistema registra "invalid vkGetInstanceProcAddr(VK_NULL_HANDLE, ...)
    // call" no logcat e devolve nullptr. A versão anterior pedia TODAS as
    // PFNs com nullptr ANTES de criar a instância: ela própria abortava aí e
    // o driver — mesmo carregado e funcional (adrenotools ativo) — era
    // descartado sem que nenhuma vkCreateInstance chegasse a rodar.
    auto gipa = reinterpret_cast<PFN_vkGetInstanceProcAddr>(g_state.proc_addr);
    auto pfnCreateInstance = reinterpret_cast<PFN_vkCreateInstance>(gipa(nullptr, "vkCreateInstance"));
    if (pfnCreateInstance == nullptr) {
        g_probe.error = "vkCreateInstance ausente no driver";
        return;
    }

    VkApplicationInfo appInfo{};
    appInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    appInfo.pApplicationName = "dk64recomp-driver-probe";
    appInfo.applicationVersion = VK_MAKE_VERSION(1, 0, 0);
    appInfo.apiVersion = VK_API_VERSION_1_1;

    VkInstanceCreateInfo ci{};
    ci.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    ci.pApplicationInfo = &appInfo;

    VkInstance instance = VK_NULL_HANDLE;
    VkResult res = pfnCreateInstance(&ci, nullptr, &instance);
    if (res != VK_SUCCESS || instance == VK_NULL_HANDLE) {
        char buf[96];
        std::snprintf(buf, sizeof(buf), "vkCreateInstance falhou (VkResult %d)", (int)res);
        g_probe.error = buf;
        return;
    }

    // PFNs de nível de instância — obtidas AGORA, com a instância válida
    // (exatamente o que o logcat do 3º release mostrou estar errado).
    auto pfnEnumDevices = reinterpret_cast<PFN_vkEnumeratePhysicalDevices>(gipa(instance, "vkEnumeratePhysicalDevices"));
    auto pfnGetProps = reinterpret_cast<PFN_vkGetPhysicalDeviceProperties>(gipa(instance, "vkGetPhysicalDeviceProperties"));
    auto pfnDestroyInstance = reinterpret_cast<PFN_vkDestroyInstance>(gipa(instance, "vkDestroyInstance"));
    if (pfnEnumDevices == nullptr || pfnGetProps == nullptr || pfnDestroyInstance == nullptr) {
        g_probe.error = "entry points Vulkan ausentes no driver";
        if (pfnDestroyInstance != nullptr) pfnDestroyInstance(instance, nullptr);
        return;
    }

    uint32_t count = 0;
    res = pfnEnumDevices(instance, &count, nullptr);
    if (res != VK_SUCCESS) {
        char buf[96];
        std::snprintf(buf, sizeof(buf), "vkEnumeratePhysicalDevices falhou (VkResult %d)", (int)res);
        g_probe.error = buf;
        pfnDestroyInstance(instance, nullptr);
        return;
    }

    g_probe.deviceCount = (int)count;
    if (count == 0) {
        g_probe.error = "nenhuma GPU Vulkan exposta pelo driver "
                        "(build sem suporte à geração da GPU deste aparelho?)";
    } else {
        std::vector<VkPhysicalDevice> devs(count);
        if (pfnEnumDevices(instance, &count, devs.data()) == VK_SUCCESS) {
            for (uint32_t i = 0; i < count; i++) {
                VkPhysicalDeviceProperties p{};
                pfnGetProps(devs[i], &p);
                ALOGI("custom driver probe: device[%u] = '%s' (API Vulkan %s, vendor 0x%X, id 0x%X)",
                      i, p.deviceName, format_vk_version(p.apiVersion).c_str(), p.vendorID, p.deviceID);
                if (i == 0) {
                    g_probe.firstName = p.deviceName;
                    g_probe.apiVersion = format_vk_version(p.apiVersion);
                }
            }
            g_probe.ok = true;
        } else {
            g_probe.error = "vkEnumeratePhysicalDevices falhou no 2º passo";
        }
    }

    pfnDestroyInstance(instance, nullptr);
}

// O probe é executado no fim de ensure_loaded_locked(); g_probe fica sempre
// consistente com a última seleção resolvida.

static std::string json_escape(const std::string &s) {
    std::string out;
    out.reserve(s.size() + 8);
    for (char c : s) {
        switch (c) {
        case '"': out += "\\\""; break;
        case '\\': out += "\\\\"; break;
        case '\n': out += "\\n"; break;
        case '\r': out += "\\r"; break;
        case '\t': out += "\\t"; break;
        default:
            if (static_cast<unsigned char>(c) < 0x20) {
                char buf[8];
                std::snprintf(buf, sizeof(buf), "\\u%04x", c);
                out += buf;
            } else {
                out += c;
            }
        }
    }
    return out;
}

/*
 * Garante que g_state reflita o CONTEÚDO ATUAL de selected.txt, recarregando
 * (com probe) quando a impressão digital da seleção mudar. PRECISA ser
 * chamado com g_stateMutex retido. Todas as entradas públicas passam por aqui,
 * então trocar o driver no Setup na mesma sessão do processo funciona.
 */
void ensure_loaded_locked() {
    const std::string &files = androidport::internal_files_dir();

    DriverSelection sel;
    bool hasSelection = false;
    std::string fingerprint;
    if (!files.empty()) {
        if (read_key_value_file(files + "/driver/selected.txt", sel)) {
            if (is_safe_install_dir(sel.dir, files + "/driver/installed/")) {
                hasSelection = true;
                fingerprint = sel.dir + '\x1f' + sel.library;
            } else {
                ALOGE("custom driver: dir inválido (%s), ignorando seleção", sel.dir.c_str());
            }
        }
    }

    if (g_state.attempted && g_state.fingerprint == fingerprint) {
        return; // seleção já resolvida (inclui o caso "nenhuma")
    }

    // (Re)carrega do zero para a nova seleção.
    g_state = CustomDriverState{};
    g_probe = ProbeResult{};
    g_state.attempted = true;
    g_state.fingerprint = fingerprint;

    if (!hasSelection) {
        ALOGI("custom driver: nenhum driver selecionado, usando driver do sistema");
        run_probe_locked();
        return;
    }

    /*
     * hookLibDir: o adrenotools EXIGE exatamente ApplicationInfo
     * .nativeLibraryDir. Preferimos o valor injetado pelo Java via JNI
     * (set_runtime_paths — paridade com redahm-android); o scan de
     * /proc/self/maps continua como fallback para o caso do probe ser
     * chamado sem os paths (ou de um contexto sem JNI).
     */
    std::string hook_lib_dir;
    const std::string &java_nld = androidport::native_library_dir();
    if (!java_nld.empty()) {
        struct stat stHook;
        if (stat((java_nld + "/libmain_hook.so").c_str(), &stHook) == 0 &&
            stat((java_nld + "/libhook_impl.so").c_str(), &stHook) == 0) {
            hook_lib_dir = java_nld;
        } else {
            ALOGE("custom driver: hooks ausentes em nativeLibraryDir='%s' "
                  "(APK sem useLegacyPackaging?) — tentando /proc/self/maps",
                  java_nld.c_str());
        }
    }
    if (hook_lib_dir.empty() && !find_native_library_dir(hook_lib_dir)) {
        ALOGE("custom driver: nativeLibraryDir não resolvido (JNI vazio + "
              "libmain.so ausente em /proc/self/maps)");
        return;
    }

    // Diretório gravável exigido pelo adrenotools (só usado em API < 29,
    // onde não há memfd; mantido por segurança).
    const std::string tmp_lib_dir = files + "/driver/tmp";
    mkdir(tmp_lib_dir.c_str(), 0755);

    // O adrenotools espera o diretório do driver com barra final.
    std::string driver_dir = sel.dir;
    if (driver_dir.back() != '/') driver_dir += '/';

    /*
     * Pré-validação (espelha as checagens internas do adrenotools, que falham
     * retornando nullptr SEM nenhum log): stat do arquivo exato que ele vai
     * abrir. Sem isso um caminho errado vira "driver não carregado" sem
     * nenhuma pista no logcat.
     */
    const std::string driver_file = driver_dir + sel.library;
    struct stat stDriver;
    if (stat(driver_file.c_str(), &stDriver) != 0) {
        ALOGE("custom driver: ARQUIVO DO DRIVER INEXISTENTE: '%s' (errno=%d) — "
              "instalação corrompida ou soname errado; abortando",
              driver_file.c_str(), errno);
    }

    ALOGI("custom driver: carregando '%s' (lib=%s) de %s | hookLibDir=%s | "
          "flags=CUSTOM", sel.name.c_str(), sel.library.c_str(),
          driver_dir.c_str(), hook_lib_dir.c_str());

    // Paridade redahm-android: apenas ADRENOTOOLS_DRIVER_CUSTOM. O Turnip
    // usa kgsl (/dev/kgsl-3d0) e não precisa do redirect de fopen; o hook
    // extra (libfile_redirect_hook.so) no namespace do driver era um modo
    // de falha sem benefício.
    void *handle = adrenotools_open_libvulkan(
        RTLD_NOW,
        ADRENOTOOLS_DRIVER_CUSTOM,
        tmp_lib_dir.c_str(),
        hook_lib_dir.c_str(),
        driver_dir.c_str(),
        sel.library.c_str(),
        nullptr,
        nullptr);

    if (handle == nullptr) {
        ALOGE("custom driver: adrenotools_open_libvulkan falhou, usando driver do sistema "
              "(hookLibDir='%s')", hook_lib_dir.c_str());
        run_probe_locked();
        return;
    }

    void *proc = dlsym(handle, "vkGetInstanceProcAddr");
    if (proc == nullptr) {
        ALOGE("custom driver: vkGetInstanceProcAddr não encontrado, usando driver do sistema");
        run_probe_locked();
        return;
    }

    g_state.proc_addr = proc;
    g_state.active = true;
    ALOGI("custom driver: '%s' ativo (via adrenotools), hookLibDir=%s", sel.name.c_str(), hook_lib_dir.c_str());

    // Pré-voo (probe): cria uma VkInstance com o driver carregado e enumera os
    // dispositivos físicos — exatamente o que o RT64 fará em seguida. Se o
    // driver não expuser NENHUMA GPU (build sem suporte à geração da GPU do
    // aparelho, ex.: build a7xx/a8xx num Adreno 619), descartamos o driver
    // aqui e o plume cai no driver do sistema com o display timing desligado,
    // em vez de falhar com "Unable to find compatible graphics device".
    run_probe_locked();
    if (!g_probe.ok) {
        ALOGE("custom driver: '%s' DESCARTADO — probe não expôs GPU Vulkan (%s). "
              "Usando driver do sistema. Instale um build Turnip compatível com a "
              "GPU do aparelho (para Adreno 6xx use builds 'a6xx', ex.: "
              "K11MCH1/AdrenoToolsDrivers).", sel.name.c_str(), g_probe.error.c_str());
        g_state.proc_addr = nullptr;
        g_state.active = false;
        return;
    }

    ALOGI("custom driver: probe OK — %d dispositivo(s) Vulkan, GPU '%s' (API Vulkan %s)",
          g_probe.deviceCount, g_probe.firstName.c_str(), g_probe.apiVersion.c_str());
}

} // namespace

void *dk64_adrenotools_get_instance_proc_addr(void) {
    std::lock_guard<std::mutex> lock(g_stateMutex);
    ensure_loaded_locked();
    return g_state.proc_addr;
}

int dk64_adrenotools_custom_driver_active(void) {
    std::lock_guard<std::mutex> lock(g_stateMutex);
    ensure_loaded_locked();
    return g_state.active ? 1 : 0;
}

/*
 * JNI — chamado pelo SetupActivity logo após instalar/selecionar um driver
 * (e disponível para diagnóstico). Recebe os paths do Java (getFilesDir e
 * ApplicationInfo.nativeLibraryDir) e os injeta via set_runtime_paths ANTES
 * de qualquer uso — sem isso internal_files_dir() estaria vazio aqui, pois
 * init_from_args() só roda no main() do jogo (bug do 2º release: o probe
 * devolvia "driver não carregado" para QUALQUER driver em ~1 ms).
 *
 * Devolve um JSON:
 *
 *   {"active":true,"ok":true,"devices":1,
 *    "device":"Adreno (TM) 619","api":"1.3.280","error":""}
 *   {"active":true,"ok":false,"devices":0,"device":"","api":"",
 *    "error":"nenhuma GPU Vulkan exposta pelo driver (...)"}   <- driver sem suporte à GPU
 *   {"active":false,...,"error":"driver não carregado"}        <- falha no carregamento
 *
 * O SetupActivity usa isso para RECUSAR drivers incompatíveis (ex.: build
 * a7xx/a8xx num Adreno 619/a6xx) com uma mensagem clara, em vez de deixar o
 * jogo falhar com "Unable to find compatible graphics device".
 */
extern "C" JNIEXPORT jstring JNICALL
Java_com_deivid22srk_dk64recomp_SetupActivity_nativeProbeCustomDriver(JNIEnv *env, jclass /*clazz*/,
                                                                      jstring jFilesDir,
                                                                      jstring jNativeLibDir) {
    const char *files = jFilesDir ? env->GetStringUTFChars(jFilesDir, nullptr) : nullptr;
    const char *nld = jNativeLibDir ? env->GetStringUTFChars(jNativeLibDir, nullptr) : nullptr;
    androidport::set_runtime_paths(files, nld);
    if (files) env->ReleaseStringUTFChars(jFilesDir, files);
    if (nld) env->ReleaseStringUTFChars(jNativeLibDir, nld);

    std::lock_guard<std::mutex> lock(g_stateMutex);
    ensure_loaded_locked();

    std::string json = "{";
    json += "\"active\":" + std::string(g_state.active ? "true" : "false");
    json += ",\"ok\":" + std::string(g_probe.ok ? "true" : "false");
    json += ",\"devices\":" + std::to_string(g_probe.deviceCount);
    json += ",\"device\":\"" + json_escape(g_probe.firstName) + "\"";
    json += ",\"api\":\"" + json_escape(g_probe.apiVersion) + "\"";
    json += ",\"error\":\"" + json_escape(g_probe.error) + "\"";
    json += "}";

    return env->NewStringUTF(json.c_str());
}

#else // !__ANDROID__

void *dk64_adrenotools_get_instance_proc_addr(void) { return nullptr; }
int dk64_adrenotools_custom_driver_active(void) { return 0; }

#endif // __ANDROID__
