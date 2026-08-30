/*
 * android_paths.cpp — resolução de caminhos para o port Android.
 *
 * Contrato de argv com o SDL 2.30.3: SDL_android.c (nativeRunMain) monta
 * argv[0]="app_process" e copia o array retornado por getArguments() do
 * MainActivity a partir de argv[1] ("arguments AFTER the application name"):
 *   argv[1] = internal files dir  (filesDir)
 *   argv[2] = external files dir  (getExternalFilesDir(null))
 * Esses valores ficam em globais e são consumidos pelo patch do RecompFrontend
 * (recompui::file::get_app_folder_path/get_program_path) e pelo scan de ROM.
 */
#include "android_paths.h"

#include <android/log.h>
#include <dirent.h>
#include <sys/stat.h>

#include <algorithm>
#include <cctype>
#include <cstring>

namespace androidport {

namespace {
std::string g_internal;
std::string g_external;

#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, "DK64Recomp", __VA_ARGS__)

bool ends_with_ci(const std::string& s, const char* suffix) {
    const size_t n = std::strlen(suffix);
    if (s.size() < n) return false;
    for (size_t i = 0; i < n; ++i) {
        if (std::tolower(static_cast<unsigned char>(s[s.size() - n + i])) !=
            std::tolower(static_cast<unsigned char>(suffix[i]))) {
            return false;
        }
    }
    return true;
}
} // namespace

const std::string& internal_files_dir() { return g_internal; }
const std::string& external_files_dir() { return g_external; }

void init_from_args(int argc, char** argv) {
    if (argc > 1 && argv[1] && argv[1][0]) g_internal = argv[1];
    if (argc > 2 && argv[2] && argv[2][0]) g_external = argv[2];
    if (g_internal.empty()) g_internal = ".";
    if (g_external.empty()) g_external = g_internal;
    ALOGI("paths: internal=%s external=%s", g_internal.c_str(), g_external.c_str());
}

// Busca por uma ROM compatível (.z64/.n64/.v64) nos diretórios conhecidos.
// Retorna true e preenche `out` com o primeiro match (ordem: interno, externo).
bool find_rom_file(std::string& out) {
    const char* exts[] = {".z64", ".n64", ".v64"};
    const std::string dirs[2] = {g_internal, g_external};
    for (const std::string& dir : dirs) {
        DIR* d = opendir(dir.c_str());
        if (!d) continue;
        std::string match;
        struct dirent* ent;
        while ((ent = readdir(d)) != nullptr) {
            const std::string name = ent->d_name;
            if (name.front() == '.') continue;
            for (const char* e : exts) {
                if (ends_with_ci(name, e)) { match = dir + "/" + name; break; }
            }
            if (!match.empty()) break;
        }
        closedir(d);
        if (!match.empty()) {
            out = match;
            ALOGI("rom found: %s", out.c_str());
            return true;
        }
    }
    return false;
}

} // namespace androidport
