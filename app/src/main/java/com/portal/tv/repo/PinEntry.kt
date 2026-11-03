package com.portal.tv.repo

/**
 * The PIN being typed on the row picker (see PinPad). Digits are only ever shown as dots.
 * Free of Android types so it can be unit tested.
 */
class PinEntry(private val maxLength: Int = ParentPin.MAX_LENGTH) {

    private val digits = StringBuilder()

    val value: String get() = digits.toString()
    val length: Int get() = digits.length

    fun add(digit: Char) {
        if (digit in '0'..'9' && digits.length < maxLength) digits.append(digit)
    }

    fun delete() {
        if (digits.isNotEmpty()) digits.deleteCharAt(digits.length - 1)
    }

    fun clear() = digits.clear()

    companion object {
        /**
         * The picker's rows. Up and Down choose a row, then Left, OK or Right chooses the first,
         * second or third digit in it, so someone watching sees only the same few buttons pressed.
         * On the last row only the middle button is a digit (0); the others are Delete and Cancel.
         */
        val ROWS: List<String> = listOf("123", "456", "789", "-0-")

        /** The digit for pressing [column] (0 left, 1 OK, 2 right) on digit row [row], or null (also for the Delete and Cancel buttons). */
        fun digitAt(row: Int, column: Int): Char? =
            ROWS.getOrNull(row)?.getOrNull(column)?.takeIf { it in '0'..'9' }
    }
}
