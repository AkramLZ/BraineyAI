plugins {
    id("java")
    id("com.github.johnrengelman.shadow") version "7.1.2"
}

group = "dev.akraml"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies {
//    implementation("org.apache.thrift:libthrift:0.18.1")
//    implementation("com.theokanning.openai-gpt3-java:service:0.16.0")
    implementation("org.projectlombok:lombok:1.18.24")
//    implementation("com.github.ErrorxCode:EasyInsta:2.9.2")
//    implementation("com.github.ErrorxCode:AsyncTask:1.0")
    implementation("com.github.instagram4j:instagram4j:2.0.7")
    implementation(files("libs/instagram4j-realtime.jar"))
    annotationProcessor("org.projectlombok:lombok:1.18.24")
    // https://mvnrepository.com/artifact/com.google.code.gson/gson
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("com.azure:azure-ai-openai:1.0.0-beta.11")
	implementation("io.reactivex.rxjava3:rxjava:3.1.9")
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "dev.akraml.brainey.Main"
    }
}

tasks.compileJava {
    options.encoding = "UTF-8"
    sourceCompatibility = "17"
    targetCompatibility = "17"
}