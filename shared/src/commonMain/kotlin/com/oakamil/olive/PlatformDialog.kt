// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

package com.oakamil.olive

import androidx.compose.ui.Modifier
import androidx.compose.ui.window.DialogProperties

expect fun fullScreenDialogProperties(
    dismissOnBackPress: Boolean = true,
    dismissOnClickOutside: Boolean = false
): DialogProperties

expect fun Modifier.platformDialogSafeAreaPadding(): Modifier
