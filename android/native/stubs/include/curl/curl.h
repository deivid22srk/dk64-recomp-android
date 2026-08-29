/*
 * curl/curl.h — cabeçalho mínimo do libcurl para o build Android.
 *
 * O mod store online (ui_mod_discovery_http.cpp) é o único consumidor de curl.
 * No Android v1 ele é mantido compilável mas inoperante: o stub retorna erro
 * de rede imediatamente. Mods LOCAIS (.nrm/.rtz) continuam funcionando.
 *
 * Os valores dos enums são copiados do libcurl real para futura substituição
 * por um libcurl Android completo sem mudanças no código do frontend.
 */
#ifndef ANDROID_STUB_CURL_CURL_H
#define ANDROID_STUB_CURL_CURL_H

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef void CURL;

typedef enum {
    CURLE_OK = 0,
    CURLE_UNSUPPORTED_PROTOCOL = 1,
    CURLE_COULDNT_RESOLVE_HOST = 6,
    CURLE_COULDNT_CONNECT = 7,
    CURLE_OPERATION_TIMEDOUT = 28,
    CURLE_FAILED_INIT = 2,
    CURLE_OUT_OF_MEMORY = 27
} CURLcode;

typedef enum {
    CURLOPT_URL = 10002,
    CURLOPT_WRITEFUNCTION = 20011,
    CURLOPT_WRITEDATA = 10001,
    CURLOPT_FOLLOWLOCATION = 52,
    CURLOPT_USERAGENT = 10018,
    CURLOPT_HTTPHEADER = 10023,
    CURLOPT_TIMEOUT = 13,
    CURLOPT_SSL_VERIFYPEER = 64,
    CURLOPT_SSL_VERIFYHOST = 81
} CURLoption;

typedef enum {
    CURLINFO_RESPONSE_CODE = 0x200002
} CURLINFO;

#define CURL_GLOBAL_DEFAULT ((long)3)

typedef enum {
    CURL_GLOBAL_SSL = 1,
    CURL_GLOBAL_WIN32 = 2,
    CURL_GLOBAL_ALL = 3,
    CURL_GLOBAL_NOTHING = 0
} CURLglobal;

struct curl_slist {
    char* data;
    struct curl_slist* next;
};

typedef size_t (*curl_write_callback)(char* buffer, size_t size, size_t nitems, void* outstream);

CURLcode curl_global_init(long flags);
void curl_global_cleanup(void);

CURL* curl_easy_init(void);
CURLcode curl_easy_setopt(CURL* curl, CURLoption option, ...);
CURLcode curl_easy_perform(CURL* curl);
CURLcode curl_easy_getinfo(CURL* curl, CURLINFO info, ...);
void curl_easy_cleanup(CURL* curl);
const char* curl_easy_strerror(CURLcode code);

struct curl_slist* curl_slist_append(struct curl_slist* list, const char* data);
void curl_slist_free_all(struct curl_slist* list);

#ifdef __cplusplus
}
#endif

#endif /* ANDROID_STUB_CURL_CURL_H */
