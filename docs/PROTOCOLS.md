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
2. **Зеркала:** любые URL из `mirrors` (например, gist). Читаются через туннель и напрямую.
3. **Nostr** (см. ниже): слоты админа и каждого устройства на нескольких релеях.
4. **Gossip:** UDP внутри туннеля между устройствами флота.

`sync()` опрашивает 1–3 параллельно и принимает самый новый валидный манифест; при приёме нового
`App.reconnectIfUp()` сразу переключает туннель.

### Отказоустойчивость

`FailoverPolicy`: сервер считается заблокированным, если нет рукопожатия при рабочей сети; устройство берёт
следующий сервер манифеста и вернётся к заблокированному через 6 часов. `HealthBook` (на админе): если о
недоступности мастера сообщили ≥ 2 устройства за 15 минут, админ сам переставляет резервный сервер первым
и публикует новый манифест.

## 3. Nostr

`core/nostr/*`

- Событие NIP-01, подпись Schnorr BIP-340 (своя реализация, проверена официальными векторами).
- Kind **30078** (NIP-78, заменяемое приложением событие), тег `d` — слот.
- Ключи Nostr выводятся из ключа флота, ничего дополнительно хранить не нужно:
  `secret(device) = SHA-256("paramnezia-nostr-v1" || fleetKey || devicePublicKey || counter)`; для админа
  `devicePublicKey = "admin"`.
- Слот флота: `d = hex(SHA-256("paramnezia-fleet-tag-v1" || fleetId || fleetKey))[0:32]`, теги `["v", version]`,
  содержимое — `SignedManifest`. Релеи видят только непрозрачный тег и зашифрованный манифест.
- Слот релизов флота: тот же тег с суффиксом `-r`, содержимое — `SignedRelease`.
- Официальные релизы: `d = "paramnezia-release-v1"`, автор — ключ Nostr, выведенный из ключа релизов.
- Релеи по умолчанию: `NostrFleet.DEFAULT_RELAYS` (damus, nos.lol, nostr.band, nostr.mom, …); свои задаются в
  настройках (`Settings.relays`).
- Клиент: `NostrClient` — RFC 6455 поверх TLS, `REQ`/`EVENT`/`EOSE`/`OK`, работает через прямую сеть
  (`Net.directSockets()`), чтобы синхронизация шла и при мёртвом туннеле.

## 4. Gossip между устройствами

`GossipMessage` в `core/fleet/Resilience.kt`. UDP-порт **51999** на адресе устройства внутри туннеля,
принимаются только пакеты от адресов устройств флота. JSON с дискриминатором `t`:

| `t` | Поля | Смысл |
|---|---|---|
| `version` | `fleetId`, `version` | «у меня такая версия»; у кого новее, отвечает `manifest` |
| `manifest` | `signed` | новый манифест |
| `health` | `serverId`, `reachable`, `at` | отчёт о доступности сервера (для кворума админа) |
| `release` | `signed` | проверенный релиз приложения |

После каждого подключения устройство рассылает `version` (через 3 с).

## 5. Обновления по воздуху (OTA)

`core/ota/Release.kt`, `core/nostr/NostrRelease.kt`, `app/ota/Updater.kt`, `releasetool`

```kotlin
Release(packageName, versionCode, versionName, minSdk, sha256, size, urls, blossom, signerSha256, notes, publishedAt)
SignedRelease(body = json(Release), sig = Ed25519(releaseSeed, "paramnezia-release-v1\n" + body))
```

Публичный ключ релизов вшит в APK (`BuildConfig.RELEASE_KEY`). Устройство принимает релиз из любого канала
(Nostr, gossip, `http://10.8.1.1:8080/release.json`), если подпись верна, пакет совпадает, версия больше,
`minSdk` подходит и `signerSha256` равен сертификату установленного APK.

APK ищется по SHA-256: Blossom (`<server>/<sha256>`, BUD-01), `http://10.8.1.1:8080/apk/<sha256>` (внутри
туннеля), затем `urls` (GitHub Releases). Байты проверяются потоково (`verifyingCopy`) до установки.
Скачать можно заранее (`Settings.autoUpdate`), установка — только по нажатию пользователя: `PackageInstaller`
с `USER_ACTION_REQUIRED`, Android показывает своё окно подтверждения. Админ после обновления (и раз в сутки)
просит серверы скачать релиз самим (`release-mirror.sh`: HTTPS-адреса из релиза, проверка SHA-256) и положить
его в `apk/<sha256>` и `release.json` внутри туннеля. Устройства переопубликовывают релиз в слот `-r`.

`UpdateChecker.installed` хранит подписанный релиз установленной версии (тот же versionCode и сертификат):
по нему админ даёт ссылку на тот же APK новым устройствам. `Release.publicApkUrls()` — адреса для браузера:
Blossom с расширением `.apk` (BUD-01) и HTTPS-адреса из `urls`.

## 6. Одноразовые ссылки

`deploy/InviteLinks.kt`, `deploy/…/invite/*`, `deploy/…/release-mirror.sh`, `app/fleet/Links.kt`

**Ссылка:** `http://<сервер>[:порт]/#<токен>`; токен — 16 случайных байт в base64url. Фрагмент `#…` не
уходит на сервер при открытии страницы и не попадает в превью мессенджеров.

**Страница** (`index.html`) определяет платформу по User-Agent. Android: кнопка «Скачать Paramnezia» (адрес из
`app.json`, иначе `/paramnezia.apk` этого сервера; ничего не скачивается само) и кнопка «Открыть приглашение»
(`intent://<ключ без vpn://>#Intent;scheme=vpn;package=io.paramnezia;S.browser_fallback_url=<страница>;end`,
плюс ключ для копирования). iPhone/iPad получает ключ и `.conf` для AmneziaVPN; прочее — выбор.

**`app.json`** (пишет `invite-server.sh` из `PN_APP_JSON`): `{"version", "sha256", "urls": [только https]}`.
**`/paramnezia.apk`** — копия того же релиза, которую сервер скачал сам (`release-mirror.sh`, `PN_INVITE=1`).

**CGI** `GET /cgi-bin/get?<токен>&<что>`, `что` = `app` (приглашение для Paramnezia) | `key` (ключ без флота,
для AmneziaVPN) | `conf`. Первый запрос атомарно (`mv t/T c/T`) гасит ссылку и запоминает `время адрес`;
повтор разрешён только с того же адреса в течение 30 минут. Ответы: 200, 400 (неверный запрос),
410 (использована/истекла).

До версии 1.1.0 приглашение вписывалось в APK Signing Block каждой копии (`pnz1`). Это убрано: см.
[PLAY-PROTECT.md](PLAY-PROTECT.md). Старые серверы чистит `invite-server.sh`.

## 7. Состояние приложения

`app/data/AppState.kt` — один JSON (kotlinx.serialization), зашифрованный ключом из Android Keystore
(AES-256-GCM, `state.bin`, предыдущая исправная версия — `state.bak`). Все поля имеют значения по умолчанию:
новые версии читают старое состояние, неизвестные поля пропускаются.
Основное: `profiles` (самостоятельные серверы), `fleet` (доверие, ключ устройства, манифест, для админа —
`adminSeed`, SSH-доступы, `revoked`, `lastHeal`), `settings`, `hostKeys` (TOFU-отпечатки SSH), `release`, `links`, `uiMode`.
