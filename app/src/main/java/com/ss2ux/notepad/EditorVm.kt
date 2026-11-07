package com.ss2ux.notepad

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.Charset

enum class SaveResult { OK, DENIED, FAILED }

/**
 * 편집 중인 글자는 화면의 EditText 가 직접 들고 있다(글이 길어도 빠르게 고치기 위해).
 * 여기에는 "파일에서 막 읽어 온 글"(docText)과 파일 정보만 두고, 화면이 새로 만들어질 때 되돌려 주는 데 쓴다.
 */
class EditorVm(app: Application) : AndroidViewModel(app) {

    var uri by mutableStateOf<Uri?>(null)
        private set
    var name by mutableStateOf(UNTITLED)
        private set
    var dirty by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    /** 한 번 보여 주고 지우는 안내 문구(토스트) */
    var message by mutableStateOf<String?>(null)

    /** 화면이 EditText 에 채워 넣을 글. 파일을 열 때와 화면이 사라질 때(stash) 갱신된다. */
    var docText: String = ""
        private set
    var docSelStart = 0
        private set
    var docSelEnd = 0
        private set
    var docScrollY = 0
        private set
    /** 새 파일을 읽을 때마다 올라간다. 화면은 이 값이 바뀌면 docText 를 다시 채운다. */
    var docVersion by mutableIntStateOf(0)
        private set

    private var editSeq = 0
    private var charset: Charset = Charsets.UTF_8
    private var bom = false
    private var eol = Eol.LF

    private val resolver get() = getApplication<Application>().contentResolver

    /** 사용자가 글을 고쳤을 때 화면이 부른다 */
    fun markEdited() {
        editSeq++
        if (!dirty) dirty = true
    }

    /** 화면이 사라지기 직전, 지금 글과 위치를 맡겨 둔다 */
    fun stash(text: String, selStart: Int, selEnd: Int, scrollY: Int) {
        docText = text
        docSelStart = selStart
        docSelEnd = selEnd
        docScrollY = scrollY
    }

    fun openFromIntent(u: Uri) {
        viewModelScope.launch { open(u) }
    }

    suspend fun open(u: Uri) {
        busy = true
        try {
            val d = withContext(Dispatchers.IO) {
                val stream = resolver.openInputStream(u) ?: throw IOException("파일을 열 수 없어요")
                val bytes = stream.use { s ->
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(64 * 1024)
                    var total = 0
                    while (true) {
                        val n = s.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) throw IOException("파일이 너무 커요 (8MB 이하만 열 수 있어요)")
                        out.write(buf, 0, n)
                    }
                    out.toByteArray()
                }
                TextCodec.decode(bytes)
            }
            charset = d.charset
            bom = d.bom
            eol = d.eol
            docText = d.text
            docSelStart = 0
            docSelEnd = 0
            docScrollY = 0
            editSeq++
            dirty = false
            uri = u
            name = queryName(u) ?: UNTITLED
            docVersion++
        } catch (e: Exception) {
            message = "열지 못했어요: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            busy = false
        }
    }

    /**
     * target 에 text 를 쓴다. makeCurrent 가 true 면(다른 이름으로 저장) 이후 이 파일이 현재 파일이 된다.
     * text 는 호출한 쪽이 EditText 에서 방금 꺼낸 글이어야 한다.
     */
    suspend fun saveTo(target: Uri, makeCurrent: Boolean, text: String): SaveResult {
        val seq = editSeq   // 저장하는 동안 더 고친 내용이 있으면 저장 후에도 "수정됨"으로 남긴다
        val cs = charset
        val useBom = bom
        val e = eol
        val enc: Encoded
        try {
            enc = withContext(Dispatchers.Default) { TextCodec.encode(text, e, cs, useBom) }
            withContext(Dispatchers.IO) {
                val out = resolver.openOutputStream(target, "wt") ?: throw IOException("파일을 열 수 없어요")
                out.use { it.write(enc.bytes) }
            }
        } catch (ex: SecurityException) {
            return SaveResult.DENIED
        } catch (ex: Exception) {
            message = "저장하지 못했어요: ${ex.message ?: ex.javaClass.simpleName}"
            return SaveResult.FAILED
        }
        val wasUtf8 = cs == Charsets.UTF_8
        charset = enc.charset
        bom = enc.bom
        if (makeCurrent) {
            uri = target
            name = queryName(target) ?: name
        }
        if (seq == editSeq) dirty = false
        message = if (enc.fellBack && !wasUtf8) "저장했어요 (글자가 맞지 않아 UTF-8로 저장)" else "저장했어요"
        return SaveResult.OK
    }

    private fun queryName(u: Uri): String? {
        var result: String? = null
        if (u.scheme == "content") {
            try {
                resolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && c.moveToFirst()) result = c.getString(idx)
                }
            } catch (e: Exception) {
                // 이름을 못 얻어도 편집에는 지장 없다
            }
        }
        return result ?: u.lastPathSegment?.substringAfterLast('/')
    }

    companion object {
        const val UNTITLED = "제목 없음.txt"
        private const val MAX_BYTES = 8 * 1024 * 1024
    }
}
