# Выпуск версий

## Два ключа

| Ключ | Что подписывает | Где хранить | Если потерять |
|---|---|---|---|
| **Ключ подписи APK** (keystore) | сам APK | секреты репозитория + офлайн-копия | обновить установленные приложения будет нельзя, только переустановка |
| **Ключ релизов** (Ed25519, `PN_RELEASE_SEED`) | манифест релиза для OTA | секрет репозитория + офлайн-копия | новый ключ = новая сборка, которую придётся поставить вручную |

Пока ключей нет, CI подписывает release-сборку debug-ключом GitHub-раннера, а OTA выключены. Первая сборка с
настоящим ключом ставится поверх только после удаления старой.

## Один раз: завести ключи

```sh
# Ключ подписи APK
keytool -genkeypair -v -keystore paramnezia.jks -alias paramnezia -keyalg EC -groupname secp256r1 -validity 36500
base64 -w0 paramnezia.jks > paramnezia.jks.b64

# Ключ релизов
./gradlew -Pparamnezia.jvmOnly=true :releasetool:installDist
releasetool/build/install/releasetool/bin/releasetool keygen
```

В GitHub → Settings → Secrets and variables → Actions:

| Секреты | Переменные |
|---|---|
| `PN_KEYSTORE_B64` (содержимое `.b64`), `PN_KEYSTORE_PASSWORD`, `PN_KEY_ALIAS` (`paramnezia`), `PN_RELEASE_SEED` | `PN_RELEASE_KEY`, `PN_RELEASE_NOSTR` (публичные ключи из `keygen`) |

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
