/*
 * curl_stub.cpp — implementação mínima (inerte) do libcurl para Android v1.
 * Toda operação retorna erro de rede imediatamente; o mod store do frontend
 * exibirá falha de conexão sem crash. Ver DESIGN.md ("mod store offline").
 */
#include <curl/curl.h>

#include <stdarg.h>
#include <cstdlib>
#include <cstring>

extern "C" {

CURLcode curl_global_init(long) {
    return CURLE_OK; /* permite inicializar sem tratar erro no chamador */
}

void curl_global_cleanup(void) {
}

CURL* curl_easy_init(void) {
    return nullptr; /* toda operação subsequente falha de forma segura */
}

CURLcode curl_easy_setopt(CURL*, CURLoption, ...) {
    return CURLE_COULDNT_CONNECT;
}

CURLcode curl_easy_perform(CURL*) {
    return CURLE_COULDNT_RESOLVE_HOST;
}

CURLcode curl_easy_getinfo(CURL*, CURLINFO info, ...) {
    if (info == CURLINFO_RESPONSE_CODE) {
        va_list args;
        va_start(args, info);
        long* out = va_arg(args, long*);
        va_end(args);
        if (out) *out = 0;
    }
    return CURLE_COULDNT_CONNECT;
}

void curl_easy_cleanup(CURL*) {
}

const char* curl_easy_strerror(CURLcode) {
    return "Network is disabled in this Android build (mod store offline)";
}

struct curl_slist* curl_slist_append(struct curl_slist* list, const char* data) {
    auto* item = static_cast<curl_slist*>(malloc(sizeof(curl_slist)));
    if (!item) return list;
    item->data = strdup(data ? data : "");
    item->next = nullptr;
    if (!list) return item;
    struct curl_slist* end = list;
    while (end->next) end = end->next;
    end->next = item;
    return list;
}

void curl_slist_free_all(struct curl_slist* list) {
    while (list) {
        struct curl_slist* next = list->next;
        free(list->data);
        free(list);
        list = next;
    }
}

} /* extern "C" */
