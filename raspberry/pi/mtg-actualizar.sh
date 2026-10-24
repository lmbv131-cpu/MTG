#!/bin/bash
# MTG Local updates on the Pi (root). Run every minute by mtg-actualizar.timer:
#   - asks GitHub every 10 minutes, or right away when the webhook left estado/buscar-ya;
#   - a new version is downloaded at once, but only applied when /partida-en-curso says "0"
#     (no game and no Estadísticas run); while a game goes on it is checked again every minute,
#     so it goes in as soon as the game ends, and the server restarts with it.
# mtg-actualizar construir   rebuild juego/ from repo/ (no restart)
# mtg-actualizar ya          look now and apply if no game is running
set -euo pipefail
. /opt/mtglocal/config.env
M=/opt/mtglocal
R=$M/repo
E=$M/estado
LOG=/var/log/mtglocal-actualizaciones.log
g() { runuser -u mtg -- env HOME="$M/home" git -C "$R" "$@"; }
log() { echo "$(date '+%F %T') $*" >>"$LOG"; }

construir() {
  runuser -u mtg -- python3 -I "$M/unir-jar.py" "$R/app.jar" "$R/motor.jar" "$M/juego/mtglocal.jar.part"
  mv -f "$M/juego/mtglocal.jar.part" "$M/juego/mtglocal.jar"
  runuser -u mtg -- rsync -a --delete "$R/engine/" "$M/juego/engine/"
  # the package number (pNNN) for the catalog's /version, when the public repo carries it
  [ -f "$R/VERSION.txt" ] && install -o mtg -g mtg -m 644 "$R/VERSION.txt" "$M/juego/VERSION.txt"
  g rev-parse --short HEAD > "$E/version"
}

case "${1:-comprobar}" in
  construir) construir; exit 0 ;;
  ya) touch "$E/buscar-ya" ;;
esac

exec 9>"$E/actualizar.lock"
flock -n 9 || exit 0

ultimo=$(stat -c %Y "$E/ultima-busqueda" 2>/dev/null || echo 0)
if [ -f "$E/buscar-ya" ] || [ $(( $(date +%s) - ultimo )) -ge 600 ]; then
  rm -f "$E/buscar-ya"
  touch "$E/ultima-busqueda"
  if ! err=$(g fetch -q --depth 1 origin "$RAMA" 2>&1); then log "GitHub no responde ($(echo "$err" | tail -n 1)); lo intento más tarde"; exit 0; fi
fi

local_=$(g rev-parse HEAD)
remoto=$(g rev-parse "origin/$RAMA")
[ "$local_" = "$remoto" ] && { rm -f "$E/pendiente"; exit 0; }

if [ ! -f "$E/pendiente" ]; then
  log "Actualización descargada ($(g rev-parse --short "$remoto")): la aplico cuando no haya partida"
  echo "$remoto" > "$E/pendiente"
fi

if systemctl is-active --quiet mtglocal.service; then
  # by the LAN address: with --localhost-remoto (Funnel) 127.0.0.1 counts as outside and gets the login page
  estado=$(curl -fsS -m 5 "http://$IP:$PUERTO/partida-en-curso" 2>/dev/null || echo "?")
  if [ "$estado" = "1" ]; then exit 0; fi
  if [ "$estado" != "0" ]; then
    # The server is still starting, or an old version without /partida-en-curso: wait unless it is the middle of the night
    hora=$(date +%H)
    if [ "$hora" != "05" ]; then exit 0; fi
  fi
fi

log "Aplicando $(g rev-parse --short "$remoto") (sin partida en curso)"
g reset -q --hard "$remoto"
construir
systemctl restart mtglocal.service
# the catalog's hidden Simular games keep the old jar until they stop: close them, the catalog starts them again when needed
pkill -u mtg -f 'mtglocal\.jar .*--sim' || true
rm -f "$E/pendiente"
log "Listo: versión $(cat "$E/version"), servidor reiniciado"
