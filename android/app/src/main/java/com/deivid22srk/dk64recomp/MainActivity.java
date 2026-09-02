package com.deivid22srk.dk64recomp;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.SurfaceHolder;
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
    // ORIENTAÇÃO — o jogo é LANDSCAPE-ONLY.
    //
    // O manifest trava em sensorLandscape, MAS o SDL sobrescreve isso em
    // runtime: Android_CreateWindow chama via JNI SDLActivity.setOrientation
    // (SDLActivity.setOrientationBis), que com a hint ORIENTATIONS vazia
    // decide pela comparação w>h DA JANELA NO MOMENTO DO CREATE. Se o app é
    // aberto com o aparelho na vertical (ou o sensor ainda está em transição
    // quando a surface nasce), o Android entrega w<h e o SDL pede
    // SCREEN_ORIENTATION_SENSOR_PORTRAIT — derrubando o lock do manifest.
    // Era exatamente o "às vezes abre no modo Portrait".
    //
    // Override IGNORA w/h/hint e reafirma a paisagem dupla em TODA chamada.
    // ------------------------------------------------------------------
    @Override
    public void setOrientationBis(int w, int h, boolean resizable, String hint) {
        Log.v(TAG, "setOrientation override: forçando SENSOR_LANDSCAPE "
                + "(w=" + w + " h=" + h + " hint=" + hint + ")");
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
    }

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
        // Captura de diagnóstico (se ativada nas configurações) começa AQUI,
        // antes de qualquer outra coisa, para registrar desde o copy de assets
        // até o fim. Nunca derruba o app: qualquer falha é engolida e logada.
        DiagnosticsLogger.onMainActivityCreate(this);
        // ANTES do super: super.onCreate inicia a SDLThread, que logo consome
        // filesDir/assets (fontes/texturas do recompui). A cópia é bloqueante
        // e marcada por .assets_version — nas execuções seguintes é um stat.
        AppSetup.ensureAssets(this);

        super.onCreate(savedInstanceState);

        // Injeta o nativeLibraryDir no nativo O QUANTO ANTES (libmain.so já
        // foi carregada por super.onCreate -> loadLibraries). Sem isto, o
        // hookLibDir do adrenotools (ApplicationInfo.nativeLibraryDir — valor
        // em contrato) dependia do FALLBACK de /proc/self/maps em TODO cold
        // start: qualquer anomalia de mapeamento deixava o carregamento do
        // driver Turnip quebrado na reabertura, embora o probe da instalação
        // (que recebe o path do Java) funcionasse — o padrão "funciona ao
        // instalar, perde ao fechar e reabrir". Deve rodar ANTES do primeiro
        // load de driver (plume init na renderização da launcher, muito depois
        // deste ponto). Vai para custom_driver.cpp (nativeSetRuntimePaths).
        try {
            nativeSetRuntimePaths(getFilesDir().getAbsolutePath(),
                    getApplicationInfo().nativeLibraryDir);
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "nativeSetRuntimePaths indisponível: " + e.getMessage());
        }

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

        // Registra a JavaVM no módulo de reinício (app_restart.cpp). O JavaVM*
        // é cacheado em g_vm e usado por dk64::native_request_app_restart()
        // para resolver MainActivity.handleNativeAppRestart() via JNI. Sem
        // isso, o menu "GPU Driver" não consegue finalizar a Activity após
        // trocar o driver Vulkan.
        try {
            nativeRestartInit();
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "JNI de reinício do app indisponível: " + e.getMessage());
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

        // SurfaceHolder.Callback para o ciclo de vida da Surface nativa.
        // - Os métodos nativos estáticos do SDLActivity (onNativeSurfaceCreated,
        //   etc.) já são chamados pelo SDLSurface; precisamos de um sinal nosso
        //   para o gate de present thread, porque o gate do Java (onPause +
        //   onWindowFocusChanged) é propenso a falsos positivos pela
        //   flutuação de foco durante a abertura do DocumentsUI. O caminho
        //   Surface→ANativeWindow→SDL é determinístico: cada surfaceCreated
        //   corresponde exatamente a um novo ANativeWindow no SDL_Window.
        // - O callback é registrado UMA VEZ (mSurface é estático, mas o
        //   callback seria re-registrado em recriações de Activity, então
        //   checamos por nulidade).
        if (mSurface != null && dk64SurfaceCallback == null) {
            dk64SurfaceCallback = new SurfaceHolder.Callback() {
                @Override public void surfaceCreated(SurfaceHolder holder) {
                    Log.i(TAG, "DK64 SurfaceHolder: surfaceCreated");
                    try { nativeSurfaceState(1); } catch (UnsatisfiedLinkError e) {
                        Log.e(TAG, "nativeSurfaceState(1) indisponível: " + e.getMessage());
                    }
                    // surfaceCreated chega DEPOIS de onResume no ciclo de
                    // retorno do DocumentsUI: é o momento DETERMINÍSTICO de
                    // surface válida, então liberamos o gate de Activity aqui
                    // (substitui a antiga liberação em onWindowFocusChanged).
                    try { nativeSetAppActive(true); } catch (UnsatisfiedLinkError e) {
                        Log.e(TAG, "nativeSetAppActive(true) indisponível: " + e.getMessage());
                    }
                }
                @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                    // Mudança de tamanho/formato: a VkSurface pode ter sido
                    // recriada (ANativeWindow novo) e o swapchain precisa de
                    // resize. Sinalizamos para a PresentQueue forçar a
                    // recriação no próximo turno, mesmo que o gate já esteja
                    // aberto (evita o loop vkCreateSwapchainKHR em surface
                    // trocada silenciosamente pelo SurfaceView BLAST).
                    Log.i(TAG, "DK64 SurfaceHolder: surfaceChanged " + width + "x" + height);
                    try { nativeSurfaceState(2); } catch (UnsatisfiedLinkError e) {
                        Log.e(TAG, "nativeSurfaceState(2) indisponível: " + e.getMessage());
                    }
                }
                @Override public void surfaceDestroyed(SurfaceHolder holder) {
                    // Surface prestes a ser INVALIDADA: o Java (gate de
                    // Activity) também fecha em onPause, mas com gap de
                    // ~600 ms; precisamos do sinal determinístico aqui para
                    // que a PresentQueue force a recriação mesmo se o gate
                    // já tiver sido liberado por uma flutuação de foco.
                    Log.i(TAG, "DK64 SurfaceHolder: surfaceDestroyed");
                    try { nativeSurfaceState(0); } catch (UnsatisfiedLinkError e) {
                        Log.e(TAG, "nativeSurfaceState(0) indisponível: " + e.getMessage());
                    }
                }
            };
            mSurface.getHolder().addCallback(dk64SurfaceCallback);
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
     *
     * ATENÇÃO: o nativo guarda um slot único em "Waiting" enquanto o picker
     * está aberto. Qualquer falha ABRIR o Intent precisa publicar
     * nativeOnFilePicked(kind, false, ...) — sem isso o slot fica preso para
     * sempre e TODO pedido seguinte é recusado no file_bridge (silêncio total
     * nos botões "Load ROM"/"GPU Driver").
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
                // Libera o slot no nativo e mostra o erro no menu do jogo.
                try {
                    nativeOnFilePicked(kind, false,
                            "Could not open the Android file manager: " + e.getMessage());
                } catch (UnsatisfiedLinkError ule) {
                    Log.e(TAG, "nativeOnFilePicked indisponível: " + ule.getMessage());
                }
            }
        });
        return true;
    }

    /**
     * Chamado via JNI pela thread de render (file_bridge.cpp) a partir da
     * opção "Logs de diagnóstico" do menu launcher do jogo (Configurações do
     * app). Abre a DiagnosticsActivity na MESMA task (voltar fecha a tela e
     * retorna ao jogo) — o ciclo onPause/onResume que isso dispara já é
     * coberto pelo congelamento da present queue (app_lifecycle), exatamente
     * como no seletor SAF.
     *
     * Fire-and-forget: sem slot no nativo e sem resultado de volta — só
     * posta o Intent e devolve. Falso = ponte indisponível (não bloqueia o
     * menu; o usuário ainda tem o shortcut do long-press no ícone).
     */
    public static boolean openDiagnosticsScreen() {
        final Activity activity = mSingleton;
        if (activity == null || activity.isFinishing()) {
            Log.e(TAG, "openDiagnosticsScreen: Activity indisponível");
            return false;
        }
        activity.runOnUiThread(() -> {
            try {
                activity.startActivity(new Intent(activity, DiagnosticsActivity.class));
            } catch (Exception e) {
                Log.e(TAG, "Falha ao abrir a tela de diagnóstico", e);
            }
        });
        return true;
    }

    /** Injeta o nativeLibraryDir no nativo cedo (implementado em custom_driver.cpp).
     *  Garante o hookLibDir exato exigido pelo adrenotools em TODA sessão —
     *  o scan de /proc/self/maps passa a ser apenas rede de segurança. O
     *  filesDir segue vindo do argv do SDL (getArguments) — o 1º parâmetro é
     *  mantido na assinatura por simetria e é ignorado no nativo. */
    private static native void nativeSetRuntimePaths(String filesDir, String nativeLibraryDir);

    /** Registro da JavaVM para a ponte de arquivos (implementado em file_bridge.cpp). */
    private static native void nativeBridgeInit();

    /** Registro da JavaVM para reinício do app (implementado em app_restart.cpp). */
    private static native void nativeRestartInit();

    /** Publica o resultado do SAF para o menu do jogo (implementado em file_bridge.cpp). */
    private static native void nativeOnFilePicked(int kind, boolean ok, String payload);

    /**
     * Congela/libera a present thread do RT64 (implementado em app_lifecycle.cpp).
     * false = app em segundo plano: a renderização para ANTES do Android destruir
     * a Surface (onPause chega ~600 ms antes do surfaceDestroyed); true = há
     * surface nova em primeiro plano: a thread recria VkSurface + swapchain e
     * retoma. Sem isto, o driver Vulkan da Adreno crasha ao apresentar em
     * surface destruída (crash ao abrir o seletor de ROM/driver).
     *
     * BUG EVITADO: este hook NÃO é mais chamado em onWindowFocusChanged(true),
     * porque a flutuação de foco durante a abertura do DocumentsUI gera um
     * "true" PREMATURO (em torno de 700 ms ANTES do surfaceDestroyed real),
     * liberando a present thread enquanto a surface ainda está prestes a
     * morrer — a thread entra em loop infinito de vkCreateSwapchainKHR em
     * surface abandonada (tela preta). A liberação AGORA acontece apenas em
     * surfaceCreated() (caminho determinístico). Veja nativeSurfaceState().
     */
    private static native void nativeSetAppActive(boolean active);

    /**
     * Sinaliza o ciclo de vida da Surface nativa (implementado em
     * app_lifecycle.cpp). state: 0 = surfaceDestroyed, 1 = surfaceCreated,
     * 2 = surfaceChanged.
     *
     * É o sinal DETERMINÍSTICO de surface (in)válida: o gate de Activity
     * (nativeSetAppActive) é reativo e propenso a falsos positivos pela
     * flutuação de foco, mas o caminho Surface→ANativeWindow→SDL é
     * determinístico — cada surfaceCreated/surfaceDestroyed corresponde
     * exatamente a um novo ANativeWindow no SDL_Window. A PresentQueue
     * consome esta marca para forçar swapChainValid=false + invalidateSurface()
     * mesmo com g_active=true (cobre o gap de 700 ms+ entre o foco
     * prematuro e o surfaceDestroyed do DocumentsUI).
     */
    private static native void nativeSurfaceState(int state);

    /** Callback anexado ao SurfaceHolder da SDLSurface para observar surfaceCreated/Destroyed/Changed. */
    private SurfaceHolder.Callback dk64SurfaceCallback;

    @Override
    public void onPause() {
        // ANTES do super (que sinaliza a pausa do SDL): congela a present
        // thread o quanto antes — surfaceDestroyed chega logo depois.
        DiagnosticsLogger.mark("onPause — app perdendo primeiro plano");
        try {
            nativeSetAppActive(false);
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "nativeSetAppActive(false) indisponível: " + e.getMessage());
        }
        super.onPause();
    }

    @Override
    public void onResume() {
        super.onResume();
        DiagnosticsLogger.mark("onResume — app de volta ao primeiro plano");
    }

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

            // NÃO chamamos mais nativeSetAppActive(true) aqui: o
            // onWindowFocusChanged(true) dispara durante a micro-flutuação
            // que acontece ANTES do surfaceDestroyed real chegar (ex.:
            // DocumentsUI sobreposto — visto no moto g34 5G com gap de
            // ~700 ms entre o foco temporário e o surfaceDestroyed). Liberar
            // o gate nesse momento faz a present thread acordar em uma
            // surface prestes a morrer e entrar em loop infinito de
            // vkCreateSwapchainKHR em surface abandonada (tela preta).
            //
            // A liberação AGORA acontece via nativeSurfaceState(1) em
            // surfaceCreated() — o sinal determinístico de surface válida.
            // Quem precisa notificar a thread sobre surface obsoleta usa
            // nativeSurfaceState(0) em surfaceDestroyed(), que cobre o caso
            // de o gate ter sido liberado prematuramente pela flutuação de
            // foco (consume_surface_dirty() na PresentQueue força a recriação
            // do swapchain mesmo com g_active=true).
        }
    }

    /**
     * SAÍDA DO JOGO = FIM DO PROCESSO.
     *
     * O jogo sai pelo menu Exit -> ultramodern::quit() seta o ESTÁTICO
     * `exited` (librecomp/recomp.cpp) -> SDL_main retorna -> SDLThread chama
     * mSingleton.finish() (SDLActivity.java ~L1891). O SDLActivity encerra a
     * própria thread e o SDL (nativeQuit -> SDL_Quit), mas o PROCESSO ficava
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
     * ports SDL no Android e é o que torna a troca de driver via menu
     * "GPU Driver" efetiva: Exit -> reabrir = driver novo.
     */
    @Override
    public void onDestroy() {
        Log.i(TAG, "MainActivity.onDestroy (isFinishing=" + isFinishing() + ")");
        // Finaliza a sessão de diagnóstico ANTES do killProcess: escreve o
        // bloco de RESUMO (erros/avisos agrupados) e fecha o arquivo. Depois
        // disto nada mais é capturado — o processo morre logo abaixo.
        DiagnosticsLogger.onMainActivityDestroy();
        /*
         * ENCERRAR O PROCESSO EM TODOS OS CAMINHOS DE DESTRUIÇÃO (bug do
         * driver "perdido" ao fechar/reabrir).
         *
         * A versão anterior matava o processo SOMENTE se isFinishing();
         * destruições iniciadas PELO SISTEMA chegam com isFinishing()==false
         * e sobreviviam: swipe do app em Recents (comportamento variável por
         * OEM/versão), "Don't keep activities" (comum em quem testa
         * emuladores), destruição por memória. O processo ficava cacheado com
         * a SDL morta e TODOS os estáticos nativos sujos (estado do driver em
         * custom_driver.cpp, tabela do volk já ligada ao driver anterior,
         * contexto RT64/RmlUi do run anterior). Reabrir rodava um 2º
         * SDL_main NESSE processo: o cache por fingerprint do ensure_loaded
         * _locked curto-circuitava, o Vulkan já estava inicializado com o
         * driver antigo e o driver novo (ou até o próprio driver) "sumia" —
         * a mesma classe do bug já corrigido no Exit do jogo, com guarda
         * fraca demais.
         *
         * killProcess ANTES do super.onDestroy: se o loop nativo não sair
         * limpo, o join do SDLThread em super.onDestroy travaria a UI thread
         * ANTES do kill chegar — mantendo o zumbi que gera o 2º SDL_main.
         * Matando primeiro, TODO relaunch nasce limpo: estáticos zerados e o
         * driver recarregado do zero a partir de files/driver/selected.txt
         * (com probe). Não há recriação por mudança de configuração aqui:
         * configChanges cobre orientação/uiMode/locale/densidade/etc.
         */
        android.os.Process.killProcess(android.os.Process.myPid());
        super.onDestroy();
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

    /**
     * Handler do pedido de reinício vindo do nativo (app_restart.cpp). Roda
     * na thread de reinício do nativo (anexada à JVM por AttachCurrentThread
     * em native_request_app_restart — antes disso o pedido nem chegava aqui).
     * O SDL está no loop de eventos do RT64, então finalizar a Activity direto
     * daqui é seguro: o super.onDestroy da SDLActivity para o SDL, e o
     * MainActivity.onDestroy mata o processo.
     *
     * A mensagem do nativo promete "The app will now restart": para cumprir,
     * agenda um ALARME de +1.5s com o PendingIntent da launcher ANTES de
     * finalizar — ele dispara DEPOIS que o onDestroy matar o processo,
     * reabrindo o app em cold start com o driver novo. Best-effort: se o
     * agendamento falhar (OEM sem alarme, etc.), o app apenas fecha e o
     * usuário reabre manualmente — que agora também carrega o driver
     * corretamente (kill incondicional no onDestroy).
     *
     * O nativo já exibiu a caixa de mensagem com o resultado do install/remove
     * antes de chamar isto (caixa modal do SDL, bloqueia a render thread até
     * o usuário fechar) — ver show_android_gpu_driver_menu() em src/main/main.cpp.
     */
    public static void handleNativeAppRestart() {
        Log.i(TAG, "handleNativeAppRestart: finalizando Activity para reinício "
                + "(driver Vulkan mudou; o app será reaberto pelo launcher)");
        final SDLActivity activity = mSingleton;
        if (activity == null || activity.isFinishing()) {
            Log.w(TAG, "handleNativeAppRestart: Activity indisponível/terminando — "
                    + "nada a fazer; o usuário precisa reabrir o app manualmente");
            return;
        }

        // Agenda a REABERTURA automática antes de encerrar (sobrevive ao
        // killProcess: o alarme é do sistema, não do processo).
        try {
            final Intent launch = activity.getPackageManager()
                    .getLaunchIntentForPackage(activity.getPackageName());
            final AlarmManager alarm =
                    (AlarmManager) activity.getSystemService(Context.ALARM_SERVICE);
            if (launch != null && alarm != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                final PendingIntent pi = PendingIntent.getActivity(activity, 0, launch,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                alarm.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime() + 1500L, pi);
                Log.i(TAG, "handleNativeAppRestart: reabertura automática agendada (+1.5s)");
            } else {
                Log.w(TAG, "handleNativeAppRestart: reabertura automática indisponível "
                        + "(launch intent/alarm manager nulos) — reabra manualmente");
            }
        } catch (Throwable t) {
            Log.w(TAG, "handleNativeAppRestart: falha ao agendar reabertura — "
                    + "reabra manualmente", t);
        }

        // Garante que o callback rode na UI thread (finish() precisa).
        activity.runOnUiThread(() -> {
            if (!activity.isFinishing()) {
                activity.finish();
            }
        });
    }
}
