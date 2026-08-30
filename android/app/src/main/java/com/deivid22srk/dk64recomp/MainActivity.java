package com.deivid22srk.dk64recomp;

import android.os.Bundle;
import android.util.Log;

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
 */
public class MainActivity extends SDLActivity {

    private static final String TAG = "DK64Recomp";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.i(TAG, "MainActivity.onCreate -> iniciando fluxo SDL");
        super.onCreate(savedInstanceState);
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
