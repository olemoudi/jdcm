# JDCM en casa: el lector de datos (Raspberry Pi)

La web oficial no deja que un navegador lea sus datos y solo responde a conexiones de casa en
España (los servidores de GitHub no llegan). Por eso una Raspberry Pi en casa lee la web cinco
veces al día y publica los datos de **todos los grupos** (unos 109, de los tres deportes) en
`olemoudi/jdcm-data`, de donde los lee la web app (https://makensi.es/jdcm). No hay que configurar
grupos: las familias eligen cualquier equipo. Es un script de bash (curl, jq y git): no compila
nada y vale cualquier Raspberry Pi con Raspberry Pi OS, de 32 o de 64 bits.

## Instalar

1. **Comprueba el sistema.** En la Pi:
   - `uname -m` → `aarch64` es 64 bits, `armv7l` es 32 bits. `dpkg --print-architecture` → `arm64`
     o `armhf`. Los dos sirven.
   - `timedatectl` → «System clock synchronized: yes». La zona horaria da igual: el temporizador
     usa la hora de Madrid.
   - La Pi debe salir a internet por la conexión de casa (cable o wifi), sin VPN.
2. **Instala**, con tu usuario normal (no root, no sudo):
   ```sh
   curl -fsSLO https://raw.githubusercontent.com/olemoudi/jdcm/main/pwa/pi/install.sh
   bash install.sh
   ```
   Te pedirá la contraseña de sudo para instalar `curl jq git`.
3. **Añade la clave** que te enseña el instalador (una línea `ssh-ed25519 …`): en GitHub,
   `olemoudi/jdcm-data` → Settings → Deploy keys → **Add deploy key** → título «Raspberry Pi»,
   pega la línea, marca **Allow write access** → Add key. Vuelve a la Pi y pulsa Intro.
   Esa clave solo puede escribir en `jdcm-data`: no toca la app ni nada más.
4. **Listo.** El instalador deja programadas las lecturas (07:07, 10:07, 13:07, 16:07 y 19:07, hora
   de Madrid; funcionan sin iniciar sesión y tras un corte de luz) y hace una primera. De noche
   (22–7 h) no lee nada; para probar a cualquier hora: `~/.local/share/jdcm-fetch/jdcm-fetch.sh --now`.

## Comprobar que funciona

5. **Última lectura:** `cat ~/.local/state/jdcm-fetch/last-run` → `published=yes` y `stopped=` vacío.
6. **Registro:** `tail -n 40 ~/.local/state/jdcm-fetch/fetch.log`.
7. **Próximas lecturas:** `systemctl --user list-timers jdcm-fetch.timer`.
8. **Datos publicados:** `curl -s https://raw.githubusercontent.com/olemoudi/jdcm-data/main/v1/index.json | jq -r .generatedAt`
   (hora UTC). En la web app: Ajustes → Datos. Si la app dice **«Datos sin actualizar»**, la Pi
   lleva más de 12 horas sin publicar: mira si está encendida y conectada.

## Uso diario

9. **Cuánto pide a la web:** en cada lectura, el calendario de toda la jornada en juego (unas 6–9
   peticiones) y, solo para los grupos con un partido ya jugado sin resultado, la página de ese grupo;
   una vez al día, las tres semanas siguientes; una vez a la semana, el resto de la temporada. Como
   mucho 150 peticiones y 8 minutos por lectura, espaciadas 1,5 s. La primera vez lee todos los
   grupos (unas 140 peticiones). El registro dice cuántas usó cada parte («requests=…»).
10. **Actualizar el lector:** vuelve a descargar y ejecutar `install.sh` (paso 2). Conserva la
    clave, el registro y las lecturas programadas.
11. **La tarjeta SD:** el lector trabaja en memoria (`/run/user`), su registro rota solo (como
    mucho ~1 MB) y escribe unos pocos KB al día. Usa una fuente de alimentación buena: los cortes
    son lo que estropea las tarjetas.

## Después del verano

12. **Fin de temporada:** sin partidos, puedes dejarla (cinco lecturas al día, poca cosa) o
    pararla: `systemctl --user disable --now jdcm-fetch.timer`.
13. **Temporada nueva:** cuando la web oficial publique los calendarios nuevos, reactiva las
    lecturas con `systemctl --user enable --now jdcm-fetch.timer`. Los grupos y equipos nuevos
    aparecen solos (salen de los calendarios de la web). Conviene empezar con datos limpios: pide
    que vacíen `jdcm-data` (o borra su rama `main`) y lanza una lectura:
    `~/.local/share/jdcm-fetch/jdcm-fetch.sh --now`.

## Si algo falla

- **`Permission denied (publickey)`** en el registro: la clave no está en `jdcm-data`, o está sin
  «Allow write access» (paso 3).
- **`site down (…)`**: la web oficial no contestó; se vuelve a intentar en la siguiente hora. Si
  pasa siempre, comprueba que la Pi no sale por una VPN: la web solo responde a conexiones de casa.
- **Nada en el registro:** `systemctl --user status jdcm-fetch.timer` y
  `loginctl show-user $USER -p Linger` (debe decir `yes`; si no: `sudo loginctl enable-linger $USER`).
- **Errores del propio servicio:** `journalctl --user -u jdcm-fetch -n 20`.

**Desinstalar:** `systemctl --user disable --now jdcm-fetch.timer`, borra
`~/.local/share/jdcm-fetch`, `~/.config/jdcm-fetch`, `~/.local/state/jdcm-fetch` y
`~/.config/systemd/user/jdcm-fetch.*`, y quita la clave en GitHub (paso 3).
