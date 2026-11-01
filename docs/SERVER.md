# Сервер (VPS)

Всё, что приложение ставит и меняет на сервере. Скрипты лежат в `deploy/src/main/resources/net/paramnezia/deploy/`
и выполняются по SSH (`bash -s`, значения передаются через переменные окружения, а не подстановкой в текст).

## Требования

Любой Linux с `apt`, `dnf`, `yum`, `zypper` или `pacman`, root или `sudo` без пароля, открытый UDP-порт для
AmneziaWG и (для ссылок) TCP-порт 80 или любой свободный. Docker ставится сам.

## Раскладка (совместима с AmneziaVPN)

| Что | Где |
|---|---|
| Контейнер VPN | `amnezia-awg2` (образ на базе `amneziavpn/amneziawg-go`, + `jq`, `busybox-extras`) |
| Конфиг сервера | `/opt/amnezia/awg/awg0.conf` внутри контейнера |
| Ключи | `/opt/amnezia/awg/wireguard_server_{private,public}_key.key`, `wireguard_psk.key` |
| Имена клиентов | `/opt/amnezia/awg/clientsTable` (JSON, как у AmneziaVPN) |
| Сеть | `amnezia-dns-net` 172.29.172.0/24 (для AmneziaDNS, если поставить его из AmneziaVPN) |
| Подсеть клиентов | 10.8.1.0/24, сервер — 10.8.1.1 |
| Манифест и релизы (только внутри туннеля) | busybox httpd на `10.8.1.1:8080`, корень `/opt/amnezia/paramnezia/www` в контейнере: `fleet.json`, `release.json`, `apk/<sha256>`, `ok` |
| Страница приглашений | контейнер `paramnezia-invite` (busybox httpd), хост `/opt/amnezia/paramnezia/invite` |
| Проверенные релизы | хост `/opt/amnezia/paramnezia/cache/<sha256>.apk` (только текущий) |

Поскольку раскладка та же, сервером можно управлять и из AmneziaVPN (ключ с полным доступом экспортируется
из Paramnezia).

## Скрипты

| Скрипт | Что делает | Входы |
|---|---|---|
| `inspect.sh` | есть ли Docker, какие контейнеры `amnezia-*` | — |
| `install-awg.sh` | Docker, образ, контейнер, ключи, `awg0.conf`, iptables/NAT, BBR, внутритуннельный httpd | `AWG_PORT`, `AWG_SUBNET`, `AWG_CIDR`, `AWG_PARAMS`, `PN_REINSTALL` |
| `add-peer.sh` | добавляет клиента (идемпотентно по ключу), выдаёт свободный адрес или заданный `CLIENT_IP` (`address_taken`, если занят чужим ключом) | `CONTAINER`, `CLIENT_PUB`, `CLIENT_NAME`, `CLIENT_IP` |
| `remove-peer.sh` | удаляет клиента | `CONTAINER`, `CLIENT_PUB` |
| `invite-server.sh` | поднимает/обновляет страницу приглашений (и запускает остановленную на том же порту), пишет `app.json`, чистит старые ссылки и остатки версии 1.0 | `PN_INVITE_PORT`, `PN_INVITE_INDEX`, `PN_INVITE_CGI`, `PN_APP_JSON` |
| `fleet-www.sh` | кладёт манифест флота и следит, чтобы внутритуннельный httpd отвечал (ставит `busybox-extras` и запускает httpd, если его нет, например в контейнере AmneziaVPN или после перезагрузки) | `CONTAINER`, `MANIFEST` |
| `release-mirror.sh` | сервер сам скачивает релиз по HTTPS, сверяет SHA-256, кладёт во внутритуннельный httpd и/или на страницу приглашений | `PN_SHA256`, `PN_URLS`, `PN_RELEASE`, `CONTAINER`, `PN_INVITE` |

Прогресс скрипты пишут строками `::step::<имя>`, результаты `::result::<ключ>=<значение>`, ошибки
`::error::<код>`.

## Страница приглашений

```
/opt/amnezia/paramnezia/invite/
  www/index.html          страница (статическая)
  www/cgi-bin/get         CGI (sh)
  www/app.json            где скачать приложение: версия, SHA-256, HTTPS-адреса
  www/paramnezia.apk      копия того же релиза (сервер скачал и проверил её сам)
  data/t/<токен>/         ждущие ссылки: app, key, conf, name, expires
  data/c/<токен>/         использованные: то же + claim ("<unix-время> <адрес>")
```

Контейнер: `--read-only --cap-drop ALL --security-opt no-new-privileges --user 65534:65534 --memory 32m
--pids-limit 64`, порт `80` (если занят — случайный 20000–60000). Использованные и истёкшие папки старше
недели удаляются при следующем вызове `invite-server.sh`.

## Полезные команды на сервере

```sh
docker exec amnezia-awg2 awg show                       # клиенты, рукопожатия, трафик
docker exec amnezia-awg2 cat /opt/amnezia/awg/awg0.conf
docker exec amnezia-awg2 cat /opt/amnezia/awg/clientsTable
docker logs paramnezia-invite                           # логов нет (--log-driver none), см. data/c
ls /opt/amnezia/paramnezia/invite/data/{t,c}            # ждущие и использованные ссылки
docker restart amnezia-awg2                             # перезапуск VPN (start.sh поднимает всё заново)
```

## Самовосстановление

Админ-устройство раз в сутки (и после своего обновления) проходит по серверам флота: регистрирует всех
устройств с их адресами (`add-peer.sh` идемпотентен), удаляет отключённых, кладёт манифест и поднимает
внутритуннельный httpd (`fleet-www.sh`), просит сервер скачать текущий релиз (`release-mirror.sh`). Поэтому после
перезагрузки VPS, временной недоступности сервера или ручной правки всё сходится само. Отдельно можно ничего
не делать.

## Если IP сервера заблокировали

Добавьте новый VPS во флот («Флот» → «Добавить сервер»), при необходимости сделайте его основным. Все
устройства узнают об этом через Nostr или gossip и переключатся сами; адреса устройств на новом сервере те же.
