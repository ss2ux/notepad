package com.ss2ux.notepad

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.SaveAs
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 파일 선택기: 읽기뿐 아니라 쓰기 권한(덮어쓰기용)도 요청하는 OpenDocument */
class OpenDocumentRw : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        ).putExtra(
            DocumentsContract.EXTRA_INITIAL_URI,
            Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload")
        )
}

/** 글자 칸의 마지막 레이아웃 결과와, 붙여넣기 직후 화면을 옮겨야 하는지 여부 */
private class EditorRefs {
    var layout: TextLayoutResult? = null
    var pendingReveal = false
}

private const val MAX_MATCHES = 10_000

/** q 가 나오는 모든 위치(겹치지 않게, 대소문자 구분 없이). 너무 많으면 MAX_MATCHES 개에서 멈춘다. */
private fun findAll(text: String, q: String): List<Int> {
    if (q.isEmpty()) return emptyList()
    val out = ArrayList<Int>()
    var from = 0
    while (out.size < MAX_MATCHES) {
        val i = text.indexOf(q, from, ignoreCase = true)
        if (i < 0) break
        out.add(i)
        from = i + q.length
    }
    return out
}

/** 검색 결과를 배경색으로 칠한다. 글자 수는 그대로라 커서/선택 위치 계산은 영향 없음. */
private class MatchHighlight(
    private val starts: List<Int>,
    private val len: Int,
    private val current: Int,
    private val normal: Color,
    private val strong: Color
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val b = AnnotatedString.Builder(text)
        for (i in starts.indices) {
            val s = starts[i]
            val e = s + len
            if (e > text.length) break
            b.addStyle(SpanStyle(background = if (i == current) strong else normal), s, e)
        }
        return TransformedText(b.toAnnotatedString(), OffsetMapping.Identity)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(vm: EditorVm, onExit: () -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val searchFocus = remember { FocusRequester() }
    val scrollState = rememberScrollState()
    val refs = remember { EditorRefs() }
    var viewportPx by remember { mutableFloatStateOf(0f) }
    var pendingDiscard by remember { mutableStateOf<(() -> Unit)?>(null) }

    var searchOpen by remember { mutableStateOf(false) }
    var searchValue by remember { mutableStateOf(TextFieldValue("")) }
    val query = searchValue.text

    // 안내 문구는 토스트로 한 번만 보여 준다
    LaunchedEffect(vm.message) {
        vm.message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            vm.message = null
        }
    }

    val openLauncher = rememberLauncherForActivityResult(OpenDocumentRw()) { u ->
        if (u != null) scope.launch { vm.open(u) }
    }
    // octet-stream 으로 만들어야 "이름.vtt" 뒤에 ".txt" 가 따라 붙지 않는다
    val saveAsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { u ->
        if (u != null) scope.launch { vm.saveTo(u, true) }
    }

    fun saveAs() {
        saveAsLauncher.launch(vm.name)
    }

    fun save() {
        val target = vm.uri
        if (target == null) {
            saveAs()
            return
        }
        scope.launch {
            if (vm.saveTo(target, false) == SaveResult.DENIED) {
                Toast.makeText(
                    context, "이 파일에는 바로 저장할 수 없어요. 다른 이름으로 저장해 주세요.", Toast.LENGTH_LONG
                ).show()
                saveAs()
            }
        }
    }

    fun guardDiscard(action: () -> Unit) {
        if (vm.dirty) pendingDiscard = action else action()
    }

    // ---- 화면 이동 ------------------------------------------------------------
    val padPx = with(density) { 16.dp.toPx() }      // 글자 칸 위쪽 여백(스크롤 내용 안쪽)
    val marginPx = with(density) { 48.dp.toPx() }   // 화면 가장자리에서 이만큼 안쪽이면 "보인다"고 본다

    /**
     * offset 위치(커서/검색 결과)가 화면에 편하게 보이지 않으면 스크롤해서 보이게 한다.
     * anchor: 이동할 때 그 줄이 화면 높이의 어디쯤(0=맨 위, 1=맨 아래)에 오게 할지.
     * afterLayout: 글자가 방금 바뀐 경우, 새 높이가 스크롤에 반영될 때까지 두 프레임 기다린다.
     */
    fun reveal(offset: Int, anchor: Float, afterLayout: Boolean = false) {
        scope.launch {
            if (afterLayout) {
                withFrameNanos { }
                withFrameNanos { }
            }
            val l = refs.layout ?: return@launch
            val vp = viewportPx
            if (vp <= 0f) return@launch
            val o = offset.coerceIn(0, l.layoutInput.text.length)
            val rect = l.getCursorRect(o)
            val top = rect.top + padPx
            val bottom = rect.bottom + padPx
            val viewTop = scrollState.value.toFloat()
            val margin = minOf(marginPx, vp / 4f)
            if (top < viewTop + margin || bottom > viewTop + vp - margin) {
                val target = (top - vp * anchor).roundToInt().coerceIn(0, scrollState.maxValue)
                scrollState.scrollTo(target)
            }
        }
    }

    // ---- 검색 -----------------------------------------------------------------
    val text = vm.value.text
    val matches = remember(text, query, searchOpen) {
        if (searchOpen) findAll(text, query) else emptyList()
    }
    val sel = vm.value.selection
    // 지금 선택된 부분이 검색 결과 중 하나면 그게 "현재 결과"
    val curIdx = if (matches.isNotEmpty() && sel.max - sel.min == query.length) {
        matches.indexOf(sel.min)
    } else -1

    fun goTo(list: List<Int>, idx: Int, len: Int) {
        val s = list[idx]
        vm.select(s, s + len)
        reveal(s, 0.35f)
    }

    fun step(forward: Boolean) {
        if (matches.isEmpty()) return
        val s = vm.value.selection
        val idx = if (forward) {
            matches.indexOfFirst { it >= s.max }.let { if (it < 0) 0 else it }
        } else {
            matches.indexOfLast { it < s.min }.let { if (it < 0) matches.lastIndex else it }
        }
        goTo(matches, idx, query.length)
    }

    fun onQueryChange(v: TextFieldValue) {
        searchValue = v
        val q = v.text
        if (q.isEmpty()) return
        val m = findAll(vm.value.text, q)
        if (m.isEmpty()) return
        val from = vm.value.selection.min
        val idx = m.indexOfFirst { it >= from }.let { if (it < 0) 0 else it }
        goTo(m, idx, q.length)
    }

    fun openSearch() {
        val s = vm.value.selection
        val sub = if (!s.collapsed && s.max - s.min <= 100) vm.value.text.substring(s.min, s.max) else ""
        if (sub.isNotEmpty() && '\n' !in sub) {
            searchValue = TextFieldValue(sub, TextRange(sub.length))
        } else if (searchValue.text.isNotEmpty()) {
            searchValue = searchValue.copy(selection = TextRange(0, searchValue.text.length))
        }
        searchOpen = true
    }

    fun closeSearch() {
        searchOpen = false
    }

    LaunchedEffect(searchOpen) {
        if (searchOpen) searchFocus.requestFocus()
    }

    val highlightNormal = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.35f)
    val highlightStrong = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
    val highlight = remember(matches, query.length, curIdx, highlightNormal, highlightStrong) {
        if (matches.isEmpty()) VisualTransformation.None
        else MatchHighlight(matches, query.length, curIdx, highlightNormal, highlightStrong)
    }

    val countLabel = when {
        query.isEmpty() -> ""
        matches.isEmpty() -> "결과 없음"
        else -> {
            val total = if (matches.size >= MAX_MATCHES) "$MAX_MATCHES+" else "${matches.size}"
            if (curIdx >= 0) "${curIdx + 1}/$total" else "${total}개"
        }
    }

    BackHandler(enabled = vm.dirty) { pendingDiscard = onExit }
    BackHandler(enabled = searchOpen) { closeSearch() }

    Scaffold(
        modifier = Modifier.fillMaxSize().imePadding(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = (if (vm.dirty) "* " else "") + vm.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { guardDiscard { openLauncher.launch(arrayOf("*/*")) } }) {
                        Icon(Icons.Outlined.FolderOpen, contentDescription = "파일 열기")
                    }
                },
                actions = {
                    IconButton(onClick = { if (searchOpen) closeSearch() else openSearch() }) {
                        Icon(Icons.Outlined.Search, contentDescription = "검색")
                    }
                    IconButton(onClick = {
                        vm.selectAll()
                        focus.requestFocus()
                    }) {
                        Icon(Icons.Outlined.SelectAll, contentDescription = "전체 선택")
                    }
                    IconButton(onClick = { save() }) {
                        Icon(Icons.Outlined.Save, contentDescription = "저장")
                    }
                    IconButton(onClick = { saveAs() }) {
                        Icon(Icons.Outlined.SaveAs, contentDescription = "다른 이름으로 저장")
                    }
                }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (searchOpen) {
                SearchBar(
                    value = searchValue,
                    onValueChange = ::onQueryChange,
                    countLabel = countLabel,
                    onPrev = { step(false) },
                    onNext = { step(true) },
                    onClose = { closeSearch() },
                    focus = searchFocus
                )
            }
            BoxWithConstraints(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onSizeChanged { viewportPx = it.height.toFloat() }
            ) {
                val minH = (maxHeight - 32.dp).coerceAtLeast(0.dp)
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .padding(16.dp)
                ) {
                    BasicTextField(
                        value = vm.value,
                        onValueChange = { nv ->
                            val old = vm.value
                            vm.onChange(nv)
                            // 한 번에 2글자 이상 들어왔고 커서가 한 곳에 있으면 붙여넣기(또는 단어 자동완성)로 보고,
                            // 새 레이아웃이 나오면 커서(=붙여넣은 글의 끝) 쪽으로 화면을 옮긴다
                            if (nv.text != old.text && nv.selection.collapsed) {
                                val inserted = nv.selection.start - old.selection.min
                                if (inserted >= 2) refs.pendingReveal = true
                            }
                        },
                        readOnly = vm.busy,
                        visualTransformation = highlight,
                        onTextLayout = { l ->
                            refs.layout = l
                            if (refs.pendingReveal) {
                                refs.pendingReveal = false
                                reveal(vm.value.selection.end, 0.7f, afterLayout = true)
                            }
                        },
                        textStyle = TextStyle(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 16.sp,
                            lineHeight = 24.sp
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = minH)
                            .focusRequester(focus)
                    )
                }
                VerticalScrollbar(scrollState, viewportPx)
            }
        }
    }

    pendingDiscard?.let { action ->
        AlertDialog(
            onDismissRequest = { pendingDiscard = null },
            title = { Text("저장하지 않은 변경 사항") },
            text = { Text("저장하지 않고 계속하면 수정한 내용이 사라져요.") },
            confirmButton = {
                TextButton(onClick = { pendingDiscard = null; action() }) { Text("버리고 계속") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDiscard = null }) { Text("취소") }
            }
        )
    }
}

@Composable
private fun SearchBar(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    countLabel: String,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
    focus: FocusRequester
) {
    Surface(tonalElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (value.text.isEmpty()) {
                    Text(
                        "검색",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 16.sp
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = TextStyle(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 16.sp
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onNext() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus)
                )
            }
            if (countLabel.isNotEmpty()) {
                Text(
                    countLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
            IconButton(onClick = onPrev) {
                Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = "이전 결과")
            }
            IconButton(onClick = onNext) {
                Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "다음 결과")
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Outlined.Close, contentDescription = "검색 닫기")
            }
        }
    }
}

/**
 * 오른쪽 가장자리 스크롤바. 문서가 화면보다 길 때만 나타난다.
 * 평소엔 흐리게, 스크롤하거나 잡고 끄는 동안엔 진하게 보인다. 막대를 잡고 끌면 빠르게 이동할 수 있다.
 */
@Composable
private fun BoxScope.VerticalScrollbar(state: ScrollState, viewportPx: Float) {
    val density = LocalDensity.current
    var dragging by remember { mutableStateOf(false) }
    var recentlyScrolled by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        snapshotFlow { state.value }.collectLatest {
            recentlyScrolled = true
            delay(1200)
            recentlyScrolled = false
        }
    }
    val active = dragging || recentlyScrolled
    val alpha by animateFloatAsState(if (active) 0.8f else 0.3f, label = "scrollbarAlpha")
    val barWidth by animateDpAsState(if (dragging) 10.dp else 5.dp, label = "scrollbarWidth")

    val max = state.maxValue
    if (max <= 0 || viewportPx <= 0f) return

    val minThumb = with(density) { 48.dp.toPx() }
    val thumbH = (viewportPx * viewportPx / (viewportPx + max)).coerceIn(minThumb, viewportPx)
    val track = viewportPx - thumbH
    val maxNow by rememberUpdatedState(max)
    val trackNow by rememberUpdatedState(track)

    Box(
        Modifier
            .align(Alignment.TopEnd)
            .offset { IntOffset(0, (track * state.value / max).roundToInt()) }
            .width(24.dp)
            .height(with(density) { thumbH.toDp() })
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { dragging = true },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false }
                ) { change, dy ->
                    change.consume()
                    if (trackNow > 0f) state.dispatchRawDelta(dy * maxNow / trackNow)
                }
            },
        contentAlignment = Alignment.CenterEnd
    ) {
        Box(
            Modifier
                .padding(end = 2.dp)
                .width(barWidth)
                .fillMaxHeight()
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
        )
    }
}
