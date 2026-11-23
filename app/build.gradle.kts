plugins {
    alias(libs.plugins.convention.android.application)
    alias(libs.plugins.convention.android.application.compose)
}

android {
    namespace = "com.mg.vpnpractice"

    defaultConfig {
        applicationId = "com.mg.vpnpractice"
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // 서명 정보는 코드/저장소에 두지 않고 환경변수로만 받는다 (CI에서는 GitHub Secrets가 환경변수로 주입됨).
    // 환경변수가 없으면 서명 없이 빌드되므로 로컬/CI의 assembleDebug, lint는 영향 받지 않는다.
    val envKeystorePath = System.getenv("KEYSTORE_PATH")
    val envKeystorePassword = System.getenv("KEYSTORE_PASSWORD")
    val envKeyAlias = System.getenv("KEY_ALIAS")
    val envKeyPassword = System.getenv("KEY_PASSWORD")
    val canSign = listOf(envKeystorePath, envKeystorePassword, envKeyAlias, envKeyPassword)
        .all { !it.isNullOrEmpty() }

    signingConfigs {
        if (canSign) {
            create("release") {
                storeFile = file(envKeystorePath!!)
                storePassword = envKeystorePassword
                keyAlias = envKeyAlias
                keyPassword = envKeyPassword
            }
        }
    }

    buildTypes {
        release {
            if (canSign) {
                signingConfig = signingConfigs.getByName("release")
            }
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}