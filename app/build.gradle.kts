plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.guileman.phalanx"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.guileman.phalanx"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Firma con la clave debug para poder instalar el APK release directamente
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    androidResources {
        // MediaPipe mapea el modelo en memoria: no debe ir comprimido
        noCompress += "tflite"
    }
}

// ---- Descarga automática del modelo de detección (EfficientDet-Lite0, COCO) ----
val modelUrl =
    "https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/int8/latest/efficientdet_lite0.tflite"
val modelFile = file("src/main/assets/efficientdet_lite0.tflite")

val downloadModel by tasks.registering {
    description = "Descarga el modelo de detección de objetos si no existe"
    outputs.file(modelFile)
    onlyIf { !modelFile.exists() }
    doLast {
        modelFile.parentFile.mkdirs()
        val tmp = File(modelFile.parentFile, modelFile.name + ".part")
        java.net.URI(modelUrl).toURL().openStream().use { input ->
            tmp.outputStream().use { output -> input.copyTo(output) }
        }
        tmp.renameTo(modelFile)
        println("Modelo descargado: ${modelFile.length()} bytes")
    }
}
tasks.named("preBuild") { dependsOn(downloadModel) }

dependencies {
    val camerax = "1.4.1"
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    implementation("com.google.mediapipe:tasks-vision:0.10.14")
}
