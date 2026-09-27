plugins {
    application
}

val flink = "2.2.1"

dependencies {
    implementation(project(":core"))
    implementation("org.apache.flink:flink-streaming-java:$flink")
    implementation("org.apache.flink:flink-clients:$flink")
    implementation("org.apache.flink:flink-connector-kafka:5.0.0-2.2")
    implementation(libs.postgres)
    runtimeOnly("org.apache.logging.log4j:log4j-slf4j2-impl:2.24.3")
    runtimeOnly("org.apache.logging.log4j:log4j-core:2.24.3")

    testImplementation(platform(libs.junit.bom))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(libs.assertj)
    testImplementation("org.apache.flink:flink-test-utils:$flink")
}

application {
    mainClass.set("adserve.billing.BillingJob")
    applicationDefaultJvmArgs = listOf("-Xmx1g",
        "--add-opens=java.base/java.util=ALL-UNNAMED", "--add-opens=java.base/java.lang=ALL-UNNAMED")
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}

tasks.withType<Test>().configureEach {
    jvmArgs("--add-opens=java.base/java.util=ALL-UNNAMED", "--add-opens=java.base/java.lang=ALL-UNNAMED")
}
