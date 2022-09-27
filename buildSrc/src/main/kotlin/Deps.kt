object Deps {

    object Androidx {
        const val lifecycleRuntimeKtx = "androidx.lifecycle:lifecycle-runtime-ktx:${Version.lifecycleRuntimeKtx}"
        const val lifecycleViewmodelKtx = "androidx.lifecycle:lifecycle-viewmodel-ktx:${Version.lifecycleViewmodelKtx}"
        const val activityCompose = "androidx.activity:activity-compose:${Version.activityCompose}"
        const val appCompat = "androidx.appcompat:appcompat:1.4.1"
        const val coreKtx = "androidx.core:core-ktx:1.7.0"
        const val navigationCompose = "androidx.navigation:navigation-compose:${Version.navigationCompose}"
    }

    object Compose {
        const val compiler = "androidx.compose.compiler:compiler:${Version.composeCompiler}"
        const val runtime = "androidx.compose.runtime:runtime:${Version.composeAndroidX}"
        const val ui = "androidx.compose.ui:ui:${Version.composeAndroidX}"
        const val uiGraphics = "androidx.compose.ui:ui-graphics:${Version.composeAndroidX}"
        const val uiTooling = "androidx.compose.ui:ui-tooling:${Version.composeAndroidX}"
        const val foundationLayout = "androidx.compose.foundation:foundation-layout:${Version.composeAndroidX}"
        const val material = "androidx.compose.material:material:${Version.composeAndroidX}"
    }

    object Dagger {
        const val dagger = "com.google.dagger:dagger:${Version.dagger}"
        const val daggerCompiler = "com.google.dagger:dagger-compiler:${Version.dagger}"
    }

    object Kotlin {
        const val gradlePlugin = "org.jetbrains.kotlin:kotlin-gradle-plugin:${Version.baseKotlin}"
    }

    object Kotlinx {
        const val serializationCore = "org.jetbrains.kotlinx:kotlinx-serialization-core:${Version.kotlinxSerialization}"
        const val serializationJson = "org.jetbrains.kotlinx:kotlinx-serialization-json:${Version.kotlinxSerialization}"
        const val coroutinesAndroid = "org.jetbrains.kotlinx:kotlinx-coroutines-android:${Version.kotlinxCoroutines}"
        const val coroutinesCore = "org.jetbrains.kotlinx:kotlinx-coroutines-core:${Version.kotlinxCoroutines}"
        const val dateTime = "org.jetbrains.kotlinx:kotlinx-datetime:0.4.0"
    }

    object Test {
        const val core = "androidx.test:core:${Version.testCore}"
        const val coroutinesTest = "org.jetbrains.kotlinx:kotlinx-coroutines-test:${Version.kotlinxCoroutines}"
        const val junit = "junit:junit:${Version.junit}"
    }
}