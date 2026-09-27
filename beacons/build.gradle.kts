plugins {
    `java-library`
    application
}

dependencies {
    api(project(":core"))
    api(libs.lettuce)
    implementation(libs.kafka.clients)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.slf4j.simple)

    testImplementation(platform(libs.junit.bom))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(libs.assertj)
    testImplementation(platform(libs.tc.bom))
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers")
}

application {
    mainClass.set("adserve.beacons.BeaconConsumer")
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}
