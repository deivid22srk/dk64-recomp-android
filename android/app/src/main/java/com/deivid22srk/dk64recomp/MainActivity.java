package com.deivid22srk.dk64recomp;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.libsdl.app.SDLActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Port Android do DK64: Recompiled.
 *
 * Fluxo:
 *  1. onCreate verifica se há uma ROM compatível em getFilesDir()/getExternalFilesDir(null)
 *     e se os assets do app já foram copiados para o armazenamento interno.
 *  2. Sem ROM -> mostra uma tela própria com botão (SAF) para selecionar o arquivo
 *     (.z64/.n64/.v64) e copiá-lo para a pasta do app; NÃO chama super.onCreate()
 *     (evita iniciar o SDL/game sem ROM).
 *  3. Com ROM -> fluxo normal do SDLActivity: carrega libmain.so e chama SDL_main,
 *     passando os diretórios do app via getArguments() (consumidos nativamente).
 */
public class MainActivity extends SDLActivity {

    private static final String TAG = "DK64Recomp";
    private static final String ASSETS_VERSION_MARKER = ".assets_version";
    private static final String ASSETS_VERSION = "1";
    private static final int PICK_ROM_REQUEST = 0xD864;
    private static final long MAX_ROM_BYTES = 64L * 1024 * 1024;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        if (!isReadyToStart()) {
            showSetupScreen();
            return; // não chama super.onCreate(): SDL só inicia com ROM e assets prontos
        }
        super.onCreate(savedInstanceState);
    }

    @Override
    protected String[] getLibraries() {
        // SDL2 é linkado estaticamente dentro de libmain.so
        return new String[]{ "main" };
    }

    @Override
    protected String[] getArguments() {
        // Consumido em src/main/main.cpp -> androidport::init_from_args()
        // argv[0] = nome, argv[1] = filesDir, argv[2] = externalFilesDir
        return new String[]{
                "dk64recomp",
                getFilesDir().getAbsolutePath(),
                getExternalFilesDir(null).getAbsolutePath()
        };
    }

    // ------------------------------------------------------------------
    // Preparação (ROM + assets)
    // ------------------------------------------------------------------

    private boolean isReadyToStart() {
        boolean rom = findRom() != null;
        boolean assets = assetsReady();
        Log.i(TAG, "isReadyToStart: rom=" + rom + " assets=" + assets);
        return rom && assets;
    }

    private File findRom() {
        File[] dirs = { getFilesDir(), getExternalFilesDir(null) };
        String[] exts = { ".z64", ".n64", ".v64" };
        for (File dir : dirs) {
            if (dir == null) continue;
            File[] files = dir.listFiles();
            if (files == null) continue;
            for (File f : files) {
                if (!f.isFile() || f.getName().startsWith(".")) continue;
                String lower = f.getName().toLowerCase();
                for (String e : exts) {
                    if (lower.endsWith(e)) return f;
                }
            }
        }
        return null;
    }

    private boolean assetsReady() {
        File marker = new File(getFilesDir(), ASSETS_VERSION_MARKER);
        if (!marker.exists()) return false;
        try {
            return ASSETS_VERSION.equals(readTextFile(marker).trim());
        } catch (IOException e) {
            return false;
        }
    }

    private void showSetupScreen() {
        // Verificação de Vulkan 1.1 (RT64 exige em practice; minSdk 26 permite
        // dispositivos com Vulkan apenas 1.0 — avisamos em vez de crashar).
        checkVulkanCapability();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(64, 64, 64, 64);
        root.setBackgroundColor(Color.rgb(0x2B, 0x1D, 0x0E)); // marrom barril

        TextView title = new TextView(this);
        title.setText("DK64: Recompiled (Android)");
        title.setTextSize(26f);
        title.setTextColor(Color.rgb(0xF7, 0xD3, 0x3B));
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        File romDir = getExternalFilesDir(null);
        TextView hint = new TextView(this);
        hint.setText("Coloque a ROM do Donkey Kong 64 (EUA, NTSC-U 1.0) em:\n"
                + romDir.getAbsolutePath()
                + "\n\n…ou selecione o arquivo abaixo. Formatos aceitos: .z64, .n64, .v64\n"
                + "O jogo valida a ROM automaticamente e faz a conversão se necessário.");
        hint.setTextSize(14f);
        hint.setTextColor(Color.WHITE);
        hint.setPadding(0, 40, 0, 40);
        root.addView(hint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button pick = new Button(this);
        pick.setText("Selecionar ROM…");
        pick.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            startActivityForResult(intent, PICK_ROM_REQUEST);
        });
        root.addView(pick, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
    }

    private void checkVulkanCapability() {
        PackageManager pm = getPackageManager();
        boolean vulkan11 = pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_VERSION, 0x00401000 /* 1.1 */);
        if (!vulkan11) {
            new AlertDialog.Builder(this)
                    .setTitle("GPU sem Vulkan 1.1")
                    .setMessage("Este dispositivo não reporta Vulkan 1.1, exigido pelo renderizador RT64. "
                            + "O app pode não iniciar ou apresentar tela preta.\n\n"
                            + "Você ainda pode tentar executar o jogo.")
                    .setPositiveButton("Continuar mesmo assim", (d, w) -> { })
                    .setNegativeButton("Sair", (d, w) -> finish())
                    .show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_ROM_REQUEST || resultCode != Activity.RESULT_OK || data == null
                || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        String name = queryDisplayName(uri);
        if (name == null) name = "rom.z64";
        String lower = name.toLowerCase();
        if (!(lower.endsWith(".z64") || lower.endsWith(".n64") || lower.endsWith(".v64"))) {
            toast("Arquivo não parece ser uma ROM de N64 (.z64/.n64/.v64).");
            return;
        }
        File dest = new File(getExternalFilesDir(null), name);
        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[1 << 16];
            long total = 0; int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_ROM_BYTES) throw new IOException("Arquivo maior que 64 MB.");
                out.write(buf, 0, n);
            }
            Log.i(TAG, "ROM copiada: " + dest.getAbsolutePath() + " (" + total + " bytes)");
        } catch (IOException | SecurityException ex) {
            Log.e(TAG, "Falha ao copiar ROM", ex);
            toast("Falha ao copiar a ROM: " + ex.getMessage());
            return;
        }
        prepareAssets();
        recreate(); // volta ao onCreate, que agora inicia o SDL
    }

    private String queryDisplayName(Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return c.getString(idx);
            }
        } catch (Exception ignored) { }
        return null;
    }

    private void toast(String msg) {
        runOnUiThread(() -> android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show());
    }

    // ------------------------------------------------------------------
    // Cópia dos assets do APK para o armazenamento interno (1ª execução)
    // ------------------------------------------------------------------

    private void prepareAssets() {
        if (assetsReady()) return;
        try {
            File assetsDir = new File(getFilesDir(), "assets");
            copyApkAssets("", assetsDir);
            // recompcontrollerdb.txt vai na raiz (get_program_path()/recompcontrollerdb.txt)
            try (InputStream in = getAssets().open("recompcontrollerdb.txt")) {
                copyStream(in, new File(getFilesDir(), "recompcontrollerdb.txt"));
            } catch (IOException ignored) { /* arquivo opcional */ }
            writeTextFile(new File(getFilesDir(), ASSETS_VERSION_MARKER), ASSETS_VERSION);
            Log.i(TAG, "Assets copiados para " + getFilesDir());
        } catch (IOException ex) {
            Log.e(TAG, "Falha ao copiar assets", ex);
        }
    }

    private void copyApkAssets(String path, File destDir) throws IOException {
        String[] entries = getAssets().list(path);
        if (entries == null) return;
        for (String entry : entries) {
            String rel = path.isEmpty() ? entry : path + "/" + entry;
            if (entry.equals("recompcontrollerdb.txt") && path.isEmpty()) continue;
            String[] children = getAssets().list(rel);
            if (children == null || children.length == 0) {
                File out = new File(destDir, rel);
                File parent = out.getParentFile();
                if (parent != null) parent.mkdirs();
                try (InputStream in = getAssets().open(rel)) {
                    copyStream(in, out);
                }
            } else {
                copyApkAssets(rel, destDir);
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
            byte[] buf = new byte[4096]; int n;
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
