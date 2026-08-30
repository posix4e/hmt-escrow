plugins {
    kotlin("jvm")
    application
}

dependencies {
    implementation(project(":androidcore"))
    implementation(project(":protocol"))
    implementation(project(":engine"))
}

application {
    mainClass = "org.hpb.labeler.LabelerAppKt"
}
