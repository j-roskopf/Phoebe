package com.phoebe.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun PillTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String,
    contentDescription: String,
    leadingIcon: PhoebeIcon,
    showClearButton: Boolean = true,
    clearButtonContentDescription: String = "Clear",
    focusRequester: FocusRequester? = null,
) {
    val fieldTextStyle = TextStyle(color = PhoebeUi.primaryText, fontSize = 12.sp, lineHeight = 16.sp)
    var fieldValue by remember { mutableStateOf(TextFieldValue(value, selection = TextRange(value.length))) }
    if (value != fieldValue.text) {
        fieldValue = fieldValue.copy(text = value, selection = TextRange(value.length))
    }
    val shape = RoundedCornerShape(PhoebeUi.shapes.controlRadius)
    Row(
        modifier
            .height(40.dp)
            .clip(shape)
            .background(if (PhoebeUi.design == PhoebeDesignSystem.Minimalist) PhoebeUi.modalField else PhoebeUi.subtleFill)
            .border(BorderStroke(1.dp, PhoebeUi.border), shape)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PhoebeIconView(leadingIcon, tint = PhoebeUi.mutedText, modifier = Modifier.size(18.dp))
        BasicTextField(
            value = fieldValue,
            onValueChange = { newValue ->
                fieldValue = newValue
                if (newValue.text != value) onValueChange(newValue.text)
            },
            singleLine = true,
            textStyle = fieldTextStyle,
            cursorBrush = SolidColor(PhoebeUi.primaryText),
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .trackDesktopTextInputFocus()
                .semantics { this.contentDescription = contentDescription },
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (value.isBlank()) {
                        Text(
                            placeholder,
                            color = PhoebeUi.mutedText,
                            style = fieldTextStyle,
                            maxLines = 1,
                        )
                    }
                    innerTextField()
                }
            },
        )
        if (showClearButton && value.isNotBlank()) {
            Box(
                Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .clickable { onValueChange("") }
                    .semantics { this.contentDescription = clearButtonContentDescription },
                contentAlignment = Alignment.Center,
            ) {
                PhoebeIconView(PhoebeIcon.Close, tint = PhoebeUi.mutedText, modifier = Modifier.size(14.dp))
            }
        }
    }
}

@Composable
fun SearchPill(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier.width(270.dp),
    placeholder: String = "Search songs, artists, albums",
    focusRequester: FocusRequester? = null,
) {
    PillTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier,
        placeholder = placeholder,
        contentDescription = placeholder,
        leadingIcon = PhoebeIcon.Search,
        showClearButton = true,
        clearButtonContentDescription = "Clear search",
        focusRequester = focusRequester,
    )
}
