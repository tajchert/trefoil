plugins { alias(libs.plugins.kotlin.jvm) }
kotlin { jvmToolchain(21) }
dependencies {
    api(project(":core:model"))
    implementation(libs.coroutines.core)
    implementation("com.hierynomus:sshj:0.41.2")
    implementation("org.bouncycastle:bcprov-jdk18on:1.84")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.84")
    implementation(libs.serialization.json)
    testImplementation(project(":core:session"))
    testImplementation(libs.junit)
    testImplementation("org.apache.sshd:sshd-core:2.20.1")
    testImplementation(libs.mockwebserver)
}

// Explicit opt-in disposable SSH/T3 fixture. Never part of APK or normal tests.
tasks.register<JavaExec>("runEmulatorFixture") {
    group = "verification"
    description = "Run isolated loopback SSH/T3 services with public test credentials user/pw"
    dependsOn(tasks.testClasses)
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.tajchert.trefoil.core.ssh.EmulatorFixture")
}
