package dev.tn3w.shelf.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val harry = Character("Harry Potter", gender = Gender.Male, age = Age.Teen)
private val ron = Character("Ron Weasley", gender = Gender.Male, age = Age.Teen)
private val ginny = Character("Ginny Weasley", gender = Gender.Female, age = Age.Child)
private val anna = Character("Anna", gender = Gender.Female, age = Age.Adult)
private val roster = Roster(listOf(harry, ron, ginny, anna))

private fun speakerOf(paragraph: String, style: QuoteStyle, quote: Int = 0): Character? {
    val utterances = utterancesOf(paragraph, style)
    val index = utterances.withIndex()
        .filter { it.value.kind == Utterance.Kind.Dialogue }[quote].index
    return taggedSpeaker(roster, utterances, index)
}

private class FakeJudge(private val reply: String) : Judge {
    val prompts = mutableListOf<String>()

    override fun answer(prompt: String) = reply.also { prompts += prompt }

    override fun choose(prompt: String, options: List<String>, prefix: String): String {
        prompts += prompt
        return when {
            "\"Kapitel\"" in prompt -> "no"
            "male or female" in prompt -> "female"
            "How old" in prompt -> "adult"
            else -> options.first()
        }
    }
}

private val english = quoteStyleOf(listOf("“Hello,” said Harry.", "“Fine.”"))
private val german = quoteStyleOf(listOf("»Komm her«, rief Anna.", "»Nein.«"))

class VoicesTest {
    @Test
    fun detectsQuoteStyle() {
        assertTrue(english is QuoteStyle.Marks)
        assertEquals(QuoteStyle.Dash, quoteStyleOf(listOf("— Hola — dijo Ana.", "— Sí.")))
        val utterances = utterancesOf("»Komm her«, rief Anna.", german)
        val first = Utterance(Utterance.Kind.Dialogue, "Komm her", 1)
        assertEquals(first, utterances.first())
    }

    @Test
    fun splitsNarrationDialogueAndScareQuotes() {
        val paragraph = "He called it a “plan” and left. “Run, now!” he said."
        val kinds = utterancesOf(paragraph, english).map { it.kind }
        val expected = listOf(
            Utterance.Kind.Narration,
            Utterance.Kind.Scare,
            Utterance.Kind.Narration,
            Utterance.Kind.Dialogue,
            Utterance.Kind.Narration,
        )
        assertEquals(expected, kinds)
    }

    @Test
    fun splitsDashedDialogue() {
        val utterances = utterancesOf("— Hola — dijo Ana — ¿qué tal?", QuoteStyle.Dash)
        assertEquals(
            listOf("Hola", "dijo Ana", "¿qué tal?"),
            utterances.map { it.text },
        )
        assertEquals(Utterance.Kind.Dialogue, utterances.last().kind)
    }

    @Test
    fun attributesSpeechTags() {
        assertEquals(harry, speakerOf("“Hello,” said Harry.", english))
        assertEquals(anna, speakerOf("»Komm her«, rief Anna.", german))
        assertEquals(anna, speakerOf("Dann sagte Anna leise: »Komm.«", german))
        assertEquals(ron, speakerOf("“Hi,” Ron said. “Want some?”", english))
    }

    @Test
    fun leavesUntaggedQuotesToTheModel() {
        assertNull(speakerOf("“Go away!” Harry looked at the floor.", english))
        assertNull(speakerOf("“Go away!”", english))
        assertNull(speakerOf("“Stop,” Harry and Ron said.", english))
    }

    @Test
    fun sharedSurnamesDoNotIdentifyASpeaker() {
        assertNull(speakerOf("“Dinner,” said Mrs Weasley.", english))
        assertEquals(ginny, speakerOf("“Dinner,” said Ginny.", english))
    }

    @Test
    fun bucketsAges() {
        assertEquals(
            listOf(Age.Child, Age.Teen, Age.YoungAdult, Age.Adult, Age.Elderly),
            listOf(8, 15, 30, 50, 80).map(::ageOf),
        )
    }

    @Test
    fun castsDistinctVoicesByGenderAndAge() {
        val pool = listOf(
            Voice("narrator", Gender.Male, Age.Adult),
            Voice("boy", Gender.Male, Age.Teen),
            Voice("man", Gender.Male, Age.Adult),
            Voice("lad", Gender.Male, Age.Teen),
            Voice("girl", Gender.Female, Age.Child),
            Voice("woman", Gender.Female, Age.Adult),
        )
        val cast = castOf(listOf(harry, ron, ginny, anna), pool)
        assertEquals("narrator", cast.narrator.voice.id)
        assertEquals("boy", cast.speakers.getValue(harry).voice.id)
        assertEquals("lad", cast.speakers.getValue(ron).voice.id)
        assertEquals("girl", cast.speakers.getValue(ginny).voice.id)
        assertEquals("woman", cast.speakers.getValue(anna).voice.id)
        assertTrue(cast.speakers.values.all { it.pitch == 1f })
    }

    @Test
    fun prefersCloseAgesOverUnusedVoices() {
        val pool = listOf(
            Voice("narrator", Gender.Male, Age.Adult),
            Voice("teen", Gender.Male, Age.Teen),
            Voice("man", Gender.Male, Age.Adult),
        )
        val boys = (1..2).map {
            Character("Boy $it", gender = Gender.Male, age = Age.Child)
        }
        val grandfather =
            Character("Grandfather", gender = Gender.Male, age = Age.Elderly)
        val cast = castOf(boys + grandfather, pool)
        assertTrue(boys.all { cast.speakers.getValue(it).voice.id == "teen" })
        assertTrue(boys.all { cast.speakers.getValue(it).pitch > 1f })
        assertEquals("man", cast.speakers.getValue(grandfather).voice.id)
        assertTrue(cast.speakers.getValue(grandfather).pitch < 1f)
    }

    @Test
    fun shiftsPitchOnlyWhenThePoolRunsOut() {
        val pool = listOf(
            Voice("narrator", Gender.Female, Age.Adult),
            Voice("man", Gender.Male, Age.Adult),
        )
        val men = (1..3).map { Character("Man $it", gender = Gender.Male) }
        val cast = castOf(men, pool)
        val castings = cast.speakers.values + cast.narrator
        assertEquals(castings.size, castings.toSet().size)
        assertTrue(cast.speakers.values.all { it.voice.id == "man" })
        assertEquals(1f, cast.speakers.getValue(men.first()).pitch)
        assertNotEquals(1f, cast.speakers.getValue(men.last()).pitch)
    }

    @Test
    fun findsNamesInSpeechTags() {
        val paragraphs = listOf(
            "»Komm her«, rief Anna. »Gleich«, sagte Mr Hind.",
            "»Nein«, sagte Owen Hind.",
            "»Doch«, sagte Owen Hinds.",
            "»Raus«, keifte sie. Ihre Stimme war scharf.",
            "»Yes«, said Mr. Darcy.",
        )
        assertEquals(
            mapOf("Anna" to 0, "Mr Hind" to 0, "Owen Hind" to 1, "Mr Darcy" to 4),
            tagNames(paragraphs, german),
        )
        assertEquals(
            listOf(listOf("Owen Hind", "Mr Hind"), listOf("Anna")),
            mergedNames(listOf("Anna", "Mr Hind", "Owen Hind")),
        )
    }

    @Test
    fun keepsOnlyTheLatestPreviousText() {
        val paragraphs =
            listOf("Erster Absatz.", List(100) { "Wort$it" }.joinToString(" "))
        val text = recentText(paragraphs)
        assertTrue(text.length <= 400)
        assertTrue(text.startsWith("Wort"))
        assertTrue(text.endsWith("Wort99"))
        assertEquals("a\nb", recentText(listOf("a", "b")))
    }

    @Test
    fun buildsTheTrainedPrompt() {
        val prompt = speakerPrompt(
            listOf(anna, ron),
            listOf("Ben sah sie an."),
            listOf("Anna" to "x".repeat(60)),
            "⟦Nein.⟧",
            1,
        )
        val expected = "Task: find who speaks a quote in a novel.\nCharacters:\n" +
            "- Anna: female, adult\n- Ron Weasley: male, teen\n\n" +
            "Previous paragraphs:\nBen sah sie an.\n\n" +
            "Last quotes, oldest first:\nAnna: ⟦${"x".repeat(50)}⟧\n\n" +
            "Target paragraph, spoken words wrapped in ⟦ ⟧:\n⟦Nein.⟧\n\n" +
            "Who speaks the ⟦ ⟧ part number 1? Use speech tags"
        assertTrue(prompt.startsWith(expected))
        assertTrue(prompt.endsWith("Unknown = an unnamed speaker."))
        assertEquals("Anna", answeredSpeaker("{\"speaker\": \"Anna\"}"))
        assertNull(answeredSpeaker("Anna"))
    }

    @Test
    fun attributesQuotesByTagThenByModel() {
        val judge = FakeJudge("{\"speaker\": \"Anna\"}")
        val attributor = Attributor(judge, german)
        val paragraphs = listOf(
            "»Komm her«, rief Anna.",
            "Ben sah sie an. »Wohin«, sagte Kapitel.",
            "»Nein.« Sie lachte. »Doch.«",
        )
        attributor.learn(paragraphs)
        val anna = Character("Anna", gender = Gender.Female)
        assertEquals(listOf(anna), attributor.characters)
        assertEquals(listOf("Anna", null), attributor.speakers(paragraphs, 0))
        assertTrue(judge.prompts.none { "speaks a quote" in it })
        val third = attributor.speakers(paragraphs, 2)
        assertEquals(listOf("Anna", null, "Anna"), third)
        val asked = judge.prompts.filter { "speaks a quote" in it }
        assertTrue(asked.first().contains("⟦Nein.⟧ Sie lachte. ⟦Doch.⟧"))
        assertTrue(asked.last().contains("part number 2"))
        val unsure = Attributor(FakeJudge("{}"), german)
        assertEquals("Unknown", unsure.speakers(paragraphs, 2)[0])
    }

    @Test
    fun splitsSentencesIntoVoices() {
        val paragraph = "»Hallo«, sagte Anna. »Wie geht es?«"
        val utterances = utterancesOf(paragraph, german)
        val speakers = listOf("Anna", null, "Anna")
        val all = segmentsOf(paragraph, utterances, speakers, 0, paragraph.length)
        assertEquals(listOf("Anna", null, "Anna"), all.map { it.speaker })
        assertEquals(paragraph, all.joinToString("") { it.text })
        val second = paragraph.indexOf("»Wie")
        val clipped =
            segmentsOf(paragraph, utterances, speakers, second, paragraph.length)
        assertEquals(listOf(Spoken("»Wie geht es?«", "Anna")), clipped)
        val unknown = segmentsOf(paragraph, utterances, listOf(null, null, null), 0, 7)
        assertEquals(UNKNOWN_SPEAKER, unknown.single().speaker)
    }

    @Test
    fun rebuildsParagraphsFromSentences() {
        val passages = listOf(
            Passage("Eins.", 0, 10, 0),
            Passage("Zwei.", 0, 17, 0),
            Passage("Drei.", 1, 0, 1),
        )
        assertEquals(listOf("Eins.  Zwei.", "Drei."), paragraphsOf(passages))
        assertEquals(
            "⟦Komm⟧, rief Anna.",
            markedParagraph(
                "»Komm«, rief Anna.",
                utterancesOf("»Komm«, rief Anna.", german),
            ),
        )
    }
}
