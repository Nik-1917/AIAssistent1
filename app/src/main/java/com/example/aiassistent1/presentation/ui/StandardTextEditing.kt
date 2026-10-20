package com.example.aiassistent1.presentation.ui

import android.view.View
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.modifier.filterTextContextMenuComponents
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentDataType
import androidx.compose.ui.composed
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDataType
import androidx.compose.ui.semantics.semantics

/** Applies the same editing policy to screen fields and fields hosted in separate dialogs. */
internal fun Modifier.standardTextEditing(): Modifier = composed {
    val hostView = LocalView.current
    SideEffect {
        // This policy lasts for the host's lifetime and also covers library-owned input fields.
        hostView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    }
    semantics { contentDataType = ContentDataType.None }
        .filterTextContextMenuComponents { component ->
            when (component.key) {
                TextContextMenuKeys.CutKey,
                TextContextMenuKeys.CopyKey,
                TextContextMenuKeys.PasteKey,
                TextContextMenuKeys.SelectAllKey -> true
                else -> false
            }
        }
}
