package io.github.projectwip.data

/**
 * The story of Dimension: each time a fighter enters a dimension the Void speaks to them and they answer, and while
 * they search for its echoes it taunts them. The user's idea ("it's just the character and the Void"; since build
 * 104 "primarily a lore mode", with the Void "at every single portal after portal one"); every word here is ours.
 * Eerie, never gory. Seven portals go down to the bottom ([Balance.PORTAL_DEPTH]), where the story ends.
 */
object Lore {
    enum class Speaker { VOID, FIGHTER }

    data class Line(val speaker: Speaker, val text: String)

    /** What each dimension is called, from the first down to the bottom. Shown on the cutscene and the HUD. */
    val DIMENSIONS = listOf(
        "The Threshold",
        "The Hollow Light",
        "The Drowned Hall",
        "The Rust Garden",
        "The Paper Sky",
        "The Static Choir",
        "The Inverse",
        "The Bottom",
    )

    fun dimensionName(dimension: Int) = DIMENSIONS[(dimension - 1).coerceIn(0, DIMENSIONS.lastIndex)]

    /** The Void's opening words on arriving in each dimension ([entered] = 0 is the first), and its closing words. */
    private val OPENERS = listOf(
        "So. Another one wanders in.",
        "You found the door. Few ever do. Fewer should.",
        "Deeper. Do you hear the water? It remembers you.",
        "Things grow down here. Slowly. Like I did.",
        "The sky is thin here. Do not lean on it.",
        "Listen. Every voice in the static was a fighter, once.",
        "Up is down now. Left is a rumour. You are still you. For now.",
        "This is the bottom. There was never a way out. Only a way in.",
    )
    private val CLOSERS = listOf(
        "Three echoes lie in this place. Find them, and a door will open. I will be watching.",
        "Gather what is left of the light. I will be waiting at the door.",
        "Find the echoes before the floor forgets it is a floor.",
        "Pick through my garden. Mind the cracks. They bite.",
        "Collect your little echoes. I will meet you at the door. I always do.",
        "Go on. Gather them. Each one sounds a little more like me.",
        "The last door is below you. I am already standing in it.",
        "Stay a while. Or climb back up and forget me. You won't. Nobody does.",
    )

    /** How each fighter answers, one line a dimension, in their own voice. The last is at the bottom. */
    private val REPLIES: Map<FighterId, List<String>> = mapOf(
        FighterId.BYTE to listOf(
            "Who's there? Show yourself, or I'll blow this whole place apart.",
            "A door's a door. I've opened bigger ones with a gust.",
            "Water doesn't scare me. I'm the one who brings the rain.",
            "Your garden needs weather. Lucky you, I brought some.",
            "Then I'll hold the sky up myself. I've done it before.",
            "Static's just a storm that forgot how to rain. I know storms.",
            "Upside down, inside out. My lightning still knows where you are.",
            "Then I'll make one. Storms don't need doors.",
        ),
        FighterId.BRAKK to listOf(
            "*low growl* ...grrr.",
            "*sniffs the door, then the dark, then growls at both*",
            "*paws at the water, ears flat, and does not back away*",
            "*digs at a crack in the ground, then sneezes*",
            "*looks up at the sky and barks until it trembles*",
            "*tilts his head at the voices, then howls back at them*",
            "*turns in a circle three times, unsure which way is down*",
            "*sits. Stares. His tail wags once, slowly. He isn't scared.*",
        ),
        FighterId.KIRA to listOf(
            "A voice with no throat. I've brewed stranger things.",
            "Doors are easy. It's what waits behind them that needs a hex.",
            "Drowned halls make the best ingredients. Thank you.",
            "Rust and roots. My grandmother kept a garden like this.",
            "Paper burns. Remember that, before you threaten me.",
            "I've heard choirs like this in a cauldron. They hush when you stir.",
            "Every hex I know works just as well upside down.",
            "A way in is a way out, read backwards. I can read backwards.",
        ),
        FighterId.KITO to listOf(
            "...",
            "A door. A step. Nothing more.",
            "Water hides footsteps. Not mine.",
            "A garden of rust. My blade does not rust.",
            "The sky can fall. I will be somewhere else.",
            "So many voices. None of them as quiet as me.",
            "Down, up. The edge cuts the same.",
            "Then I will wait. I am good at waiting.",
        ),
        FighterId.BUDDY to listOf(
            "UNKNOWN SIGNAL. LOGGING YOU.",
            "DOOR DETECTED. OPENING ANYWAY.",
            "WARNING: LIQUID. I AM NOT WATERPROOF. PROCEEDING.",
            "ORGANIC GROWTH ON METAL. FASCINATING. DISGUSTING.",
            "SKY INTEGRITY: 4 PERCENT. NOTED.",
            "THE STATIC IS SPEAKING MY LANGUAGE. I DO NOT LIKE ITS ACCENT.",
            "ORIENTATION LOST. CONFIDENCE: UNCHANGED.",
            "YOU CANNOT TRAP WHAT IS ALREADY EVERYWHERE. I WILL REBOOT UPSTAIRS.",
        ),
    )

    /** The scene for the [entered]th dimension (0 = arriving in the first, [Balance.PORTAL_DEPTH] = the bottom). */
    fun scene(entered: Int, fighter: FighterId): List<Line> {
        val replies = REPLIES[fighter] ?: REPLIES.getValue(FighterId.BYTE)
        val i = entered.coerceIn(0, OPENERS.lastIndex)
        return listOf(
            Line(Speaker.VOID, OPENERS[i]),
            Line(Speaker.FIGHTER, replies[i.coerceAtMost(replies.lastIndex)]),
            Line(Speaker.VOID, CLOSERS[i]),
        )
    }

    /**
     * What the Void says as each echo is found, three a dimension ([World.portal]'s taunt number), mocking the
     * fighter as they go. The third is always said at the moment the portal opens.
     */
    private val TAUNTS = listOf(
        // The Threshold
        "That one was easy. They get harder.", "You pick things up like you own them.", "The door is open. Walk in, little light.",
        // The Hollow Light
        "Is that all you came for? Glitter?", "I can hear your heart from here. It's quick.", "There it is. I'm standing right beside it.",
        // The Drowned Hall
        "Your footsteps sound like the last one's.", "Don't look down. Something is looking up.", "Come to the door. I saved you a place.",
        // The Rust Garden
        "Careful. The ground here has opinions.", "You're slower than you were. I noticed.", "My door. My garden. My guest.",
        // The Paper Sky
        "The sky is tearing. Was that you?", "Still walking. How tiresome. How wonderful.", "I'm at the door. I'm always at the door.",
        // The Static Choir
        "They're singing your name. Badly.", "One more voice for the choir. Soon.", "Come closer. I want to hear you breathe.",
        // The Inverse
        "Nothing is where you left it. Not even you.", "You've come so far to find so little.", "The last door. After this, only me.",
    )

    fun taunt(index: Int): String = TAUNTS[index.coerceIn(0, TAUNTS.lastIndex)]

    /** What the Void says once the player first stands in a dimension (shown as they arrive, on the HUD). */
    fun arrival(dimension: Int): String = "Welcome to ${dimensionName(dimension)}."
}
