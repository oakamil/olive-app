// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

package com.oakamil.olive

import androidx.compose.ui.Modifier
import androidx.compose.ui.window.DialogProperties

actual fun fullScreenDialogProperties(
    dismissOnBackPress: Boolean,
    dismissOnClickOutside: Boolean
): DialogProperties = DialogProperties(
    dismissOnBackPress = dismissOnBackPress,
    dismissOnClickOutside = dismissOnClickOutside,
    usePlatformDefaultWidth = false
)

actual fun Modifier.platformDialogSafeAreaPadding(): Modifier = this
