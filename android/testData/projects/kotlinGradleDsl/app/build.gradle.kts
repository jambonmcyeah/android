import com.android.build.gradle.AppPlugin
import com.android.build.gradle.AppExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinAndroidPluginWrapper


apply<AppPlugin>()
apply<KotlinAndroidPluginWrapper>()

configure<AppExtension> {
    buildToolsVersion("27.0.1")
    compileSdkVersion(27)

    defaultConfig {
        minSdkVersion(15)
        targetSdkVersion(27)

        applicationId = "com.example.kotlingradle"
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles("proguard-rules.pro")
        }
    }
}

dependencies {
    "implementation"("com.android.support:appcompat-v7:27.1.1")
    "implementation"("com.android.support.constraint:constraint-layout:1.0.2")
    "implementation"(kotlin("stdlib", "1.3.11"))
    "implementation"(project(":lib"))
}

