/*
 * crash_handler.h — visibilidade de morte nativa no logcat.
 *
 * POR QUE ISTO EXISTE: os relatos do device (moto g34 5G) mostravam o app
 * fechando SEM nenhuma linha de diagnóstico (o logcat capturado terminava
 * antes da morte). Morte por sinal (SIGSEGV/SIGABRT/...) ou por
 * std::terminate (exceção não capturada em thread nativa) nem sempre produz
 * linhas legíveis no buffer capturado — e o tombstone nativo fica em
 * /data/tombstones, inacessível sem adb. Este handler registra a causa no
 * TAG "DK64Recomp-Crash" do logcat ANTES de devolver a morte ao fluxo
 * padrão do Android (o tombstone/"Fatal signal" continua acontecendo).
 *
 * É observabilidade pura: não altera nenhum fluxo de execução normal.
 */
#ifndef ANDROID_CRASH_HANDLER_H
#define ANDROID_CRASH_HANDLER_H

namespace androidport::crash {

/*
 * Instala handlers para SIGSEGV/SIGBUS/SIGFPE/SIGILL/SIGABRT + std::terminate
 * e devolve a morte ao comportamento padrão (re-raise com handler anterior /
 * SIG_DFL), preservando o tombstone nativo. Idempotente.
 */
void install();

} // namespace androidport::crash

#endif // ANDROID_CRASH_HANDLER_H
