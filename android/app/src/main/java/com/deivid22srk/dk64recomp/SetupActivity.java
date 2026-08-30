package com.deivid22srk.dk64recomp;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Port Android do DK64: Recompiled — tela de preparação (launcher).
 *
 * Responsabilidades (antes de qualquer SDL):
 *  1. Verificar Vulkan 1.1 (RT64) — aviso, não bloqueio.
 *  2. Copiar os assets do APK para o filesDir na 1ª execução
 *     (marcador .assets_version): assets/<nome> + recompcontrollerdb.txt na raiz.
 *  3. Garantir uma ROM (.z64/.n64/.v64): scan de filesDir/externalFilesDir
 *     ou seleção via SAF (ACTION_OPEN_DOCUMENT) com cópia para o app.
 *  4. Quando tudo pronto -> botão INICIAR JOGO -> startActivity(MainActivity).
 *
 * Assim o MainActivity (SDLActivity) nunca inicia sem ROM/assets e não
 * precisamos de recreate() nem de manipular estado estático do SDL.
 *
 * Driver Vulkan custom (Turnip): o usuário pode instalar um driver aqui no
 * Setup — .zip adrenotools (meta.json + vulkan.ad*.so, ex.
 * K11MCH1/AdrenoToolsDrivers), .zip Winlator/WN-Turnip (sem meta.json, libs
 * em subpastas — soname autodetectado) ou .so solto. Extração em
 * filesDir/driver/installed/<id>/ e seleção em filesDir/driver/selected.txt
 * (KEY=VALUE), lida pelo nativo (custom_driver.cpp). O driver é VALIDADO no
 * ato (probe JNI com paths do Java — roda antes do main()) e o início do
 * jogo é manual (botão INICIAR JOGO), garantindo acesso a esta tela em todo
 * launch.
 */
public class SetupActivity extends Activity {

    private static final String TAG = "DK64Recomp";
    private static final String ASSETS_VERSION_MARKER = ".assets_version";
    private static final String ASSETS_VERSION = "1";
    private static final int PICK_ROM_REQUEST = 0xD864;
    private static final int PICK_DRIVER_REQUEST = 0xADF1;
    private static final long MAX_ROM_BYTES = 64L * 1024 * 1024; // ROM NTSC-U: 32 MiB
    // Limites anti zip-bomb para o driver (zips reais: ~1 .so de 13-20 MB + meta.json)
    private static final long MAX_DRIVER_ZIP_BYTES = 512L * 1024 * 1024;
    private static final int MAX_DRIVER_ENTRIES = 128;

    static {
        // Habilita o probe nativo do driver (JNI em custom_driver.cpp). O
        // libmain.so é o MESMO que o jogo usa via SDLActivity: carregar aqui é
        // inofensivo (nada roda no dlopen — o SDL_main só inicia via
        // SDLActivity) e evita duplicar o probe numa lib separada.
        try {
            System.loadLibrary("main");
        } catch (Throwable t) {
            Log.e(TAG, "libmain.so indisponível no Setup (probe de driver desativado)", t);
        }
    }

    /**
     * Valida o driver selecionado (custom_driver.cpp): carrega o Turnip via
     * adrenotools, cria uma VkInstance e enumera os dispositivos físicos — o
     * MESMO caminho que o RT64 executará ao iniciar o jogo. Recebe os paths
     * do app porque roda ANTES do main() do jogo (sem eles o nativo não sabia
     * onde ficava files/driver/selected.txt e recusava qualquer driver).
     * Retorna JSON:
     * {"active":bool,"ok":bool,"devices":int,"device":str,"api":str,"error":str}
     */
    private static native String nativeProbeCustomDriver(String filesDir, String nativeLibraryDir);

    private TextView mStatus;
    private Button mPickButton;
    private TextView mDriverStatus;
    private Button mDriverInstallButton;
    private Button mDriverRemoveButton;
    private Button mStartButton;

    private volatile boolean mAssetCopyRunning;
    private volatile boolean mRomCopyRunning;
    private volatile boolean mDriverWorkRunning;
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
        updateStartState();
    }

    // ------------------------------------------------------------------
    // UI
    // ------------------------------------------------------------------

    private void buildUi() {
        // ScrollView: em paisagem (ex.: 1600x720) o conteúdo inteiro não cabe
        // sem rolagem — sem ele os botões de driver e o INICIAR JOGO ficam
        // cortados abaixo da tela (bug visto no moto g34 5G).
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(64, 64, 64, 64);
        root.setBackgroundColor(Color.rgb(0x2B, 0x1D, 0x0E)); // marrom barril
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

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

        // --------------------------------------------------------------
        // Driver Vulkan custom (Turnip via adrenotools) — opcional
        // --------------------------------------------------------------
        TextView driverTitle = new TextView(this);
        driverTitle.setText("Driver Vulkan (opcional)");
        driverTitle.setTextSize(16f);
        driverTitle.setTextColor(Color.rgb(0xF7, 0xD3, 0x3B));
        driverTitle.setPadding(0, 48, 0, 8);
        root.addView(driverTitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView driverHint = new TextView(this);
        driverHint.setText("Em alguns dispositivos o driver proprietário Adreno tem bugs "
                + "(ex.: crash em vkGetRefreshCycleDurationGOOGLE). Um driver Turnip "
                + "(Mesa) pode corrigir. Formatos aceitos: .zip adrenotools "
                + "(meta.json + vulkan.ad*.so, ex. K11MCH1/AdrenoToolsDrivers), "
                + ".zip Winlator/WN-Turnip (sem meta.json) e .so solto. "
                + "Requer arm64 + Android 10+.\n\n"
                + "Importante: use um build para a geração da sua GPU — em Adreno 6xx "
                + "(ex.: Adreno 619) use os builds 'a6xx'; builds a7xx/a8xx não expõem "
                + "GPU neste aparelho. O driver é testado aqui mesmo após instalar; se "
                + "não listar nenhuma GPU Vulkan, ele é recusado automaticamente.");
        driverHint.setTextSize(12f);
        driverHint.setTextColor(Color.rgb(0xD8, 0xD8, 0xD8));
        root.addView(driverHint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mDriverStatus = new TextView(this);
        mDriverStatus.setTextSize(13f);
        mDriverStatus.setTextColor(Color.WHITE);
        mDriverStatus.setPadding(0, 12, 0, 12);
        root.addView(mDriverStatus, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mDriverInstallButton = new Button(this);
        mDriverInstallButton.setText("Instalar driver (.zip)…");
        mDriverInstallButton.setOnClickListener(v -> startDriverPicker());
        root.addView(mDriverInstallButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mDriverRemoveButton = new Button(this);
        mDriverRemoveButton.setText("Remover driver (voltar ao sistema)");
        mDriverRemoveButton.setOnClickListener(v -> removeDriver());
        root.addView(mDriverRemoveButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // --------------------------------------------------------------
        // Início manual: garante acesso a esta tela (ROM/driver) em todo launch
        // --------------------------------------------------------------
        mStartButton = new Button(this);
        mStartButton.setText("INICIAR JOGO ▶");
        mStartButton.setTextSize(18f);
        mStartButton.setEnabled(false);
        mStartButton.setOnClickListener(v -> startGame());
        LinearLayout.LayoutParams startParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        startParams.topMargin = 40;
        root.addView(mStartButton, startParams);

        refreshDriverStatus();
        setContentView(scroll);
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
                    updateStartState();
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
    // Handoff para o SDL (MainActivity) — agora MANUAL via INICIAR JOGO
    // ------------------------------------------------------------------

    /** Habilita o botão de início quando ROM + assets estiverem prontos. */
    private void updateStartState() {
        if (mStartButton == null) return;
        boolean ready = !mRomCopyRunning && !mAssetCopyRunning && assetsReady() && findRom() != null;
        if (ready && !mVulkanAcknowledged && !vulkanOk()) ready = false;
        mStartButton.setEnabled(ready);
        if (ready) {
            mStatus.setText("Tudo pronto — toque em INICIAR JOGO");
        }
    }

    private void startGame() {
        if (mHandedOff || isFinishing() || isDestroyed()) return;
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
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        if (requestCode == PICK_ROM_REQUEST) {
            handleRomPicked(data.getData());
        } else if (requestCode == PICK_DRIVER_REQUEST) {
            handleDriverPicked(data.getData());
        }
    }

    private void handleRomPicked(Uri uri) {
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
                updateStartState();
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
    // Driver Vulkan custom (Turnip via adrenotools)
    //
    // Formato do zip (ecossistema adrenotools): meta.json + o .so do driver
    // (campo "libraryName"/"library" do meta). Os arquivos podem estar na raiz
    // ou num subdiretório — usamos o diretório do meta.json como raiz.
    // O nativo (custom_driver.cpp) lê filesDir/driver/selected.txt e carrega o
    // driver via adrenotools_open_libvulkan() no início do SDL_main.
    // ------------------------------------------------------------------

    private static final String DRIVER_BASE = "driver";              // filesDir/driver
    private static final String DRIVER_SELECTED = "selected.txt";    // filesDir/driver/selected.txt
    private static final String DRIVER_INSTALLED = "installed";      // filesDir/driver/installed/<id>/

    private static final class DriverMeta {
        String name;
        String library;
        String dir;
    }

    private void startDriverPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*"); // zips podem chegar como application/zip ou octet-stream
        startActivityForResult(intent, PICK_DRIVER_REQUEST);
    }

    private void handleDriverPicked(Uri uri) {
        if (mDriverWorkRunning) return;
        String name = queryDisplayName(uri);
        if (name == null) name = "driver.zip";
        name = name.substring(name.lastIndexOf('/') + 1).trim();
        String lower = name.toLowerCase();
        if (!lower.endsWith(".zip") && !lower.endsWith(".so")) {
            toast("Selecione um .zip de driver (adrenotools ou Winlator/Turnip) "
                    + "ou um .so de driver.");
            return;
        }
        installDriverAsync(uri, name);
    }

    private void installDriverAsync(Uri uri, final String displayName) {
        mDriverWorkRunning = true;
        setDriverButtonsEnabled(false);
        setDriverStatus("Instalando driver…");
        new Thread(() -> {
            String error = null;
            String success = null;
            DriverMeta meta = null;
            try {
                meta = installDriver(uri, displayName);
                Log.i(TAG, "Driver instalado: " + meta.name + " (" + meta.library + ") em " + meta.dir);

                // Validação (probe): o driver recém-instalado é carregado pela
                // mesma via do jogo (adrenotools) e testado com uma VkInstance.
                // Sem isso, um build sem suporte à GPU (ex.: a8xx num Adreno 619)
                // só falharia lá na frente com "Unable to find compatible
                // graphics device".
                String probeJson = null;
                try {
                    // Paths do app: o probe roda antes do main() do jogo, então o
                    // nativo ainda não conhece filesDir — e nativeLibraryDir é
                    // exatamente o hookLibDir exigido pelo libadrenotools.
                    probeJson = nativeProbeCustomDriver(getFilesDir().getAbsolutePath(),
                            getApplicationInfo().nativeLibraryDir);
                } catch (Throwable t) {
                    Log.w(TAG, "Probe nativo indisponível — driver aceito sem validação", t);
                }
                if (probeJson != null) {
                    org.json.JSONObject j = new org.json.JSONObject(probeJson);
                    final boolean active = j.optBoolean("active", false);
                    final boolean ok = j.optBoolean("ok", false);
                    final int devices = j.optInt("devices", 0);
                    final String device = j.optString("device", "");
                    final String api = j.optString("api", "");
                    final String probeError = j.optString("error", "");
                    Log.i(TAG, "Probe do driver: " + probeJson);

                    if (active && !ok) {
                        // Driver não expôs GPU — recusa e faz rollback da seleção.
                        clearDriverSelection();
                        error = "Driver \"" + meta.name + "\" recusado: ele não expõe nenhuma GPU "
                                + "Vulkan neste aparelho" + (probeError.isEmpty() ? "" : " (" + probeError + ")")
                                + ".\n\nIsso acontece quando o build não suporta a geração da GPU "
                                + "deste dispositivo. Baixe um build Turnip compatível — para Adreno "
                                + "6xx (ex.: Adreno 619) use os builds 'a6xx' do repositório "
                                + "K11MCH1/AdrenoToolsDrivers no GitHub — e instale novamente.";
                    } else if (active && ok) {
                        success = "Driver \"" + meta.name + "\" verificado: "
                                + (device.isEmpty() ? "GPU Vulkan" : device)
                                + (api.isEmpty() ? "" : " (Vulkan " + api + ")")
                                + ". Ele será usado ao iniciar o jogo.";
                    } else if (!active) {
                        // Falha no carregamento (adrenotools): rollback também.
                        clearDriverSelection();
                        error = "Driver \"" + meta.name + "\" não pôde ser carregado"
                                + (probeError.isEmpty() ? "" : ": " + probeError)
                                + ". O jogo usará o driver do sistema. Confira se o zip "
                                + "é arm64 (formato adrenotools com meta.json, Winlator/Turnip "
                                + "ou .so) e se o build corresponde à geração da sua GPU; "
                                + "veja o logcat (tag DK64Recomp) para o motivo exato.";
                    }
                }
            } catch (Exception ex) {
                Log.e(TAG, "Falha ao instalar driver", ex);
                error = "Falha ao instalar driver: " + ex.getMessage();
            }
            final String finalError = error;
            final String finalSuccess = success;
            runOnUiThread(() -> {
                mDriverWorkRunning = false;
                setDriverButtonsEnabled(true);
                refreshDriverStatus();
                if (finalError != null) {
                    toast(finalError);
                } else if (finalSuccess != null) {
                    toast(finalSuccess);
                } else {
                    toast("Driver instalado! Ele será usado ao iniciar o jogo.");
                }
            });
        }, "dk64-driver-install").start();
    }

    /** Remove apenas a seleção (selected.txt); usado no rollback do probe. */
    private void clearDriverSelection() {
        try {
            new File(new File(getFilesDir(), DRIVER_BASE), DRIVER_SELECTED).delete();
            Log.i(TAG, "Seleção de driver revertida (rollback do probe)");
        } catch (Exception ex) {
            Log.e(TAG, "Falha ao reverter seleção de driver", ex);
        }
    }

    private void setDriverButtonsEnabled(boolean enabled) {
        if (mDriverInstallButton != null) mDriverInstallButton.setEnabled(enabled);
        if (mDriverRemoveButton != null) mDriverRemoveButton.setEnabled(enabled);
    }

    private void setDriverStatus(String text) {
        if (mDriverStatus != null) mDriverStatus.setText(text);
    }

    /**
     * Extrai, valida e seleciona o driver. Lança Exception com mensagem amigável.
     *
     * Formatos aceitos (paridade com redahm-android, que carrega drivers sem
     * problemas no moto g34 5G):
     *  - .zip adrenotools (meta.json + vulkan.ad*.so): extrai sob o prefixo do
     *    meta.json e usa o soname dele (libraryName/library);
     *  - .zip Winlator/WN-Turnip (SEM meta.json, libs em subpastas): extrai
     *    todos os *.so ACHATADOS na raiz e autodetecta o soname;
     *  - .so solto: copiado como driver de arquivo único.
     */
    private DriverMeta installDriver(Uri uri, String displayName) throws Exception {
        File base = new File(getFilesDir(), DRIVER_BASE);
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
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    if (in == null) throw new IOException("não foi possível abrir o arquivo");
                    copyStreamTo(in, new File(target, soName));
                }
                library = soName;
            } else {
                // 1ª passada: meta.json é OPCIONAL (zips Winlator/WN não têm)
                String prefix = null;
                JSONObject meta = null;
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    if (in == null) throw new IOException("não foi possível abrir o arquivo");
                    Object[] found = findMetaJson(in);
                    if (found != null) {
                        prefix = (String) found[0];
                        meta = new JSONObject((String) found[1]);
                    }
                }

                if (meta != null) {
                    // Formato adrenotools: extrai sob o prefixo do meta.json
                    extractZipUnderPrefix(uri, prefix, target);
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
                    extractZipFlattened(uri, target);
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
            return out;
        } catch (Exception ex) {
            deleteRecursively(target);
            throw ex;
        }
    }

    /** Soname principal de um diretório de driver, ordem de preferência:
     *  nomes conhecidos (Winlator/Turnip) → libvulkan*/vulkan.* → qualquer
     *  .so que não seja o compilador LLVM do Mesa. */
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
    private void extractZipFlattened(Uri uri, File target) throws IOException {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
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

    private static void copyStreamTo(InputStream in, File out) throws IOException {
        try (OutputStream os = new FileOutputStream(out)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
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
    private Object[] findMetaJson(InputStream in) throws IOException {
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

    private void extractZipUnderPrefix(Uri uri, String prefix, File target) throws IOException {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
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

    private void removeDriver() {
        if (mDriverWorkRunning) return;
        try {
            File base = new File(getFilesDir(), DRIVER_BASE);
            new File(base, DRIVER_SELECTED).delete();
            File installedRoot = new File(base, DRIVER_INSTALLED);
            File[] dirs = installedRoot.listFiles();
            if (dirs != null) for (File d : dirs) deleteRecursively(d);
            deleteRecursively(new File(base, "tmp"));
            deleteRecursively(new File(base, "cache"));
            Log.i(TAG, "Driver removido");
            toast("Driver removido — o jogo voltará a usar o driver do sistema.");
        } catch (Exception ex) {
            Log.e(TAG, "Falha ao remover driver", ex);
            toast("Falha ao remover driver: " + ex.getMessage());
        }
        refreshDriverStatus();
    }

    private void refreshDriverStatus() {
        if (mDriverStatus == null) return;
        DriverMeta sel = readSelectedDriver();
        if (sel != null) {
            mDriverStatus.setText("Ativo: " + sel.name + " (" + sel.library + ")\n"
                    + "Aplicado ao iniciar o jogo. Toque em Remover para voltar ao driver do sistema.");
            mDriverRemoveButton.setEnabled(true);
        } else {
            mDriverStatus.setText("Usando o driver do sistema (padrão).");
            mDriverRemoveButton.setEnabled(false);
        }
    }

    /** Mesmo formato que o nativo (custom_driver.cpp) lê: linhas KEY=VALUE. */
    private DriverMeta readSelectedDriver() {
        try {
            File f = new File(new File(getFilesDir(), DRIVER_BASE), DRIVER_SELECTED);
            if (!f.isFile()) return null;
            DriverMeta meta = new DriverMeta();
            for (String line : readTextFile(f).split("\n")) {
                line = line.trim();
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String key = line.substring(0, eq);
                String value = line.substring(eq + 1);
                if (key.equals("dir")) meta.dir = value;
                else if (key.equals("library")) meta.library = value;
                else if (key.equals("name")) meta.name = value;
            }
            if (meta.dir == null || meta.library == null) return null;
            return meta;
        } catch (IOException e) {
            return null;
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
                updateStartState();
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
