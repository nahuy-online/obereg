// Пример вызова ядра Obereg из Swift через C ABI (без SwiftUI — только ядро).
//
// Сборка (после ./scripts/build-ios.sh для iOS или ручной сборки под macOS):
//   swiftc -O ios/Example/main.swift \
//     -I ios/include -L . -lobereg_ffi \
//     -o obereg-swift-example && ./obereg-swift-example
//
// Ожидаемый вывод: версия API, JSON статуса с mode=tunnel, адрес локального прокси
// и JSON состояния после остановки.

import Foundation

let configJson = #"""
{"config_version":1,"device_id":"swift-example","locale":"ru","log_level":"info","journal_path":":memory:","tunnel":{"listen_addr":"127.0.0.1:0","mode":"tunnel","upstream":null,"handshake_timeout_ms":5000,"idle_timeout_ms":30000}}
"""#

func readAndFree(_ pointer: UnsafeMutablePointer<CChar>?) -> String? {
    guard let pointer = pointer else { return nil }
    let value = String(cString: pointer)
    obereg_string_free(pointer)
    return value
}

print("core api: \(String(cString: obereg_core_version()))")

guard let handle = obereg_core_new(configJson) else {
    print("create failed: проверьте JSON конфигурации")
    exit(1)
}
defer { obereg_core_free(handle) }

if let status = readAndFree(obereg_core_start(handle)) {
    print("status после старта: \(status)")
} else {
    print("start failed; last_error: \(readAndFree(obereg_core_last_error(handle)) ?? "n/a")")
}

if let proxy = readAndFree(obereg_core_local_proxy_addr(handle)) {
    print("локальный прокси: \(proxy)")
} else {
    print("локальный прокси не поднят (режим direct)")
}

// Проверка прохождения трафика через активный транспорт.
if let probe = readAndFree(obereg_core_probe_traffic(handle, "http://example.com/")) {
    print("проба трафика: \(probe)")
}

if let journal = readAndFree(obereg_core_journal_read(handle)) {
    print("журнал: \(journal)")
}

if let status = readAndFree(obereg_core_stop(handle)) {
    print("status после остановки: \(status)")
}
