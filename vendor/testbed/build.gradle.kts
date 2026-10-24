plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(project(":protocol-extension-api"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.core)
}
