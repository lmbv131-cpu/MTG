# MTG Local en esta Raspberry Pi

El juego arranca solo al encender la Pi. Tarda 1 o 2 minutos en cargar las cartas.

## Direcciones

| Qué | Dirección | Desde dónde |
|---|---|---|
| Mesa (proyector o pantalla grande) | http://__IP__:__PUERTO__/ | Solo en casa |
| Mesa (otra dirección) | http://__IP__:__PUERTO__/mesa | Solo en casa |
| Pantalla del jugador (tabletas y móviles) | http://__IP__:__PUERTO__/play.html (corta: /j) | Casa y fuera |
| Iniciar sesión | https://__NOMBRE__.TU-RED.ts.net/login (quien abre la pantalla del jugador desde fuera llega aquí solo) | Fuera |
| Usuarios del juego (crear, borrar, contraseñas) | http://__IP__:__PUERTO__/users, o ⚙ en una tableta de casa, o «👤 Usuarios» en la mesa | Solo en casa |
| Catálogo de acciones (con Simular, de una en una) | http://__IP__:8090/ o http://__NOMBRE__:8090/ por Tailscale | Casa y tus aparatos con Tailscale |
| Enlace de fuera (cuando enciendas `mtg-enlace`) | https://__NOMBRE__.TU-RED.ts.net/play.html | Fuera |

Desde fuera de casa solo se puede usar el inicio de sesión y la pantalla de cada jugador. Todo lo demás funciona únicamente en tu red (192.168…).
El inicio de sesión, la página de usuarios y la lista de lo que se ve desde fuera llegan con el paquete p152 del juego. Hasta entonces no enciendas `mtg-enlace`.

## Entrar a la Pi

- Desde tu PC: `ssh __NOMBRE__` (o `ssh __USUARIO__@__IP__`), con la clave de tu PC y sin contraseña.
- Con teclado y pantalla: usuario `__USUARIO__`. Cambia la contraseña con `passwd`.
- `__USUARIO__` es la cuenta de la Pi, no un jugador. Los usuarios del juego para tus amigos se crean en /users.

## Comandos útiles

| Para | Comando |
|---|---|
| Ver si el juego está encendido | `systemctl status mtglocal` |
| Ver lo que escribe el juego | `sudo journalctl -u mtglocal -f` |
| Reiniciar el juego | `sudo systemctl restart mtglocal` |
| Buscar actualización ya | `sudo mtg-actualizar ya` |
| Historial de actualizaciones | `cat /var/log/mtglocal-actualizaciones.log` |
| Encender o apagar el enlace de fuera | `sudo mtg-enlace` / `sudo mtg-enlace off` |
| Cambiar puerto o memoria | `sudo nano /opt/mtglocal/config.env` y reiniciar el juego |
| Apagar la Pi | `sudo poweroff` |

Las actualizaciones del juego llegan solas desde GitHub y solo se aplican cuando no hay partida en curso.
