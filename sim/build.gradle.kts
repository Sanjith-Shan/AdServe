plugins {
    application
}

dependencies {
    implementation(project(":core"))
    implementation(project(":beacons"))
    implementation(libs.commons.compress)
    implementation(libs.grpc.netty)
    implementation(libs.kafka.clients)
    implementation(libs.lettuce)
    implementation(libs.postgres)
    implementation(libs.slf4j.simple)

    testImplementation(platform(libs.junit.bom))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(libs.assertj)
}

application {
    mainClass.set("adserve.sim.Main")
    applicationDefaultJvmArgs = listOf("-Xmx4g", "-XX:+UseZGC", "-XX:+ZGenerational")
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}
