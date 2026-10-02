package io.github.projectwip.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import io.github.projectwip.data.AttackShape
import io.github.projectwip.data.Settings
import io.github.projectwip.data.SuperKind
import io.github.projectwip.match.TouchControls
import io.github.projectwip.sim.Fighter
import io.github.projectwip.sim.GameEvent
import io.github.projectwip.sim.Match
import io.github.projectwip.sim.Phase
import io.github.projectwip.sim.Projectile
import io.github.projectwip.sim.ShotStyle
import io.github.projectwip.sim.Tile
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Draws one frame of a match. World geometry is drawn with a canvas transform in tile units;
 * text is drawn in screen space so it stays crisp at any zoom.
 */
class GameRenderer(private val density: Float) {
    private val art = FighterArt()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    private val textStroke = Paint(text).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; color = OUTLINE }
    private val path = Path()
    private val rect = RectF()

    // Camera (world units)
    private var camX = Float.NaN
    private var camY = 0f
    private var scale = 1f
    private var screenW = 1f
    private var screenH = 1f

    private val particles = Particles()
    private val floaters = ArrayList<Floater>()
    private val feed = ArrayList<FeedEntry>()
    private var shake = 0f
    private val rng = Random(7)
    private var renderTime = 0f
    private var countdownPop = 0f

    private class Floater(var x: Float, var y: Float, val text: String, val color: Int, var life: Float, val big: Boolean)
    private class FeedEntry(val killer: String, val killerTeam: Int, val victim: String, val victimTeam: Int, var life: Float)

    private fun dp(v: Float) = v * density

    // ------------------------------------------------------------------ events → effects

    fun onEvent(e: GameEvent, match: Match, settings: Settings) {
        val w = match.world
        val playerId = match.player.id
        when (e) {
            is GameEvent.Shot -> {
                val f = w.fighter(e.fighterId) ?: return
                val col = shotColor(f, e.isSuper)
                repeat(if (e.isSuper) 10 else 4) {
                    val a = atan2(e.dirY, e.dirX) + (rng.nextFloat() - 0.5f) * 0.9f
                    val sp = 2f + rng.nextFloat() * 4f
                    particles.add(e.x + e.dirX * 0.5f, e.y + e.dirY * 0.5f - 0.2f, cos(a) * sp, sin(a) * sp, 0.18f, 0.08f, col, Particles.DOT)
                }
            }
            is GameEvent.Hit -> {
                val target = w.fighter(e.targetId)
                val col = if (target?.team == match.player.team) Color.rgb(255, 90, 90) else Color.rgb(255, 230, 120)
                repeat(7) {
                    val a = rng.nextFloat() * 6.28f
                    val sp = 2f + rng.nextFloat() * 5f
                    particles.add(e.x, e.y - 0.3f, cos(a) * sp, sin(a) * sp, 0.25f, 0.07f, col, Particles.DOT)
                }
                if (settings.showDamageNumbers && (e.sourceId == playerId || e.targetId == playerId)) {
                    val mine = e.sourceId == playerId
                    floaters += Floater(e.x + (rng.nextFloat() - 0.5f) * 0.4f, e.y - 1.0f, e.damage.toString(),
                        if (mine) Color.WHITE else Color.rgb(255, 92, 92), 0.75f, e.isSuper)
                }
                if (e.targetId == playerId) shake = max(shake, 0.12f)
            }
            is GameEvent.Blocked -> particles.add(e.x, e.y - 0.3f, 0f, 0f, 0.3f, 0.4f, Color.rgb(140, 230, 255), Particles.RING)
            is GameEvent.WallHit -> repeat(5) {
                val a = rng.nextFloat() * 6.28f
                particles.add(e.x, e.y, cos(a) * 2.5f, sin(a) * 2.5f, 0.2f, 0.06f, Color.rgb(230, 220, 200), Particles.DOT)
            }
            is GameEvent.Ko -> {
                val victim = w.fighter(e.victimId)
                val killer = w.fighter(e.killerId)
                val col = victim?.def?.skins?.get(victim.skin)?.primary?.toInt() ?: Color.WHITE
                particles.add(e.x, e.y - 0.3f, 0f, 0f, 0.5f, 1.4f, Color.WHITE, Particles.RING)
                repeat(22) {
                    val a = rng.nextFloat() * 6.28f
                    val sp = 3f + rng.nextFloat() * 6f
                    particles.add(e.x, e.y - 0.3f, cos(a) * sp, sin(a) * sp, 0.5f + rng.nextFloat() * 0.3f, 0.1f, col, Particles.DOT)
                }
                repeat(6) {
                    particles.add(e.x + rng.nextFloat() - 0.5f, e.y - 0.2f, (rng.nextFloat() - 0.5f), -1f - rng.nextFloat(), 0.9f, 0.3f, Color.argb(160, 60, 50, 80), Particles.SMOKE)
                }
                if (victim != null) feed.add(0, FeedEntry(killer?.name ?: "—", killer?.team ?: -1, victim.name, victim.team, 4.5f))
                while (feed.size > 4) feed.removeAt(feed.lastIndex)
                if (e.victimId == playerId) shake = 0.3f
            }
            is GameEvent.Spawned -> {
                val f = w.fighter(e.fighterId) ?: return
                particles.add(f.x, f.y - 0.3f, 0f, 0f, 0.45f, 1.1f, teamColor(f, match), Particles.RING)
            }
            is GameEvent.Dash -> shake = max(shake, 0.1f)
            is GameEvent.CountdownTick -> countdownPop = 1f
            is GameEvent.MatchStart -> countdownPop = 1f
            else -> Unit
        }
    }

    private fun shotColor(f: Fighter, isSuper: Boolean): Int = when {
        isSuper -> Color.rgb(255, 214, 64)
        else -> f.def.skins[f.skin].accent.toInt()
    }

    private fun teamColor(f: Fighter, m: Match) = when {
        f === m.player -> PLAYER
        f.team == m.player.team -> ALLY
        else -> ENEMY
    }

    // ------------------------------------------------------------------ frame

    fun draw(c: Canvas, match: Match, alpha: Float, dt: Float, controls: TouchControls, input: TouchControls.Input, settings: Settings, fps: Int) {
        renderTime += dt
        screenW = c.width.toFloat()
        screenH = c.height.toFloat()
        scale = min(screenH / VIEW_TILES_H, screenW / VIEW_TILES_W)
        val world = match.world
        val player = match.player
        val arena = world.arena

        // ---- camera
        val px = lerp(player.prevX, player.x, alpha)
        val py = lerp(player.prevY, player.y, alpha)
        var tx = px
        var ty = py
        if (input.aimingAttack || input.aimingSuper) { tx += input.aimX * 1.6f; ty += input.aimY * 1.2f }
        val halfW = screenW / scale / 2
        val halfH = screenH / scale / 2
        tx = clampCam(tx, halfW, arena.width.toFloat())
        ty = clampCam(ty, halfH, arena.height.toFloat())
        if (camX.isNaN()) { camX = tx; camY = ty }
        val k = 1f - exp(-dt * 9f)
        camX += (tx - camX) * k
        camY += (ty - camY) * k
        shake = max(0f, shake - dt)
        val sx = if (shake > 0f) (rng.nextFloat() - 0.5f) * shake * 0.6f else 0f
        val sy = if (shake > 0f) (rng.nextFloat() - 0.5f) * shake * 0.6f else 0f

        c.drawColor(BACKDROP)
        c.save()
        c.translate(screenW / 2, screenH / 2)
        c.scale(scale, scale)
        c.translate(-camX + sx, -camY + sy)

        val x0 = floor(camX - halfW - 1).toInt().coerceAtLeast(-1)
        val x1 = floor(camX + halfW + 1).toInt().coerceAtMost(arena.width)
        val y0 = floor(camY - halfH - 1).toInt().coerceAtLeast(-1)
        val y1 = floor(camY + halfH + 2).toInt().coerceAtMost(arena.height)

        drawGround(c, match, x0, x1, y0, y1)
        drawAimIndicator(c, match, input, px, py)

        // ---- depth-sorted walls + fighters
        val sorted = world.fighters.filter { it.alive }.sortedBy { lerp(it.prevY, it.y, alpha) }
        var fi = 0
        for (ty2 in y0..y1) {
            while (fi < sorted.size && lerp(sorted[fi].prevY, sorted[fi].y, alpha) + 0.3f < ty2 + 1f) {
                drawFighter(c, match, sorted[fi], alpha); fi++
            }
            for (tx2 in x0..x1) if (arena[tx2, ty2] == Tile.WALL) drawWall(c, tx2, ty2, arena.width, arena.height)
        }
        while (fi < sorted.size) { drawFighter(c, match, sorted[fi], alpha); fi++ }

        drawThicketTops(c, match, x0, x1, y0, y1)
        for (p in world.projectiles) drawProjectile(c, p, alpha, world.fighter(p.ownerId))
        particles.updateAndDraw(c, dt, fill, stroke)
        c.restore()

        // ---- screen-space overlays
        for (f in sorted) drawOverhead(c, match, f, alpha)
        drawFloaters(c, dt)
        drawHud(c, match, dt, settings, fps)
        if (player.alive && world.phase == Phase.PLAYING) {
            controls.draw(c, player.ammo, player.def.ammoMax, player.superCharge, true, renderTime)
        } else if (world.phase != Phase.ENDED) {
            controls.draw(c, 0f, player.def.ammoMax, player.superCharge, false, renderTime)
        }
    }

    private fun clampCam(t: Float, half: Float, size: Float): Float {
        val margin = 0.6f
        return if (half * 2 >= size + margin * 2) size / 2 else t.coerceIn(half - margin, size - half + margin)
    }

    private fun worldToScreenX(x: Float) = (x - camX) * scale + screenW / 2
    private fun worldToScreenY(y: Float) = (y - camY) * scale + screenH / 2

    // ------------------------------------------------------------------ ground

    private fun drawGround(c: Canvas, m: Match, x0: Int, x1: Int, y0: Int, y1: Int) {
        val a = m.world.arena
        // Arena base + rim
        fill.color = RIM
        c.drawRoundRect(-0.35f, -0.35f, a.width + 0.35f, a.height + 0.35f, 0.4f, 0.4f, fill)
        for (y in y0..y1) for (x in x0..x1) {
            if (x < 0 || y < 0 || x >= a.width || y >= a.height) continue
            when (a[x, y]) {
                Tile.WATER -> drawWater(c, a, x, y)
                Tile.THICKET -> {
                    fill.color = if ((x + y) % 2 == 0) GRASS_BASE else GRASS_BASE2
                    c.drawRect(x.toFloat(), y.toFloat(), x + 1f, y + 1f, fill)
                }
                else -> {
                    fill.color = if ((x + y) % 2 == 0) FLOOR_A else FLOOR_B
                    c.drawRect(x.toFloat(), y.toFloat(), x + 1.002f, y + 1.002f, fill)
                    // Team zones
                    if (x < 3 || x >= a.width - 3) {
                        fill.color = if (x < 3) ZONE_BLUE else ZONE_RED
                        c.drawRect(x.toFloat(), y.toFloat(), x + 1.002f, y + 1.002f, fill)
                    }
                    // Deterministic decals: bolts and cracks make the floor less flat.
                    val h = hash(x, y)
                    if (h % 7 == 0) {
                        fill.color = DECAL
                        c.drawCircle(x + 0.3f + (h % 5) * 0.1f, y + 0.35f + (h % 3) * 0.15f, 0.06f, fill)
                        c.drawCircle(x + 0.62f, y + 0.7f, 0.045f, fill)
                    } else if (h % 11 == 0) {
                        stroke.color = DECAL; stroke.strokeWidth = 0.035f
                        c.drawLine(x + 0.2f, y + 0.3f, x + 0.45f, y + 0.5f, stroke)
                        c.drawLine(x + 0.45f, y + 0.5f, x + 0.4f, y + 0.8f, stroke)
                    }
                }
            }
        }
        // Centre line
        stroke.color = Color.argb(70, 255, 255, 255); stroke.strokeWidth = 0.08f
        c.drawLine(a.width / 2f, 0.2f, a.width / 2f, a.height - 0.2f, stroke)
        c.drawCircle(a.width / 2f, a.height / 2f, 1.6f, stroke)
    }

    private fun drawWater(c: Canvas, a: io.github.projectwip.sim.Arena, x: Int, y: Int) {
        fill.color = WATER
        c.drawRect(x.toFloat(), y.toFloat(), x + 1.002f, y + 1.002f, fill)
        // Inner shading where the pool meets ground
        fill.color = WATER_DEEP
        if (a[x, y - 1] != Tile.WATER) c.drawRect(x.toFloat(), y.toFloat(), x + 1f, y + 0.22f, fill)
        stroke.color = Color.argb(150, 200, 240, 255); stroke.strokeWidth = 0.05f
        val t = renderTime
        val off = ((t * 0.4f + hash(x, y) % 10 * 0.1f) % 1f)
        c.drawLine(x + 0.15f + off * 0.3f, y + 0.45f, x + 0.45f + off * 0.3f, y + 0.45f, stroke)
        c.drawLine(x + 0.5f - off * 0.2f, y + 0.75f, x + 0.75f - off * 0.2f, y + 0.75f, stroke)
        // Rim
        stroke.color = Color.argb(200, 230, 250, 255); stroke.strokeWidth = 0.06f
        if (a[x, y - 1] != Tile.WATER) c.drawLine(x.toFloat(), y.toFloat(), x + 1f, y.toFloat(), stroke)
        if (a[x, y + 1] != Tile.WATER) c.drawLine(x.toFloat(), y + 1f, x + 1f, y + 1f, stroke)
        if (a[x - 1, y] != Tile.WATER) c.drawLine(x.toFloat(), y.toFloat(), x.toFloat(), y + 1f, stroke)
        if (a[x + 1, y] != Tile.WATER) c.drawLine(x + 1f, y.toFloat(), x + 1f, y + 1f, stroke)
    }

    private fun drawWall(c: Canvas, x: Int, y: Int, w: Int, h: Int) {
        if (x < 0 || y < 0 || x >= w || y >= h) return
        val top = y - WALL_H
        // Front face
        fill.color = WALL_FRONT
        c.drawRect(x.toFloat(), y + 1f - WALL_H, x + 1f, y + 1f, fill)
        // Top face
        fill.color = if (hash(x, y) % 3 == 0) WALL_TOP2 else WALL_TOP
        c.drawRect(x.toFloat(), top, x + 1f, y + 1f - WALL_H, fill)
        // Bevel highlight + crate detail
        stroke.color = WALL_LIGHT; stroke.strokeWidth = 0.05f
        c.drawLine(x + 0.08f, top + 0.08f, x + 0.92f, top + 0.08f, stroke)
        stroke.color = WALL_DETAIL; stroke.strokeWidth = 0.05f
        c.drawRect(x + 0.18f, top + 0.2f, x + 0.82f, y + 0.82f - WALL_H, stroke)
        fill.color = WALL_DETAIL
        c.drawCircle(x + 0.18f, top + 0.2f, 0.05f, fill); c.drawCircle(x + 0.82f, top + 0.2f, 0.05f, fill)
        c.drawCircle(x + 0.18f, y + 0.82f - WALL_H, 0.05f, fill); c.drawCircle(x + 0.82f, y + 0.82f - WALL_H, 0.05f, fill)
        // Outline
        stroke.color = OUTLINE; stroke.strokeWidth = 0.05f
        c.drawRect(x.toFloat(), top, x + 1f, y + 1f, stroke)
    }

    private fun drawThicketTops(c: Canvas, m: Match, x0: Int, x1: Int, y0: Int, y1: Int) {
        val a = m.world.arena
        val p = m.player
        for (y in y0..y1) for (x in x0..x1) {
            if (a[x, y] != Tile.THICKET || x < 0 || y < 0 || x >= a.width || y >= a.height) continue
            // See-through near friendly fighters so you can tell where you are.
            val near = m.world.fighters.any { it.alive && it.team == p.team && hypot(it.x - (x + 0.5f), it.y - (y + 0.5f)) < 1.6f }
            val alpha = if (near) 110 else 255
            val sway = sin(renderTime * 1.8f + x * 0.7f + y * 0.3f) * 0.04f
            for (i in 0..2) {
                val bx = x + 0.18f + i * 0.32f
                val by = y + 0.95f - (i % 2) * 0.35f
                path.reset()
                path.moveTo(bx - 0.26f, by)
                path.lineTo(bx - 0.16f + sway, by - 0.62f)
                path.lineTo(bx - 0.05f, by - 0.3f)
                path.lineTo(bx + 0.04f + sway, by - 0.78f)
                path.lineTo(bx + 0.12f, by - 0.32f)
                path.lineTo(bx + 0.24f + sway, by - 0.58f)
                path.lineTo(bx + 0.28f, by)
                path.close()
                fill.color = Color.argb(alpha, if (i == 1) 88 else 63, if (i == 1) 196 else 174, if (i == 1) 110 else 92)
                c.drawPath(path, fill)
                stroke.color = Color.argb(alpha, 24, 80, 44); stroke.strokeWidth = 0.04f
                c.drawPath(path, stroke)
            }
        }
    }

    // ------------------------------------------------------------------ fighters

    private fun drawFighter(c: Canvas, m: Match, f: Fighter, alpha: Float) {
        val w = m.world
        if (!f.alive) return
        val visible = w.isVisibleTo(f, m.player.team)
        if (!visible) return
        val x = lerp(f.prevX, f.x, alpha)
        val y = lerp(f.prevY, f.y, alpha)
        val hidden = w.arena.inThicket(f.x, f.y) && f.team == m.player.team && f.revealTimer <= 0f
        val col = teamColor(f, m)

        // Team ring
        stroke.color = col; stroke.strokeWidth = 0.07f
        rect.set(x - f.radius * 1.15f, y + f.radius * 0.25f, x + f.radius * 1.15f, y + f.radius * 0.95f)
        c.drawOval(rect, stroke)
        if (f === m.player) {
            fill.color = Color.argb(60, 92, 255, 122)
            c.drawOval(rect, fill)
        }
        // Super-ready aura
        if (f.superReady) {
            stroke.color = Color.argb(200, 255, 214, 64); stroke.strokeWidth = 0.05f
            val r = f.radius * 1.5f + sin(renderTime * 8f) * 0.05f
            c.drawCircle(x, y - 0.3f, r, stroke)
        }
        val size = f.radius * 1.15f
        art.draw(c, x, y - size * 0.25f, size, f.def, f.skin, f.facing, if (hypot(f.vx, f.vy) > 0.2f) f.walkCycle else 0f,
            renderTime + f.id, (f.hitFlash / 0.12f).coerceIn(0f, 1f), if (hidden) 140 else 255)
        if (f.shield > 0f) {
            fill.color = Color.argb(60, 140, 230, 255)
            c.drawCircle(x, y - 0.3f, f.radius * 1.6f, fill)
            stroke.color = Color.argb(170, 180, 240, 255); stroke.strokeWidth = 0.05f
            c.drawCircle(x, y - 0.3f, f.radius * 1.6f, stroke)
        }
        if (f.isDashing) {
            repeat(2) { particles.add(x - f.dashDirX * 0.4f, y, -f.dashDirX * 2f, -f.dashDirY * 2f, 0.3f, 0.18f, Color.argb(150, 230, 220, 200), Particles.SMOKE) }
        }
    }

    private fun drawOverhead(c: Canvas, m: Match, f: Fighter, alpha: Float) {
        if (!f.alive || !m.world.isVisibleTo(f, m.player.team)) return
        val x = worldToScreenX(lerp(f.prevX, f.x, alpha))
        val y = worldToScreenY(lerp(f.prevY, f.y, alpha) - f.radius * 1.15f * 1.95f)
        val bw = scale * 1.15f
        val bh = max(dp(9f), scale * 0.16f)
        val col = teamColor(f, m)
        // Name
        text.textSize = max(dp(11f), scale * 0.2f)
        outlinedText(c, f.name, x, y - bh - dp(4f), if (f === m.player) Color.rgb(255, 245, 160) else Color.WHITE, dp(3f))
        // HP bar
        rect.set(x - bw / 2, y - bh, x + bw / 2, y)
        fill.color = OUTLINE
        c.drawRoundRect(rect.left - dp(2f), rect.top - dp(2f), rect.right + dp(2f), rect.bottom + dp(2f), bh, bh, fill)
        fill.color = Color.argb(255, 50, 30, 70)
        c.drawRoundRect(rect, bh / 2, bh / 2, fill)
        fill.color = col
        val fr = f.hpFraction.coerceIn(0f, 1f)
        if (fr > 0f) c.drawRoundRect(rect.left, rect.top, rect.left + bw * fr, rect.bottom, bh / 2, bh / 2, fill)
        text.textSize = bh * 0.95f
        outlinedText(c, f.hp.toString(), x, rect.bottom - bh * 0.14f, Color.WHITE, dp(2.5f))
        // Ammo (player only)
        if (f === m.player) {
            val segW = (bw - dp(4f)) / f.def.ammoMax
            val ay = rect.bottom + dp(4f)
            for (i in 0 until f.def.ammoMax) {
                val l = rect.left + i * (segW + dp(2f))
                fill.color = OUTLINE
                c.drawRect(l - dp(1f), ay - dp(1f), l + segW + dp(1f), ay + dp(6f), fill)
                val a = (f.ammo - i).coerceIn(0f, 1f)
                fill.color = if (a >= 1f) Color.rgb(255, 159, 28) else Color.rgb(140, 110, 90)
                c.drawRect(l, ay, l + segW * a, ay + dp(5f), fill)
            }
        }
    }

    private fun drawAimIndicator(c: Canvas, m: Match, input: TouchControls.Input, px: Float, py: Float) {
        val p = m.player
        if (!p.alive || !(input.aimingAttack || input.aimingSuper)) return
        val len = hypot(input.aimX, input.aimY)
        if (len < 0.01f) return
        val dx = input.aimX / len
        val dy = input.aimY / len
        val isSuper = input.aimingSuper
        val col = if (isSuper) Color.argb(110, 255, 214, 64) else Color.argb(90, 255, 255, 255)
        val edge = if (isSuper) Color.argb(220, 255, 214, 64) else Color.argb(170, 255, 255, 255)
        c.save()
        c.translate(px, py)
        c.rotate(Math.toDegrees(atan2(dy, dx).toDouble()).toFloat())
        val arena = m.world.arena
        if (isSuper) {
            val s = p.def.superSpec
            when (s.kind) {
                SuperKind.VOLLEY -> cone(c, s.range, s.spreadDegrees, col, edge)
                SuperKind.PIERCE -> bar(c, wallClip(arena, px, py, dx, dy, s.range), s.radius * 1.6f, col, edge)
                SuperKind.RAM -> bar(c, s.range, p.radius * 1.1f, col, edge)
            }
        } else {
            val a = p.def.attack
            when (a.shape) {
                AttackShape.SPREAD -> cone(c, a.range, a.spreadDegrees, col, edge)
                else -> bar(c, wallClip(arena, px, py, dx, dy, a.range), if (a.shape == AttackShape.BURST) 0.3f else 0.18f, col, edge)
            }
        }
        c.restore()
    }

    private fun wallClip(a: io.github.projectwip.sim.Arena, x: Float, y: Float, dx: Float, dy: Float, range: Float): Float {
        val hit = a.shotBlockedAt(x, y, x + dx * range, y + dy * range)
        return if (hit < 0f) range else max(0.4f, hit)
    }

    private fun bar(c: Canvas, length: Float, halfWidth: Float, col: Int, edge: Int) {
        fill.color = col
        rect.set(0.3f, -halfWidth, length, halfWidth)
        c.drawRoundRect(rect, halfWidth, halfWidth, fill)
        stroke.color = edge; stroke.strokeWidth = 0.05f
        c.drawRoundRect(rect, halfWidth, halfWidth, stroke)
    }

    private fun cone(c: Canvas, range: Float, spreadDeg: Float, col: Int, edge: Int) {
        rect.set(-range, -range, range, range)
        fill.color = col
        c.drawArc(rect, -spreadDeg / 2 - 4f, spreadDeg + 8f, true, fill)
        stroke.color = edge; stroke.strokeWidth = 0.05f
        c.drawArc(rect, -spreadDeg / 2 - 4f, spreadDeg + 8f, true, stroke)
    }

    // ------------------------------------------------------------------ projectiles

    private fun drawProjectile(c: Canvas, p: Projectile, alpha: Float, owner: Fighter?) {
        val x = lerp(p.prevX, p.x, alpha)
        val y = lerp(p.prevY, p.y, alpha) - 0.3f
        val sp = hypot(p.vx, p.vy)
        val dx = p.vx / sp
        val dy = p.vy / sp
        val accent = owner?.def?.skins?.get(owner.skin)?.accent?.toInt() ?: Color.WHITE
        val secondary = owner?.def?.skins?.get(owner.skin)?.secondary?.toInt() ?: Color.WHITE
        when (p.style) {
            ShotStyle.SPARK, ShotStyle.VOLLEY -> {
                val r = p.radius * if (p.style == ShotStyle.VOLLEY) 1.2f else 1f
                val tail = min(p.age * sp, 0.9f)
                stroke.color = Color.argb(140, Color.red(accent), Color.green(accent), Color.blue(accent)); stroke.strokeWidth = r * 1.6f
                c.drawLine(x - dx * tail, y - dy * tail, x, y, stroke)
                fill.color = if (p.style == ShotStyle.VOLLEY) Color.rgb(255, 214, 64) else accent
                c.drawCircle(x, y, r * 1.15f, fill)
                fill.color = Color.WHITE
                c.drawCircle(x, y, r * 0.55f, fill)
            }
            ShotStyle.PELLET -> {
                fill.color = OUTLINE
                c.drawCircle(x, y, p.radius * 1.1f, fill)
                fill.color = secondary
                c.drawCircle(x, y, p.radius * 0.85f, fill)
                fill.color = Color.argb(200, 255, 240, 200)
                c.drawCircle(x - p.radius * 0.25f, y - p.radius * 0.25f, p.radius * 0.3f, fill)
            }
            ShotStyle.PRISM, ShotStyle.LANCE -> {
                val big = p.style == ShotStyle.LANCE
                val l = if (big) 1.1f else 0.55f
                val wdt = if (big) p.radius * 0.9f else p.radius * 0.8f
                val tail = min(p.age * sp, if (big) 2.2f else 1.4f)
                stroke.color = Color.argb(120, Color.red(secondary), Color.green(secondary), Color.blue(secondary)); stroke.strokeWidth = wdt * 1.2f
                c.drawLine(x - dx * tail, y - dy * tail, x, y, stroke)
                c.save()
                c.translate(x, y)
                c.rotate(Math.toDegrees(atan2(dy, dx).toDouble()).toFloat())
                path.reset()
                path.moveTo(l * 0.5f, 0f); path.lineTo(0f, -wdt); path.lineTo(-l * 0.5f, 0f); path.lineTo(0f, wdt); path.close()
                fill.color = secondary
                c.drawPath(path, fill)
                stroke.color = Color.WHITE; stroke.strokeWidth = 0.04f
                c.drawPath(path, stroke)
                c.restore()
            }
        }
    }

    // ------------------------------------------------------------------ floating text

    private fun drawFloaters(c: Canvas, dt: Float) {
        val it = floaters.iterator()
        while (it.hasNext()) {
            val f = it.next()
            f.life -= dt
            f.y -= dt * 1.4f
            if (f.life <= 0f) { it.remove(); continue }
            val pop = if (f.life > 0.6f) 1f + (f.life - 0.6f) * 3f else 1f
            text.textSize = (if (f.big) dp(26f) else dp(19f)) * pop
            val a = (f.life / 0.3f).coerceIn(0f, 1f)
            val col = Color.argb((a * 255).toInt(), Color.red(f.color), Color.green(f.color), Color.blue(f.color))
            textStroke.alpha = (a * 255).toInt()
            outlinedText(c, f.text, worldToScreenX(f.x), worldToScreenY(f.y), col, dp(4f))
            textStroke.alpha = 255
        }
    }

    // ------------------------------------------------------------------ HUD

    private fun drawHud(c: Canvas, m: Match, dt: Float, settings: Settings, fps: Int) {
        val w = m.world
        val cx = screenW / 2
        val top = dp(10f)
        val panelW = dp(250f)
        val panelH = dp(54f)
        // Score panel (chamfered plate)
        chamferRect(c, cx - panelW / 2, top, cx + panelW / 2, top + panelH, dp(12f), PANEL, OUTLINE)
        val boxW = dp(70f)
        chamferRect(c, cx - panelW / 2 + dp(6f), top + dp(6f), cx - panelW / 2 + dp(6f) + boxW, top + panelH - dp(6f), dp(8f), ALLY_DARK, null)
        chamferRect(c, cx + panelW / 2 - dp(6f) - boxW, top + dp(6f), cx + panelW / 2 - dp(6f), top + panelH - dp(6f), dp(8f), ENEMY_DARK, null)
        val blueScore = w.score[m.player.team]
        val redScore = w.score[1 - m.player.team]
        text.textSize = dp(28f)
        outlinedText(c, blueScore.toString(), cx - panelW / 2 + dp(6f) + boxW / 2, top + panelH / 2 + dp(10f), Color.WHITE, dp(4f))
        outlinedText(c, redScore.toString(), cx + panelW / 2 - dp(6f) - boxW / 2, top + panelH / 2 + dp(10f), Color.WHITE, dp(4f))
        val secs = kotlin.math.ceil(w.timeLeft).toInt()
        text.textSize = dp(22f)
        outlinedText(c, "%d:%02d".format(secs / 60, secs % 60), cx, top + dp(27f), if (secs <= 15 && w.phase == Phase.PLAYING) Color.rgb(255, 120, 100) else Color.WHITE, dp(3.5f))
        text.textSize = dp(11f)
        outlinedText(c, "FIRST TO ${w.rules.koTarget}", cx, top + dp(44f), Color.rgb(255, 214, 64), dp(3f))

        // Kill feed
        var fy = top + dp(16f)
        text.textAlign = Paint.Align.RIGHT
        text.textSize = dp(14f)
        val it = feed.iterator()
        while (it.hasNext()) {
            val e = it.next()
            e.life -= dt
            if (e.life <= 0f) { it.remove(); continue }
            val a = (e.life / 0.5f).coerceIn(0f, 1f)
            val right = screenW - dp(18f)
            val victimCol = if (e.victimTeam == m.player.team) ALLY else ENEMY
            val killerCol = if (e.killerTeam == m.player.team) ALLY else ENEMY
            textStroke.alpha = (a * 255).toInt()
            val vw = text.measureText(e.victim)
            outlinedText(c, e.victim, right, fy, withAlpha(victimCol, a), dp(3f))
            outlinedText(c, " ✕ ", right - vw, fy, withAlpha(Color.WHITE, a), dp(3f))
            outlinedText(c, e.killer, right - vw - text.measureText(" ✕ "), fy, withAlpha(killerCol, a), dp(3f))
            textStroke.alpha = 255
            fy += dp(20f)
        }
        text.textAlign = Paint.Align.CENTER

        // Countdown / start
        countdownPop = max(0f, countdownPop - dt * 2.2f)
        val bigPop = 1f + countdownPop * 0.6f
        if (w.phase == Phase.COUNTDOWN) {
            val n = kotlin.math.ceil(w.rules.countdownSeconds - w.phaseTime).toInt().coerceAtLeast(1)
            text.textSize = dp(84f) * bigPop
            outlinedText(c, n.toString(), cx, screenH * 0.45f, Color.WHITE, dp(8f))
            text.textSize = dp(20f)
            outlinedText(c, "KNOCKOUT RUSH · FIRST TO ${w.rules.koTarget} KOs", cx, screenH * 0.45f + dp(44f), Color.rgb(255, 214, 64), dp(4f))
        } else if (w.phase == Phase.PLAYING && w.phaseTime < 0.9f) {
            text.textSize = dp(72f) * bigPop
            outlinedText(c, "FIGHT!", cx, screenH * 0.45f, Color.rgb(255, 159, 28), dp(8f))
        }

        // Respawn notice
        val p = m.player
        if (!p.alive && w.phase == Phase.PLAYING) {
            text.textSize = dp(26f)
            outlinedText(c, "KNOCKED OUT", cx, screenH * 0.42f, Color.rgb(255, 92, 92), dp(5f))
            text.textSize = dp(18f)
            outlinedText(c, "Back in ${kotlin.math.ceil(p.respawnTimer).toInt().coerceAtLeast(1)}…", cx, screenH * 0.42f + dp(30f), Color.WHITE, dp(4f))
        }

        // End banner
        if (w.phase == Phase.ENDED) {
            val t = w.phaseTime
            val slide = (t / 0.35f).coerceIn(0f, 1f)
            val (label, col) = when (w.winningTeam) {
                p.team -> "VICTORY!" to Color.rgb(255, 214, 64)
                -1 -> "DRAW" to Color.WHITE
                else -> "DEFEAT" to Color.rgb(255, 92, 92)
            }
            fill.color = Color.argb((160 * slide).toInt(), 10, 5, 30)
            c.drawRect(0f, screenH * 0.36f, screenW, screenH * 0.36f + dp(110f), fill)
            text.textSize = dp(78f) * (1.4f - 0.4f * slide)
            textStroke.alpha = (255 * slide).toInt()
            outlinedText(c, label, cx, screenH * 0.36f + dp(80f), withAlpha(col, slide), dp(9f))
            textStroke.alpha = 255
        }

        if (settings.showFps) {
            text.textAlign = Paint.Align.LEFT
            text.textSize = dp(12f)
            outlinedText(c, "$fps FPS", dp(76f), dp(30f), Color.rgb(160, 255, 160), dp(3f))
            text.textAlign = Paint.Align.CENTER
        }
    }

    private fun chamferRect(c: Canvas, l: Float, t: Float, r: Float, b: Float, cut: Float, color: Int, outline: Int?) {
        path.reset()
        path.moveTo(l + cut, t); path.lineTo(r - cut, t); path.lineTo(r, t + cut); path.lineTo(r, b - cut)
        path.lineTo(r - cut, b); path.lineTo(l + cut, b); path.lineTo(l, b - cut); path.lineTo(l, t + cut); path.close()
        fill.color = color
        c.drawPath(path, fill)
        if (outline != null) {
            stroke.color = outline; stroke.strokeWidth = dp(3f)
            c.drawPath(path, stroke)
        }
    }

    private fun outlinedText(c: Canvas, s: String, x: Float, y: Float, color: Int, outline: Float) {
        textStroke.textSize = text.textSize
        textStroke.textAlign = text.textAlign
        textStroke.strokeWidth = outline
        c.drawText(s, x, y, textStroke)
        text.color = color
        c.drawText(s, x, y, text)
    }

    private fun withAlpha(c: Int, a: Float) = Color.argb((a * 255).toInt().coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))

    private fun hash(x: Int, y: Int): Int {
        var h = x * 374761393 + y * 668265263
        h = (h xor (h ushr 13)) * 1274126177
        return (h xor (h ushr 16)) and 0x7fffffff
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    companion object {
        /** The camera shows at least this many tiles vertically / horizontally. */
        const val VIEW_TILES_H = 13.5f
        const val VIEW_TILES_W = 22f
        const val WALL_H = 0.34f

        val OUTLINE = Color.rgb(27, 16, 53)
        val BACKDROP = Color.rgb(28, 20, 58)
        val RIM = Color.rgb(64, 48, 110)
        val FLOOR_A = Color.rgb(222, 196, 146)
        val FLOOR_B = Color.rgb(213, 186, 137)
        val DECAL = Color.argb(60, 90, 60, 30)
        val ZONE_BLUE = Color.argb(46, 63, 182, 255)
        val ZONE_RED = Color.argb(46, 255, 77, 94)
        val GRASS_BASE = Color.rgb(52, 142, 78)
        val GRASS_BASE2 = Color.rgb(48, 134, 74)
        val WATER = Color.rgb(44, 160, 222)
        val WATER_DEEP = Color.rgb(30, 118, 186)
        val WALL_TOP = Color.rgb(120, 112, 196)
        val WALL_TOP2 = Color.rgb(112, 104, 186)
        val WALL_FRONT = Color.rgb(70, 60, 140)
        val WALL_LIGHT = Color.argb(170, 200, 196, 255)
        val WALL_DETAIL = Color.argb(120, 40, 30, 90)
        val PANEL = Color.argb(230, 34, 22, 84)
        val PLAYER = Color.rgb(92, 255, 122)
        val ALLY = Color.rgb(63, 182, 255)
        val ENEMY = Color.rgb(255, 77, 94)
        val ALLY_DARK = Color.rgb(28, 110, 200)
        val ENEMY_DARK = Color.rgb(200, 40, 64)
    }
}

/** Tiny pooled particle system (cosmetic only, advanced by render time). */
private class Particles {
    private val max = 600
    private val x = FloatArray(max)
    private val y = FloatArray(max)
    private val vx = FloatArray(max)
    private val vy = FloatArray(max)
    private val life = FloatArray(max)
    private val maxLife = FloatArray(max)
    private val size = FloatArray(max)
    private val color = IntArray(max)
    private val type = IntArray(max)
    private var count = 0

    fun add(px: Float, py: Float, pvx: Float, pvy: Float, l: Float, s: Float, c: Int, t: Int) {
        if (count >= max) return
        x[count] = px; y[count] = py; vx[count] = pvx; vy[count] = pvy
        life[count] = l; maxLife[count] = l; size[count] = s; color[count] = c; type[count] = t
        count++
    }

    fun updateAndDraw(c: Canvas, dt: Float, fill: Paint, stroke: Paint) {
        var i = 0
        while (i < count) {
            life[i] -= dt
            if (life[i] <= 0f) {
                count--
                x[i] = x[count]; y[i] = y[count]; vx[i] = vx[count]; vy[i] = vy[count]
                life[i] = life[count]; maxLife[i] = maxLife[count]; size[i] = size[count]; color[i] = color[count]; type[i] = type[count]
                continue
            }
            x[i] += vx[i] * dt
            y[i] += vy[i] * dt
            vx[i] *= 0.9f
            vy[i] *= 0.9f
            val f = life[i] / maxLife[i]
            val a = (Color.alpha(color[i]) * f).toInt()
            when (type[i]) {
                DOT -> {
                    fill.color = Color.argb(a, Color.red(color[i]), Color.green(color[i]), Color.blue(color[i]))
                    c.drawCircle(x[i], y[i], size[i] * (0.4f + f * 0.6f), fill)
                }
                RING -> {
                    stroke.color = Color.argb(a, Color.red(color[i]), Color.green(color[i]), Color.blue(color[i]))
                    stroke.strokeWidth = 0.08f * f + 0.02f
                    c.drawCircle(x[i], y[i], size[i] * (1.1f - f), stroke)
                }
                SMOKE -> {
                    fill.color = Color.argb(a, Color.red(color[i]), Color.green(color[i]), Color.blue(color[i]))
                    c.drawCircle(x[i], y[i], size[i] * (1.6f - f), fill)
                }
            }
            i++
        }
    }

    companion object {
        const val DOT = 0
        const val RING = 1
        const val SMOKE = 2
    }
}
