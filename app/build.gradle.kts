// Force-downloads the MediaPipe model asset instantly during script evaluation
val modelFile = file("src/main/assets/hand_landmarker.task")
if (!modelFile.exists()) {
    modelFile.parentFile.mkdirs()
    val urlString = "https://googleapis.com"
    println("Downloading hand_landmarker.task immediately ...")
    try {
        java.net.URL(urlString).openStream().use { input ->
            modelFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        println("Saved to ${modelFile.absolutePath}")
    } catch (e: Exception) {
        println("Warning: Could not download model asset: ${e.message}")
    }
} else {
    println("Model already present, skipping download.")
}
