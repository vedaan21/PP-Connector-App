plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.gnssbridge"

    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.gnssbridge"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner =
            "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }

    /*
     * Mock location is intentionally used by
     * Airtel PP Connector.
     *
     * Android Lint normally treats the
     * ACCESS_MOCK_LOCATION permission as a
     * fatal MockLocation issue in release builds.
     *
     * We suppress ONLY that specific check.
     * Other release lint checks remain active.
     */
    lint {
        disable += "MockLocation"
    }

    compileOptions {
        sourceCompatibility =
            JavaVersion.VERSION_11

        targetCompatibility =
            JavaVersion.VERSION_11
    }
}

dependencies {

    implementation(
        "com.github.mik3y:usb-serial-for-android:3.11.0"
    )

    implementation(
        "com.squareup.okhttp3:okhttp:4.12.0"
    )

    implementation(
        "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2"
    )

    implementation(
        libs.androidx.activity.ktx
    )

    implementation(
        libs.androidx.appcompat
    )

    implementation(
        libs.androidx.constraintlayout
    )

    implementation(
        libs.androidx.core.ktx
    )

    implementation(
        libs.material
    )

    testImplementation(
        libs.junit
    )

    androidTestImplementation(
        libs.androidx.espresso.core
    )

    androidTestImplementation(
        libs.androidx.junit
    )
}