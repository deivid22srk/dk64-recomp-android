package com.deivid22srk.dk64recomp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.CornerPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Gamepad virtual do port Android do DK64: Recompiled.
 *
 * Overlay transparente desenhado inteiramente em [Canvas], herdando a
 * linguagem visual do projeto N64Pad2 (mesmo autor): traço cinza-claro,
 * realce verde nos controles pressionados, setas de 8 direções no analógico,
 * vibração leve e multi-touch real — mas com o HUD posicionado no estilo do
 * Dolphin (gatilhos nas quinas superiores, analógico à esquerda, cluster de
 * ações à direita, D-pad e Start na base), com transparência sobre o jogo
 * para não esconder a ação.
 *
 * Integração nativa:
 *  - Cada toque é repassado para `android/native/compat/virtual_pad.cpp`
 *    (JNI), que mantém o estado usado pelo runtime do jogo e espelha os
 *    eventos para as interfaces (launcher/menu do port) como eventos SDL.
 *  - O pad aparece sozinho quando o jogo inicia (callback [onGameStarted],
 *    chamado pela thread nativa) e pode ser escondido/mostrado pelo botão
 *    da quina inferior direita.
 *
 * Layout (frações da tela, referência paisagem):
 *  - Topo:        L (esq) · Z (centro) · R (dir)
 *  - Esquerda:    analógico (y ~60%)
 *  - Base:        D-pad (~27%, 80%) · START (~47%, 88%) · MENU ☰ (~57%, 88%)
 *  - Direita:     losango de C (~73%, 33%) · A (~87%, 57%) · B (~73%, 70%)
 *  - Quina inf. dir.: botão de mostrar/esconder o HUD
 */
class VirtualPadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        // ------------------------------------------------------------------
        // Ids de botão — DEVEM casar com o enum ButtonId de virtual_pad.cpp.
        // ------------------------------------------------------------------
        const val BTN_A = 0
        const val BTN_B = 1
        const val BTN_Z = 2
        const val BTN_L = 3
        const val BTN_R = 4
        const val BTN_START = 5
        const val BTN_C_UP = 6
        const val BTN_C_DOWN = 7
        const val BTN_C_LEFT = 8
        const val BTN_C_RIGHT = 9
        const val BTN_DPAD_UP = 10
        const val BTN_DPAD_DOWN = 11
        const val BTN_DPAD_LEFT = 12
        const val BTN_DPAD_RIGHT = 13
        const val BTN_MENU = 14 // abre/fecha o menu do port (não chega ao jogo)

        // Zona morta radial do analógico (evita "andar sozinho" com o dedo parado).
        const val STICK_DEADZONE = 0.06f

        // Alpha dos controles sobre o jogo (estilo Dolphin).
        const val ALPHA_IDLE = 0.58f
        const val ALPHA_PRESSED = 0.97f

        @Volatile
        private var instance: VirtualPadView? = null

        /**
         * Callback chamado PELO CÓDIGO NATIVO (virtual_pad.cpp) quando o jogo
         * inicia/encerra. Pode chegar de uma thread sem loop Android, então
         * é marshalado para a thread de UI via [post].
         */
        @JvmStatic
        fun onGameStarted(started: Boolean) {
            instance?.post {
                instance?.handleGameStarted(started)
            }
        }
    }

    // ---------------------------------------------------------------- cores
    // Mesma paleta do N64Pad2 (identidade visual do autor), com alpha próprio
    // de overlay.
    private val colOutline = Color.parseColor("#C8C8C8")
    private val colOutlineDim = Color.parseColor("#8A8F8C")
    private val colAccent = Color.parseColor("#9BD32B") // verde: controla pressionado
    private val colHalo = Color.parseColor("#000000")   // halo escuro sob cada controle

    private val colA64 = Color.parseColor("#5C87B0")    // A: azul-acinzentado
    private val outA64 = Color.parseColor("#B3D9F7")
    private val colB64 = Color.parseColor("#3053C4")    // B: azul escuro
    private val outB64 = Color.parseColor("#8FA8F5")
    private val colC = Color.parseColor("#D1A312")      // C: amarelo
    private val outC = Color.parseColor("#F6DC7A")
    private val colStart = Color.parseColor("#B01B2E")  // START: vermelho
    private val outStart = Color.parseColor("#F2707F")
    private val colMenu = Color.parseColor("#2B2B2B")   // MENU: grafite
    private val outMenu = Color.parseColor("#BFBFBF")

    // ---------------------------------------------------------------- paints
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    // ---------------------------------------------------------------- modelo
    private class RoundBtn(val id: Int, val label: String, val fill: Int, val outline: Int) {
        var cx = 0f
        var cy = 0f
        var r = 0f
        var pointerId = -1
        val pressed: Boolean get() = pointerId != -1

        fun hit(x: Float, y: Float): Boolean {
            val rr = r * 1.35f
            val dx = x - cx
            val dy = y - cy
            return dx * dx + dy * dy <= rr * rr
        }
    }

    private class ShoulderBtn(val id: Int, val label: String) {
        val rect = RectF()
        var radius = 0f
        var pointerId = -1
        val pressed: Boolean get() = pointerId != -1

        fun hit(x: Float, y: Float): Boolean {
            val pad = rect.height() * 0.18f
            return x >= rect.left - pad && x <= rect.right + pad &&
                y >= rect.top - pad && y <= rect.bottom + pad
        }
    }

    private class Stick {
        var cx = 0f
        var cy = 0f
        var outerR = 0f
        var knobR = 0f
        var dx = 0f // -1..1 (espaço de tela)
        var dy = 0f
        var pointerId = -1
        val active: Boolean get() = pointerId != -1

        fun hit(x: Float, y: Float): Boolean {
            val rr = outerR * 1.25f
            val ddx = x - cx
            val ddy = y - cy
            return ddx * ddx + ddy * ddy <= rr * rr
        }
    }

    private class DPad {
        var cx = 0f
        var cy = 0f
        var size = 0f      // meia largura do braço externo
        var armWidth = 0f  // meia largura do braço interno
        var pointerId = -1
        var up = false
        var down = false
        var left = false
        var right = false
        val pressed: Boolean get() = pointerId != -1

        fun hit(x: Float, y: Float): Boolean {
            val dx = abs(x - cx)
            val dy = abs(y - cy)
            return (dx <= size && dy <= armWidth * 1.4f) || (dy <= size && dx <= armWidth * 1.4f) ||
                (dx <= size * 0.85f && dy <= size * 0.85f)
        }

        fun clear() {
            up = false; down = false; left = false; right = false
        }
    }

    private val btnA = RoundBtn(BTN_A, "A", colA64, outA64)
    private val btnB = RoundBtn(BTN_B, "B", colB64, outB64)
    private val btnCU = RoundBtn(BTN_C_UP, "C", colC, outC)
    private val btnCD = RoundBtn(BTN_C_DOWN, "C", colC, outC)
    private val btnCL = RoundBtn(BTN_C_LEFT, "C", colC, outC)
    private val btnCR = RoundBtn(BTN_C_RIGHT, "C", colC, outC)
    private val btnStart = RoundBtn(BTN_START, "START", colStart, outStart)
    private val btnMenu = RoundBtn(BTN_MENU, "", colMenu, outMenu) // ícone ☰ desenhado

    private val cButtons = listOf(btnCU, btnCD, btnCL, btnCR)
    private val faceButtons = listOf(btnA, btnB, btnStart, btnMenu) + cButtons

    private val lt = ShoulderBtn(BTN_L, "L")
    private val rt = ShoulderBtn(BTN_R, "R")
    private val zt = ShoulderBtn(BTN_Z, "Z")
    private val shoulders = listOf(lt, rt, zt)

    private val leftStick = Stick()
    private val dpad = DPad()

    // botão de mostrar/esconder o HUD (quina inferior direita)
    private val toggleRect = RectF()

    private var unit = 1f
    private var padVisible = false
    private var gameStarted = false
    private val tmpPath = Path()

    init {
        instance = this
        // libmain.so já foi carregada pelo SDLActivity (super.onCreate) antes
        // desta view ser adicionada à hierarquia.
        runCatching { nativeInit() }
    }

    // ---------------------------------------------------------------- jni
    private external fun nativeInit()
    private external fun nativeButton(id: Int, pressed: Boolean)
    private external fun nativeAxis(x: Float, y: Float)

    // ---------------------------------------------------------------- estado
    private fun handleGameStarted(started: Boolean) {
        gameStarted = started
        if (!started) {
            padVisible = false
            releaseAll(sendNative = true)
        }
        invalidate()
    }

    private fun setPadVisible(visible: Boolean) {
        if (padVisible == visible) return
        padVisible = visible
        releaseAll(sendNative = true)
        invalidate()
    }

    // ---------------------------------------------------------------- layout
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == 0 || h == 0) return

        val fw = w.toFloat()
        val fh = h.toFloat()
        // Escala do N64Pad2: referência 1280x720 paisagem.
        unit = min(fh / 720f, fw / 1280f)

        // ---- gatilhos (topo, estilo Dolphin: quinas + centro)
        val trigW = 190f * unit
        val trigH = 96f * unit
        lt.rect.set(fw * 0.045f, fh * 0.045f, fw * 0.045f + trigW, fh * 0.045f + trigH)
        lt.radius = 26f * unit
        rt.rect.set(fw * 0.955f - trigW, fh * 0.045f, fw * 0.955f, fh * 0.045f + trigH)
        rt.radius = 26f * unit
        zt.rect.set(fw * 0.44f, fh * 0.045f, fw * 0.56f, fh * 0.045f + trigH)
        zt.radius = 26f * unit

        // ---- analógico (esquerda)
        leftStick.cx = fw * 0.115f
        leftStick.cy = fh * 0.60f
        leftStick.outerR = 92f * unit
        leftStick.knobR = 46f * unit

        // ---- D-pad (base esquerda-centro)
        dpad.cx = fw * 0.27f
        dpad.cy = fh * 0.80f
        dpad.size = 66f * unit
        dpad.armWidth = 32f * unit

        // ---- START e MENU (base central)
        btnStart.cx = fw * 0.475f
        btnStart.cy = fh * 0.88f
        btnStart.r = 36f * unit

        btnMenu.cx = fw * 0.575f
        btnMenu.cy = fh * 0.88f
        btnMenu.r = 30f * unit

        // ---- losango de botões C (direita, acima do A)
        val clusterX = fw * 0.735f
        val clusterY = fh * 0.335f
        val spread = 62f * unit
        val cR = 26f * unit
        btnCU.cx = clusterX; btnCU.cy = clusterY - spread; btnCU.r = cR
        btnCD.cx = clusterX; btnCD.cy = clusterY + spread; btnCD.r = cR
        btnCL.cx = clusterX - spread; btnCL.cy = clusterY; btnCL.r = cR
        btnCR.cx = clusterX + spread; btnCR.cy = clusterY; btnCR.r = cR

        // ---- A (grande) e B (menor, diagonal inferior-esquerda, como no N64)
        btnA.cx = fw * 0.875f
        btnA.cy = fh * 0.575f
        btnA.r = 46f * unit

        btnB.cx = fw * 0.735f
        btnB.cy = fh * 0.70f
        btnB.r = 32f * unit

        // ---- botão de alternar HUD (quina inferior direita)
        val tR = 26f * unit
        toggleRect.set(fw * 0.945f - tR, fh * 0.90f - tR, fw * 0.945f + tR, fh * 0.90f + tR)
    }

    // ---------------------------------------------------------------- desenho
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!gameStarted) return // launcher: overlay 100% pass-through

        // botão de alternância existe sempre (mostrar/esconder o HUD)
        drawToggle(canvas)

        if (!padVisible) return

        for (b in shoulders) drawShoulder(canvas, b)
        drawStick(canvas)
        drawDPad(canvas)
        for (b in cButtons) drawCButton(canvas, b)
        for (b in faceButtons) drawFaceButton(canvas, b)
    }

    /** halo escuro atrás do controle para destacar sobre o jogo */
    private fun drawHalo(canvas: Canvas, cx: Float, cy: Float, r: Float, pressed: Boolean) {
        fillPaint.color = colHalo
        fillPaint.alpha = if (pressed) 120 else 92
        canvas.drawCircle(cx, cy, r, fillPaint)
        fillPaint.alpha = 255
    }

    private fun drawToggle(canvas: Canvas) {
        val cx = toggleRect.centerX()
        val cy = toggleRect.centerY()
        val r = toggleRect.width() / 2f
        drawHalo(canvas, cx, cy, r * 1.1f, false)

        strokePaint.pathEffect = null
        strokePaint.strokeWidth = 3f * unit
        strokePaint.color = colOutline
        strokePaint.alpha = 200
        canvas.drawCircle(cx, cy, r, strokePaint)

        // ícone: ✕ (esconder) ou desenho de controle (mostrar)
        strokePaint.color = Color.WHITE
        strokePaint.alpha = 230
        if (padVisible) {
            val k = r * 0.42f
            canvas.drawLine(cx - k, cy - k, cx + k, cy + k, strokePaint)
            canvas.drawLine(cx - k, cy + k, cx + k, cy - k, strokePaint)
        } else {
            // mini-controle: retângulo com dois "furos" de grip
            val w = r * 1.1f
            val h = r * 0.62f
            canvas.drawRoundRect(cx - w, cy - h, cx + w, cy + h, h, h, strokePaint)
            fillPaint.color = Color.WHITE
            fillPaint.alpha = 230
            canvas.drawCircle(cx - w * 0.45f, cy, r * 0.10f, fillPaint)
            canvas.drawCircle(cx + w * 0.45f, cy, r * 0.10f, fillPaint)
            fillPaint.alpha = 255
        }
        strokePaint.alpha = 255
    }

    private fun drawShoulder(canvas: Canvas, b: ShoulderBtn) {
        strokePaint.pathEffect = null
        strokePaint.strokeWidth = 5f * unit
        strokePaint.color = if (b.pressed) colAccent else colOutline
        strokePaint.alpha = if (b.pressed) 255 else (255 * ALPHA_IDLE).toInt()

        if (b.pressed) {
            fillPaint.color = Color.parseColor("#1F2A10")
            fillPaint.alpha = 230
            canvas.drawRoundRect(b.rect, b.radius, b.radius, fillPaint)
            fillPaint.alpha = 255
        } else {
            fillPaint.color = colHalo
            fillPaint.alpha = 70
            canvas.drawRoundRect(b.rect, b.radius, b.radius, fillPaint)
            fillPaint.alpha = 255
        }
        canvas.drawRoundRect(b.rect, b.radius, b.radius, strokePaint)

        // linha interna sutil (identidade N64Pad2/X360Pad)
        strokePaint.strokeWidth = 2f * unit
        strokePaint.color = if (b.pressed) colAccent else colOutlineDim
        strokePaint.alpha = if (b.pressed) 255 else (255 * ALPHA_IDLE * 0.9f).toInt()
        val inset = 8f * unit
        canvas.drawRoundRect(
            b.rect.left + inset, b.rect.top + inset,
            b.rect.right - inset, b.rect.bottom - inset,
            b.radius * 0.8f, b.radius * 0.8f, strokePaint
        )

        textPaint.textSize = 42f * unit
        textPaint.color = if (b.pressed) colAccent else colOutline
        textPaint.alpha = if (b.pressed) 255 else (255 * (ALPHA_IDLE + 0.25f)).toInt()
        canvas.drawText(b.label, b.rect.centerX(), b.rect.centerY() + textPaint.textSize * 0.36f, textPaint)
        strokePaint.alpha = 255
        textPaint.alpha = 255
    }

    private fun drawStick(canvas: Canvas) {
        val cx = leftStick.cx
        val cy = leftStick.cy
        val r = leftStick.outerR
        val alpha = if (leftStick.active) 255 else (255 * ALPHA_IDLE).toInt()

        drawHalo(canvas, cx, cy, r * 1.06f, leftStick.active)

        strokePaint.pathEffect = null
        strokePaint.strokeWidth = 4f * unit
        strokePaint.color = if (leftStick.active) colAccent else colOutline
        strokePaint.alpha = alpha
        canvas.drawCircle(cx, cy, r, strokePaint)

        // setas de direção internas (8 posições) — identidade N64Pad2
        val arrowR = r * 0.70f
        val arrowSize = r * 0.11f
        fillPaint.color = if (leftStick.active) colAccent else colOutlineDim
        fillPaint.alpha = alpha
        for (i in 0 until 8) {
            val ang = Math.toRadians((i * 45).toDouble())
            val ax = cx + (arrowR * cos(ang)).toFloat()
            val ay = cy + (arrowR * sin(ang)).toFloat()
            drawTriangle(canvas, ax, ay, arrowSize, ang.toFloat())
        }

        // knob (segue o dedo)
        val kx = cx + leftStick.dx * (r - leftStick.knobR) * 0.92f
        val ky = cy + leftStick.dy * (r - leftStick.knobR) * 0.92f
        strokePaint.strokeWidth = 5f * unit
        strokePaint.color = if (leftStick.active) colAccent else colOutline
        strokePaint.alpha = alpha
        if (leftStick.active) {
            fillPaint.color = Color.parseColor("#17210C")
            fillPaint.alpha = 235
            canvas.drawCircle(kx, ky, leftStick.knobR, fillPaint)
            fillPaint.alpha = 255
        } else {
            fillPaint.color = colHalo
            fillPaint.alpha = 110
            canvas.drawCircle(kx, ky, leftStick.knobR, fillPaint)
            fillPaint.alpha = 255
        }
        canvas.drawCircle(kx, ky, leftStick.knobR, strokePaint)
        strokePaint.alpha = 255
        fillPaint.alpha = 255
    }

    /** triângulo apontando para fora, no ângulo informado */
    private fun drawTriangle(canvas: Canvas, x: Float, y: Float, size: Float, angle: Float) {
        tmpPath.reset()
        val tipX = x + size * cos(angle)
        val tipY = y + size * sin(angle)
        val a1 = angle + 2.4f
        val a2 = angle - 2.4f
        tmpPath.moveTo(tipX, tipY)
        tmpPath.lineTo(x + size * cos(a1), y + size * sin(a1))
        tmpPath.lineTo(x + size * cos(a2), y + size * sin(a2))
        tmpPath.close()
        canvas.drawPath(tmpPath, fillPaint)
    }

    private fun drawDPad(canvas: Canvas) {
        val cx = dpad.cx
        val cy = dpad.cy
        val s = dpad.size
        val a = dpad.armWidth

        tmpPath.reset()
        tmpPath.moveTo(cx - a, cy - s)
        tmpPath.lineTo(cx + a, cy - s)
        tmpPath.lineTo(cx + a, cy - a)
        tmpPath.lineTo(cx + s, cy - a)
        tmpPath.lineTo(cx + s, cy + a)
        tmpPath.lineTo(cx + a, cy + a)
        tmpPath.lineTo(cx + a, cy + s)
        tmpPath.lineTo(cx - a, cy + s)
        tmpPath.lineTo(cx - a, cy + a)
        tmpPath.lineTo(cx - s, cy + a)
        tmpPath.lineTo(cx - s, cy - a)
        tmpPath.lineTo(cx - a, cy - a)
        tmpPath.close()

        val anyDir = dpad.up || dpad.down || dpad.left || dpad.right
        val alpha = if (dpad.pressed) 255 else (255 * ALPHA_IDLE).toInt()

        drawHalo(canvas, cx, cy, s * 1.12f, anyDir)

        strokePaint.pathEffect = CornerPathEffect(10f * unit)
        strokePaint.strokeWidth = 5f * unit
        strokePaint.color = if (anyDir) colAccent else colOutline
        strokePaint.alpha = alpha
        canvas.drawPath(tmpPath, strokePaint)
        strokePaint.pathEffect = null

        // realce do braço acionado
        fillPaint.color = Color.parseColor("#233010")
        fillPaint.alpha = if (dpad.pressed) 235 else (235 * ALPHA_IDLE).toInt()
        if (dpad.up) canvas.drawRect(cx - a, cy - s, cx + a, cy - a, fillPaint)
        if (dpad.down) canvas.drawRect(cx - a, cy + a, cx + a, cy + s, fillPaint)
        if (dpad.left) canvas.drawRect(cx - s, cy - a, cx - a, cy + a, fillPaint)
        if (dpad.right) canvas.drawRect(cx + a, cy - a, cx + s, cy + a, fillPaint)
        strokePaint.alpha = 255
        fillPaint.alpha = 255
    }

    private fun drawFaceButton(canvas: Canvas, b: RoundBtn) {
        val alpha = if (b.pressed) 255 else (255 * ALPHA_IDLE).toInt()
        drawHalo(canvas, b.cx, b.cy, b.r * 1.16f, b.pressed)

        fillPaint.color = b.fill
        fillPaint.alpha = alpha
        val rr = if (b.pressed) b.r * 1.08f else b.r
        canvas.drawCircle(b.cx, b.cy, rr, fillPaint)

        strokePaint.pathEffect = null
        strokePaint.strokeWidth = 3f * unit
        strokePaint.color = if (b.pressed) Color.WHITE else b.outline
        strokePaint.alpha = alpha
        canvas.drawCircle(b.cx, b.cy, rr, strokePaint)

        val textSize = if (b.id == BTN_START) b.r * 0.42f else b.r * 1.05f
        if (b.id == BTN_MENU) {
            // ícone "hamburger" (3 traços) — independe da fonte do sistema
            strokePaint.pathEffect = null
            strokePaint.strokeWidth = b.r * 0.13f
            strokePaint.color = if (b.pressed) Color.WHITE else b.outline
            strokePaint.alpha = alpha
            val w = b.r * 0.55f
            val gap = b.r * 0.30f
            for (i in -1..1) {
                canvas.drawLine(b.cx - w, b.cy + i * gap, b.cx + w, b.cy + i * gap, strokePaint)
            }
        } else {
            textPaint.textSize = textSize
            textPaint.color = if (b.pressed) Color.WHITE else b.outline
            textPaint.alpha = alpha
            val labelY = if (b.id == BTN_START) b.cy + textSize * 0.35f else b.cy + textSize * 0.35f
            canvas.drawText(b.label, b.cx, labelY, textPaint)
        }

        fillPaint.alpha = 255
        strokePaint.alpha = 255
        textPaint.alpha = 255
    }

    /** botão C amarelo com uma seta indicando a direção (identidade N64Pad2) */
    private fun drawCButton(canvas: Canvas, b: RoundBtn) {
        drawFaceButton(canvas, b)

        val alpha = if (b.pressed) 255 else (255 * ALPHA_IDLE).toInt()
        val ang = when (b.id) {
            BTN_C_UP -> -Math.PI / 2.0
            BTN_C_DOWN -> Math.PI / 2.0
            BTN_C_LEFT -> Math.PI
            else -> 0.0
        }.toFloat()
        fillPaint.color = if (b.pressed) Color.WHITE else b.outline
        fillPaint.alpha = alpha
        drawTriangle(canvas, b.cx, b.cy, b.r * 0.42f, ang)
        fillPaint.alpha = 255
    }

    // ---------------------------------------------------------------- toque
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!gameStarted) return false // launcher: deixa o SDL tratar (mouse/touch)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                if (!claim(event.getPointerId(index), event.getX(index), event.getY(index))) {
                    // toque em área livre: não consome, cai para o SDL
                    // (navega menus por toque mesmo com o HUD visível)
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) return false
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val id = event.getPointerId(i)
                    val x = event.getX(i)
                    val y = event.getY(i)
                    if (leftStick.pointerId == id) updateStick(x, y)
                    if (dpad.pointerId == id) updateDPad(x, y)
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                release(event.getPointerId(event.actionIndex))
                invalidate()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                releaseAll(sendNative = true)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun claim(pointerId: Int, x: Float, y: Float): Boolean {
        // botão de alternar HUD (funciona com o pad visível ou escondido)
        if (toggleRect.contains(x, y)) {
            haptic()
            setPadVisible(!padVisible)
            return true
        }
        if (!padVisible) return false

        if (!leftStick.active && leftStick.hit(x, y)) {
            leftStick.pointerId = pointerId
            updateStick(x, y)
            haptic()
            return true
        }
        if (!dpad.pressed && dpad.hit(x, y)) {
            dpad.pointerId = pointerId
            updateDPad(x, y)
            haptic()
            return true
        }
        for (b in faceButtons) {
            if (!b.pressed && b.hit(x, y)) {
                b.pointerId = pointerId
                nativeButton(b.id, true)
                haptic()
                return true
            }
        }
        for (b in shoulders) {
            if (!b.pressed && b.hit(x, y)) {
                b.pointerId = pointerId
                nativeButton(b.id, true)
                haptic()
                return true
            }
        }
        return false
    }

    private fun release(pointerId: Int) {
        if (leftStick.pointerId == pointerId) {
            leftStick.pointerId = -1
            leftStick.dx = 0f
            leftStick.dy = 0f
            nativeAxis(0f, 0f)
        }
        if (dpad.pointerId == pointerId) {
            dpad.pointerId = -1
            clearDPad()
        }
        for (b in faceButtons) {
            if (b.pointerId == pointerId) {
                b.pointerId = -1
                nativeButton(b.id, false)
            }
        }
        for (b in shoulders) {
            if (b.pointerId == pointerId) {
                b.pointerId = -1
                nativeButton(b.id, false)
            }
        }
    }

    private fun releaseAll(sendNative: Boolean) {
        if (sendNative) {
            for (b in faceButtons) {
                if (b.pressed) nativeButton(b.id, false)
            }
            for (b in shoulders) {
                if (b.pressed) nativeButton(b.id, false)
            }
            if (leftStick.active || leftStick.dx != 0f || leftStick.dy != 0f) {
                nativeAxis(0f, 0f)
            }
            if (dpad.pressed) releaseDPadNative()
        }
        leftStick.pointerId = -1
        leftStick.dx = 0f
        leftStick.dy = 0f
        dpad.pointerId = -1
        dpad.clear()
        for (b in faceButtons) b.pointerId = -1
        for (b in shoulders) b.pointerId = -1
    }

    private fun updateStick(x: Float, y: Float) {
        var ddx = (x - leftStick.cx) / leftStick.outerR
        var ddy = (y - leftStick.cy) / leftStick.outerR
        val mag = hypot(ddx.toDouble(), ddy.toDouble()).toFloat()
        if (mag > 1f) {
            ddx /= mag
            ddy /= mag
        }
        leftStick.dx = ddx
        leftStick.dy = ddy

        // Converte para o espaço do N64: +y para cima (inverte o eixo da tela),
        // aplica zona morta radial e envia ao nativo.
        var ax = ddx
        var ay = -ddy
        val aMag = hypot(ax.toDouble(), ay.toDouble()).toFloat()
        if (aMag <= STICK_DEADZONE) {
            ax = 0f
            ay = 0f
        } else if (aMag > 1f) {
            ax /= aMag
            ay /= aMag
        }
        nativeAxis(ax, ay)
    }

    private fun updateDPad(x: Float, y: Float) {
        val ddx = x - dpad.cx
        val ddy = y - dpad.cy
        val dead = dpad.armWidth * 0.45f
        val was = "${dpad.up}${dpad.down}${dpad.left}${dpad.right}"
        dpad.clear()
        if (abs(ddx) > dead || abs(ddy) > dead) {
            if (abs(ddx) > abs(ddy) * 0.45f) {
                if (ddx > 0) dpad.right = true else dpad.left = true
            }
            if (abs(ddy) > abs(ddx) * 0.45f) {
                if (ddy > 0) dpad.down = true else dpad.up = true
            }
        }
        val now = "${dpad.up}${dpad.down}${dpad.left}${dpad.right}"
        if (was != now) {
            val wasUp = was[0] == 't'; val wasDown = was[1] == 't'
            val wasLeft = was[2] == 't'; val wasRight = was[3] == 't'
            if (dpad.up != wasUp) nativeButton(BTN_DPAD_UP, dpad.up)
            if (dpad.down != wasDown) nativeButton(BTN_DPAD_DOWN, dpad.down)
            if (dpad.left != wasLeft) nativeButton(BTN_DPAD_LEFT, dpad.left)
            if (dpad.right != wasRight) nativeButton(BTN_DPAD_RIGHT, dpad.right)
            haptic()
        }
    }

    private fun clearDPad() {
        releaseDPadNative()
        dpad.clear()
    }

    private fun releaseDPadNative() {
        if (dpad.up) nativeButton(BTN_DPAD_UP, false)
        if (dpad.down) nativeButton(BTN_DPAD_DOWN, false)
        if (dpad.left) nativeButton(BTN_DPAD_LEFT, false)
        if (dpad.right) nativeButton(BTN_DPAD_RIGHT, false)
    }

    private fun haptic() {
        performHapticFeedback(
            HapticFeedbackConstants.VIRTUAL_KEY,
            HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
        )
    }
}
