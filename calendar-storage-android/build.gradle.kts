plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.example.aiassistent1.calendar.storage.android"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 30
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Room migration and persistence checks run locally, without a device.
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

val calendarSchemasPath = layout.projectDirectory.dir("schemas").asFile.absolutePath
tasks.withType<Test>().configureEach {
    systemProperty("calendar.schemas", calendarSchemasPath)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":calendar-core"))
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation("org.robolectric:robolectric:4.16")

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
