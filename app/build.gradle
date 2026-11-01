// Downloads the MediaPipe hand-landmarker model into assets on first build
tasks.register("downloadModel") {
    val modelFile = file("src/main/assets/hand_landmarker.task")
    outputs.file(modelFile)
    doLast {
        if (!modelFile.exists()) {
            modelFile.parentFile.mkdirs()
            val urlString = "https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task"
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
preBuild.dependsOn("downloadModel")
