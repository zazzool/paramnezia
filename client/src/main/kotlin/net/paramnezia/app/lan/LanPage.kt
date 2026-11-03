package net.paramnezia.app.lan

/**
 * The page a browser gets at the share's address. It has to work in a TV's built-in browser, the
 * Downloader app and old Android WebViews: plain HTML, a little ES5, no external files, big
 * buttons that a remote's arrows can reach. Downloads start only on a tap (docs/PLAY-PROTECT.md).
 */
object LanPage {
    enum class Device { TV, ANDROID, WINDOWS, IPHONE, OTHER }

    fun detect(ua: String): Device {
        val u = ua.lowercase()
        return when {
            listOf("android tv", "googletv", "google tv", "smart-tv", "smarttv", "bravia", "aft", "mibox", "mitv", "tv build", "; tv", "crkey", "leanback").any { it in u } -> Device.TV
            "iphone" in u || "ipad" in u -> Device.IPHONE
            "android" in u -> Device.ANDROID
            "windows" in u -> Device.WINDOWS
            else -> Device.OTHER
        }
    }

    /** Name the owner sees when a browser asks for settings. */
    fun deviceName(d: Device) = when (d) {
        Device.TV -> "Телевизор"
        Device.ANDROID -> "Android-устройство"
        Device.WINDOWS -> "Компьютер"
        Device.IPHONE -> "iPhone"
        Device.OTHER -> "Устройство"
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun mb(size: Long) = "%.1f МБ".format(size / 1_048_576.0).replace('.', ',')

    fun render(info: LanInfo, apk: LanFile?, exe: LanFile?, ua: String): String {
        val device = detect(ua)
        val androidBlock = if (apk != null) """
            <section>
              <h2>Android: телефон, планшет, телевизор</h2>
              <a class="btn main" href="/a" ${if (device == Device.TV || device == Device.ANDROID) "autofocus" else ""}>Скачать Paramnezia ${esc(apk.version)} · ${mb(apk.size)}</a>
              <ol>
                <li>Откройте скачанный файл и нажмите «Установить».</li>
                <li>Если Android спросит, разрешите установку из этого приложения (браузера, Downloader или файлового менеджера): «Настройки» → включить → «Назад».</li>
                <li>Откройте Paramnezia: она сама найдёт ${esc(info.name)} в этой сети${if (info.settings) " и попросит настройки" else ""}.</li>
              </ol>
            </section>""" else """
            <section><h2>Android</h2><p class="muted">Приложение для Android здесь пока не раздаётся: на ${esc(info.name)} нажмите «Скачать» у Android на экране раздачи.</p></section>"""
        val windowsBlock = if (exe != null) """
            <section>
              <h2>Windows 10 и 11</h2>
              <a class="btn ${if (device == Device.WINDOWS) "main" else ""}" href="/w" ${if (device == Device.WINDOWS) "autofocus" else ""}>Скачать установщик ${esc(exe.version)} · ${mb(exe.size)}</a>
              <ol>
                <li>Запустите скачанный файл. Если появится синее окно SmartScreen: «Подробнее» → «Выполнить в любом случае».</li>
                <li>После установки Paramnezia сама найдёт ${esc(info.name)} в этой сети${if (info.settings) " и попросит настройки" else ""}.</li>
              </ol>
            </section>""" else """
            <section><h2>Windows</h2><p class="muted">Установщик для Windows здесь пока не раздаётся: на ${esc(info.name)} нажмите «Скачать» у Windows на экране раздачи.</p></section>"""
        val iphoneBlock = """
            <section>
              <h2>iPhone и iPad</h2>
              <p>Установите <b>AmneziaVPN</b> из App Store${if (info.settings) ", затем нажмите «Получить настройки» ниже и вставьте ключ в AmneziaVPN" else ""}.</p>
            </section>"""
        val ordered = when (device) {
            Device.WINDOWS -> listOf(windowsBlock, androidBlock, iphoneBlock)
            Device.IPHONE -> listOf(iphoneBlock, androidBlock, windowsBlock)
            else -> listOf(androidBlock, windowsBlock, iphoneBlock)
        }
        val settingsBlock = if (info.settings) """
            <section id="get">
              <h2>Уже установлено?</h2>
              <p class="muted">Настройки выдаются после разрешения на ${esc(info.name)}.</p>
              <button class="btn" id="ask" onclick="ask()">Получить настройки</button>
              <div id="st" class="muted"></div>
              <div id="done" style="display:none">
                <a class="btn main" id="open" href="#">Открыть в Paramnezia</a>
                <a class="btn" id="file" href="#">Скачать файл настроек</a>
                <p class="muted">Или скопируйте ключ и вставьте в Paramnezia / AmneziaVPN:</p>
                <textarea id="key" readonly rows="4"></textarea>
                <button class="btn" onclick="copyKey()">Скопировать ключ</button>
              </div>
            </section>""" else ""
        return """<!doctype html>
<html lang="ru"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Paramnezia · ${esc(info.name)}</title>
<style>
:root{--bg:#f4f2fb;--card:#fff;--fg:#1d1a2b;--mut:#5d5873;--a:#6d4aff;--b:#2f8cff}
@media (prefers-color-scheme:dark){:root{--bg:#141220;--card:#1f1c2e;--fg:#ece9f7;--mut:#a9a3c2}}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--fg);font:18px/1.45 system-ui,-apple-system,Segoe UI,Roboto,sans-serif}
main{max-width:760px;margin:0 auto;padding:24px 16px 48px}
h1{font-size:30px;margin:8px 0 4px}h2{font-size:21px;margin:0 0 12px}
.lead{color:var(--mut);margin:0 0 20px}
section{background:var(--card);border-radius:24px;padding:20px;margin:0 0 16px}
.btn{display:block;width:100%;text-align:center;font:600 20px system-ui,sans-serif;padding:18px 16px;margin:8px 0;border-radius:18px;
 border:2px solid var(--a);color:var(--a);background:transparent;text-decoration:none;cursor:pointer}
.btn.main{background:linear-gradient(135deg,var(--a),var(--b));color:#fff;border-color:transparent}
.btn:focus,.btn:hover{outline:none;box-shadow:0 0 0 4px rgba(109,74,255,.45);transform:scale(1.02)}
ol{margin:12px 0 0;padding-left:22px}li{margin:6px 0}.muted{color:var(--mut)}
textarea{width:100%;font:14px monospace;border-radius:12px;padding:10px;background:var(--bg);color:var(--fg);border:1px solid var(--mut)}
</style></head>
<body><main>
<h1>Paramnezia</h1>
<p class="lead">VPN для семьи · раздаёт ${esc(info.name)} · версия ${esc(info.version)}</p>
${ordered.joinToString("\n")}
$settingsBlock
</main>
<script>
var dev=${jsString(deviceName(device))},kind=${jsString(device.name.lowercase())},id=null;
function q(i){return document.getElementById(i)}
function req(m,u,b,cb){var x=new XMLHttpRequest();x.open(m,u,true);x.timeout=30000;
 x.onload=function(){try{cb(JSON.parse(x.responseText))}catch(e){cb(null)}};x.onerror=x.ontimeout=function(){cb(null)};
 if(b){x.setRequestHeader('Content-Type','application/json');x.send(JSON.stringify(b))}else x.send()}
function ask(){q('ask').disabled=true;q('st').innerHTML='Запрос отправлен. Разрешите его на ${esc(info.name)}…';
 req('POST','/join',{device:dev,kind:kind},function(r){if(!r||!r.id){fail(r);return}id=r.id;poll()})}
function poll(){req('GET','/join/'+id,null,function(r){if(!r){setTimeout(poll,2000);return}
 if(r.state=='waiting'){poll();return}if(r.state=='ok'){ok(r.key);return}fail(r)})}
function fail(r){q('ask').disabled=false;q('st').innerHTML=(r&&r.state=='denied')?'Запрос отклонён.':((r&&r.message)||'Не получилось, попробуйте ещё раз.')}
function ok(k){q('st').innerHTML='Готово.';q('ask').style.display='none';q('done').style.display='block';
 q('open').href=k;q('file').href='/k/'+id;q('key').value=k;q('open').focus()}
function copyKey(){var t=q('key');t.focus();t.select();try{document.execCommand('copy');q('st').innerHTML='Ключ скопирован.'}catch(e){}}
</script>
</body></html>"""
    }

    fun notFound() = """<!doctype html><html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Paramnezia</title></head><body style="font:18px system-ui,sans-serif;padding:24px"><p>Здесь ничего нет. <a href="/">На главную раздачи</a></p></body></html>"""

    private fun jsString(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
