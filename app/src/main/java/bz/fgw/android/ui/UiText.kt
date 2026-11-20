package bz.fgw.android.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

class UiText(val language: String) {
    val locale: Locale = Locale.forLanguageTag(language)
    fun choose(ru: String, en: String) = if (language == "en") en else ru
    val giveaways = choose("Раздачи", "Giveaways")
    val history = choose("История", "History")
    val settings = choose("Настройки", "Settings")
    val refresh = choose("Проверить сейчас", "Check now")
    val refreshing = choose("Проверяем магазины…", "Checking stores…")
    val openStore = choose("Открыть магазин", "Open store")
    val claimed = choose("Уже забрал", "Claimed")
    val undoClaim = choose("Отменить отметку", "Undo claimed")
    val ended = choose("Завершена", "Ended")
    val okay = choose("Понятно", "OK")
    val close = choose("Закрыть", "Close")
    val back = choose("Назад", "Back")
    val noDeadline = choose("Срок окончания неизвестен", "End date unknown")
    val firstCheck = choose("Запустите первую проверку.", "Run your first check.")
    val noStores = choose("Магазины отключены", "No stores selected")
    val noStoresDetail = choose("Выберите магазины в настройках, чтобы включить проверку.", "Choose stores in Settings to enable checks.")
    val sourceError = choose("Не удалось обновить источник", "Could not update this source")
    val partial = choose("Неполный ответ источника", "Partial source response")
    val success = choose("Проверка завершена", "Check completed")
    val cached = choose("Доступны сохранённые данные.", "Saved results are available.")
    val systemBlocked = choose("Android блокирует уведомления", "Android is blocking notifications")
    val systemSettings = choose("Настройки уведомлений Android", "Android notification settings")
    val actionFailed = choose("Не удалось выполнить действие. Попробуйте ещё раз.", "Could not complete the action. Please try again.")
    val permissionDenied = choose(
        "Разрешение на уведомления не выдано. Раздачи доступны в приложении. Включить уведомления можно в настройках Android.",
        "Notification permission was denied. Giveaways remain available in the app. You can allow notifications in Android settings.",
    )
    val offerMissing = choose("Запись о раздаче недоступна. Запустите новую проверку.", "The giveaway record is unavailable. Run a new check.")
    val regionChanged = choose("Это уведомление относится к другой стране каталога. Проверьте доступность игры для выбранной страны.", "This notification is for another catalog country. Check availability for your selected country.")
    val unsafeUrl = choose("Не удалось подтвердить адрес магазина. Обновите раздачи.", "Could not verify the store address. Refresh the giveaways.")
    val noBrowser = choose("Установите браузер или приложение магазина для открытия ссылки.", "Install a browser or store app to open this link.")
    fun country(code: String): String = Locale.Builder().setRegion(code).build().getDisplayCountry(locale).ifBlank { code }
    fun time(value: Long): String = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withLocale(locale).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(value))
    fun deadline(value: Long) = choose("Забрать до ${time(value)}", "Claim by ${time(value)}")
    fun lastSuccess(value: Long) = choose("Успешно ${time(value)}", "Last success ${time(value)}")
    fun hour(value: Int) = choose("Каждые $value ч", "Every $value h")
}

fun storeName(store: String) = when (store) {
    "steam" -> "Steam"
    "gog" -> "GOG"
    else -> "Epic Games Store"
}
