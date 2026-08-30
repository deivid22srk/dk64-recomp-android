/*
 * android_paths.cpp — resolução de caminhos para o port Android.
 *
 * Contrato de argv com o SDL 2.30.8 (idêntico na série 2.30): SDL_android.c
 * (nativeRunMain) monta
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
#include <pthread.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <cctype>
#include <cstdio>
#include <cstring>

namespace androidport {

namespace {
std::string g_internal;
std::string g_external;
std::string g_native_lib_dir;

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
const std::string& native_library_dir() { return g_native_lib_dir; }

void set_runtime_paths(const char* internal_dir, const char* native_lib_dir) {
    if (internal_dir && internal_dir[0]) {
        g_internal = internal_dir;
        ALOGI("paths (JNI): internal=%s", internal_dir);
    }
    if (native_lib_dir && native_lib_dir[0]) {
        g_native_lib_dir = native_lib_dir;
        ALOGI("paths (JNI): nativeLibraryDir=%s", native_lib_dir);
    }
}

/*
 * Pipa o stderr do processo para o logcat. O plume/RT64 reportam falhas de
 * vídeo via fprintf(stderr) ("Unable to find devices that support Vulkan.",
 * "Missing required extension: ...") — em builds release isso vai para
 * /dev/null e o usuário só vê a caixa genérica "Unable to find compatible
 * graphics device", sem chance de diagnóstico. Com o redirect, tudo aparece
 * no logcat na tag "DK64Recomp-stderr".
 */
void redirect_stderr_to_logcat() {
    static bool done = false;
    if (done) return;
    done = true;

    int fds[2];
    if (pipe(fds) != 0) return;

    static int readerFd = fds[0];

    auto reader = +[](void*) -> void* {
        FILE* in = fdopen(readerFd, "r");
        if (in == nullptr) return nullptr;
        char* line = nullptr;
        size_t cap = 0;
        ssize_t n;
        while ((n = getline(&line, &cap, in)) > 0) {
            if (n > 0 && line[n - 1] == '\n') line[n - 1] = '\0';
            __android_log_print(ANDROID_LOG_WARN, "DK64Recomp-stderr", "%s", line);
        }
        free(line);
        fclose(in);
        return nullptr;
    };

    // A thread leitora é criada ANTES do dup2: se falhar, o stderr original
    // permanece intacto (não há risco de um pipe sem leitor encher e travar
    // quem escreve, pois o dup2 só acontece com o leitor já ativo).
    pthread_t tid;
    if (pthread_create(&tid, nullptr, reader, nullptr) != 0) {
        close(fds[0]);
        close(fds[1]);
        return;
    }
    pthread_detach(tid);

    // O fd de escrita vira o novo stderr (fd 2) do processo inteiro.
    if (dup2(fds[1], STDERR_FILENO) < 0) {
        // A thread leitora fica bloqueada num pipe vazio — inofensivo.
        close(fds[1]);
        return;
    }
    if (fds[1] != STDERR_FILENO) close(fds[1]);

    ALOGI("stderr -> logcat (tag DK64Recomp-stderr)");
}

void init_from_args(int argc, char** argv) {
    // Primeiro de tudo: garantir que fprintf(stderr) do plume/RT64 apareça no logcat.
    redirect_stderr_to_logcat();

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
