# Дымовой тест JNI-биндингов на JVM

Проверяет тот же контракт, которым пользуется Android-оболочка: имена классов, методов
и экспортов совпадают с `android/app/src/main/java/app/obereg/core/CoreBridge.kt`
буквально (`Java_app_obereg_core_CoreBridge_*`), поэтому проверка на обычной JVM доказывает
работоспособность биндингов и для Android.

## Запуск

```powershell
# 1. Собрать нативную библиотеку под хост
cargo build --release -p obereg-jni

# 2. Скомпилировать обвязку и запустить
javac -encoding UTF-8 -d tools/jni-smoke/out `
  tools/jni-smoke/src/app/obereg/core/CoreBridge.java `
  tools/jni-smoke/src/SmokeMain.java

java "-Djava.library.path=target/release" -cp tools/jni-smoke/out SmokeMain
```

Требуется JDK 17+. На Android ту же роль играет `libobereg_jni.so`, положенная в
`android/app/src/main/jniLibs/<abi>/`.

## Записанный результат (Windows x86_64, JDK 17.0.20, 2026-09-22)

```
== Obereg JNI smoke test ==
version: 0.1.0
  [ok]   версия API должна быть 0.1.0
create: handle=2504102583088
  [ok]   сессия должна создаться
start: {"state":"connected","mode":"tunnel","since_ms":1790093167236,"last_error":null,
        "listen_addr":"127.0.0.1:3648","bytes_up":0,"bytes_down":0,"sessions_served":0}
  [ok]   после старта состояние connected
  [ok]   после старта режим tunnel
status: {"state":"connected","mode":"tunnel",...}
  [ok]   локальный прокси слушает петлю
setTransportMode(direct): {"state":"connected","mode":"direct",...}
  [ok]   режим переключился на direct
journal: [session_start, tunnel_start, tunnel_stop, transport_switch]
  [ok]   журнал содержит запуск сессии
  [ok]   журнал содержит запуск туннеля
  [ok]   журнал содержит переключение режима
stop: {"state":"idle","mode":"direct",...}
  [ok]   после остановки состояние idle
  [ok]   состояние остаётся idle
catalogSearch(пустой запрос): [{"id":"buck-bunny","title":"Big Buck Bunny (тестовое видео)",...}]
  [ok]   подборка содержит демонстрационное видео
catalogSearch(SINTEL): [{"id":"sintel","title":"Sintel (тестовое видео)","url":"https://...","duration_secs":888,...}]
  [ok]   поиск находит элемент по названию
  [ok]   элемент содержит ссылку на поток
catalogSearch(нет-такого): []
  [ok]   по неизвестному запросу — пустой массив
free: handle освобождён
РЕЗУЛЬТАТ: все проверки JNI пройдены
```

Проверка экспортов библиотеки (дизассемблер экспортов MSVC) подтвердила наличие всех
восьми символов: `version`, `create`, `free`, `start`, `stop`, `status`,
`setTransportMode`, `journalJson`.

## Что этот тест доказывает и чего не доказывает

Доказывает: JNI-контракт собран правильно, библиотека загружается, хэндл живёт между
вызовами, состояние и журнал корректно сериализуются в JSON, переключение режима и
освобождение ресурсов работают.

Не доказывает: работу оболочки на устройстве (ей нужны Android SDK/JDK и эмулятор или
телефон) и системные особенности Android — см. `docs/verification-report.md`, раздел 3.
