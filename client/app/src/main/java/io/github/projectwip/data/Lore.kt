package io.github.projectwip.data

/**
 * The story of Dimension. The user's idea ("it's just the character and the Void"; since build 104 a lore mode that
 * goes on forever). The lore is the user's: the Void carries the soul of every fighter that was removed from the
 * game, and glitches between them. In every dimension a fighter is chained up; the player can go up to them and ask
 * what needs to be done, and they scream for help. Every word here is ours. Eerie, never horror, never gory.
 */
object Lore {
    enum class Speaker { VOID, FIGHTER, CAPTIVE }

    /** The removed fighters whose souls the Void carries, each with the colour it flickers into. */
    enum class Soul(val title: String, val color: Long) {
        BYTE("Byte", 0xFF5CE1FF),
        ROCKET("Rocket", 0xFFFF8A3C),
        SPARK("Spark", 0xFF4C8DFF),
        MIRA("Mira", 0xFFFFD84C),
        VARUN("Varun", 0xFFFF4C6A),
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
        "There is one of yours in chains here. They scream when anyone comes near.",
        "Your friend is waiting for you, tied to my wall. Do hurry.",
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
        Line(Speaker.VOID, "I aimed better than that, once.", Soul.MIRA),
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

    /** Who is chained up in [dimension]: never the player's own fighter, and a different one each time round. */
    fun captive(dimension: Int, player: FighterId): FighterId {
        val all = listOf(FighterId.BRAKK, FighterId.KIRA, FighterId.KITO, FighterId.BUDDY, FighterId.BYTE).filter { it != player }
        return all[Math.floorMod(dimension - 1 + hash(dimension / all.size, 5) % all.size, all.size)]
    }

    /** What the player asks, in their own voice. */
    private val ASK: Map<FighterId, String> = mapOf(
        FighterId.BYTE to "Hey! Hold on, I've got you. What do I need to do?",
        FighterId.BRAKK to "*whines and noses at the chains, then looks up at them*",
        FighterId.KIRA to "Hold still. Tell me what breaks these.",
        FighterId.KITO to "Speak. What must be done?",
        FighterId.BUDDY to "QUERY: HOW DO I RELEASE YOU?",
    )

    /** What the chained fighter screams, and what they tell the player to do. */
    private val PLEA: Map<FighterId, Pair<String, String>> = mapOf(
        FighterId.BYTE to ("HELP! HELP ME! It won't stop whispering!" to
            "There are echoes out there, three of them. Each one breaks a chain. Find them, please!"),
        FighterId.BRAKK to ("*howls and yanks at the chain* AWOO! AWOOOO!" to
            "*paws the ground three times, then stares out into the dark, whining*"),
        FighterId.KIRA to ("HELP! These chains drink magic, I can't hex my way out!" to
            "Three echoes. They're the locks. Bring them back to life and the chains fall off."),
        FighterId.KITO to ("...help. Please. I have never said that before." to
            "Three echoes hold me. Find them. Do not stand still: the ground tears here."),
        FighterId.BUDDY to ("HELP. HELP. HELP. ERROR: HELP." to
            "THREE ECHO SIGNALS DETECTED IN THIS SECTOR. EACH ONE IS A LOCK. RETRIEVE THEM."),
    )

    /** The talk with the chained fighter: the player asks, they scream for help, then say what needs doing. */
    fun captiveTalk(captive: FighterId, player: FighterId): List<Line> {
        val plea = PLEA[captive] ?: PLEA.getValue(FighterId.BYTE)
        return listOf(
            Line(Speaker.FIGHTER, ASK[player] ?: ASK.getValue(FighterId.BYTE)),
            Line(Speaker.CAPTIVE, plea.first),
            Line(Speaker.CAPTIVE, plea.second),
        )
    }
}
