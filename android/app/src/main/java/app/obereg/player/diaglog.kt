package app.obereg.player

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Журнал диагностики: то, что пользователь может скопировать и прислать в отчёте.
 *
 * Пишем сюда всё, что важно для удалённой отладки: перехваченные адреса, исходы
 * извлечения, ошибки воспроизведения, смены маршрута. Живёт в памяти приложения,
 * переживает переходы между экранами, обрезается до разумного размера.
 */
object DiagLog {

    private const val MAX_LINES = 700

    private val lines = ArrayDeque<String>()

    private val time = SimpleDateFormat("HH:mm:ss", Locale.ROOT)

    /** Добавить запись (безопасно: журнал не должен ломать основной поток приложения). */
    fun add(message: String) {
        runCatching {
            val trimmed = message.replace("\n", " ⏎ ").take(1200)
            lines.addLast("${time.format(Date())}  $trimmed")
            while (lines.size > MAX_LINES) {
                lines.removeFirst()
            }
        }
    }

    /** Весь журнал одним текстом — для копирования в отчёт. */
    fun dump(): String = lines.joinToString("\n")

    /** Сколько записей накоплено. */
    fun size(): Int = lines.size
}
