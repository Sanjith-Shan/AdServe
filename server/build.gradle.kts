plugins {
    java
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"
}

extra["testcontainers.version"] = libs.versions.testcontainers.get()

dependencies {
    implementation(project(":core"))
    implementation(project(":beacons"))
    implementation(platform("com.netflix.graphql.dgs:graphql-dgs-platform-dependencies:${libs.versions.dgs.get()}"))
    implementation("com.netflix.graphql.dgs:graphql-dgs-spring-graphql-starter")
    implementation("com.netflix.graphql.dgs:graphql-dgs-extended-scalars")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("io.lettuce:lettuce-core")
    implementation("org.apache.kafka:kafka-clients")
    implementation(libs.grpc.netty)
    implementation(libs.grpc.services)
    implementation(libs.protobuf.util)
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("com.netflix.graphql.dgs:graphql-dgs-spring-graphql-starter-test")
    testImplementation(platform(libs.tc.bom))
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-kafka")
    testImplementation(libs.grpc.inprocess)
    testImplementation(libs.jqwik)
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    workingDir = rootProject.projectDir
    jvmArgs("-XX:+UseZGC", "-XX:+ZGenerational", "-Xms2g", "-Xmx2g")
}

configurations.all {
    // The beacon consumer's standalone logger must not compete with Spring Boot's Logback.
    exclude(group = "org.slf4j", module = "slf4j-simple")
}
