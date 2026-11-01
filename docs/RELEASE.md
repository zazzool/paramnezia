# Выпуск версий

## Два ключа

| Ключ | Что подписывает | Где хранить | Если потерять |
|---|---|---|---|
| **Ключ подписи APK** (keystore) | сам APK | секреты репозитория + офлайн-копия | обновить установленные приложения будет нельзя, только переустановка |
| **Ключ релизов** (Ed25519, `PN_RELEASE_SEED`) | манифест релиза для OTA | секрет репозитория + офлайн-копия | новый ключ = новая сборка, которую придётся поставить вручную |

Оба ключа созданы заново 1.11.2026 (версия 2.0.0, пакет `net.paramnezia`; ключи 1.x с пакетом
`io.paramnezia` больше не используются) и лежат в репозитории зашифрованными: `keys/release-keys.enc` (AES-256, PBKDF2
600 000 итераций). Внутри `paramnezia.jks` (ключ подписи, алиас `paramnezia`) и `release.env`
(`PN_KEYSTORE_PASSWORD`, `PN_KEY_ALIAS`, `PN_RELEASE_SEED`). Пароль от архива — секрет репозитория
`PN_KEYS_PASSWORD`; держите его копию офлайн (менеджер паролей). Публичные половины ключа релизов —
в `gradle.properties` (`paramnezia.releaseKey`, `paramnezia.releaseNostr`), их вшивает каждая сборка.

Открыть ключи локально:

```sh
openssl enc -d -aes-256-cbc -pbkdf2 -iter 600000 -in keys/release-keys.enc | tar x -C /tmp/pn-keys
```

Сборки CI из PR и `main` подписаны debug-ключом раннера; поверх них релиз ставится только после удаления.

Отпечаток сертификата подписи 2.x (SHA-256):
`1C:19:A3:3B:D3:03:8E:59:41:F0:97:97:6D:C8:66:0E:5B:5A:89:4C:41:8F:8B:81:A7:35:C8:14:A5:0F:5F:20`.

## Если нужно заменить ключи

```sh
keytool -genkeypair -keystore paramnezia.jks -storetype PKCS12 -alias paramnezia -keyalg EC -groupname secp256r1 -validity 36500
./gradlew -Pparamnezia.jvmOnly=true :releasetool:installDist
releasetool/build/install/releasetool/bin/releasetool keygen      # печатает PN_RELEASE_SEED и публичные ключи
printf 'PN_KEYSTORE_PASSWORD=…\nPN_KEY_ALIAS=paramnezia\nPN_RELEASE_SEED=…\n' > release.env
tar c paramnezia.jks release.env | openssl enc -aes-256-cbc -pbkdf2 -iter 600000 -salt -out keys/release-keys.enc
```

Затем обновите публичные ключи в `gradle.properties` и секрет `PN_KEYS_PASSWORD`. Новый ключ подписи
означает переустановку приложения на всех устройствах; новый ключ релизов — ручную установку одной сборки.
Шаг `Open the keys` в `release.yml` сверяет ключ релизов из архива с `gradle.properties` и останавливает выпуск,
если они не совпадают (иначе устройства молча перестали бы принимать обновления).

Если нужно разорвать связь с прошлыми сборками совсем (как в 2.0.0 из-за Play Protect), меняется и `applicationId`
вместе с пакетами кода: `app/build.gradle.kts`, каталоги `*/src/*/kotlin|java/net/paramnezia`, `jni.c` (имена
`Java_net_paramnezia_…`), `go.mod`, proguard-правила, `baseline-prof.txt`, `apk-audit.sh`, `vpn-e2e.sh`,
`intent://…;package=` на странице приглашения и `--package` по умолчанию в `releasetool`. Старые установки такую
сборку как обновление не получат: удалить старое приложение, поставить новое.

## Выпуск

```sh
git tag v2.1.0 && git push origin v2.1.0
# или, если теги пушить нельзя (так выпускает Claude):
git push origin main:release/v2.1.0
```

Тег релиза при выпуске из ветки создаёт сам `gh release create`.

`release.yml`:

1. Собирает APK, подписанный вашим ключом, с вшитыми `PN_RELEASE_KEY`/`PN_RELEASE_NOSTR`; `versionCode` —
   номер запуска workflow, `versionName` — тег.
2. Создаёт GitHub Release с APK.
3. `releasetool publish`: подписывает манифест релиза, загружает APK на Blossom-серверы, публикует событие в
   Nostr и прикладывает `release.json` к релизу.

Дальше устройства находят релиз в Nostr или через gossip, скачивают его заранее и показывают «Установить»:
ставится после нажатия и подтверждения Android. Админ-устройство после обновления просит серверы флота скачать
релиз самим (доступно внутри туннеля даже при блокировке всего остального).

Перед публикацией `apk-audit.sh --release` проверяет APK по правилам из [PLAY-PROTECT.md](PLAY-PROTECT.md); APK
публикуется ровно таким, каким его подписал apksigner. Описание релиза берётся из `docs/releases/vX.Y.Z.md`, если
такой файл есть. Перезапуск упавшего выпуска безопасен: релиз и файлы перезаписываются (`--clobber`).

## `releasetool`

```
releasetool keygen
releasetool publish --apk F --version-code N --version-name S [--blossom URL]… [--relay URL]… [--url URL]…
                    [--signer-sha256 HEX] [--notes S] [--out release.json]      # нужен PN_RELEASE_SEED
```

## Сборки без релиза

Каждый пуш в PR и в `main` собирает APK и кладёт свежие файлы в ветку `apk-builds`
(`paramnezia-ci-release-debugkey.apk`, `paramnezia-ci-debug.apk`); там же они видны в артефактах запуска
Actions. Они подписаны debug-ключом раннера: только для проверки, поверх релиза не встанут.
