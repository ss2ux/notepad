package com.ss2ux.notepad

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 글이 바뀐 한 덩어리: [start, start + before.length) 가 before 에서 after 로 바뀌었다. */
class EditOp(val start: Int, val before: String, val after: String) {
    val size: Long get() = (before.length + after.length).toLong()
}

/**
 * 뒤로(Ctrl+Z) / 앞으로(Ctrl+Shift+Z) 기록.
 * 글 전체를 복사해 두지 않고 "바뀐 부분"만 저장하므로 긴 글에서도 가볍다.
 *
 * - 연속해서 입력/지우는 글자는 한 덩어리로 묶는다(단어 단위). 한글 조합 중 교체도 여기서 합쳐진다.
 * - 모두 바꾸기처럼 큰 변경은 따로 한 칸을 차지한다.
 * - 새로 고치면 "앞으로" 기록은 사라진다.
 * - 파일을 새로 열면 VM 이 clear() 한다.
 */
class EditHistory {

    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    private val undoStack = ArrayList<EditOp>()
    private val redoStack = ArrayList<EditOp>()
    private var chars = 0L          // undo + redo 에 쌓인 글자 수 (메모리 상한용)
    private var lastTime = 0L
    private var mergeable = false   // 바로 앞 기록에 이어 붙일 수 있는 상태인지

    fun clear() {
        undoStack.clear()
        redoStack.clear()
        chars = 0
        mergeable = false
        refresh()
    }

    /** 다음 변경은 앞 기록과 합치지 않고 새 칸으로 둔다 */
    fun breakMerge() {
        mergeable = false
    }

    /**
     * 글이 바뀌었다: start 자리의 before 가 after 로 바뀜.
     * merge 가 false 면 앞뒤 기록과 합치지 않는다(모두 바꾸기 등).
     */
    fun record(start0: Int, before0: String, after0: String, merge: Boolean = true) {
        var start = start0
        var before = before0
        var after = after0

        // 앞뒤로 똑같은 부분은 잘라 낸다 (모두 바꾸기에서 저장량이 크게 줄어든다)
        val minLen = minOf(before.length, after.length)
        var p = 0
        while (p < minLen && before[p] == after[p]) p++
        var s = 0
        val maxS = minLen - p
        while (s < maxS && before[before.length - 1 - s] == after[after.length - 1 - s]) s++
        if (p > 0 || s > 0) {
            before = before.substring(p, before.length - s)
            after = after.substring(p, after.length - s)
            start += p
        }
        if (before.isEmpty() && after.isEmpty()) return

        if (redoStack.isNotEmpty()) {
            for (o in redoStack) chars -= o.size
            redoStack.clear()
        }

        val now = SystemClock.uptimeMillis()
        val op = EditOp(start, before, after)
        val last = undoStack.lastOrNull()
        if (merge && last != null && mergeable && now - lastTime <= MERGE_MS && canMerge(last, op)) {
            undoStack.removeAt(undoStack.size - 1)
            chars -= last.size
            val m = mergeOps(last, op)
            if (m != null) {
                undoStack.add(m)
                chars += m.size
                mergeable = true
            } else {
                mergeable = false   // 입력했다가 지워서 흔적이 없어졌다
            }
        } else {
            undoStack.add(op)
            chars += op.size
            mergeable = merge
        }
        lastTime = now

        // 너무 오래된 기록은 버린다 (가장 최근 한 칸은 항상 남긴다)
        while (undoStack.size > MAX_OPS || (chars > MAX_CHARS && undoStack.size > 1)) {
            chars -= undoStack.removeAt(0).size
        }
        refresh()
    }

    /** 뒤로: 가장 최근 변경을 꺼내 "앞으로" 쪽으로 옮긴다. 화면이 이 변경을 거꾸로 적용해야 한다. */
    fun takeUndo(): EditOp? {
        if (undoStack.isEmpty()) return null
        val op = undoStack.removeAt(undoStack.size - 1)
        redoStack.add(op)
        mergeable = false
        refresh()
        return op
    }

    /** 앞으로: 되돌렸던 변경을 다시 꺼낸다. 화면이 이 변경을 그대로 적용해야 한다. */
    fun takeRedo(): EditOp? {
        if (redoStack.isEmpty()) return null
        val op = redoStack.removeAt(redoStack.size - 1)
        undoStack.add(op)
        mergeable = false
        refresh()
        return op
    }

    private fun refresh() {
        val u = undoStack.isNotEmpty()
        val r = redoStack.isNotEmpty()
        if (canUndo != u) canUndo = u
        if (canRedo != r) canRedo = r
    }

    /** 앞 기록(last)에 이어 붙여도 되는 변경인가: 작고, 맞닿아 있고, 단어가 바뀌는 지점이 아닐 때 */
    private fun canMerge(last: EditOp, op: EditOp): Boolean {
        if (last.before.length > SMALL || last.after.length > SMALL) return false
        if (op.before.length > SMALL || op.after.length > SMALL) return false
        if (op.after.indexOf('\n') >= 0) return false
        val lastEnd = last.start + last.after.length
        // 앞 기록이 만든 글자 영역과 맞닿거나 겹쳐야 한다
        if (op.start > lastEnd || op.start + op.before.length < last.start) return false
        // 공백 뒤에서 새 단어를 쓰기 시작하면 새 칸
        if (op.before.isEmpty() && op.after.isNotEmpty() &&
            last.after.isNotEmpty() && last.after.last().isWhitespace() &&
            !op.after.first().isWhitespace()
        ) return false
        return true
    }

    /**
     * last 다음에 op 가 일어난 것을 하나로 합친다. (op 의 위치는 last 가 적용된 뒤의 글 기준)
     * 결과가 "아무 변화 없음"이면 null.
     */
    private fun mergeOps(last: EditOp, op: EditOp): EditOp? {
        val lastEnd = last.start + last.after.length
        val ns = op.start
        val nb = op.before.length
        // op 가 last 영역 밖까지 건드린 부분(왼쪽/오른쪽). 이 글자들은 last 가 바꾸지 않은 원래 글자다.
        val left = if (ns < last.start) op.before.substring(0, last.start - ns) else ""
        val right = if (ns + nb > lastEnd) op.before.substring(lastEnd - ns) else ""
        val start = minOf(last.start, ns)
        val before = left + last.before + right
        val mid = left + last.after + right
        val off = ns - start
        val after = mid.substring(0, off) + op.after + mid.substring(off + nb)
        if (before == after) return null
        return EditOp(start, before, after)
    }

    private companion object {
        const val MERGE_MS = 1500L          // 이 시간 안에 이어서 입력하면 한 덩어리
        const val SMALL = 40                // 이보다 큰 변경(붙여넣기 등)은 합치지 않는다
        const val MAX_OPS = 5_000
        const val MAX_CHARS = 8_000_000L
    }
}
