# Config — Android VPN Client (AmneziaWG)

Android-приложение для подключения к VPN-серверам через протокол **WireGuard / AmneziaWG**. Поддерживает до 6 конфигураций, бэкап/восстановление, авто-подключение и виджет рабочего стола.

---

## 📱 Скриншот

Зелёный интерфейс с 6 слотами для конфигов, Drawer-меню и индикатором статуса.

---

## 🚀 Быстрый старт

### Скачать APK

Последняя версия: **[Config v1.0.5](https://github.com/gennadyevt-bot/Config_dns/releases/download/v1.0.3/Config_v1.0.3.apk)**

### Сборка из исходников

```bash
git clone https://github.com/gennadyevt-bot/Config_dns.git
cd Config_dns
./gradlew assembleDebug
```

APK будет в `app/build/outputs/apk/debug/app-debug.apk`.

---

## 📁 Структура проекта

```
Config_dns/
├── .github/workflows/android.yml   # CI/CD (GitHub Actions)
├── app/
│   ├── build.gradle.kts            # Зависимости и настройки сборки
│   └── src/main/
│       ├── AndroidManifest.xml     # Манифест с VpnService и FileProvider
│       ├── java/com/config/app/
│       │   ├── MainActivity.kt     # Главный экран, UI, Drawer, диалоги
│       │   ├── VpnManager.kt       # Управление VPN через GoBackend
│       │   ├── WgTunnel.kt         # Реализация Tunnel для AmneziaWG
│       │   ├── ServerInfo.kt       # Data class конфигурации сервера
│       │   ├── VpnStatus.kt        # Enum статусов подключения
│       │   ├── ServerAdapter.kt    # RecyclerView адаптер для списка серверов
│       │   ├── ServerStorage.kt    # Сохранение конфигов в SharedPreferences (JSON)
│       │   ├── ServerBackupManager.kt  # Экспорт/импорт бэкапов
│       │   ├── AutoConnectStorage.kt   # Настройка авто-подключения
│       │   └── StopVpnWidget.kt    # Виджет рабочего стола
│       └── res/
│           ├── layout/
│           │   ├── activity_main.xml       # Главный экран (DrawerLayout)
│           │   ├── item_server.xml         # Карточка сервера
│           │   ├── dialog_add_server.xml   # Диалог добавления конфига
│           │   ├── dialog_edit_server.xml  # Диалог редактирования
│           │   ├── nav_header.xml          # Шапка Drawer-меню
│           │   └── widget_stop_vpn.xml     # Layout виджета
│           ├── drawable/
│           │   ├── menu_container_bg.xml   # Фон меню
│           │   ├── nav_item_bg.xml         # Селектор пунктов меню
│           │   ├── ic_menu.xml             # Иконка гамбургер
│           │   ├── ic_menu_backup.xml      # Иконка Backup
│           │   ├── ic_menu_auto.xml        # Иконка Auto Connect
│           │   └── ic_menu_about.xml       # Иконка About
│           ├── menu/
│           │   └── drawer_menu.xml         # Пункты Drawer-меню
│           ├── values/
│           │   ├── strings.xml             # Строки
│           │   ├── colors.xml              # Цвета (зелёная тема)
│           │   └── themes.xml              # Тема MaterialComponents
│           └── xml/
│               ├── config_widget_info.xml  # Провайдер виджета
│               └── file_provider_paths.xml # Пути FileProvider
├── build.gradle.kts                # Root build script
├── settings.gradle.kts             # Настройки Gradle
└── gradle.properties               # Свойства Gradle
```

---

## 🔧 Технологии

| Компонент | Версия |
|-----------|--------|
| compileSdk | 34 |
| minSdk | 24 (Android 7.0) |
| targetSdk | 34 |
| Kotlin | 2.0.21 |
| Gradle | 8.7 |
| JDK | 17 |
| AmneziaWG | 2.3.7 |
| AndroidX Core | 1.12.0 |
| Material Components | 1.11.0 |
| Coroutines | 1.7.3 |

---

## 🎯 Функционал

- **6 слотов для конфигов** — добавляй, редактируй, удаляй WireGuard/AmneziaWG конфигурации
- **Подключение по тапу** — CONNECT / STOP прямо в списке
- **AmneziaWG обфускация** — поддержка Jc, Jmin, Jmax, S1, S2, H1-H4 (опционально)
- **Бэкап** — экспорт всех конфигов в JSON через системный шаринг
- **Авто-подключение** — автоматически подключается к первому валидному серверу при запуске
- **Виджет** — виджет рабочего стола показывает статус VPN (ON/OFF)
- **Drawer-меню** — Backup, Auto Connect, About

---

## 🐛 История исправлений

### v1.0.3
- **Fix:** `NetworkOnMainThreadException` — `connect()`/`disconnect()` обёрнуты в `CoroutineScope(Dispatchers.IO)`
- **Fix:** Корректные UI-callback'и через `withContext(Dispatchers.Main)`

### v1.0.2
- **Fix:** `Error: null` — `e.toString()` вместо `e.message` для реальной диагностики
- **Fix:** Убраны AmneziaWG параметры из дефолтного конфига (plain WireGuard)
- **Fix:** `VpnService exported="true"` + `foregroundServiceType="specialUse"`
- **Add:** `Log.d` для отладки конфига перед парсингом

### v1.0.1
- **Fix:** `isIpv4ResolutionPreferred()` — исправлен тип с `Boolean?` на `Boolean`
- **Fix:** `VpnManager` — корректное использование `peerAllowedIPs`, `peerPersistentKeepalive`, `PresharedKey`
- **Fix:** Добавлен `FileProvider` для экспорта бэкапов
- **Fix:** Добавлены недостающие иконки меню
- **Add:** Авто-подключение при запуске приложения
- **Add:** Обновление виджета при смене статуса VPN

### v1.0.0
- Первоначальный релиз

---

## 🔐 Права доступа (AndroidManifest)

- `INTERNET` — сетевое соединение
- `ACCESS_NETWORK_STATE` — проверка состояния сети
- `FOREGROUND_SERVICE` — фоновый сервис VPN
- `FOREGROUND_SERVICE_SPECIAL_USE` — специальный foreground сервис (Android 14+)
- `POST_NOTIFICATIONS` — уведомления о статусе VPN
- `WAKE_LOCK` — предотвращение засыпания при подключении
- `BIND_VPN_SERVICE` — создание VPN-туннеля

---

## 🏗️ CI/CD

GitHub Actions (`.github/workflows/android.yml`):
- JDK 17 Temurin
- Gradle 8.7
- `gradle assembleDebug`
- Автоматическая загрузка APK как артефакт

---

## 📄 Лицензия

Проект создан для личного использования.

---

## 👤 Автор

Создан на основе проекта **STOP VPN** (WireGuard/AmneziaWG, Kotlin, Android).

<!-- CI trigger: 1788243202 -->
