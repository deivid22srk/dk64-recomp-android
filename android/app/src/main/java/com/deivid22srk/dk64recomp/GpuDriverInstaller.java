package com.deivid22srk.dk64recomp;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Port Android do DK64: Recompiled — instalação do driver Vulkan custom (Turnip).
 *
 * Extraído da SetupActivity (removida): a seleção de driver agora acontece na
 * PRÓPRIA interface do jogo — a opção "GPU Driver" do menu launcher abre o
 * DocumentsUI via MainActivity (SAF) e entrega o Uri aqui. O fluxo é:
 *
 *   1. {@link #installFromUri} extrai e valida o pacote em
 *      filesDir/driver/installed/&lt;id&gt;/ e grava filesDir/driver/selected.txt
 *      (formato KEY=VALUE consumido pelo nativo custom_driver.cpp);
 *   2. {@link #probeStatusText} chama o probe nativo (mesma via do jogo:
 *      adrenotools + VkInstance) e devolve um texto pronto para exibição —
 *      recusando (rollback automático) drivers sem suporte à GPU do aparelho.
 *
 * Formatos aceitos (paridade com redahm-android, que carrega drivers sem
 * problemas no moto g34 5G):
 *  - .zip adrenotools (meta.json + vulkan.ad*.so): extrai sob o prefixo do
 *    meta.json e usa o soname dele (libraryName/library);
 *  - .zip Winlator/WN-Turnip (SEM meta.json, libs em subpastas): extrai todos
 *    os *.so ACHATADOS na raiz e autodetecta o soname;
 *  - .so solto: copiado como driver de arquivo único.
 */
public final class GpuDriverInstaller {

    private static final String TAG = "DK64Recomp";

    // Layout em disco — MESMO contrato lido por android/native/compat/custom_driver.cpp.
    private static final String DRIVER_BASE = "driver";              // filesDir/driver
    private static final String DRIVER_SELECTED = "selected.txt";    // filesDir/driver/selected.txt
    private static final String DRIVER_INSTALLED = "installed";      // filesDir/driver/installed/<id>/

    private static final long MAX_DRIVER_ZIP_BYTES = 512L * 1024 * 1024;
    private static final int MAX_DRIVER_ENTRIES = 128;

    private static final class DriverMeta {
        String name;
        String library;
        String dir;
    }

    private GpuDriverInstaller() {}

    /**
     * Extrai, valida e seleciona o driver. Retorna o nome amigável.
     * Lança Exception com mensagem já amigável em caso de falha.
     */
    public static String installFromUri(Context ctx, Uri uri, String displayName) throws Exception {
        File base = new File(ctx.getFilesDir(), DRIVER_BASE);
        File installedRoot = new File(base, DRIVER_INSTALLED);
        if (!installedRoot.isDirectory() && !installedRoot.mkdirs()) {
            throw new IOException("não foi possível criar " + installedRoot);
        }

        String id = sanitizeId(displayName) + "-" + Long.toString(System.currentTimeMillis(), 36);
        File target = new File(installedRoot, id);
        if (!target.isDirectory() && !target.mkdirs()) {
            throw new IOException("não foi possível criar " + target);
        }

        try {
            String library;
            String friendly = displayName;
            int minApi = 0;
            String lower = displayName.toLowerCase();

            if (lower.endsWith(".so")) {
                // Driver de arquivo único (.so solto)
                String soName = sanitizeId(displayName);
                if (!soName.toLowerCase().endsWith(".so")) soName = soName + ".so";
                try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
                    if (in == null) throw new IOException("não foi possível abrir o arquivo");
                    copyStreamTo(in, new File(target, soName));
                }
                library = soName;
            } else {
                // 1ª passada: meta.json é OPCIONAL (zips Winlator/WN não têm)
                String prefix = null;
                JSONObject meta = null;
                try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
                    if (in == null) throw new IOException("não foi possível abrir o arquivo");
                    Object[] found = findMetaJson(in);
                    if (found != null) {
                        prefix = (String) found[0];
                        meta = new JSONObject((String) found[1]);
                    }
                }

                if (meta != null) {
                    // Formato adrenotools: extrai sob o prefixo do meta.json
                    extractZipUnderPrefix(ctx, uri, prefix, target);
                    minApi = meta.optInt("minApi", 0);
                    library = meta.optString("libraryName", meta.optString("library", ""));
                    if (library.isEmpty()) library = null;
                    String metaName = meta.optString("name", "");
                    if (!metaName.isEmpty()) friendly = metaName;
                    else if (!meta.optString("driverVersion").isEmpty()) {
                        friendly = "Turnip " + meta.optString("driverVersion");
                    }
                } else {
                    // Sem meta.json: achata todos os *.so na raiz
                    extractZipFlattened(ctx, uri, target);
                    library = null;
                    // Nome amigável: sem a extensão .zip
                    if (lower.endsWith(".zip")) {
                        friendly = displayName.substring(0, displayName.length() - 4);
                    }
                }

                if (library == null) library = findPreferredDriverSo(target);
                if (library == null || library.isEmpty()) {
                    throw new IOException("nenhum .so de driver (libvulkan*/vulkan*) "
                            + "encontrado no arquivo");
                }

                // Tolerância: soname do meta dentro de subpasta — traz p/ raiz
                File libFile = new File(target, library);
                if (!libFile.isFile() || libFile.length() == 0) {
                    File nested = findFileRecursively(target, library);
                    if (nested != null) {
                        copyStreamTo(new FileInputStream(nested), libFile);
                    } else {
                        throw new IOException(".so do driver ('" + library
                                + "') não veio no arquivo");
                    }
                }
            }

            if (minApi > Build.VERSION.SDK_INT) {
                throw new IOException("driver exige Android " + minApi + "+ (este device: "
                        + Build.VERSION.SDK_INT + ")");
            }

            DriverMeta out = new DriverMeta();
            out.dir = target.getAbsolutePath();
            out.library = library;
            out.name = friendly;

            // Seleção (KEY=VALUE, lido pelo nativo)
            StringBuilder sb = new StringBuilder();
            sb.append("dir=").append(out.dir).append('\n');
            sb.append("library=").append(out.library).append('\n');
            sb.append("name=").append(out.name).append('\n');
            writeTextFile(new File(base, DRIVER_SELECTED), sb.toString());

            // Limpa instalações antigas (mantém a atual)
            File[] others = installedRoot.listFiles();
            if (others != null) {
                for (File d : others) {
                    if (!d.getAbsolutePath().equals(target.getAbsolutePath())) {
                        deleteRecursively(d);
                    }
                }
            }
            return out.name;
        } catch (Exception ex) {
            deleteRecursively(target);
            throw ex;
        }
    }

    /**
     * Probe (validação empírica) do driver recém-instalado, exatamente como a
     * SetupActivity fazia: carrega pela via do jogo (adrenotools) e cria uma
     * VkInstance. Drivers recusados têm a seleção revertida (rollback).
     *
     * @return texto em inglês pronto para a caixa de mensagem do menu do jogo.
     */
    public static String probeStatusText(Context ctx, String friendlyName) {
        String probeJson = null;
        try {
            // Paths do app: filesDir (onde vive selected.txt) e nativeLibraryDir
            // (hookLibDir exigido pelo libadrenotools).
            probeJson = nativeProbeCustomDriver(ctx.getFilesDir().getAbsolutePath(),
                    ctx.getApplicationInfo().nativeLibraryDir);
        } catch (Throwable t) {
            Log.w(TAG, "Probe nativo indisponível — driver aceito sem validação", t);
        }

        if (probeJson == null) {
            return "Driver \"" + friendlyName + "\" installed.\n\n"
                    + "It will be used the next time the app starts.";
        }

        try {
            JSONObject j = new JSONObject(probeJson);
            final boolean active = j.optBoolean("active", false);
            final boolean ok = j.optBoolean("ok", false);
            final String device = j.optString("device", "");
            final String api = j.optString("api", "");
            final String probeError = j.optString("error", "");
            Log.i(TAG, "Probe do driver: " + probeJson);

            if (active && ok) {
                return "Driver \"" + friendlyName + "\" installed and verified: "
                        + (device.isEmpty() ? "Vulkan GPU" : device)
                        + (api.isEmpty() ? "" : " (Vulkan " + api + ")")
                        + ".\n\nIt will be used the next time the app starts.";
            }

            // Recusado — rollback da seleção (mesma política da SetupActivity).
            removeSelection(ctx);
            if (active) {
                return "Driver \"" + friendlyName + "\" was rejected: it does not expose "
                        + "any Vulkan GPU on this device"
                        + (probeError.isEmpty() ? "" : " (" + probeError + ")")
                        + ".\n\nThis usually means the build doesn't support your GPU "
                        + "generation. Install a compatible Turnip build — for Adreno 6xx "
                        + "GPUs use the 'a6xx' builds from K11MCH1/AdrenoToolsDrivers — "
                        + "and try again. The system driver remains in use.";
            }
            return "Driver \"" + friendlyName + "\" could not be loaded"
                    + (probeError.isEmpty() ? "" : ": " + probeError)
                    + ".\n\nThe system driver remains in use. Make sure the package is "
                    + "arm64 and matches your GPU generation (adrenotools zip with "
                    + "meta.json, Winlator/Turnip zip or a bare .so); see logcat "
                    + "(tag DK64Recomp) for the exact reason.";
        } catch (Exception ex) {
            Log.w(TAG, "Probe JSON inválido", ex);
            return "Driver \"" + friendlyName + "\" installed (probe unavailable).\n\n"
                    + "It will be used the next time the app starts.";
        }
    }

    /** Remove seleção + instalações (volta ao driver do sistema). */
    public static void removeSelection(Context ctx) {
        try {
            File base = new File(ctx.getFilesDir(), DRIVER_BASE);
            new File(base, DRIVER_SELECTED).delete();
            File[] dirs = new File(base, DRIVER_INSTALLED).listFiles();
            if (dirs != null) for (File d : dirs) deleteRecursively(d);
            deleteRecursively(new File(base, "tmp"));
            deleteRecursively(new File(base, "cache"));
            Log.i(TAG, "Seleção de driver removida");
        } catch (Exception ex) {
            Log.e(TAG, "Falha ao remover seleção de driver", ex);
        }
    }

    /**
     * Probe nativo (implementado em android/native/compat/custom_driver.cpp).
     * Devolve JSON {"active":..,"ok":..,"devices":..,"device":..,"api":..,"error":..}.
     */
    public static native String nativeProbeCustomDriver(String filesDir, String nativeLibraryDir);

    // ------------------------------------------------------------------
    // Helpers de extração (portados da SetupActivity sem mudança de comportamento)
    // ------------------------------------------------------------------

    /** Soname principal de um diretório de driver, ordem de preferência:
     *  nomes conhecidos (Winlator/Turnip), depois "libvulkan*" ou "vulkan.*",
     *  depois qualquer .so que não seja o compilador LLVM do Mesa. */
    private static String findPreferredDriverSo(File dir) {
        File[] files = dir == null ? null : dir.listFiles();
        if (files == null) return null;
        for (String preferred : new String[]{"libvulkan_freedreno.so", "libvulkan_turnip.so",
                "libvulkan.so.qualcomm"}) {
            for (File f : files) {
                if (f.isFile() && f.getName().equals(preferred)) return f.getName();
            }
        }
        for (File f : files) {
            String n = f.getName();
            if (f.isFile() && (n.startsWith("libvulkan") || n.startsWith("vulkan."))) return n;
        }
        for (File f : files) {
            String n = f.getName();
            if (f.isFile() && n.endsWith(".so") && !n.contains("llvm")) return n;
        }
        for (File f : files) {
            if (f.isFile() && f.getName().endsWith(".so")) return f.getName();
        }
        return null;
    }

    /**
     * Extrai todos os *.so do zip ACHATADOS na raiz do target (formato dos
     * pacotes Winlator/WN-Turnip, que guardam as libs em subpastas — ex.:
     * "Turnip/25.x/libvulkan_freedreno.so"). Mantém a primeira cópia de cada
     * basename: builds multi-variante repetem o mesmo soname por subpasta.
     */
    private static void extractZipFlattened(Context ctx, Uri uri, File target) throws IOException {
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("não foi possível reabrir o zip");
            ZipInputStream zip = new ZipInputStream(in);
            ZipEntry entry;
            long total = 0;
            int entries = 0;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String base = new File(entry.getName()).getName();
                if (!base.endsWith(".so")) continue;
                if (++entries > MAX_DRIVER_ENTRIES) throw new IOException("zip com entradas demais");
                File out = new File(target, base);
                if (out.exists()) continue; // mantém a 1ª cópia (raiz tem precedência)
                byte[] buf = new byte[1 << 16];
                try (OutputStream os = new FileOutputStream(out)) {
                    int n;
                    while ((n = zip.read(buf)) > 0) {
                        total += n;
                        if (total > MAX_DRIVER_ZIP_BYTES) throw new IOException("driver maior que 512 MB");
                        os.write(buf, 0, n);
                    }
                }
                out.setReadable(true, false);
                out.setExecutable(true, false);
            }
        }
    }

    /** Procura um arquivo por basename em dir (recursivo) ou null. */
    private static File findFileRecursively(File dir, String name) {
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File f : files) {
            if (f.isFile() && f.getName().equals(name)) return f;
        }
        for (File f : files) {
            if (f.isDirectory()) {
                File found = findFileRecursively(f, name);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** Procura meta.json na raiz ou em subdiretórios. Retorna {prefixo, conteúdo} ou null. */
    private static Object[] findMetaJson(InputStream in) throws IOException {
        ZipInputStream zip = new ZipInputStream(in);
        ZipEntry entry;
        while ((entry = zip.getNextEntry()) != null) {
            String name = entry.getName();
            if (entry.isDirectory()) continue;
            if (name.endsWith("/meta.json") || name.equals("meta.json")) {
                String prefix = name.equals("meta.json") ? "" : name.substring(0, name.length() - "meta.json".length());
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = zip.read(buf)) > 0) bos.write(buf, 0, n);
                return new Object[] { prefix, bos.toString("UTF-8") };
            }
        }
        return null;
    }

    private static void extractZipUnderPrefix(Context ctx, Uri uri, String prefix, File target) throws IOException {
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("não foi possível reabrir o zip");
            ZipInputStream zip = new ZipInputStream(in);
            ZipEntry entry;
            long total = 0;
            int entries = 0;
            String canonicalTarget = target.getCanonicalPath() + File.separator;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory() || !name.startsWith(prefix)) continue;
                String rel = name.substring(prefix.length());
                if (rel.isEmpty()) continue;
                if (++entries > MAX_DRIVER_ENTRIES) throw new IOException("zip com entradas demais");
                if (rel.contains("..") || rel.startsWith("/")) {
                    throw new IOException("caminho suspeito no zip: " + name);
                }
                File out = new File(target, rel);
                if (!out.getCanonicalPath().startsWith(canonicalTarget)) {
                    throw new IOException("zip-slip detectado: " + name);
                }
                File parent = out.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("não foi possível criar " + parent);
                }
                byte[] buf = new byte[1 << 16];
                try (OutputStream os = new FileOutputStream(out)) {
                    int n;
                    while ((n = zip.read(buf)) > 0) {
                        total += n;
                        if (total > MAX_DRIVER_ZIP_BYTES) throw new IOException("driver maior que 512 MB");
                        os.write(buf, 0, n);
                    }
                }
            }
        }
    }

    private static void copyStreamTo(InputStream in, File out) throws IOException {
        try (OutputStream os = new FileOutputStream(out)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        }
    }

    private static String sanitizeId(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toLowerCase().toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        String out = sb.toString();
        return out.isEmpty() ? "driver" : out;
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        File[] children = f.listFiles();
        if (children != null) for (File c : children) deleteRecursively(c);
        f.delete();
    }

    private static void writeTextFile(File f, String s) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(s.getBytes("UTF-8"));
        }
    }
}
