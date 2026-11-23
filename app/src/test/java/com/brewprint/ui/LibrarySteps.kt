package com.brewprint.ui

import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo

/** Opens the Library tab, then one of its sections from the hub: "recipes", "beans" or "grinders". */
internal fun SemanticsNodeInteractionsProvider.openLibrarySection(kind: String) {
    onNode(hasText("Library") and isSelectable()).performClick()
    onNodeWithTag("library-$kind").performScrollTo().performClick()
}
