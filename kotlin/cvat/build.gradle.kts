plugins {
    kotlin("jvm")
    application
}

dependencies {
    implementation(project(":roles"))
    implementation(project(":headless"))
}

application {
    mainClass = "org.hpb.cvat.CvatBridgeKt"
}

// A fake CVAT to try the bridge against without a deployment:
//   gradle :cvat:mock   then   CVAT_URL=http://127.0.0.1:7688 gradle :cvat:run
tasks.register<JavaExec>("mock") {
    group = "application"
    description = "Serve the built-in mock CVAT (task 1 'animals', labels cat/dog)"
    mainClass = "org.hpb.cvat.MockCvatKt"
    classpath = sourceSets["main"].runtimeClasspath
}
