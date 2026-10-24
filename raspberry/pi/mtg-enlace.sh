#!/bin/bash
# The link for friends outside home: Tailscale Funnel publishes the game (and /github-hook for the webhook)
# at https://<pi>.<your-tailnet>.ts.net, and the game shows that link and asks remote players to log in.
# sudo mtg-enlace        turn it on
# sudo mtg-enlace off    turn it off (the game stays reachable only at home)
set -euo pipefail
. /opt/mtglocal/config.env
CONF=/opt/mtglocal/config.env

if [ "${1:-on}" = "off" ]; then
  tailscale funnel reset || true
  sed -i 's#^URL_PUBLICA_ARG=.*#URL_PUBLICA_ARG=#' "$CONF"
  systemctl restart mtglocal
  echo "Enlace apagado: el juego solo se ve desde casa."
  exit 0
fi

if ! tailscale status >/dev/null 2>&1; then
  echo "La Pi todavía no está en tu red de Tailscale. Ejecuta:  sudo tailscale up"
  echo "y abre el enlace que sale con tu cuenta de Tailscale. Después vuelve a ejecutar:  sudo mtg-enlace"
  exit 1
fi

echo "Activando el enlace. La primera vez Tailscale puede pedirte que abras un enlace para permitir Funnel:"
# tailscale only prints why it can't (HTTPS off, Funnel not allowed) to a terminal, so give it one
if ! script -qfec "timeout 600 tailscale funnel --bg $PUERTO" /dev/null; then
  echo
  echo "Tailscale no ha activado el enlace. Si arriba pone «HTTPS must be enabled»: en https://login.tailscale.com/admin/dns"
  echo "pulsa «Enable HTTPS» y vuelve a ejecutar  sudo mtg-enlace"
  exit 1
fi
timeout 120 tailscale funnel --bg --set-path /github-hook http://127.0.0.1:9000 || echo "(el webhook no se publicó; las actualizaciones siguen llegando cada 10 minutos)"

NOMBRE=$(tailscale status --json | python3 -I -c 'import json, sys; print(json.load(sys.stdin)["Self"]["DNSName"].rstrip("."))')
URL="https://$NOMBRE"
sed -i "s#^URL_PUBLICA_ARG=.*#URL_PUBLICA_ARG=\"--url-publica $URL --localhost-remoto\"#" "$CONF"
systemctl restart mtglocal
echo
echo "Enlace para tus amigos:  $URL/play.html"
echo "Webhook de GitHub:       $URL/github-hook"
