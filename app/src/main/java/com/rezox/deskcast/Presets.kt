package com.rezox.deskcast

import android.view.KeyEvent.*

/**
 * Ready-made key layouts so games work the moment they open. Button positions match each game's
 * default on-screen layout as closely as possible; press F10 in the game to drag them onto
 * your own HUD if you moved buttons in the game's settings.
 */
object Presets {

    private fun tap(key: Int, x: Float, y: Float) = KeyBinding(BindType.TAP, key, x, y)
    private fun swipe(key: Int, x: Float, y: Float, dx: Float, dy: Float) = KeyBinding(BindType.SWIPE, key, x, y, dx = dx, dy = dy)
    private fun wasd(x: Float, y: Float, r: Float) = KeyBinding(BindType.JOYSTICK, 0, x, y, r, KEYCODE_W, KEYCODE_S, KEYCODE_A, KEYCODE_D)
    private fun arrowsStick(x: Float, y: Float, r: Float) =
        KeyBinding(BindType.JOYSTICK, 0, x, y, r, KEYCODE_DPAD_UP, KEYCODE_DPAD_DOWN, KEYCODE_DPAD_LEFT, KEYCODE_DPAD_RIGHT)
    private fun aim(x: Float, y: Float, range: Float, sens: Float = 1f) = KeyBinding(BindType.AIM, KEYCODE_GRAVE, x, y, range, dx = sens)

    /** Runner games (Subway Surfers, Temple Run…): arrows or WASD swipe. */
    fun runner(): List<KeyBinding> {
        val cx = 0.5f; val cy = 0.58f
        return listOf(
            swipe(KEYCODE_DPAD_UP, cx, cy, 0f, -0.25f), swipe(KEYCODE_W, cx, cy, 0f, -0.25f),
            swipe(KEYCODE_DPAD_DOWN, cx, cy, 0f, 0.25f), swipe(KEYCODE_S, cx, cy, 0f, 0.25f),
            swipe(KEYCODE_DPAD_LEFT, cx, cy, -0.2f, 0f), swipe(KEYCODE_A, cx, cy, -0.2f, 0f),
            swipe(KEYCODE_DPAD_RIGHT, cx, cy, 0.2f, 0f), swipe(KEYCODE_D, cx, cy, 0.2f, 0f),
            tap(KEYCODE_SPACE, cx, cy),
        )
    }

    /** Free Fire / Free Fire MAX, default HUD (landscape). */
    fun freeFire(): List<KeyBinding> = listOf(
        wasd(0.145f, 0.72f, 0.07f),
        tap(Keys.MOUSE_LEFT, 0.865f, 0.62f),   // fire
        tap(Keys.MOUSE_RIGHT, 0.80f, 0.43f),   // scope
        tap(KEYCODE_SPACE, 0.935f, 0.50f),     // jump
        tap(KEYCODE_C, 0.885f, 0.86f),         // crouch
        tap(KEYCODE_Z, 0.95f, 0.76f),          // prone
        tap(KEYCODE_R, 0.775f, 0.70f),         // reload
        tap(KEYCODE_1, 0.44f, 0.90f),          // weapon 1
        tap(KEYCODE_2, 0.56f, 0.90f),          // weapon 2
        tap(KEYCODE_G, 0.665f, 0.88f),         // grenade / gloo wall
        tap(KEYCODE_H, 0.37f, 0.80f),          // medkit
        tap(KEYCODE_F, 0.63f, 0.56f),          // pick up / interact
        tap(KEYCODE_M, 0.92f, 0.13f),          // map
        tap(KEYCODE_TAB, 0.06f, 0.25f),        // bag
        aim(0.62f, 0.38f, 0.22f),              // ` or middle click: mouse aim on/off
    )

    /** Battle-royale shooters with a similar default layout (PUBG, CODM, BGMI). */
    fun shooter(): List<KeyBinding> = listOf(
        wasd(0.15f, 0.70f, 0.075f),
        tap(Keys.MOUSE_LEFT, 0.88f, 0.60f),
        tap(Keys.MOUSE_RIGHT, 0.93f, 0.42f),
        tap(KEYCODE_SPACE, 0.94f, 0.72f),
        tap(KEYCODE_C, 0.88f, 0.88f),
        tap(KEYCODE_Z, 0.95f, 0.88f),
        tap(KEYCODE_R, 0.78f, 0.86f),
        tap(KEYCODE_1, 0.42f, 0.90f),
        tap(KEYCODE_2, 0.58f, 0.90f),
        tap(KEYCODE_G, 0.70f, 0.90f),
        tap(KEYCODE_F, 0.62f, 0.55f),
        tap(KEYCODE_M, 0.92f, 0.12f),
        tap(KEYCODE_TAB, 0.06f, 0.88f),
        aim(0.62f, 0.38f, 0.22f),
    )

    /** Any other game: WASD moves the left joystick, arrows swipe, Space taps the main action button. */
    fun anyGame(): List<KeyBinding> {
        val cx = 0.5f; val cy = 0.55f
        return listOf(
            wasd(0.15f, 0.72f, 0.075f),
            swipe(KEYCODE_DPAD_UP, cx, cy, 0f, -0.25f),
            swipe(KEYCODE_DPAD_DOWN, cx, cy, 0f, 0.25f),
            swipe(KEYCODE_DPAD_LEFT, cx, cy, -0.2f, 0f),
            swipe(KEYCODE_DPAD_RIGHT, cx, cy, 0.2f, 0f),
            tap(KEYCODE_SPACE, 0.87f, 0.70f),
        )
    }

    private val runners = setOf(
        "com.kiloo.subwaysurf", "com.imangi.templerun2", "com.imangi.templerun", "com.sybogames.subway.surfers.game",
        "com.fingersoft.hillclimb", "com.halfbrick.fruitninjafree", "com.outfit7.talkingtomgoldrun",
    )
    private val freeFire = setOf("com.dts.freefireth", "com.dts.freefiremax")
    private val shooters = setOf(
        "com.tencent.ig", "com.pubg.imobile", "com.pubg.krmobile", "com.activision.callofduty.shooter", "com.garena.game.codm",
    )

    fun forPackage(pkg: String): List<KeyBinding>? = when (pkg) {
        in freeFire -> freeFire()
        in runners -> runner()
        in shooters -> shooter()
        else -> null
    }

    /** Names shown in the editor's "Layouts" picker. */
    val all: List<Pair<String, () -> List<KeyBinding>>> = listOf(
        "Any game (WASD + arrows)" to { anyGame() },
        "Runner (Subway Surfers style)" to { runner() },
        "Free Fire" to { freeFire() },
        "Shooter (PUBG / CODM style)" to { shooter() },
        "Movement only: arrow keys joystick" to { listOf(arrowsStick(0.15f, 0.72f, 0.075f)) },
    )
}
