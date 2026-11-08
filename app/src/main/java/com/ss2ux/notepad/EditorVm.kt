package com.ss2ux.notepad

import android.app.Application
import android.content.Intent
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
import java.util.zip.CRC32

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
    /** 우리가 마지막으로 읽거나 쓴 뒤 다른 앱이 파일을 바꿨다 */
    var externalChange by mutableStateOf(false)
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
    private var diskSig = 0L      // 마지막으로 읽거나 쓴 파일 내용의 지문(CRC32 + 크기)
    private var saving = false
    private var charset: Charset = Charsets.UTF_8
    private var bom = false
    private var eol = Eol.LF

    private val resolver get() = getApplication<Application>().contentResolver

    /**
     * SAF URI에 대해 읽기+쓰기 영구 권한을 가져온다.
     * 외부 앱에서 넘어온 URI 등 권한이 없는 경우는 조용히 무시한다.
     */
    private fun takePersistable(u: Uri) {
        try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            resolver.takePersistableUriPermission(u, flags)
        } catch (_: SecurityException) {
            // 권한을 줄 수 없는 URI (다른 앱에서 VIEW/EDIT으로 넘어온 경우 등)
        } catch (_: Exception) {
            // 일부 프로바이더는 지원하지 않음
        }
    }

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

    /** 파일 전체를 읽는다(8MB 상한) */
    private fun readCapped(u: Uri): ByteArray {
        val stream = resolver.openInputStream(u) ?: throw IOException("파일을 열 수 없어요")
        return stream.use { s ->
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
    }

    private fun sig(bytes: ByteArray): Long {
        val c = CRC32()
        c.update(bytes, 0, bytes.size)
        return c.value xor (bytes.size.toLong() shl 32)
    }

    /** selStart/selEnd/scrollY: 다시 불러올 때 보던 위치를 유지하기 위한 값(새로 열 때는 0) */
    suspend fun open(u: Uri, selStart: Int = 0, selEnd: Int = 0, scrollY: Int = 0) {
        busy = true
        try {
            val (d, newSig) = withContext(Dispatchers.IO) {
                val bytes = readCapped(u)
                TextCodec.decode(bytes) to sig(bytes)
            }
            charset = d.charset
            bom = d.bom
            eol = d.eol
            docText = d.text
            docSelStart = selStart
            docSelEnd = selEnd
            docScrollY = scrollY
            editSeq++
            dirty = false
            diskSig = newSig
            externalChange = false
            uri = u
            name = queryName(u) ?: UNTITLED
            takePersistable(u)   // 이후 바로 저장이 가능하도록 영구 쓰기 권한 확보
            docVersion++
        } catch (e: Exception) {
            message = "열지 못했어요: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            busy = false
        }
    }

    /**
     * 파일이 마지막으로 읽거나 쓴 뒤 다른 앱에서 바뀌었는지 확인한다.
     * 새로 바뀐 것을 알아챘으면 true. (파일 제공 앱마다 수정 시각이 믿을 만하지 않아서 내용 지문으로 비교한다)
     */
    suspend fun checkExternalChange(): Boolean {
        val u = uri ?: return false
        if (busy || saving || externalChange) return false
        val now = try {
            withContext(Dispatchers.IO) { sig(readCapped(u)) }
        } catch (e: Exception) {
            return false   // 못 읽는 경우(권한/삭제 등)는 조용히 넘어간다
        }
        if (saving || now == diskSig) return false
        externalChange = true
        return true
    }

    /**
     * target 에 text 를 쓴다. makeCurrent 가 true 면(다른 이름으로 저장) 이후 이 파일이 현재 파일이 된다.
     * text 는 호출한 쪽이 EditText 에서 방금 꺼낸 글이어야 한다.
     */
    suspend fun saveTo(target: Uri, makeCurrent: Boolean, text: String): SaveResult {
        val seq = editSeq   // 저장하는 동안 더 고친 내용이 있으면 저장 후에도 "수정됨"으로 남긴다
        saving = true
        val cs = charset
        val useBom = bom
        val e = eol
        val enc: Encoded
        val newSig: Long
        try {
            enc = withContext(Dispatchers.Default) { TextCodec.encode(text, e, cs, useBom) }
            newSig = withContext(Dispatchers.IO) {
                val out = resolver.openOutputStream(target, "wt") ?: throw IOException("파일을 열 수 없어요")
                out.use { it.write(enc.bytes) }
                sig(enc.bytes)
            }
        } catch (ex: SecurityException) {
            saving = false
            return SaveResult.DENIED
        } catch (ex: Exception) {
            saving = false
            val m = ex.message ?: ""
            if (ex is java.io.FileNotFoundException &&
                (m.contains("EACCES") || m.contains("EPERM") || m.contains("Permission denied", ignoreCase = true))
            ) {
                return SaveResult.DENIED
            }
            message = "저장하지 못했어요: ${ex.message ?: ex.javaClass.simpleName}"
            return SaveResult.FAILED
        }
        saving = false
        diskSig = newSig
        externalChange = false
        val wasUtf8 = cs == Charsets.UTF_8
        charset = enc.charset
        bom = enc.bom
        if (makeCurrent) {
            uri = target
            name = queryName(target) ?: name
            takePersistable(target)   // 다른 이름으로 저장한 파일도 이후 바로 저장 가능하게
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
