# PROJECT.md — Техническая документация Config

## Общая информация

- **Название:** Config
- **Пакет:** `com.config.app`
- **Репозиторий:** `gennadyevt-bot/Config_dns`
- **База:** STOP VPN v4.0.0 (WireGuard/AmneziaWG)
- **Язык:** Kotlin
- **Платформа:** Android (minSdk 24, targetSdk 34)
- **VPN-библиотека:** AmneziaWG Android 2.3.7 (`com.zaneschepke:amneziawg-android`)

---

## Архитектура

### Поток данных при подключении

```
Пользователь нажимает CONNECT
        ↓
MainActivity → VpnManager.connect(server)
        ↓
CoroutineScope(Dispatchers.IO)
        ↓
buildConfigString(server) → WireGuard/AmneziaWG INI-формат
        ↓
Config.parse(ByteArrayInputStream(...))
        ↓
GoBackend.setState(tunnel, State.UP, config)
        ↓
Android VpnService → системный VPN-туннель
        ↓
withContext(Dispatchers.Main) → обновление UI
```

### Классы и их ответственность

| Класс | Ответственность | Поток |
|-------|----------------|-------|
| `MainActivity` | UI, Drawer, диалоги, RecyclerView, обработка кликов | Main |
| `VpnManager` | Управление VPN: connect/disconnect, парсинг конфига | IO (coroutines) |
| `WgTunnel` | Реализация интерфейса `Tunnel` для GoBackend | IO |
| `ServerInfo` | Data class с полями WG-конфига | — |
| `VpnStatus` | Enum: DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING, SWITCHING, ERROR | — |
| `ServerAdapter` | RecyclerView адаптер, отображение 6 слотов | Main |
| `ServerStorage` | SharedPreferences + JSON сериализация | Main |
| `ServerBackupManager` | Экспорт конфигов через FileProvider | Main |
| `AutoConnectStorage` | Boolean-флаг в SharedPreferences | Main |
| `StopVpnWidget` | AppWidgetProvider, RemoteViews | Main |

---

## Формат конфигурации WireGuard

```ini
[Interface]
Address = 192.168.6.75/32
DNS = 1.1.1.1, 8.8.8.8
PrivateKey = <base64>
Jc = 5          # AmneziaWG (опционально)
Jmin = 50       # AmneziaWG (опционально)
Jmax = 1000     # AmneziaWG (опционально)
S1 = 50         # AmneziaWG (опционально)
S2 = 100        # AmneziaWG (опционально)
H1 = 1          # AmneziaWG (опционально)
H2 = 2          # AmneziaWG (опционально)
H3 = 3          # AmneziaWG (опционально)
H4 = 4          # AmneziaWG (опционально)

[Peer]
PublicKey = <base64>
PresharedKey = <base64>  # опционально
AllowedIPs = 0.0.0.0/0
Endpoint = premiusa2.vpnjantit.com:1024
PersistentKeepalive = 25
```

AmneziaWG параметры добавляются только если значение не пустое и не `"0"`.

---

## Известные проблемы и решения

### 1. NetworkOnMainThreadException
**Причина:** `GoBackend.setState()` выполняет сетевые операции на UI-потоке.
**Решение:** Обернуть в `CoroutineScope(Dispatchers.IO)`, UI-обновления через `withContext(Dispatchers.Main)`.

### 2. isIpv4ResolutionPreferred() — неверная сигнатура
**Причина:** Возвращал `Boolean?`, AmneziaWG 2.3.7 ожидает `Boolean`.
**Решение:** `override fun isIpv4ResolutionPreferred() = false`

### 3. Error: null (e.message == null)
**Причина:** Некоторые исключения (native crash, reflection) не имеют message.
**Решение:** Использовать `e.toString()` вместо `e.message`.

### 4. Отсутствующие ресурсы (иконки меню)
**Причина:** `drawer_menu.xml` ссылался на `ic_menu_backup`, `ic_menu_auto`, `ic_menu_about`, которых не было.
**Решение:** Создать vector drawable иконки.

### 5. FileProvider не настроен
**Причина:** `ServerBackupManager` использует `FileProvider.getUriForFile()`, но provider не был объявлен.
**Решение:** Добавить `androidx.core.content.FileProvider` в AndroidManifest + `file_provider_paths.xml`.

---

## CI/CD Pipeline

```yaml
on: [push, pull_request] → main
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - checkout@v4
      - setup-java@v4 (JDK 17 Temurin)
      - setup-gradle@v3 (Gradle 8.7)
      - gradle assembleDebug
      - upload-artifact@v4 (app-debug.apk)
```

---

## Зависимости (app/build.gradle.kts)

```kotlin
implementation("androidx.core:core-ktx:1.12.0")
implementation("androidx.appcompat:appcompat:1.6.1")
implementation("com.google.android.material:material:1.11.0")
implementation("androidx.constraintlayout:constraintlayout:2.1.4")
implementation("androidx.recyclerview:recyclerview:1.3.2")
implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
implementation("com.zaneschepke:amneziawg-android:2.3.7")
```

---

## Цветовая схема

| Элемент | Цвет |
|---------|------|
| Фон приложения | `#228B22` (ForestGreen) |
| Акцент / кнопка CONNECT | `#32CD32` (LimeGreen) |
| Кнопка STOP | `#DC143C` (Crimson) |
| Текст статуса Connected | `#90EE90` (LightGreen) |
| Текст трафика down | `#90EE90` |
| Текст трафика up | `#FF6B6B` |
| StatusBar | `#228B22` |
| NavigationBar | `#000000` |

---

## Будущие улучшения (TODO)

- [ ] Импорт бэкапа из JSON-файла
- [ ] Пинг серверов перед подключением
- [ ] Счётчик трафика (rx/tx bytes)
- [ ] QR-сканер для импорта конфигов
- [ ] Тёмная тема
- [ ] Уведомление в статус-баре при подключении
- [ ] Kill Switch
- [ ] Split Tunneling
