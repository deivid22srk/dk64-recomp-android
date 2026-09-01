package com.deivid22srk.dk64recomp;

import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import org.libsdl.app.SDLActivity;

import java.io.File;

/**
 * Port Android do DK64: Recompiled — Activity do SDL.
 *
 * Esta Activity NÃO é a launcher: o {@link SetupActivity} garante ROM + assets
 * prontos antes de iniciá-la, então o onCreate NÃO é sobrescrito para "pular"
 * o super — o ciclo de vida do SDLActivity 2.30.8 (loadLibraries -> SDLThread
 * -> SDL_main) roda 100% padrão. Sobrescrever onCreate sem chamar
 * super.onCreate() derruba o app com SuperNotCalledException (o framework
 * exige chamada ao super.onCreate de android.app.Activity) e deixaria
 * mBrokenLibraries=true, bloqueando onResume/onWindowFocusChanged.
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.i(TAG, "MainActivity.onCreate -> iniciando fluxo SDL");
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
     * significa "fim de jogo", nunca recriação de configuração).
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
