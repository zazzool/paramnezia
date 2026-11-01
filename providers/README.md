# Хостеры с API для «Купить сервер у хостера»

Список, по которому приложение покупает VPS одной кнопкой. Каждая строка проверена по **коду официального
SDK или CLI самого хостера** (не по статьям и не по сторонним обёрткам); ссылка на файл, по которому сверялись
адреса и поля, есть в таблице и в комментарии к классу провайдера в `deploy/src/main/kotlin/net/paramnezia/deploy/cloud/`.
Машиночитаемая копия: [`providers.json`](providers.json).

## Подключены (2.2.0)

| Хостер | API | Авторизация | Создание сервера | Источник, по которому сверено | Почему он |
|---|---|---|---|---|---|
| **Cloudzy** | `https://dash.cloudzy.com/api/v1` | `Authorization: Bearer <developer token>` (токен с ограниченными правами) | `POST /services` `{planId, region, hostname, billingCycle, osTemplateId, sshAuthorizedKeys[], config.userData}` + заголовок `Idempotency-Key`; цены `GET /pricing/catalog`; состояние `GET /services/{id}` (`state=active`, `ipAddress`) | [CloudzyVPS/cli `src/api/ops.rs`](https://github.com/CloudzyVPS/cli/blob/main/src/api/ops.rs), [`src/commands/catalog.rs`](https://github.com/CloudzyVPS/cli/blob/main/src/commands/catalog.rs) | основной сервер флота уже у Cloudzy; принимает криптовалюту; много зарубежных локаций |
| **Timeweb Cloud** | `https://api.timeweb.cloud/api/v1` | `Authorization: Bearer <token>` | `POST /servers` `{name, preset_id, bandwidth, os_id, ssh_keys_ids[], cloud_init}`; тарифы `GET /presets/servers` (цена ₽/мес); ключи `GET/POST /ssh-keys`; состояние `GET /servers/{id}` (`status=on`, IPv4 в `networks[].ips[]`) | [timeweb-cloud/twc `twc/api/client.py`](https://github.com/timeweb-cloud/twc/blob/master/twc/api/client.py), [`twc/commands/server.py`](https://github.com/timeweb-cloud/twc/blob/master/twc/commands/server.py) | оплата российскими картами; локации в Нидерландах, Германии, Польше, Казахстане |

## Рассмотрены, но не подключены

| Хостер | Причина (на 1.11.2026) |
|---|---|
| Vultr (`api.vultr.com/v2`) | API хороший, но работа с клиентами из России не подтверждена. Добавить легко, если понадобится |
| Hetzner Cloud | с декабря 2023 не заключает договоры с клиентами с российским адресом |
| DigitalOcean | заблокирован Роскомнадзором с апреля 2024 |
| Aeza | под санкциями OFAC (июль 2025): риск блокировки платежей и аккаунта |
| Selectel, VK Cloud, Yandex Cloud | API на OpenStack/IAM (сложнее), а смысл VPN-сервера — зарубежный IP; российские локации не помогают обойти блокировки |

## Как устроена покупка (лучшие практики)

1. **Токен** вводится один раз в режиме администратора, проверяется запросом к API и хранится в зашифрованном
   состоянии приложения (AES-GCM, ключ в Android Keystore). Никуда, кроме API самого хостера, не отправляется,
   в резервные копии и ключи `vpn://` не попадает. Лучше создавать токен с минимальными правами.
2. **Без паролей.** Приложение создаёт свою пару SSH-ключей (RSA-3072) и передаёт хостеру только публичную часть
   (через API хостера и через cloud-init). Пароль root не нужен и не запрашивается.
3. **Ключ хоста закреплён заранее.** Ключ SSH-сервера генерирует приложение и кладёт его в cloud-init
   (`ssh_keys`, `ssh_deletekeys`, `ssh_genkeytypes: [rsa]`: на сервере остаётся только наш ключ); отпечаток записывается до первого подключения. Подменить сервер
   при первом входе нельзя (обычный TOFU не нужен).
4. **Без двойной покупки.** Cloudzy получает `Idempotency-Key`; если ответ на создание потерялся, приложение
   сначала ищет сервер по уникальному имени `paramnezia-xxxxxx` и только потом повторяет запрос.
5. **Ожидание**: опрос состояния каждые 5 с до 10 минут, затем SSH с повторами до 8 минут и
   `cloud-init status --wait` (cloud-init держит блокировку apt, нужную установщику).
6. **Цена до покупки**: показывается тариф и цена в месяц; по умолчанию самый дешёвый тариф от 1 ГБ RAM
   (сборка AmneziaWG в Docker не помещается в 512 МБ) в последней использованной зарубежной локации.
7. **Если что-то сорвалось** после создания, приложение предлагает удалить сервер у хостера, чтобы он не
   оплачивался. Сервер, купленный из приложения и удалённый из флота, тоже можно удалить у хостера.
8. **Только по нажатию.** Запросы к хостеру идут только при открытом приложении (правило 11 в
   `docs/PLAY-PROTECT.md`); новых разрешений Android не нужно.

## Как добавить хостера

1. Найдите официальный SDK/CLI/OpenAPI хостера и сверяйте адреса и поля по нему.
2. Новый класс `XxxProvider : CloudProvider` рядом с `CloudzyProvider` (методы `regions`, `plans`, `create`,
   `get`, `find`, `delete`, `verify`); в KDoc ссылка на источник.
3. Строка в `CloudProviders.all` и `open()`, строка в таблице выше и в `providers.json`.
4. Тест на поддельных ответах в `deploy/src/test/kotlin/net/paramnezia/deploy/cloud/CloudProvidersTest.kt`
   (`./gradlew -Pparamnezia.jvmOnly=true :deploy:test`).
