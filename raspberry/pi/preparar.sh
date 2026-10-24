#!/bin/bash
# First boot, run by cloud-init (runcmd) as root, before anything is downloaded.
# 1. Fixed IP on the cable or the Wi-Fi Luis chose (NetworkManager).
# 2. Moves the installer off the SD card's boot partition (and the secrets with it) to /opt/mtglocal/instalador.
# 3. Starts the long part (instalar.sh) as its own service, so the boot finishes and it can retry without internet.
set -u
BOOT=/boot/firmware/mtg
DEST=/opt/mtglocal/instalador
LOG=/var/log/mtglocal-instalacion.log
exec >>"$LOG" 2>&1
echo "=== preparar.sh $(date -Is)"

mkdir -p "$DEST"
cp -r "$BOOT"/. "$DEST"/
chmod 700 "$DEST"
. "$DEST/config.env"

# Wi-Fi country (the radio stays blocked without it) and time zone
raspi-config nonint do_wifi_country "${PAIS_WIFI:-ES}" || true
rfkill unblock wifi || true
timedatectl set-timezone "${ZONA_HORARIA:-Europe/Madrid}" || true

# Fixed IP. The connection cloud-init made (DHCP) stays as a fallback with lower priority.
DNS="$PUERTA_ENLACE 1.1.1.1"
if [ "${CONEXION:-cable}" = "wifi" ]; then
  SSID=$(cat "$DEST/wifi-nombre.txt")
  PSK=$(cat "$DEST/wifi-clave.txt")
  nmcli connection delete mtg-wifi >/dev/null 2>&1 || true
  nmcli connection add type wifi ifname wlan0 con-name mtg-wifi ssid "$SSID" \
    wifi-sec.key-mgmt wpa-psk wifi-sec.psk "$PSK" \
    ipv4.method manual ipv4.addresses "$IP/$PREFIJO" ipv4.gateway "$PUERTA_ENLACE" ipv4.dns "$DNS" \
    connection.autoconnect yes connection.autoconnect-priority 100
  nmcli connection up mtg-wifi || true
else
  nmcli connection delete mtg-cable >/dev/null 2>&1 || true
  nmcli connection add type ethernet ifname eth0 con-name mtg-cable \
    ipv4.method manual ipv4.addresses "$IP/$PREFIJO" ipv4.gateway "$PUERTA_ENLACE" ipv4.dns "$DNS" \
    connection.autoconnect yes connection.autoconnect-priority 100
  nmcli connection up mtg-cable || true
fi

# The secrets are now only in /opt/mtglocal/instalador (root only): off the boot partition, which anyone can read
rm -f "$BOOT/wifi-clave.txt" "$BOOT/wifi-nombre.txt" "$BOOT/tailscale-clave.txt" "$BOOT/webhook-secreto.txt"

install -m 644 "$DEST/servicios/mtg-instalar.service" /etc/systemd/system/mtg-instalar.service
systemctl daemon-reload
systemctl enable mtg-instalar.service
systemctl start --no-block mtg-instalar.service
echo "preparar.sh listo: la instalación sigue en mtg-instalar.service"
