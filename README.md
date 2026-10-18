# Phalanx CIWS — simulador manual en primera persona (Android)

Juego de realidad aumentada: la cámara del celular es la mira de un sistema tipo **Phalanx CIWS** operado a mano.
Encima de la imagen en vivo se dibuja un HUD estilo avión, se detectan personas/autos/etc. con un recuadro y los
proyectiles quedan **fijos respecto del entorno**: si movés el celular mientras disparás, la "manguera" de trazadoras se curva
como en la realidad.

## Cómo se juega

| Control | Acción |
|---|---|
| Botón **FUEGO** mantenido | El rotor acelera 1 s (motor eléctrico) y recién ahí dispara a 4000 disparos/min |
| Soltar después de disparar | Corte de fuego: descarga de aire / turbina que se apaga (0,5 s) |
| Soltar antes de llegar a disparar | El motor eléctrico frena bruscamente (sin descarga de aire) |
| Botón **RECARGA** | Recarga el cargador de 1550 tiros (4 s) |
| Botón **AVIONES** (interruptor) | Activa los contactos aéreos |
| Teclas de volumen | Volumen del juego |

### Aviones

Con **AVIONES** encendido y apuntando más de 10° sobre el horizonte, cada 10 segundos aparece un avión al azar:
**F-16, Mirage III, A-4 Skyhawk, MiG-29 o C-130 Hércules** (modelos 3D low-poly a escala real, en metros).
Vuelan en coordenadas del mundo real, igual que los proyectiles, así que quedan fijos al entorno de la cámara.
El HUD los marca con recuadro, distancia, velocidad y vida, y dibuja una **retícula de adelanto** (dónde apuntar
para que las balas lo intercepten, incluida la caída). Si están fuera de pantalla aparece una flecha roja.
Los impactos se calculan en 3D; al quedarse sin vida el avión explota, cae girando con fuego y humo, y vuelve a
explotar al tocar el suelo. El sonido de las explosiones llega con el retardo de la velocidad del sonido.

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
| `GunController.kt` | Máquina de estados: LISTO → ACELERANDO (1 s) → FUEGO (4000/min) → DETENIENDO (0,5 s); munición, recarga, temperatura. |
| `PhalanxSound.kt` | Sintetizador procedural en tiempo real (AudioTrack, baja latencia): motor eléctrico, metralla a 66,7 Hz y ráfaga de aire con filtro en barrido. Sin archivos de audio. |
| `TargetDetector.kt` | MediaPipe Object Detector (EfficientDet‑Lite0, COCO) sobre cada cuadro de CameraX. |
| `Aircraft.kt` | Modelos 3D, vuelo, impactos (segmento de la bala contra la esfera del avión), derribo y dibujo con perspectiva. |
| `Effects.kt` | Explosiones y humo con sprites, anclados al mundo y escalados por distancia. |
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
