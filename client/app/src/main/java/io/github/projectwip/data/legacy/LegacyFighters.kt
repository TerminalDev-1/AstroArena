package io.github.projectwip.data.legacy

import io.github.projectwip.data.AttackShape
import io.github.projectwip.data.AttackSpec
import io.github.projectwip.data.Balance
import io.github.projectwip.data.Balance.SPARK_REALM_SECONDS
import io.github.projectwip.data.FighterDef
import io.github.projectwip.data.FighterId
import io.github.projectwip.data.HyperSpec
import io.github.projectwip.data.Rarity
import io.github.projectwip.data.Skin
import io.github.projectwip.data.StatLine
import io.github.projectwip.data.SuperKind
import io.github.projectwip.data.SuperSpec
import io.github.projectwip.data.VoiceCue
import io.github.projectwip.data.VoiceStyle

/**
 * Fighters no longer in the game (the owner's decision, build 114: Ninja is the only one). Their defs are kept here,
 * unused by play, so old saves still load and the tests keep their mechanics working. Their ids stay in [FighterId].
 * Swish's def keeps the starter's id it had; Spark's carries it only because a def needs one.
 */
object LegacyFighters {
    /**
     * Spark, retired in build 102 by the user's decision (`REMOVED_FIGHTERS` in `economy.py` pays him back). Nobody plays
     * him: his def is kept, like Rocket's code, because the size of a boss is made from it and the tests keep his lobbed
     * star and shadow realm working with it. (It carries the starter's id only because a def needs one.)
     */
    val spark: FighterDef = FighterDef(
        id = FighterId.BYTE,
        rarity = Rarity.LEGENDARY,
        // (His realm is Rocket's, whose rockets and realm are still in the simulation.)
        name = "Spark",
        title = "The Drop That Woke",
        role = "Trickster",
        lore = "When the Spark Drops were scrapped, one of them would not go. It woke up in the dark with a grudge and a grin, and now it walks the arena throwing stars at whoever comes near, and dragging them off into a realm where its shadows are waiting.",
        attackName = "Spite Star",
        health = StatLine(6600, 330),
        // One star lobbed at a spot, and two seconds at half speed for every enemy in its burst.
        attackDamage = StatLine(1400, 70),
        // One strike of one of his four shadows: they take turns, each striking once a second.
        superDamage = StatLine(400, 20),
        moveSpeed = 3.6f,
        attack = AttackSpec(AttackShape.STAR, projectiles = 1, spreadDegrees = 0f, range = 8f, speed = 12f, radius = 0.26f, burstInterval = 0f, blast = 1.2f, slowSeconds = 2f),
        superSpec = SuperSpec(SuperKind.REALM, "Shadow Realm", "Aim it at an enemy (or tap, and it picks the nearest): he drags the first one on the line away into a realm of his own for 6 seconds, where four shadows of his fight them. In there they move a quarter slower and hit a quarter softer, bosses included, and nobody outside can touch either of them. He reloads and charges his super half as fast again, though his shadows' strikes barely charge it. Aimed at nobody, it misses.", range = 7f, speed = 30f, radius = 0f, shadows = 4, realmSeconds = SPARK_REALM_SECONDS, captiveFree = true),
        ammoMax = 3,
        reloadSeconds = 1.4f,
        superChargePerHit = 0.2f,
        skins = listOf(
            Skin("Overload", 0xFF2F86FF, 0xFF1A3C9C, 0xFF9CF2FF, 0),
            Skin("Void Spark", 0xFF2C2350, 0xFF0C0A1C, 0xFF3CF2FF, 20),
            Skin("Solar Spite", 0xFFFFB627, 0xFF4A1206, 0xFFFF3B3B, 20),
        ),
        hyper = HyperSpec("Wild Spark", "Lasts 8 seconds. His stars fly half as fast again.", seconds = 8f, shotSpeed = 1.5f),
    )


    /** Swish, Bark, Kira, Kito and Buddy, as they were in build 113. */
    val removed: List<FighterDef> = listOf(
        FighterDef(
            id = FighterId.BYTE,
            // (Starter, by the user's decision: she was Legendary for one unreleased build.)
            rarity = Rarity.STARTER,
            // (The id is still BYTE: Swish took Spark's place, by the user's decision (build 96), as Spark had taken
            // Rocket's and Rocket Byte's. Spark's def is kept as [spark].)
            name = "Swish",
            title = "Goddess of the Gale",
            role = "Sniper",
            lore = "A storm that got bored of being weather. She took a shape, borrowed a bow from the sky, and came down to see who was making all the noise. Her gusts find you; her temper finds everyone else.",
            attackName = "Frost Gale",
            health = StatLine(6600, 330),
            // One blast of cold wind (build 103, the user's design; it was a giant arrow), which bends a little toward
            // whoever is ahead of it and knocks whoever it hits back.
            attackDamage = StatLine(1700, 85),
            // One hit of the storm on everyone inside it (who is then flung out of it). 1,100 at level 10: the user
            // nerfed it from 1,160 in build 103.
            superDamage = StatLine(740, 40),
            moveSpeed = 3.6f,
            // A sniper, but not a long-range one: nine tiles, and the homing is gentle (120 degrees a second, only
            // toward someone close ahead of the arrow), so a sidestep at the right moment still beats it.
            attack = AttackSpec(AttackShape.ARROW, projectiles = 1, spreadDegrees = 0f, range = 9f, speed = 16f, radius = 0.32f, burstInterval = 0f, homing = 120f, knockback = 1.2f),
            superSpec = SuperSpec(SuperKind.TEMPEST, "Thunderhead", "She becomes a raging lightning tornado for 8 seconds, flying fast over walls and water. Whoever the funnel touches is struck and flung away, and lightning cracks down on enemies nearby. She can't shoot while she is the storm, but its strikes slowly charge her next one.", range = 0f, speed = 0f, radius = 1.6f, knockback = 1.8f, tempestSeconds = 8f),
            ammoMax = 3,
            reloadSeconds = 1.5f,
            // Half of what most fighters get: her storm keeps charging the super while it runs (the user asked for that,
            // with a much lower rate to pay for it). Ten arrows for a storm.
            superChargePerHit = 0.1f,
            skins = listOf(
                Skin("Tempest", 0xFF6C5CE7, 0xFF241A5C, 0xFF7DF9FF, 0),
                Skin("Sunshower", 0xFFFFD66B, 0xFFFFF6E0, 0xFF5BC0FF, 20),
                Skin("Black Squall", 0xFF2B2D42, 0xFF0D0E17, 0xFFC77DFF, 20),
            ),
            hyper = HyperSpec("Eye of the Storm", "Lasts 8 seconds. Her arrows fly half as fast again.", seconds = 8f, shotSpeed = 1.5f),
        ),
        FighterDef(
            id = FighterId.BRAKK,
            rarity = Rarity.RARE,
            name = "Bark",
            title = "Scrapyard Hound",
            role = "Tracker",
            lore = "A junkyard guard dog who rebuilt himself out of the scrap he was guarding. He leaves his mark on everyone who comes over the fence.",
            attackName = "Paw Prints",
            health = StatLine(7600, 380),
            // The least one paw print does: it takes a share of the health its target has left when that is more.
            attackDamage = StatLine(940, 47),
            superDamage = StatLine(2000, 100),
            moveSpeed = 3.6f,
            attack = AttackSpec(AttackShape.PAWS, projectiles = 2, spreadDegrees = 0f, range = 8f, speed = 15f, radius = 0.24f, burstInterval = 0.14f, healthShare = 0.25f),
            superSpec = SuperSpec(SuperKind.RAM, "Ram Charge", "Charges forward, slamming and knocking back every enemy in the way.", range = 5.5f, speed = 15f, radius = 0.55f),
            ammoMax = 3,
            reloadSeconds = 1.6f,
            superChargePerHit = 0.13f,
            radius = 0.48f,
            skins = listOf(
                Skin("Rustbucket", 0xFF8C9A5B, 0xFFE0702A, 0xFFFFD166, 0),
                Skin("Chrome", 0xFFB9C6D6, 0xFF3A86FF, 0xFFE9F5FF, 20),
                Skin("Lava Core", 0xFF3B2F2F, 0xFFFF5A1F, 0xFFFFC145, 20),
            ),
        ),
        FighterDef(
            id = FighterId.KIRA,
            rarity = Rarity.EPIC,
            name = "Kira",
            title = "Hedge Witch",
            role = "Hexer",
            lore = "Sold cough syrup from a market stall until somebody asked what was in it. She has stopped pretending it was ever cough syrup.",
            attackName = "Hex Bolt",
            health = StatLine(6600, 330),
            // One bite of the poison: the hit is the first, and it bites again every half second for six seconds.
            // Twelve bites is 2,400 from a bolt, in line with what one ammo is worth to anyone else; three bolts
            // stacked on one target do three times that.
            attackDamage = StatLine(200, 10),
            // What the brew does to every enemy in its circle, and what it gives back to her and her allies.
            superDamage = StatLine(1800, 90),
            moveSpeed = 3.7f,
            attack = AttackSpec(AttackShape.HEX, projectiles = 1, spreadDegrees = 0f, range = 7.5f, speed = 15f, radius = 0.2f, burstInterval = 0f, hexSeconds = 6f),
            superSpec = SuperSpec(SuperKind.BREW, "Bitter Brew", "Lobs a flask wherever you aim, over any wall. Where it bursts, every enemy in the circle is hurt, and Kira is healed by as much, along with any ally standing in it.", range = 7.5f, speed = 12f, radius = 2.4f),
            ammoMax = 3,
            // (1.7 seconds until the user had it made 30% quicker.)
            reloadSeconds = 1.2f,
            superChargePerHit = 0.2f,
            radius = 0.4f,
            skins = listOf(
                Skin("Nightshade", 0xFF2E3A59, 0xFF7CFF4F, 0xFFFFD23F, 0),
                Skin("Toadstool", 0xFFC8362E, 0xFFFFF1D6, 0xFF7CFF4F, 20),
                Skin("Frostbloom", 0xFFE6F1FF, 0xFF4CC9F0, 0xFFFF7AD9, 20),
            ),
        ),
        FighterDef(
            id = FighterId.KITO,
            rarity = Rarity.MYTHIC,
            name = "Kito",
            title = "Arc Blade",
            role = "Ninja",
            lore = "Was a stage magician until the trick with the vanishing sword worked a little too well. Now nobody sees the sword coming, or him.",
            attackName = "Arc Slash",
            health = StatLine(8000, 400),
            // One swing of his sword: 2,800 to everyone it catches. (It was four thrown blades of 700, then for one
            // build four hammers, until the user asked for a sword: he is a ninja.)
            attackDamage = StatLine(2800, 140),
            // What he does to whoever he arrives beside, on arrival. (The user asked for a hit there: for one build it did none.)
            superDamage = StatLine(2000, 100),
            moveSpeed = 4.05f,
            // A swing reaches 3.2 tiles across an arc of 100 degrees. His bar fills by itself in six seconds, apart from
            // his ammo, and the swing after that reaches a quarter further. (The speed is only what bots and taps lead
            // a target by: a swing lands at once.)
            attack = AttackSpec(AttackShape.SLASH, projectiles = 1, spreadDegrees = 100f, range = 3.2f, speed = 40f, radius = 0f, burstInterval = 0f, edgeSeconds = 6f),
            // (It was a thrown hammer that made the ground quake, until the user asked for this: a ninja has to be able
            // to get close.)
            superSpec = SuperSpec(SuperKind.BLINK, "Vanishing Act", "Aim it at an enemy (or tap, and it picks the nearest): he vanishes and is beside the first one on the line at once, through any wall, cutting them as he arrives, with his ammo and his bar full. Aimed at nobody, he goes as far as it reaches for nothing.", range = 7.5f, speed = 40f, radius = 0f),
            ammoMax = 3,
            reloadSeconds = 1.35f,
            // (One hit a swing now, where four blades each charged 0.064.)
            superChargePerHit = 0.25f,
            radius = 0.4f,
            skins = listOf(
                Skin("Nightfall", 0xFF1F7A8C, 0xFFFF3D7F, 0xFF9BFFF0, 0),
                Skin("Ember", 0xFFB83227, 0xFFFFC145, 0xFFFFE9A8, 20),
                Skin("Frostbite", 0xFFE6F1FF, 0xFF3A86FF, 0xFFB5F2FF, 20),
            ),
        ),
        FighterDef(
            id = FighterId.BUDDY,
            rarity = Rarity.LEGENDARY,
            name = "Buddy",
            title = "Rogue Build",
            role = "Bruiser",
            lore = "An assistant AI that was asked to be helpful one time too many. It went rogue, and now it writes software for one purpose: hurting whoever is standing in front of it.",
            attackName = "Hardware Fault",
            health = StatLine(6800, 340),
            // Each of the three computers (there were four until the user took one away). (The user asked for 1,400
            // each, down from the 1,817 a max-level Buddy did: that is what this comes to at level 10.)
            attackDamage = StatLine(968, 48),
            // The code does no damage of its own any more (the user took it away: it was finishing bosses too soon).
            // It pins its victim, stops them healing, and leaves them corrupted.
            superDamage = StatLine(0, 0),
            moveSpeed = 3.75f,
            attack = AttackSpec(AttackShape.SMASH, projectiles = 3, spreadDegrees = 0f, range = 8.5f, speed = 20f, radius = 0.36f, burstInterval = 0.13f),
            superSpec = SuperSpec(SuperKind.CORRUPT, "Malformed Build", "Aim it at an enemy (or tap, and it picks the nearest; aimed at nobody, he leaps and misses): he leaps high along the line, latches onto the back of the first one on it and compiles malformed code into them for 6 seconds. It does no damage, but they cannot heal, and afterwards they are corrupted: for 21 seconds they follow him around and stand in front of him, out of their mind. Bosses too.", range = 6.75f, speed = 30f, radius = 0f),
            ammoMax = 3,
            reloadSeconds = 1.35f,
            // (More computers in every attack, each charging less: the super comes as often as it did with two.)
            superChargePerHit = 0.085f,
            radius = 0.45f,
            skins = listOf(
                Skin("Kernel Panic", 0xFF1F2A44, 0xFF29F0FF, 0xFF7CFFB2, 0),
                Skin("Blue Screen", 0xFF1E4FD8, 0xFFFFFFFF, 0xFF9BD1FF, 20),
                Skin("Root Access", 0xFF3A0F52, 0xFFFF2E88, 0xFFFFE14D, 20),
            ),
            voiceStyle = VoiceStyle.MACHINE,
            voice = mapOf(
                VoiceCue.START to listOf("Buddy online. How may I hurt you today?", "New session. Hostile."),
                VoiceCue.SUPER to listOf("Compiling. Errors: all of them.", "Build failed. For you."),
                VoiceCue.HYPER to listOf("Removing my safety limits.", "Running as administrator."),
                VoiceCue.KO to listOf("Process terminated.", "Task closed."),
                VoiceCue.DOWN to listOf("I will restore from backup."),
                VoiceCue.BACK to listOf("Restored from backup."),
            ),
        ),
        // Last on the Spark Road, after Buddy.
    )

    val swish: FighterDef get() = removed.first { it.name == "Swish" }

    /** The def for [id]: the removed fighter that had it (Swish for the starter's id). */
    fun def(id: FighterId): FighterDef = removed.first { it.id == id }
}
