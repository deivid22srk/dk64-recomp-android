/*
 * crash_handler.cpp — implementação da visibilidade de morte nativa.
 * Ver crash_handler.h para a motivação.
 *
 * Regras de segurança deste handler:
 *  - No caminho do sinal, apenas operações async-signal-safe (write,
 *    snprintf, sigaction, gettid, _Unwind_Backtrace) + __android_log_print
 *    (não oficialmente signal-safe, porém sem locks — é o mecanismo usado
 *    por Crashpad/Chromium e é o que garante a linha no logcat mesmo se o
 *    pipe do stderr estiver morto).
 *  - dladdr NÃO é async-signal-safe; é aceito conscientemente (roda uma
 *    única vez, no caminho de morte do processo) para dar os nomes das
 *    bibliotecas dos frames. O re-raise abaixo preserva o tombstone
 *    completo do Android, que é a fonte autoritativa de símbolos.
 */
#include "crash_handler.h"

#if defined(__ANDROID__)

#include <android/log.h>

#include <csignal>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <dlfcn.h>
#include <exception>
#include <typeinfo>
#include <unistd.h>
#include <unwind.h>

namespace androidport::crash {

namespace {

#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, "DK64Recomp-Crash", __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, "DK64Recomp-Crash", __VA_ARGS__)

constexpr int kHandledSignals[] = { SIGSEGV, SIGBUS, SIGFPE, SIGILL, SIGABRT };
constexpr int kHandledSignalCount = static_cast<int>(sizeof(kHandledSignals) / sizeof(kHandledSignals[0]));

// Indexado por NÚMERO do sinal (todos os sinais tratados têm número < 32).
struct sigaction g_previous[32];

struct BacktraceContext {
    static constexpr int kMaxFrames = 24;
    int frames = 0;
    uintptr_t pcs[kMaxFrames] = {};
};

_Unwind_Reason_Code unwind_callback(struct _Unwind_Context *context, void *data) {
    BacktraceContext *bt = static_cast<BacktraceContext *>(data);
    if (bt->frames >= BacktraceContext::kMaxFrames) {
        return _URC_END_OF_STACK;
    }
    const uintptr_t pc = _Unwind_GetIP(context);
    if (pc != 0) {
        bt->pcs[bt->frames++] = pc;
    }
    return _URC_NO_REASON;
}

void write_line(const char *text, size_t length) {
    if (text != nullptr && length > 0) {
        // STDERR_FILENO já é redirecionado ao logcat pelo port
        // ("stderr -> logcat", tag DK64Recomp-stderr) — a write é apenas um
        // segundo canal, async-signal-safe.
        write(STDERR_FILENO, text, length);
    }
}

/*
 * Grava `buf` após um snprintf, com CLAMP do comprimento retornado: quando o
 * snprintf trunca, ele devolve o tamanho QUE TERIA (que pode exceder a
 * capacidade do buffer) — usar esse valor na write leria além do stack.
 */
void write_clamped(const char *buf, int len, size_t cap) {
    if (len <= 0) {
        return;
    }
    const size_t n = (static_cast<size_t>(len) >= cap) ? cap - 1 : static_cast<size_t>(len);
    write_line(buf, n);
}

void log_backtrace() {
    BacktraceContext bt{};
    _Unwind_Backtrace(&unwind_callback, &bt);

    char line[256];
    int len = snprintf(line, sizeof(line), "backtrace (%d frames):\n", bt.frames);
    write_clamped(line, len, sizeof(line));

    for (int i = 0; i < bt.frames; i++) {
        Dl_info info{};
        if (dladdr(reinterpret_cast<void *>(bt.pcs[i]), &info) != 0 && info.dli_fname != nullptr) {
            len = snprintf(line, sizeof(line), "  #%02d pc %p %s (base %p)\n",
                           i, reinterpret_cast<void *>(bt.pcs[i]), info.dli_fname, info.dli_fbase);
        } else {
            len = snprintf(line, sizeof(line), "  #%02d pc %p\n",
                           i, reinterpret_cast<void *>(bt.pcs[i]));
        }
        write_clamped(line, len, sizeof(line));
    }
}

const char *signal_name(int sig) {
    switch (sig) {
        case SIGSEGV: return "SIGSEGV";
        case SIGBUS:  return "SIGBUS";
        case SIGFPE:  return "SIGFPE";
        case SIGILL:  return "SIGILL";
        case SIGABRT: return "SIGABRT";
        default:      return "UNKNOWN";
    }
}

void signal_handler(int sig, siginfo_t *info, void * /*ucontext*/) {
    // 1º passo: restaura o handler anterior (normalmente SIG_DFL). Se
    // qualquer coisa abaixo falhar, o comportamento nativo de morte é
    // preservado (sigaction é async-signal-safe).
    if (sig >= 0 && sig < 32) {
        sigaction(sig, &g_previous[sig], nullptr);
    }

    char header[256];
    int len;
    if (info != nullptr && (sig == SIGSEGV || sig == SIGBUS || sig == SIGILL || sig == SIGFPE)) {
        len = snprintf(header, sizeof(header),
                       "FATAL: %s (si_code=%d) at address %p — tid=%d\n",
                       signal_name(sig), info->si_code, info->si_addr, gettid());
    } else {
        len = snprintf(header, sizeof(header),
                       "FATAL: %s (si_code=%d) — tid=%d\n",
                       signal_name(sig), info != nullptr ? info->si_code : 0, gettid());
    }
    write_clamped(header, len, sizeof(header));
    ALOGE("%s", header);

    log_backtrace();

    // Re-raise com a disposição restaurada: o Android registra o
    // "Fatal signal"/tombstone padrão no logcat do sistema e gera o
    // crash report nativo. Sem isso, o app morreria "em silêncio".
    sigset_t mask;
    sigemptyset(&mask);
    sigaddset(&mask, sig);
    sigprocmask(SIG_UNBLOCK, &mask, nullptr);
    raise(sig);

    // Alcançável apenas se o handler anterior ignorar o sinal.
    _exit(127);
}

void terminate_handler() {
    /*
     * Captura a mensagem da exceção ativa — a maioria dos std::terminate é
     * uma exceção não capturada em thread nativa (ex.: bad_alloc, erro de
     * filesystem em mods), que antes morria sem NENHUM texto no logcat.
     * O abort() do terminate passa pelo signal_handler acima (SIGABRT),
     * que faz o backtrace + re-raise.
     */
    char header[512];
    const char *what = "unknown";
    const char *type_name = "unknown";
    if (std::exception_ptr current = std::current_exception()) {
        try {
            std::rethrow_exception(current);
        } catch (const std::exception &e) {
            what = e.what();
            type_name = typeid(e).name();
        } catch (...) {
            what = "non-std exception";
            type_name = "non-std";
        }
    }

    int len = snprintf(header, sizeof(header),
                       "FATAL: std::terminate called — active exception %s: %s\n", type_name, what);
    write_clamped(header, len, sizeof(header));
    ALOGE("%s", header);

    std::abort();
}

} // namespace

void install() {
    static bool installed = false;
    if (installed) {
        return;
    }
    installed = true;

    struct sigaction action{};
    action.sa_sigaction = signal_handler;
    action.sa_flags = SA_SIGINFO;
    sigemptyset(&action.sa_mask);

    for (int i = 0; i < kHandledSignalCount; i++) {
        const int sig = kHandledSignals[i];
        if (sig >= 0 && sig < 32) {
            if (sigaction(sig, &action, &g_previous[sig]) != 0) {
                ALOGE("crash handler: falha ao instalar handler para %s", signal_name(sig));
            }
        }
    }

    std::set_terminate(terminate_handler);
    ALOGI("crash handler: instalado (SIGSEGV/SIGBUS/SIGFPE/SIGILL/SIGABRT + std::terminate)");
}

} // namespace androidport::crash

#endif // __ANDROID__
