package gr.dimitris.app.core.speech

class FakeTextToSpeech(var greekAvailable: Boolean = true) : TextToSpeech {
    val spoken = mutableListOf<String>()
    override suspend fun speak(text: String, rate: Float): Result<Unit> { spoken += text; return Result.success(Unit) }
    override suspend fun isGreekAvailable(): Boolean = greekAvailable
    override fun stop() {}
}
