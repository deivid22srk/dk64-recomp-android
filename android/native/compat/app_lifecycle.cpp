/*
 * app_lifecycle.cpp — implementação do congelamento em segundo plano.
 * Ver app_lifecycle.h para o desenho completo (motivação e contrato).
 *
 * O export JNI é consumido pelo MainActivity (onPause / surfaceChanged /
 * onWindowFocusChanged). Compilado DENTRO do libmain.so junto com o resto do
 * port; o header é injetado no alvo rt64 pelo CMake do app (ver
 * android/app/CMakeLists.txt) para o gate na PresentQueue.
 */
#include "app_lifecycle.h"

#if defined(__ANDROID__)

#include <android/log.h>
#include <jni.h>

#include <chrono>
#include <condition_variable>
#include <mutex>

namespace androidport::lifecycle {

namespace {

#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, "DK64Recomp", __VA_ARGS__)

std::mutex g_mutex;
std::condition_variable g_cv;
bool g_active = true; // padrão: em primeiro plano (o Java só restringe)

// Flag independente do gate de segundo plano: indica que a Surface nativa foi
// destruída (surfaceDestroyed chegou do Android) ou substituída (surfaceCreated
// com um ANativeWindow novo). Consumido pela PresentQueue para forçar
// swapChainValid=false + ext.swapChain->invalidateSurface() no próximo turno,
// mesmo que o gate esteja aberto (g_active=true) — caso clássico do
// DocumentsUI: onPause + onWindowFocusChanged(true) prematuro libera a
// thread, e só então a surface morre de fato 700 ms depois.
std::atomic<bool> g_surface_dirty{false};

} // namespace

void set_active(bool active) {
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (g_active == active) return;
        g_active = active;
    }

    if (active) {
        ALOGI("lifecycle: app ativo — liberando present queue");
        g_cv.notify_all();
    } else {
        ALOGI("lifecycle: app em segundo plano — congelando present queue");
    }
}

void mark_surface_dirty() {
    bool was = g_surface_dirty.exchange(true, std::memory_order_acq_rel);
    if (!was) {
        ALOGI("lifecycle: surface marcada como obsoleta (próximo resize recria VkSurface)");
        // Acorda a thread caso esteja congelada — assim que ela acordar ela
        // chama consume_surface_dirty() e força a recriação do swapchain,
        // mesmo que g_active já seja true (DocumentsUI: gate liberado
        // prematuramente por onWindowFocusChanged antes da surfaceDestroyed).
        g_cv.notify_all();
    }
}

bool consume_surface_dirty() {
    return g_surface_dirty.exchange(false, std::memory_order_acq_rel);
}

bool wait_while_backgrounded(const std::atomic<bool> &running) {
    std::unique_lock<std::mutex> lock(g_mutex);

    if (g_active) return false;

    bool frozen = false;
    while (!g_active) {
        // Shutdown da PresentQueue (destrutor): não segurar o join.
        if (running.load(std::memory_order_relaxed) == false) return frozen;
        g_cv.wait_for(lock, std::chrono::milliseconds(2));
        frozen = true;
    }

    return frozen;
}

} // namespace androidport::lifecycle

// ---------------------------------------------------------------------------
// Export JNI (chamado pelo MainActivity) — extern "C" preserva o nome literal.
// ---------------------------------------------------------------------------

extern "C" {

JNIEXPORT void JNICALL
Java_com_deivid22srk_dk64recomp_MainActivity_nativeSetAppActive(JNIEnv * /*env*/, jclass /*clazz*/,
                                                                jboolean active) {
    androidport::lifecycle::set_active(active == JNI_TRUE);
}

/*
 * Hook de ciclo de vida da Surface nativa, consumido pelo MainActivity via
 * um SurfaceHolder.Callback adicionado no onCreate(). Recebe:
 *   state = 0 → surfaceDestroyed: congela a fila e marca a surface como
 *               obsoleta (g_surface_dirty=true).
 *   state = 1 → surfaceCreated: marca a surface como obsoleta TAMBÉM — a
 *               janela do SDL pode ter sido recriada com um ANativeWindow
 *               novo e o swapchain antigo precisa ser refeito.
 *   state = 2 → surfaceChanged (resize): marca como obsoleta — o tamanho
 *               mudou, e o swapchain precisa de resize() de qualquer forma.
 *
 * Idempotente: chamar várias vezes seguidas com o mesmo state só faz o
 * necessário uma vez (o atomic + flag evitam storms de log no SurfaceView
 * BLAST do Android 14/15, que dispara surfaceChanged várias vezes durante
 * rotação e transição de Activity).
 */
JNIEXPORT void JNICALL
Java_com_deivid22srk_dk64recomp_MainActivity_nativeSurfaceState(JNIEnv * /*env*/, jclass /*clazz*/,
                                                               jint state) {
    switch (state) {
    case 0:
        androidport::lifecycle::set_active(false);
        androidport::lifecycle::mark_surface_dirty();
        break;
    case 1:
    case 2:
    default:
        androidport::lifecycle::mark_surface_dirty();
        // NÃO liberamos a thread aqui: ela libera sozinha quando recebe o
        // present. Liberar manualmente aqui causou o bug original (gate
        // liberado prematuramente enquanto a surface ainda estava prestes a
        // morrer). O caminho correto é esperar a thread acordar via
        // consume_surface_dirty() no início do threadLoop.
        break;
    }
}

} // extern "C"

#endif // __ANDROID__