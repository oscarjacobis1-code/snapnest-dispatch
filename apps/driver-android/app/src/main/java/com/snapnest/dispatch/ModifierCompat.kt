package com.snapnest.dispatch

import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * Keeps standalone action composables centered when they are not invoked from a ColumnScope.
 * ColumnScope.align still takes precedence inside normal Column content.
 */
fun Modifier.align(alignment: Alignment.Horizontal): Modifier = wrapContentWidth(align = alignment)
