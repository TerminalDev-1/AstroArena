package io.github.projectwip.data

/**
 * The story of Dimension (1.0): each time a fighter enters a dimension, the Void speaks to them and they answer.
 * The user's idea ("it's just the character and the Void"); every word here is ours. Eerie, never gory.
 */
object Lore {
    enum class Speaker { VOID, FIGHTER }

    data class Line(val speaker: Speaker, val text: String)

    /** What the Void says first in each dimension after the first (in turn), and how it closes each scene. */
    private val FOLLOW = listOf(
        "Deeper, then. The walls here remember things.",
        "Go on. The next ones are hungrier.",
        "You are close to something. Or something is close to you.",
        "Do not stop now. I am enjoying this.",
        "Every step down, you sound a little more like me.",
        "I will be here. I am always here.",
    )

    /** How each fighter answers, in turn: in their own voice. */
    private val REPLIES: Map<FighterId, List<String>> = mapOf(
        FighterId.BYTE to listOf(
            "Who's there? Show yourself, or I'll blow this whole place apart.",
            "I've been through worse weather than you.",
            "Keep whispering. Storms don't scare.",
            "Then I'll keep walking. Lightning first.",
            "You talk a lot for something with no face.",
        ),
        FighterId.BRAKK to listOf(
            "*low growl* ...grrr.",
            "*sniffs the dark air, hackles up*",
            "*barks once, sharp, at nothing at all*",
            "*whines, then plants his paws and growls again*",
            "*stares into the dark and does not blink*",
        ),
        FighterId.KIRA to listOf(
            "A voice with no throat. I've brewed stranger things.",
            "Your riddles taste like old smoke.",
            "Every hex I know has a name. What's yours?",
            "Watching me? Good. Watch closely.",
            "Keep talking. I'm counting my bolts.",
        ),
        FighterId.KITO to listOf(
            "...",
            "Shadows do not frighten a shadow.",
            "Speak less. I am listening for your blade.",
            "One step. Then another. That is the way.",
            "My edge is sharp enough for you too.",
        ),
        FighterId.BUDDY to listOf(
            "UNKNOWN SIGNAL. LOGGING YOU.",
            "RUNNING DIAGNOSTIC ON YOUR VOICE. RESULT: NULL.",
            "I HAVE NO LIGHT TO LOOK BACK AT.",
            "YOU CANNOT CORRUPT WHAT IS ALREADY CORRUPTED.",
            "PROCESSING... STILL NOT IMPRESSED.",
        ),
    )

    /** The scene for the [entered]th dimension (0 = arriving in the first) with [fighter]. */
    fun scene(entered: Int, fighter: FighterId): List<Line> {
        val replies = REPLIES[fighter] ?: REPLIES.getValue(FighterId.BYTE)
        val opener = if (entered == 0) "So. Another one wanders in." else Balance.VOID_LINES[(entered - 1) % Balance.VOID_LINES.size]
        val closer = if (entered == 0) "Clear the way, and a door will open. I will be watching." else FOLLOW[(entered - 1) % FOLLOW.size]
        return listOf(
            Line(Speaker.VOID, opener),
            Line(Speaker.FIGHTER, replies[entered % replies.size]),
            Line(Speaker.VOID, closer),
        )
    }
}
