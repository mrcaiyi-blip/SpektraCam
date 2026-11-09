plugins { id("com.android.application") }

android {
    namespace = "org.spektracam"
    compileSdk = 35
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "org.spektracam"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "org.spektracam.FilmSmokeInstrumentation"
        ndk { abiFilters.add("arm64-v8a") }
        externalNativeBuild.cmake {
            arguments += listOf("-DANDROID_STL=c++_static", "-DBUILD_TESTING=OFF")
        }
    }

    externalNativeBuild.cmake {
        path = file("src/main/cpp/CMakeLists.txt")
        version = "3.22.1"
    }
    sourceSets.getByName("main").assets.srcDirs(
        rootProject.file("assets"),
        rootProject.file("licenses"),
    )
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Keep both translations available when switching language inside the app.
    bundle {
        language { enableSplit = false }
    }
}
