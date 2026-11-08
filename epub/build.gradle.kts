import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Plain JVM library that wraps epub4j (the maintained fork of epublib) and turns
// canvas content into an EPUB file. Being pure JVM, it is tested without Android.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("com.gradleup.shadow")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

val epubcheck: Configuration by configurations.creating

dependencies {
    implementation("io.documentnode:epub4j-core:4.2.3")

    testImplementation(kotlin("test"))
    testImplementation("org.w3c:epubcheck:5.3.0")
    epubcheck("org.w3c:epubcheck:5.3.0")
}

tasks.test {
    useJUnitPlatform()
    systemProperty("epub.outputDir", layout.buildDirectory.dir("test-epubs").get().asFile.path)
}

// The Android app consumes this jar instead of the plain dependencies.
// epub4j constructs org.kxml2.io.KXmlSerializer directly. Android ships a hidden
// copy of that class in the OS, which would shadow ours and is off-limits to apps,
// so kxml2 is bundled under our own package name. The org.xmlpull.v1 interfaces
// are public Android API, so they are left out and the OS copy is used.
tasks.shadowJar {
    // Distinct name, so the plain jar (used by this module's tests) never overwrites it.
    archiveClassifier.set("android")
    dependencies {
        include(dependency("io.documentnode:epub4j-core"))
        include(dependency("io.documentnode:minilog"))
        include(dependency("net.sf.kxml:kxml2"))
        include(dependency("net.lingala.zip4j:zip4j"))
    }
    relocate("org.kxml2", "io.github.ddastoor.kindlify.shaded.kxml2")
    exclude("org/xmlpull/**")
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/maven/**")
}

// Validates any EPUB with the official W3C checker:
//   ./gradlew :epub:epubcheck -Pfile=/path/to/book.epub
tasks.register<JavaExec>("epubcheck") {
    group = "verification"
    description = "Runs W3C epubcheck on the EPUB given with -Pfile=..."
    classpath = epubcheck
    mainClass.set("com.adobe.epubcheck.tool.Checker")
    args(providers.gradleProperty("file").orElse("missing.epub").get())
}
