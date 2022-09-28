object Deps {

    object Androidx {
        const val activityCompose = "androidx.activity:activity-compose:${Version.activityCompose}"
    }

    object Compose {
        const val compiler = "androidx.compose.compiler:compiler:${Version.composeCompiler}"
        const val runtime = "androidx.compose.runtime:runtime:${Version.composeAndroidX}"
        const val ui = "androidx.compose.ui:ui:${Version.composeAndroidX}"
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