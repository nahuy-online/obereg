import app.obereg.core.CoreBridge;

/**
 * Дымовой тест JNI-биндингов ядра Obereg на обычной JVM.
 *
 * Проверяет тот же путь, которым пользуется Android-оболочка:
 * version → create → start → status → setTransportMode → journalJson → stop → free.
 *
 * Запуск (из корня репозитория, после сборки оbereg-jni под хост):
 * <pre>
 *   cargo build --release -p obereg-jni
 *   javac -d tools/jni-smoke/out tools/jni-smoke/src/app/obereg/core/CoreBridge.java tools/jni-smoke/src/SmokeMain.java
 *   java -Djava.library.path=target/release -cp tools/jni-smoke/out SmokeMain
 * </pre>
 */
public final class SmokeMain {

    private static final String CONFIG = """
        {"config_version":1,"device_id":"jni-smoke","locale":"ru","log_level":"info",\
        "journal_path":":memory:",\
        "tunnel":{"listen_addr":"127.0.0.1:0","mode":"tunnel","upstream":null,\
        "handshake_timeout_ms":5000,"idle_timeout_ms":30000}}""";

    private static int failures = 0;

    public static void main(String[] args) {
        System.out.println("== Obereg JNI smoke test ==");

        String version = CoreBridge.version();
        System.out.println("version: " + version);
        check("0.1.0".equals(version), "версия API должна быть 0.1.0");

        long handle = CoreBridge.create(CONFIG);
        System.out.println("create: handle=" + handle);
        check(handle != 0L, "сессия должна создаться");
        if (handle == 0L) {
            finish();
            return;
        }

        try {
            String started = CoreBridge.start(handle);
            System.out.println("start: " + started);
            check(started.contains("\"state\":\"connected\""), "после старта состояние connected");
            check(started.contains("\"mode\":\"tunnel\""), "после старта режим tunnel");

            String status = CoreBridge.status(handle);
            System.out.println("status: " + status);
            check(status.contains("\"listen_addr\":\"127.0.0.1:"), "локальный прокси слушает петлю");

            String switched = CoreBridge.setTransportMode(handle, "direct");
            System.out.println("setTransportMode(direct): " + switched);
            check(switched.contains("\"mode\":\"direct\""), "режим переключился на direct");

            String journal = CoreBridge.journalJson(handle);
            System.out.println("journal: " + journal);
            check(journal.contains("\"kind\":\"session_start\""), "журнал содержит запуск сессии");
            check(journal.contains("\"kind\":\"tunnel_start\""), "журнал содержит запуск туннеля");
            check(journal.contains("\"kind\":\"transport_switch\""), "журнал содержит переключение режима");

            String stopped = CoreBridge.stop(handle);
            System.out.println("stop: " + stopped);
            check(stopped.contains("\"state\":\"idle\""), "после остановки состояние idle");

            // Каталог: тот же JNI-путь, которым пользуется экран каталога в приложении.
            String featured = CoreBridge.catalogSearch(handle, "", 10);
            System.out.println("catalogSearch(пустой) : " + shorten(featured));
            check(featured.contains("\"id\":\"buck-bunny\""), "подборка содержит демонстрационное видео");

            String found = CoreBridge.catalogSearch(handle, "SINTEL", 10);
            System.out.println("catalogSearch(SINTEL): " + shorten(found));
            check(found.contains("\"id\":\"sintel\""), "поиск находит элемент по названию");
            check(found.contains("https://"), "элемент содержит ссылку на поток");

            String nothing = CoreBridge.catalogSearch(handle, "нет-такого", 10);
            System.out.println("catalogSearch(нет-такого): " + nothing);
            check("[]".equals(nothing.trim()), "по неизвестному запросу — пустой массив");

            String idleStatus = CoreBridge.status(handle);
            System.out.println("status после остановки: " + idleStatus);
            check(idleStatus.contains("\"state\":\"idle\""), "состояние остаётся idle");
        } finally {
            CoreBridge.free(handle);
            System.out.println("free: handle освобождён");
        }

        finish();
    }

    private static String shorten(String value) {
        return value.length() <= 160 ? value : value.substring(0, 160) + "…";
    }

    private static void check(boolean condition, String what) {
        if (!condition) {
            failures++;
            System.out.println("  [FAIL] " + what);
        } else {
            System.out.println("  [ok]   " + what);
        }
    }

    private static void finish() {
        if (failures == 0) {
            System.out.println("РЕЗУЛЬТАТ: все проверки JNI пройдены");
            System.exit(0);
        } else {
            System.out.println("РЕЗУЛЬТАТ: провалено проверок — " + failures);
            System.exit(1);
        }
    }
}
