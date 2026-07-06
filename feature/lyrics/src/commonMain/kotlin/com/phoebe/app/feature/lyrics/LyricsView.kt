package com.phoebe.app.feature.lyrics

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phoebe.app.domain.LyricsAnnotation
import com.phoebe.app.domain.LyricsAnnotations
import com.phoebe.app.domain.LyricsDocument
import com.phoebe.app.domain.LyricsLine
import com.phoebe.app.domain.LyricsLoadState
import com.phoebe.app.domain.LyricsSource
import com.phoebe.app.domain.Track
import com.phoebe.app.platform.currentTimeMs
import com.phoebe.app.platform.openExternalUrl
import com.phoebe.app.ui.DetailBackButton
import com.phoebe.app.ui.PhoebeIcon
import com.phoebe.app.ui.PhoebeIconView
import com.phoebe.app.ui.PhoebeUi
import com.phoebe.app.ui.PlatformBackHandler
import com.phoebe.app.ui.SectionLabel
import com.phoebe.app.ui.mobileContentTopPadding
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val LyricsAutoScrollPauseMs = 5_000L
private const val UnmatchedAnnotationsSelection = -1
private const val AnnotationSheetDismissAnimationMs = 220
private const val AnnotationSheetScrimAlpha = 0.58f
private const val AnnotationSheetDragDismissFraction = 0.24f

private data class AnnotationSheetContent(
    val title: String,
    val annotations: List<LyricsAnnotation>,
    val songAnnotations: LyricsAnnotations?,
)

@Composable
fun LyricsView(
    track: Track?,
    currentTrackId: String?,
    positionMs: Long,
    state: LyricsLoadState,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    onRetry: () -> Unit,
) {
    val loadedDocument = (state as? LyricsLoadState.Loaded)?.document
    var annotationSheet by remember(loadedDocument?.trackFingerprint, loadedDocument?.annotations) {
        mutableStateOf<AnnotationSheetContent?>(null)
    }
    fun showAnnotationSheet(content: AnnotationSheetContent) {
        annotationSheet = content
    }

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(PhoebeUi.shellTop),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(
                    start = 24.dp,
                    top = mobileContentTopPadding(22.dp),
                    end = 24.dp,
                    bottom = 22.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            LyricsHeader(
                track = track,
                source = loadedDocument?.source,
                hasGeniusAnnotations = loadedDocument?.annotations?.hasAnnotations == true,
                onBack = onBack,
            )
            when {
                track == null -> LyricsEmptyState("Start a song to see lyrics here.")
                state is LyricsLoadState.Loading -> LyricsLoadingState()
                state is LyricsLoadState.Loaded -> LyricsDocumentView(
                    document = state.document,
                    positionMs = if (track.id == currentTrackId) positionMs else 0L,
                    syncEnabled = track.id == currentTrackId,
                    onShowMobileAnnotations = ::showAnnotationSheet,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
                state is LyricsLoadState.NotFound -> LyricsRetryState("No lyrics found for this song yet.", onRetry)
                state is LyricsLoadState.Failed -> LyricsRetryState(state.message, onRetry)
                else -> LyricsEmptyState("Lyrics will appear here when a song is selected.")
            }
        }
        annotationSheet?.let { sheet ->
            AnnotationBottomSheet(
                sheet = sheet,
                maxHeight = (maxHeight.value * 0.86f).dp,
                onDismissed = { annotationSheet = null },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun LyricsHeader(
    track: Track?,
    source: LyricsSource?,
    hasGeniusAnnotations: Boolean,
    onBack: (() -> Unit)?,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            DetailBackButton(onBack = onBack)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SectionLabel("Lyrics", PhoebeUi.accentLight)
            Text(
                track?.title ?: "No song playing",
                color = PhoebeUi.primaryText,
                fontSize = 28.sp,
                lineHeight = 32.sp,
                fontWeight = FontWeight.Black,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (track != null) {
                Text(
                    listOfNotNull(
                        track.artist,
                        source?.label(),
                        if (hasGeniusAnnotations) "Genius annotations" else null,
                    ).filter { it.isNotBlank() }.joinToString(" • "),
                    color = PhoebeUi.secondaryText,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun LyricsDocumentView(
    document: LyricsDocument,
    positionMs: Long,
    syncEnabled: Boolean,
    onShowMobileAnnotations: (AnnotationSheetContent) -> Unit,
    modifier: Modifier,
) {
    if (document.instrumental) {
        LyricsEmptyState("Instrumental track.")
        return
    }
    if (!document.hasText) {
        LyricsEmptyState("No lyric text available.")
        return
    }
    val lines = document.lines
    val annotations = document.annotations
    val annotationsByLine = remember(annotations) {
        annotations?.annotations
            .orEmpty()
            .flatMap { annotation ->
                annotation.target?.lineIndexes.orEmpty().map { lineIndex -> lineIndex to annotation }
            }
            .groupBy(keySelector = { it.first }, valueTransform = { it.second })
    }
    var selectedLineIndex by remember(document.trackFingerprint, annotations) { mutableStateOf<Int?>(null) }
    val selectedAnnotations = when (selectedLineIndex) {
        UnmatchedAnnotationsSelection -> annotations?.unmatched.orEmpty()
        null -> emptyList()
        else -> annotationsByLine[selectedLineIndex].orEmpty()
    }
    val activeIndex by remember(lines, positionMs, syncEnabled) {
        derivedStateOf {
            if (!document.synced || !syncEnabled) -1 else activeLyricsIndex(lines, positionMs)
        }
    }
    val listState = rememberLazyListState()
    var lastUserTouchMs by remember(document.trackFingerprint) { mutableLongStateOf(0L) }
    fun markUserBrowsing() {
        lastUserTouchMs = currentTimeMs()
    }
    LaunchedEffect(document.trackFingerprint, activeIndex) {
        val autoScrollSuppressed = currentTimeMs() - lastUserTouchMs < LyricsAutoScrollPauseMs
        if (activeIndex >= 0 && !autoScrollSuppressed) {
            listState.animateScrollToItem((activeIndex - 3).coerceAtLeast(0))
        }
    }

    BoxWithConstraints(modifier) {
        val wideLayout = maxWidth >= 820.dp
        if (wideLayout) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                LyricsLinesList(
                    document = document,
                    lines = lines,
                    activeIndex = activeIndex,
                    annotations = annotations,
                    annotationsByLine = annotationsByLine,
                    selectedLineIndex = selectedLineIndex,
                    onSelectLine = { selectedLineIndex = it },
                    listState = listState,
                    markUserBrowsing = ::markUserBrowsing,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
                if (selectedAnnotations.isNotEmpty()) {
                    AnnotationDetailPanel(
                        title = annotationPanelTitle(selectedLineIndex),
                        annotations = selectedAnnotations,
                        songAnnotations = annotations,
                        modifier = Modifier.width(320.dp).fillMaxHeight(),
                    )
                }
            }
        } else {
            LyricsLinesList(
                document = document,
                lines = lines,
                activeIndex = activeIndex,
                annotations = annotations,
                annotationsByLine = annotationsByLine,
                selectedLineIndex = null,
                onSelectLine = { selection ->
                    val mobileAnnotations = when (selection) {
                        UnmatchedAnnotationsSelection -> annotations?.unmatched.orEmpty()
                        null -> emptyList()
                        else -> annotationsByLine[selection].orEmpty()
                    }
                    if (mobileAnnotations.isNotEmpty()) {
                        onShowMobileAnnotations(
                            AnnotationSheetContent(
                                title = annotationPanelTitle(selection),
                                annotations = mobileAnnotations,
                                songAnnotations = annotations,
                            ),
                        )
                    }
                },
                listState = listState,
                markUserBrowsing = ::markUserBrowsing,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun LyricsLinesList(
    document: LyricsDocument,
    lines: List<LyricsLine>,
    activeIndex: Int,
    annotations: LyricsAnnotations?,
    annotationsByLine: Map<Int, List<LyricsAnnotation>>,
    selectedLineIndex: Int?,
    onSelectLine: (Int?) -> Unit,
    listState: androidx.compose.foundation.lazy.LazyListState,
    markUserBrowsing: () -> Unit,
    modifier: Modifier,
) {
    LazyColumn(
        state = listState,
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(PhoebeUi.panel)
            .border(BorderStroke(1.dp, PhoebeUi.border), RoundedCornerShape(14.dp))
            .pointerInput(document.trackFingerprint) {
                detectTapGestures(
                    onPress = {
                        markUserBrowsing()
                        tryAwaitRelease()
                    },
                )
            }
            .pointerInput(document.trackFingerprint) {
                detectDragGestures(
                    onDragStart = { markUserBrowsing() },
                    onDrag = { _, _ -> markUserBrowsing() },
                )
            }
            .pointerInput(document.trackFingerprint) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Scroll) {
                            markUserBrowsing()
                        }
                    }
                }
            },
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        itemsIndexed(
            items = lines,
            key = { index, line -> "${line.startMs ?: "plain"}:$index:${line.text.hashCode()}" },
        ) { index, line ->
            val active = index == activeIndex
            val lineAnnotations = annotationsByLine[index].orEmpty()
            LyricsLineRow(
                line = line,
                active = active,
                dimInactive = document.synced,
                annotationCount = lineAnnotations.size,
                selected = selectedLineIndex == index,
                onClick = if (lineAnnotations.isNotEmpty()) {
                    { onSelectLine(if (selectedLineIndex == index) null else index) }
                } else {
                    null
                },
            )
        }
        annotations?.unmatched?.takeIf { it.isNotEmpty() }?.let { unmatched ->
            item("genius-unmatched") {
                UnmatchedAnnotationRow(
                    count = unmatched.size,
                    selected = selectedLineIndex == UnmatchedAnnotationsSelection,
                    onClick = {
                        onSelectLine(
                            if (selectedLineIndex == UnmatchedAnnotationsSelection) {
                                null
                            } else {
                                UnmatchedAnnotationsSelection
                            },
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun LyricsLineRow(
    line: LyricsLine,
    active: Boolean,
    dimInactive: Boolean,
    annotationCount: Int,
    selected: Boolean,
    onClick: (() -> Unit)?,
) {
    val rowShape = RoundedCornerShape(10.dp)
    val textColor = if (active) {
        PhoebeUi.accentLight
    } else {
        PhoebeUi.primaryText.copy(alpha = if (dimInactive) 0.56f else 0.86f)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(rowShape)
            .background(if (selected) PhoebeUi.accent.copy(alpha = 0.12f) else androidx.compose.ui.graphics.Color.Transparent)
            .then(
                if (onClick != null) {
                    Modifier
                        .clickable(onClick = onClick)
                        .semantics {
                            role = Role.Button
                            contentDescription = "${line.text}. $annotationCount Genius annotations"
                        }
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            line.text.ifBlank { " " },
            color = textColor,
            fontSize = if (active) 24.sp else 20.sp,
            lineHeight = if (active) 30.sp else 27.sp,
            fontWeight = if (active) FontWeight.Black else FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        if (annotationCount > 0) {
            AnnotationMarker(count = annotationCount, selected = selected)
        }
    }
}

@Composable
private fun AnnotationMarker(count: Int, selected: Boolean) {
    Box(
        Modifier
            .size(24.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) PhoebeUi.accentLight else PhoebeUi.elevatedFill)
            .border(
                BorderStroke(1.dp, if (selected) PhoebeUi.accentLight else PhoebeUi.border),
                RoundedCornerShape(999.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            count.coerceAtMost(9).let { if (count > 9) "9+" else it.toString() },
            color = if (selected) PhoebeUi.panel else PhoebeUi.accentLight,
            fontSize = 11.sp,
            fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun UnmatchedAnnotationRow(
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) PhoebeUi.accent.copy(alpha = 0.12f) else PhoebeUi.elevatedFill.copy(alpha = 0.44f))
            .border(BorderStroke(1.dp, PhoebeUi.border), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .semantics {
                role = Role.Button
                contentDescription = "$count unmatched Genius annotations"
            }
            .padding(horizontal = 12.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PhoebeIconView(PhoebeIcon.Book, tint = PhoebeUi.accentLight, modifier = Modifier.size(16.dp))
        Text(
            "More Genius annotations",
            color = PhoebeUi.primaryText,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        AnnotationMarker(count = count, selected = selected)
    }
}

@Composable
private fun AnnotationDetailPanel(
    title: String,
    annotations: List<LyricsAnnotation>,
    songAnnotations: LyricsAnnotations?,
    modifier: Modifier,
    onDismiss: (() -> Unit)? = null,
    shape: RoundedCornerShape = RoundedCornerShape(14.dp),
    showDragHandle: Boolean = false,
    includeNavigationBarPadding: Boolean = false,
    dragHandleModifier: Modifier = Modifier,
    opaqueContainer: Boolean = false,
) {
    val containerColor = if (opaqueContainer) PhoebeUi.panel.copy(alpha = 1f) else PhoebeUi.panel
    val panelModifier = modifier
        .clip(shape)
        .background(containerColor)
        .border(BorderStroke(1.dp, PhoebeUi.border), shape)

    if (showDragHandle) {
        Column(panelModifier) {
            AnnotationPanelHeader(
                title = title,
                songAnnotations = songAnnotations,
                onDismiss = onDismiss,
                showDragHandle = true,
                dragHandleModifier = dragHandleModifier,
            )
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                annotations.forEach { annotation ->
                    AnnotationBody(annotation)
                }
                if (includeNavigationBarPadding) {
                    Spacer(Modifier.navigationBarsPadding())
                }
            }
        }
        return
    }

    Column(
        panelModifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AnnotationPanelHeader(
            title = title,
            songAnnotations = songAnnotations,
            onDismiss = onDismiss,
        )
        annotations.forEach { annotation ->
            AnnotationBody(annotation)
        }
        if (includeNavigationBarPadding) {
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

@Composable
private fun AnnotationPanelHeader(
    title: String,
    songAnnotations: LyricsAnnotations?,
    onDismiss: (() -> Unit)?,
    showDragHandle: Boolean = false,
    dragHandleModifier: Modifier = Modifier,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (showDragHandle) dragHandleModifier else Modifier),
    ) {
        if (showDragHandle) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .width(38.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(PhoebeUi.border.copy(alpha = 0.92f)),
                )
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(
                    start = if (showDragHandle) 16.dp else 0.dp,
                    end = if (showDragHandle) 12.dp else 0.dp,
                    bottom = if (showDragHandle) 12.dp else 0.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = PhoebeUi.primaryText, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                songAnnotations?.songTitle?.let { songTitle ->
                    Text(songTitle, color = PhoebeUi.secondaryText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            songAnnotations?.songUrl?.let { url ->
                TextButton(onClick = { openExternalUrl(url) }) {
                    Text("Genius", color = PhoebeUi.accentLight, fontWeight = FontWeight.SemiBold)
                }
            }
            onDismiss?.let { dismiss ->
                AnnotationCloseButton(onClick = dismiss)
            }
        }
    }
}

@Composable
private fun AnnotationBottomSheet(
    sheet: AnnotationSheetContent,
    maxHeight: Dp,
    onDismissed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val sheetOffsetPx = remember(sheet) { Animatable(10_000f) }
    val scrimAlpha = remember(sheet) { Animatable(0f) }
    var sheetHeightPx by remember(sheet) { mutableIntStateOf(0) }
    var dismissing by remember(sheet) { mutableStateOf(false) }
    var animatedSheet by remember { mutableStateOf<AnnotationSheetContent?>(null) }

    fun animateDismiss() {
        if (dismissing) return
        dismissing = true
        scope.launch {
            val hiddenOffset = sheetHeightPx.takeIf { it > 0 }?.toFloat() ?: sheetOffsetPx.value
            launch {
                scrimAlpha.animateTo(
                    0f,
                    animationSpec = tween(AnnotationSheetDismissAnimationMs, easing = FastOutSlowInEasing),
                )
            }
            sheetOffsetPx.animateTo(
                hiddenOffset,
                animationSpec = tween(AnnotationSheetDismissAnimationMs, easing = FastOutSlowInEasing),
            )
            onDismissed()
        }
    }

    LaunchedEffect(sheet, sheetHeightPx) {
        if (sheetHeightPx > 0 && animatedSheet != sheet) {
            animatedSheet = sheet
            sheetOffsetPx.snapTo(sheetHeightPx.toFloat())
            scrimAlpha.snapTo(0f)
            launch {
                scrimAlpha.animateTo(
                    AnnotationSheetScrimAlpha,
                    animationSpec = tween(150, easing = FastOutSlowInEasing),
                )
            }
            sheetOffsetPx.animateTo(
                0f,
                animationSpec = tween(280, easing = FastOutSlowInEasing),
            )
        }
    }

    PlatformBackHandler(enabled = true, onBack = ::animateDismiss)
    Box(modifier) {
        Box(
            Modifier
                .fillMaxSize()
                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = scrimAlpha.value))
                .clickable(onClick = ::animateDismiss)
                .semantics {
                    role = Role.Button
                    contentDescription = "Dismiss Genius annotations"
                },
        )
        AnnotationDetailPanel(
            title = sheet.title,
            annotations = sheet.annotations,
            songAnnotations = sheet.songAnnotations,
            onDismiss = ::animateDismiss,
            shape = RoundedCornerShape(
                topStart = 22.dp,
                topEnd = 22.dp,
                bottomEnd = 0.dp,
                bottomStart = 0.dp,
            ),
            showDragHandle = true,
            includeNavigationBarPadding = true,
            dragHandleModifier = Modifier.annotationSheetDragHandle(
                sheetOffsetPx = sheetOffsetPx,
                sheetHeightPx = sheetHeightPx,
                onDismiss = ::animateDismiss,
            ),
            opaqueContainer = true,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .onSizeChanged { size -> sheetHeightPx = size.height }
                .offset { IntOffset(x = 0, y = sheetOffsetPx.value.roundToInt()) },
        )
    }
}

private fun Modifier.annotationSheetDragHandle(
    sheetOffsetPx: Animatable<Float, androidx.compose.animation.core.AnimationVector1D>,
    sheetHeightPx: Int,
    onDismiss: () -> Unit,
): Modifier = pointerInput(sheetOffsetPx, sheetHeightPx, onDismiss) {
    coroutineScope {
        detectVerticalDragGestures(
            onVerticalDrag = { change, dragAmount ->
                change.consume()
                val target = (sheetOffsetPx.value + dragAmount).coerceIn(0f, sheetHeightPx.toFloat())
                launch { sheetOffsetPx.snapTo(target) }
            },
            onDragEnd = {
                val dismissThreshold = sheetHeightPx * AnnotationSheetDragDismissFraction
                if (sheetOffsetPx.value >= dismissThreshold) {
                    onDismiss()
                } else {
                    launch {
                        sheetOffsetPx.animateTo(
                            0f,
                            animationSpec = tween(180, easing = FastOutSlowInEasing),
                        )
                    }
                }
            },
            onDragCancel = {
                launch {
                    sheetOffsetPx.animateTo(
                        0f,
                        animationSpec = tween(180, easing = FastOutSlowInEasing),
                    )
                }
            },
        )
    }
}

@Composable
private fun AnnotationCloseButton(onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(PhoebeUi.elevatedFill)
            .clickable(onClick = onClick)
            .semantics {
                role = Role.Button
                contentDescription = "Close Genius annotations"
            },
        contentAlignment = Alignment.Center,
    ) {
        PhoebeIconView(PhoebeIcon.Close, tint = PhoebeUi.secondaryText, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun AnnotationBody(annotation: LyricsAnnotation) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(
            annotation.fragment,
            color = PhoebeUi.accentLight,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            annotation.body,
            color = PhoebeUi.primaryText.copy(alpha = 0.88f),
            fontSize = 13.sp,
            lineHeight = 18.sp,
        )
        val metadata = annotation.metadataLabel()
        if (metadata.isNotBlank() || annotation.url != null) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (metadata.isNotBlank()) {
                    Text(
                        metadata,
                        color = PhoebeUi.secondaryText,
                        fontSize = 11.sp,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                annotation.url?.let { url ->
                    TextButton(onClick = { openExternalUrl(url) }) {
                        Text("Open", color = PhoebeUi.accentLight, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        Spacer(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(PhoebeUi.border.copy(alpha = 0.58f)),
        )
    }
}

private fun activeLyricsIndex(lines: List<LyricsLine>, positionMs: Long): Int =
    lines.indexOfLast { line ->
        val startMs = line.startMs
        startMs != null && startMs <= positionMs
    }

private fun annotationPanelTitle(selectedLineIndex: Int?): String =
    if (selectedLineIndex == UnmatchedAnnotationsSelection) "Unmatched annotations" else "Genius annotations"

private fun LyricsAnnotation.metadataLabel(): String =
    listOfNotNull(
        authorName,
        if (verified) "Verified" else null,
        votesTotal?.let { votes -> "$votes votes" },
    ).joinToString(" • ")

@Composable
private fun LyricsLoadingState() {
    Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = PhoebeUi.accentLight, modifier = Modifier.size(32.dp))
    }
}

@Composable
private fun LyricsEmptyState(message: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(240.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(PhoebeUi.panel)
            .border(BorderStroke(1.dp, PhoebeUi.border), RoundedCornerShape(14.dp))
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(message, color = PhoebeUi.secondaryText, fontSize = 16.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun LyricsRetryState(message: String, onRetry: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(PhoebeUi.panel)
            .border(BorderStroke(1.dp, PhoebeUi.border), RoundedCornerShape(14.dp))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(message, color = PhoebeUi.secondaryText, fontSize = 16.sp, textAlign = TextAlign.Center)
        Row(
            Modifier
                .clip(RoundedCornerShape(999.dp))
                .clickable(onClick = onRetry)
                .background(PhoebeUi.elevatedFill)
                .border(BorderStroke(1.dp, PhoebeUi.border), RoundedCornerShape(999.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PhoebeIconView(PhoebeIcon.Lyrics, tint = PhoebeUi.accentLight, modifier = Modifier.size(16.dp))
            Text("Retry", color = PhoebeUi.primaryText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(2.dp))
    }
}

private fun LyricsSource.label(): String = when (this) {
    LyricsSource.LocalEmbedded -> "Local tags"
    LyricsSource.LocalSidecar -> "Local file"
    LyricsSource.Lrclib -> "LRCLIB"
    LyricsSource.Cache -> "Cached"
}
