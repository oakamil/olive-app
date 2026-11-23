// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

package com.oakamil.olive

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource


/** Unescapes Android-style XML quote escapes (`\'`, `\"`) in Compose Resources strings. */
internal fun String.unescapeXml(): String =
    this.replace("\\'", "'").replace("\\\"", "\"")

@Composable
fun stringResource(id: StringResource): String =
    org.jetbrains.compose.resources.stringResource(id).unescapeXml()

/** Resolves a Compose string resource, unescapes XML quotes, and formats arguments via [pureFormat]. */
@Composable
fun stringResource(id: StringResource, vararg formatArgs: Any): String {
    val raw = org.jetbrains.compose.resources.stringResource(id).unescapeXml()
    return pureFormat(raw, *formatArgs)
}

@Composable
fun painterResource(id: DrawableResource): Painter =
    org.jetbrains.compose.resources.painterResource(id)


