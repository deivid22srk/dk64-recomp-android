package com.deivid22srk.dk64recomp;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Port Android do DK64: Recompiled — tela de preparação (launcher).
 *
 * Responsabilidades (antes de qualquer SDL):
 *  1. Verificar Vulkan 1.1 (RT64) — aviso, não bloqueio.
 *  2. Copiar os assets do APK para o filesDir na 1ª execução
 *     (marcador .assets_version): assets/<nome> + recompcontrollerdb.txt na raiz.
 *  3. Garantir uma ROM (.z64/.n64/.v64): scan de filesDir/externalFilesDir
 *     ou seleção via SAF (ACTION_OPEN_DOCUMENT) com cópia para o app.
 *  4. Quando tudo pronto -> startActivity(MainActivity) + finish().
 *
 * Assim o MainActivity (SDLActivity) nunca inicia sem ROM/assets e não
 * precisamos de recreate() nem de manipular estado estático do SDL.
 */
public class SetupActivity extends Activity {

    private static final String TAG = "DK64Recomp";
    private static final String ASSETS_VERSION_MARKER = ".assets_version";
    private static final String ASSETS_VERSION = "1";
    private static final int PICK_ROM_REQUEST = 0xD864;
    private static final long MAX_ROM_BYTES = 64L * 1024 * 1024; // ROM NTSC-U: 32 MiB

    private TextView mStatus;
    private Button mPickButton;

    private volatile boolean mAssetCopyRunning;
    private volatile boolean mRomCopyRunning;
    private boolean mVulkanDialogShown;
    private boolean mVulkanAcknowledged;
    private boolean mHandedOff;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(TAG, "SetupActivity.onCreate");

        buildUi();
        startAssetCopyIfNeeded();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Reavalia ao voltar do SAF / ao trazer o app de segundo plano
        // (a ROM pode ter sido copiada via adb ou gerenciador de arquivos).
        if (!mVulkanDialogShown && !vulkanOk()) {
            showVulkanWarning();
        }
        maybeHandOff();
    }

    // ------------------------------------------------------------------
    // UI
    // ------------------------------------------------------------------

    private void buildUi() {
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

        TextView hint = new TextView(this);
        hint.setText("Coloque a ROM do Donkey Kong 64 (EUA, NTSC-U 1.0) em:\n"
                + romDir().getAbsolutePath()
                + "\n\n…ou selecione o arquivo abaixo. Formatos aceitos: .z64, .n64, .v64\n"
                + "O jogo valida a ROM automaticamente e faz a conversão se necessário.");
        hint.setTextSize(14f);
        hint.setTextColor(Color.WHITE);
        hint.setPadding(0, 40, 0, 40);
        root.addView(hint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mStatus = new TextView(this);
        mStatus.setText("");
        mStatus.setTextSize(13f);
        mStatus.setTextColor(Color.rgb(0xF7, 0xD3, 0x3B));
        mStatus.setGravity(Gravity.CENTER);
        root.addView(mStatus, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mPickButton = new Button(this);
        mPickButton.setText("Selecionar ROM…");
        mPickButton.setOnClickListener(v -> startRomPicker());
        root.addView(mPickButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
    }

    private void setStatus(String text) {
        if (mStatus != null) {
            mStatus.setText(text);
        }
    }

    private void startRomPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, PICK_ROM_REQUEST);
    }

    private void showVulkanWarning() {
        mVulkanDialogShown = true;
        new AlertDialog.Builder(this)
                .setTitle("GPU sem Vulkan 1.1")
                .setMessage("Este dispositivo não reporta Vulkan 1.1, exigido pelo renderizador RT64. "
                        + "O app pode não iniciar ou apresentar tela preta.\n\n"
                        + "Você ainda pode tentar executar o jogo.")
                .setPositiveButton("Continuar mesmo assim", (d, w) -> {
                    mVulkanAcknowledged = true;
                    maybeHandOff();
                })
                .setNegativeButton("Sair", (d, w) -> finish())
                .setCancelable(false)
                .show();
    }

    /**
     * A feature "android.hardware.vulkan.version" (PackageManager.FEATURE_VULKAN_VERSION)
     * e hasSystemFeature(String,int) existem desde a API 24 (minSdk 26). A versão usa a
     * codificação VK_MAKE_VERSION: 1.1 -> (1<<22)|(1<<12)|0 = 0x00401000.
     * hasSystemFeature retorna true se a versão disponível for >= à pedida.
     * Usamos a string literal para não depender do nome da constante no compileSdk.
     */
    private boolean vulkanOk() {
        PackageManager pm = getPackageManager();
        try {
            return pm.hasSystemFeature("android.hardware.vulkan.version", 0x00401000 /* Vulkan 1.1 */);
        } catch (Throwable ignored) {
            // Device/pacote sem a feature declarada: tratamos como não suportado.
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Handoff para o SDL (MainActivity)
    // ------------------------------------------------------------------

    private void maybeHandOff() {
        if (mHandedOff || isFinishing() || isDestroyed()) return;
        if (mRomCopyRunning || mAssetCopyRunning) return;
        if (!mVulkanAcknowledged && !vulkanOk()) return;

        if (!assetsReady()) return; // retry: startAssetCopyIfNeeded roda em onCreate/onResume
        if (findRom() == null) return;

        mHandedOff = true;
        Log.i(TAG, "Setup completo -> MainActivity (SDL)");
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    // ------------------------------------------------------------------
    // ROM (SAF)
    // ------------------------------------------------------------------

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_ROM_REQUEST || resultCode != Activity.RESULT_OK
                || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        String name = queryDisplayName(uri);
        if (name == null) name = "rom.z64";
        name = name.substring(name.lastIndexOf('/') + 1).trim();
        if (name.isEmpty()) name = "rom.z64";
        String lower = name.toLowerCase();
        if (!(lower.endsWith(".z64") || lower.endsWith(".n64") || lower.endsWith(".v64"))) {
            toast("Arquivo não parece ser uma ROM de N64 (.z64/.n64/.v64).");
            return;
        }
        copyRomAsync(uri, name);
    }

    /** Cópia fora da UI thread: ROM de 32 MB na main thread é risco de ANR. */
    private void copyRomAsync(Uri uri, final String name) {
        if (mRomCopyRunning) return;
        mRomCopyRunning = true;
        mPickButton.setEnabled(false);
        setStatus("Copiando ROM…");
        new Thread(() -> {
            String error = null;
            try {
                copyRom(uri, name);
                Log.i(TAG, "ROM copiada: " + name);
            } catch (IOException | SecurityException ex) {
                Log.e(TAG, "Falha ao copiar ROM", ex);
                error = "Falha ao copiar a ROM: " + ex.getMessage();
            }
            final String finalError = error;
            runOnUiThread(() -> {
                mRomCopyRunning = false;
                mPickButton.setEnabled(true);
                setStatus(finalError != null ? finalError : "");
                if (finalError != null) toast(finalError);
                maybeHandOff();
            });
        }, "dk64-rom-copy").start();
    }

    private void copyRom(Uri uri, String name) throws IOException, SecurityException {
        File dest = new File(romDir(), name);
        // Cópia atômica: grava em .tmp e renomeia — evita ROM parcial no
        // diretório escaneado se a cópia falhar no meio (JAVA-1).
        File tmp = new File(romDir(), name + ".tmp");
        try {
            try (InputStream in = getContentResolver().openInputStream(uri);
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
            tmp.delete(); // limpa resíduo em qualquer falha (rename bem-sucedido = no-op)
        }
    }

    private String queryDisplayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return c.getString(idx);
            }
        } catch (Exception ignored) { }
        return null;
    }

    // ------------------------------------------------------------------
    // ROM scan (mesmos diretórios/ordem do nativo: filesDir, externalFilesDir)
    // ------------------------------------------------------------------

    /** Armazenamento preferencial para ROM; external pode ser null sem cartão/perfil. */
    private File romDir() {
        File ext = getExternalFilesDir(null);
        return ext != null ? ext : getFilesDir();
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

    private void toast(String msg) {
        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
    }

    // ------------------------------------------------------------------
    // Cópia dos assets do APK para o armazenamento interno (1ª execução)
    //
    // Mapeamento esperado pelo nativo (get_program_path() = filesDir):
    //   APK assets/<rel>       -> filesDir/assets/<rel>
    //   APK recompcontrollerdb -> filesDir/recompcontrollerdb.txt (raiz!)
    // O AssetManager vê a MESCLAGEM de todos os srcDirs do AGP
    // (../../assets + src/main/assets) num namespace único.
    // ------------------------------------------------------------------

    private boolean assetsReady() {
        File marker = new File(getFilesDir(), ASSETS_VERSION_MARKER);
        if (!marker.exists()) return false;
        try {
            return ASSETS_VERSION.equals(readTextFile(marker).trim());
        } catch (IOException e) {
            return false;
        }
    }

    private void startAssetCopyIfNeeded() {
        if (mAssetCopyRunning || assetsReady()) return;
        mAssetCopyRunning = true;
        setStatus("Preparando arquivos do jogo…");
        new Thread(() -> {
            String error = null;
            try {
                prepareAssets();
            } catch (IOException ex) {
                Log.e(TAG, "Falha ao copiar assets", ex);
                error = "Falha ao preparar arquivos: " + ex.getMessage();
            }
            final String finalError = error;
            runOnUiThread(() -> {
                mAssetCopyRunning = false;
                setStatus(finalError != null ? finalError : "");
                maybeHandOff();
            });
        }, "dk64-asset-copy").start();
    }

    private void prepareAssets() throws IOException {
        File filesDir = getFilesDir();
        if (filesDir == null) throw new IOException("filesDir indisponível");
        File assetsDir = new File(filesDir, "assets");
        copyApkAssets("", assetsDir);
        // recompcontrollerdb.txt vem do nosso src/main/assets e é OBRIGATÓRIO
        // (SDL_GameControllerAddMappingsFromFile em src/main/main.cpp).
        try (InputStream in = getAssets().open("recompcontrollerdb.txt")) {
            copyStream(in, new File(filesDir, "recompcontrollerdb.txt"));
        }
        writeTextFile(new File(filesDir, ASSETS_VERSION_MARKER), ASSETS_VERSION);
        Log.i(TAG, "Assets copiados para " + filesDir);
    }

    private void copyApkAssets(String path, File destDir) throws IOException {
        String[] entries = getAssets().list(path);
        if (entries == null) return;
        for (String entry : entries) {
            String rel = path.isEmpty() ? entry : path + "/" + entry;
            if (path.isEmpty() && entry.equals("recompcontrollerdb.txt")) continue;
            String[] children = getAssets().list(rel);
            if (children != null && children.length > 0) {
                copyApkAssets(rel, destDir);
                continue;
            }
            File out = new File(destDir, rel);
            File parent = out.getParentFile();
            if (parent != null && !parent.isDirectory()) parent.mkdirs();
            try (InputStream in = getAssets().open(rel)) {
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
