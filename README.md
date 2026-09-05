# Config VPN — Android клиент (WireGuard / AmneziaWG)

Приложение для подключения к VPN через протокол **WireGuard / AmneziaWG**. До 6 конфигураций, авто-подключение, App VPN, бэкап, виджет.

---

## ⬇ Скачать

| Версия | Ссылка |
|---|---|
| **v4.7.10** (последняя) | [Config_v4.7.10.apk](https://github.com/gennadyevt-bot/Config_dns/releases/download/v4.7.10/Config_v4.7.10.apk) |
| v4.7.9 | [Config_v4.7.9.apk](https://github.com/gennadyevt-bot/Config_dns/releases/download/v4.7.9/Config_v4.7.9.apk) |
| v4.7.8 | [Config_v4.7.8.apk](https://github.com/gennadyevt-bot/Config_dns/releases/download/v4.7.8/Config_v4.7.8.apk) |
| v4.7.7 | [Config_v4.7.7.apk](https://github.com/gennadyevt-bot/Config_dns/releases/download/v4.7.7/Config_v4.7.7.apk) |
| v4.7.6 | [Config_v4.7.6.apk](https://github.com/gennadyevt-bot/Config_dns/releases/download/v4.7.6/Config_v4.7.6.apk) |
| v4.7.5 | [Config_v4.7.5.apk](https://github.com/gennadyevt-bot/Config_dns/releases/download/v4.7.5/Config_v4.7.5.apk) |
| v4.6.0 | [Config_v4.6.0.apk](https://github.com/gennadyevt-bot/Config_dns/releases/download/v4.6.0/Config_v4.6.0.apk) |

---

## 📘 Быстрый старт

### 1. Установка и первый запуск
1. Скачай APK → установи
2. Открой приложение → нажми **CONNECT** на любом сервере
3. Android спросит разрешение VPN → нажми **"Разрешить"** (один раз)

### 2. Добавить сервер
- **Вручную**: Меню (☰) → Добавить сервер → вставь ключи
- **QR-код**: Нажми "QR" → отсканируй код
- **Файл**: Нажми "Файлы" → выбери `.conf` или картинку с QR

### 3. Подключить / Отключить
- **CONNECT** — включить VPN
- **STOP** — отключить VPN

### 4. App VPN (авто-включение по приложению)
1. Меню (☰) → **App VPN**
2. Выбери приложения (Telegram, Chrome...)
3. Нажми **Сохранить**
4. Открой выбранное приложение → VPN включится автоматически
5. Закрой приложение → VPN отключится (через 60 сек)

### 5. Domain VPN (экспериментально)
1. Меню (☰) → **Domain VPN**
2. Добавь домены (например: `rutracker.org`)
3. Включи **Accessibility Service** в настройках телефона
4. При переходе по ссылкам на эти домены VPN включится

### 6. Бэкап
- Меню (☰) → **Backup** → Share → отправь JSON-файл
- На новом телефоне: Файлы → выбери JSON → импортируй

### 7. Авто-подключение при запуске
- Меню (☰) → **Auto Connect** → включи тумблер

---

## ⚙️ Разрешения телефона (обязательно!)

Чтобы VPN не отключался:

| Разрешение | Где включить | Зачем |
|---|---|---|
| **Не ограничивать батарею** | Настройки → Приложения → Config → Батарея → "Не ограничивать" | Чтобы система не убивала сервис |
| **Автозапуск** | Настройки → Приложения → Config → Автозапуск → ВКЛ | Запуск после перезагрузки |
| **Не приостанавливать** | Настройки → Приложения → Config → "Приостанавливать неиспользуемые" → ВЫКЛ | Не останавливать фоновый сервис |
| **Статистика трафика** | Настройки → Приложения → Config → Статистика трафика → ВКЛ | Для App VPN (определение foreground app) |
| **Accessibility** | Настройки → Спец. возможности → Config VPN Domain Service → ВКЛ | Для Domain VPN |
| **Уведомления** | Настройки → Приложения → Config → Уведомления → ВКЛ | Статус VPN в шторке |

---

## 🛠 Технологии

- Kotlin, WireGuard/AmneziaWG, AndroidX
- compileSdk 34, minSdk 24
- CI/CD: GitHub Actions

---

## 📄 Лицензия

Проект для личного использования.
