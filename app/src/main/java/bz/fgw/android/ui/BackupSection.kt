package bz.fgw.android.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import bz.fgw.android.data.BackupCodec
import bz.fgw.android.data.BackupData
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun BackupSection(
    language: String,
    exportBackup: suspend (Boolean) -> String,
    importBackup: suspend (BackupData, Boolean) -> Unit,
) {
    val text = UiText(language)
    val resolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()
    val currentExport by rememberUpdatedState(exportBackup)
    val currentImport by rememberUpdatedState(importBackup)
    var includeExportHistory by remember { mutableStateOf(true) }
    var includeImportHistory by remember { mutableStateOf(true) }
    var preview by remember { mutableStateOf<BackupData?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    val saveDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            busy = true
            message = null
            try {
                val content = currentExport(includeExportHistory)
                withContext(Dispatchers.IO) {
                    requireNotNull(resolver.openOutputStream(uri, "wt")).bufferedWriter(Charsets.UTF_8).use { it.write(content) }
                }
                message = text.choose("Резервная копия сохранена.", "Backup saved.")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                message = text.choose("Не удалось сохранить резервную копию.", "Could not save the backup.")
            } finally {
                busy = false
            }
        }
    }
    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            message = null
            try {
                preview = withContext(Dispatchers.IO) {
                    val content = requireNotNull(resolver.openInputStream(uri)).use { stream ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = stream.read(buffer)
                            if (count < 0) break
                            require(output.size() + count <= BackupCodec.MAX_BYTES)
                            output.write(buffer, 0, count)
                        }
                        output.toString(Charsets.UTF_8.name())
                    }
                    BackupCodec.decode(content)
                }
                includeImportHistory = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                message = text.choose("Не удалось прочитать файл. Выберите резервную копию Free Games Watcher размером до 5 МБ.",
                    "Could not read the file. Choose a Free Games Watcher backup up to 5 MB.")
            } finally {
                busy = false
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text.choose("Перенос данных", "Data transfer"), style = MaterialTheme.typography.titleMedium)
        Text(text.choose("Перенос настроек, личных отметок и истории между Android и браузерным расширением.",
            "Transfer settings, personal marks and history between Android and the browser extension."), style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = includeExportHistory, enabled = !busy, onCheckedChange = { includeExportHistory = it })
            Text(text.choose("Включить историю в копию", "Include history in backup"))
        }
        if (!includeExportHistory) Text(text.choose(
            "История помогает сопоставить отметки скрытия с конкретными раздачами при переносе.",
            "History helps match hidden marks to their giveaways during transfer."), style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !busy, onClick = { saveDocument.launch("Free-Games-Watcher-${LocalDate.now()}.json") }) {
                Text(text.choose("Экспорт", "Export"))
            }
            OutlinedButton(enabled = !busy, onClick = { openDocument.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) {
                Text(text.choose("Импорт", "Import"))
            }
        }
        if (busy) Text(text.choose("Обрабатываем файл…", "Processing file…"), style = MaterialTheme.typography.bodySmall)
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }

    preview?.let { data ->
        AlertDialog(
            onDismissRequest = { if (!busy) preview = null },
            title = { Text(text.choose("Импорт резервной копии", "Import backup")) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (data.exportedAt.isNotBlank()) Text(text.choose("Создана ${data.exportedAt}", "Created ${data.exportedAt}"))
                    Text(text.choose("Страна каталога — ${data.settings.country}", "Catalog country — ${data.settings.country}"))
                    Text(text.choose("Личных отметок — ${data.marks.size}", "Personal marks — ${data.marks.size}"))
                    Text(text.choose("Записей истории — ${data.history.size}", "History entries — ${data.history.size}"))
                    Text(text.choose("Настройки и личные отметки будут заменены. История объединится с сохранёнными записями. Раздачи из копии будут проверены при следующем обновлении.",
                        "Settings and personal marks will be replaced. History will merge with saved entries. Imported giveaways will be verified on the next refresh."))
                    if (data.hasHistory) Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = includeImportHistory, enabled = !busy, onCheckedChange = { includeImportHistory = it })
                        Text(text.choose("Импортировать историю", "Import history"))
                    }
                    if (data.marks.any { it.hiddenCampaign.isNotEmpty() } && (!data.hasHistory || !includeImportHistory)) {
                        Text(text.choose("Без истории отметки скрытия сопоставляются по известным срокам раздачи.",
                            "Without history, hidden marks are matched using known giveaway dates."))
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    scope.launch {
                        busy = true
                        try {
                            currentImport(data, includeImportHistory)
                            preview = null
                            message = text.choose("Резервная копия импортирована.", "Backup imported.")
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            preview = null
                            message = text.choose("Не удалось импортировать резервную копию.", "Could not import the backup.")
                        } finally {
                            busy = false
                        }
                    }
                }) { Text(text.choose("Импортировать", "Import")) }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { preview = null }) { Text(text.choose("Отмена", "Cancel")) }
            },
        )
    }
}
