/*
 * custom_driver.cpp — implementação da ponte libadrenotools (driver Turnip).
 *
 * Referências de uso do adrenotools_open_libvulkan(): Vita3K
 * (vita3k/util/src/android_driver.cpp) e yuzu/sudachi (GpuDriverHelper) —
 * parâmetros idênticos: RTLD_NOW, flags CUSTOM|FILE_REDIRECT, tmpLibDir
 * gravável, hookLibDir = nativeLibraryDir, dir do driver com barra final,
 * soname do .so (campo "libraryName"/"library" do meta.json do zip).
 *
 * Requisito do adrenotools: o app PRECISA empacotar os hooks
 * (libmain_hook.so/libhook_impl.so/libfile_redirect_hook.so) e extrair os
 * .so do APK (useLegacyPackaging = true, já configurado no build.gradle.kts),
 * pois eles são dlopen'ados de dentro de nativeLibraryDir.
 */
#include "custom_driver.h"

#ifdef __ANDROID__

#include <android/log.h>
#include <dlfcn.h>
#include <sys/stat.h>

#include <fstream>
#include <mutex>
#include <string>

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

struct CustomDriverState {
    bool attempted = false;
    bool active = false;
    void *proc_addr = nullptr;
};

CustomDriverState g_state;
std::once_flag g_once;

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

// nativeLibraryDir na prática é o diretório que contém libmain.so (o APK usa
// useLegacyPackaging=true, então os .so são extraídos para lá). Derivamos de
// /proc/self/maps para não precisar de um novo parâmetro no argv do Java.
bool find_native_library_dir(std::string &out) {
    std::ifstream maps("/proc/self/maps");
    if (!maps) return false;

    std::string line;
    while (std::getline(maps, line)) {
        const size_t pos = line.find("/libmain.so");
        if (pos == std::string::npos) continue;
        const size_t slash = line.rfind('/', pos);
        if (slash == std::string::npos) continue;
        out = line.substr(0, slash);
        return !out.empty();
    }
    return false;
}

void try_load_custom_driver() {
    g_state.attempted = true;

    const std::string &files = androidport::internal_files_dir();
    if (files.empty()) {
        ALOGI("custom driver: sem files dir, usando driver do sistema");
        return;
    }

    DriverSelection sel;
    if (!read_key_value_file(files + "/driver/selected.txt", sel)) {
        ALOGI("custom driver: nenhum driver selecionado, usando driver do sistema");
        return;
    }

    if (!is_safe_install_dir(sel.dir, files + "/driver/installed/")) {
        ALOGE("custom driver: dir inválido (%s), usando driver do sistema", sel.dir.c_str());
        return;
    }

    std::string hook_lib_dir;
    if (!find_native_library_dir(hook_lib_dir)) {
        ALOGE("custom driver: nativeLibraryDir não encontrado em /proc/self/maps");
        return;
    }

    // Diretórios graváveis exigidos pelo adrenotools/turnip.
    const std::string tmp_lib_dir = files + "/driver/tmp";
    const std::string file_redirect_dir = files + "/driver/cache";
    mkdir(tmp_lib_dir.c_str(), 0755);
    mkdir(file_redirect_dir.c_str(), 0755);

    // O adrenotools espera o diretório do driver com barra final.
    std::string driver_dir = sel.dir;
    if (driver_dir.back() != '/') driver_dir += '/';

    ALOGI("custom driver: carregando '%s' (lib=%s) de %s", sel.name.c_str(), sel.library.c_str(), driver_dir.c_str());

    void *handle = adrenotools_open_libvulkan(
        RTLD_NOW,
        ADRENOTOOLS_DRIVER_CUSTOM | ADRENOTOOLS_DRIVER_FILE_REDIRECT,
        tmp_lib_dir.c_str(),
        hook_lib_dir.c_str(),
        driver_dir.c_str(),
        sel.library.c_str(),
        file_redirect_dir.c_str(),
        nullptr);

    if (handle == nullptr) {
        ALOGE("custom driver: adrenotools_open_libvulkan falhou, usando driver do sistema");
        return;
    }

    void *proc = dlsym(handle, "vkGetInstanceProcAddr");
    if (proc == nullptr) {
        ALOGE("custom driver: vkGetInstanceProcAddr não encontrado, usando driver do sistema");
        return;
    }

    g_state.proc_addr = proc;
    g_state.active = true;
    ALOGI("custom driver: '%s' ativo (via adrenotools)", sel.name.c_str());
}

} // namespace

void *dk64_adrenotools_get_instance_proc_addr(void) {
    std::call_once(g_once, try_load_custom_driver);
    return g_state.proc_addr;
}

int dk64_adrenotools_custom_driver_active(void) {
    std::call_once(g_once, try_load_custom_driver);
    return g_state.active ? 1 : 0;
}

#else // !__ANDROID__

void *dk64_adrenotools_get_instance_proc_addr(void) { return nullptr; }
int dk64_adrenotools_custom_driver_active(void) { return 0; }

#endif // __ANDROID__
