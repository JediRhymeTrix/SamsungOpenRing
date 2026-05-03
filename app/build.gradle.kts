import com.android.build.api.variant.BuiltArtifactsLoader
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.isFile) {
        keystorePropertiesFile.inputStream().use(::load)
    }
}

fun stringProperty(name: String, defaultValue: String): String {
    return providers.gradleProperty(name).orElse(defaultValue).get()
}

fun intProperty(name: String, defaultValue: Int): Int {
    return stringProperty(name, defaultValue.toString()).toInt()
}

android {
    namespace = "io.github.thevellichor.samsungopenring.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.thevellichor.samsungopenring.app"
        minSdk = 31
        targetSdk = 34
        versionCode = intProperty("VERSION_CODE", 1)
        versionName = stringProperty("VERSION_NAME", "0.1.0")
    }

    signingConfigs {
        create("openRing") {
            val storeFilePath = keystoreProperties.getProperty("storeFile")
            if (!storeFilePath.isNullOrBlank()) {
                storeFile = rootProject.file(storeFilePath)
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            if (keystorePropertiesFile.isFile) {
                signingConfig = signingConfigs.getByName("openRing")
            }
        }

        release {
            if (keystorePropertiesFile.isFile) {
                signingConfig = signingConfigs.getByName("openRing")
            }
        }
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.constraintlayout)
    implementation(libs.okhttp)
    implementation(libs.gson)
    implementation(libs.coroutines.android)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
}

androidComponents {
    onVariants { variant ->
        val capitalizedVariantName = variant.name.replaceFirstChar { it.uppercase() }
        val versionName = variant.outputs.single().versionName.get()
        val versionCode = variant.outputs.single().versionCode.get()
        val outputDirectory = layout.buildDirectory.dir("outputs/apk/${variant.name}")

        tasks.register("rename${capitalizedVariantName}Apk") {
            val builtArtifactsLoader: BuiltArtifactsLoader = variant.artifacts.getBuiltArtifactsLoader()
            dependsOn("assemble$capitalizedVariantName")
            doLast {
                val builtArtifacts = builtArtifactsLoader.load(outputDirectory.get()) ?: return@doLast
                builtArtifacts.elements.forEach { artifact ->
                    val outputFile = file(artifact.outputFile)
                    val normalizedVariant = variant.name.lowercase()
                    outputFile.copyTo(
                        outputFile.parentFile.resolve(
                            "SamsungOpenRing-${versionName}-${versionCode}-${normalizedVariant}.apk"
                        ),
                        overwrite = true
                    )
                }
            }
        }
    }
}
