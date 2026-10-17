# Phalanx CIWS — simulador manual en primera persona (Android)

Juego de realidad aumentada: la cámara del celular es la mira de un sistema tipo **Phalanx CIWS** operado a mano.
Encima de la imagen en vivo se dibuja un HUD estilo avión, se detectan personas/autos/etc. con un recuadro y los
proyectiles quedan **fijos respecto del entorno**: si movés el celular mientras disparás, la "manguera" de trazadoras se curva
como en la realidad.

## Cómo se juega

| Control | Acción |
|---|---|
| Botón **FUEGO** (o tecla volumen −) mantenido | El rotor acelera 1 s y luego dispara a 4000 disparos/min |
| Soltar | Corte de fuego, descarga de aire / turbina que se apaga (0,5 s) |
| Botón **RECARGA** (o volumen +) | Recarga el cargador de 1550 tiros (4 s) |

El blanco más cercano a la mira queda **bloqueado** (rombo ámbar) con distancia estimada y tiempo de vuelo.
Un proyectil cuenta como **impacto** cuando llega a la distancia estimada del blanco estando dentro de su recuadro.

## HUD

- **Escalera de cabeceo conformal** (anclada al mundo, cada 5°; negativas punteadas) y horizonte con N/E/S/O.
- **Cinta de rumbo** arriba y **escala de alabeo** abajo.
- Panel izquierdo: inclinación, rumbo, alabeo, estado del cañón, giro del rotor, cadencia medida, disparos, cargador, temperatura del cañón.
- Panel derecho: blancos visibles, impactos, proyectiles en vuelo, datos del blanco bloqueado.
- Recuadros tipo *brackets* sobre los objetos detectados (persona, auto, camión, colectivo, moto, bici, avión, ave, bote, perro, tren).

## Cómo funciona por dentro

| Archivo | Qué hace |
|---|---|
| `Orientation.kt` | Vector de rotación (giroscopio + acelerómetro + brújula) → matriz mundo↔celular; proyección pinhole con el FOV real de la cámara (leído de Camera2). |
| `Ballistics.kt` | Proyectiles en coordenadas del mundo (Este/Norte/Arriba): 1100 m/s, gravedad, arrastre, dispersión ~2,8 mrad. |
| `GunController.kt` | Máquina de estados: LISTO → ACELERANDO (1 s) → FUEGO (4000/min) → DETENIENDO (0,5 s); munición, recarga, temperatura. |
| `PhalanxSound.kt` | Sintetizador procedural en tiempo real (AudioTrack, baja latencia): motor eléctrico, metralla a 66,7 Hz y ráfaga de aire con filtro en barrido. Sin archivos de audio. |
| `TargetDetector.kt` | MediaPipe Object Detector (EfficientDet‑Lite0, COCO) sobre cada cuadro de CameraX. |
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
