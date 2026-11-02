# Выпуск версий

## Два ключа

| Ключ | Что подписывает | Где хранить | Если потерять |
|---|---|---|---|
| **Ключ подписи APK** (keystore) | сам APK | секреты репозитория + офлайн-копия | обновить установленные приложения будет нельзя, только переустановка |
| **Ключ релизов** (Ed25519, `PN_RELEASE_SEED`) | `release.json` (проверка обновлений) | секрет репозитория + офлайн-копия | новый ключ = новая сборка, которую придётся поставить вручную |

Оба ключа созданы заново 1.11.2026 (версия 2.0.0, пакет `net.paramnezia`; ключи 1.x с пакетом
`io.paramnezia` больше не используются) и лежат в репозитории зашифрованными: `keys/release-keys.enc` (AES-256, PBKDF2
600 000 итераций). Внутри `paramnezia.jks` (ключ подписи, алиас `paramnezia`) и `release.env`
(`PN_KEYSTORE_PASSWORD`, `PN_KEY_ALIAS`, `PN_RELEASE_SEED`). Пароль от архива — секрет репозитория
`PN_KEYS_PASSWORD`; держите его копию офлайн (менеджер паролей). Публичная половина ключа релизов —
в `gradle.properties` (`paramnezia.releaseKey`), её вшивает каждая сборка.

Открыть ключи локально:

```sh
openssl enc -d -aes-256-cbc -pbkdf2 -iter 600000 -in keys/release-keys.enc | tar x -C /tmp/pn-keys
```

Сборки CI из PR и `main` подписаны debug-ключом раннера; поверх них релиз ставится только после удаления.

Отпечаток сертификата подписи (SHA-256) — в `keys/signing-cert-sha256.txt`.

## Если нужно заменить ключи

Проще всего в GitHub Actions (так созданы ключи 2.0.0, они никогда не покидали Actions):

1. Задайте новое случайное значение секрета `PN_KEYS_PASSWORD` (Settings → Secrets and variables → Actions) и
   сохраните копию офлайн.
2. Запушьте ветку `keys/rotate-<что угодно>`. Workflow `rotate-keys.yml` создаст keystore и ключ релизов,
   зашифрует их этим паролем, проверит расшифровку и закоммитит в ту же ветку `keys/release-keys.enc`,
   `keys/signing-cert-sha256.txt` и публичные ключи в `gradle.properties`.
3. Смёржите ветку.

Вручную:

```sh
keytool -genkeypair -keystore paramnezia.jks -storetype PKCS12 -alias paramnezia -keyalg EC -groupname secp256r1 -validity 36500
./gradlew -Pparamnezia.jvmOnly=true :releasetool:installDist
releasetool/build/install/releasetool/bin/releasetool keygen      # печатает PN_RELEASE_SEED и публичный ключ
printf 'PN_KEYSTORE_PASSWORD=…\nPN_KEY_ALIAS=paramnezia\nPN_RELEASE_SEED=…\n' > release.env
tar c paramnezia.jks release.env | openssl enc -aes-256-cbc -pbkdf2 -iter 600000 -salt -out keys/release-keys.enc
```

Затем обновите публичные ключи в `gradle.properties` и секрет `PN_KEYS_PASSWORD`. Новый ключ подписи
означает переустановку приложения на всех устройствах; новый ключ релизов — ручную установку одной сборки.
Шаг `Check that the release key matches` в `release.yml` сверяет ключ релизов из архива с `gradle.properties` и останавливает выпуск,
если они не совпадают (иначе устройства молча перестали бы видеть обновления).

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

1. Собирает APK, подписанный вашим ключом, с вшитыми `PN_RELEASE_KEY` и `UPDATE_URL`; `versionCode` — номер
   запуска workflow, `versionName` — тег.
2. Создаёт GitHub Release с APK.
3. `releasetool sign`: подписывает `release.json` (ссылка на APK в этом же релизе) и прикладывает его к релизу.

Дальше приложения при запуске (не чаще раза в 12 часов) видят новую версию и показывают «Скачать». Админ-устройство
при открытом приложении просит серверы семьи скачать релиз самим (доступно внутри туннеля даже при блокировке
всего остального).

Приложение проверяет `https://github.com/zazzool/paramnezia/releases/latest/download/release.json`. Для этого
релизы должны открываться без входа в GitHub, то есть репозиторий должен быть публичным. В закрытом репозитории
проверка обновлений молча не находит ничего: настройки семейной группы это не затрагивает, APK раздаётся вручную.

Перед публикацией `apk-audit.sh --release` проверяет APK по правилам из [PLAY-PROTECT.md](PLAY-PROTECT.md); APK
публикуется ровно таким, каким его подписал apksigner. Описание релиза берётся из `docs/releases/vX.Y.Z.md`, если
такой файл есть.

Параллельно задача `windows` собирает на `windows-latest` установщик для Windows и проверяет его
(`windows-service-check.ps1`), а `windows-publish` прикладывает `Paramnezia-Setup-X.Y.Z.exe` к релизу и
подписывает `release-windows.json` (`--package net.paramnezia.windows --min-sdk 0`) тем же ключом релизов.
Номер сборки у обеих платформ один (`github.run_number`). Подробнее: [WINDOWS.md](WINDOWS.md).

Кроме файлов с номером версии к релизу прикладываются те же байты под постоянными именами `paramnezia.apk` и
`Paramnezia-Setup.exe`: страница для семьи на серверах ссылается на `releases/latest/download/<имя>` и поэтому
не устаревает.

Перезапуск упавшего выпуска безопасен: релиз и файлы перезаписываются (`--clobber`).

## `releasetool`

```
releasetool keygen                                   # новый PN_RELEASE_SEED и публичный ключ
releasetool pubkey                                   # публичный ключ из PN_RELEASE_SEED (проверка при выпуске)
releasetool sign --apk F --version-code N --version-name S --url URL… [--signer-sha256 HEX] [--package P] [--min-sdk N] [--notes S] --out release.json
```

## Сборки без релиза

Каждый пуш в PR и в `main` собирает APK и кладёт свежие файлы в ветку `apk-builds`
(`paramnezia-ci-release-debugkey.apk`, `paramnezia-ci-debug.apk`); там же они видны в артефактах запуска
Actions. Они подписаны debug-ключом раннера: только для проверки, поверх релиза не встанут.
