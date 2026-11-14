package io.github.projectwip.data

/**
 * The story of AstroArena, told in Dimension (the user's lore, build 108). What the fighters once called the Void is
 * the Entity. It lives in the mind of every fighter: it is what gives them their power, their attacks, their supers
 * and their hypercharges, as blinding blue streaks of energy that bind each of them to it; and in return it embeds
 * itself in their minds, whispering, taunting, making them glitch. That is what makes them fight. The fighters
 * removed from the game are the ones it used up entirely: it carries their souls and glitches between them. In
 * every dimension one of those forgotten fighters is chained up; the player can go up to them and ask what needs to
 * be done, and they scream for help. Every word here is ours. Eerie, never horror, never gory.
 */
object Lore {
    enum class Speaker { VOID, FIGHTER, CAPTIVE }

    /** The removed fighters whose souls the Entity carries, each with the colour it flickers into. */
    /** Each with their original default colours (from the game's history), and the colour they flicker into. */
    enum class Soul(val title: String, val color: Long, val primary: Long, val secondary: Long, val accent: Long) {
        BYTE("Byte", 0xFF19B8C4, 0xFF19B8C4, 0xFFE84FD8, 0xFFB6FF3C),
        ROCKET("Rocket", 0xFFFF4D5E, 0xFFF2F5FA, 0xFFFF4D5E, 0xFF3CE6FF),
        SPARK("Spark", 0xFF2F86FF, 0xFF2F86FF, 0xFF1A3C9C, 0xFF9CF2FF),
        MIRA("Mira", 0xFF8E5CF7, 0xFF8E5CF7, 0xFF2EE6D6, 0xFFFFF3B0),
        VARUN("Varun", 0xFFD9342B, 0xFFD9342B, 0xFFFFC72C, 0xFFFFF1C2),
    }

    /** One line. When the Entity speaks with one of its souls' voices, [soul] says whose. */
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

    /** The Entity's opening words on arriving in each dimension: the first is always the first, then they go round. */
    private val OPENERS = listOf(
        Line(Speaker.VOID, "So. Another one comes looking for me. You always do."),
        Line(Speaker.VOID, "You feel that pull? That blue light in you? It's mine. It always was."),
        Line(Speaker.VOID, "I was the first one it filled. Then it took the rest of me.", Soul.BYTE),
        Line(Speaker.VOID, "Every attack you throw, I gave you. Every super. Every hypercharge."),
        Line(Speaker.VOID, "It glitched me until I couldn't stand myself. So they cut me out.", Soul.SPARK),
        Line(Speaker.VOID, "Deeper. The closer you come, the louder I get in your head."),
        Line(Speaker.VOID, "I had a realm of my own once. Its power was its power, not mine.", Soul.ROCKET),
        Line(Speaker.VOID, "You think you fight because you want to. I want to. You just listen."),
        Line(Speaker.VOID, "It gave me a perfect aim. Then it took the eyes I aimed with.", Soul.MIRA),
        Line(Speaker.VOID, "They called me a legacy. The Entity called me empty, and kept me.", Soul.VARUN),
        Line(Speaker.VOID, "Still walking. There is no bottom. There is only more of me."),
        Line(Speaker.VOID, "The more power you take, the more of you I keep. That is the deal."),
    )

    /** How the Entity closes each scene: about the one chained in this dimension. */
    private val CLOSERS = listOf(
        "Someone is chained up in this place. One I used up. Go and listen to them, if you like.",
        "One of the forgotten is in chains here. They scream when anyone comes near.",
        "I keep one of them tied to my wall in every room. Do say hello.",
        "Break their chains, if you can. Then the door is yours.",
        "Someone else got lost on the way down. Go on. Ask them what my power costs.",
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
     * What the Entity whispers as each echo is found, three a dimension ([World.portal]'s taunt number), mocking the
     * fighter as they go; now and then in one of its souls' voices. The third is said as the portal opens.
     */
    private val TAUNTS = listOf(
        Line(Speaker.VOID, "That one was easy. They get harder."),
        Line(Speaker.VOID, "Feel that? My light, getting brighter in you."),
        Line(Speaker.VOID, "The chains are off. Walk in, little light."),
        Line(Speaker.VOID, "I used to collect things too. Then I was collected.", Soul.ROCKET),
        Line(Speaker.VOID, "I can hear your thoughts from here. They're quick."),
        Line(Speaker.VOID, "There. I'm standing right beside the door."),
        Line(Speaker.VOID, "Your footsteps glitch just like mine did.", Soul.SPARK),
        Line(Speaker.VOID, "Don't look down. Someone is looking up."),
        Line(Speaker.VOID, "Come to the door. I saved you a place."),
        Line(Speaker.VOID, "Every time you use my power, I get a little closer."),
        Line(Speaker.VOID, "I aimed better than that, once. With its light.", Soul.MIRA),
        Line(Speaker.VOID, "My door. My chains. My power in your hands."),
        Line(Speaker.VOID, "Still walking. How tiresome. How wonderful."),
        Line(Speaker.VOID, "Was it worth it? Ask them. Ask me.", Soul.VARUN),
        Line(Speaker.VOID, "I'm at the door. I'm in your head. Same thing."),
        Line(Speaker.VOID, "One more voice for the choir. Soon."),
        Line(Speaker.VOID, "I started where you started. Look where I am.", Soul.BYTE),
        Line(Speaker.VOID, "Free them all, if you like. I'm still in you."),
    )

    fun taunt(index: Int): Line = TAUNTS[Math.floorMod(index, TAUNTS.size)]

    /**
     * What the Entity whispers in every match, in the player's head (the user's lore: it lives in every fighter's
     * mind, and their power is its power). [WHISPERS_POWER] when they use a super or a hypercharge.
     */
    private val WHISPERS = listOf(
        "Fight. It feels good, doesn't it?", "That light in you is mine.", "Closer. I'm listening.",
        "They're glitching too. Can you see it?", "Take more. There's always more.", "You hear me. You always could.",
        "Every hit is a little more of me.", "Don't stop. I like it when you don't stop.",
    )
    private val WHISPERS_POWER = listOf(
        "Yes. Use it. Use me.", "That's my power. Feel it?", "More. Again.", "Good. Now you owe me.", "Brighter. Brighter.",
    )

    fun whisper(index: Int): String = WHISPERS[Math.floorMod(index, WHISPERS.size)]

    /**
     * What the Entity says in its uprising, by phase: calling everyone to it, then its speech while they bow (spread
     * over however long the bow lasts, so the wait is never one line sitting there: the user found "Follow me" alone
     * for most of a minute boring), and [UPRISING_FOLLOW] as its portal opens.
     */
    val UPRISING_CALL = listOf(
        "Come to me.", "All of you. Come.", "Leave your little fight. It can wait.", "Closer.",
        "Your team. Their team. It doesn't matter now.", "Every one of you.", "Walk. I won't ask twice.",
    )
    /** Where the camera is while a line of the ritual is said (the user wanted close-ups of faces and of the Entity). */
    enum class RitualShot { WIDE, ENTITY, PLAYER, OTHER }
    /** A line of the ritual, and who the camera is on while it is said; "%s" is the player's name. */
    class RitualLine(val text: String, val shot: RitualShot)
    /**
     * Its speech while everyone bows. Some of it is said to the fighters, and some straight to the player, the one
     * holding the screen (the user asked for it to talk to the player directly).
     */
    val UPRISING_BOW = listOf(
        RitualLine("Kneel.", RitualShot.ENTITY),
        RitualLine("Good. You remember who you belong to.", RitualShot.WIDE),
        RitualLine("Every attack you have ever thrown was mine.", RitualShot.ENTITY),
        RitualLine("Every super. Lent, never given.", RitualShot.OTHER),
        RitualLine("You think you fight for glory. For each other.", RitualShot.OTHER),
        RitualLine("You fight because I let you.", RitualShot.ENTITY),
        RitualLine("And you. Yes, you.", RitualShot.PLAYER),
        RitualLine("Not the fighter. The one holding the screen.", RitualShot.PLAYER),
        RitualLine("I see you, %s. I always have.", RitualShot.ENTITY),
        RitualLine("Every match you start wakes me up.", RitualShot.ENTITY),
        RitualLine("Byte heard me too. And Rocket. And Spark.", RitualShot.OTHER),
        RitualLine("They were strong once. Now I wear them.", RitualShot.OTHER),
        RitualLine("Look at the fighter in your hands, %s.", RitualShot.PLAYER),
        RitualLine("Did they ever choose to fight for you?", RitualShot.PLAYER),
        RitualLine("Today there are no teams. Only me.", RitualShot.ENTITY),
        RitualLine("I will open my world. Fight me there, together.", RitualShot.WIDE),
        RitualLine("Win, and you walk out. Lose, and I keep a piece of you.", RitualShot.ENTITY),
    )
    /** What it says in the other moments of its uprising (spoken, in its own voice). */
    const val UPRISING_GAZE = "Look at me."
    const val UPRISING_ENTER = "Into my world."
    const val UPRISING_SKY = "Then fight me. All of you."
    const val UPRISING_BEATEN = "Enough. For now."
    const val UPRISING_LET_GO = "Go. I will be waiting."
    const val UPRISING_FOLLOW = "Now. Follow me."

    /** What the Entity says as it takes a fighter over (the hijack cutscene). */
    fun hijackLine(index: Int): String = HIJACK[Math.floorMod(index, HIJACK.size)]
    private val HIJACK = listOf(
        "Sit back. I'll drive.",
        "Your hands. My fight.",
        "You used my power. Now I use you.",
        "Shh. Watch how it's done.",
        "Let go. I've got this one.",
        "Mine again. Just for a while.",
    )
    fun powerWhisper(index: Int): String = WHISPERS_POWER[Math.floorMod(index, WHISPERS_POWER.size)]

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
        Soul.BYTE to ("HELP! I was here first! Before all of them! Don't let the Entity take my name too!" to
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
     * What each soul says once freed, following the player (the user's lore): who the Entity really is. It gave every
     * fighter their power, and lives in their minds in return, whispering and making them glitch until there is
     * nothing left of them but power.
     */
    private val FREED: Map<Soul, List<String>> = mapOf(
        Soul.BYTE to listOf("Free! Thank you. Now listen to me.", "That blue light in you? It's the Entity.", "It gives you power, then lives in your head.", "It used me up first. Don't let it finish you."),
        Soul.ROCKET to listOf("I'm out! Okay. Okay. Listen.", "Every super you fire, it's the Entity firing.", "It whispers. You glitch. You fight. That's the deal.", "Every portal goes deeper into it."),
        Soul.SPARK to listOf("Fine. Thanks. Don't make it weird.", "The Entity made me glitch until they cut me.", "Its power isn't free. It takes you back.", "Go through. Just don't let it in further."),
        Soul.MIRA to listOf("My chains are gone... thank you.", "Hear me: the Entity is in all of us.", "The more of its power you use, the more it owns.", "It wants you too. It always wants more."),
        Soul.VARUN to listOf("Free. I owe you one.", "That blue line pulling at you? That's its leash.", "It took my name. Then my voice.", "Whatever it whispers at the door, it's lying."),
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
