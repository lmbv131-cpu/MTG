# Actualizaciones de MTG Commander

MTG Commander.exe mira este repositorio cada vez que se abre con internet. Si `manifiesto.txt`
cambió, baja solo los archivos que cambiaron y luego las imágenes de las cartas nuevas desde Scryfall.

- `app.jar`: el juego (servidor y pantallas).
- `motor.jar`: el motor de reglas (GameEngine, GPL v3) y sus librerías.
- `engine/res`: las reglas de las cartas, ediciones, fichas y mazos preconstruidos.
- `manifiesto.txt`: sha1, tamaño y ruta de cada archivo.

Esta carpeta la rellena INSTALAR.bat del juego, que también la sube.
