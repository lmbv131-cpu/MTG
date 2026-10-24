#!/bin/bash
# The long part of the first boot (mtg-instalar.service, root). Safe to run again: each step checks what is already there.
# Leaves MTG Local running on $IP:$PUERTO, updating itself from GitHub between games, plus cloudflared and Claude Code.
set -euo pipefail
INST=/opt/mtglocal/instalador
. "$INST/config.env"
M=/opt/mtglocal
LOG=/var/log/mtglocal-instalacion.log
exec >>"$LOG" 2>&1
echo "=== instalar.sh $(date -Is)"
paso() { echo; echo "--- $*"; }

paso "Esperando internet"
for i in $(seq 1 120); do
  curl -fsS -m 10 -o /dev/null https://github.com && break
  sleep 10
done

paso "Paquetes del sistema"
export DEBIAN_FRONTEND=noninteractive
apt-get update -q
apt-get install -y -q git curl ca-certificates python3 rsync vmtouch unzip iputils-arping

paso "Usuario mtg (el servidor no corre como root)"
id mtg >/dev/null 2>&1 || useradd --system --home-dir "$M/home" --shell /usr/sbin/nologin mtg
mkdir -p "$M/juego/cache" "$M/juego/mazos" "$M/estado" "$M/home"

paso "Java $JAVA_VERSION (Temurin, ARM 64 bits)"
if [ ! -x "$M/runtime/bin/java" ]; then
  curl -fL --retry 5 -o /tmp/jre.tar.gz \
    "https://api.adoptium.net/v3/binary/latest/$JAVA_VERSION/ga/linux/aarch64/jre/hotspot/normal/eclipse?project=jdk"
  rm -rf "$M/runtime.part" && mkdir -p "$M/runtime.part"
  tar -xzf /tmp/jre.tar.gz -C "$M/runtime.part" --strip-components=1
  rm -rf "$M/runtime" && mv "$M/runtime.part" "$M/runtime" && rm -f /tmp/jre.tar.gz
fi
"$M/runtime/bin/java" -version

paso "El juego desde GitHub ($REPO_JUEGO)"
if [ ! -d "$M/repo/.git" ]; then
  rm -rf "$M/repo"
  git clone --depth 1 --branch "$RAMA" "$REPO_JUEGO" "$M/repo"
fi
install -m 755 "$INST/mtg-actualizar.sh" /usr/local/bin/mtg-actualizar
install -m 755 "$INST/mtg-actualizar-privado.sh" /usr/local/bin/mtg-actualizar-privado
cp "$INST/config.env" "$M/config.env"
[ -f "$INST/webhook-secreto.txt" ] && install -m 640 "$INST/webhook-secreto.txt" "$M/webhook-secreto.txt"
chown -R mtg:mtg "$M/repo" "$M/juego" "$M/estado" "$M/home"
[ -f "$M/webhook-secreto.txt" ] && chown root:mtg "$M/webhook-secreto.txt"
/usr/local/bin/mtg-actualizar construir

paso "Tus mazos"
if [ -f "$INST/mazos.zip" ] && [ -z "$(ls -A "$M/juego/mazos")" ]; then
  unzip -o -q "$INST/mazos.zip" -d "$M/juego/mazos"
  chown -R mtg:mtg "$M/juego/mazos"
fi

paso "Tailscale (el enlace para jugar desde fuera; se activa con mtg-enlace)"
command -v tailscale >/dev/null || curl -fsSL https://tailscale.com/install.sh | sh
install -m 755 "$INST/mtg-enlace.sh" /usr/local/bin/mtg-enlace
if [ -s "$INST/tailscale-clave.txt" ] && ! tailscale status >/dev/null 2>&1; then
  tailscale up --authkey="$(cat "$INST/tailscale-clave.txt")" --hostname="$(hostname)" || echo "Tailscale no pudo entrar con la clave; hazlo con: sudo tailscale up"
fi

paso "Servicios"
for f in mtglocal.service mtg-actualizar.service mtg-actualizar.timer mtg-webhook.service mtg-arp.service \
         mtg-imagenes.service mtg-precarga.service claude-remoto.service; do
  sed "s/__USUARIO__/$USUARIO/g" "$INST/servicios/$f" > "/etc/systemd/system/$f"
done
systemctl daemon-reload
systemctl enable --now mtglocal.service mtg-actualizar.timer mtg-webhook.service mtg-arp.service
systemctl enable mtg-imagenes.service mtg-precarga.service claude-remoto.service
systemctl start --no-block mtg-imagenes.service

paso "Claude Code para $USUARIO"
mkdir -p /srv/claude-mtg && chown "$USUARIO:$USUARIO" /srv/claude-mtg
if [ ! -x "/home/$USUARIO/.local/bin/claude" ]; then
  runuser -l "$USUARIO" -c 'curl -fsSL https://claude.ai/install.sh | bash' || echo "Claude Code no se pudo instalar; repítelo con: curl -fsSL https://claude.ai/install.sh | bash"
fi
grep -q '.local/bin' "/home/$USUARIO/.profile" 2>/dev/null || echo 'export PATH="$HOME/.local/bin:$PATH"' >> "/home/$USUARIO/.profile"

paso "LEEME con las direcciones"
sed -e "s/__IP__/$IP/g" -e "s/__PUERTO__/$PUERTO/g" -e "s/__USUARIO__/$USUARIO/g" -e "s/__NOMBRE__/$(hostname)/g"   "$INST/LEEME-PI.md" | tee "$M/LEEME.md" > "/home/$USUARIO/LEEME.md"
chown "$USUARIO:$USUARIO" "/home/$USUARIO/LEEME.md"

paso "Listo"
systemctl disable mtg-instalar.service
touch "$M/.instalado"
echo "MTG Local instalado: http://$IP:$PUERTO/  (registro: $LOG)"
