package com.ss2ux.notepad

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

class MainActivity : ComponentActivity() {
    private val vm: EditorVm by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 파일을 눌러서 들어온 경우 바로 연다 (화면 회전 등으로 다시 만들어질 때는 다시 열지 않는다)
        val i = intent
        val act = i?.action
        if (savedInstanceState == null && i != null &&
            (act == Intent.ACTION_VIEW || act == Intent.ACTION_EDIT)
        ) {
            i.data?.let { vm.openFromIntent(it) }
        }

        setContent {
            AppTheme { EditorScreen(vm = vm, onExit = { finish() }) }
        }
    }
}

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
        content = content
    )
}
