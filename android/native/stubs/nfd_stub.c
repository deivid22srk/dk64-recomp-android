/*
 * nfd_stub.c — stub do nativefiledialog-extended para Android.
 *
 * O port Android não usa diálogos nativos: `recompui::file::open_file_dialog`
 * foi substituído (patch do RecompFrontend) por um escaneador de diretórios,
 * e os diálogos de debug do RT64 não existem no app. Este stub existe apenas
 * para satisfazer o linker com a mesma API C (retornando NFD_ERROR/CANCEL).
 */
#include "nfd.h"

#include <stdlib.h>
#include <string.h>

static char g_nfd_error[256] = {0};

static void set_error(const char* msg) {
    snprintf(g_nfd_error, sizeof(g_nfd_error), "%s", msg ? msg : "NFD unavailable on Android");
}

const char* NFD_GetError(void) {
    return g_nfd_error[0] ? g_nfd_error : NULL;
}

void NFD_ClearError(void) {
    g_nfd_error[0] = '\0';
}

nfdresult_t NFD_Init(void) {
    /* Inicialização é no-op: sucesso, para não bloquear fluxos de shutdown. */
    return NFD_OKAY;
}

void NFD_Quit(void) {
}

void NFD_FreePathN(nfdnchar_t* filePath) {
    free(filePath);
}

nfdresult_t NFD_OpenDialogN(nfdnchar_t** outPath,
                            const nfdnfilteritem_t* filterList,
                            nfdfiltersize_t filterCount,
                            const nfdnchar_t* defaultPath) {
    (void)filterList; (void)filterCount; (void)defaultPath;
    if (outPath) *outPath = NULL;
    set_error("File dialogs are not available on Android; place the ROM in the app folder.");
    return NFD_ERROR;
}

nfdresult_t NFD_OpenDialogMultipleN(const nfdpathset_t** outPaths,
                                    const nfdnfilteritem_t* filterList,
                                    nfdfiltersize_t filterCount,
                                    const nfdnchar_t* defaultPath) {
    (void)filterList; (void)filterCount; (void)defaultPath;
    if (outPaths) *outPaths = NULL;
    set_error("File dialogs are not available on Android.");
    return NFD_ERROR;
}

nfdresult_t NFD_SaveDialogN(nfdnchar_t** outPath,
                            const nfdnfilteritem_t* filterList,
                            nfdfiltersize_t filterCount,
                            const nfdnchar_t* defaultPath,
                            const nfdnchar_t* defaultName) {
    (void)filterList; (void)filterCount; (void)defaultPath; (void)defaultName;
    if (outPath) *outPath = NULL;
    set_error("File dialogs are not available on Android.");
    return NFD_ERROR;
}

nfdresult_t NFD_PickFolderN(nfdnchar_t** outPath, const nfdnchar_t* defaultPath) {
    (void)defaultPath;
    if (outPath) *outPath = NULL;
    set_error("Folder dialogs are not available on Android.");
    return NFD_ERROR;
}

nfdresult_t NFD_PathSet_GetCount(const nfdpathset_t* pathSet, nfdpathsetsize_t* count) {
    if (count) *count = 0;
    (void)pathSet;
    return NFD_OKAY;
}

nfdresult_t NFD_PathSet_GetPathN(const nfdpathset_t* pathSet,
                                 nfdpathsetsize_t index,
                                 nfdnchar_t** outPath) {
    (void)pathSet; (void)index;
    if (outPath) *outPath = NULL;
    return NFD_ERROR;
}

void NFD_PathSet_Free(const nfdpathset_t* pathSet) {
    (void)pathSet;
}
