package com.deivid22srk.dk64recomp;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Captura de LOGS DE DIAGNÓSTICO do jogo (ativável/desativável pelo usuário).
 *
 * OBJETIVO (pedido do usuário): deixar EXATAMENTE claro se há qualquer
 * problema — áudio, renderização (Vulkan/RT64/swapchain), driver, ciclo de
 * vida da surface, crashes etc. — capturando o log do próprio processo e
 * PRESERVANDO o arquivo mesmo que o app seja fechado à força ou crashe,
 * com opção de compartilhar o log (DiagnosticsActivity).
 *
 * COMO FUNCIONA:
 *  1. Captura: processo filho `logcat -v time --pid=<nosso pid>` lido em
 *     thread dedicada; CADA linha é gravada e FLUSHADA no arquivo na hora
 *     (E/F/W flush imediato; D/I/V flush a cada 25 linhas). Dados já
 *     flushados vivem no kernel (page cache) e SOBREVIVEM a SIGKILL
 *     (force-close/swipe) — só se perde o que estava no buffer de usuário,
 *     que é mínimo por construção.
 *  2. Crash Java: Thread.UncaughtExceptionHandler encadeado grava o stack
 *     trace + resumo ANTES de delegar ao handler padrão do sistema.
 *  3. Crash nativo: as linhas "Fatal signal ... (pid)" do libc e o backtrace
 *     do libmain/plume/RT64 chegam pelo próprio logcat do pid (capturados).
 *  4. Encerramento normal: onDestroy da MainActivity chama endSession(),
 *     que escreve o bloco de RESUMO AUTOMÁTICO (erros/avisos agrupados por
 *     frequência) ANTES do killProcess — o resumo nunca se perde.
 *  5. Retenção: últimos 5 arquivos em <externalFilesDir>/diagnostics/
 *     (fallback: filesDir), cap de 8 MB por sessão.
 *
 * TUDO é defensivo: nenhuma falha do logger pode derrubar o jogo.
 */
public final class DiagnosticsLogger {

    private static final String TAG = "DK64Recomp";
    private static final String PREFS = "dk64_diagnostics";
    private static final String PREF_ENABLED = "enabled";

    /** Captura DESATIVADA por padrão (pedido do usuário): nada é registrado
     *  até que o usuário ligue a captura nas Configurações do app — menu do
     *  jogo -> "Logs de diagnóstico" (ou long-press no ícone -> shortcut).
     *  Ao ligar, a sessão começa NA HORA (setEnabled inicia a sessão); não
     *  é preciso reabrir o app. */
    private static final boolean DEFAULT_ENABLED = false;

    private static final int KEEP_FILES = 5;
    private static final long MAX_FILE_BYTES = 8L * 1024 * 1024;
    /** Flush de linhas D/I/V a cada N (E/F/W são imediatos). */
    private static final int FLUSH_EVERY_N = 25;

    private static final Object LOCK = new Object();
    private static DiagnosticsLogger sInstance;

    /** Estado da sessão atual (null = sem captura). */
    private Session session;

    private DiagnosticsLogger() {}

    public static DiagnosticsLogger get() {
        synchronized (LOCK) {
            if (sInstance == null) sInstance = new DiagnosticsLogger();
            return sInstance;
        }
    }

    // ==================================================================
    // API pública (chamadas defensivas — nunca lançam)
    // ==================================================================

    /** onCreate da MainActivity: inicia a sessão (se ativada) o mais cedo possível. */
    public static void onMainActivityCreate(Context ctx) {
        try {
            if (isEnabled(ctx)) get().startSession(ctx.getApplicationContext(), "abertura do app");
        } catch (Throwable t) {
            Log.w(TAG, "Diagnostics: falha ao iniciar sessão", t);
        }
    }

    /** onDestroy da MainActivity: encerra com resumo ANTES do killProcess. */
    public static void onMainActivityDestroy() {
        try {
            get().endSession("app encerrado (onDestroy)");
        } catch (Throwable t) {
            Log.w(TAG, "Diagnostics: falha ao encerrar sessão", t);
        }
    }

    /** Marcador de ciclo de vida no log (onResume/onPause/surface etc.). */
    public static void mark(String label) {
        try {
            get().writeMark(label);
        } catch (Throwable ignored) { }
    }

    /** Ativa/desativa a captura AO VIVO (toggle da DiagnosticsActivity). */
    public static void setEnabled(Context ctx, boolean enabled) {
        try {
            prefs(ctx.getApplicationContext()).edit()
                    .putBoolean(PREF_ENABLED, enabled).apply();
            if (enabled) {
                get().startSession(ctx.getApplicationContext(), "ativada pelo usuário");
            } else {
                get().endSession("desativada pelo usuário");
            }
        } catch (Throwable t) {
            Log.w(TAG, "Diagnostics: falha ao mudar captura", t);
        }
    }

    public static boolean isEnabled(Context ctx) {
        try {
            return prefs(ctx.getApplicationContext()).getBoolean(PREF_ENABLED, DEFAULT_ENABLED);
        } catch (Throwable t) {
            return DEFAULT_ENABLED;
        }
    }

    /** Diretório de diagnóstico (external preferido; fallback internal). */
    public static File getDiagnosticsDir(Context ctx) {
        File base = ctx.getExternalFilesDir(null);
        File dir = (base != null) ? new File(base, "diagnostics")
                                  : new File(ctx.getFilesDir(), "diagnostics");
        if (!dir.isDirectory()) dir.mkdirs();
        return dir;
    }

    /** Arquivos de log, MAIS RECENTES primeiro. */
    public static List<File> listLogFiles(Context ctx) {
        File[] files = getDiagnosticsDir(ctx).listFiles();
        List<File> out = new ArrayList<>();
        if (files != null) {
            for (File f : files) if (f.isFile() && f.getName().endsWith(".log")) out.add(f);
            out.sort(Comparator.comparingLong(File::lastModified).reversed());
        }
        return out;
    }

    /** Sessão atual em andamento (ou null). */
    public static File currentSessionFile() {
        synchronized (LOCK) {
            return (get().session != null) ? get().session.file : null;
        }
    }

    // ==================================================================
    // Sessão
    // ==================================================================

    private static final class Session {
        final File file;
        final FileOutputStream fos;
        final BufferedWriter writer;
        // logcat/reader/previousHandler são preenchidos logo APÓS o ctor
        // (início da sessão é multi-passo), por isso não são final.
        Process logcat;
        Thread reader;
        /** Handler de uncaught anterior (restaurado no fim da sessão). */
        Thread.UncaughtExceptionHandler previousHandler;
        long bytes;
        boolean capped;
        // Contadores p/ o resumo (dedup por nível+tag+mensagem truncada).
        final Map<String, Integer> errors = new LinkedHashMap<>();
        final Map<String, Integer> warns = new LinkedHashMap<>();
        long total, eCount, wCount, iCount, dCount, vCount, fCount;

        Session(File file, FileOutputStream fos, BufferedWriter writer) {
            this.file = file; this.fos = fos; this.writer = writer;
        }
    }

    private void startSession(Context appCtx, String motivo) {
        synchronized (LOCK) {
            if (session != null) return; // já capturando
            try {
                File dir = getDiagnosticsDir(appCtx);
                File file = new File(dir, sessionFileName());
                FileOutputStream fos = new FileOutputStream(file);
                BufferedWriter w = new BufferedWriter(
                        new OutputStreamWriter(fos, Charset.forName("UTF-8")), 16 * 1024);

                Session s = new Session(file, fos, w);
                session = s;
                writeHeader(appCtx, s, motivo);

                // Handler de crash Java (encadeia o anterior).
                Thread.UncaughtExceptionHandler prev =
                        Thread.getDefaultUncaughtExceptionHandler();
                s.previousHandler = prev;
                Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
                    try {
                        Session cur = session; // captura local
                        if (cur == s) {
                            session = null; // drainLogcat para de gravar
                            writeCrash(s, thread, throwable);
                            writeSummary(s, "CRASH — resumo gerado no instante do crash");
                            closeWriters(s);
                        }
                    } catch (Throwable ignored) { }
                    Thread.UncaughtExceptionHandler p = s.previousHandler;
                    if (p != null) p.uncaughtException(thread, throwable);
                });

                // Captura do logcat do próprio processo.
                final int pid = android.os.Process.myPid();
                try {
                    Process lc = new ProcessBuilder()
                            .command("logcat", "-v", "time", "--pid=" + String.valueOf(pid), "-T", "1")
                            .redirectErrorStream(true)
                            .start();
                    s.logcat = lc;
                    Thread reader = new Thread(() -> drainLogcat(s, lc), "dk64-diag-logcat");
                    reader.setDaemon(true);
                    reader.start();
                    s.reader = reader;
                    writeMark("captura de logcat iniciada (pid=" + pid + ")");
                } catch (Throwable t) {
                    Log.w(TAG, "Diagnostics: logcat indisponível — só lifecycle/crash", t);
                    writeMark("AVISO: logcat indisponível (" + t.getMessage() + ")");
                }
                Log.i(TAG, "Diagnostics: sessão iniciada -> " + file.getName());
                pruneOldFiles(dir, file);
            } catch (Throwable t) {
                Log.w(TAG, "Diagnostics: não foi possível iniciar a sessão", t);
                Session s = session;
                session = null;
                if (s != null) closeWriters(s);
            }
        }
    }

    /** Mantém só os KEEP_FILES-1 logs mais recentes ALÉM do arquivo atual. */
    private static void pruneOldFiles(File dir, File current) {
        try {
            File[] files = dir.listFiles();
            if (files == null) return;
            List<File> logs = new ArrayList<>();
            for (File f : files) {
                if (f.isFile() && f.getName().endsWith(".log") && !f.equals(current)) {
                    logs.add(f);
                }
            }
            if (logs.size() < KEEP_FILES) return;
            logs.sort(Comparator.comparingLong(File::lastModified).reversed());
            for (int i = KEEP_FILES - 1; i < logs.size(); i++) {
                try { logs.get(i).delete(); } catch (Throwable ignored) { }
            }
        } catch (Throwable ignored) { }
    }

    private void endSession(String motivo) {
        synchronized (LOCK) {
            Session s = session;
            if (s == null) return;
            session = null; // drainLogcat para; nenhum mark() novo entra
            try {
                // Direto em `s` — o campo session já é null aqui.
                writeLine(s, "---- [" + now() + "] fim da sessão: " + motivo + " ----\n", true);
                writeSummary(s, null);
            } catch (Throwable ignored) { }
            try {
                closeWriters(s);
                if (s.logcat != null) s.logcat.destroy(); // encerra o processo logcat
            } catch (Throwable ignored) { }
            // Restaura o handler de crash padrão do sistema.
            try {
                if (Thread.getDefaultUncaughtExceptionHandler() != null
                        && s.previousHandler != null) {
                    Thread.setDefaultUncaughtExceptionHandler(s.previousHandler);
                }
            } catch (Throwable ignored) { }
            Log.i(TAG, "Diagnostics: sessão encerrada -> " + s.file.getName());
        }
    }

    // ==================================================================
    // Gravação
    // ==================================================================

    private void writeHeader(Context appCtx, Session s, String motivo) throws IOException {
        StringBuilder b = new StringBuilder();
        b.append("================================================================\n");
        b.append("DK64: Recompiled — LOG DE DIAGNÓSTICO\n");
        b.append("Sessão iniciada : ").append(now()).append("\n");
        b.append("Motivo          : ").append(motivo).append("\n");
        b.append("App             : ").append(appVersion(appCtx)).append("\n");
        b.append("Dispositivo     : ").append(Build.MANUFACTURER).append(' ')
                .append(Build.MODEL).append(" — Android ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        b.append("Driver Vulkan   : ").append(driverInfo(appCtx)).append("\n");
        b.append("Captura         : logcat do próprio processo (todas as tags/levels)\n");
        b.append("Cobertura       : áudio, renderização (Vulkan/RT64/swapchain), driver,\n");
        b.append("                  ciclo de vida, crashes. Gravação linha a linha:\n");
        b.append("                  mesmo com fechamento à força, o que foi registrado\n");
        b.append("                  até ali PERMANECE salvo neste arquivo.\n");
        b.append("================================================================\n");
        writeLine(s, b.toString(), true);
    }

    private void writeMark(String label) {
        synchronized (LOCK) {
            Session s = session;
            if (s == null) return;
            try {
                writeLine(s, "---- [" + now() + "] " + label + " ----\n", true);
            } catch (Throwable ignored) { }
        }
    }

    private void writeCrash(Session s, Thread thread, Throwable t) throws IOException {
        synchronized (LOCK) {
            StringBuilder b = new StringBuilder();
            b.append("\n================================================================\n");
            b.append("!!! CRASH CAPTURADO !!!  [").append(now()).append("]\n");
            b.append("Thread: ").append(thread.getName()).append("\n");
            b.append("Exceção: ").append(t.getClass().getName())
                    .append(": ").append(t.getMessage()).append("\n");
            b.append("Stacktrace:\n");
            for (StackTraceElement e : t.getStackTrace()) b.append("  at ").append(e).append('\n');
            Throwable cause = t.getCause();
            int depth = 0;
            while (cause != null && depth < 5) {
                b.append("Causado por: ").append(cause.getClass().getName())
                        .append(": ").append(cause.getMessage()).append('\n');
                for (StackTraceElement e : cause.getStackTrace()) b.append("  at ").append(e).append('\n');
                cause = cause.getCause();
                depth++;
            }
            b.append("================================================================\n");
            writeLine(s, b.toString(), true);
        }
    }

    /** Bloco de RESUMO AUTOMÁTICO: deixa claro ONDE está o problema. */
    private void writeSummary(Session s, String nota) {
        synchronized (LOCK) {
            try {
                StringBuilder b = new StringBuilder();
                b.append("\n================================================================\n");
                b.append("RESUMO AUTOMÁTICO DA SESSÃO");
                if (nota != null) b.append(" (").append(nota).append(')');
                b.append('\n');
                b.append(String.format(Locale.US,
                        "Linhas capturadas: %d  (F:%d E:%d W:%d I:%d D:%d V:%d)\n",
                        s.total, s.fCount, s.eCount, s.wCount, s.iCount, s.dCount, s.vCount));
                if (s.capped) b.append("ATENÇÃO: captura interrompida por limite de tamanho (8 MB).\n");
                if (s.errors.isEmpty() && s.warns.isEmpty()) {
                    b.append("Nenhum erro (E/F) ou aviso (W) registrado nesta sessão.\n");
                } else {
                    if (!s.errors.isEmpty()) {
                        b.append("\nPROBLEMAS (erros E/F mais frequentes) — o primeiro é o\n");
                        b.append("mais provável culpado; as linhas completas estão acima:\n");
                        dumpTop(s, b, s.errors);
                    }
                    if (!s.warns.isEmpty()) {
                        b.append("\nAVISOS (W) mais frequentes:\n");
                        dumpTop(s, b, s.warns);
                    }
                }
                b.append("================================================================\n");
                writeLine(s, b.toString(), true);
            } catch (Throwable ignored) { }
        }
    }

    private void dumpTop(Session s, StringBuilder b, Map<String, Integer> map) {
        int n = 0;
        for (Map.Entry<String, Integer> e : map.entrySet()) {
            b.append(String.format(Locale.US, "  %3dx  %s\n", e.getValue(), e.getKey()));
            if (++n >= 25) break;
        }
    }

    /** Escreve + flush (flush=true força sync imediato — sobrevive a SIGKILL). */
    private void writeLine(Session s, String line, boolean flush) throws IOException {
        if (s.capped) return;
        s.writer.write(line);
        if (flush) s.writer.flush();
        s.bytes += line.length();
        if (s.bytes > MAX_FILE_BYTES) {
            s.capped = true;
            try {
                s.writer.flush();
                s.writer.write("\n[CAPTURA PAUSADA: limite de 8 MB desta sessão atingido]\n");
                s.writer.flush();
            } catch (Throwable ignored) { }
        }
    }

    private void closeWriters(Session s) {
        try { s.writer.flush(); } catch (Throwable ignored) { }
        try { s.fos.getFD().sync(); } catch (Throwable ignored) { } // fsync antes do close
        try { s.writer.close(); } catch (Throwable ignored) { }
    }

    // ==================================================================
    // Leitor de logcat
    // ==================================================================

    /*
     * Ruído benigno CONHECIDO, filtrado antes de contar/escrever — sem isto
     * o log fica ilegível (centenas de linhas por sessão) e o resumo acusa
     * falsos erros/avisos. Cada padrão foi verificado num log real:
     *  - "Access denied finding property": leitura de props vendor.mesa.* e
     *    vendor.display.* pelo Turnip/Mesa e pelo SDL em aparelho SEM root
     *    — comportamento normal, sem qualquer efeito.
     *  - Sondas de formato do gralloc (4x4) na inicialização do RT64/plume:
     *    o gralloc da Qualcomm não mapeia os formatos de sonda (0x38/0x3b)
     *    e a alocação 4x4 falha — a sonda serve exatamente para marcar o
     *    formato como não-suportado. Esperado em todo Adreno + Turnip.
     *  - "AUDIO_OUTPUT_FLAG_FAST denied by client": fallback de latência do
     *    AAudio (TRANSFER_SYNC nunca recebe fast track) — sem impacto.
     *  - "libdolphin.so": lib proprietária de game-boost da Qualcomm que o
     *    stack de perf do vendor tenta carregar dentro do processo (o app
     *    é reconhecido como jogo); opcional e ausente em vários aparelhos.
     *  - InteractionJankMonitor / "Unknown dataspace 0": avisos do framework
     *    sem efeito funcional.
     */
    private static final String[] NOISE_SUBSTRINGS = {
            "Access denied finding property",
            "GetGpuPixelFormat: No map for format",
            "validate_memory_layout_input_parmas",
            "adreno_init_memory_layout",
            "Graphics metadata init failed",
            "isSupported(1, 1,",
            "Failed to allocate (4 x 4)",
            "GraphicBuffer(w=4, h=4",
            "AUDIO_OUTPUT_FLAG_FAST denied by client",
            "Unable to open libdolphin.so",
            "InteractionJankMonitor",
            "Unknown dataspace 0",
    };

    private static boolean isNoise(String line) {
        for (String n : NOISE_SUBSTRINGS) {
            if (line.contains(n)) return true;
        }
        return false;
    }

    private void drainLogcat(Session s, Process lc) {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(lc.getInputStream(), Charset.forName("UTF-8")), 16 * 1024)) {
            int unimportantSinceFlush = 0;
            String line;
            while ((line = r.readLine()) != null) {
                if (isNoise(line)) continue; // ruído benigno conhecido: não vira log nem estatística
                char level = levelOf(line);
                boolean important = (level == 'E' || level == 'F' || level == 'W');
                boolean flush = important || (++unimportantSinceFlush >= FLUSH_EVERY_N);
                if (unimportantSinceFlush >= FLUSH_EVERY_N) unimportantSinceFlush = 0;
                synchronized (LOCK) {
                    if (session != s) break; // sessão trocada/encerrada
                    count(s, level, line);
                    try { writeLine(s, line + '\n', flush); } catch (Throwable ignored) { }
                }
            }
        } catch (Throwable ignored) {
            // logcat morreu (ex.: processo encerrado) — fim silencioso.
        }
    }

    /** Nível de uma linha `logcat -v time`: "MM-dd HH:mm:ss.mmm L/TAG( pid): msg". */
    private static char levelOf(String line) {
        // Procura " X/" com X em [VDIWEF] após os 19 chars de timestamp.
        for (int i = 19; i < line.length() - 2 && i < 32; i++) {
            char c = line.charAt(i);
            if (c == '/' && i > 0) {
                char lvl = line.charAt(i - 1);
                if ("VDIWEF".indexOf(lvl) >= 0) return lvl;
                return '?';
            }
            if (c == ' ' && i > 20) break;
        }
        return '?';
    }

    private static void count(Session s, char level, String line) {
        s.total++;
        switch (level) {
            case 'F': s.fCount++; collect(s.errors, line); break;
            case 'E': s.eCount++; collect(s.errors, line); break;
            case 'W': s.wCount++; collect(s.warns, line); break;
            case 'I': s.iCount++; break;
            case 'D': s.dCount++; break;
            case 'V': s.vCount++; break;
            default: break;
        }
    }

    /** Guarda "TAG: mensagem" (dedup, sem timestamp/pid) — máx 40 entradas. */
    private static void collect(Map<String, Integer> map, String line) {
        String key;
        int slash = line.indexOf('/');
        int paren = line.indexOf("): ", slash);
        if (slash > 0 && paren > slash) {
            // "MM-dd HH:mm:ss.mmm E/SDL     ( 1234): msg" -> "SDL: msg"
            int tagEnd = line.indexOf(' ', slash + 1);
            String tag = (tagEnd > slash && tagEnd < paren)
                    ? line.substring(slash + 1, tagEnd)
                    : line.substring(slash + 1, paren + 1);
            key = tag.trim() + ": " + line.substring(paren + 3);
        } else {
            key = line; // formato inesperado: guarda a linha inteira
        }
        if (key.length() > 160) key = key.substring(0, 160) + "…";
        Integer prev = map.get(key);
        if (prev != null) {
            map.put(key, prev + 1);
        } else if (map.size() < 40) {
            map.put(key, 1);
        }
    }

    // ==================================================================
    // Utilidades
    // ==================================================================

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                .format(new Date());
    }

    private static String sessionFileName() {
        String ts = new SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(new Date());
        return "dk64-diag-" + ts + ".log";
    }

    private static String appVersion(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0);
            return pi.versionName + " (versionCode " + pi.versionCode + ")";
        } catch (Throwable t) {
            return "desconhecida";
        }
    }

    /** Conteúdo de files/driver/selected.txt (driver Vulkan em uso). */
    private static String driverInfo(Context ctx) {
        try {
            File f = new File(ctx.getFilesDir(), "driver/selected.txt");
            if (f.isFile()) {
                java.util.Scanner sc = new java.util.Scanner(f, "UTF-8");
                StringBuilder b = new StringBuilder();
                while (sc.hasNextLine()) {
                    String l = sc.nextLine().trim();
                    if (!l.isEmpty()) {
                        if (b.length() > 0) b.append("; ");
                        b.append(l);
                    }
                }
                sc.close();
                String v = b.toString();
                return v.isEmpty() ? "padrão do sistema (selected.txt vazio)"
                                   : (v.length() > 300 ? v.substring(0, 300) + "…" : v);
            }
        } catch (Throwable ignored) { }
        return "padrão do sistema (nenhum driver custom instalado)";
    }
}
