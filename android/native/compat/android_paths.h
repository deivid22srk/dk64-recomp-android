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

// Chamado uma única vez no início do main() no Android.
void init_from_args(int argc, char** argv);

// Busca ROM (.z64/.n64/.v64, case-insensitive) em internal_files_dir() e
// external_files_dir(). true + caminho em `out` se encontrado.
bool find_rom_file(std::string& out);

} // namespace androidport

#endif // ANDROID_PATHS_H
