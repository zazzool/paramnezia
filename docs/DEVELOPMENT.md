# Разработка Paramnezia

Документ для того, кто продолжает проект: как он устроен, где что лежит, как собирать, проверять и
выпускать. Протоколы описаны в [PROTOCOLS.md](PROTOCOLS.md), сервер в [SERVER.md](SERVER.md), выпуск
обновлений в [RELEASE.md](RELEASE.md), общая картина в [ARCHITECTURE.md](ARCHITECTURE.md).

## 1. Окружение

| Что | Версия | Зачем |
|---|---|---|
| JDK | 21 (Temurin) | Gradle, Kotlin, тесты |
| Android SDK | compileSdk 36, build-tools 36+ | приложение |
| Android NDK | любой свежий (CI берёт `ANDROID_NDK_LATEST_HOME`) | сборка Go-библиотеки |
| Go | версия из `tunnel/src/main/go/go.mod` (1.25) | amneziawg-go и DNS-фильтр |
| Docker | любой | e2e-тесты сервера |
| Python | 3.12 | генерация списков обхода |

Gradle 9.7, AGP 9.3, Kotlin 2.3, Compose BOM 2025.09. Версии собраны в `gradle/libs.versions.toml`.

Android Studio открывает проект как обычно. Если Google Maven недоступен (например, в песочнице), JVM-модули
собираются без Android: `./gradlew -Pparamnezia.jvmOnly=true …` (корневой `build.gradle.kts` тогда не тянет AGP).

## 2. Модули

```
core/         чистый Kotlin/JVM, без Android: форматы, криптография, флот, релизы, маршруты
deploy/       JVM: SSH (JSch), скрипты установки сервера, публикация манифеста, страница приглашений
tunnel/       Android-библиотека: VpnService, JNI, Go (amneziawg-go + DNS-фильтр)
app/          Android-приложение: Compose UI, состояние, флот, OTA, виджет, плитка
releasetool/  JVM CLI: ключи релизов, публикация релиза
tools/bypass-lists/  генератор списков «российское напрямую» и блок-листа трекеров
```

### core (`net.paramnezia.core`)

| Пакет | Файлы | Что делает |
|---|---|---|
| `amnezia` | `AmneziaKey`, `AmneziaFormat`, `AmneziaQr` | ключи `vpn://` (qCompress + base64url JSON), JSON-раскладка AmneziaVPN, серии QR |
| `config` | `TunnelConfig`, `WgQuick`, `Uapi`, `AwgParam` | модель туннеля, wg-quick/awg-quick, UAPI для Go, параметры AmneziaWG 1/2/3 |
| `crypto` | `WgKey`, `Curve25519.java` | ключи WireGuard |
| `fleet` | `Fleet`, `FleetManifest`, `FleetCrypto`, `Resilience` | админ/участник флота, манифест, шифрование и подпись, выбор сервера, повышение резервного сервера |
| `ota` | `Release` | релиз, подписанный ключом релизов, проверка, потоковая проверка SHA-256 |
| `routing` | `BypassLists`, `RoutePlanner` | загрузка списков, план маршрутов в бюджете Android (3000) |
| `net` | `IpPrefix`, `PrefixSet` | IPv4/IPv6 префиксы, вычитание множеств |
| `profile` | `Profile` | `SshCredentials`, `Protocol` |

### deploy (`net.paramnezia.deploy`)

| Файл | Что делает |
|---|---|
| `Ssh.kt` | `SshConnection`: `run(script, env)` (скрипт через stdin `bash -s`, результаты строками `::result::k=v`), TOFU-проверка ключа хоста, два захода по всем маршрутам |
| `ServerManager.kt` | `inspect`, `install`, `addClient`, `registerPeer`, `ensurePeers` (много за одно подключение), `removeClient(s)`, `usedAddresses` |
| `ObfuscationProfile.kt` | генерация параметров AmneziaWG (Jc/Jmin/Jmax, S1–S4, H1–H4, I1–I5) |
| `FleetPublisher.kt` | манифест на сервер с проверкой внутритуннельного httpd (`fleet-www.sh`), релиз, который сервер скачивает сам (`release-mirror.sh`), gist |
| `InviteLinks.kt` | одноразовые ссылки: страница, `app.json`, хранение, проверка «использована», отзыв |
| `resources/…/*.sh` | серверные скрипты, см. [SERVER.md](SERVER.md) |

### tunnel (`net.paramnezia.tunnel`)

| Файл | Что делает |
|---|---|
| `ParamneziaVpnService.kt` | поднимает tun, передаёт fd в Go, сторож живости, keepalive по экрану |
| `TunnelSpec.kt` | `TunnelSpec`, `DnsPolicy`, `TunnelHost` (интерфейс к приложению), `TunnelRuntime` (состояние, статистика, здоровье) |
| `AwgNative.kt` | JNI: `turnOn/turnOff/getConfig/setConfig/setDnsRules/dnsBlocked/ed25519*` |
| `go/api.go` | экспорт в C: запуск amneziawg-go на fd |
| `go/dns.go` | DNS-фильтр внутри tun: блок, «российское напрямую», кэш |
| `go/jni.c` | JNI-обвязка |
| `build-go.sh` | сборка `libparamnezia.so` под ABI (патч Go на CLOCK_BOOTTIME) |

### app (`net.paramnezia.app`)

| Файл | Что делает |
|---|---|
| `App.kt` | процесс приложения и `TunnelHost`: выбор сервера, failover, самовосстановление серверов (админ, при открытом приложении), видимость адресов |
| `data/AppState.kt` | всё состояние приложения (сериализуется) |
| `data/Vault.kt`, `data/Repo.kt` | зашифрованное (Keystore AES-GCM) хранилище: атомарная запись, `state.bak`, нечитаемое откладывается; асинхронная загрузка |
| `data/Servers.kt` | импорт ключей/QR/файлов, деплой VPS, экспорт (в том числе всех ключей с полным доступом) |
| `fleet/FleetManager.kt` | флот: вступление, синхронизация (сервер в туннеле, HTTPS-зеркала), управление, `heal` |
| `fleet/Links.kt` | одноразовые ссылки со стороны админа, подготовка серверов заранее |
| `ota/Updater.kt` | децентрализованное обновление: фоновая загрузка, установка по нажатию |
| `tunnel/SpecBuilder.kt` | собирает `TunnelSpec`: маршруты, исключённые приложения, DNS-политика |
| `tunnel/Net.kt` | HTTP через туннель и напрямую |
| `net/Visibility.kt` | «как вас видят сайты»: зарубежный и российский адрес |
| `work/SyncWorker.kt` | синхронизация раз в 12 часов |
| `ui/*` | Compose: `MainActivity`, `HomeScreen`, `PowerButton`, `Theme`, `AddScreen`, `FleetScreen`, `SettingsScreen`, `SimpleScreen`, `Qr`, `QuickTile`, `WidgetProvider` (+ `ToggleActivity` для кнопки виджета) |

## 3. Сборка и проверка

```sh
./gradlew :app:assembleDebug :app:assembleRelease   # APK (нужны SDK, NDK, Go)
./gradlew -Pparamnezia.jvmOnly=true test             # unit-тесты core, deploy, releasetool
PN_E2E=1 ./gradlew -Pparamnezia.jvmOnly=true :deploy:test   # настоящий сервер в Docker (нужен root)
(cd tunnel/src/main/go && go vet ./... && go test -race ./...)
```

APK: `app/build/outputs/apk/{debug,release}/`. Release без ключа подписывается debug-ключом: ставится, но
OTA в нём выключены (нет `RELEASE_KEY`). Debug имеет `applicationId` `net.paramnezia.debug` и ставится рядом.

### Тесты

| Тест | Что проверяет |
|---|---|
| `AmneziaTest` | совместимость `vpn://`, multi-QR, раскладка JSON AmneziaVPN |
| `WgQuickTest`, `CryptoTest` | конфиги и ключи |
| `FleetTest` | смена мастера без участия пользователя, приглашения, адреса устройств |
| `ResilienceTest` | failover и кворум |
| `OtaTest` | подпись релизов, выбор лучшего, установленный релиз, публичные адреса, проверка SHA-256 |
| `RoutingTest` | ключевые российские сервисы идут напрямую, бюджет маршрутов |
| `DeployE2ETest` | установка сервера, клиенты (в том числе пачкой и с занятым адресом), рукопожатие, перезапуск внутритуннельного httpd, зеркало релиза; одноразовая ссылка на настоящем контейнере, `app.json`, копия APK на сервере |
| Go `dns_test.go` | правила, блок, разделение DNS |

### CI (`.github/workflows/build.yml`)

1. **JVM** — все unit-тесты и e2e с Docker.
2. **Go** — `vet` и `test -race`.
3. **Android APK** — debug и release; `apk-audit.sh` (правила Play Protect: подпись, блок подписи без чужих
   записей, разрешения по списку, не debuggable, targetSdk); свежие APK (подписаны debug-ключом раннера, только
   для проверки) принудительно кладутся в ветку `apk-builds`; lint как отчёт.
4. **Starts on an emulator (29, 34)** — ставит release APK на эмуляторы Android 10 и 14, запускает, падает
   при `FATAL EXCEPTION` или если процесс умер. Журнал падения печатается прямо в лог задачи.

Еженедельно `bypass-lists.yml` обновляет списки и открывает PR (сначала прогоняя `RoutingTest`).

## 4. Как устроены ключевые вещи

**Запуск.** `App.onCreate` ничего не ждёт: хранилище читается в фоне (`Repo.ready`), WorkManager, Updater и
Go-библиотека стартуют лениво. `MainActivity` показывает экран, когда `ready` стал `true` (миллисекунды).
`baseline-prof.txt` компилирует путь запуска при установке.

**Туннель.** `ParamneziaVpnService.connect` строит `VpnService.Builder` по `TunnelSpec`, передаёт fd в Go
(`AwgNative.turnOn`), защищает UDP-сокеты от VPN (`protect`). Своё приложение остаётся внутри VPN (нужно для
внутритуннельных адресов); каналы, которые должны работать при мёртвом туннеле, идут через
`TunnelRuntime.directNetwork()`.

**Сторож живости.** Каждую минуту при включённом экране и раз в 15 минут при выключенном читает счётчики
туннеля (без сети). Если рукопожатие старше 180 с или мы отправляли, а ответа не было, шлёт DNS-запрос
через туннель (1.1.1.1/8.8.8.8/9.9.9.9). Нет ответа: туннель пересоздаётся (новые сокеты), после двух неудач
подряд спрашивает у `App` другой сервер. Проверки сразу после подключения (20 с), смены сети (15 с) и
включения экрана (3 с). Пинг и число переподключений видны на главном экране (`TunnelRuntime.health`).

**Российское напрямую.** Три слоя: приложения по пакетам (`assets/bypass/apps.txt`) исключаются из VPN;
российские IP (RIR + ASN ключевых сервисов первыми) исключаются маршрутами; DNS-фильтр в Go отправляет
российские имена к Яндекс DNS вне туннеля, чтобы CDN выдавали российские адреса.

**Трекеры.** Тот же DNS-фильтр отвечает `0.0.0.0`/`::` на имена из `blocklist.txt`. `allow.txt` — исключения.

**Флот, OTA, приглашения** — в [PROTOCOLS.md](PROTOCOLS.md).

## 5. Частые задачи

- **Добавить российский сервис.** Домен — в `tools/bypass-lists/extra-direct.txt`; ASN — в `KEY_ASNS` в
  `generate.py`; приложение — в `app/src/main/assets/bypass/apps.txt`. Запустить `python3 tools/bypass-lists/generate.py`,
  добавить проверку в `RoutingTest.keyRussianServicesGoDirect`.
- **Добавить трекер.** `tools/bypass-lists/trackers-ru.txt`, затем генератор.
- **Поменять параметры маскировки.** `deploy/ObfuscationProfile.kt`.
- **Новая настройка.** Поле в `Settings` (`AppState.kt`, у всех полей есть значения по умолчанию, чтобы старое
  состояние читалось), переключатель в `SettingsScreen`, использование в `SpecBuilder`.

## 6. Подводные камни

- **R8.** Всё, что создаётся рефлексией, нужно держать в `app/proguard-rules.pro`: JSch, сериализаторы,
  WorkManager/Room (без этого release падал на старте). После правок правил смотрите задачу эмулятора в CI.
- **AGP 9.** `sourceSets.jniLibs.srcDir` не работает; Go-библиотека кладётся в стандартный `src/main/jniLibs`.
- **sudo и окружение.** `sudo` сбрасывает переменные: в скриптах значения передаются в контейнер явно
  (`docker exec -e "VAR=$VAR"`).
- **Адреса флота** одинаковы на всех серверах; новые устройства пропускают адреса, уже занятые на серверах
  (`ServerManager.usedAddresses`).
- **Android 13+** использует `excludeRoute`; до 13 маршруты считаются как дополнение (меньше точность, тот же бюджет).
- **Play Protect.** Перед тем как трогать установку, обновления, ссылки или разрешения, прочитайте
  [PLAY-PROTECT.md](PLAY-PROTECT.md). Главное: один APK для всех, приложение не трогает APK-файлы, установка
  только с подтверждением, новые разрешения только через список в `apk-audit.sh`.
- **Отладка на телефоне.** `adb logcat -s ParamneziaVpn Paramnezia PnFleet PnLinks PnRepo AndroidRuntime`.

## 7. Что дальше

См. раздел «Планы» в [ARCHITECTURE.md](ARCHITECTURE.md#планы).
