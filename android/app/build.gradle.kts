plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.pathpilot"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.pathpilot"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 백엔드 주소. 코드를 고치지 않고 바꿀 수 있도록 gradle 속성으로 뺀다 — 시연 직전에
        // adb reverse가 끊기거나 Wi-Fi 주소로 옮겨야 할 때 RetrofitClient.kt를 편집하고
        // 커밋할 필요가 없다.
        //   ./gradlew installDebug -PbackendBaseUrl=http://10.28.85.74:8000/
        // 기본값은 `adb reverse tcp:8000 tcp:8000`을 전제로 한 로컬 터널이다.
        val backendBaseUrl = (project.findProperty("backendBaseUrl") as String?)
            ?: "http://127.0.0.1:8000/"
        buildConfigField("String", "BACKEND_BASE_URL", "\"$backendBaseUrl\"")
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        // wakeup/WakeTriggerReceiver가 릴리스 빌드에서 스스로 무력화되도록 BuildConfig.DEBUG를 쓴다.
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    // 네트워킹 (멤버 A: network/ 전용)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}