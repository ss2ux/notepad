package com.ss2ux.notepad

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.UpdateAppearance
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.OverScroller
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 글이 아주 길어도 빠르게 고칠 수 있도록 Compose 글자칸 대신 쓰는 안드로이드 기본 EditText.
 * 기본 EditText 는 고친 문단만 다시 배치하고, 커서 따라가기·선택 핸들·붙여넣기도 시스템이 처리한다.
 * 여기에 (1) 손가락을 튕겨 넘기는 스크롤, (2) 원하는 위치로 화면 옮기기, (3) 스크롤바 갱신 알림을 더했다.
 */
class NotepadEditText(context: Context) : EditText(context) {

    /** 스크롤 위치/크기/글자가 바뀌어 스크롤바를 다시 그려야 할 때 */
    var onMetrics: (() -> Unit)? = null
    /** 커서/선택이 바뀔 때 (start, end) */
    var onSelection: ((Int, Int) -> Unit)? = null
    /** 뒤로(Ctrl+Z) / 앞으로(Ctrl+Shift+Z, Ctrl+Y). 안드로이드 기본 undo 대신 앱의 기록을 쓴다. */
    var onUndo: (() -> Unit)? = null
    var onRedo: (() -> Unit)? = null

    private var accent = 0
    private val scroller = OverScroller(context)
    private val flingStep = object : Runnable {
        override fun run() {
            if (scroller.computeScrollOffset()) {
                val y = scroller.currY.coerceIn(0, maxScrollY())
                if (y != scrollY) scrollTo(0, y)
                postOnAnimation(this)
            }
        }
    }
    private val flingDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            val range = maxScrollY()
            if (range <= 0) return false
            scroller.fling(0, scrollY, 0, (-velocityY).roundToInt(), 0, 0, 0, range)
            postOnAnimation(flingStep)
            return true
        }
    })

    init {
        background = null
        gravity = Gravity.TOP or Gravity.START
        // 맞춤법 밑줄/추천 단어는 긴 글에서 느려지는 주범이라 끈다
        inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        lineHeight = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 24f, resources.displayMetrics
        ).roundToInt()
        setPadding(dp(16f), dp(8f), dp(16f), dp(8f))
        isVerticalScrollBarEnabled = false      // 스크롤바는 화면(Compose) 쪽에서 직접 그린다
        overScrollMode = View.OVER_SCROLL_NEVER
        isSaveEnabled = false                   // 큰 글이 상태 저장(Bundle)에 실리지 않게
        setHorizontallyScrolling(false)
    }

    fun dp(v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).roundToInt()

    /** 커서/선택 배경 색을 앱 색에 맞춘다 */
    fun applyAccent(color: Int) {
        if (accent == color) return
        accent = color
        val cursor = GradientDrawable().apply {
            setColor(color)
            setSize(dp(2f), -1)
        }
        setTextCursorDrawable(cursor)
        highlightColor = (color and 0x00FFFFFF) or 0x59000000
    }

    /** 스크롤할 수 있는 최대 위치 */
    fun maxScrollY(): Int {
        val l = layout ?: return 0
        val viewH = height - totalPaddingTop - totalPaddingBottom
        return (l.getLineTop(l.lineCount) - viewH).coerceAtLeast(0)
    }

    fun stopFling() {
        scroller.forceFinished(true)
        removeCallbacks(flingStep)
    }

    fun scrollToY(y: Int) {
        stopFling()
        val t = y.coerceIn(0, maxScrollY())
        if (t != scrollY) scrollTo(0, t)
    }

    /**
     * offset 가 있는 줄이 화면 가장자리 근처이거나 밖이면 스크롤해서 보이게 한다. 이미 편하게 보이면 가만히 둔다.
     * anchor 가 null 이면 가장 가까운 만큼만, 값이 있으면 그 줄이 화면 높이의 그 비율(0=위, 1=아래)에 오게 한다.
     */
    fun revealOffset(offset: Int, anchor: Float? = null) {
        val l = layout ?: return
        val o = offset.coerceIn(0, text.length)
        val line = l.getLineForOffset(o)
        val top = l.getLineTop(line)
        val bottom = l.getLineBottom(line)
        val viewH = height - totalPaddingTop - totalPaddingBottom
        if (viewH <= 0) return
        val margin = min(dp(48f), viewH / 4)
        val vTop = scrollY
        val vBottom = scrollY + viewH
        if (top >= vTop + margin && bottom <= vBottom - margin) return
        val target = when {
            anchor != null -> (top - viewH * anchor).roundToInt()
            top < vTop + margin -> top - margin
            else -> bottom - viewH + margin
        }
        scrollToY(target)
    }

    /** 하드웨어 키보드 단축키 */
    override fun onKeyShortcut(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_Z && event.hasModifiers(KeyEvent.META_CTRL_ON)) {
            onUndo?.invoke()
            return true
        }
        if ((keyCode == KeyEvent.KEYCODE_Z &&
                event.hasModifiers(KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON)) ||
            (keyCode == KeyEvent.KEYCODE_Y && event.hasModifiers(KeyEvent.META_CTRL_ON))
        ) {
            onRedo?.invoke()
            return true
        }
        return super.onKeyShortcut(keyCode, event)
    }

    /** 길게 눌러 나오는 메뉴/키보드의 실행 취소·다시 실행도 앱의 기록으로 보낸다 */
    override fun onTextContextMenuItem(id: Int): Boolean = when (id) {
        android.R.id.undo -> { onUndo?.invoke(); true }
        android.R.id.redo -> { onRedo?.invoke(); true }
        else -> super.onTextContextMenuItem(id)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) stopFling()
        flingDetector.onTouchEvent(event)
        return super.onTouchEvent(event)
    }

    override fun onScrollChanged(horiz: Int, vert: Int, oldHoriz: Int, oldVert: Int) {
        super.onScrollChanged(horiz, vert, oldHoriz, oldVert)
        onMetrics?.invoke()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        onMetrics?.invoke()
        // 키보드가 올라와 화면이 줄었을 때 커서가 가려지면, 커서가 보이는 곳까지만 살짝 옮긴다
        if (h != oldh && oldh > 0 && hasFocus()) post { revealOffset(selectionEnd) }
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        onSelection?.invoke(selStart, selEnd)
    }
}

/** 검색 결과 배경색. 클립보드에 복사될 때 딸려 가지 않도록 ParcelableSpan 이 아닌 직접 만든 span 을 쓴다. */
class MatchSpan(private val color: Int) : CharacterStyle(), UpdateAppearance {
    override fun updateDrawState(tp: TextPaint) {
        tp.bgColor = color
    }
}
