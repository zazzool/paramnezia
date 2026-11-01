# Протоколы и форматы

Всё, что передаётся между приложениями, серверами и устройствами. Код ссылок указан у каждого раздела.

## 1. Ключ `vpn://` (совместимость с AmneziaVPN)

`core/amnezia/AmneziaKey.kt`, `AmneziaFormat.kt`

```
vpn://<base64url без '='>( qCompress( JSON ) )
qCompress = 4 байта длины (big-endian) + zlib
```

JSON — раскладка AmneziaVPN: `containers[]` с `container: "amnezia-awg2"` (или `amnezia-awg`, `amnezia-wireguard`),
внутри `awg.last_config` — строка JSON с `config` (текст awg-quick) и ключами. Ключ с полным доступом
дополнительно содержит `hostName`, `userName`, `password` или ключ SSH. AmneziaVPN игнорирует неизвестные
поля, поэтому Paramnezia кладёт свои данные рядом (поле `paramnezia_fleet`, см. ниже) без потери совместимости.

Серии QR AmneziaVPN (`AmneziaQr.kt`): сжатые данные режутся на куски с заголовком (магия, номер, всего),
каждый кусок — отдельный QR; приложение собирает и импортирует как `vpn://`.

## 2. Флот

`core/fleet/*`, `app/fleet/FleetManager.kt`

### Доверие

`FleetTrust`: `fleetId` (UUID), `adminPublicKey` (Ed25519, base64), `fleetKey` (AES-256, base64).
Создаётся на админ-устройстве; приватный ключ админа (`adminSeed`) хранится только там.

### Манифест

```kotlin
FleetManifest(fleetId, version, issuedAt, servers: [FleetServer], devices: [FleetDevice], mirrors: [url])
FleetServer(id, name, host, port, publicKey, presharedKey?, awg: {Jc, S1, H1, I1…}, dns, mtu, keepalive)
FleetDevice(name, publicKey /* WireGuard */, address /* 10.8.1.N/32, одинаковый на всех серверах */)
```

Первый сервер в списке — основной (мастер), остальные — резервные. `version` только растёт
(`max(old + 1, now)`).

### Упаковка

```
body = base64( nonce(12) || AES-256-GCM(fleetKey, json(manifest), aad = "<fleetId>:<version>") )
sig  = Ed25519(adminSeed, "paramnezia-fleet-v1\n<fleetId>\n<version>\n<body>")
SignedManifest = { fleetId, version, body, sig }   // JSON, это и есть «wire form»
```

Устройство принимает манифест, только если подпись верна для `adminPublicKey`, `fleetId` совпадает и версия
больше текущей. Поэтому любой канал доставки может быть недоверенным.

### Приглашение устройства

Обычный ключ `vpn://` к основному серверу с дополнительным полем:

```json
"paramnezia_fleet": { "trust": FleetTrust, "deviceName": "...", "privateKey": "<WireGuard>", "manifest": "<SignedManifest>" }
```

AmneziaVPN видит обычный ключ; Paramnezia вступает во флот. Для оригинального AmneziaVPN это поле
вырезается (`Invite.plainKey`).

### Каналы доставки манифеста

1. **Сервер, внутри туннеля:** `http://10.8.1.1:8080/fleet.json` (busybox httpd в контейнере `amnezia-awg2`).
2. **Зеркала:** любые HTTPS-URL из `mirrors` (например, gist). Читаются через туннель и напрямую.

Никаких релеев и обмена между устройствами (с 2.1.0, см. [PLAY-PROTECT.md](PLAY-PROTECT.md)): устройство само
читает манифест, как подписку VPN. `sync()` опрашивает 1–2 параллельно и принимает самый новый валидный манифест; при приёме нового
`App.reconnectIfUp()` сразу переключает туннель.

### Отказоустойчивость

`FailoverPolicy`: сервер считается заблокированным, если нет рукопожатия при рабочей сети; устройство сначала
читает манифест (вдруг админ уже сменил мастер), затем берёт следующий сервер манифеста и вернётся к
заблокированному через 6 часов. Новый манифест оно читает уже через рабочий сервер. `HealthBook` (на админе):
если мастер недоступен для самого админа, админ переставляет резервный сервер первым и публикует новый
манифест на серверы и зеркала. Фоновая задача раз в 12 часов читает манифест (`SyncWorker`).

## 3. Обновления

`core/ota/Release.kt`, `app/ota/Updater.kt`, `releasetool`

```kotlin
Release(packageName, versionCode, versionName, minSdk, sha256, size, urls, signerSha256, notes, publishedAt)
SignedRelease(body = json(Release), sig = Ed25519(releaseSeed, "paramnezia-release-v1\n" + body))
```

Публичный ключ релизов вшит в APK (`BuildConfig.RELEASE_KEY`), адрес проверки тоже (`BuildConfig.UPDATE_URL`,
сейчас `https://paramnezia.psync.ru/release.json`). Устройство читает `release.json` оттуда и, во флоте, с
`http://10.8.1.1:8080/release.json`. Релиз принимается, если подпись верна, пакет совпадает, версия больше,
`minSdk` подходит и `signerSha256` равен сертификату установленного APK. Проверка: при запуске приложения не чаще
раза в 12 часов и по кнопке в настройках.

Приложение не скачивает и не ставит APK само. «Скачать» открывает в браузере первый HTTPS-адрес из `urls`
(`https://paramnezia.psync.ru/paramnezia-vX.Y.Z.apk`), дальше обычная установка Android. Админ при открытом
приложении раз в сутки просит серверы скачать релиз самим (`release-mirror.sh`: HTTPS-адреса из релиза, проверка
SHA-256) и положить его в `apk/<sha256>` и `release.json` внутри туннеля и на страницу приглашений.

`UpdateChecker.installed` хранит подписанный релиз установленной версии (тот же versionCode и сертификат):
по нему админ даёт ссылку на тот же APK новым устройствам. `Release.publicApkUrls()` — HTTPS-адреса из `urls`.

## 4. Одноразовые ссылки

`deploy/InviteLinks.kt`, `deploy/…/invite/*`, `deploy/…/release-mirror.sh`, `app/fleet/Links.kt`

**Ссылка:** `http://<сервер>[:порт]/#<токен>`; токен — 16 случайных байт в base64url. Фрагмент `#…` не
уходит на сервер при открытии страницы и не попадает в превью мессенджеров.

**Страница** (`index.html`) определяет платформу по User-Agent. Android: кнопка «Скачать Paramnezia» (адрес из
`app.json`, иначе `/paramnezia.apk` этого сервера; ничего не скачивается само) и кнопка «Открыть приглашение»
(`intent://<ключ без vpn://>#Intent;scheme=vpn;package=net.paramnezia;S.browser_fallback_url=<страница>;end`,
плюс ключ для копирования). iPhone/iPad получает ключ и `.conf` для AmneziaVPN; прочее — выбор.

**`app.json`** (пишет `invite-server.sh` из `PN_APP_JSON`): `{"version", "sha256", "urls": [только https]}`.
**`/paramnezia.apk`** — копия того же релиза, которую сервер скачал сам (`release-mirror.sh`, `PN_INVITE=1`).

**CGI** `GET /cgi-bin/get?<токен>&<что>`, `что` = `app` (приглашение для Paramnezia) | `key` (ключ без флота,
для AmneziaVPN) | `conf`. Первый запрос атомарно (`mv t/T c/T`) гасит ссылку и запоминает `время адрес`;
повтор разрешён только с того же адреса в течение 30 минут. Ответы: 200, 400 (неверный запрос),
410 (использована/истекла).

До версии 1.1.0 приглашение вписывалось в APK Signing Block каждой копии (`pnz1`). Это убрано: см.
[PLAY-PROTECT.md](PLAY-PROTECT.md). Старые серверы чистит `invite-server.sh`.

## 5. Состояние приложения

`app/data/AppState.kt` — один JSON (kotlinx.serialization), зашифрованный ключом из Android Keystore
(AES-256-GCM, `state.bin`, предыдущая исправная версия — `state.bak`). Все поля имеют значения по умолчанию:
новые версии читают старое состояние, неизвестные поля пропускаются.
Основное: `profiles` (самостоятельные серверы), `fleet` (доверие, ключ устройства, манифест, для админа —
`adminSeed`, SSH-доступы, `revoked`, `lastHeal`), `settings`, `hostKeys` (TOFU-отпечатки SSH), `release`, `links`, `uiMode`.
