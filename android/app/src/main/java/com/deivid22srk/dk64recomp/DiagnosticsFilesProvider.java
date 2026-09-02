package com.deivid22srk.dk64recomp;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * Provider MÍNIMO (framework puro, sem AndroidX/FileProvider) para servir
 * SOMENTE os arquivos do diretório de diagnóstico via content://.
 *
 * Por quê: a partir do Android 7 o ACTION_SEND com EXTRA_STREAM exige
 * content:// (FileUriExposedException com file://). O FileProvider padrão
 * vive no AndroidX/support e o app é deliberadamente sem dependências
 * externas — então implementamos o openFile nós mesmos (~70 linhas).
 *
 * Segurança:
 *  - exported=false no manifest; o acesso é concedido em tempo de
 *    compartilhamento pelo FLAG_GRANT_READ_URI_PERMISSION.
 *  - Só aceita NOMES simples (sem barra, sem "..") que existam no diretório
 *    de diagnóstico — impossível "escapar" para outros arquivos do app.
 *  - Apenas LEITURA (MODE_READ_ONLY).
 */
public class DiagnosticsFilesProvider extends ContentProvider {

    @Override
    public boolean onCreate() {
        return true;
    }

    /** Authority registrada no manifest: ${applicationId}.diagnostics */
    public static String authority(Context ctx) {
        return ctx.getPackageName() + ".diagnostics";
    }

    /** URI de compartilhamento para um arquivo de log (nome simples). */
    public static Uri shareUri(Context ctx, String fileName) {
        return Uri.parse("content://" + authority(ctx) + "/" + Uri.encode(fileName));
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (mode != null && mode.contains("w")) {
            throw new FileNotFoundException("Provider de diagnóstico é somente leitura");
        }
        File file = resolve(uri);
        if (file == null) throw new FileNotFoundException("Log não encontrado: " + uri);
        try {
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (IOException e) {
            throw new FileNotFoundException("Falha ao abrir " + file.getName() + ": " + e.getMessage());
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        File file = resolve(uri);
        MatrixCursor cursor = new MatrixCursor(new String[]{"_display_name", "_size"});
        if (file != null && file.isFile()) {
            cursor.addRow(new Object[]{file.getName(), file.length()});
        }
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        return "text/plain";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Provider somente leitura");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    // ------------------------------------------------------------------

    /** Resolve o nome simples -> arquivo no diretório de diagnóstico (ou null). */
    private File resolve(Uri uri) {
        try {
            String name = uri.getLastPathSegment();
            if (name == null || name.isEmpty()) return null;
            // Sanitização estrita: nome de arquivo simples, sem caminho.
            if (name.contains("/") || name.contains("\\") || name.contains("..")) return null;
            File dir = DiagnosticsLogger.getDiagnosticsDir(getContext());
            File candidate = new File(dir, name);
            File canon = candidate.getCanonicalFile();
            File canonDir = dir.getCanonicalFile();
            if (!canon.getPath().startsWith(canonDir.getPath() + File.separator)) return null;
            return (canon.isFile()) ? canon : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
