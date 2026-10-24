#!/bin/bash
# The catalog and the Pi's own scripts, from the same public repo as the game (root, run by mtg-actualizar.service after
# mtg-actualizar). They reach GitHub like everything else: prepared in Documentos\MTG\instalar, published by INSTALAR.bat
# (Publicar copies catalogo/ and raspberry/ to lmbv131-cpu/MTG); mtg-actualizar has already fetched it into repo/.
#   - repo/catalogo   -> /opt/mtglocal/catalogo (code only: the recordings and pictures made on the Pi stay), and the
#                        catalog server is recompiled when its .java changed;
#   - repo/raspberry/pi -> the Pi's own scripts and services.
# Applied only when no game is running, like the game.
set -euo pipefail
. /opt/mtglocal/config.env
M=/opt/mtglocal
R=$M/repo
C=$M/catalogo
E=$M/estado
LOG=/var/log/mtglocal-actualizaciones.log
log() { echo "$(date '+%F %T') [catálogo y scripts] $*" >>"$LOG"; }

exec 8>"$E/privado.lock"
flock -n 8 || exit 0
[ -d "$R/catalogo" ] || [ -d "$R/raspberry/pi" ] || exit 0

# what is in the repo now, by content: only a real change does anything
huella() { (cd "$R" && find catalogo raspberry/pi -type f 2>/dev/null | LC_ALL=C sort | xargs -r sha1sum) | sha1sum | cut -c1-40; }
ahora=$(huella)
[ "$ahora" = "$(cat "$E/catalogo-huella" 2>/dev/null)" ] && exit 0

estado=$(curl -fsS -m 5 "http://$IP:$PUERTO/partida-en-curso" 2>/dev/null || echo "?")
[ "$estado" = "1" ] && exit 0

# --- catalog: code only (recordings, pictures and the hidden browsers' profiles are the Pi's own)
if [ -d "$R/catalogo" ] && [ -d "$C" ]; then
  java_antes=$(cat "$C/servidor/CatalogoServer.java" "$C/servidor/Auto.java" 2>/dev/null | sha1sum)
  escenas_antes=$(cat "$C"/grabacion/*.js 2>/dev/null | sha1sum)
  proxy_antes=$(sha1sum < "$C/servidor/sin_motor_proxy.py" 2>/dev/null || true)
  rsync -rlt --chown=mtg:mtg \
    --exclude 'grab/' --exclude 'img/' --exclude 'vid/' --exclude 'real/' --exclude 'servidor/navegador*' --exclude 'servidor/tmp/' \
    --exclude '*.log' --exclude '*.exe' --exclude '*.bat' --exclude '*.ps1' --exclude '*.jar' --exclude 'servidor/sin-motor.txt' \
    "$R/catalogo/" "$C/"
  if [ "$java_antes" != "$(cat "$C/servidor/CatalogoServer.java" "$C/servidor/Auto.java" | sha1sum)" ]; then
    t=$(mktemp -d)
    if javac -nowarn -encoding UTF-8 -d "$t" "$C/servidor/CatalogoServer.java" "$C/servidor/Auto.java" 2>>"$LOG" \
       && (cd "$t" && jar --create --file "$t/CatalogoServer.jar" --main-class CatalogoServer ./*.class); then
      install -o mtg -g mtg -m 644 "$t/CatalogoServer.jar" "$C/servidor/CatalogoServer.jar"
      systemctl restart mtg-catalogo.service
    else
      log "El catálogo nuevo no compila: sigue el anterior"
    fi
    rm -rf "$t"
  fi
  [ "$proxy_antes" != "$(sha1sum < "$C/servidor/sin_motor_proxy.py")" ] && systemctl restart mtg-catalogo-puerta.service
  # the hidden Simular game keeps the scene scripts it loaded when its board was made: start it again with the new ones
  if [ "$escenas_antes" != "$(cat "$C"/grabacion/*.js 2>/dev/null | sha1sum)" ]; then
    pkill -u mtg -f 'mtglocal\.jar .*--sim' || true
    systemctl restart mtg-catalogo.service
  fi
fi

# --- the Pi's own scripts and services
if [ -d "$R/raspberry/pi" ]; then
  P=$R/raspberry/pi
  install -m 755 "$P/mtg-actualizar.sh" /usr/local/bin/mtg-actualizar
  install -m 755 "$P/mtg-actualizar-privado.sh" /usr/local/bin/mtg-actualizar-privado
  install -m 755 "$P/mtg-enlace.sh" /usr/local/bin/mtg-enlace
  install -m 644 "$P/unir-jar.py" "$M/unir-jar.py"
  install -m 755 "$P/mtg-webhook.py" "$M/mtg-webhook.py"
  for f in "$P"/servicios/*.service "$P"/servicios/*.timer; do
    n=$(basename "$f"); [ "$n" = mtg-instalar.service ] && continue
    [ -f "/etc/systemd/system/$n" ] || continue   # only the ones this Pi already uses
    sed "s/__USUARIO__/$USUARIO/g" "$f" > "/etc/systemd/system/$n.nuevo"
    if cmp -s "/etc/systemd/system/$n.nuevo" "/etc/systemd/system/$n"; then rm -f "/etc/systemd/system/$n.nuevo"; else mv "/etc/systemd/system/$n.nuevo" "/etc/systemd/system/$n"; fi
  done
  systemctl daemon-reload
fi
echo "$ahora" > "$E/catalogo-huella"
log "Aplicado (versión del juego $(cat "$E/version" 2>/dev/null))"
