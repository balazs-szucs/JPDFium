allprojects {
    group = "com.stirling"
    version = findProperty("jpdfium.version")?.toString() ?: "0.0.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

// Task to build the real native bridge (libjpdfium.so with real PDFium)
tasks.register<Exec>("buildRealBridge") {
    group = "build"
    description = "Build the real native bridge with PDFium (run native/setup-pdfium.sh first)"

    val scriptPath = rootProject.file("native/build-real.sh")
    if (!scriptPath.exists()) {
        throw GradleException("build-real.sh not found at ${scriptPath.absolutePath}")
    }

    commandLine("bash", scriptPath.absolutePath)
}

// Task to build the stub native bridge (for unit tests without PDFium)
tasks.register<Exec>("buildStubBridge") {
    group = "build"
    description = "Build the stub native bridge (no PDFium required, for unit tests only)"

    val scriptPath = rootProject.file("native/build-stub.sh")
    if (!scriptPath.exists()) {
        throw GradleException("build-stub.sh not found at ${scriptPath.absolutePath}")
    }

    commandLine("bash", scriptPath.absolutePath)
}

// Task to build PDFium from EmbedPDF fork source
tasks.register<Exec>("buildPdfium") {
    group = "build"
    description = "Build PDFium from EmbedPDF fork source (required before building real bridge)"

    val scriptPath = rootProject.file("native/setup-pdfium.sh")
    if (!scriptPath.exists()) {
        throw GradleException("setup-pdfium.sh not found at ${scriptPath.absolutePath}")
    }

    commandLine("bash", scriptPath.absolutePath)
}

// Task to run all samples (full application test)
tasks.register<JavaExec>("runAllSamples") {
    group = "application"
    description = "Run all samples to test the complete application"

    dependsOn(":jpdfium:compileTestJava", ":jpdfium:processTestResources")

    mainClass.set("stirling.software.jpdfium.samples.RunAllSamples")
    val jpdfiumCompileJava = tasks.getByPath(":jpdfium:compileJava") as JavaCompile
    val jpdfiumCompileTest = tasks.getByPath(":jpdfium:compileTestJava") as JavaCompile
    classpath = project(":jpdfium").configurations.getByName("testRuntimeClasspath") +
                files(jpdfiumCompileJava.outputs.files, jpdfiumCompileTest.outputs.files) +
                files(project(":jpdfium").layout.buildDirectory.dir("resources/test"))
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    maxHeapSize = findProperty("samples.xmx")?.toString() ?: "2g"
    workingDir = rootProject.projectDir
    // Forward -Dsamples.* (e.g. -Dsamples.selfClean=true) from the Gradle
    // invocation to the sample JVM. JavaExec does not inherit them otherwise.
    for ((k, v) in System.getProperties()) {
        val key = k.toString()
        if (key.startsWith("samples.")) systemProperty(key, v.toString())
    }
}

// Task to run a specific sample by number (e.g., ./gradlew runSample -Psample=01)
tasks.register<JavaExec>("runSample") {
    group = "application"
    description = "Run a specific sample by number (e.g., ./gradlew runSample -Psample=01)"

    dependsOn(":jpdfium:compileTestJava", ":jpdfium:processTestResources")

    val sampleNum = project.findProperty("sample")?.toString()?.padStart(2, '0') ?: "01"

    // Map sample numbers to class names
    val sampleClasses = mapOf(
        "01" to "S01_Document",
        "02" to "S02_Render",
        "03" to "S03_Text",
        "04" to "S04_Images",
        "05" to "S05_Redact",
        "06" to "S06_Forms",
        "07" to "S07_MergeSplit",
        "08" to "S08_Compress",
        "09" to "S09_Security",
        "10" to "S10_Pipeline"
    )

    // The map is hand-maintained, so an unknown number fails here with the list
    // of known samples and a drifted name fails with the missing file path,
    // instead of surfacing later as a ClassNotFoundException.
    val sampleName = sampleClasses[sampleNum]
        ?: throw GradleException(
            "Unknown sample '$sampleNum'. Known numbers are ${sampleClasses.keys.joinToString(", ")}"
        )
    val sampleSource = project(":jpdfium")
        .file("src/test/java/stirling/software/jpdfium/samples/$sampleName.java")
    if (!sampleSource.isFile) {
        throw GradleException(
            "Sample '$sampleName' maps to ${rootProject.relativePath(sampleSource)}, which does not exist"
        )
    }

    mainClass.set("stirling.software.jpdfium.samples.$sampleName")
    val jpdfiumCompileJava = tasks.getByPath(":jpdfium:compileJava") as JavaCompile
    val jpdfiumCompileTest = tasks.getByPath(":jpdfium:compileTestJava") as JavaCompile
    classpath = project(":jpdfium").configurations.getByName("testRuntimeClasspath") +
                files(jpdfiumCompileJava.outputs.files, jpdfiumCompileTest.outputs.files) +
                files(project(":jpdfium").layout.buildDirectory.dir("resources/test"))
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    workingDir = rootProject.projectDir

    doFirst {
        println("Running sample: $sampleName")
    }
}

// Full end-to-end build and test task
tasks.register("fullBuildAndTest") {
    group = "build"
    description = "Full end-to-end: build PDFium, build real bridge, run unit tests, integration tests, and all samples"

    dependsOn(
        "buildPdfium",
        "buildRealBridge",
        ":jpdfium:test",
        ":jpdfium:integrationTest",
        "runAllSamples"
    )

    doLast {
        println("")
        println("========================================")
        println("  FULL BUILD AND TEST COMPLETED")
        println("========================================")
        println("  - PDFium downloaded")
        println("  - Real native bridge built")
        println("  - Unit tests passed")
        println("  - Integration tests passed")
        println("  - All samples executed")
        println("  - Output: samples-output/")
        println("========================================")
    }
}

// Quick try-out task (build stub + run samples for quick testing)
tasks.register("quickTry") {
    group = "application"
    description = "Quick try-out: build stub bridge and run all samples (no PDFium required)"

    dependsOn(
        "buildStubBridge",
        ":jpdfium:test",
        "runAllSamples"
    )

    doLast {
        println("")
        println("========================================")
        println("  QUICK TRY COMPLETED")
        println("========================================")
        println("  - Stub bridge built (no PDFium)")
        println("  - Unit tests passed")
        println("  - Samples executed (stub mode)")
        println("  Note: Some features require real PDFium")
        println("  For full testing, run: ./gradlew fullBuildAndTest")
        println("========================================")
    }
}

// Maven Central Publishing (Central Portal via OSSRH Staging API)
//
// After running:  ./gradlew publishAllPublicationsToCentralPortalRepository
// the artifacts sit in the OSSRH staging area.  You must then POST to finalize
// the deployment so it appears in the Central Portal for review / auto-release.
//
// Usage:
//   ./gradlew publishAllToCentralPortal            # publish + finalize (user_managed)
//   ./gradlew publishAllToCentralPortal -PautoRelease=true  # publish + auto-release
//
// Prerequisites (set in ~/.gradle/gradle.properties or env):
//   centralPortalUsername / CENTRAL_PORTAL_USERNAME
//   centralPortalPassword / CENTRAL_PORTAL_PASSWORD
//   signing.key / GPG_SIGNING_KEY
//   signing.password / GPG_SIGNING_PASSWORD

tasks.register("finalizePortalDeployment") {
    group = "publishing"
    description = "POST to the OSSRH Staging API to push the deployment to the Central Portal"

    doLast {
        val namespace = project.group.toString()  // e.g. "com.stirling"
        val autoRelease = (findProperty("autoRelease")?.toString() ?: "false").toBoolean()
        val publishingType = if (autoRelease) "automatic" else "user_managed"
        val url = "https://ossrh-staging-api.central.sonatype.com/manual/upload/defaultRepository/$namespace?publishing_type=$publishingType"

        val tokenUser = findProperty("centralPortalUsername")?.toString()
            ?: findProperty("ossrhUsername")?.toString()
            ?: System.getenv("CENTRAL_PORTAL_USERNAME")
            ?: System.getenv("OSSRH_USERNAME")
            ?: error("No Central Portal username configured")
        val tokenPass = findProperty("centralPortalPassword")?.toString()
            ?: findProperty("ossrhPassword")?.toString()
            ?: System.getenv("CENTRAL_PORTAL_PASSWORD")
            ?: System.getenv("OSSRH_PASSWORD")
            ?: error("No Central Portal password configured")

        val bearer = java.util.Base64.getEncoder().encodeToString("$tokenUser:$tokenPass".toByteArray())

        val client = java.net.http.HttpClient.newHttpClient()
        val request = java.net.http.HttpRequest.newBuilder()
            .uri(java.net.URI.create(url))
            .header("Authorization", "Bearer $bearer")
            .POST(java.net.http.HttpRequest.BodyPublishers.noBody())
            .build()

        println("Finalizing deployment to Central Portal (publishingType=$publishingType) ...")
        val response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() in 200..299) {
            println("[OK] Deployment finalized successfully (HTTP ${response.statusCode()})")
            if (autoRelease) {
                println("  Auto-release enabled - artifacts will appear on Maven Central after validation (~10-30 min)")
            } else {
                println("  Go to https://central.sonatype.com/publishing/deployments to review and publish")
            }
        } else {
            error("[ERROR] Failed to finalize deployment: HTTP ${response.statusCode()}\n${response.body()}")
        }
    }
}

tasks.register("publishAllToCentralPortal") {
    group = "publishing"
    description = "Publish all modules to the Central Portal (upload + finalize)"

    // Publish all subprojects that apply maven-publish
    dependsOn(subprojects.filter { it.plugins.hasPlugin("maven-publish") }.map { "${it.path}:publishAllPublicationsToCentralPortalRepository" })
    finalizedBy("finalizePortalDeployment")
}
