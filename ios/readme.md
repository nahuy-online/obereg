# iOS: подключение ядра Obereg

В этом каталоге лежит минимальный пример вызова ядра из Swift и module map.
Готовой пользовательской оболочки для iOS в этом этапе нет — только биндинги и пример.

## 1. Собрать статическую библиотеку

```bash
./scripts/build-ios.sh
# результат: libobereg_ffi.a в корне репозитория (universal: arm64 + x86_64)
lipo -info libobereg_ffi.a
```

## 2. Подключить к проекту

Xcode:

1. Добавьте `libobereg_ffi.a` в таргет (Build Phases → Link Binary With Libraries).
2. Добавьте `ios/include` в Header Search Paths.
3. Создайте bridging header с `#import "obereg.h"` — после этого функции ядра
   доступны из Swift напрямую (`obereg_core_new`, `obereg_core_start`, …).

Swift Package Manager: используйте `ios/include/module.modulemap` — модуль называется `OberegCore`.

## 3. Запустить пример

```bash
swiftc -O ios/Example/main.swift \
  -I ios/include -L . -lobereg_ffi \
  -o obereg-swift-example
./obereg-swift-example
```

Ожидаемый вывод:

```
core api: 0.1.0
status после старта: {"state":"connected","mode":"tunnel",...}
локальный прокси: 127.0.0.1:4xxxx
проба трафика: {"url":"http://example.com/","via":"tunnel",...}
журнал: [{"seq":1,"kind":"session_start",...}]
status после остановки: {"state":"idle","mode":"direct",...}
```

## 4. Правила владения памятью

- `obereg_core_version()` возвращает статическую строку — освобождать нельзя;
- все прочие `char*` освобождаются через `obereg_string_free`;
- хэндл освобождается через `obereg_core_free`;
- при ошибке функции возвращают `NULL`, детали — в `obereg_core_last_error`.

## 5. Что осталось для полноценного iOS-клиента

Сетевой слой ядра используется тем же способом, что и на Android: оболочка направляет
свои запросы на локальный SOCKS5-прокси ядра. Системный `NEPacketTunnelProvider`
не требуется — встроенный туннель работает внутри процесса приложения
(см. `docs/adr/0003-tunnel-engine.md`).
