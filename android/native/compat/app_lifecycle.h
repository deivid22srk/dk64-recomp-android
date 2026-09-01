/*
 * app_lifecycle.h — congela o pipeline de render do RT64 enquanto o app está
 * em segundo plano (Port Android do DK64: Recompiled).
 *
 * POR QUE ISTO EXISTE: ao abrir o DocumentsUI (seleção de ROM/driver na UI do
 * próprio jogo) ou ao apertar Home, o Android pausa a Activity e DESTRÓI a
 * Surface (surfaceDestroyed). O SDL pausa a thread principal (SDL_main fica
 * preso no event loop), mas a PRESENT THREAD do RT64 é uma thread nativa
 * própria e continuava apresentando quadros em uma surface morta — o driver
 * Vulkan da Adreno termina em SIGSEGV (null deref dentro do
 * vkCreateFramebuffer após vkCreateSwapchainKHR falhar na surface destruída).
 *
 * Contrato:
 *   - Java (MainActivity.onPause) chama nativeSetAppActive(false) assim que a
 *     Activity sai da frente (ANTES do surfaceDestroyed, que chega ~600 ms
 *     depois) → a present thread congela no início da próxima iteração.
 *   - Java (MainActivity.surfaceChanged / onWindowFocusChanged(true)) chama
 *     nativeSetAppActive(true) quando há surface nova em primeiro plano → a
 *     present thread acorda e recria VkSurface + swapchain (plume
 *     invalidateSurface + resize) antes de apresentar de novo.
 *
 * Thread-safety: wait_while_backgrounded() pode ser chamada da present thread
 * a cada iteração do loop (custo desprezível quando ativo). `running` é o
 * flag de shutdown da PresentQueue — checado a cada despertar para o join do
 * destrutor não travar.
 */
#ifndef ANDROID_APP_LIFECYCLE_H
#define ANDROID_APP_LIFECYCLE_H

namespace androidport::lifecycle {

/* Marcado pelo Java (JNI em app_lifecycle.cpp). Padrão: true (ativo). */
void set_active(bool active);

/*
 * Bloqueia a thread chamadora enquanto o app estiver em segundo plano.
 * Retorna true se chegou a ficar congelada (o chamador DEVE tratar a surface
 * do swapchain como obsoleta e recriá-la). Checa `running` a cada despertar:
 * quando virar false (shutdown da PresentQueue), retorna imediatamente.
 */
bool wait_while_backgrounded(const volatile bool &running);

} // namespace androidport::lifecycle

#endif // ANDROID_APP_LIFECYCLE_H
