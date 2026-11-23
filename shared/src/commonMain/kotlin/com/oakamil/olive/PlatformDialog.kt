// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

package com.oakamil.olive

import androidx.compose.ui.Modifier
import androidx.compose.ui.window.DialogProperties

/** Platform-specific [DialogProperties] configured for edge-to-edge full-screen Compose dialogs. */
expect fun fullScreenDialogProperties(
    dismissOnBackPress: Boolean = true,
    dismissOnClickOutside: Boolean = false
): DialogProperties

/** Applies system-bar / display-cutout safe-area padding inside full-screen dialogs. */
expect fun Modifier.platformDialogSafeAreaPadding(): Modifier
