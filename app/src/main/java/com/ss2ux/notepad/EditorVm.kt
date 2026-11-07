package com.ss2ux.notepad

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.Charset

enum class SaveResult { OK, DENIED, FAILED }

class EditorVm(app: Application) : AndroidViewModel(app) {

    var value by mutableStateOf(TextFieldValue(""))
        private set
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

    private var savedText = ""
    private var charset: Charset = Charsets.UTF_8
    private var bom = false
    private var eol = Eol.LF

    private val resolver get() = getApplication<Application>().contentResolver

    fun onChange(v: TextFieldValue) {
        val textChanged = v.text != value.text
        value = v
        if (textChanged) dirty = v.text != savedText
    }

    fun selectAll() {
        value = value.copy(selection = TextRange(0, value.text.length))
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
            savedText = d.text
            value = TextFieldValue(d.text)
            dirty = false
            uri = u
            name = queryName(u) ?: UNTITLED
        } catch (e: Exception) {
            message = "열지 못했어요: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            busy = false
        }
    }

    /** target 에 현재 텍스트를 쓴다. makeCurrent 가 true 면(다른 이름으로 저장) 이후 이 파일이 현재 파일이 된다. */
    suspend fun saveTo(target: Uri, makeCurrent: Boolean): SaveResult {
        val text = value.text
        val enc = TextCodec.encode(text, eol, charset, bom)
        try {
            withContext(Dispatchers.IO) {
                val out = resolver.openOutputStream(target, "wt") ?: throw IOException("파일을 열 수 없어요")
                out.use { it.write(enc.bytes) }
            }
        } catch (e: SecurityException) {
            return SaveResult.DENIED
        } catch (e: Exception) {
            message = "저장하지 못했어요: ${e.message ?: e.javaClass.simpleName}"
            return SaveResult.FAILED
        }
        val wasUtf8 = charset == Charsets.UTF_8
        charset = enc.charset
        bom = enc.bom
        if (makeCurrent) {
            uri = target
            name = queryName(target) ?: name
        }
        savedText = text
        dirty = value.text != savedText
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
