/*
 * file_bridge.h — ponte NÃO-bloqueante entre os menus do recomp (RmlUi, na
 * thread de render do RT64) e o seletor de arquivos SAF do Android.
 *
 * Substitui o fluxo da tela Java de setup (removida): agora a PRÓPRIA UI do
 * jogo aciona o DocumentsUI do Android para escolher ROM e driver Turnip.
 *
 * Por que NÃO-bloqueante: o callback de um GameOption roda dentro do draw_hook
 * (thread de present do RT64) segurando ui_state_mutex. Bloquear ali num
 * startActivityForResult congelaria o render durante um ciclo completo de
 * onPause/onResume da Activity (a Activity fica em pausa atrás do DocumentsUI).
 * Em vez disso:
 *
 *   1. request(kind, cb)      — guarda o callback (slot único) e posta o
 *                               Intent SAF na UI thread via JNI (retorno
 *                               imediato; o menu continua renderizando).
 *   2. (usuário escolhe)      — MainActivity.onActivityResult processa em
 *                               background (cópia da ROM / instalação do zip
 *                               do driver + probe) e publica o resultado via
 *                               o export JNI nativeOnFilePicked.
 *   3. process_pending()      — chamado a cada frame no início do draw_hook
 *                               (patch do RecompFrontend, ui_state.cpp) sob o
 *                               MESMO ui_state_mutex dos callbacks de menu;
 *                               despacha o resultado para o callback armazenado
 *                               — exatamente o mesmo contexto em que o upstream
 *                               executa o retorno do NFD no desktop.
 *
 * Single-slot: os menus são modais (um clique de cada vez), então nunca há dois
 * pedidos simultâneos; request() devolve false se já houver um pendente e o
 * callback do chamador é invocado com ok=false (sem travar ninguém).
 */
#ifndef ANDROID_FILE_BRIDGE_H
#define ANDROID_FILE_BRIDGE_H

#include <functional>
#include <string>

#if defined(__ANDROID__)

namespace androidport::filedialog {

enum class Kind {
    Rom = 0,       // .z64/.n64/.v64 — copiado para o filesDir e validado pelo select_rom
    DriverZip = 1, // .zip/.so de driver Turnip — instalado pelo GpuDriverInstaller (Java)
};

using Callback = std::function<void(bool ok, const std::string& payload)>;

/*
 * Posts the SAF file picker (DocumentsUI) via JNI and stores `callback` for
 * later dispatch by process_pending(). Returns false (and invokes `callback`
 * with ok=false) if a request is already pending or the JNI bridge is not
 * initialized — the caller's flow just ends, exactly like a canceled dialog.
 */
bool request(Kind kind, Callback callback);

/*
 * Despacha (uma vez por frame, no draw_hook) o resultado publicado pelo Java.
 * No-op quando não há resultado pendente.
 */
void process_pending();

/*
 * Abre a tela Java de diagnóstico (DiagnosticsActivity — toggle da captura,
 * logs por sessão e compartilhamento) a partir do menu launcher. Fire-and-
 * forget: posta o Intent e devolve; false só quando a ponte JNI não está
 * pronta ou o Java recusou. NÃO toca no slot único do SAF.
 */
bool open_diagnostics_screen();

/*
 * Injetado pelo MainActivity.nativeBridgeInit (após loadLibraries) — guarda a
 * JavaVM para os pinos JNI das threads de render. Recebe void* para o header
 * não depender de jni.h.
 */
void set_java_vm(void *vm);

} // namespace androidport::filedialog

#else // !__ANDROID__

// Call sites compilam em desktop sem guards: tudo vira no-op/false.
#include <cstdlib>
namespace androidport::filedialog {
enum class Kind { Rom = 0, DriverZip = 1 };
using Callback = std::function<void(bool ok, const std::string& payload)>;
inline bool request(Kind, Callback callback) {
    if (callback) callback(false, std::string{});
    return false;
}
inline void process_pending() {}
inline bool open_diagnostics_screen() { return false; }
inline void set_java_vm(void *) {}
} // namespace androidport::filedialog

#endif // __ANDROID__

#endif // ANDROID_FILE_BRIDGE_H
