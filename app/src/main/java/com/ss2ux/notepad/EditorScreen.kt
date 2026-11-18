package com.ss2ux.notepad

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.DocumentsContract
import android.text.Editable
import android.text.Spanned
import android.text.TextWatcher
import android.view.inputmethod.BaseInputConnection
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FindReplace
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Redo
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.SaveAs
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.doOnLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
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

// ---- 보조 상태/함수 ------------------------------------------------------------

private const val MAX_MATCHES = 10_000       // 검색 결과 개수 상한(표시/이동용)
private const val MAX_HIGHLIGHTS = 1_500     // 한 번에 칠하는 결과 수 상한(화면에 보이는 부분만 칠한다)

/** 스크롤바가 읽는 값. EditText 가 바뀔 때마다 갱신한다. */
private class ScrollMetrics {
    var scrollY by mutableIntStateOf(0)
    var maxScroll by mutableIntStateOf(0)
}

private fun NotepadEditText.readMetrics(m: ScrollMetrics) {
    m.scrollY = scrollY
    m.maxScroll = maxScrollY()
}

/** 상태가 아닌 단순 보관용 */
private class EditorRefs {
    var loading = false          // 파일 내용을 채워 넣는 중이면 "사용자가 고쳤다"로 치지 않는다
    var applying = false         // 뒤로/앞으로를 적용하는 중: 고친 것으로는 치되, 새 기록으로 남기지는 않는다
    var pendingPaste = false     // 한 번에 여러 글자가 들어왔다(붙여넣기)
    var recStart = -1            // 바뀌기 직전의 글 조각(되돌리기 기록용). -1 이면 기록하지 않는 변경
    var recBefore = ""
    val spans = ArrayList<MatchSpan>()
    var jumpJob: Job? = null
}

private class Matches(val starts: List<Int>, val len: Int)
private val NO_MATCHES = Matches(emptyList(), 0)

private data class HlKey(val found: Matches, val selStart: Int, val selEnd: Int, val scrollY: Int)

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

/** q 가 모두 몇 번 나오는지(개수 제한 없이) */
private fun countAll(text: String, q: String): Int {
    if (q.isEmpty()) return 0
    var n = 0
    var from = 0
    while (true) {
        val i = text.indexOf(q, from, ignoreCase = true)
        if (i < 0) return n
        n++
        from = i + q.length
    }
}

private fun replaceAllText(t: String, q: String, with: String): Pair<String, Int> {
    if (q.isEmpty()) return t to 0
    val sb = StringBuilder(t.length)
    var from = 0
    var n = 0
    while (true) {
        val i = t.indexOf(q, from, ignoreCase = true)
        if (i < 0) break
        sb.append(t, from, i).append(with)
        from = i + q.length
        n++
    }
    if (n == 0) return t to 0
    sb.append(t, from, t.length)
    return sb.toString() to n
}

/** 정렬된 list 에서 v 이상인 첫 위치 */
private fun lowerBound(list: List<Int>, v: Int): Int {
    var lo = 0
    var hi = list.size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (list[mid] < v) lo = mid + 1 else hi = mid
    }
    return lo
}

/**
 * 검색 결과 중 "지금 화면에 보이는 부분"에 있는 것만 배경색을 칠한다(전체를 칠하면 긴 글에서 느려진다).
 * 현재 선택된 결과는 진한 색으로 칠한다.
 */
private fun applyHighlights(
    edit: NotepadEditText,
    spans: ArrayList<MatchSpan>,
    found: Matches,
    curStart: Int,
    curEnd: Int,
    normal: Int,
    strong: Int
) {
    val text = edit.text
    for (sp in spans) text.removeSpan(sp)
    spans.clear()
    val l = edit.layout ?: return
    if (found.starts.isEmpty() || found.len <= 0 || l.lineCount == 0) return
    val topLine = l.getLineForVertical(edit.scrollY)
    val botLine = l.getLineForVertical(edit.scrollY + edit.height)
    val from = (l.getLineStart(topLine) - found.len).coerceAtLeast(0)
    val to = l.getLineEnd(botLine)
    var i = lowerBound(found.starts, from)
    var n = 0
    while (i < found.starts.size && n < MAX_HIGHLIGHTS) {
        val s = found.starts[i]
        if (s >= to) break
        val e = s + found.len
        if (e <= text.length) {
            val sp = MatchSpan(if (s == curStart && e == curEnd) strong else normal)
            text.setSpan(sp, s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            spans.add(sp)
        }
        i++
        n++
    }
}

// ---- 화면 -----------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(vm: EditorVm, onExit: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val edit = remember(context) { NotepadEditText(context) }
    val refs = remember { EditorRefs() }
    val metrics = remember { ScrollMetrics() }
    var textVersion by remember { mutableIntStateOf(0) }
    var selStart by remember { mutableIntStateOf(0) }
    var selEnd by remember { mutableIntStateOf(0) }
    var found by remember { mutableStateOf(NO_MATCHES) }
    var trackPx by remember { mutableFloatStateOf(0f) }
    var pendingDiscard by remember { mutableStateOf<(() -> Unit)?>(null) }

    val searchFocus = remember { FocusRequester() }
    var searchOpen by remember { mutableStateOf(false) }
    var searchValue by remember { mutableStateOf(TextFieldValue("")) }
    var replaceOpen by remember { mutableStateOf(false) }
    var replaceValue by remember { mutableStateOf(TextFieldValue("")) }
    var pendingReplaceAll by remember { mutableStateOf<Int?>(null) }
    var reloadDialog by remember { mutableStateOf(false) }
    val query = searchValue.text

    // ---- DeepL / Google 번역 (선택 메뉴) ----
    // 1) PROCESS_TEXT 결과  2) 복사 후 복귀 시 클립보드
    // 클립보드는 번역 시작 전 스냅샷과 다를 때만 사용 (예전 복사본 오반영 방지)
    var translateRange by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var translateSource by remember { mutableStateOf<String?>(null) }
    var clipboardAtLaunch by remember { mutableStateOf<String?>(null) }

    fun readClipboard(): String? {
        return try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip ?: return null
            if (clip.itemCount <= 0) return null
            clip.getItemAt(0).coerceToText(context)?.toString()?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    /** 선택 구간에 번역문 넣고 커서 이동. 성공하면 true */
    fun applyTranslation(out: String, range: Pair<Int, Int>): Boolean {
        if (out.isBlank()) return false
        val len = edit.text.length
        val a = range.first.coerceIn(0, len)
        val b = range.second.coerceIn(a, len)
        edit.text.replace(a, b, out)
        val newEnd = (a + out.length).coerceAtMost(edit.text.length)
        edit.setSelection(newEnd)
        edit.revealOffset(newEnd, 0.7f)
        return true
    }

    /**
     * 번역 앱에서 돌아온 뒤 반영 시도.
     * PROCESS_TEXT 결과 → 없으면 클립보드(원문·시작 전 클립보드와 모두 다를 때만).
     */
    fun tryApplyTranslation(processTextResult: String?) {
        val range = translateRange ?: return
        val src = translateSource
        val snap = clipboardAtLaunch
        val fromResult = processTextResult?.takeIf { it.isNotBlank() }
        val fromClip = readClipboard()
        val out = when {
            !fromResult.isNullOrBlank() -> fromResult
            !fromClip.isNullOrBlank() && fromClip != src && fromClip != snap -> fromClip
            else -> null
        }
        if (out != null && applyTranslation(out, range)) {
            Toast.makeText(context, "번역문을 반영했어요", Toast.LENGTH_SHORT).show()
            translateRange = null
            translateSource = null
            clipboardAtLaunch = null
        }
        // 복사 없이 나오면 아무것도 안 함
    }

    val translateLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val out = result.data?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        // RESULT_OK 가 아니어도 DeepL에서 복사만 하고 돌아온 경우가 많음
        tryApplyTranslation(out)
        // 반영 못했으면 range 유지 (아래에서 resume 시 클립보드 재시도)
        if (translateRange != null && out.isNullOrBlank()) {
            // 한 번 더 클립보드만 확인
            tryApplyTranslation(null)
        }
    }

    fun startTranslate(engine: TranslateEngine, src: String, start: Int, end: Int) {
        if (src.isBlank()) {
            Toast.makeText(context, "번역할 글자가 없어요", Toast.LENGTH_SHORT).show()
            return
        }
        val pm = context.packageManager
        fun matches(ri: android.content.pm.ResolveInfo): Boolean {
            val pkg = ri.activityInfo.packageName
            val label = ri.loadLabel(pm).toString()
            return when (engine) {
                TranslateEngine.DEEPL ->
                    pkg.contains("deepl", true) || label.contains("deepl", true)
                TranslateEngine.GOOGLE ->
                    pkg.contains("google.android.apps.translate", true) ||
                        pkg.contains("google.translate", true) ||
                        (label.contains("Google", true) && label.contains("번역", true)) ||
                        (label.contains("Google", true) && label.contains("Translate", true)) ||
                        label.equals("Translate", true)
            }
        }
        val engineName = when (engine) {
            TranslateEngine.DEEPL -> "DeepL"
            TranslateEngine.GOOGLE -> "Google 번역"
        }
        try {
            translateRange = start to end
            translateSource = src
            clipboardAtLaunch = readClipboard() // 예전 복사본 스냅샷
            // 1순위: PROCESS_TEXT
            val pt = pm.queryIntentActivities(
                Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain"),
                PackageManager.MATCH_DEFAULT_ONLY
            ).firstOrNull { matches(it) }
            if (pt != null) {
                translateLauncher.launch(
                    Intent(Intent.ACTION_PROCESS_TEXT)
                        .setClassName(pt.activityInfo.packageName, pt.activityInfo.name)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_PROCESS_TEXT, src)
                        .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)
                )
                Toast.makeText(
                    context,
                    "번역 후 복사하거나 삽입하면 이 앱으로 돌아와 반영돼요",
                    Toast.LENGTH_SHORT
                ).show()
                return
            }
            // 2순위: 공유(SEND) — 돌아와 클립보드로 반영
            val sd = pm.queryIntentActivities(
                Intent(Intent.ACTION_SEND).setType("text/plain"),
                PackageManager.MATCH_DEFAULT_ONLY
            ).firstOrNull { matches(it) }
            if (sd != null) {
                context.startActivity(
                    Intent(Intent.ACTION_SEND)
                        .setClassName(sd.activityInfo.packageName, sd.activityInfo.name)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, src)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                Toast.makeText(
                    context,
                    "번역문을 복사한 뒤 이 앱으로 돌아오면 자동으로 반영해요",
                    Toast.LENGTH_LONG
                ).show()
                return
            }
            translateRange = null
            translateSource = null
            clipboardAtLaunch = null
            Toast.makeText(
                context,
                "$engineName 앱을 찾지 못했어요. 설치되어 있는지 확인해 주세요",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            translateRange = null
            translateSource = null
            clipboardAtLaunch = null
            Toast.makeText(context, "$engineName 을(를) 열지 못했어요", Toast.LENGTH_SHORT).show()
        }
    }

    // 최신 startTranslate 를 선택 메뉴 콜백에 연결 (Composable 재구성 후에도 최신 상태 사용)
    val translateHandler = rememberUpdatedState { engine: TranslateEngine, src: String, a: Int, b: Int ->
        startTranslate(engine, src, a, b)
    }

    // EditText 와 연결: 고쳤는지 알아채고, 스크롤바/선택 위치를 갱신하고, 화면이 사라질 때 글을 맡겨 둔다
    DisposableEffect(edit) {
        edit.onMetrics = { edit.readMetrics(metrics) }
        edit.onSelection = { s, e -> selStart = s; selEnd = e }
        edit.onTranslate = { engine, src, a, b -> translateHandler.value(engine, src, a, b) }
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                // 파일을 채우는 중이거나 뒤로/앞으로를 적용하는 중에는 기록하지 않는다(큰 글을 복사하지 않도록 먼저 거른다)
                refs.recStart = -1
                if (refs.loading || refs.applying || s == null) return
                refs.recStart = start
                refs.recBefore = s.subSequence(start, start + count).toString()
            }
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                // 한 번에 2글자 이상 들어오면 붙여넣기(또는 단어 자동완성)로 본다
                if (!refs.loading && !refs.applying && count >= 2) refs.pendingPaste = true
                if (refs.recStart == start && s != null) {
                    vm.history.record(start, refs.recBefore, s.subSequence(start, start + count).toString())
                }
                refs.recStart = -1
                refs.recBefore = ""
            }
            override fun afterTextChanged(s: Editable?) {
                if (refs.loading) return
                vm.markEdited()
                textVersion++
                edit.post {
                    edit.readMetrics(metrics)
                    // 붙여넣은 글의 끝(커서)이 화면 가장자리거나 밖이면 그쪽으로 화면을 옮긴다
                    if (refs.pendingPaste) {
                        refs.pendingPaste = false
                        edit.revealOffset(edit.selectionEnd, 0.7f)
                    }
                }
            }
        }
        edit.addTextChangedListener(watcher)
        onDispose {
            edit.removeTextChangedListener(watcher)
            edit.onMetrics = null
            edit.onSelection = null
            edit.onTranslate = null
            vm.stash(edit.text.toString(), edit.selectionStart, edit.selectionEnd, edit.scrollY)
        }
    }

    // 파일을 새로 읽었거나 화면이 새로 만들어졌을 때 EditText 에 글을 채운다
    LaunchedEffect(vm.docVersion) {
        refs.loading = true
        edit.setText(vm.docText)
        refs.loading = false
        val len = edit.text.length
        edit.setSelection(vm.docSelStart.coerceIn(0, len), vm.docSelEnd.coerceIn(0, len))
        val y = vm.docScrollY
        edit.doOnLayout {
            edit.scrollToY(y)
            edit.readMetrics(metrics)
        }
    }

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
        if (u != null) scope.launch { vm.saveTo(u, true, edit.text.toString()) }
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
            // 읽기 전용으로 열린 파일처럼 바로 쓸 수 없으면 안내 문구 없이 "다른 이름으로 저장" 창을 연다
            if (vm.saveTo(target, false, edit.text.toString()) == SaveResult.DENIED) saveAs()
        }
    }

    fun guardDiscard(action: () -> Unit) {
        if (vm.dirty) pendingDiscard = action else action()
    }

    /** 뒤로(undo=true) / 앞으로(undo=false). 기록에 남은 변경 한 덩어리를 거꾸로/그대로 적용한다. */
    fun applyHistory(undo: Boolean) {
        if (vm.busy) return
        val op = (if (undo) vm.history.takeUndo() else vm.history.takeRedo()) ?: return
        val removeLen = if (undo) op.after.length else op.before.length
        val insert = if (undo) op.before else op.after
        val t = edit.text
        if (op.start < 0 || op.start + removeLen > t.length) {
            // 기록과 글이 어긋난 경우(생기면 안 된다): 잘못 고치지 않도록 기록을 비우고 멈춘다
            vm.history.clear()
            vm.message = "기록이 맞지 않아 되돌리기 기록을 지웠어요"
            return
        }
        BaseInputConnection.removeComposingSpans(t)   // 한글 등 조합 중이던 글자를 확정
        refs.applying = true
        try {
            t.replace(op.start, op.start + removeLen, insert)
        } finally {
            refs.applying = false
        }
        val caret = (op.start + insert.length).coerceIn(0, edit.text.length)
        edit.requestFocus()
        edit.setSelection(caret)
        edit.post { edit.revealOffset(caret) }
    }

    // 화면 단축키(Ctrl+Z 등)와 길게 누르기 메뉴도 같은 기록을 쓰게 연결
    DisposableEffect(edit) {
        edit.onUndo = { applyHistory(true) }
        edit.onRedo = { applyHistory(false) }
        onDispose {
            edit.onUndo = null
            edit.onRedo = null
        }
    }

    /** 파일에서 다시 읽는다. 보던 위치(커서/스크롤)는 최대한 유지한다. */
    fun reload() {
        val u = vm.uri ?: return
        scope.launch {
            vm.open(
                u,
                edit.selectionStart.coerceAtLeast(0),
                edit.selectionEnd.coerceAtLeast(0),
                edit.scrollY
            )
        }
    }

    // 다른 앱에서 파일을 고치고 돌아왔는지 확인 + 번역 앱에서 복사 후 복귀 시 클립보드 반영
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        scope.launch {
            if (vm.checkExternalChange()) reloadDialog = true
        }
        // DeepL 등에서 복사만 하고 돌아온 경우: 선택 구간에 클립보드 반영
        if (translateRange != null) {
            tryApplyTranslation(null)
        }
    }

    // ---- 검색 -----------------------------------------------------------------

    // 결과 목록(칠하기/개수 표시용)은 글을 고친 뒤 잠깐 쉬었다가 다른 스레드에서 계산한다
    LaunchedEffect(searchOpen, query) {
        if (!searchOpen || query.isEmpty()) {
            found = NO_MATCHES
            return@LaunchedEffect
        }
        val q = query
        snapshotFlow { textVersion }.collectLatest {
            delay(200)
            val snap = edit.text.toString()
            val list = withContext(Dispatchers.Default) { findAll(snap, q) }
            found = Matches(list, q.length)
        }
    }

    // 화면에 보이는 부분의 결과만 칠한다 (스크롤/선택/결과가 바뀌면 잠깐 뒤에 다시)
    val hlNormal = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.35f).toArgb()
    val hlStrong = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f).toArgb()
    val hlColors by rememberUpdatedState(hlNormal to hlStrong)
    LaunchedEffect(edit) {
        snapshotFlow {
            HlKey(found, min(selStart, selEnd), max(selStart, selEnd), metrics.scrollY)
        }.collectLatest { k ->
            delay(80)
            applyHighlights(edit, refs.spans, k.found, k.selStart, k.selEnd, hlColors.first, hlColors.second)
        }
    }

    // 지금 선택된 부분이 검색 결과 중 몇 번째인지
    val curIdx by remember {
        derivedStateOf {
            val f = found
            val lo = min(selStart, selEnd)
            val hi = max(selStart, selEnd)
            if (f.starts.isNotEmpty() && hi - lo == f.len) {
                val k = lowerBound(f.starts, lo)
                if (k < f.starts.size && f.starts[k] == lo) k else -1
            } else -1
        }
    }

    fun goTo(start: Int, len: Int) {
        val n = edit.text.length
        edit.setSelection(start.coerceIn(0, n), (start + len).coerceIn(0, n))
        edit.revealOffset(start, 0.35f)
    }

    fun step(forward: Boolean) {
        if (query.isEmpty()) return
        val t = edit.text.toString()
        val lo = min(edit.selectionStart, edit.selectionEnd).coerceAtLeast(0)
        val hi = max(edit.selectionStart, edit.selectionEnd).coerceAtLeast(0)
        var i = when {
            forward -> t.indexOf(query, hi, ignoreCase = true)
            lo > 0 -> t.lastIndexOf(query, lo - 1, ignoreCase = true)
            else -> -1
        }
        if (i < 0) {
            i = if (forward) t.indexOf(query, 0, ignoreCase = true)
            else t.lastIndexOf(query, t.length, ignoreCase = true)
        }
        if (i >= 0) goTo(i, query.length)
    }

    fun onQueryChange(v: TextFieldValue) {
        searchValue = v
        refs.jumpJob?.cancel()
        val q = v.text
        if (q.isEmpty()) return
        // 입력이 멈추면 현재 위치 이후의 첫 결과로 이동
        refs.jumpJob = scope.launch {
            delay(150)
            val snap = edit.text.toString()
            val from = min(edit.selectionStart, edit.selectionEnd).coerceAtLeast(0)
            val i = withContext(Dispatchers.Default) {
                val k = snap.indexOf(q, from, ignoreCase = true)
                if (k >= 0) k else snap.indexOf(q, 0, ignoreCase = true)
            }
            if (i >= 0) goTo(i, q.length)
        }
    }

    /** 지금 선택된 게 검색 결과면 바꾸고 다음 결과로, 아니면 다음 결과로 이동만 한다 */
    fun replaceOne() {
        if (vm.busy || query.isEmpty()) return
        val lo = min(edit.selectionStart, edit.selectionEnd).coerceAtLeast(0)
        val hi = max(edit.selectionStart, edit.selectionEnd).coerceAtLeast(0)
        val onMatch = hi - lo == query.length &&
            edit.text.subSequence(lo, hi).toString().equals(query, ignoreCase = true)
        if (!onMatch) {
            step(true)
            return
        }
        val repl = replaceValue.text
        vm.history.breakMerge()          // 연속으로 눌러도 바꾼 곳마다 한 칸씩
        edit.text.replace(lo, hi, repl)
        vm.history.breakMerge()
        val after = lo + repl.length
        val t = edit.text.toString()
        var i = t.indexOf(query, after, ignoreCase = true)
        if (i < 0) i = t.indexOf(query, 0, ignoreCase = true)
        if (i >= 0) goTo(i, query.length) else edit.setSelection(after.coerceAtMost(t.length))
    }

    fun requestReplaceAll() {
        if (vm.busy || query.isEmpty()) return
        val q = query
        scope.launch {
            val snap = edit.text.toString()
            val n = withContext(Dispatchers.Default) { countAll(snap, q) }
            if (n == 0) vm.message = "바꿀 글자가 없어요" else pendingReplaceAll = n
        }
    }

    fun doReplaceAll() {
        val q = query
        val r = replaceValue.text
        scope.launch {
            val snap = edit.text.toString()
            val ver = textVersion
            val (nt, n) = withContext(Dispatchers.Default) { replaceAllText(snap, q, r) }
            if (n == 0) {
                vm.message = "바꿀 글자가 없어요"
                return@launch
            }
            if (ver != textVersion) {
                vm.message = "바꾸는 동안 글이 바뀌어 취소했어요"
                return@launch
            }
            val sy = edit.scrollY
            val sel = min(edit.selectionStart, edit.selectionEnd).coerceAtLeast(0)
            refs.loading = true
            edit.setText(nt)
            refs.loading = false
            vm.history.record(0, snap, nt, merge = false)   // 모두 바꾸기 전체가 한 칸 (되돌리기 가능)
            edit.setSelection(sel.coerceAtMost(nt.length))
            vm.markEdited()
            textVersion++
            edit.doOnLayout { edit.scrollToY(sy) }
            vm.message = "${n}곳을 바꿨어요"
        }
    }

    fun openSearch() {
        val lo = min(edit.selectionStart, edit.selectionEnd)
        val hi = max(edit.selectionStart, edit.selectionEnd)
        if (hi - lo in 1..100) {
            val sub = edit.text.subSequence(lo, hi).toString()
            if ('\n' !in sub) searchValue = TextFieldValue(sub, TextRange(sub.length))
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

    val countLabel = when {
        query.isEmpty() -> ""
        found.starts.isEmpty() -> "결과 없음"
        else -> {
            val total = if (found.starts.size >= MAX_MATCHES) "$MAX_MATCHES+" else "${found.starts.size}"
            if (curIdx >= 0) "${curIdx + 1}/$total" else "${total}개"
        }
    }

    BackHandler(enabled = vm.dirty) { pendingDiscard = onExit }
    BackHandler(enabled = searchOpen) { closeSearch() }

    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val accentColor = MaterialTheme.colorScheme.primary.toArgb()

    Scaffold(
        modifier = Modifier.fillMaxSize().imePadding(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = (if (vm.dirty || vm.externalChange) "* " else "") + vm.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = { guardDiscard { openLauncher.launch(arrayOf("*/*")) } },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(Icons.Outlined.FolderOpen, contentDescription = "파일 열기")
                    }
                }
            )
        },
        bottomBar = {
            BottomBar(
                canUndo = vm.history.canUndo,
                canRedo = vm.history.canRedo,
                canReload = vm.uri != null && !vm.busy,
                reloadAccent = vm.externalChange,
                searchOpen = searchOpen,
                onUndo = { applyHistory(true) },
                onRedo = { applyHistory(false) },
                onReload = { reloadDialog = true },
                onSearch = { if (searchOpen) closeSearch() else openSearch() },
                onSelectAll = {
                    edit.requestFocus()
                    edit.selectAll()
                },
                onSave = { save() },
                onSaveAs = { saveAs() }
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
                    focus = searchFocus,
                    replaceOpen = replaceOpen,
                    onToggleReplace = { replaceOpen = !replaceOpen },
                    replaceValue = replaceValue,
                    onReplaceChange = { replaceValue = it },
                    canReplace = query.isNotEmpty() && !vm.busy,
                    onReplaceOne = { replaceOne() },
                    onReplaceAll = { requestReplaceAll() }
                )
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onSizeChanged { trackPx = it.height.toFloat() }
            ) {
                AndroidView(
                    factory = { edit },
                    update = { v ->
                        v.isEnabled = !vm.busy
                        v.setTextColor(textColor)
                        v.applyAccent(accentColor)
                    },
                    modifier = Modifier.fillMaxSize()
                )
                VerticalScrollbar(metrics, trackPx) { y -> edit.scrollToY(y) }
            }
        }
    }

    pendingReplaceAll?.let { n ->
        AlertDialog(
            onDismissRequest = { pendingReplaceAll = null },
            title = { Text("모두 바꾸기") },
            text = { Text("\"$query\" → \"${replaceValue.text}\"\n${n}곳을 바꿀까요? (저장하기 전까지는 파일에 반영되지 않아요)") },
            confirmButton = {
                TextButton(onClick = {
                    pendingReplaceAll = null
                    doReplaceAll()
                }) { Text("바꾸기") }
            },
            dismissButton = {
                TextButton(onClick = { pendingReplaceAll = null }) { Text("취소") }
            }
        )
    }

    if (reloadDialog) {
        AlertDialog(
            onDismissRequest = { reloadDialog = false },
            title = { Text(if (vm.externalChange) "다른 앱에서 파일이 수정됐어요" else "파일 새로고침") },
            text = {
                Text(
                    buildString {
                        append(if (vm.externalChange) "변경된 내용을 다시 불러올까요?" else "파일에서 다시 불러올까요?")
                        if (vm.dirty) append("\n저장하지 않은 수정 내용은 사라져요.")
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = { reloadDialog = false; reload() }) { Text("불러오기") }
            },
            dismissButton = {
                TextButton(onClick = { reloadDialog = false }) { Text("나중에") }
            }
        )
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

/** 하단 도구줄: 뒤로 / 앞으로 / 새로고침 / 검색 / 전체 선택 / 저장 / 다른 이름으로 저장 */
@Composable
private fun BottomBar(
    canUndo: Boolean,
    canRedo: Boolean,
    canReload: Boolean,
    reloadAccent: Boolean,
    searchOpen: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onReload: () -> Unit,
    onSearch: () -> Unit,
    onSelectAll: () -> Unit,
    onSave: () -> Unit,
    onSaveAs: () -> Unit
) {
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        // 배경은 시스템 내비게이션 바 뒤까지 깔고, 버튼은 그 위에 둔다
        Row(
            Modifier.navigationBarsPadding().fillMaxWidth().height(52.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BarButton(Icons.Outlined.Undo, "뒤로", enabled = canUndo, onClick = onUndo)
            BarButton(Icons.Outlined.Redo, "앞으로", enabled = canRedo, onClick = onRedo)
            BarButton(Icons.Outlined.Refresh, "파일에서 다시 불러오기", enabled = canReload, accent = reloadAccent, onClick = onReload)
            BarButton(Icons.Outlined.Search, "검색", accent = searchOpen, onClick = onSearch)
            BarButton(Icons.Outlined.SelectAll, "전체 선택", onClick = onSelectAll)
            BarButton(Icons.Outlined.Save, "저장", onClick = onSave)
            BarButton(Icons.Outlined.SaveAs, "다른 이름으로 저장", onClick = onSaveAs)
        }
    }
}

@Composable
private fun RowScope.BarButton(
    icon: ImageVector,
    description: String,
    enabled: Boolean = true,
    accent: Boolean = false,
    onClick: () -> Unit
) {
    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
        IconButton(onClick = onClick, enabled = enabled) {
            Icon(
                icon,
                contentDescription = description,
                // 꺼진 버튼은 IconButton 이 LocalContentColor 를 흐리게 해 준다
                tint = if (accent && enabled) MaterialTheme.colorScheme.primary else LocalContentColor.current
            )
        }
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
    focus: FocusRequester,
    replaceOpen: Boolean,
    onToggleReplace: () -> Unit,
    replaceValue: TextFieldValue,
    onReplaceChange: (TextFieldValue) -> Unit,
    canReplace: Boolean,
    onReplaceOne: () -> Unit,
    onReplaceAll: () -> Unit
) {
    val fieldStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp)
    Surface(tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().height(52.dp).padding(start = 4.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onToggleReplace, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Outlined.FindReplace,
                        contentDescription = "바꾸기 열기/닫기",
                        tint = if (replaceOpen) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Box(Modifier.weight(1f).padding(start = 4.dp), contentAlignment = Alignment.CenterStart) {
                    if (value.text.isEmpty()) {
                        Text("검색", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp)
                    }
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        singleLine = true,
                        textStyle = fieldStyle,
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
                IconButton(onClick = onPrev, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = "이전 결과")
                }
                IconButton(onClick = onNext, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "다음 결과")
                }
                IconButton(onClick = onClose, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Outlined.Close, contentDescription = "검색 닫기")
                }
            }
            if (replaceOpen) {
                Row(
                    Modifier.fillMaxWidth().height(48.dp).padding(start = 48.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.weight(1f).padding(start = 4.dp), contentAlignment = Alignment.CenterStart) {
                        if (replaceValue.text.isEmpty()) {
                            Text(
                                "바꿀 내용 (비우면 삭제)",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 16.sp
                            )
                        }
                        BasicTextField(
                            value = replaceValue,
                            onValueChange = onReplaceChange,
                            singleLine = true,
                            textStyle = fieldStyle,
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    TextButton(onClick = onReplaceOne, enabled = canReplace) { Text("바꾸기") }
                    TextButton(onClick = onReplaceAll, enabled = canReplace) { Text("모두 바꾸기") }
                }
            }
        }
    }
}

/**
 * 오른쪽 가장자리 스크롤바. 문서가 화면보다 길 때만 나타난다.
 * 평소엔 흐리게, 스크롤하거나 잡고 끄는 동안엔 진하게 보인다. 막대를 잡고 끌면 빠르게 이동할 수 있다.
 */
@Composable
private fun BoxScope.VerticalScrollbar(m: ScrollMetrics, trackPx: Float, onScrollTo: (Int) -> Unit) {
    val density = LocalDensity.current
    var dragging by remember { mutableStateOf(false) }
    var recentlyScrolled by remember { mutableStateOf(false) }
    LaunchedEffect(m) {
        snapshotFlow { m.scrollY }.collectLatest {
            recentlyScrolled = true
            delay(1200)
            recentlyScrolled = false
        }
    }
    val active = dragging || recentlyScrolled
    val alpha by animateFloatAsState(if (active) 0.8f else 0.3f, label = "scrollbarAlpha")
    val barWidth by animateDpAsState(if (dragging) 10.dp else 5.dp, label = "scrollbarWidth")

    val maxScroll = m.maxScroll
    val minThumb = with(density) { 48.dp.toPx() }
    val thumbH = if (trackPx > 0f) {
        (trackPx * trackPx / (trackPx + maxScroll)).coerceIn(min(minThumb, trackPx), trackPx)
    } else 0f
    val track = trackPx - thumbH
    val maxNow by rememberUpdatedState(maxScroll)
    val trackNow by rememberUpdatedState(track)
    val scrollTo by rememberUpdatedState(onScrollTo)

    if (maxScroll <= 0 || trackPx <= 0f) return

    Box(
        Modifier
            .align(Alignment.TopEnd)
            .offset {
                val y = if (maxNow > 0) trackNow * m.scrollY / maxNow else 0f
                IntOffset(0, y.roundToInt())
            }
            .width(24.dp)
            .height(with(density) { thumbH.toDp() })
            .pointerInput(Unit) {
                var startScroll = 0
                var total = 0f
                detectVerticalDragGestures(
                    onDragStart = {
                        dragging = true
                        startScroll = m.scrollY
                        total = 0f
                    },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false }
                ) { change, dy ->
                    change.consume()
                    total += dy
                    if (trackNow > 0f) scrollTo((startScroll + total * maxNow / trackNow).roundToInt())
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
