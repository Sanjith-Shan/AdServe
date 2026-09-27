plugins {
    `java-library`
    id("me.champeau.jmh") version "0.7.3"
}

dependencies {
    api(project(":api"))
    api(libs.hdr)
    api(libs.jackson.databind)
    api("com.netflix.hollow:hollow:7.15.2")
    implementation(libs.slf4j.api)

    testImplementation(platform(libs.junit.bom))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(libs.jqwik)
    testImplementation(libs.assertj)
}

jmh {
    warmupIterations.set(3)
    iterations.set(5)
    fork.set(1)
    timeUnit.set("ns")
    benchmarkMode.set(listOf("avgt"))
    resultFormat.set("JSON")
}
