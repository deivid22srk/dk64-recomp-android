package com.deivid22srk.dk64recomp;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import org.libsdl.app.SDLActivity;

import java.io.File;

/**
 * Port Android do DK64: Recompiled — Activity do SDL e ÚNICA activity do app.
 *
 * A tela Java de setup (SetupActivity) foi DESATIVADA: o app abre direto no
 * jogo e tudo que ela fazia migrou para cá e para a UI do próprio recomp:
 *  - Assets do APK: copiados no onCreate (AppSetup.ensureAssets, marcador
 *    .assets_version) ANTES do super.onCreate iniciar a SDLThread;
 *  - ROM: opção "Load ROM" do menu launcher do jogo -> DocumentsUI (SAF)
 *    via ponte JNI android/native/compat/file_bridge.cpp -> SafFiles copia
 *    para o filesDir -> select_rom valida e armazena no config;
 *  - Driver Turnip: opção "GPU Driver" do menu launcher -> mesmo caminho SAF
 *    -> GpuDriverInstaller extrai + probe -> custom_driver.cpp carrega no
 *    próximo início do app.
 *
 * Fluxo nativo: SDLMain.run -> nativeRunMain -> SDL_main (src/main/main.cpp),
 * que consome os diretórios via androidport::init_from_args(argc, argv).
 *
 * Tela cheia: o nativo pede SDL_WINDOW_FULLSCREEN_DESKTOP (create_window),
 * que ativa o imersivo sticky do SDLActivity; aqui complementamos com
 * layoutInDisplayCutoutMode=SHORT_EDGES (o furo de câmera em paisagem fica
 * nas laterais — sem isto o Android reserva faixas pretas de ~4 mm em cada
 * borda e o swapchain nasce menor que o display) e reafirmamos as flags
 * imersivas a cada ganho de foco (teclas deslizantes do Android 15).
 */
public class MainActivity extends SDLActivity {

    private static final String TAG = "DK64Recomp";

    /** Overlay do gamepad virtual (estilo N64Pad2/Dolphin) sobre o SDLSurface. */
    private VirtualPadView virtualPadView;

    // ------------------------------------------------------------------
    // Ponte SAF <-> menu do jogo (file_bridge.cpp). Requisições chegam da
    // thread de render do RT64 (JNI -> requestFilePicker); resultados são
    // processados em background e publicados em nativeOnFilePicked.
    // ------------------------------------------------------------------

    /** Kind::Rom do file_bridge.h — mantenha em sincronia. */
    private static final int KIND_ROM = 0;
    /** Kind::DriverZip do file_bridge.h — mantenha em sincronia. */
    private static final int KIND_DRIVER = 1;

    private static final int PICK_ROM_REQUEST = 0xD864;
    private static final int PICK_DRIVER_REQUEST = 0xADF1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.i(TAG, "MainActivity.onCreate -> preparando assets e iniciando fluxo SDL");
        // ANTES do super: super.onCreate inicia a SDLThread, que logo consome
        // filesDir/assets (fontes/texturas do recompui). A cópia é bloqueante
        // e marcada por .assets_version — nas execuções seguintes é um stat.
        AppSetup.ensureAssets(this);

        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams attrs = getWindow().getAttributes();
            // SHORT_EDGES: permite que o conteúdo se estenda pela área do
            // recorte da câmera em qualquer orientação (combinado com o
            // imersivo, remove as barras pretas laterais em displays 20:9).
            attrs.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(attrs);
        }

        // Registra a JavaVM na ponte de arquivos (libmain.so já carregada por
        // super.onCreate -> loadLibraries). Sem isso, o menu do jogo não
        // consegue abrir o DocumentsUI; falha é logada e degrada o recurso.
        try {
            nativeBridgeInit();
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "JNI da ponte de arquivos indisponível: " + e.getMessage());
        }

        // Gamepad virtual: overlay transparente por cima do SDLSurface.
        // - A libmain.so já foi carregada por super.onCreate (loadLibraries),
        //   então os métodos JNI do VirtualPadView já estão vinculáveis.
        // - Touches que não acertam nenhum controle caem no SDLSurface
        //   (o overlay devolve false), mantendo o touch-as-mouse dos menus.
        // - O HUD só é desenhado quando o jogo inicia (callback nativo
        //   VirtualPadView.onGameStarted), para não bloquear a launcher.
        if (mLayout != null && virtualPadView == null) {
            try {
                virtualPadView = new VirtualPadView(this);
                mLayout.addView(virtualPadView, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
            } catch (UnsatisfiedLinkError e) {
                Log.e(TAG, "JNI do gamepad virtual indisponível: " + e.getMessage());
                virtualPadView = null;
            }
        }
    }

    /**
     * Resultado do DocumentsUI (SAF). Pode ser cancelamento (res != OK).
     * O processamento (cópia da ROM / extração do driver + probe Vulkan) roda
     * em background para não ANRar a UI thread; ao terminar publica no nativo
     * (nativeOnFilePicked), que entrega ao menu do jogo no próximo frame
     * (draw_hook -> file_bridge::process_pending).
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        final int kind;
        switch (requestCode) {
            case PICK_ROM_REQUEST: kind = KIND_ROM; break;
            case PICK_DRIVER_REQUEST: kind = KIND_DRIVER; break;
            default: return; // (HID do SDLActivity já foi tratado pelo super)
        }

        final Uri uri = (resultCode == RESULT_OK && data != null && data.getData() != null)
                ? data.getData() : null;
        final Context appContext = getApplicationContext();
        new Thread(() -> {
            boolean ok = false;
            String payload;
            try {
                if (uri == null) {
                    payload = "No file selected."; // cancelamento: o menu só ignora
                } else if (kind == KIND_ROM) {
                    payload = SafFiles.copyRomToFilesDir(appContext, uri);
                    ok = true;
                } else {
                    String name = SafFiles.queryDisplayName(appContext, uri);
                    if (name == null) name = "driver.zip";
                    name = name.substring(name.lastIndexOf('/') + 1).trim();
                    String lower = name.toLowerCase();
                    if (!lower.endsWith(".zip") && !lower.endsWith(".so")) {
                        payload = "Please pick a driver .zip (adrenotools or Winlator/Turnip) "
                                + "or a driver .so file.";
                    } else {
                        String friendly = GpuDriverInstaller.installFromUri(appContext, uri, name);
                        payload = GpuDriverInstaller.probeStatusText(appContext, friendly);
                        ok = true;
                    }
                }
            } catch (Throwable t) {
                Log.e(TAG, "Falha ao processar arquivo escolhido", t);
                payload = "Failed to process the selected file: " + t.getMessage();
            }

            final boolean fOk = ok;
            final String fPayload = payload;
            try {
                nativeOnFilePicked(kind, fOk, fPayload);
            } catch (UnsatisfiedLinkError e) {
                Log.e(TAG, "nativeOnFilePicked indisponível: " + e.getMessage());
            }
        }, "dk64-saf-result").start();
    }

    /**
     * Chamado via JNI pela thread de render (file_bridge.cpp). Posta o Intent
     * SAF na UI thread e devolve na hora — a troca de Activity (DocumentsUI)
     * e o processamento do resultado ficam inteiramente do lado Java.
     */
    public static boolean requestFilePicker(int kind) {
        final Activity activity = mSingleton;
        if (activity == null || activity.isFinishing()) {
            Log.e(TAG, "requestFilePicker: Activity indisponível");
            return false;
        }
        activity.runOnUiThread(() -> {
            try {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*"); // ROMs/drivers chegam como octet-stream/zip
                activity.startActivityForResult(intent,
                        kind == KIND_ROM ? PICK_ROM_REQUEST : PICK_DRIVER_REQUEST);
            } catch (Exception e) {
                Log.e(TAG, "Falha ao abrir o seletor SAF", e);
            }
        });
        return true;
    }

    /** Registro da JavaVM para a ponte de arquivos (implementado em file_bridge.cpp). */
    private static native void nativeBridgeInit();

    /** Publica o resultado do SAF para o menu do jogo (implementado em file_bridge.cpp). */
    private static native void nativeOnFilePicked(int kind, boolean ok, String payload);

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        // SEMPRE chamar o super: SDLActivity usa este ponto para
        // nativeFocusChanged() (pausa/retoma do loop de render).
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            View decor = getWindow().getDecorView();
            decor.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN);
        }
    }

    /**
     * SAÍDA DO JOGO = FIM DO PROCESSO.
     *
     * O jogo sai pelo menu Exit -> ultramodern::quit() seta o ESTÁTICO
     * `exited` (librecomp/recomp.cpp) -> SDL_main retorna -> SDLThread chama
     * mSingleton.finish() (SDLActivity.java ~L1891). O SDLActivity encerra a
     * própria thread e o SDL (nativeQuit -> SDL_Quit), mas o PROCESSO fica
     * vivo (apenas cacheado pelo sistema). Um 2º SDL_main no mesmo processo —
     * exatamente o que acontece ao sair do jogo e reabrir o app — nasce com
     * TODOS os estáticos do runtime sujos: `exited` ainda é true (o loop
     * principal `while (!exited)` nem executa), game_status=Quit, contexto
     * RT64/RmlUi do run anterior, g_state do driver Vulkan... O sintoma é o
     * relato do usuário: "sair do jogo e entrar novamente não carrega".
     *
     * Encerrar o processo aqui torna CADA relaunch um processo novo:
     * estáticos zerados, driver recarregado do zero a partir de
     * files/driver/selected.txt, probe rerodado. É o comportamento padrão de
     * ports SDL no Android (a Activity é destruída apenas em finish() do fim
     * do jogo, back na raiz da task, ou swipe em recents — a orientação é
     * fixa e configChanges cobre rotações/teclado, então isFinishing() aqui
     * significa "fim de jogo", nunca recriação de configuração). É também o
     * que torna a troca de driver via menu "GPU Driver" efetiva: Exit ->
     * reabrir = driver novo.
     */
    @Override
    public void onDestroy() {
        Log.i(TAG, "MainActivity.onDestroy (isFinishing=" + isFinishing() + ")");
        super.onDestroy();
        if (isFinishing()) {
            android.os.Process.killProcess(android.os.Process.myPid());
        }
    }

    @Override
    protected String[] getLibraries() {
        // SDL2 é linkado estaticamente dentro de libmain.so (ver app/CMakeLists.txt).
        // getMainSharedObject() usa o ÚLTIMO elemento: "main" -> libmain.so.
        return new String[]{ "main" };
    }

    /**
     * IMPORTANTE: em SDL 2.30.8 o javadoc diz "arguments AFTER the application
     * name" — SDL_android.c (nativeRunMain) monta argv[0]="app_process" e copia
     * o array retornado aqui a partir de argv[1] (NÃO adicionamos um argv[0]
     * próprio; isso deslocaria os paths lidos pelo nativo).
     *
     * Contrato consumido em src/main/main.cpp -> androidport::init_from_args():
     *   argv[1] = filesDir            (internal files dir)
     *   argv[2] = getExternalFilesDir(null) (external files dir)
     *
     * getExternalFilesDir() pode retornar null (armazenamento indisponível):
     * passamos "" e o nativo faz fallback (g_external = g_internal).
     */
    @Override
    protected String[] getArguments() {
        return new String[]{
                absolutePathOrEmpty(getFilesDir()),
                absolutePathOrEmpty(getExternalFilesDir(null))
        };
    }

    private static String absolutePathOrEmpty(File dir) {
        return dir != null ? dir.getAbsolutePath() : "";
    }
}
