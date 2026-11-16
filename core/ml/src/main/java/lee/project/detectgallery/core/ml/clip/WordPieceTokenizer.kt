package lee.project.detectgallery.core.ml.clip

/** multilingual DistilBERT(cased) 용 최소 WordPiece. vocab.txt 한 줄 = 토큰 하나. */
internal class WordPieceTokenizer(vocabLines: List<String>) {
    private val vocab = vocabLines.withIndex().associate { it.value to it.index.toLong() }
    private val cls = vocab.getValue("[CLS]")
    private val sep = vocab.getValue("[SEP]")
    private val unk = vocab.getValue("[UNK]")

    fun encode(text: String, maxLen: Int): LongArray {
        val ids = mutableListOf(cls)
        for (word in WORD.findAll(text)) ids += wordPiece(word.value)
        return (ids.take(maxLen - 1) + sep).toLongArray()
    }

    /** 가장 긴 접두부터 vocab 에서 찾고, 이어지는 조각은 "##" 을 붙여 찾는다. */
    private fun wordPiece(word: String): List<Long> {
        val out = mutableListOf<Long>()
        var start = 0
        while (start < word.length) {
            var end = word.length
            var id: Long? = null
            while (start < end) {
                id = vocab[(if (start > 0) "##" else "") + word.substring(start, end)]
                if (id != null) break
                end--
            }
            if (id == null) return listOf(unk)
            out += id
            start = end
        }
        return out
    }

    private companion object { val WORD = Regex("""[\p{L}\p{N}]+|[^\s\p{L}\p{N}]""") }
}
// ponytail: BERT BasicTokenizer 의 한자 낱자 분리·제어문자 정리는 생략. 한국어·영어 쿼리에는 영향 없음.