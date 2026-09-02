/*
 * android_paths.h — globais de paths injetados pelo Java (argv do SDLActivity)
 * e helpers usados pelo patch do RecompFrontend (recompui::file) no Android.
 */
#ifndef ANDROID_PATHS_H
#define ANDROID_PATHS_H

#include <string>

// Definidos em android_paths.cpp a partir do argv do SDL_main.
namespace androidport {

// /data/user/0/<pkg>/files  (config: DK64.z64, saves, config, mods, assets copiados)
const std::string& internal_files_dir();
// /storage/emulated/0/Android/data/<pkg>/files (onde o usuário coloca a ROM)
const std::string& external_files_dir();

// Diretório das libs nativas do APK (ApplicationInfo.nativeLibraryDir).
// Injetado via JNI pelo SetupActivity (set_runtime_paths) — é exatamente o
// valor que o libadrenotools exige como hookLibDir. Pode ficar vazio até a
// 1ª injeção (aí custom_driver.cpp faz fallback ao scan de /proc/self/maps).
const std::string& native_library_dir();

/*
 * Injeta os paths ANTES do main() (JNI do SetupActivity). O probe de driver
 * roda na SetupActivity, onde init_from_args() ainda não executou — sem isso
 * internal_files_dir() estaria vazio e o nativo jamais encontraria
 * files/driver/selected.txt ("driver não carregado" instantâneo).
 * Parâmetros null/vazios preservam o valor atual.
 */
void set_runtime_paths(const char* internal_dir, const char* native_lib_dir);

/*
 * Injeta APENAS o nativeLibraryDir (JNI do MainActivity.onCreate —
 * nativeSetRuntimePaths). Separação de propósito: no onCreate a SDLThread já
 * pode estar rodando init_from_args() em paralelo (que escreve
 * internal/external a partir do argv) — escrever os MESMOS globals aqui seria
 * uma corrida de dados (mesmo com valor igual). g_native_lib_dir NÃO é
 * escrito por init_from_args, então esta função não disputa nada.
 * Usado por custom_driver.cpp como hookLibDir do adrenotools em TODA sessão.
 */
void set_native_library_dir(const char* native_lib_dir);

// Chamado uma única vez no início do main() no Android.
void init_from_args(int argc, char** argv);

// Busca ROM (.z64/.n64/.v64, case-insensitive) em internal_files_dir() e
// external_files_dir(). true + caminho em `out` se encontrado.
bool find_rom_file(std::string& out);

// Redireciona o stderr do processo para o logcat (tag "DK64Recomp-stderr",
// nível WARN). Sem isso, fprintf(stderr) do plume/RT64 — ex.:
// "Unable to find devices that support Vulkan." / "Missing required
// extension: ..." — não aparece em nenhum lugar em builds release, o que
// impossibilita diagnosticar falhas de vídeo a partir do logcat do usuário.
void redirect_stderr_to_logcat();

} // namespace androidport

#endif // ANDROID_PATHS_H
