package com.pbungert.geoimport.core.imports

/**
 * The camera a card's photos came from, as their EXIF names it: Make (0x010F),
 * Model (0x0110) and BodySerialNumber (0xA431). The serial is what tells two
 * bodies of the same model apart; not every camera writes it.
 */
data class Camera(val make: String?, val model: String?, val serial: String?) {

    /**
     * Stable across devices and safe as a properties key, e.g.
     * `FUJIFILM_X-T2_81M52794`. Built from the values alone, so the phone and
     * the PC arrive at the same key for the same camera.
     */
    val key: String
        get() = listOfNotNull(make, modelWithoutMake, serial)
            .map { it.replace(NOT_KEY_CHARS, "-").trim('-') }
            .filter { it.isNotEmpty() }
            .joinToString("_")

    /** "FUJIFILM X-T2 (81M52794)", for the log. */
    val label: String
        get() = listOfNotNull(make, modelWithoutMake).joinToString(" ") +
            (serial?.let { " ($it)" } ?: "")

    /** Canon and others repeat the make in the model: "Canon" / "Canon EOS R5". */
    private val modelWithoutMake: String?
        get() = if (make != null && model != null && model.startsWith(make, ignoreCase = true)) {
            model.substring(make.length).trim().ifEmpty { null }
        } else {
            model
        }

    /**
     * Whether [other] could be the same body: same make and model, and the
     * same serial unless one of them lacks it - a reader that cannot get at
     * the serial of some format should not turn one camera into two.
     */
    fun matches(other: Camera): Boolean =
        make.equals(other.make, ignoreCase = true) &&
            model.equals(other.model, ignoreCase = true) &&
            (serial == null || other.serial == null || serial.equals(other.serial, ignoreCase = true))

    override fun toString() = label

    companion object {
        private val NOT_KEY_CHARS = Regex("[^A-Za-z0-9]+")

        /** Trimmed, blank as null; null when nothing at all identifies the camera. */
        fun of(make: String?, model: String?, serial: String?): Camera? {
            fun clean(value: String?) = value?.replace('\u0000', ' ')
                ?.replace(Regex("\\s+"), " ")?.trim()?.ifEmpty { null }
            val camera = Camera(clean(make), clean(model), clean(serial))
            return camera.takeIf { it.key.isNotEmpty() }
        }
    }
}
