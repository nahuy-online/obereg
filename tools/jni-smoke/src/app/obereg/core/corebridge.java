package app.obereg.core;

/**
 * Зеркало Android-контракта {@code app.obereg.core.CoreBridge}.
 *
 * Используется для проверки JNI-биндингов ядра на обычной JVM: сигнатуры и имена
 * совпадают с Kotlin-версией из {@code android/app/src/main/java/app/obereg/core/CoreBridge.kt},
 * поэтому имена экспортов (`Java_app_obereg_core_CoreBridge_*`) совпадают буквально.
 *
 * Библиотека: {@code obereg_jni.dll} (Windows) / {@code libobereg_jni.so} (Android).
 */
public final class CoreBridge {

    static {
        System.loadLibrary("obereg_jni");
    }

    private CoreBridge() {
    }

    /** Версия публичного API ядра. */
    public static native String version();

    /** Создать сессию. 0 — ошибка конфигурации. */
    public static native long create(String configJson);

    /** Уничтожить сессию. */
    public static native void free(long handle);

    /** Запустить сессию: JSON StateSnapshot либо {"error": "..."}. */
    public static native String start(long handle);

    /** Остановить сессию. */
    public static native String stop(long handle);

    /** Текущее состояние: JSON StateSnapshot. */
    public static native String status(long handle);

    /** Переключить режим транспорта: "direct" или "tunnel". */
    public static native String setTransportMode(long handle, String mode);

    /** Журнал событий: JSON-массив. */
    public static native String journalJson(long handle);

    /** Поиск в каталоге видео: JSON-массив элементов. */
    public static native String catalogSearch(long handle, String query, int limit);
}
