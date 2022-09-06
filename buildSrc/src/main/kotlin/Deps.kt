object Deps {
    object Airbnb {
        const val lottieCompose = "com.airbnb.android:lottie-compose:${Version.lottie}"
    }

    object Android {
        const val material = "com.google.android.material:material:${Version.material}"
    }

    object AndroidX {
        const val lifecycleRuntimeKtx = "androidx.lifecycle:lifecycle-runtime-ktx:${Version.lifecycleRuntimeKtx}"
        const val lifecycleViewmodelKtx = "androidx.lifecycle:lifecycle-viewmodel-ktx:${Version.lifecycleViewmodelKtx}"
        const val activityCompose = "androidx.activity:activity-compose:${Version.activityCompose}"
        const val appCompat = "androidx.appcompat:appcompat:1.4.1"
        const val coreKtx = "androidx.core:core-ktx:1.7.0"
    }

    object Cash {
        object Zipline {
            const val zipline = "app.cash.zipline:zipline:${Version.zipline}"
            const val ziplineSnapshot = "app.cash.zipline:zipline:${Version.ziplineSnapshot}"
            const val ziplineLoader = "app.cash.zipline:zipline:${Version.zipline}"
            const val pluginSnapshot = "app.cash.zipline:zipline-kotlin-plugin:${Version.ziplineSnapshot}"
            const val plugin = "app.cash.zipline:zipline-kotlin-plugin:${Version.zipline}"
            const val ziplineGradlePlugin = "app.cash.zipline:zipline-gradle-plugin:${Version.zipline}"
        }

        const val okhttp = "com.squareup.okhttp3:okhttp:4.9.2"

        object SqlDelight {
            const val gradlePlugin = "com.squareup.sqldelight:gradle-plugin:${Version.sqlDelight}"
            const val driverAndroid = "com.squareup.sqldelight:android-driver:${Version.sqlDelight}"
            const val driverNative = "com.squareup.sqldelight:native-driver:${Version.sqlDelight}"
            const val driverSqlite = "com.squareup.sqldelight:sqlite-driver:${Version.sqlDelight}"
            const val runtime = "com.squareup.sqldelight:runtime:${Version.sqlDelight}"
            const val coroutineExtensions = "com.squareup.sqldelight:coroutines-extensions:${Version.sqlDelight}"
            const val driverNativeMacOS = "com.squareup.sqldelight:native-driver-macosx64:${Version.sqlDelight}"
        }
    }

    object Compose {
        const val compiler = "androidx.compose.compiler:compiler:${Version.composeCompiler}"
        const val runtime = "androidx.compose.runtime:runtime:${Version.compose}"
        const val ui = "androidx.compose.ui:ui:${Version.composeUi}"
        const val uiGraphics = "androidx.compose.ui:ui-graphics:${Version.composeUi}"
        const val uiTooling = "androidx.compose.ui:ui-tooling:${Version.composeUi}"
        const val foundationLayout = "androidx.compose.foundation:foundation-layout:${Version.composeUi}"
        const val material = "androidx.compose.material:material:${Version.composeMaterial}"
        const val navigation = "androidx.navigation:navigation-compose:${Version.navCompose}"

        const val coilCompose = "io.coil-kt:coil-compose:1.3.2"
        const val accompanistNavigationAnimation =
            "com.google.accompanist:accompanist-navigation-animation:${Version.accompanist}"
    }

    object Dropbox {
        const val store = "com.dropbox.mobile.store:store4:4.0.5"
    }

    object Koin {
        const val core = "io.insert-koin:koin-core:${Version.koin}"
        const val android = "io.insert-koin:koin-android:${Version.koin}"
        const val compose = "io.insert-koin:koin-androidx-compose:${Version.koin}"
        const val ktor = "io.insert-koin:koin-ktor:${Version.koin}"
    }

    object Kotlin {
        const val gradlePlugin = "org.jetbrains.kotlin:kotlin-gradle-plugin:${Version.baseKotlin}"
        const val serializationCore = "org.jetbrains.kotlin:kotlin-serialization:${Version.baseKotlin}"
        const val reflect = "org.jetbrains.kotlin:kotlin-reflect:${Version.baseKotlin}"
    }


    object Test {
        const val junit = "junit:junit:${Version.junit}"
        const val core = "androidx.test:core:${Version.testCore}"
    }

    object Kotlinx {
        const val serializationCore = "org.jetbrains.kotlinx:kotlinx-serialization-core:${Version.kotlinxSerialization}"
        const val serializationJson = "org.jetbrains.kotlinx:kotlinx-serialization-json:${Version.kotlinxSerialization}"
        const val coroutinesAndroid = "org.jetbrains.kotlinx:kotlinx-coroutines-android:${Version.kotlinxCoroutines}"
        const val coroutinesCore = "org.jetbrains.kotlinx:kotlinx-coroutines-core:${Version.kotlinxCoroutines}"
        const val coroutinesTest = "org.jetbrains.kotlinx:kotlinx-coroutines-test:${Version.kotlinxCoroutines}"
        const val dateTime = "org.jetbrains.kotlinx:kotlinx-datetime:0.4.0"
    }

    object Ktor {
        const val serverCore = "io.ktor:ktor-server-core:${Version.ktor}"
        const val serverNetty = "io.ktor:ktor-server-netty:${Version.ktor}"
        const val serverCors = "io.ktor:ktor-server-cors:${Version.ktor}"
        const val contentNegotiation = "io.ktor:ktor-client-content-negotiation:${Version.ktor}"
        const val json = "io.ktor:ktor-serialization-kotlinx-json:${Version.ktor}"

        const val serverContentNegotiation = "io.ktor:ktor-server-content-negotiation:${Version.ktor}"

        const val clientCore = "io.ktor:ktor-client-core:${Version.ktor}"
        const val clientJson = "io.ktor:ktor-client-json:${Version.ktor}"
        const val clientLogging = "io.ktor:ktor-client-logging:${Version.ktor}"
        const val clientSerialization = "io.ktor:ktor-client-serialization:${Version.ktor}"
        const val kotlinxJson = "io.ktor:ktor-serialization-kotlinx-json:${Version.ktor}"
        const val clientAndroid = "io.ktor:ktor-client-android:${Version.ktor}"
        const val clientJava = "io.ktor:ktor-client-java:${Version.ktor}"
        const val clientDarwin = "io.ktor:ktor-client-darwin:${Version.ktor}"
        const val clientJs = "io.ktor:ktor-client-js:${Version.ktor}"
    }

    object Mavericks {
        const val mavericksCompose = "com.airbnb.android:mavericks-compose:${Version.mavericksCompose}"
    }

    object Stately {
        const val common = "co.touchlab:stately-common:${Version.stately}"
        const val concurrency = "co.touchlab:stately-concurrency:${Version.stately}"
        const val isolate = "co.touchlab:stately-isolate:${Version.stately}"
        const val collections = "co.touchlab:stately-iso-collections:${Version.stately}"
    }
}