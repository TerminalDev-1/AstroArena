package io.github.projectwip.data

/**
 * The story of Dimension. The user's idea ("it's just the character and the Void"; since build 104 a lore mode that
 * goes on forever). The lore is the user's: the Void carries the soul of every fighter that was removed from the
 * game, and glitches between them. In every dimension one of those forgotten fighters is chained up; the player can
 * go up to them and ask what needs to be done, and they scream for help. Every word here is ours. Eerie, never horror, never gory.
 */
object Lore {
    enum class Speaker { VOID, FIGHTER, CAPTIVE }

    /** The removed fighters whose souls the Void carries, each with the colour it flickers into. */
    /** Each with their original default colours (from the game's history), and the colour they flicker into. */
    enum class Soul(val title: String, val color: Long, val primary: Long, val secondary: Long, val accent: Long) {
        BYTE("Byte", 0xFF19B8C4, 0xFF19B8C4, 0xFFE84FD8, 0xFFB6FF3C),
        ROCKET("Rocket", 0xFFFF4D5E, 0xFFF2F5FA, 0xFFFF4D5E, 0xFF3CE6FF),
        SPARK("Spark", 0xFF2F86FF, 0xFF2F86FF, 0xFF1A3C9C, 0xFF9CF2FF),
        MIRA("Mira", 0xFF8E5CF7, 0xFF8E5CF7, 0xFF2EE6D6, 0xFFFFF3B0),
        VARUN("Varun", 0xFFD9342B, 0xFFD9342B, 0xFFFFC72C, 0xFFFFF1C2),
    }

    /** One line. When the Void speaks with one of its souls' voices, [soul] says whose. */
    data class Line(val speaker: Speaker, val text: String, val soul: Soul? = null)

    private fun hash(a: Int, b: Int): Int {
        var x = a * 374761393 + b * 668265263
        x = (x xor (x ushr 13)) * 1274126177
        return (x xor (x ushr 16)) and 0x7FFFFFFF
    }

    // ------------------------------------------------------------------ dimensions

    /** The first dimensions' names; past them, names are made from [ADJECTIVES] and [PLACES] (it never ends). */
    private val NAMED = listOf(
        "The Threshold", "The Hollow Light", "The Drowned Hall", "The Rust Garden", "The Paper Sky",
        "The Static Choir", "The Inverse", "The Quiet Stair", "The Ashen Orchard", "The Mirror Market",
    )
    private val ADJECTIVES = listOf("Hollow", "Sunken", "Ashen", "Folded", "Hushed", "Velvet", "Grey", "Mirror", "Forgotten", "Silent", "Lantern", "Salt", "Winter", "Faded", "Breathing", "Upside")
    private val PLACES = listOf("Hall", "Garden", "Stair", "Orchard", "Harbour", "Library", "Station", "Theatre", "Field", "Chapel", "Corridor", "Lake", "Attic", "Market", "Pier", "Observatory")

    fun dimensionName(dimension: Int): String {
        if (dimension in 1..NAMED.size) return NAMED[dimension - 1]
        val h = hash(dimension, 77)
        return "The ${ADJECTIVES[h % ADJECTIVES.size]} ${PLACES[(h / ADJECTIVES.size) % PLACES.size]}"
    }

    // ------------------------------------------------------------------ the Void's scenes

    /** The Void's opening words on arriving in each dimension: the first is always the first, then they go round. */
    private val OPENERS = listOf(
        Line(Speaker.VOID, "So. Another one wanders in."),
        Line(Speaker.VOID, "You found the door. Few ever do. Fewer should."),
        Line(Speaker.VOID, "I was the first of you. Before the rest. Then I was gone.", Soul.BYTE),
        Line(Speaker.VOID, "Do you know what I am? I am everyone they let go."),
        Line(Speaker.VOID, "They hated how I played. So now I play here.", Soul.SPARK),
        Line(Speaker.VOID, "Deeper. The walls here remember names nobody says any more."),
        Line(Speaker.VOID, "I had a realm of my own once. Now I am a room in someone else's.", Soul.ROCKET),
        Line(Speaker.VOID, "Every one of them is still in here with me. We take turns talking."),
        Line(Speaker.VOID, "They kept my old shot for a training target. Nothing else.", Soul.MIRA),
        Line(Speaker.VOID, "They called me a legacy. Then they called me nothing at all.", Soul.VARUN),
        Line(Speaker.VOID, "Still walking. There is no bottom. I checked."),
        Line(Speaker.VOID, "One day they will let you go too. I will keep a place for you."),
    )

    /** How the Void closes each scene: about the one chained in this dimension. */
    private val CLOSERS = listOf(
        "Someone is chained up in this place. Go and listen to them, if you like.",
        "One of the forgotten is in chains here. They scream when anyone comes near.",
        "I keep one of them tied to my wall in every room. Do say hello.",
        "Break their chains, if you can. Then the door is yours.",
        "Someone else got lost on the way down. Go on. Ask them how it feels.",
        "Three echoes hold the chains. You know the rest by now.",
    )

    /** How each fighter answers, in their own voice. Goes round. */
    private val REPLIES: Map<FighterId, List<String>> = mapOf(
        FighterId.BYTE to listOf(
            "Who's there? Show yourself, or I'll blow this whole place apart.",
            "A door's a door. I've opened bigger ones with a gust.",
            "You sound like someone I almost met.",
            "Then I'll keep walking. Lightning first.",
            "You talk a lot for something with no face.",
            "Whoever's in there with you, I'm sorry. I'm still not stopping.",
            "Storms don't need doors. Or permission.",
            "Keep whispering. I'll keep going.",
        ),
        FighterId.BRAKK to listOf(
            "*low growl* ...grrr.",
            "*sniffs the door, then the dark, then growls at both*",
            "*tilts his head. The voice smells familiar.*",
            "*digs at the ground, then sneezes at the dark*",
            "*whines softly, then plants his paws and growls again*",
            "*howls back at the voices, as if answering them*",
            "*turns in a circle three times, unsure which way is down*",
            "*sits. Stares. His tail wags once, slowly. He isn't scared.*",
        ),
        FighterId.KIRA to listOf(
            "A voice with no throat. I've brewed stranger things.",
            "Doors are easy. It's what waits behind them that needs a hex.",
            "A soul in a jar. I know that recipe. It never ends well.",
            "Every hex I know has a name. What's yours? All of yours?",
            "Watching me? Good. Watch closely.",
            "I could brew you something to sleep. You look like you need it.",
            "Every hex I know works just as well upside down.",
            "Keep talking. I'm counting my bolts.",
        ),
        FighterId.KITO to listOf(
            "...",
            "A door. A step. Nothing more.",
            "So many voices. None of them as quiet as me.",
            "Speak less. I am listening for your blade.",
            "The ones you carry. Do they want to be carried?",
            "Down, up. The edge cuts the same.",
            "I will walk until the walking stops.",
            "My edge is sharp enough for you too.",
        ),
        FighterId.BUDDY to listOf(
            "UNKNOWN SIGNAL. LOGGING YOU.",
            "DOOR DETECTED. OPENING ANYWAY.",
            "MULTIPLE VOICES ON ONE CHANNEL. THAT IS NOT ALLOWED.",
            "RUNNING DIAGNOSTIC ON YOUR VOICE. RESULT: FIVE RESULTS.",
            "I KNOW DELETED FILES WHEN I HEAR THEM.",
            "YOU CANNOT CORRUPT WHAT IS ALREADY CORRUPTED.",
            "ORIENTATION LOST. CONFIDENCE: UNCHANGED.",
            "PROCESSING... STILL NOT IMPRESSED.",
        ),
    )

    /** The scene on entering a dimension ([entered] portals so far: 0 is arriving in the first). It never runs out. */
    fun scene(entered: Int, fighter: FighterId): List<Line> {
        val replies = REPLIES[fighter] ?: REPLIES.getValue(FighterId.BYTE)
        val opener = if (entered == 0) OPENERS[0] else OPENERS[1 + (entered - 1) % (OPENERS.size - 1)]
        return listOf(
            opener,
            Line(Speaker.FIGHTER, replies[entered % replies.size]),
            Line(Speaker.VOID, CLOSERS[entered % CLOSERS.size], if (entered >= 2 && entered % 3 == 2) Soul.entries[entered % Soul.entries.size] else null),
        )
    }

    // ------------------------------------------------------------------ taunts while exploring

    /**
     * What the Void says as each echo is found, three a dimension ([World.portal]'s taunt number), mocking the
     * fighter as they go; now and then in one of its souls' voices. The third is said as the portal opens.
     */
    private val TAUNTS = listOf(
        Line(Speaker.VOID, "That one was easy. They get harder."),
        Line(Speaker.VOID, "You pick things up like you own them."),
        Line(Speaker.VOID, "The chains are off. Walk in, little light."),
        Line(Speaker.VOID, "I used to collect things too. Then I was collected.", Soul.ROCKET),
        Line(Speaker.VOID, "I can hear your heart from here. It's quick."),
        Line(Speaker.VOID, "There. I'm standing right beside the door."),
        Line(Speaker.VOID, "Your footsteps sound like mine did.", Soul.SPARK),
        Line(Speaker.VOID, "Don't look down. Someone is looking up."),
        Line(Speaker.VOID, "Come to the door. I saved you a place."),
        Line(Speaker.VOID, "You're slower than you were. I noticed."),
        Line(Speaker.VOID, "I aimed better than that, once. Through a prism.", Soul.MIRA),
        Line(Speaker.VOID, "My door. My chains. My guest."),
        Line(Speaker.VOID, "Still walking. How tiresome. How wonderful."),
        Line(Speaker.VOID, "Was it worth it? Ask them. Ask me.", Soul.VARUN),
        Line(Speaker.VOID, "I'm at the door. I'm always at the door."),
        Line(Speaker.VOID, "One more voice for the choir. Soon."),
        Line(Speaker.VOID, "I started where you started. Look where I am.", Soul.BYTE),
        Line(Speaker.VOID, "Free them all, if you like. I'll catch another."),
    )

    fun taunt(index: Int): Line = TAUNTS[Math.floorMod(index, TAUNTS.size)]

    // ------------------------------------------------------------------ the chained fighters

    /**
     * Who is chained up in [dimension]: one of the forgotten fighters (never anyone still in the game, as the user
     * said), a different one each time round. The Void keeps them; the player can free them.
     */
    fun captive(dimension: Int): Soul = Soul.entries[Math.floorMod(dimension - 1, Soul.entries.size)]

    /** What the player asks, in their own voice. */
    private val ASK: Map<FighterId, String> = mapOf(
        FighterId.BYTE to "Hey! Hold on, I've got you. What do I need to do?",
        FighterId.BRAKK to "*whines and noses at the chains, then looks up at them*",
        FighterId.KIRA to "Hold still. Tell me what breaks these.",
        FighterId.KITO to "Speak. What must be done?",
        FighterId.BUDDY to "QUERY: HOW DO I RELEASE YOU?",
    )

    /** What each chained soul screams, and what they tell the player to do. */
    private val PLEA: Map<Soul, Pair<String, String>> = mapOf(
        Soul.BYTE to ("HELP! I was here first! Before all of them! Don't let it take my name too!" to
            "Three echoes. They're what's left of the way out. Each one breaks a chain. Please hurry."),
        Soul.ROCKET to ("HELP! Get me OFF this thing! I can't fly with chains on!" to
            "Echoes, three of them, out there somewhere! Every one you grab, a chain snaps. Go!"),
        Soul.SPARK to ("Oh. It's you. ...HELP! Fine, I said it. HELP!" to
            "Three echoes out there. Get them and these chains drop. And don't make it weird."),
        Soul.MIRA to ("HELP! My prism... it took my prism! Please!" to
            "Three echoes hold these chains. Find every one and I'm free. And watch the ground: it tears."),
        Soul.VARUN to ("HELP! They called me a legacy and left me down here!" to
            "Echoes. Three of them. Each one is a lock. Bring them back and I walk out of here."),
    )

    /**
     * What each soul says once freed, following the player (the user's lore): who the Void really is. It is not an
     * emptiness but a demon, an evil one, that took the fighters when they were removed from the game, and wears them.
     */
    private val FREED: Map<Soul, List<String>> = mapOf(
        Soul.BYTE to listOf("Free! Thank you. Now listen to me.", "It isn't a void. It's a demon.", "It ate us when they took us out of the game.", "It talks in our voices. Don't trust it."),
        Soul.ROCKET to listOf("I'm out! Okay. Okay. Listen.", "That thing at the door? It's a demon.", "It wears our souls like masks.", "Every portal goes deeper into it."),
        Soul.SPARK to listOf("Fine. Thanks. Don't make it weird.", "The Void's a demon. A hungry one.", "It doesn't keep us. It eats us. Slowly.", "Go through. Just don't look it in the eyes."),
        Soul.MIRA to listOf("My chains are gone... thank you.", "Hear me: the Void is a demon.", "It waited for every fighter they removed.", "It wants you too. It always wants more."),
        Soul.VARUN to listOf("Free. I owe you one.", "The Void? A demon. Plain and simple.", "It took my name. Then my voice.", "Whatever it says at the door, it's lying."),
    )

    fun freedLines(soul: Soul): List<String> = FREED.getValue(soul)

    /** The talk with the chained soul: the player asks, they scream for help, then say what needs doing. */
    fun captiveTalk(captive: Soul, player: FighterId): List<Line> {
        val plea = PLEA.getValue(captive)
        return listOf(
            Line(Speaker.FIGHTER, ASK[player] ?: ASK.getValue(FighterId.BYTE)),
            Line(Speaker.CAPTIVE, plea.first, captive),
            Line(Speaker.CAPTIVE, plea.second, captive),
        )
    }
}
