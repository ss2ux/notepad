package com.ss2ux.notepad

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.SaveAs
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(vm: EditorVm, onExit: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    var pendingDiscard by remember { mutableStateOf<(() -> Unit)?>(null) }

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

    BackHandler(enabled = vm.dirty) { pendingDiscard = onExit }

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
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val minH = (maxHeight - 32.dp).coerceAtLeast(0.dp)
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                ) {
                    BasicTextField(
                        value = vm.value,
                        onValueChange = vm::onChange,
                        readOnly = vm.busy,
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
