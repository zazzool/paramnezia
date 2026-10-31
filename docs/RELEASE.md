# Выпуск версий

## Два ключа

| Ключ | Что подписывает | Где хранить | Если потерять |
|---|---|---|---|
| **Ключ подписи APK** (keystore) | сам APK | секреты репозитория + офлайн-копия | обновить установленные приложения будет нельзя, только переустановка |
| **Ключ релизов** (Ed25519, `PN_RELEASE_SEED`) | манифест релиза для OTA | секрет репозитория + офлайн-копия | новый ключ = новая сборка, которую придётся поставить вручную |

Оба ключа созданы 31.10.2026 и лежат в репозитории зашифрованными: `keys/release-keys.enc` (AES-256, PBKDF2
600 000 итераций). Внутри `paramnezia.jks` (ключ подписи, алиас `paramnezia`) и `release.env`
(`PN_KEYSTORE_PASSWORD`, `PN_KEY_ALIAS`, `PN_RELEASE_SEED`). Пароль от архива — секрет репозитория
`PN_KEYS_PASSWORD`; держите его копию офлайн (менеджер паролей). Публичные половины ключа релизов —
в `gradle.properties` (`paramnezia.releaseKey`, `paramnezia.releaseNostr`), их вшивает каждая сборка.

Открыть ключи локально:

```sh
openssl enc -d -aes-256-cbc -pbkdf2 -iter 600000 -in keys/release-keys.enc | tar x -C /tmp/pn-keys
```

Сборки CI из PR и `main` подписаны debug-ключом раннера; поверх них релиз ставится только после удаления.

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

## Выпуск

```sh
git tag v1.2.0 && git push origin v1.2.0
```

`release.yml`:

1. Собирает APK, подписанный вашим ключом, с вшитыми `PN_RELEASE_KEY`/`PN_RELEASE_NOSTR`; `versionCode` —
   номер запуска workflow, `versionName` — тег.
2. Создаёт GitHub Release с APK.
3. `releasetool publish`: подписывает манифест релиза, загружает APK на Blossom-серверы, публикует событие в
   Nostr и прикладывает `release.json` к релизу.

Дальше всё само: устройства находят релиз в Nostr или через gossip, админ-устройство после обновления кладёт
APK и релиз на все серверы флота (доступно внутри туннеля даже при блокировке всего остального).

## `releasetool`

```
releasetool keygen
releasetool publish --apk F --version-code N --version-name S [--blossom URL]… [--relay URL]… [--url URL]…
                    [--signer-sha256 HEX] [--notes S] [--out release.json]      # нужен PN_RELEASE_SEED
releasetool embed --apk in.apk --out out.apk --payload "vpn://…"             # APK со вшитым приглашением
```

## Сборки без релиза

Каждый пуш в PR и в `main` собирает APK и кладёт свежие файлы в ветку `apk-builds`
(`paramnezia-release.apk`, `paramnezia-debug.apk`); там же они видны в артефактах запуска Actions.
