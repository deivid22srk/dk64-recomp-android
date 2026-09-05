package com.deivid22srk.dk64recomp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// ---------------------------------------------------------------------------
// Tuning do gamepad v2 — linguagem visual "dark glass + identidade N64".
// Valores em frações/`unit`, onde unit = min(alt/720, larg/1280) da área útil
// (área útil = tela − display cutout − margem), referência paisagem 1280x720.
// ---------------------------------------------------------------------------
private const val STICK_DEADZONE = 0.08f      // zona morta radial (com reescala)
private const val HIT_SCALE = 1.45f           // hit dos botões redondos (× raio)
private const val MIN_HIT_UNIT = 48f          // hit mínimo (× unit) — alvo de toque ≥ 48dp
private const val EXIT_HYSTERESIS = 1.75f     // solta o botão só além de hit × 1.75
private const val STICK_HIT_SCALE = 1.35f     // hit do stick (× raio externo)
private const val PRESS_IN_MS = 90f           // animação de pressionar
private const val PRESS_OUT_MS = 140f         // animação de soltar
private const val HUD_FADE_MS = 200f          // fade do HUD ao mostrar/esconder
private const val KNOB_RETURN_TAU = 60f       // constante de tempo do retorno do knob

// Paleta base do vidro (RGB puro; o alpha é aplicado por paint e escalado pelo
// fade do HUD — nada de alpha "assado" na cor, evita dupla transformação).
private const val GLASS_RGB = 0x0A0D12
private const val GLASS_ALPHA = 140           // base do vidro (55%)
private const val HILITE_TOP = 0x26FFFFFF.toInt() // topo do gradiente (15%)
private const val HILITE_BOT = 0x08FFFFFF.toInt() // base do gradiente (3%)
private const val SHADOW_COLOR = 0x59000000   // sombra suave (35%)
private const val BORDER_ALPHA = 110          // contorno idle (43%) — legível em cenas claras
private const val LABEL_ALPHA = 217           // label idle (85%)

// Accent do projeto (verde-banana DK64) para controles neutros pressionados.
private const val ACCENT = 0xFF9BD32B.toInt()

// Identidade N64 (hardware real): A azul, B VERDE, C amarelo, START vermelho.
// Tinta sutil no idle (borda/label) e glow no pressionado.
private const val TINT_A = 0xFF7FB2E5.toInt()
private const val TINT_B = 0xFF63C46B.toInt()
private const val TINT_C = 0xFFF6DC7A.toInt()
private const val TINT_START = 0xFFF2707F.toInt()
private const val TINT_NEUTRAL = 0xFFE8ECEF.toInt()

// Stops dos gradientes cacheados (RadialGradient/LinearGradient).
private val TWO_STOPS = floatArrayOf(0f, 1f)
private val SHADOW_STOPS = floatArrayOf(0.5f, 1f)
private val GLOW_STOPS = floatArrayOf(0.5f, 0.86f, 1f)

/**
 * Gamepad virtual do port Android do DK64: Recompiled — v2 "dark glass".
 *
 * Overlay transparente desenhado inteiramente em [Canvas] sobre o SDLSurface.
 * Linguagem visual: vidro escuro translúcido (gradiente + sombra suave via
 * gradiente radial, sem BlurMaskFilter/setShadowLayer por compatibilidade de
 * HW), contorno fino, labels na fonte do jogo (InterVariable) e feedback de
 * pressionado com escala (o controle "afunda") + glow na cor de identidade
 * N64 (A azul, B verde, C amarelo, START vermelho; neutros usam o accent
 * verde DK64). O HUD entra/sai com fade+deslize e aparece sozinho quando o
 * jogo inicia (a escolha de esconder é persistida em SharedPreferences).
 *
 * Ergonomia: hit-areas generosas com prioridade por proximidade, histerese de
 * saída (deslizar o dedo para fora solta o botão, mata "ghost press"), stick
 * com zona morta reescalada e retorno animado do knob, haptics diferenciados
 * (press × mudança de setor) e [requestUnbufferedDispatch] para menor latência
 * de toque. O D-pad não tem controle na tela: o jogo não o usa (todo o
 * movimento é pelo analógico) e a base esquerda do HUD fica mais limpa.
 *
 * Integração nativa (CONTRATO — não mudar nomes/ids):
 *  - `nativeInit/nativeButton/nativeAxis/nativeIsGameStarted` vinculam por
 *    nome de classe em `android/native/compat/virtual_pad.cpp`, que mantém o
 *    estado lido pelo runtime do jogo e espelha eventos SDL para as UIs.
 *  - [onGameStarted] é chamado PELA THREAD NATIVA em transições jogo↔launcher;
 *    o estado também é reconsultado no init ([nativeIsGameStarted]) para o pad
 *    reaparecer corretamente se a Activity for recriada com o jogo rodando.
 *  - Toques que não acertam controle nenhum retornam `false` e caem no SDL
 *    (touch-as-mouse dos menus), inclusive no launcher (overlay pass-through).
 *
 * Layout (frações da ÁREA ÚTIL, referência paisagem):
 *  - Topo:      Z (esq, pose do indicador esquerdo do N64) · L (centro) · R (dir)
 *  - Esquerda:  analógico (12%, 63%)
 *  - Base:      START (47.5%, 87%)
 *  - Direita:   losango C (76%, 42%) · A (88%, 58%) · B (79%, 72%) · MENU (65.5%, 88%)
 *  - Quina inf. dir.: botão de mostrar/esconder o HUD (94%, 89%)
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
        // D-pad (10..13): existe no contrato nativo (o enum do virtual_pad lê
        // esses bits), mas não tem controle na tela — removido por não ser
        // usado no DK64; os ids são mantidos para casar com o enum nativo.
        const val BTN_DPAD_UP = 10
        const val BTN_DPAD_DOWN = 11
        const val BTN_DPAD_LEFT = 12
        const val BTN_DPAD_RIGHT = 13
        const val BTN_MENU = 14 // abre/fecha o menu do port (não chega ao jogo)

        // Persistência da escolha do usuário (HUD visível ou não).
        private const val PREFS_NAME = "virtual_pad"
        private const val PREF_HUD_VISIBLE = "hud_visible"

        @Volatile
        private var instance: VirtualPadView? = null

        /**
         * Callback chamado PELO CÓDIGO NATIVO (virtual_pad.cpp) quando o jogo
         * inicia/encerra. Pode chegar de uma thread sem loop Android, então
         * é marshalado para a thread de UI via [post].
         */
        @JvmStatic
        fun onGameStarted(started: Boolean) {
            val view = instance
            view?.post { view.handleGameStarted(started) }
        }
    }

    // ---------------------------------------------------------------- modelo
    /** Controle redondo (A, B, C×4, START, MENU). */
    private class RoundControl(
        val id: Int,
        val label: String,
        val tintIdle: Int,
        val tintPress: Int,
        val labelScale: Float = 0.85f,
        val drawArrow: Boolean = false,
        val arrowAngleDeg: Float = 0f
    ) {
        var cx = 0f
        var cy = 0f
        var r = 0f
        var hitR = 0f
        var pointerId = -1
        var press = 0f // animação de press 0..1 (linear; easing aplicado no draw)
        var glass: Shader? = null
        var shadow: Shader? = null
        var glow: Shader? = null
        val pressed: Boolean get() = pointerId != -1

        fun layout(cx: Float, cy: Float, r: Float, unit: Float) {
            this.cx = cx
            this.cy = cy
            this.r = r
            hitR = max(r * HIT_SCALE, MIN_HIT_UNIT * unit)
        }

        fun hit(x: Float, y: Float): Boolean {
            val dx = x - cx
            val dy = y - cy
            return dx * dx + dy * dy <= hitR * hitR
        }

        /** true quando o dedo deslizou além da zona de histerese (soltar). */
        fun exitZone(x: Float, y: Float): Boolean {
            val rr = hitR * EXIT_HYSTERESIS
            val dx = x - cx
            val dy = y - cy
            return dx * dx + dy * dy > rr * rr
        }
    }

    /** Gatilho em pílula (L, Z, R) no topo da tela. */
    private class PillControl(
        val id: Int,
        val label: String,
        val tintIdle: Int,
        val tintPress: Int
    ) {
        val rect = RectF()
        val hitRect = RectF()
        val exitRect = RectF()
        var pointerId = -1
        var press = 0f
        var glass: Shader? = null
        var shadow: Shader? = null
        var shadowRect = RectF()
        val pressed: Boolean get() = pointerId != -1
        val radius: Float get() = rect.height() / 2f

        fun layout(l: Float, t: Float, r: Float, b: Float) {
            rect.set(l, t, r, b)
            val m = rect.height() * 0.18f
            hitRect.set(rect)
            hitRect.inset(-m, -m)
            exitRect.set(rect)
            exitRect.inset(-m * EXIT_HYSTERESIS, -m * EXIT_HYSTERESIS)
            shadowRect.set(rect)
            shadowRect.inset(-rect.height() * 0.14f, -rect.height() * 0.14f)
        }

        fun hit(x: Float, y: Float): Boolean = hitRect.contains(x, y)

        fun exitZone(x: Float, y: Float): Boolean = !exitRect.contains(x, y)
    }

    /** Analógico virtual (anel + knob que segue o dedo). */
    private class StickControl {
        var cx = 0f
        var cy = 0f
        var outerR = 0f
        var knobR = 0f
        var dx = 0f // vetor bruto do dedo, -1..1 (espaço de tela)
        var dy = 0f
        var sentX = 0f // último eixo efetivamente enviado ao nativo (dedup)
        var sentY = 0f
        var kx = 0f // knob renderizado (suavizado), -1..1
        var ky = 0f
        var pointerId = -1
        var press = 0f
        var glass: Shader? = null
        var shadow: Shader? = null
        var glow: Shader? = null
        var knobGrad: Shader? = null
        val active: Boolean get() = pointerId != -1
        val travel: Float get() = (outerR - knobR) * 0.92f

        fun layout(cx: Float, cy: Float, outerR: Float, knobR: Float) {
            this.cx = cx
            this.cy = cy
            this.outerR = outerR
            this.knobR = knobR
        }

        fun hit(x: Float, y: Float): Boolean {
            val rr = outerR * STICK_HIT_SCALE
            val dx = x - cx
            val dy = y - cy
            return dx * dx + dy * dy <= rr * rr
        }
    }

    private val btnA = RoundControl(BTN_A, "A", TINT_A, TINT_A)
    private val btnB = RoundControl(BTN_B, "B", TINT_B, TINT_B)
    private val btnCU = RoundControl(BTN_C_UP, "", TINT_C, TINT_C, drawArrow = true, arrowAngleDeg = -90f)
    private val btnCD = RoundControl(BTN_C_DOWN, "", TINT_C, TINT_C, drawArrow = true, arrowAngleDeg = 90f)
    private val btnCL = RoundControl(BTN_C_LEFT, "", TINT_C, TINT_C, drawArrow = true, arrowAngleDeg = 180f)
    private val btnCR = RoundControl(BTN_C_RIGHT, "", TINT_C, TINT_C, drawArrow = true, arrowAngleDeg = 0f)
    private val btnStart = RoundControl(BTN_START, "START", TINT_START, TINT_START, labelScale = 0.55f)
    private val btnMenu = RoundControl(BTN_MENU, "", TINT_NEUTRAL, ACCENT)
    private val roundButtons: Array<RoundControl> =
        arrayOf(btnA, btnB, btnCU, btnCD, btnCL, btnCR, btnStart, btnMenu)

    private val pillL = PillControl(BTN_L, "L", TINT_NEUTRAL, ACCENT)
    private val pillR = PillControl(BTN_R, "R", TINT_NEUTRAL, ACCENT)
    private val pillZ = PillControl(BTN_Z, "Z", TINT_NEUTRAL, ACCENT)
    // Arrays (não List): iteração sem alocar Iterator no hot path de draw/touch.
    private val pills: Array<PillControl> = arrayOf(pillL, pillZ, pillR)

    private val stick = StickControl()

    // ---------------------------------------------------------------- paints
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    /** Fonte dos labels: a mesma do jogo (assets do APK), com fallback seguro. */
    private val labelFont: Typeface = run {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Typeface.Builder(context.assets, "InterVariable.ttf").setWeight(700).build()
            } else {
                Typeface.createFromAsset(context.assets, "InterVariable.ttf")
            }
        }.getOrNull() ?: Typeface.create("sans-serif", Typeface.BOLD)
    }

    // ---------------------------------------------------------------- estado
    // Preferência do usuário (HUD visível) sobrevive à recriação da Activity.
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private var unit = 1f
    private var gameStarted = false
    private var padVisible = false
    private var hudAlpha = 0f          // fade do HUD (0..1)
    private var toggleFlash = 0f       // flash de ativação do botão de toggle
    private var togglePointerId = -1   // toggle aguarda o UP (cancelável)
    private var togglePress = 0f       // animação de press do toggle
    private var lastFrame = 0L

    // Haptic de setor do stick (com cooldown contra rajadas).
    private var lastStickTick = -1
    private var lastTickHapticAt = 0L

    // Insets de display cutout (API 28+): mantêm os controles fora da câmera.
    private var safeL = 0
    private var safeT = 0
    private var safeR = 0
    private var safeB = 0

    // Botão de alternar HUD (quina inferior direita) — geometria própria.
    private var toggleX = 0f
    private var toggleY = 0f
    private var toggleR = 0f
    private var toggleHitR = 0f
    private var toggleScrim: Shader? = null

    private val tmpPath = Path()

    init {
        instance = this
        // libmain.so já foi carregada pelo SDLActivity (super.onCreate) antes
        // desta view ser adicionada à hierarquia.
        runCatching { nativeInit() }
        // Auto-recuperação: se a Activity foi recriada com o jogo rodando, a
        // transição em notify_game_started não re-dispara; consultamos direto.
        // A preferência do usuário (HUD oculto) é respeitada.
        runCatching {
            if (nativeIsGameStarted()) {
                gameStarted = true
                padVisible = prefs.getBoolean(PREF_HUD_VISIBLE, true)
            }
        }
    }

    // ---------------------------------------------------------------- jni
    private external fun nativeInit()
    private external fun nativeButton(id: Int, pressed: Boolean)
    private external fun nativeAxis(x: Float, y: Float)
    private external fun nativeIsGameStarted(): Boolean

    // ---------------------------------------------------------------- estado
    private fun handleGameStarted(started: Boolean) {
        gameStarted = started
        // Auto-show com fade+deslize; a escolha de esconder (persistida) vence.
        padVisible = started && prefs.getBoolean(PREF_HUD_VISIBLE, true)
        if (!started) releaseAll()
        postInvalidateOnAnimation()
    }

    private fun setPadVisible(visible: Boolean) {
        if (padVisible == visible) return
        padVisible = visible
        prefs.edit().putBoolean(PREF_HUD_VISIBLE, visible).apply()
        if (!visible) releaseAll()
        postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        if (instance === this) instance = null
        releaseAll()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        // Cinto de segurança: alguns OEMs/fluxos não entregam ACTION_CANCEL ao
        // roubar o gesto — sem isto, botões ficariam presos durante a pausa.
        if (!hasWindowFocus) releaseAll()
    }

    // ---------------------------------------------------------------- layout
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyLayout()
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        // Captura o cutout (API 28+; minSdk 26 não tem cutout reportado) e
        // re-layouta. NÃO consome o insets: outros filhos podem precisar.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val cutout = insets.displayCutout
            if (cutout != null) {
                safeL = cutout.safeInsetLeft
                safeT = cutout.safeInsetTop
                safeR = cutout.safeInsetRight
                safeB = cutout.safeInsetBottom
                applyLayout()
            }
        }
        return insets
    }

    /** Recalcula geometria de todos os controles a partir da área útil. */
    private fun applyLayout() {
        val w = width
        val h = height
        if (w == 0 || h == 0) return

        val fw = w.toFloat()
        val fh = h.toFloat()
        val base = min(fh / 720f, fw / 1280f)
        // API 26/27 não reportam cutout: margem lateral extra para vidros curvos.
        val extraSide = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) 12f * base else 0f
        val margin = 6f * base
        val left = safeL + margin + extraSide
        val top = safeT + margin
        val right = fw - safeR - margin - extraSide
        val bottom = fh - safeB - margin
        val aw = right - left
        val ah = bottom - top
        if (aw <= 0f || ah <= 0f) return

        unit = min(ah / 720f, aw / 1280f)
        fun fx(f: Float) = left + aw * f
        fun fy(f: Float) = top + ah * f

        // ---- gatilhos (topo): Z (esq — pose do indicador esquerdo do N64,
        // desbloqueia Z+stick, Z+A e o ground pound) · L (centro, uso raro) · R (dir)
        val trigW = 185f * unit
        val trigH = 90f * unit
        val trigY = fy(0.04f)
        pillZ.layout(fx(0.035f), trigY, fx(0.035f) + trigW, trigY + trigH)
        pillL.layout(fx(0.5f) - trigW / 2f, trigY, fx(0.5f) + trigW / 2f, trigY + trigH)
        pillR.layout(fx(0.965f) - trigW, trigY, fx(0.965f), trigY + trigH)

        // ---- analógico (esquerda)
        stick.layout(fx(0.12f), fy(0.63f), 96f * unit, 47f * unit)

        // ---- START (base central) e MENU (dentro do arco do polegar direito)
        btnStart.layout(fx(0.475f), fy(0.87f), 34f * unit, unit)
        btnMenu.layout(fx(0.655f), fy(0.88f), 30f * unit, unit)

        // ---- losango de botões C (elíptico, junto do arco do polegar direito;
        // células de toque ~48dp em phones 20:9)
        val cCx = fx(0.76f)
        val cCy = fy(0.42f)
        val spreadX = 84f * unit
        val spreadY = 70f * unit
        val cR = 33f * unit
        btnCU.layout(cCx, cCy - spreadY, cR, unit)
        btnCD.layout(cCx, cCy + spreadY, cR, unit)
        btnCL.layout(cCx - spreadX, cCy, cR, unit)
        btnCR.layout(cCx + spreadX, cCy, cR, unit)

        // ---- A (principal) e B (adjacente, encadeamento A→B barato)
        btnA.layout(fx(0.88f), fy(0.58f), 52f * unit, unit)
        btnB.layout(fx(0.79f), fy(0.72f), 37f * unit, unit)

        // ---- botão de alternar HUD (quina inferior direita)
        toggleX = fx(0.94f)
        toggleY = fy(0.89f)
        toggleR = 24f * unit
        toggleHitR = max(toggleR * 1.5f, MIN_HIT_UNIT * unit)
        toggleScrim = RadialGradient(toggleX, toggleY, toggleR * 2.4f,
            intArrayOf(0x5A000000, Color.TRANSPARENT), SHADOW_STOPS, Shader.TileMode.CLAMP)

        rebuildShaders()
    }

    /** (Re)constrói os shaders cacheados — chamado só quando a geometria muda. */
    private fun rebuildShaders() {
        for (b in roundButtons) {
            b.glass = LinearGradient(b.cx, b.cy - b.r, b.cx, b.cy + b.r,
                intArrayOf(HILITE_TOP, HILITE_BOT), TWO_STOPS, Shader.TileMode.CLAMP)
            b.shadow = RadialGradient(b.cx, b.cy, b.r * 1.5f,
                intArrayOf(SHADOW_COLOR, Color.TRANSPARENT), SHADOW_STOPS, Shader.TileMode.CLAMP)
            b.glow = RadialGradient(b.cx, b.cy, b.r * 1.38f,
                intArrayOf(Color.TRANSPARENT, b.tintPress, Color.TRANSPARENT),
                GLOW_STOPS, Shader.TileMode.CLAMP)
        }
        for (p in pills) {
            val cxp = p.rect.centerX()
            val cyp = p.rect.centerY()
            p.glass = LinearGradient(cxp, p.rect.top, cxp, p.rect.bottom,
                intArrayOf(HILITE_TOP, HILITE_BOT), TWO_STOPS, Shader.TileMode.CLAMP)
            p.shadow = RadialGradient(cxp, cyp, max(p.rect.width(), p.rect.height()) * 0.72f,
                intArrayOf(SHADOW_COLOR, Color.TRANSPARENT), SHADOW_STOPS, Shader.TileMode.CLAMP)
        }
        stick.glass = LinearGradient(stick.cx, stick.cy - stick.outerR,
            stick.cx, stick.cy + stick.outerR,
            intArrayOf(HILITE_TOP, HILITE_BOT), TWO_STOPS, Shader.TileMode.CLAMP)
        stick.shadow = RadialGradient(stick.cx, stick.cy, stick.outerR * 1.45f,
            intArrayOf(SHADOW_COLOR, Color.TRANSPARENT), SHADOW_STOPS, Shader.TileMode.CLAMP)
        stick.glow = RadialGradient(stick.cx, stick.cy, stick.outerR * 1.3f,
            intArrayOf(Color.TRANSPARENT, ACCENT, Color.TRANSPARENT), GLOW_STOPS,
            Shader.TileMode.CLAMP)
        stick.knobGrad = RadialGradient(stick.cx, stick.cy, stick.knobR,
            intArrayOf(HILITE_TOP, HILITE_BOT), TWO_STOPS, Shader.TileMode.CLAMP)
    }

    // ---------------------------------------------------------------- desenho
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!gameStarted) return // launcher: overlay 100% pass-through

        val now = SystemClock.elapsedRealtime()
        val dt = if (lastFrame == 0L) 16f else (now - lastFrame).coerceIn(1L, 50L).toFloat()
        lastFrame = now
        val animating = advanceAnimations(dt)

        drawToggle(canvas)

        if (hudAlpha > 0.01f) {
            // fade + deslize: o HUD sobe ao entrar e desce ao sair
            canvas.save()
            canvas.translate(0f, (1f - hudAlpha) * 24f * unit)
            for (p in pills) drawPill(canvas, p)
            drawStick(canvas)
            for (b in roundButtons) drawRound(canvas, b)
            canvas.restore()
        }

        if (animating) postInvalidateOnAnimation()
    }

    private fun drawRound(canvas: Canvas, b: RoundControl) {
        val e = ease(b.press)
        val hud = hudAlpha
        val scale = 1f - 0.06f * e
        canvas.save()
        canvas.scale(scale, scale, b.cx, b.cy)

        // sombra suave (disco maior que o controle, desvanecendo)
        if (b.shadow != null) {
            fillPaint.shader = b.shadow
            fillPaint.alpha = (255 * hud).toInt()
            canvas.drawCircle(b.cx, b.cy, b.r * 1.5f, fillPaint)
            fillPaint.shader = null
        }

        // vidro: base escura + gradiente de brilho
        fillPaint.shader = null
        fillPaint.color = GLASS_RGB
        fillPaint.alpha = (GLASS_ALPHA * hud).toInt()
        canvas.drawCircle(b.cx, b.cy, b.r, fillPaint)
        if (b.glass != null) {
            fillPaint.shader = b.glass
            fillPaint.alpha = (255 * hud).toInt()
            canvas.drawCircle(b.cx, b.cy, b.r, fillPaint)
            fillPaint.shader = null
        }

        // press: clareia o vidro (botão "afunda" e acende)
        if (e > 0f) {
            fillPaint.shader = null
            fillPaint.color = Color.WHITE
            fillPaint.alpha = (0x26 * e * hud).toInt()
            canvas.drawCircle(b.cx, b.cy, b.r, fillPaint)
        }

        // glow de identidade (anel com gradiente radial)
        if (e > 0f && b.glow != null) {
            fillPaint.shader = b.glow
            fillPaint.alpha = (200 * e * hud).toInt()
            canvas.drawCircle(b.cx, b.cy, b.r * 1.38f, fillPaint)
            fillPaint.shader = null
        }

        // borda: tinta idle -> cor de identidade no press
        strokePaint.strokeWidth = 2.5f * unit
        strokePaint.color = lerpColor(b.tintIdle, b.tintPress, e)
        strokePaint.alpha = ((BORDER_ALPHA + (255 - BORDER_ALPHA) * e) * hud).toInt()
        canvas.drawCircle(b.cx, b.cy, b.r, strokePaint)

        // conteúdo: seta (C), ícone (MENU) ou label
        if (b.drawArrow) {
            fillPaint.shader = null
            fillPaint.color = lerpColor(b.tintIdle, Color.WHITE, e)
            fillPaint.alpha = (labelAlpha(e) * hud).toInt()
            drawArrow(canvas, b.cx, b.cy, b.r * 0.55f, b.arrowAngleDeg)
        } else if (b.id == BTN_MENU) {
            strokePaint.strokeWidth = b.r * 0.14f
            strokePaint.color = lerpColor(b.tintIdle, Color.WHITE, e)
            strokePaint.alpha = (labelAlpha(e) * hud).toInt()
            val w = b.r * 0.5f
            val gap = b.r * 0.30f
            for (i in -1..1) {
                canvas.drawLine(b.cx - w, b.cy + i * gap, b.cx + w, b.cy + i * gap, strokePaint)
            }
        } else if (b.label.isNotEmpty()) {
            textPaint.typeface = labelFont
            textPaint.isFakeBoldText = Build.VERSION.SDK_INT < Build.VERSION_CODES.P
            textPaint.textSize = b.r * b.labelScale
            textPaint.letterSpacing = if (b.id == BTN_START) 0.12f else 0f
            textPaint.color = lerpColor(b.tintIdle, Color.WHITE, e)
            textPaint.alpha = (labelAlpha(e) * hud).toInt()
            canvas.drawText(b.label, b.cx, b.cy + textPaint.textSize * 0.35f, textPaint)
        }

        canvas.restore()
        fillPaint.alpha = 255
        strokePaint.alpha = 255
        textPaint.alpha = 255
    }

    private fun drawPill(canvas: Canvas, p: PillControl) {
        val e = ease(p.press)
        val hud = hudAlpha
        val rad = p.radius
        val scale = 1f - 0.05f * e
        val cxp = p.rect.centerX()
        val cyp = p.rect.centerY()
        canvas.save()
        canvas.scale(scale, scale, cxp, cyp)

        // sombra suave (retângulo inflado com gradiente)
        if (p.shadow != null) {
            fillPaint.shader = p.shadow
            fillPaint.alpha = (255 * hud).toInt()
            canvas.drawRoundRect(p.shadowRect, p.shadowRect.height() / 2f,
                p.shadowRect.height() / 2f, fillPaint)
            fillPaint.shader = null
        }

        // vidro
        fillPaint.shader = null
        fillPaint.color = GLASS_RGB
        fillPaint.alpha = (GLASS_ALPHA * hud).toInt()
        canvas.drawRoundRect(p.rect, rad, rad, fillPaint)
        if (p.glass != null) {
            fillPaint.shader = p.glass
            fillPaint.alpha = (255 * hud).toInt()
            canvas.drawRoundRect(p.rect, rad, rad, fillPaint)
            fillPaint.shader = null
        }

        if (e > 0f) {
            fillPaint.shader = null
            fillPaint.color = Color.WHITE
            fillPaint.alpha = (0x26 * e * hud).toInt()
            canvas.drawRoundRect(p.rect, rad, rad, fillPaint)
        }

        strokePaint.strokeWidth = 2.5f * unit
        strokePaint.color = lerpColor(p.tintIdle, p.tintPress, e)
        strokePaint.alpha = ((BORDER_ALPHA + (255 - BORDER_ALPHA) * e) * hud).toInt()
        canvas.drawRoundRect(p.rect, rad, rad, strokePaint)

        textPaint.typeface = labelFont
        textPaint.isFakeBoldText = Build.VERSION.SDK_INT < Build.VERSION_CODES.P
        textPaint.letterSpacing = 0f
        textPaint.textSize = p.rect.height() * 0.40f
        textPaint.color = lerpColor(p.tintIdle, Color.WHITE, e)
        textPaint.alpha = (labelAlpha(e) * hud).toInt()
        canvas.drawText(p.label, cxp, cyp + textPaint.textSize * 0.35f, textPaint)

        canvas.restore()
        fillPaint.alpha = 255
        strokePaint.alpha = 255
        textPaint.alpha = 255
    }

    private fun drawStick(canvas: Canvas) {
        val s = stick
        val e = ease(s.press)
        val hud = hudAlpha

        // sombra do anel
        if (s.shadow != null) {
            fillPaint.shader = s.shadow
            fillPaint.alpha = (255 * hud).toInt()
            canvas.drawCircle(s.cx, s.cy, s.outerR * 1.45f, fillPaint)
            fillPaint.shader = null
        }

        // anel externo de vidro
        fillPaint.shader = null
        fillPaint.color = GLASS_RGB
        fillPaint.alpha = (GLASS_ALPHA * hud).toInt()
        canvas.drawCircle(s.cx, s.cy, s.outerR, fillPaint)
        if (s.glass != null) {
            fillPaint.shader = s.glass
            fillPaint.alpha = (255 * hud).toInt()
            canvas.drawCircle(s.cx, s.cy, s.outerR, fillPaint)
            fillPaint.shader = null
        }

        // ticks de direção (8 pontos) — o tick da direção atual acende
        // (só fora da zona morta: o feedback visual mente nunca)
        val tickIn = s.outerR * 0.76f
        val tickOut = s.outerR * 0.88f
        val activeTick = if (s.active && hypot(s.dx, s.dy) > STICK_DEADZONE) {
            nearestTickIndex(s.dx, s.dy)
        } else {
            -1
        }
        strokePaint.strokeWidth = 2f * unit
        for (i in 0 until 8) {
            val ang = (i * 45f) * (Math.PI.toFloat() / 180f)
            val ca = cos(ang)
            val sa = sin(ang)
            val on = i == activeTick
            strokePaint.color = if (on) ACCENT else Color.WHITE
            strokePaint.alpha = ((if (on) 220 else 70) * hud).toInt()
            canvas.drawLine(s.cx + ca * tickIn, s.cy + sa * tickIn,
                s.cx + ca * tickOut, s.cy + sa * tickOut, strokePaint)
        }

        // borda do anel + glow quando ativo
        strokePaint.strokeWidth = 2.5f * unit
        strokePaint.color = lerpColor(TINT_NEUTRAL, ACCENT, e)
        strokePaint.alpha = ((BORDER_ALPHA + (255 - BORDER_ALPHA) * e) * hud).toInt()
        canvas.drawCircle(s.cx, s.cy, s.outerR, strokePaint)
        if (e > 0f && s.glow != null) {
            fillPaint.shader = s.glow
            fillPaint.alpha = (160 * e * hud).toInt()
            canvas.drawCircle(s.cx, s.cy, s.outerR * 1.3f, fillPaint)
            fillPaint.shader = null
        }

        // knob (segue o dedo com suavização visual; o input nativo é bruto)
        val kx = s.cx + s.kx * s.travel
        val ky = s.cy + s.ky * s.travel
        fillPaint.shader = null
        fillPaint.color = Color.BLACK
        fillPaint.alpha = (45 * hud).toInt()
        canvas.drawCircle(kx, ky, s.knobR * 1.18f, fillPaint)

        canvas.save()
        canvas.translate(kx - s.cx, ky - s.cy)
        fillPaint.shader = null
        fillPaint.color = GLASS_RGB
        fillPaint.alpha = (220 * hud).toInt()
        canvas.drawCircle(s.cx, s.cy, s.knobR, fillPaint)
        if (s.knobGrad != null) {
            fillPaint.shader = s.knobGrad
            fillPaint.alpha = (255 * hud).toInt()
            canvas.drawCircle(s.cx, s.cy, s.knobR, fillPaint)
            fillPaint.shader = null
        }
        // grip interno
        strokePaint.strokeWidth = 2f * unit
        strokePaint.color = Color.WHITE
        strokePaint.alpha = (70 * hud).toInt()
        canvas.drawCircle(s.cx, s.cy, s.knobR * 0.66f, strokePaint)
        // borda do knob
        strokePaint.strokeWidth = 2.5f * unit
        strokePaint.color = lerpColor(TINT_NEUTRAL, ACCENT, e)
        strokePaint.alpha = ((BORDER_ALPHA + (255 - BORDER_ALPHA) * e) * hud).toInt()
        canvas.drawCircle(s.cx, s.cy, s.knobR, strokePaint)
        canvas.restore()

        fillPaint.alpha = 255
        strokePaint.alpha = 255
    }

    /** Botão de mostrar/esconder o HUD — sempre presente com o jogo rodando. */
    private fun drawToggle(canvas: Canvas) {
        val hud = hudAlpha
        val e = ease(togglePress)
        // Piso mais alto que o resto do HUD: é a única porta de volta do pad.
        val alpha = ((0.58f + 0.27f * (1f - hud)) * 255f).toInt()
        val scale = 1f - 0.06f * e

        canvas.save()
        canvas.scale(scale, scale, toggleX, toggleY)

        // scrim radial discreto: garante contraste sobre cenas claras
        if (toggleScrim != null) {
            fillPaint.shader = toggleScrim
            fillPaint.alpha = 255
            canvas.drawCircle(toggleX, toggleY, toggleR * 2.4f, fillPaint)
            fillPaint.shader = null
        }

        // vidro + borda
        fillPaint.shader = null
        fillPaint.color = GLASS_RGB
        fillPaint.alpha = (GLASS_ALPHA * alpha / 255).toInt()
        canvas.drawCircle(toggleX, toggleY, toggleR, fillPaint)
        strokePaint.strokeWidth = 2f * unit
        strokePaint.color = ACCENT
        strokePaint.alpha = (alpha * min(1f, toggleFlash + 0.35f)).toInt()
        canvas.drawCircle(toggleX, toggleY, toggleR, strokePaint)

        // glyph: mini gamepad
        val w = toggleR * 1.05f
        val h = toggleR * 0.62f
        strokePaint.color = Color.WHITE
        strokePaint.alpha = (alpha * 0.9f).toInt()
        strokePaint.strokeWidth = 2f * unit
        canvas.drawRoundRect(toggleX - w, toggleY - h, toggleX + w, toggleY + h, h, h, strokePaint)
        fillPaint.shader = null
        fillPaint.color = Color.WHITE
        fillPaint.alpha = (alpha * 0.9f).toInt()
        canvas.drawCircle(toggleX - w * 0.42f, toggleY, toggleR * 0.10f, fillPaint)
        canvas.drawCircle(toggleX + w * 0.42f, toggleY, toggleR * 0.10f, fillPaint)

        // badge ✕ quando o HUD está visível
        if (padVisible) {
            val bx = toggleX + toggleR * 0.72f
            val by = toggleY - toggleR * 0.72f
            val br = toggleR * 0.42f
            fillPaint.shader = null
            fillPaint.color = GLASS_RGB
            fillPaint.alpha = (235 * alpha / 255).toInt()
            canvas.drawCircle(bx, by, br, fillPaint)
            strokePaint.strokeWidth = 1.5f * unit
            strokePaint.color = Color.WHITE
            strokePaint.alpha = (alpha * 0.95f).toInt()
            canvas.drawCircle(bx, by, br, strokePaint)
            val k = br * 0.42f
            canvas.drawLine(bx - k, by - k, bx + k, by + k, strokePaint)
            canvas.drawLine(bx - k, by + k, bx + k, by - k, strokePaint)
        }

        canvas.restore()
        fillPaint.alpha = 255
        strokePaint.alpha = 255
    }

    /** Setinha triangular apontando para fora (botões C). */
    private fun drawArrow(canvas: Canvas, x: Float, y: Float, size: Float, angleDeg: Float) {
        val ang = Math.toRadians(angleDeg.toDouble()).toFloat()
        val tipX = x + size * cos(ang)
        val tipY = y + size * sin(ang)
        val a1 = ang + 2.5f
        val a2 = ang - 2.5f
        tmpPath.reset()
        tmpPath.moveTo(tipX, tipY)
        tmpPath.lineTo(x + size * 0.45f * cos(a1), y + size * 0.45f * sin(a1))
        tmpPath.lineTo(x + size * 0.45f * cos(a2), y + size * 0.45f * sin(a2))
        tmpPath.close()
        canvas.drawPath(tmpPath, fillPaint)
    }

    // -------------------------------------------------------------- animação
    /** Avança uma animação de press (0..1). Método puro: sem captura/alocação. */
    private fun stepPress(press: Float, target: Float, dt: Float): Float {
        if (abs(target - press) <= 0.001f) return target
        val rate = if (target > press) dt / PRESS_IN_MS else dt / PRESS_OUT_MS
        return if (target > press) min(target, press + rate) else max(target, press - rate)
    }

    /** Avança todas as animações; true se ainda há movimento (continuar render). */
    private fun advanceAnimations(dt: Float): Boolean {
        var animating = false

        for (b in roundButtons) {
            val next = stepPress(b.press, if (b.pressed) 1f else 0f, dt)
            if (next != b.press) { b.press = next; animating = true }
        }
        for (p in pills) {
            val next = stepPress(p.press, if (p.pressed) 1f else 0f, dt)
            if (next != p.press) { p.press = next; animating = true }
        }
        run {
            val next = stepPress(stick.press, if (stick.active) 1f else 0f, dt)
            if (next != stick.press) { stick.press = next; animating = true }
        }
        run {
            val next = stepPress(togglePress, if (togglePointerId != -1) 1f else 0f, dt)
            if (next != togglePress) { togglePress = next; animating = true }
        }

        // knob do stick volta suavemente (decaimento exponencial)
        val k = 1f - exp(-dt / KNOB_RETURN_TAU)
        val nkx = stick.kx + (stick.dx - stick.kx) * k
        val nky = stick.ky + (stick.dy - stick.ky) * k
        if (abs(nkx - stick.kx) > 0.0005f || abs(nky - stick.ky) > 0.0005f) {
            stick.kx = nkx
            stick.ky = nky
            animating = true
        }

        // fade do HUD (com snap no alvo — termina exato, sem resíduo)
        val hudTarget = if (padVisible) 1f else 0f
        if (hudAlpha != hudTarget) {
            val step = dt / HUD_FADE_MS
            val next = if (hudAlpha < hudTarget) min(hudTarget, hudAlpha + step)
                       else max(hudTarget, hudAlpha - step)
            hudAlpha = if (abs(hudTarget - next) <= 0.001f) hudTarget else next
            animating = true
        }

        // flash do toggle
        if (toggleFlash > 0f) {
            toggleFlash = max(0f, toggleFlash - dt / 160f)
            animating = true
        }

        return animating
    }

    /** Easing suave (smoothstep) aplicado ao progresso de press. */
    private fun ease(t: Float): Float = t * t * (3f - 2f * t)

    private fun labelAlpha(e: Float): Int =
        (LABEL_ALPHA + (255 - LABEL_ALPHA) * e).toInt()

    private fun lerpColor(c1: Int, c2: Int, t: Float): Int {
        val r = (Color.red(c1) + (Color.red(c2) - Color.red(c1)) * t).toInt()
        val g = (Color.green(c1) + (Color.green(c2) - Color.green(c1)) * t).toInt()
        val b = (Color.blue(c1) + (Color.blue(c2) - Color.blue(c1)) * t).toInt()
        return Color.rgb(r, g, b)
    }

    /** Índice do tick (0..7) mais próximo da direção atual do stick. */
    private fun nearestTickIndex(dx: Float, dy: Float): Int {
        if (dx == 0f && dy == 0f) return -1
        val ang = atan2(dy, dx)
        val idx = Math.round(ang / (Math.PI.toFloat() / 4f))
        return ((idx % 8) + 8) % 8
    }

    // ---------------------------------------------------------------- toque
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!gameStarted) return false // launcher: deixa o SDL tratar (mouse/touch)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                val pid = event.getPointerId(index)
                if (claim(pid, event.getX(index), event.getY(index))) {
                    // Menor latência possível para o gesto que acabou de começar.
                    requestUnbufferedDispatch(event)
                    postInvalidateOnAnimation()
                    return true
                }
                // toque em área livre: ACTION_DOWN não é consumido e cai para o
                // SDL (navega menus por toque mesmo com o HUD visível).
                if (event.actionMasked == MotionEvent.ACTION_DOWN) return false
                postInvalidateOnAnimation()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                var dirty = false
                for (i in 0 until event.pointerCount) {
                    val pid = event.getPointerId(i)
                    val x = event.getX(i)
                    val y = event.getY(i)
                    when {
                        togglePointerId == pid -> {
                            // A decisão é no UP; deslizar para fora cancela.
                            if (!toggleHit(x, y)) togglePointerId = -1
                            dirty = true
                        }
                        stick.pointerId == pid -> {
                            // Stick nunca solta por deslize: só UP/CANCEL.
                            updateStick(x, y)
                            dirty = true
                        }
                        else -> {
                            // Botões: histerese de saída mata "ghost press".
                            for (b in roundButtons) {
                                if (b.pointerId == pid && b.exitZone(x, y)) {
                                    b.pointerId = -1
                                    nativeButton(b.id, false)
                                    dirty = true
                                }
                            }
                            for (p in pills) {
                                if (p.pointerId == pid && p.exitZone(x, y)) {
                                    p.pointerId = -1
                                    nativeButton(p.id, false)
                                    dirty = true
                                }
                            }
                        }
                    }
                }
                if (dirty) postInvalidateOnAnimation()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                releasePointer(event.getPointerId(event.actionIndex))
                postInvalidateOnAnimation()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                releaseAll()
                postInvalidateOnAnimation()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /**
     * Tenta atribuir o ponteiro ao controle mais próximo (prioridade por
     * proximidade entre hit-areas sobrepostas). Retorna true se consumiu.
     */
    private fun claim(pid: Int, x: Float, y: Float): Boolean {
        if (togglePointerId == -1 && toggleHit(x, y)) {
            // Decide no UP (cancelável deslizando para fora) — mesmo contrato
            // de press dos demais controles; feedback vem no releasePointer.
            togglePointerId = pid
            return true
        }
        if (!padVisible) return false

        var bestKind = 0
        var bestDist = Float.MAX_VALUE
        var bestRound: RoundControl? = null
        var bestPill: PillControl? = null

        if (!stick.active && stick.hit(x, y)) {
            val d = dist2(x, y, stick.cx, stick.cy)
            if (d < bestDist) { bestDist = d; bestKind = 1 }
        }
        for (b in roundButtons) {
            if (!b.pressed && b.hit(x, y)) {
                val d = dist2(x, y, b.cx, b.cy)
                if (d < bestDist) { bestDist = d; bestKind = 2; bestRound = b }
            }
        }
        for (p in pills) {
            if (!p.pressed && p.hit(x, y)) {
                val d = dist2(x, y, p.rect.centerX(), p.rect.centerY())
                if (d < bestDist) { bestDist = d; bestKind = 3; bestPill = p }
            }
        }

        when (bestKind) {
            1 -> {
                stick.pointerId = pid
                updateStick(x, y)
                haptic(HapticFeedbackConstants.CLOCK_TICK)
            }
            2 -> {
                val b = bestRound!!
                b.pointerId = pid
                nativeButton(b.id, true)
                haptic(HapticFeedbackConstants.VIRTUAL_KEY)
            }
            3 -> {
                val p = bestPill!!
                p.pointerId = pid
                nativeButton(p.id, true)
                haptic(HapticFeedbackConstants.VIRTUAL_KEY)
            }
            else -> return false
        }
        return true
    }

    /** Solta o controle atualmente dono do ponteiro informado (UP/POINTER_UP). */
    private fun releasePointer(pid: Int) {
        if (togglePointerId == pid) {
            // Toque confirmado dentro do toggle: alterna o HUD com feedback.
            togglePointerId = -1
            toggleFlash = 1f
            haptic(HapticFeedbackConstants.VIRTUAL_KEY)
            setPadVisible(!padVisible)
            return
        }
        if (stick.pointerId == pid) {
            val wasMoving = stick.sentX != 0f || stick.sentY != 0f
            stick.pointerId = -1
            stick.dx = 0f
            stick.dy = 0f
            stick.sentX = 0f
            stick.sentY = 0f
            lastStickTick = -1
            if (wasMoving) nativeAxis(0f, 0f) // evita release duplicado
            return
        }
        for (b in roundButtons) {
            if (b.pointerId == pid) {
                b.pointerId = -1
                nativeButton(b.id, false)
                return
            }
        }
        for (p in pills) {
            if (p.pointerId == pid) {
                p.pointerId = -1
                nativeButton(p.id, false)
                return
            }
        }
    }

    /** Solta tudo (esconder HUD, fim do jogo, foco perdido, cancelamento). */
    private fun releaseAll() {
        for (b in roundButtons) {
            if (b.pressed) nativeButton(b.id, false)
        }
        for (p in pills) {
            if (p.pressed) nativeButton(p.id, false)
        }
        if (stick.sentX != 0f || stick.sentY != 0f) nativeAxis(0f, 0f)
        stick.pointerId = -1
        stick.dx = 0f
        stick.dy = 0f
        stick.sentX = 0f
        stick.sentY = 0f
        lastStickTick = -1
        for (b in roundButtons) b.pointerId = -1
        for (p in pills) p.pointerId = -1
        togglePointerId = -1
    }

    /**
     * Vetor do stick: bruto (clamp ao círculo unitário) para o knob; para o
     * N64 aplica +y para cima, zona morta radial COM reescala ((v-dz)/(1-dz))
     * e envia ao nativo a cada movimento.
     */
    private fun updateStick(x: Float, y: Float) {
        val s = stick
        if (s.outerR <= 0f) return // sem layout ainda: evita NaN
        var ddx = (x - s.cx) / s.outerR
        var ddy = (y - s.cy) / s.outerR
        val mag = hypot(ddx, ddy)
        if (mag > 1f) {
            ddx /= mag
            ddy /= mag
        }
        s.dx = ddx
        s.dy = ddy

        var ax = ddx
        var ay = -ddy
        val aMag = hypot(ax, ay)
        if (aMag <= STICK_DEADZONE) {
            ax = 0f
            ay = 0f
            lastStickTick = -1
        } else {
            val scaled = min((aMag - STICK_DEADZONE) / (1f - STICK_DEADZONE), 1f)
            ax = ax / aMag * scaled
            ay = ay / aMag * scaled
            stickTickHaptic(nearestTickIndex(ddx, ddy))
        }
        nativeAxis(ax, ay)
        s.sentX = ax
        s.sentY = ay
    }

    /** CLOCK_TICK ao cruzar setores do stick, com cooldown curto. */
    private fun stickTickHaptic(idx: Int) {
        if (idx == -1 || idx == lastStickTick) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastTickHapticAt < 60L) return
        lastTickHapticAt = now
        lastStickTick = idx
        haptic(HapticFeedbackConstants.CLOCK_TICK)
    }

    private fun toggleHit(x: Float, y: Float): Boolean {
        val dx = x - toggleX
        val dy = y - toggleY
        return dx * dx + dy * dy <= toggleHitR * toggleHitR
    }

    private fun dist2(x: Float, y: Float, cx: Float, cy: Float): Float {
        val dx = x - cx
        val dy = y - cy
        return dx * dx + dy * dy
    }

    // --------------------------------------------------------------- haptics
    /** Respeita a preferência global de feedback de toque (acessibilidade). */
    private fun haptic(constant: Int) {
        performHapticFeedback(constant)
    }
}
