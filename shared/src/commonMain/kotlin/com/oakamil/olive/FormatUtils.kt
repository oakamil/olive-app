// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

package com.oakamil.olive

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.em
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong

fun String.Companion.format(format: String, vararg args: Any?): String = pureFormat(format, *args)

/**
 * Lightweight multiplatform HTML-to-[AnnotatedString] parser supporting basic formatting tags
 * (`b`, `strong`, `i`, `em`, `u`, `h1`-`h3`, `small`, `p`, `br`) and common HTML entities.
 */
fun AnnotatedString.Companion.fromHtml(htmlString: String): AnnotatedString {
    val clean = htmlString
        .replace("\\'", "'")
        .replace("\\\"", "\"")
        .replace("<br>", "\n")
        .replace("<br/>", "\n")
        .replace("<br />", "\n")

    return buildAnnotatedString {
        var i = 0
        var lastChar = '\n'
        var pushedCount = 0

        while (i < clean.length) {
            val c = clean[i]
            if (c == '<') {
                val closeTag = clean.indexOf('>', i)
                if (closeTag != -1) {
                    val rawTag = clean.substring(i + 1, closeTag).trim().lowercase()
                    val tagName = rawTag.substringBefore(' ')
                    when (tagName) {
                        "b", "strong" -> {
                            pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                            pushedCount++
                        }

                        "/b", "/strong" -> {
                            if (pushedCount > 0) {
                                pop()
                                pushedCount--
                            }
                        }

                        "i", "em" -> {
                            pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                            pushedCount++
                        }

                        "/i", "/em" -> {
                            if (pushedCount > 0) {
                                pop()
                                pushedCount--
                            }
                        }

                        "u" -> {
                            pushStyle(SpanStyle(textDecoration = TextDecoration.Underline))
                            pushedCount++
                        }

                        "/u" -> {
                            if (pushedCount > 0) {
                                pop()
                                pushedCount--
                            }
                        }

                        "h1" -> {
                            if (lastChar != '\n') {
                                append('\n')
                                lastChar = '\n'
                            }
                            pushStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 1.3.em))
                            pushedCount++
                        }

                        "/h1" -> {
                            if (pushedCount > 0) {
                                pop()
                                pushedCount--
                            }
                            if (lastChar != '\n') {
                                append('\n')
                                lastChar = '\n'
                            }
                        }

                        "h2" -> {
                            if (lastChar != '\n') {
                                append('\n')
                                lastChar = '\n'
                            }
                            pushStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 1.15.em))
                            pushedCount++
                        }

                        "/h2" -> {
                            if (pushedCount > 0) {
                                pop()
                                pushedCount--
                            }
                            if (lastChar != '\n') {
                                append('\n')
                                lastChar = '\n'
                            }
                        }

                        "h3" -> {
                            if (lastChar != '\n') {
                                append('\n')
                                lastChar = '\n'
                            }
                            pushStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 1.05.em))
                            pushedCount++
                        }

                        "/h3" -> {
                            if (pushedCount > 0) {
                                pop()
                                pushedCount--
                            }
                            if (lastChar != '\n') {
                                append('\n')
                                lastChar = '\n'
                            }
                        }

                        "small" -> {
                            pushStyle(SpanStyle(fontSize = 0.85.em))
                            pushedCount++
                        }

                        "/small" -> {
                            if (pushedCount > 0) {
                                pop()
                                pushedCount--
                            }
                        }

                        "p" -> {
                            if (lastChar != '\n') {
                                append('\n')
                                lastChar = '\n'
                            }
                        }

                        "/p" -> {
                            if (lastChar != '\n') {
                                append('\n')
                                lastChar = '\n'
                            }
                        }
                        // Any other unknown tag is stripped cleanly without rendering literal brackets
                    }
                    i = closeTag + 1
                    continue
                }
            } else if (c == '&') {
                val semicolon = clean.indexOf(';', i)
                if (semicolon != -1 && semicolon - i <= 10) {
                    val entity = clean.substring(i, semicolon + 1)
                    val decoded = when (entity) {
                        "&lt;" -> "<"
                        "&gt;" -> ">"
                        "&amp;" -> "&"
                        "&quot;" -> "\""
                        "&apos;", "&#39;" -> "'"
                        "&nbsp;" -> " "
                        "&copy;" -> "©"
                        "&bull;" -> "•"
                        "&ldquo;" -> "“"
                        "&rdquo;" -> "”"
                        else -> null
                    }
                    if (decoded != null) {
                        append(decoded)
                        lastChar = decoded.lastOrNull() ?: lastChar
                        i = semicolon + 1
                        continue
                    }
                }
            }

            append(c)
            lastChar = c
            i++
        }
    }
}


/**
 * Pure-Kotlin `printf`-style formatter for KMP targets, supporting positional arguments (`1$`),
 * `-`/`+`/`0` flags, width, precision, and `s`, `d`, `f`, `b`, `c`, `x`/`X` conversions.
 */
internal fun pureFormat(format: String, vararg args: Any?): String {
    val sb = StringBuilder()
    var argIndex = 0
    var i = 0
    while (i < format.length) {
        val c = format[i]
        if (c == '%' && i + 1 < format.length) {
            if (format[i + 1] == '%') {
                sb.append('%')
                i += 2
                continue
            }
            // Parse specifier: %[argument_index$][flags][width][.precision]conversion
            var j = i + 1

            // 1. Check for positional argument index e.g. "1$", "2$"
            var explicitArgIndex = -1
            var k = j
            while (k < format.length && format[k] in '0'..'9') {
                k++
            }
            if (k > j && k < format.length && format[k] == '$') {
                val indexStr = format.substring(j, k)
                explicitArgIndex = (indexStr.toIntOrNull() ?: 1) - 1
                j = k + 1
            }

            // 2. Flags: -+ 0,#
            var flagPlus = false
            var flagMinus = false
            var flagZero = false

            while (j < format.length && format[j] in "-+ 0,#") {
                when (format[j]) {
                    '+' -> flagPlus = true
                    '-' -> flagMinus = true
                    '0' -> flagZero = true
                }
                j++
            }

            // 3. Width
            var width = 0
            while (j < format.length && format[j] in '0'..'9') {
                width = width * 10 + (format[j] - '0')
                j++
            }

            // 4. Precision
            var precision = -1
            if (j < format.length && format[j] == '.') {
                j++
                precision = 0
                while (j < format.length && format[j] in '0'..'9') {
                    precision = precision * 10 + (format[j] - '0')
                    j++
                }
            }

            // 5. Conversion character
            if (j < format.length) {
                val spec = format[j]
                val arg = if (explicitArgIndex >= 0) {
                    if (explicitArgIndex in args.indices) args[explicitArgIndex] else null
                } else {
                    if (argIndex in args.indices) args[argIndex++] else null
                }
                val formatted = when (spec) {
                    's', 'S' -> arg?.toString() ?: "null"
                    'd' -> {
                        val n = (arg as? Number)?.toLong() ?: 0L
                        val signStr = if (n >= 0 && flagPlus) "+" else ""
                        signStr + n.toString()
                    }

                    'f' -> {
                        val d = (arg as? Number)?.toDouble() ?: 0.0
                        val prec = if (precision >= 0) precision else 6
                        formatDouble(d, prec, flagPlus)
                    }

                    'b', 'B' -> (arg as? Boolean)?.toString() ?: arg?.toString() ?: "false"
                    'c', 'C' -> (arg as? Char)?.toString() ?: arg?.toString() ?: ""
                    'x' -> {
                        val n = (arg as? Number)?.toLong() ?: 0L
                        n.toString(16)
                    }

                    'X' -> {
                        val n = (arg as? Number)?.toLong() ?: 0L
                        n.toString(16).uppercase()
                    }

                    else -> arg?.toString() ?: ""
                }

                // Apply width & padding
                val padChar = if (flagZero && !flagMinus) '0' else ' '
                val padLen = (width - formatted.length).coerceAtLeast(0)
                if (flagMinus) {
                    sb.append(formatted)
                    repeat(padLen) { sb.append(' ') }
                } else {
                    if (padChar == '0' && (formatted.startsWith("+") || formatted.startsWith("-"))) {
                        sb.append(formatted[0])
                        repeat(padLen) { sb.append('0') }
                        sb.append(formatted.substring(1))
                    } else {
                        repeat(padLen) { sb.append(padChar) }
                        sb.append(formatted)
                    }
                }
                i = j + 1
                continue
            }
        }
        sb.append(c)
        i++
    }
    return sb.toString()
}

private fun formatDouble(value: Double, precision: Int, alwaysSign: Boolean): String {
    if (value.isNaN()) return "NaN"
    if (value.isInfinite()) return if (value > 0) "Infinity" else "-Infinity"

    val safePrecision = precision.coerceIn(0, 12)
    val isNeg = value < 0
    val absVal = abs(value)
    val factor = 10.0.pow(safePrecision)
    val scaled = absVal * factor
    // Guard against Long overflow in roundToLong() (max signed Long ≈ 9.22e18).
    if (!scaled.isFinite() || scaled >= 4.5e18) {
        val sign = if (isNeg && absVal > 0.0) "-" else if (alwaysSign) "+" else ""
        return "$sign$absVal"
    }

    val rounded = scaled.roundToLong()
    val factorLong = factor.toLong()
    val intPart = rounded / factorLong
    val fracPart = rounded % factorLong

    val sign = if (isNeg && rounded > 0L) "-" else if (alwaysSign) "+" else ""
    return if (safePrecision > 0) {
        val fracStr = fracPart.toString().padStart(safePrecision, '0')
        "$sign$intPart.$fracStr"
    } else {
        "$sign$intPart"
    }
}
