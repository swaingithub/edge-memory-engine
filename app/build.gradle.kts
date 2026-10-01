plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.edgememory"
    compileSdk = 34
    
    defaultConfig {
        applicationId = "com.edgememory"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }
    }
    
    externalNativeBuild {
        cmake {
            path = file("CMakeLists.txt")
        }
    }
}

dependencies {
    // 1. Encrypted Storage
    implementation("net.zetetic:android-database-sqlcipher:4.5.4")
    implementation("androidx.sqlite:sqlite-ktx:2.4.0")

    // 2. Local Model Inference
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.17.0")

    // 3. Android Jetpack & Background Tasks
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.0")
    
    // 4. On-Device LLM (MediaPipe)
    implementation("com.google.mediapipe:tasks-genai:0.10.14")
}
