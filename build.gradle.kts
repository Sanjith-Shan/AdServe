plugins {
    java
}

allprojects {
    group = "adserve"
    version = "0.1.0"
    repositories { mavenCentral() }
}

subprojects {
    apply(plugin = "java")

    extensions.configure<JavaPluginExtension> {
        toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-parameters", "-Xlint:unchecked"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform {
            // Integration tests need Docker; run them with -Pintegration or ./gradlew integrationTest.
            if (!project.hasProperty("integration")) excludeTags("integration")
        }
        maxHeapSize = "1g"
        testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
    }
}
