// Downloads the MediaPipe hand-landmarker model into assets on first build
tasks.register("downloadModel") {
    val modelFile = file("src/main/assets/hand_landmarker.task")
    outputs.file(modelFile)
    doLast {
        if (!modelFile.exists()) {
            modelFile.parentFile.mkdirs()
            val urlString = "https://googleapis.com"
            println("Downloading hand_landmarker.task ...")
            java.net.URL(urlString).openStream().use { input ->
                modelFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            println("Saved to ${modelFile.absolutePath}")
        } else {
            println("Model already present, skipping download.")
        }
    }
}

// Correct Kotlin DSL hook to make preBuild wait for the download
tasks.named("preBuild") {
    dependsOn("downloadModel")
}
