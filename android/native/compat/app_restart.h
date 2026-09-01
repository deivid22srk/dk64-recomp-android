/*
 * app_restart.h — reinício limpo do app após troca de driver Vulkan.
 *
 * Por que isto existe: o plume/RT64 inicializa o Vulkan UMA VEZ por
 * processo (volkInitializeCustom no construtor de plume::VulkanInterface,
 * com o vkGetInstanceProcAddr vindo do driver Turnip via adrenotools).
 * O construtor roda em rt64_application.cpp::createDeviceInterface(),
 * chamado na primeira criação do swapchain — ANTES do usuário ter
 * oportunidade de abrir o menu "GPU Driver". Quando o usuário instala
 * ou remove o driver pelo menu, o nativo já está rodando com o driver
 * da INICIALIZAÇÃO anterior:
 *
 *   - Se a inicialização foi SEM driver custom (caso típico na 1ª
 *     execução), a tabela do volk aponta para o loader do sistema
 *     (libvulkan.so do Adreno). Carregar o Turnip via adrenotools depois
 *     disso só valida via probe (uma VkInstance descartável) — as
 *     chamadas vk* do RT64 continuam pelo volk do sistema. Se o usuário
 *     clica "Start Game", o RT64 cria a VkInstance real + swapchain com
 *     o driver do sistema, ignorando o Turnip.
 *
 *   - Pior: os hooks do adrenotools (libmain_hook.so, injetado em
 *     nativeLibraryDir) interceptam o próximo dlopen("vulkan.adreno.so")
 *     e o redirecionam para o Turnip. Se algo dentro do plume/RT64
 *     tenta recarregar a libvulkan depois da instalação (ex.: recriar o
 *     device por perda de device — caminho possível em trocas de
 *     orientação), o dlopen é redirecionado para o Turnip MAS a
 *     VkInstance ativa continua vinculada ao driver do sistema —
 *     mismatch fatal, SIGSEGV no thread [Game] MAIN (visto no log
 *     01_09-19-34-08_380.log linhas 173-189 + 331: vkCreateGraphicsPipelines
 *     falhando, vkAllocateDescriptorSets falhando, crash no main thread).
 *
 * Solução: após install/remove do driver, persistir a seleção em
 * files/driver/selected.txt (já é o que o GpuDriverInstaller faz) E forçar
 * o reinício do processo. Cada relaunch recria a VkInstance e o volk
 * carrega o driver atual. O onDestroy do MainActivity já mata o processo
 * (Process.killProcess) para garantir estáticos zerados — basta chamá-lo
 * com isFinishing=true via finish() + um sinal do nativo.
 *
 * O wrapper nativo dk64::native_request_app_restart é chamado pelo menu
 * do jogo (src/main/main.cpp::show_android_gpu_driver_menu) depois de
 * mostrar a caixa de mensagem com o resultado da instalação, dando ao
 * usuário ~800 ms para ler antes de matar o app.
 */
#ifndef ANDROID_APP_RESTART_H
#define ANDROID_APP_RESTART_H

#ifdef __cplusplus
namespace dk64 {
/*
 * Pede ao MainActivity para finalizar a Activity (que mata o processo
 * via Process.killProcess no onDestroy, configurado no handler de
 * "fim de jogo"). Em builds não-Android é um no-op (não há Activity).
 *
 * Implementação real em app_restart.cpp (só compilado em Android).
 * O menu "GPU Driver" em src/main/main.cpp::show_android_gpu_driver_menu
 * já é guardado por #ifdef __ANDROID__, mas o header é incluído
 * incondicionalmente, então precisamos do stub inline abaixo para o
 * compilador em desktop — sem ele, o linker reclamaria de símbolo
 * indefinido.
 *
 * Sob Android, app_restart.cpp fornece a definição real (declarada
 * no bloco #if defined(__ANDROID__) abaixo).
 */
#if defined(__ANDROID__)
void native_request_app_restart();
#else
inline void native_request_app_restart() { /* no-op fora do Android */ }
#endif
} // namespace dk64
#endif // __cplusplus

#endif // ANDROID_APP_RESTART_H
