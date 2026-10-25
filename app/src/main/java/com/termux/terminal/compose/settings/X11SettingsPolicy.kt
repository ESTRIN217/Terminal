package com.termux.terminal.compose.settings

/**
 * Pure decisions for the X11 settings screen (spec 017, RF-4, RF-5, RF-6,
 * RF-7, RF-9). No Android dependencies: everything here runs under plain JUnit.
 *
 * The raw values and defaults mirrored below are the source of truth of
 * lorie's `termux-x11/lorie/src/main/res/xml/preferences.xml` and
 * `values/arrays.xml`. Keep them in sync if the lorie submodule changes the
 * shared arrays (the resolution list is deliberately NOT mirrored: it is read
 * at runtime from [com.termux.x11.Prefs]).
 */
object X11SettingsPolicy {

    // Output: displayScale bounds and behaviour (preferences.xml SeekBarPreference).
    const val SCALE_MIN = 30
    const val SCALE_MAX = 300
    const val SCALE_STEP = 10
    const val DEFAULT_SCALE = 100

    // Output: displayResolutionMode (arrays.xml displayResolutionVariants).
    val OUTPUT_MODES: List<String> = listOf("native", "scaled", "exact", "custom")
    const val DEFAULT_OUTPUT_MODE = "native"

    // Output: displayFilteringMode (arrays.xml displayFilteringVariants).
    val FILTERING_MODES: List<String> = listOf("bilinear", "nearest")
    const val DEFAULT_FILTERING_MODE = "bilinear"

    // Output: forceOrientation (arrays.xml forceOrientationVariants, 5 values).
    val ORIENTATION_MODES: List<String> = listOf(
        "auto",
        "portrait",
        "landscape",
        "reverse portrait",
        "reverse landscape"
    )
    const val DEFAULT_ORIENTATION = "auto"

    // Pointer: touchMode (arrays.xml touchscreenInputModesValues).
    val TOUCH_MODES: List<String> = listOf("1", "2", "3")
    const val DEFAULT_TOUCH_MODE = "1"

    /** Result of validating a custom `WxH` resolution. */
    sealed interface X11ResolutionValidation {
        /** The input is well formed; [resolution] is the normalized `WxH` text. */
        data class Valid(val resolution: String) : X11ResolutionValidation

        /** The input must not be persisted; the current value is kept. */
        data object Invalid : X11ResolutionValidation
    }

    private val RESOLUTION_PATTERN = Regex("^([0-9]{1,5})x([0-9]{1,5})$")

    /**
     * Validates a custom resolution in `WxH` form (RF-7). Surrounding
     * whitespace is trimmed; any other deviation (empty, spaces inside a
     * dimension, missing part, zero dimension or non numeric) is rejected.
     * No upper bound is imposed: single dimensions grow as the user chooses.
     *
     * @param raw the raw text typed by the user (may be untrimmed)
     * @return [X11ResolutionValidation.Valid] carrying the normalized text, or
     * [X11SettingsPolicy.X11ResolutionValidation.Invalid] to keep the current
     * value without persisting anything
     */
    fun validateCustomResolution(raw: String): X11ResolutionValidation {
        val match = RESOLUTION_PATTERN.matchEntire(raw.trim())
            ?: return X11ResolutionValidation.Invalid
        val width = match.groupValues[1].toInt()
        val height = match.groupValues[2].toInt()
        if (width == 0 || height == 0) return X11ResolutionValidation.Invalid
        return X11ResolutionValidation.Valid("${width}x${height}")
    }

    /**
     * Sanitizes the display scale (RF-5, RF-9): clamps to [SCALE_MIN]..
     * [SCALE_MAX] and rounds to the nearest multiple of [SCALE_STEP], so only
     * meaningful multiples ever reach the preference store.
     *
     * @param raw the raw scale value being applied
     * @return the sanitized scale, always inside [SCALE_MIN]..[SCALE_MAX]
     */
    fun sanitizeScale(raw: Int): Int {
        val clamped = raw.coerceIn(SCALE_MIN, SCALE_MAX)
        val rounded = ((clamped + SCALE_STEP / 2) / SCALE_STEP) * SCALE_STEP
        return rounded.coerceIn(SCALE_MIN, SCALE_MAX)
    }

    /**
     * Places a persisted list value back on track (RF-9): null, blank or
     * unknown values fall back to the default, known values pass through.
     *
     * @param raw the persisted value, may be null
     * @param values the valid raw values of the list
     * @param default the default raw value (first entry of the source array)
     * @return the sanitized raw value to show and use
     */
    fun sanitizeListValue(raw: String?, values: List<String>, default: String): String {
        return if (raw.isNullOrBlank() || !values.contains(raw)) default else raw
    }

    /**
     * Resolves the selected option index for a dialog list (RF-6). Unknown
     * values map onto index 0, which is the default position of every core
     * list.
     *
     * @param values the raw values backing the dialog options
     * @param current the sanitized current raw value
     * @return the selected index inside [values]
     */
    fun selectedIndexFor(values: List<String>, current: String): Int {
        return values.indexOf(current).coerceAtLeast(0)
    }

    /**
     * Whether the exact-resolution dialog is visible (RF-4): only in `exact`
     * mode.
     *
     * @param mode the current sanitized `displayResolutionMode`
     * @return visibility of the exact-resolution control
     */
    fun isExactResolutionVisible(mode: String): Boolean = mode == "exact"

    /**
     * Whether the custom-resolution text field is visible (RF-4): only in
     * `custom` mode.
     *
     * @param mode the current sanitized `displayResolutionMode`
     * @return visibility of the custom-resolution control
     */
    fun isCustomResolutionVisible(mode: String): Boolean = mode == "custom"
}
