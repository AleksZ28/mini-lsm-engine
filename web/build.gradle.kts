plugins {
    id("application")
}

group = "com.ibm.internship.lsm"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    implementation(project(":engine"))
    implementation("io.javalin:javalin:7.2.4")
    implementation("org.slf4j:slf4j-simple:2.0.20")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.3")
    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("com.ibm.internship.lsm.web.Main")
}

tasks.test {
    useJUnitPlatform()
}