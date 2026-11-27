plugins {
    alias(libs.plugins.convention.jvm.library)
    application
}

application {
    mainClass.set("com.mg.vpnpractice.server.MainKt")
}

dependencies {
    implementation(project(":tunnel-protocol"))
    implementation(libs.jna)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlinx.coroutines.test)
}
