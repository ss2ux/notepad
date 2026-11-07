package com.ss2ux.notepad

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** 파일의 줄바꿈 방식. 편집 중에는 항상 \n 으로 통일해서 다루고, 저장할 때 원래 방식으로 되돌린다. */
enum class Eol(val seq: String) { LF("\n"), CRLF("\r\n"), CR("\r") }

class Decoded(val text: String, val charset: Charset, val bom: Boolean, val eol: Eol)
class Encoded(val bytes: ByteArray, val charset: Charset, val bom: Boolean, val fellBack: Boolean)

object TextCodec {
    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val UTF16LE_BOM = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
    private val UTF16BE_BOM = byteArrayOf(0xFE.toByte(), 0xFF.toByte())

    private fun ByteArray.startsWith(p: ByteArray): Boolean =
        size >= p.size && p.indices.all { this[it] == p[it] }

    private fun korean(): Charset =
        try { Charset.forName("windows-949") } catch (e: Exception) { Charset.forName("EUC-KR") }

    private fun isValidUtf8(bytes: ByteArray): Boolean = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
        true
    } catch (e: CharacterCodingException) {
        false
    }

    fun decode(bytes: ByteArray): Decoded {
        var offset = 0
        var bom = false
        val charset: Charset
        when {
            bytes.startsWith(UTF8_BOM) -> { charset = Charsets.UTF_8; bom = true; offset = 3 }
            bytes.startsWith(UTF16LE_BOM) -> { charset = Charsets.UTF_16LE; bom = true; offset = 2 }
            bytes.startsWith(UTF16BE_BOM) -> { charset = Charsets.UTF_16BE; bom = true; offset = 2 }
            else -> charset = if (isValidUtf8(bytes)) Charsets.UTF_8 else korean()
        }
        val raw = String(bytes, offset, bytes.size - offset, charset)

        var crlf = 0
        var lf = 0
        var cr = 0
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '\r') {
                if (i + 1 < raw.length && raw[i + 1] == '\n') { crlf++; i++ } else cr++
            } else if (c == '\n') {
                lf++
            }
            i++
        }
        val eol = when {
            crlf > 0 && crlf >= lf && crlf >= cr -> Eol.CRLF
            cr > lf && cr > crlf -> Eol.CR
            else -> Eol.LF
        }
        val text = raw.replace("\r\n", "\n").replace('\r', '\n')
        return Decoded(text, charset, bom, eol)
    }

    fun encode(text: String, eol: Eol, charset: Charset, bom: Boolean): Encoded {
        val joined = if (eol == Eol.LF) text else text.replace("\n", eol.seq)
        var cs = charset
        var useBom = bom
        var fell = false
        if (!cs.newEncoder().canEncode(joined)) {   // 예: 한글 옛 인코딩 파일에 중국어를 입력한 경우
            cs = Charsets.UTF_8
            useBom = false
            fell = true
        }
        val body = joined.toByteArray(cs)
        val prefix = if (!useBom) ByteArray(0) else when (cs) {
            Charsets.UTF_8 -> UTF8_BOM
            Charsets.UTF_16LE -> UTF16LE_BOM
            Charsets.UTF_16BE -> UTF16BE_BOM
            else -> ByteArray(0)
        }
        return Encoded(prefix + body, cs, useBom, fell)
    }
}
