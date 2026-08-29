plugins {
    kotlin("jvm")
    application
}

dependencies {
    implementation(project(":roles"))
}

application {
    mainClass = "org.hpb.headless.WitnessDaemonKt"
}

// The serverless round-trip as a runnable program (signet by default):
//   HPB_RELAYS=ws://... gradle :headless:demo     — see docs/runbook.md
tasks.register<JavaExec>("demo") {
    group = "application"
    description = "Run the serverless job round-trip demo (HPB_NETWORK=SIGNET default)"
    mainClass = "org.hpb.headless.DemoKt"
    classpath = sourceSets["main"].runtimeClasspath
}
