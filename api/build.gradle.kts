import com.google.protobuf.gradle.id

plugins {
    `java-library`
    id("com.google.protobuf") version "0.9.5"
}

// The contract lives in contract/ads.proto at the repo root; this module only compiles it.
sourceSets {
    main {
        proto {
            setSrcDirs(listOf("../contract"))
            include("*.proto")
        }
    }
}

dependencies {
    api(libs.protobuf.java)
    api(libs.grpc.protobuf)
    api(libs.grpc.stub)
    compileOnly(libs.javax.annotation)
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}" }
    plugins {
        id("grpc") { artifact = "io.grpc:protoc-gen-grpc-java:${libs.versions.grpc.get()}" }
    }
    generateProtoTasks {
        all().forEach { it.plugins { id("grpc") } }
    }
}
