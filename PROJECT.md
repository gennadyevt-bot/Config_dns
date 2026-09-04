# PROJECT.md — Техническая документация Config

## Общая информация

- **Название:** Config
- **Пакет:** `com.config.app`
- **Репозиторий:** `gennadyevt-bot/Config_dns`
- **База:** STOP VPN v4.0.0 (WireGuard/AmneziaWG)
- **Язык:** Kotlin
- **Платформа:** Android (minSdk 24, targetSdk 34)
- **Текущая версия приложения:** 4.7.6 (versionCode 15)
- **VPN-библиотеки (две, обязательны):**
  - `com.wireguard.android:tunnel:1.0.20260102` — для серверов БЕЗ junk-параметров (здесь гарантированно работает App VPN / IncludedApplications)
  - `com.zaneschepke:amneziawg-android:2.3.7` — для серверов С junk-параметрами Jc/Jmin/Jmax/S1/S2/H1-H4 (обход DPI РНК)

---

## Двойной бэкенд (ключевая архитектура, введена в v4.5.0+)

| Тип конфига | Бэкенд | App VPN | Обход DPI РНК |
|---|---|---|---|
| Обычный (jc=0 или пусто) — VPNJantit и т.п. | WireGuard (`com.wireguard`) | ✅ IncludedApplications | — |
| С junk-параметрами (jc≠0) — WARP от llimonix | AmneziaWG (`org.amnezia.awg`) | ✅ (поддержка подтверждена байткодом) | ✅ junk-пакеты |

- **Автовыбор:** `server.jc.isNotEmpty() && server.jc != "0"` → AmneziaWG, иначе WireGuard.
- **ВАЖНО:** переход на единый AmneziaWG-бэкенд НЕ делать — пользователь подтвердил, что раньше App VPN с AmneziaWG не работал (причина перехода 01.09). Двойная схема проверена в бою.
- Оба AAR содержат встроенный VpnService (`GoBackend$VpnService` / `AbstractBackend$VpnService` с BIND_VPN_SERVICE в мердженом манифесте) — свой VpnService-класс в приложении НЕ нужен, сервис стартует автоматически.
- AmneziaWG 2.3.7 парсит и применяет `IncludedApplications` (проверено сканированием байткода: GoBackend вызывает `getIncludedApplications()` и `addAllowedApplication()`).

### Поток данных при подключении

```
CONNECT → VpnService.prepare() проверка
        → VpnKeepAliveService стартует (foreground)
        → AppVpnStorage.getSelectedPackages() (если App VPN включён)
        → buildConfigString(): санитайзеры + junk/IncludedApplications
        → Config.parse() (у выбранного бэкенда)
        → backend.setState(tunnel, UP, config)
        → через 10 сек: проверка статистики — если rx=0, тост
          «Сервер не отвечает: конфиг устарел или IP заблокирован»
```

---

## Санитайзеры конфига (VpnManager.buildConfigString)

1. **AllowedIPs:** IPv6-префиксы (включая `::/0`) сохраняются, только если у интерфейса есть IPv6-адрес; иначе все v6-маршруты удаляются (маршрутизация v6 в туннель без v6-адреса = чёрная дыра = «VPN блокирует интернет») и добавляются Telegram-v6 blackhole-префиксы; если список пуст — `0.0.0.0/0`. Вырезание `::/0` при живом v6-адресе (баг до 4.7.5) отправляло IPv6 Telegram мимо туннеля прямо в блок РНК.
2. **DNS:** IPv6-DNS удаляются без IPv6 на интерфейсе (иначе бэкенд мог не поднять туннель); fallback `1.1.1.1`.
3. **Telegram IPv6 blackhole (v4.5.5):** если нет IPv6 на интерфейсе, в AllowedIPs добавляются ТОЛЬКО v6-диапазоны DC Telegram (`2001:67c:4e8::/48`, `2001:b28:f23d::/48`, `2001:b28:f23f::/48`, `2a0a:f280::/32`) → направляются в туннель и умирают мгновенно (fast-fail) → Telegram сразу падает на IPv4 через VPN. Без фикса Telegram пробовал v6 в обход туннеля и висел на блоке РНК («Connecting...»).

---

## App VPN (per-app routing)

- Экран: Меню → App VPN (`AppVpnActivity`): список приложений, режимы включить/исключить.
- **Выбор конфига (v4.5.4):** Spinner «Конфиг для App VPN» вверху экрана — авто-подключение идёт на ВЫБРАННЫЙ сервер (хранится в `AppVpnStorage.getServerId()`), фолбэк — первый валидный.
- `AppMonitorService` следит за foreground-приложением: приложение из списка на переднем плане → автоподключение; ушло → авто-отключение через grace-период 60 сек (v4.7.5; быстрые переключения приложений не рвут VPN, т.к. реконнект ~30 сек).
- Подключение с выбранными приложениями; при ошибке IncludedApplications — фолбэк «VPN для всех» + тост.

---

## Реестр серверов и конфиги

- 6 слотов серверов (`ServerStorage`, SharedPreferences+JSON).
- Демо-сервер VPNJantit (premiusa2.vpnjantit.com:1024) — **мёртв, VPNJantit целиком заблокирован РНК по IP**. Не использовать для тестов.
- **Рабочий рецепт:** конфиги WARP+AmneziaWG с https://warp3.llimonix.pw (генератор работает в РФ, .conf с AWG-параметрами). Импорт: Файлы → выбрать .conf.
- Внимание: WARP-endpoints иногда блокируются по IP даже с junk — тогда генерировать новый конфиг (endpoint меняется).

---

## Классы и ответственность

| Класс | Ответственность |
|-------|----------------|
| `MainActivity` | UI, Drawer, диалоги, RecyclerView серверов |
| `VpnManager` | connect/disconnect, выбор бэкенда, санитайзеры, buildConfigString, предупреждение о мёртвом сервере |
| `WgTunnel` | `com.wireguard.android.backend.Tunnel` (WireGuard-путь) |
| `AwgTunnel` | `org.amnezia.awg.backend.Tunnel` (AmneziaWG-путь) |
| `ServerInfo` | Data class полей конфига (вкл. jc/jmin/jmax/s1/s2/h1-h4) |
| `WgConfigParser` | Парсинг .conf, извлечение AWG-параметров |
| `ServerStorage` / `AppVpnStorage` / `VpnStateStorage` / `AutoConnectStorage` | SharedPreferences-хранилища |
| `AppVpnActivity` / `AppMonitorService` | App VPN: выбор приложений + конфига, мониторинг foreground |
| `VpnKeepAliveService` | Foreground keep-alive, переподключение, индикатор прогресса в уведомлении |
| `VpnActionReceiver` / `BootReceiver` / `AutoConnectManager` | Приёмники (виджет, загрузка) |
| `StopVpnWidget` | Виджет подключения |

---

## Работа с репозиторием (важно для CI/релизов)

- **`git push` из среды Kimi ненадёжен (GnuTLS timeout)** — надёжный способ пуша: GitHub API: `POST /git/blobs` (base64) → `POST /git/trees` (base_tree = HEAD tree) → `POST /git/commits` (parent = HEAD) → `PATCH /git/refs/heads/main`. После пуша CI стартует автоматически.
- Проверка CI: `GET /actions/runs`, детали: `GET /actions/runs/{id}/jobs`, логи: `GET /actions/runs/{id}/logs` (ZIP, смотреть `build/6_Assemble Debug.txt`, искать `e:` и `FAILURE`).
- Скачивание APK: `GET /actions/runs/{id}/artifacts` → artifact_id → `GET /actions/artifacts/{id}/zip` (внутри app-debug.apk).
- **APK выкладывать ТОЛЬКО через GitHub Release** (`POST /releases` → `POST /releases/{id}/assets?name=...apk` на **uploads.github.com**) и давать пользователю прямую ссылку `browser_download_url`. Sandbox-ссылки на большие APK (~56 МБ) не работают в приложении Kimi на телефоне.
- **Сбивка версий:** версия приложения (versionName) и git-теги разошлись: приложение v4.5.x, теги релизов v4.7.x (v4.5.x–v4.6.x теги заняты старой историей). Новые теги брать v4.7.x и далее.
- Gradle: compileSdk 34, minSdk 24, targetSdk 34, JDK 17, Gradle 8.7, workflow `.github/workflows/android.yml` (assembleDebug + upload-artifact).

---

## История версий (сентябрь 2026)

| Версия | Что |
|---|---|
| 4.4.1 | Санитайзеры AllowedIPs (::/0) и DNS — фикс «VPN блокирует интернет» |
| 4.5.0 | Двойной бэкенд WireGuard+AmneziaWG |
| 4.5.1 | Единый AmneziaWG — ОТКАЧЕНО (App VPN) |
| 4.5.2 | Двойной бэкенд восстановлен + предупреждение о мёртвом сервере (10 сек rx=0) |
| 4.5.3 | IncludedApplications в обоих путях (проверка App VPN на AmneziaWG) |
| 4.5.4 | Выбор конфига (Spinner) в экране App VPN |
| 4.5.5 | Telegram IPv6 blackhole (fast-fail на IPv4 через VPN) |
| 4.7.5 | AllowedIPs: `::/0` не вырезается, если у интерфейса ЕСТЬ IPv6-адрес — иначе IPv6-трафик Telegram шёл мимо туннеля в блок РНК. AppMonitor: grace-период 60 сек перед авто-отключением (быстрые переключения приложений не рвут VPN) + оценка отключения каждый тик |
| 4.7.6 | Индикатор прогресса подключения (ненавязчивый): статус на экране тикает «CONNECTING… N сек», после 20 сек — «дольше обычного»; текст уведомления в шторке показывает «Подключение… N сек» |

---

## Цветовая схема

Фон `#228B22`, CONNECT `#32CD32`, STOP `#DC143C`, статус Connected `#90EE90`, трафик down `#90EE90` / up `#FF6B6B`, StatusBar `#228B22`, NavigationBar `#000000`.

---

## TODO

- [ ] Импорт бэкапа из JSON
- [x] App VPN (split tunneling) — сделано
- [x] Диагностика мёртвого сервера — сделано (тост по rx=0)
- [ ] Пинг серверов ДО подключения
- [ ] Экран лога handshake для диагностики
- [ ] Счётчик трафика в UI
- [ ] QR-сканер импорта
- [ ] Kill Switch
