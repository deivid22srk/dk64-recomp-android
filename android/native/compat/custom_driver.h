/*
 * custom_driver.h — ponte para o libadrenotools (driver Vulkan Turnip).
 *
 * O SetupActivity (Kotlin) permite instalar um driver Vulkan custom (ex.: Mesa
 * Turnip, distribuído como .zip com meta.json) no app. O zip é extraído para
 * <filesDir>/driver/installed/<id>/ e a seleção fica em
 * <filesDir>/driver/selected.txt no formato KEY=VALUE (dir/library/name).
 *
 * O plume (lib/rt64), no Android, chama dk64_adrenotools_get_instance_proc_addr()
 * antes do volkInitialize(); se um driver estiver selecionado, carregamos a
 * libvulkan.so do sistema via adrenotools_open_libvulkan() (com hooks que
 * redirecionam o carregamento do driver para o Turnip) e retornamos o
 * vkGetInstanceProcAddr do driver custom. Qualquer falha => NULL e o plume usa
 * o driver do sistema como sempre.
 */
#ifndef ANDROID_CUSTOM_DRIVER_H
#define ANDROID_CUSTOM_DRIVER_H

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Retorna o vkGetInstanceProcAddr do driver Vulkan custom (Turnip) instalado,
 * ou NULL se não houver driver selecionado / o carregamento falhar (nesse caso
 * o chamador deve seguir com o driver do sistema).
 */
void *dk64_adrenotools_get_instance_proc_addr(void);

/*
 * Retorna 1 se um driver custom foi carregado com sucesso nesta execução
 * (o plume usa isso para decidir se recursos como VK_GOOGLE_display_timing
 * são seguros — o driver proprietário Adreno antigo tem SIGSEGV em
 * vkGetRefreshCycleDurationGOOGLE).
 */
int dk64_adrenotools_custom_driver_active(void);

#ifdef __cplusplus
}
#endif

#endif // ANDROID_CUSTOM_DRIVER_H
