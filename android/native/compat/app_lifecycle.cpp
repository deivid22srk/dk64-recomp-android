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

bool wait_while_backgrounded(const volatile bool &running) {
    std::unique_lock<std::mutex> lock(g_mutex);

    if (g_active) return false;

    bool frozen = false;
    while (!g_active) {
        // Shutdown da PresentQueue (destrutor): não segurar o join.
        if (running == false) return frozen;
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

} // extern "C"

#endif // __ANDROID__
