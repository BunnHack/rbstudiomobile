import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.secrets)
  alias(libs.plugins.google.services)
}

android {
  namespace = "com.example"
  compileSdk = 36

  defaultConfig {
    applicationId = "com.example"
    minSdk = 28
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  buildTypes {
    debug {
      // Use AGP's built-in debug signing so local and CI builds do not require a checked-in keystore.
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  kotlin {
    jvmToolchain(17)
  }

  // SceneView is compiled with Kotlin 2.4 while this module compiles with Kotlin 2.2 —
  // allow the newer library metadata.
  kotlin.compilerOptions {
    freeCompilerArgs.add("-Xskip-metadata-version-check")
  }


  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
  packaging {
    resources.excludes += setOf(
      "META-INF/INDEX.LIST",
      "META-INF/DEPENDENCIES",
      "META-INF/*.kotlin_module"
    )
  }
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
}

googleServices {
  missingGoogleServicesStrategy = MissingGoogleServicesStrategy.WARN
}

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(platform(libs.firebase.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.converter.moshi)
  implementation(libs.firebase.ai)
  implementation(libs.firebase.appcheck.recaptcha)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  implementation(libs.retrofit)

  // SceneView (Filament) 3D viewport — real GPU 3D via OpenGL ES / Vulkan
  implementation(libs.sceneview)
  implementation(libs.androidsvg)
  // Roblox .rbxm/.rbxl binary format decompression
  implementation(libs.zstd.jni)
  implementation(libs.lz4.java)
  // Lucide icon collection for Compose
  implementation(libs.lucide.icons)

  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  testImplementation(libs.junit)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  "ksp"(libs.androidx.room.compiler)
  "ksp"(libs.moshi.kotlin.codegen)
}

tasks.register<Copy>("copyApkToBuildOutput") {
  from(layout.buildDirectory.dir("outputs/apk/debug")) {
    include("app-debug.apk")
  }
  into(rootProject.file("build_output"))
}

tasks.configureEach {
  if (name == "assembleDebug" || name == "packageDebug") {
    finalizedBy("copyApkToBuildOutput")
  }
}
