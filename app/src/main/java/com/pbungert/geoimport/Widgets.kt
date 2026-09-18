package com.pbungert.geoimport

import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp

// Controls more than one screen needs.

/**
 * Label for a segmented button. Segments split the row width evenly, which on
 * a phone is too narrow for a long label like "Custom": it would wrap onto a
 * second line and grow the row. Keep it on one line and shrink the type only
 * as far as the segment demands.
 */
@Composable
internal fun SegmentLabel(text: String) {
    Text(
        text,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        autoSize = TextAutoSize.StepBased(
            minFontSize = 9.sp,
            maxFontSize = MaterialTheme.typography.labelLarge.fontSize,
            stepSize = 0.5.sp,
        ),
    )
}
