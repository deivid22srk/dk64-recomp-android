package com.deivid22srk.dk64recomp;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.io.File;
import java.util.List;

/**
 * Tela de LOGS E DIAGNÓSTICO (pedido do usuário):
 *  - opção ATIVAR/DESATIVAR a captura de logs (efeito imediato, mesmo com o
 *    jogo rodando no mesmo processo);
 *  - status da sessão atual;
 *  - COMPARTILHAR o log (sessão atual ou das 5 sessões mais recentes) via
 *    ACTION_SEND + provider content:// próprio (sem AndroidX);
 *  - long-press em um log antigo = apagar.
 *
 * Como chegar aqui: long-press no ícone do app no launcher -> "Logs de
 * diagnóstico" (shortcut estático em res/xml/shortcuts.xml).
 *
 * UI 100% programática: o app é deliberadamente sem AndroidX e com uma
 * activity só; para uma tela utilitária como esta, código direto evita
 * inflar recursos. Tema herdado: AppTheme (fullscreen preto).
 */
public class DiagnosticsActivity extends Activity {

    private static final int MAX_LISTED = 5;

    private LinearLayout fileListContainer;
    private TextView statusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.BLACK);
        setContentView(scroll);

        int pad = dp(20);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Título
        root.addView(label("Logs e diagnóstico", 22, Typeface.BOLD));

        // Explicação
        TextView expl = label(
                "Com a captura ATIVADA, todo o log do jogo é registrado em um arquivo "
                        + "(áudio, renderização Vulkan/RT64, driver, ciclo de vida e crashes), "
                        + "linha a linha. Se o app fechar à força ou crashear, o que foi "
                        + "registrado até ali permanece salvo, e cada sessão termina com um "
                        + "RESUMO dos erros e avisos mais frequentes — deixando claro onde "
                        + "está o problema.\n\n"
                        + "Os arquivos ficam em: Android/data/" + getPackageName()
                        + "/files/diagnostics (ou no armazenamento interno do app, se o "
                        + "externo não estiver disponível).",
                14, Typeface.NORMAL);
        root.addView(expl);

        root.addView(spacer(16));

        // Toggle ON/OFF
        Switch toggle = new Switch(this);
        toggle.setText("Capturar logs");
        toggle.setTextSize(17);
        toggle.setTextColor(Color.WHITE);
        toggle.setChecked(DiagnosticsLogger.isEnabled(this));
        toggle.setOnCheckedChangeListener((b, checked) -> {
            DiagnosticsLogger.setEnabled(getApplicationContext(), checked);
            refresh();
        });
        root.addView(toggle);

        root.addView(spacer(8));

        // Status da sessão
        statusText = label("", 14, Typeface.NORMAL);
        root.addView(statusText);

        root.addView(spacer(12));

        // Compartilhar sessão atual
        Button shareCurrent = new Button(this);
        shareCurrent.setText("Compartilhar log da sessão atual");
        shareCurrent.setOnClickListener(v -> {
            File cur = DiagnosticsLogger.currentSessionFile();
            if (cur == null) {
                List<File> files = DiagnosticsLogger.listLogFiles(this);
                if (!files.isEmpty()) share(files.get(0));
                else toastStatus("Nenhum log disponível — ative a captura e use o jogo um pouco.");
            } else {
                share(cur);
            }
        });
        root.addView(shareCurrent);

        root.addView(spacer(16));

        // Lista de sessões recentes
        root.addView(label("Sessões recentes (toque = compartilhar; segurar = apagar)", 15, Typeface.BOLD));
        fileListContainer = new LinearLayout(this);
        fileListContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(fileListContainer);

        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    // ------------------------------------------------------------------

    private void refresh() {
        if (fileListContainer == null) return;
        File current = DiagnosticsLogger.currentSessionFile();
        boolean enabled = DiagnosticsLogger.isEnabled(this);

        if (statusText != null) {
            if (enabled && current != null) {
                statusText.setText("Captura ATIVA — sessão atual: " + current.getName()
                        + " (" + humanSize(current.length()) + ")");
            } else if (!enabled) {
                statusText.setText("Captura DESATIVADA — nenhum problema será registrado.");
            } else {
                statusText.setText("Captura ativada — a sessão começa no próximo "
                        + "início do app.");
            }
        }

        fileListContainer.removeAllViews();
        List<File> files = DiagnosticsLogger.listLogFiles(this);
        int shown = 0;
        for (File f : files) {
            if (shown >= MAX_LISTED) break;
            final File log = f;
            Button b = new Button(this);
            b.setAllCaps(false);
            b.setText(log.getName() + "  (" + humanSize(log.length()) + ")");
            b.setOnClickListener(v -> share(log));
            b.setOnLongClickListener(v -> { confirmDelete(log); return true; });
            fileListContainer.addView(b, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            shown++;
        }
        if (shown == 0) {
            TextView none = label("Nenhum log ainda.", 13, Typeface.ITALIC);
            fileListContainer.addView(none);
        }
    }

    /** ACTION_SEND via content:// do DiagnosticsFilesProvider (sem file://). */
    private void share(File log) {
        try {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_SUBJECT, log.getName());
            send.putExtra(Intent.EXTRA_STREAM,
                    DiagnosticsFilesProvider.shareUri(this, log.getName()));
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, "Compartilhar log"));
        } catch (Throwable t) {
            toastStatus("Falha ao compartilhar: " + t.getMessage());
        }
    }

    private void confirmDelete(File log) {
        new AlertDialog.Builder(this)
                .setTitle("Apagar log")
                .setMessage("Apagar " + log.getName() + "?")
                .setPositiveButton("Apagar", (d, w) -> {
                    try { log.delete(); } catch (Throwable ignored) { }
                    refresh();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    // ------------------------------------------------------------------

    private TextView label(String text, float sizeSp, int style) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(sizeSp);
        tv.setTypeface(Typeface.DEFAULT, style);
        tv.setTextColor(Color.WHITE);
        tv.setLineSpacing(dp(2), 1.0f);
        tv.setGravity(Gravity.START);
        return tv;
    }

    private View spacer(int heightDp) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(heightDp)));
        return v;
    }

    private void toastStatus(String msg) {
        if (statusText != null) statusText.setText(msg);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0);
        return String.format(java.util.Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0));
    }
}
