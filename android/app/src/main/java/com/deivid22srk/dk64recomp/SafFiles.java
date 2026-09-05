package com.deivid22srk.dk64recomp;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Port Android do DK64: Recompiled — utilitário SAF para a ROM escolhida na
 * interface do jogo (opção "Load ROM" do menu launcher, via file_bridge).
 *
 * O Uri do SAF não é um caminho de filesystem (std::ifstream do select_rom não
 * consegue ler content://), então copiamos para o filesDir e devolvemos o
 * caminho real para o nativo. Efeito colateral útil: o arquivo copiado é
 * detectado por androidport::find_rom_file nos próximos launches (a ROM
 * validada vive no config do recomp, mas a cópia facilita reinstalar/limpar).
 */
final class SafFiles {

    private static final String TAG = "DK64Recomp";

    /** ROM NTSC-U: 32 MiB; margem p/ covers/headers e versões truncadas. */
    private static final long MAX_ROM_BYTES = 64L * 1024 * 1024;

    /**
     * Mods (.nrm/.rtz e zips de pacotes) podem ser bem maiores que uma ROM
     * (texture packs); 512 MiB cobre qualquer mod razoável e ainda protege
     * contra escolhas acidentais de arquivos gigantes.
     */
    private static final long MAX_MOD_BYTES = 512L * 1024 * 1024;

    /** Diretório de staging para mods escolhidos no SAF (filesDir/mods_staging). */
    private static File modsStagingDir(Context ctx) {
        return new File(ctx.getFilesDir(), "mods_staging");
    }

    /**
     * Limpa o staging de mods. Chamado ANTES de cada sessão de cópia: os
     * caminhos de staging são entregues ao ModInstaller, que COPIA para a
     * pasta mods definitiva — sobrar arquivos antigos aqui só desperdiça
     * espaço (o installer nunca lê o staging fora da sessão atual).
     */
    static void clearModsStaging(Context ctx) {
        File dir = modsStagingDir(ctx);
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    /**
     * Copia um mod escolhido no SAF para o staging (filesDir/mods_staging),
     * preservando o nome real do arquivo — o ModInstaller valida o conteúdo
     * (.nrm/.rtz/zip com manifest) e mostra erro claro se não for um mod.
     *
     * Diferente da ROM, NADA é renomeado aqui: .nrm/.rtz são extensões
     * significativas para o instalador (container de mod vs texture pack) e
     * zips de pacote são escaneados em busca de mods internos.
     *
     * @return caminho absoluto do arquivo copiado (legível pelo nativo).
     */
    static String copyModToModsStaging(Context ctx, Uri uri) throws IOException, SecurityException {
        String name = queryDisplayName(ctx, uri);
        if (name == null) name = "mod";
        // Sanitiza: sem diretórios e sem caracteres de controle — o payload
        // do file_bridge usa '\n' como delimitador entre caminhos.
        name = name.substring(name.lastIndexOf('/') + 1).trim().replaceAll("[\\x00-\\x1f]", "_");
        if (name.isEmpty()) name = "mod";

        File dir = modsStagingDir(ctx);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Não foi possível criar o diretório de staging de mods.");
        }

        File dest = new File(dir, name);
        // Cópia atômica: grava em .tmp e renomeia — evita mod parcial.
        File tmp = new File(dir, name + ".tmp");
        try {
            try (InputStream in = ctx.getContentResolver().openInputStream(uri);
                 OutputStream out = new FileOutputStream(tmp)) {
                if (in == null) throw new IOException("Não foi possível abrir o arquivo selecionado.");
                byte[] buf = new byte[1 << 16];
                long total = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_MOD_BYTES) throw new IOException("Arquivo maior que 512 MB.");
                    out.write(buf, 0, n);
                }
            }
            if (!tmp.renameTo(dest)) {
                // fallback raro (rename entre filesystems): cópia direta
                try (InputStream in = new java.io.FileInputStream(tmp);
                     OutputStream out = new FileOutputStream(dest)) {
                    byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                tmp.delete();
            }
        } finally {
            tmp.delete(); // limpa resíduo em qualquer falha (rename OK = no-op)
        }
        Log.i(TAG, "Mod copiado para " + dest.getAbsolutePath());
        return dest.getAbsolutePath();
    }

    private SafFiles() {}

    /** Nome de exibição do Uri (OpenableColumns.DISPLAY_NAME) ou fallback. */
    static String queryDisplayName(Context ctx, Uri uri) {
        try (Cursor c = ctx.getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String name = c.getString(idx);
                    if (name != null && !name.trim().isEmpty()) return name;
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    /**
     * Copia o arquivo escolhido para o filesDir (raiz), com nome sanitizado e
     * extensão N64 garantida (o scan nativo filtra por .z64/.n64/.v64).
     *
     * @return caminho absoluto do arquivo copiado (legível pelo nativo).
     */
    static String copyRomToFilesDir(Context ctx, Uri uri) throws IOException, SecurityException {
        String name = queryDisplayName(ctx, uri);
        if (name == null) name = "rom.z64";
        name = name.substring(name.lastIndexOf('/') + 1).trim();
        if (name.isEmpty()) name = "rom.z64";

        String lower = name.toLowerCase();
        if (!(lower.endsWith(".z64") || lower.endsWith(".n64") || lower.endsWith(".v64"))) {
            name = name + ".z64"; // conteúdo real é detectado pelo select_rom (byteswap)
        }

        File dest = new File(ctx.getFilesDir(), name);
        // Cópia atômica: grava em .tmp e renomeia — evita ROM parcial.
        File tmp = new File(ctx.getFilesDir(), name + ".tmp");
        try {
            try (InputStream in = ctx.getContentResolver().openInputStream(uri);
                 OutputStream out = new FileOutputStream(tmp)) {
                if (in == null) throw new IOException("Não foi possível abrir o arquivo selecionado.");
                byte[] buf = new byte[1 << 16];
                long total = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_ROM_BYTES) throw new IOException("Arquivo maior que 64 MB.");
                    out.write(buf, 0, n);
                }
            }
            if (!tmp.renameTo(dest)) {
                // fallback raro (rename entre filesystems): cópia direta
                try (InputStream in = new java.io.FileInputStream(tmp);
                     OutputStream out = new FileOutputStream(dest)) {
                    byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                tmp.delete();
            }
        } finally {
            tmp.delete(); // limpa resíduo em qualquer falha (rename OK = no-op)
        }
        Log.i(TAG, "ROM copiada para " + dest.getAbsolutePath());
        return dest.getAbsolutePath();
    }
}
