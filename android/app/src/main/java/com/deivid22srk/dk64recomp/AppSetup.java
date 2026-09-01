package com.deivid22srk.dk64recomp;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Port Android do DK64: Recompiled — preparação silenciosa na inicialização.
 *
 * Extraído da SetupActivity (removida): a cópia dos assets do APK para o
 * filesDir agora acontece no onCreate do MainActivity, ANTES do super.onCreate
 * (que inicia a SDLThread). A cópia é bloqueante e roda só na 1ª execução
 * (marcador .assets_version) — nas demais é um stat e nada mais.
 *
 * Mapeamento esperado pelo nativo (get_program_path() = filesDir):
 *   APK assets/&lt;rel&gt;       -&gt; filesDir/assets/&lt;rel&gt;
 *   APK recompcontrollerdb -&gt; filesDir/recompcontrollerdb.txt (raiz!)
 * O AssetManager vê a MESCLAGEM de todos os srcDirs do AGP
 * (../../assets + src/main/assets) num namespace único.
 */
final class AppSetup {

    private static final String TAG = "DK64Recomp";

    private static final String ASSETS_VERSION_MARKER = ".assets_version";
    private static final String ASSETS_VERSION = "1";

    private AppSetup() {}

    /**
     * Garante os assets no filesDir. Bloqueia (chamado na UI thread antes do
     * SDL arrancar); na 1ª execução copia alguns MB — imediato nas seguintes.
     * Falha é logada e NÃO derruba o app (assets de execuções anteriores
     * permanecem válidos; sem eles o recompui degrada, mas não crasha).
     */
    static void ensureAssets(Context ctx) {
        if (assetsReady(ctx)) return;
        try {
            prepareAssets(ctx);
        } catch (IOException ex) {
            Log.e(TAG, "Falha ao copiar assets do APK", ex);
        }
    }

    private static boolean assetsReady(Context ctx) {
        File marker = new File(ctx.getFilesDir(), ASSETS_VERSION_MARKER);
        if (!marker.exists()) return false;
        try {
            return ASSETS_VERSION.equals(readTextFile(marker).trim());
        } catch (IOException e) {
            return false;
        }
    }

    private static void prepareAssets(Context ctx) throws IOException {
        File filesDir = ctx.getFilesDir();
        if (filesDir == null) throw new IOException("filesDir indisponível");
        File assetsDir = new File(filesDir, "assets");
        copyApkAssets(ctx, "", assetsDir);
        // recompcontrollerdb.txt vem do nosso src/main/assets e é OBRIGATÓRIO
        // (SDL_GameControllerAddMappingsFromFile em src/main/main.cpp).
        try (InputStream in = ctx.getAssets().open("recompcontrollerdb.txt")) {
            copyStream(in, new File(filesDir, "recompcontrollerdb.txt"));
        }
        writeTextFile(new File(filesDir, ASSETS_VERSION_MARKER), ASSETS_VERSION);
        Log.i(TAG, "Assets copiados para " + filesDir);
    }

    private static void copyApkAssets(Context ctx, String path, File destDir) throws IOException {
        String[] entries = ctx.getAssets().list(path);
        if (entries == null) return;
        for (String entry : entries) {
            String rel = path.isEmpty() ? entry : path + "/" + entry;
            if (path.isEmpty() && entry.equals("recompcontrollerdb.txt")) continue;
            String[] children = ctx.getAssets().list(rel);
            if (children != null && children.length > 0) {
                copyApkAssets(ctx, rel, destDir);
                continue;
            }
            File out = new File(destDir, rel);
            File parent = out.getParentFile();
            if (parent != null && !parent.isDirectory()) parent.mkdirs();
            try (InputStream in = ctx.getAssets().open(rel)) {
                copyStream(in, out);
            } catch (FileNotFoundException e) {
                // Entrada sem filhos que não abre = diretório vazio; ignora.
                Log.w(TAG, "Asset não aberto (ignorado): " + rel);
            }
        }
    }

    private static void copyStream(InputStream in, File dest) throws IOException {
        try (OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    private static String readTextFile(File f) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        try (InputStream in = new java.io.FileInputStream(f)) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        }
        return bos.toString("UTF-8");
    }

    private static void writeTextFile(File f, String s) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(s.getBytes("UTF-8"));
        }
    }
}
