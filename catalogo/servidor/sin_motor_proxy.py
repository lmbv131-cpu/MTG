"""Puerta del catálogo sin motor (Luis 2026-10-20 20:47): escucha en el puerto de siempre (8090) y pasa todo al
CatalogoServer real (127.0.0.1:8088), salvo lo que arranca un motor (/simular, /simular-listo, /auto…), mientras exista
servidor\\sin-motor.txt. Sin ese archivo deja pasar todo. En las páginas apaga los botones de simular/grabar.

Con una partida en curso (Luis 2026-10-24 18:35, la Raspberry): el juego dice GET 127.0.0.1:8080/partida-en-curso -> "1"
durante una partida (también Mesa de pruebas) o Estadísticas. Mientras tanto Simular se rechaza igual que con la marca,
y si una partida empieza con motores de Simular abiertos, se cierran, para que ni el juego ni la simulación vayan lentos.
Activo en Linux (la Pi) o con --sin-partida; en el PC de Windows Simular sigue permitido mientras se juega.

  python sin_motor_proxy.py [puerto=8090] [destino=8088] [--sin-partida]
"""
import http.client
import os
import subprocess
import time
import json
import re
import threading
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

_NUM = [a for a in sys.argv[1:] if a.isdigit()]
PUERTO = int(_NUM[0]) if len(_NUM) > 0 else 8090
DESTINO = int(_NUM[1]) if len(_NUM) > 1 else 8088
CON_PARTIDA = sys.platform.startswith("linux") or "--sin-partida" in sys.argv
AVISO_PARTIDA = "Hay una partida en curso: Simular vuelve cuando acabe"
_partida = {"t": 0.0, "v": False}
# Where to ask: on the Pi the game runs with --localhost-remoto (Tailscale Funnel), so 127.0.0.1 counts as outside and gets
# the login page; ask by the home address from /opt/mtglocal/config.env instead (PARTIDA_HOST/PARTIDA_PORT override it).
PARTIDA_HOST, PARTIDA_PORT = os.environ.get("PARTIDA_HOST", ""), int(os.environ.get("PARTIDA_PORT", "8080"))
if not PARTIDA_HOST:
    try:
        PARTIDA_HOST = next(l.split("=", 1)[1].strip().strip('"') for l in Path("/opt/mtglocal/config.env").read_text().splitlines() if l.startswith("IP="))
    except (OSError, StopIteration):
        PARTIDA_HOST = "127.0.0.1"


def partida():
    """True mientras el juego (8080) tiene una partida o Estadísticas en marcha; se pregunta como mucho cada 2 s."""
    if not CON_PARTIDA:
        return False
    ahora = time.monotonic()
    if ahora - _partida["t"] > 2:
        v = False
        try:
            c = http.client.HTTPConnection(PARTIDA_HOST, PARTIDA_PORT, timeout=1)
            c.request("GET", "/partida-en-curso")
            v = c.getresponse().read().decode("utf-8", "replace").strip() == "1"
            c.close()
        except OSError:
            v = False
        _partida.update(t=ahora, v=v)
    return _partida["v"]


def motivo():
    """Por qué Simular está apagado ahora, o None."""
    if BANDERA.exists():
        return AVISO
    if partida():
        return AVISO_PARTIDA
    return None


def cerrar_simular():
    """Cierra los motores de Simular (java … mtglocal.jar … --sim) y los navegadores ocultos del catálogo. Nunca el juego."""
    perfil = str(Path(__file__).resolve().parent / "navegador")
    try:
        if os.name == "nt":
            cmd = ("Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -and (($_.CommandLine -match 'mtglocal\\.jar' -and "
                   "$_.CommandLine -match '--sim') -or $_.CommandLine -match [regex]::Escape('" + perfil + "')) } | "
                   "ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }")
            subprocess.run(["powershell", "-NoProfile", "-NonInteractive", "-Command", cmd], timeout=30, capture_output=True)
        else:
            # only java (the engines) and chromium (the hidden browsers): a shell that merely mentions them is left alone
            for pid in [d for d in os.listdir("/proc") if d.isdigit()]:
                try:
                    with open("/proc/" + pid + "/cmdline", "rb") as f:
                        args = f.read().decode("utf-8", "replace").split("\0")
                except OSError:
                    continue
                exe, todo = os.path.basename(args[0]), " ".join(args)
                motor = exe.startswith("java") and "mtglocal" in todo and "--sim" in args
                nav = ("chrom" in exe or "edge" in exe or "brave" in exe) and perfil in todo
                if (motor or nav) and int(pid) != os.getpid():
                    try:
                        os.kill(int(pid), 15)
                    except OSError:
                        pass
    except (OSError, subprocess.SubprocessError):
        pass


def vigilar():
    """Si empieza una partida con Simular en marcha, cierra Simular (una vez por partida)."""
    cerrado = False
    while True:
        time.sleep(3)
        if partida():
            if not cerrado:
                cerrar_simular()
                cerrado = True
        else:
            cerrado = False
BANDERA = Path(__file__).resolve().parent / "sin-motor.txt"
BLOQUEADAS = ("/simular", "/auto")
# «Pedir cambio» del catálogo (Luis 2026-10-24 10:57): «Enviar» añade el bloque al archivo que se le pasa a la skill de
# animaciones. GET /pedido -> {"siguiente": n}; POST /pedido {"texto": "## #. …"} -> {"numero": n, "total": t}.
# El número sale del propio archivo: solo sube al enviar.
PEDIDOS = Path(__file__).resolve().parent.parent.parent / "ABRIR" / "6 Pedidos de animaciones.txt"
CABECERA = ("Pedidos de animaciones para la skill «mejorar-animaciones».\r\n"
            "Los añade el botón «Pedir cambio» del Catálogo de acciones. Pásale este archivo a la skill; cuando los haga,\r\n"
            "puedes vaciarlo. Campos: momento (dónde está en el juego), ahora (qué se ve hoy), quiero (qué debe verse, en orden),\r\n"
            "dónde (carta, vida del jugador, centro de la mesa, mi tableta, tableta del rival), duración, no tocar (lo que debe\r\n"
            "quedar igual), captura (si pegas una imagen en el hilo) y prioridad (alta, media, baja).\r\n")
CANDADO = threading.Lock()


# GET /version (Luis 2026-10-24 19:18): the installed game's version and since when (VERSION.txt of the game folder), so
# the catalog marks the videos filmed with an older version
CAT = Path(__file__).resolve().parent.parent
JUEGOS = [CAT.parent / "Juego", CAT.parent / "juego", CAT.parent / "MTGLocal", Path("/opt/mtglocal/juego"), Path("/opt/mtglocal")]


def version():
    for d in ([Path(os.environ["MTG_JUEGO"])] if os.environ.get("MTG_JUEGO") else []) + JUEGOS:
        f = d / "VERSION.txt"
        try:
            linea = f.read_text(encoding="utf-8-sig").strip().splitlines()[0].strip()
            return {"version": linea.split(" ")[0][:20], "desde": int(f.stat().st_mtime * 1000)}  # "p162 (fecha): …" -> p162
        except (OSError, IndexError):
            continue
    return {}


def numeros():
    try:
        return [int(x) for x in re.findall(r"^## (\d+)\.", PEDIDOS.read_text(encoding="utf-8-sig"), re.M)]
    except OSError:
        return []
AVISO = "Simular no disponible ahora (verificación en marcha)"


def script(aviso):
    return SCRIPT.replace(AVISO, aviso)


SCRIPT = ("<script>(function(){var re=/simular|grabar|captur|probar|preparar/i;function apaga(){document.querySelectorAll('button,a.btn,"
          "input[type=button]').forEach(function(b){var t=(b.innerText||b.value||'');if(re.test(t)&&!b.dataset.sm){b.dataset.sm=1;b.disabled=true;"
          "b.style.opacity='.4';b.style.pointerEvents='none';b.title='" + AVISO + "';}});}apaga();new MutationObserver(apaga)"
          ".observe(document.documentElement,{childList:true,subtree:true});})();</script>")


class Puerta(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    def _bloquear(self, aviso=AVISO):
        if self.path.startswith("/auto"):
            # the catalog page reads /auto* as JSON and shows «estado»
            b = json.dumps({"fin": True, "ok": False, "estado": aviso, "motores": 0}, ensure_ascii=False).encode("utf-8")
            tipo = "application/json; charset=utf-8"
        else:
            b = ("<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'><title>Simular</title>"
                 "<body style='margin:0;background:#10141B;color:#E9E3D5;font:18px system-ui;display:grid;place-items:center;height:100vh'>"
                 "<p style='padding:16px;text-align:center'>" + aviso + "</p>").encode("utf-8")
            tipo = "text/html; charset=utf-8"
        self.send_response(503)
        self.send_header("Content-Type", tipo)
        self.send_header("Content-Length", str(len(b)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(b)

    def _json(self, codigo, obj):
        b = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(codigo)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def _pedido(self):
        with CANDADO:
            ns = numeros()
            if self.command != "POST":
                self._json(200, {"siguiente": (max(ns) if ns else 0) + 1})
                return
            try:
                n = int(self.headers.get("Content-Length") or 0)
                texto = str(json.loads(self.rfile.read(n).decode("utf-8")).get("texto", ""))
            except (ValueError, OSError):
                self._json(400, {"error": "pedido ilegible"})
                return
            if not texto.startswith("## ") or len(texto) > 20000:
                self._json(400, {"error": "pedido ilegible"})
                return
            num = (max(ns) if ns else 0) + 1
            bloque = re.sub(r"^## [^.]*\.", "## %d." % num, texto, count=1).replace("\r\n", "\n").strip("\n")
            bloque = bloque.replace("\n", "\r\n") + "\r\n"
            PEDIDOS.parent.mkdir(parents=True, exist_ok=True)
            nuevo = not PEDIDOS.exists() or PEDIDOS.stat().st_size == 0
            with open(PEDIDOS, "a", encoding="utf-8", newline="") as f:
                f.write((CABECERA if nuevo else "") + "\r\n" + bloque)
            self._json(200, {"numero": num, "total": len(ns) + 1})

    def _pasar(self):
        if self.path.split("?")[0] == "/version":
            self._json(200, version())
            return
        if self.path.split("?")[0] == "/pedido":
            self._pedido()
            return
        ruta = self.path.split("?")[0]
        apagado = motivo() if ruta.startswith(BLOQUEADAS) or ruta in ("/", "/index.html") else None
        if apagado and ruta.startswith(BLOQUEADAS):
            self._bloquear(apagado)
            return
        n = int(self.headers.get("Content-Length") or 0)
        cuerpo = self.rfile.read(n) if n else None
        c = http.client.HTTPConnection("127.0.0.1", DESTINO, timeout=600)
        h = {k: v for k, v in self.headers.items() if k.lower() not in ("host", "connection", "accept-encoding")}
        c.request(self.command, self.path, body=cuerpo, headers=h)
        r = c.getresponse()
        tipo = r.getheader("Content-Type", "")
        largo = r.getheader("Content-Length")
        if largo is not None and not (apagado and "text/html" in tipo):
            # Videos and pictures go through as they arrive (Luis 2026-10-24: videos slow to load): no waiting for
            # the whole file before the first byte, and a video the page stops watching is not read to the end
            self.send_response(r.status)
            for k, v in r.getheaders():
                if k.lower() not in ("transfer-encoding", "connection"):
                    self.send_header(k, v)
            self.end_headers()
            try:
                if self.command != "HEAD":
                    while True:
                        b = r.read(256 * 1024)
                        if not b:
                            break
                        self.wfile.write(b)
            except (BrokenPipeError, ConnectionResetError):
                self.close_connection = True
            c.close()
            return
        datos = r.read()
        if apagado and "text/html" in tipo:
            t = datos.decode("utf-8", "replace")
            # Append at the very end of the page. Never insert before a «</body>»/«</head>»/«</script>»: the
            # catalog's only «</body>» is inside a JS string of its main inline script, and inserting there
            # ends that script early (the page shows its code as text and the tables stay empty).
            t = t + script(apagado)
            datos = t.encode("utf-8")
        self.send_response(r.status)
        for k, v in r.getheaders():
            if k.lower() not in ("content-length", "transfer-encoding", "connection"):
                self.send_header(k, v)
        self.send_header("Content-Length", str(len(datos)))
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(datos)
        c.close()

    do_GET = do_POST = do_HEAD = do_PUT = do_OPTIONS = _pasar


if CON_PARTIDA:
    threading.Thread(target=vigilar, daemon=True).start()
ThreadingHTTPServer(("0.0.0.0", PUERTO), Puerta).serve_forever()
