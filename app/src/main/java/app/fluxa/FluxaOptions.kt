package app.fluxa

import android.content.Intent

data class FluxaOptions(
    val enabled: Boolean = false,
    val multiplier: Int = DEFAULT_MULTIPLIER,
    /** DIS optical-flow resolution: short side in px. */
    val flowSide: Int = DEFAULT_FLOW_SIDE,
    val driverName: String? = null,
    val presentMode: Int = PRESENT_FIFO,
) {
    val usable: Boolean get() = enabled

    fun writeTo(intent: Intent): Intent {
        intent.putExtra(EXTRA_ENABLED, enabled)
        intent.putExtra(EXTRA_MULTIPLIER, multiplier)
        intent.putExtra(EXTRA_FLOW_SIDE, flowSide)
        intent.putExtra(EXTRA_DRIVER, driverName)
        intent.putExtra(EXTRA_PRESENT_MODE, presentMode)
        return intent
    }

    companion object {
        const val EXTRA_ENABLED = "fluxa"
        const val EXTRA_MULTIPLIER = "fluxa_multiplier"
        const val EXTRA_FLOW_SIDE = "fluxa_flow_side"
        const val EXTRA_DRIVER = "fluxa_driver"
        const val EXTRA_PRESENT_MODE = "fluxa_present_mode"

        const val PRESENT_FIFO = 0
        const val PRESENT_MAILBOX = 1

        const val DEFAULT_MULTIPLIER = 2
        const val DEFAULT_FLOW_SIDE = 270
        /** Slider position 1 = "OFF": frames pass through with no generation. */
        const val MIN_MULTIPLIER = 1
        const val MAX_MULTIPLIER = 10
        const val MIN_FLOW_SIDE = 64
        const val MAX_FLOW_SIDE = 720

        /** Slider step for the optical-flow resolution (px). */
        const val FLOW_SIDE_STEP = 2

        fun multiplierLabel(value: Int): String = if (value <= 1) "OFF" else "${value}×"

        fun clampMultiplier(value: Int): Int = value.coerceIn(MIN_MULTIPLIER, MAX_MULTIPLIER)

        fun clampPresentMode(value: Int): Int =
            if (value == PRESENT_MAILBOX) PRESENT_MAILBOX else PRESENT_FIFO

        fun clampFlowSide(value: Int): Int = value.coerceIn(MIN_FLOW_SIDE, MAX_FLOW_SIDE)

        fun fromIntent(intent: Intent?): FluxaOptions {
            if (intent == null || !intent.getBooleanExtra(EXTRA_ENABLED, false)) {
                return FluxaOptions()
            }
            return FluxaOptions(
                enabled = true,
                multiplier = clampMultiplier(intent.getIntExtra(EXTRA_MULTIPLIER, DEFAULT_MULTIPLIER)),
                flowSide = clampFlowSide(intent.getIntExtra(EXTRA_FLOW_SIDE, DEFAULT_FLOW_SIDE)),
                driverName = intent.getStringExtra(EXTRA_DRIVER),
                presentMode = clampPresentMode(intent.getIntExtra(EXTRA_PRESENT_MODE, PRESENT_FIFO)),
            )
        }
    }
}
