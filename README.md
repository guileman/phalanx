# Phalanx AR — simulador de sistemas de armas en realidad aumentada (Android)

La cámara del celular es la mira de un sistema de armas real, operado a mano y en primera persona (con algo de ficción).
Encima de la imagen en vivo se dibuja un HUD, se detectan personas/autos/etc. con un recuadro y todo lo virtual
(proyectiles, misiles, aviones, carros, explosiones) queda **fijo respecto del entorno**: si movés el celular, la trayectoria
se ve como en la realidad.

## Capturas y ayuda

| Phalanx | Javelin |
|---|---|
| ![Phalanx](docs/capturas/phalanx-amenaza-entrante.jpg) | ![Javelin](docs/capturas/javelin-fijado-termico.jpg) |

👉 **[Guía de ayuda: cómo leer la pantalla](docs/AYUDA.md)** — todas las capturas explicadas y preguntas frecuentes.

## Menú de inicio

Al abrir la app se elige el sistema tocando su tarjeta (las ilustraciones son renders propios, `tools/render_weapons.py`):

| Sistema | Real | Ficción |
|---|---|---|
| **Phalanx CIWS** | Cañón rotativo M61A1 de 20 mm, defensa automática de buques | Se apunta y dispara a mano |
| **Gepard 35 mm** | Blindado antiaéreo con dos cañones Oerlikon de 35 mm (550/min cada uno) | Apuntado manual y munición AHEAD (la del Skynex) |
| **Javelin** | Misil antitanque "dispara y olvida" con unidad de comando (CLU) y visor térmico | También fija aviones |
| **TAM 105 mm** | Tanque Argentino Mediano: cañón de 105 mm, telémetro láser y computadora de tiro | Se apunta con el celular |
| **Obús CITER 155** | Obús remolcado argentino de 155 mm para tiro indirecto | Cargas escaladas (blancos a 1–3,5 km), dron observador |

El botón **MENÚ** (o atrás) vuelve a la selección.

### Gepard 35 mm
- Dos cañones que se **alternan** (1100 disparos/min en total), trazadoras gruesas saliendo de ambos lados.
- **MUNICIÓN**: **HE** (contacto, más daño por impacto) o **AHEAD**: el telémetro mide el avión más cercano a la mira
  (al punto de intercepción) o el objeto bloqueado, y la espoleta se programa a esa distancia; el proyectil estalla
  y larga un cono de fragmentos (~10°, ~40 m) que daña lo que tenga adelante.
- Panel con munición por cañón (IZQ/DER), telémetro y distancia de espoleta; mira con arco de distancia.
- Estampido grave de 35 mm + golpe del cerrojo; vibración fuerte por disparo.

### Javelin
1. **Enfriamiento del buscador** (2,5 s, con siseo).
2. Mantener el blanco (objeto detectado o avión) dentro de la **ventana de seguimiento** ~1,4 s, con pitidos cada vez
   más rápidos → **FIJADO** (tono continuo y cruz de borde a borde).
3. **LANZAR**: lanzamiento suave (el misil sale despacio del tubo), a los 0,3 s se enciende el motor (rugido, destello
   del flash) y vuela solo con estela de humo. **TOP**: trepa y cae sobre el blanco desde arriba; **DIR**: trayectoria baja.
   A corta distancia vuela más lento para poder doblar. Contra aviones calcula la intercepción.
4. Recarga de tubo (4 s) y 6 misiles; al quedarse sin misiles llega un reabastecimiento.
- **WFOV/NFOV**: zoom real de la cámara (hasta 3x); **DÍA/TÉRM**: visor térmico simulado (monocromo de alto contraste).

### TAM 105 mm
1. **LÁSER**: mide la distancia a lo que está en el centro de la mira (vehículo, avión, objeto detectado o el suelo).
   Si es un vehículo, la computadora lo **sigue** mientras siga cerca de la mira.
2. **FUEGO**: la computadora de tiro agrega la **elevación** por la caída del proyectil y el **adelanto** si el blanco
   se mueve. Sin medir, el disparo sale derecho por la mira y a distancia cae corto.
3. **Munición**: **APFSDS** (flecha perforante, 1450 m/s, destruye un carro de un tiro) o **HE** (explosiva, 730 m/s,
   cae más y daña en un radio de 9 m). Cambiar de munición obliga a recargar.
4. Recarga de 5 s con sonido del cierre de recámara; estampido, fogonazo y nube de humo en la boca; **WFOV/NFOV** de zoom.
- La simulación de la computadora pasa a menos de 0,5 m del blanco a 800–2000 m, quieto o en movimiento.

### Obús CITER 155 mm
- **Apuntar**: el rumbo del celular es el azimut y la inclinación es la elevación del tubo (en grados y milésimas).
- **CARGA 1–5**: velocidad de salida (110–230 m/s, escalada para el juego). Con elevación y carga se cubre de 0,4 a 4 km,
  con 5 a 38 s de vuelo.
- La pieza calcula todo el tiempo dónde va a caer: **trayectoria punteada** y **círculo de impacto** dibujados sobre el
  terreno real, más alcance y tiempo de vuelo en el panel.
- **CORRECCIÓN**: respecto del carro más cercano al impacto previsto indica cuántos metros CORTO/LARGO e IZQ/DER.
- **MAPA / DRON**: mapa de tiro visto desde arriba (blancos, línea de tiro, proyectiles en vuelo, impactos) o la
  imagen de un **dron observador** sobre el blanco, para ver el impacto aunque esté a kilómetros.
- Cuenta regresiva hasta el impacto y aviso **¡SPLASH!** 5 s antes. Daño en área de 22 m.
- Los carros arrancan activados, a 1–3,5 km y casi quietos, en todo el frente.

## Phalanx: cómo se juega

| Control | Acción |
|---|---|
| Botón **FUEGO** mantenido | El motor neumático acelera los cañones (0,4 s) y recién ahí dispara |
| Soltar después de disparar | Corte de fuego: descarga de aire / turbina que se apaga (0,5 s) |
| Soltar antes de llegar a disparar | El rotor frena bruscamente (sin descarga de aire) |
| Botón **MODO** | Cicla 4500/min → 3000/min → ráfaga 60 → ráfaga 100 (en ráfaga, un toque dispara la ráfaga completa) |
| Botón **RECARGA** | Recarga el cargador de 1550 tiros (4 s) |
| Botón **AVIONES** (interruptor) | Activa los contactos aéreos (en los tres sistemas) |
| Botón **CARROS** (interruptor) | Activa carros de combate sobre el suelo (en los tres sistemas) |
| Teclas de volumen | Volumen del juego |

### Sonido (basado en el Phalanx real)

- El cañón M61A1 del Phalanx es **accionado neumáticamente** (controlado eléctricamente): al apretar se oye la válvula
  de aire y el silbido de la turbina acelerando el rotor de 6 cañones.
- Metralla a 4500 o 3000 disparos/min (cadencias seleccionables del Block 1/1B); ráfagas de 60/100 tiros como el Block 0.
- Al cortar: descarga de aire y rotor desacelerando.
- Lo eléctrico son los motores de la torreta (giro/elevación, hasta ~115°/s): al mover el celular se oye el zumbido
  de los servos, proporcional a la velocidad (el HUD muestra TORRETA °/s y avisa MÁX por encima de 115°/s).

### Aviones

Con **AVIONES** encendido, cada 10 segundos aparece un avión al azar (si no está a la vista, seguí la flecha roja):
**F-16, Mirage III, A-4 Skyhawk, MiG-29 o C-130 Hércules** (modelos 3D low-poly a escala real, en metros).
Vuelan en coordenadas del mundo real, igual que los proyectiles, así que quedan fijos al entorno de la cámara.
El HUD los marca con recuadro, distancia, velocidad y vida, y dibuja una **retícula de adelanto** (dónde apuntar
para que las balas lo intercepten, incluida la caída). Si están fuera de pantalla aparece una flecha roja.
Los impactos se calculan en 3D; al quedarse sin vida el avión explota, cae girando con fuego y humo, y vuelve a
explotar al tocar el suelo. Cerca de la mitad de los contactos hacen una **pasada de ataque**: vienen de frente
en picada directo hacia el Phalanx, pasan a pocos metros (alarma "¡AMENAZA ENTRANTE!") y se van trepando. El sonido de las explosiones llega con el retardo de la velocidad del sonido.

### Carros de combate

Con **CARROS** encendido, cada 12 segundos aparece un carro sobre el suelo a 200–1200 m: **TAM, T-72, M1 Abrams o
Leopard 2** (modelos 3D a escala). Cruzan de costado o avanzan hacia vos. Aguantan 12 impactos de 20 mm, 4 de 35 mm
o un Javelin (que los ataca desde arriba en modo TOP). Al destruirse explotan, estalla su munición y quedan ardiendo.

### Explosiones y flash

- Los impactos sobre objetos detectados (personas, autos…) generan explosiones ancladas al entorno, de tamaño
  proporcional al recuadro (objeto cercano → explosión grande).
- Sprites de explosión (16 cuadros) y humo generados proceduralmente (`res/drawable-nodpi`).
- El flash LED destella durante la metralla, cada 2 disparos (≈16 destellos/s, el máximo práctico del LED).

El blanco más cercano a la mira queda **bloqueado** (rombo ámbar) con distancia estimada y tiempo de vuelo.
Un proyectil cuenta como **impacto** cuando llega a la distancia estimada del blanco estando dentro de su recuadro.

## HUD

- **Escalera de cabeceo conformal** (anclada al mundo, cada 5°; negativas punteadas) y horizonte con N/E/S/O.
- **Cinta de rumbo** arriba y **escala de alabeo** abajo.
- Panel izquierdo: inclinación, rumbo, alabeo, estado del cañón, giro del rotor, cadencia medida, disparos, cargador, temperatura del cañón.
- Panel derecho: blancos visibles, impactos, proyectiles en vuelo, datos del blanco bloqueado.
- Durante la metralla la imagen de la cámara vibra y se ve borrosa (desenfoque en Android 12+, solo vibración en versiones anteriores).
- Recuadros tipo *brackets* sobre los objetos detectados (persona, auto, camión, colectivo, moto, bici, avión, ave, bote, perro, tren).

## Cómo funciona por dentro

| Archivo | Qué hace |
|---|---|
| `Orientation.kt` | Vector de rotación (giroscopio + acelerómetro + brújula) → matriz mundo↔celular; proyección pinhole con el FOV real de la cámara (leído de Camera2). |
| `Ballistics.kt` | Proyectiles en coordenadas del mundo (Este/Norte/Arriba): 1100 m/s, gravedad, arrastre, dispersión ~2,8 mrad. |
| `GunController.kt` | Máquina de estados: LISTO → ACELERANDO (0,4 s) → FUEGO (4500/3000 por min o ráfagas 60/100) → DETENIENDO (0,5 s); munición, recarga, temperatura. |
| `PhalanxSound.kt` | Sintetizador procedural en tiempo real (AudioTrack, baja latencia): motor neumático, servos de la torreta, metralla a 75/50 Hz, freno, explosiones y ráfaga de aire con filtro en barrido. Sin archivos de audio. |
| `TargetDetector.kt` | MediaPipe Object Detector (EfficientDet‑Lite0, COCO) sobre cada cuadro de CameraX. |
| `Aircraft.kt` | Modelos 3D, vuelo, impactos (segmento de la bala contra la esfera del avión), derribo y dibujo con perspectiva. |
| `Effects.kt` | Explosiones y humo con sprites, anclados al mundo y escalados por distancia. |
| `MenuActivity.kt` | Menú de inicio con las tarjetas de cada sistema. |
| `Weapon.kt` | Sistemas y configuración de cada cañón (cadencias, cargador, velocidad, bocas de fuego, modos). |
| `Cannon.kt` | TAM y obús: municiones, recarga y cargas. |
| `Javelin.kt` | Buscador, fijación, misiles, guiado (perfil TOP/DIR e intercepción) y espoleta. |
| `HudView.kt` | Game loop + dibujo. Las detecciones se anclan a una dirección del mundo usando la orientación *al momento de la captura*, así los recuadros siguen al objeto aunque gires el celular entre detecciones. |

## Compilar

**Opción fácil (sin instalar nada):** cada push a `main` dispara GitHub Actions, que compila el APK y lo publica en
**Releases** (`build-N`). Desde el celular entrá a *Releases*, bajá el `.apk` e instalalo (hay que permitir "instalar apps de origen desconocido").

**Android Studio:** abrir la carpeta del proyecto y Run. El modelo `efficientdet_lite0.tflite` se descarga solo en el primer build
(tarea `downloadModel`).

Requisitos: Android 8.0+ (API 26), cámara trasera; giroscopio recomendado.

## Ajustes rápidos

- Cadencia, tiempos de giro, cargador: constantes en `GunController`.
- Velocidad, dispersión, posición de la boca del cañón: `Ballistics`.
- Mezcla y timbre del sonido: `PhalanxSound` (frecuencias del motor, decaimientos del disparo, barrido del aire).
- Si la manguera de trazadoras no coincide bien con la imagen en tu modelo de celular, el FOV se puede forzar con `hud.setHorizontalFov(...)` en `MainActivity`.
