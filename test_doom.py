"""Offline tests for the DOOM engine + bot command vocabulary.

Run:  python -m pytest test_doom.py -q     (or: python test_doom.py)
No BOT_TOKEN / network needed.
"""
import math

from doom_engine import LEVELS, Game


def test_levels_load_and_render_fits_telegram():
    for i in range(len(LEVELS)):
        g = Game()
        g.load_level(i)
        assert g.enemies, f"level {i} has no enemies"
        frame = g.render(fps=5)
        assert len(frame) < 4000, f"frame too big: {len(frame)}"
        assert len(g.render_html(fps=5)) <= 4096
        lines = frame.splitlines()
        assert len(lines[1].splitlines()) >= 1  # viewport exists


def test_movement_and_turning():
    g = Game()
    x0, y0, a0 = g.player.x, g.player.y, g.player.angle
    g.action("turn_right")
    assert g.player.angle > a0
    g.action("turn_left")
    assert abs(g.player.angle - a0) < 1e-9
    g.action("forward")
    assert (g.player.x, g.player.y) != (x0, y0)


def test_wall_collision():
    g = Game()
    g.player.x, g.player.y, g.player.angle = 1.5, 1.5, math.pi  # face west wall
    for _ in range(10):
        g.action("forward")
    assert g.player.x > 1.0  # never inside / past the wall


def test_shooting_kills_and_scores():
    g = Game()
    e = g.enemies[0]
    # put an enemy right in front of the player, clear line
    g.player.x, g.player.y, g.player.angle = e.x - 2.0, e.y, 0.0
    hp0, bullets0 = e.hp, g.player.bullets
    g.action("fire")
    assert g.player.bullets == bullets0 - 1
    assert e.hp < hp0 or not e.alive
    for _ in range(10):
        if not e.alive:
            break
        g.action("fire")
    assert not e.alive and g.kills >= 1 and g.score > 0


def test_enemy_ai_moves_and_damages():
    g = Game()
    e = g.enemies[0]
    g.player.x, g.player.y = e.x - 3.0, e.y
    d0 = math.hypot(g.player.x - e.x, g.player.y - e.y)
    for _ in range(30):
        g.tick(0.1)
    d1 = math.hypot(g.player.x - e.x, g.player.y - e.y)
    assert d1 < d0, "enemy should chase the player"
    hp0 = g.player.hp
    for _ in range(30):
        g.tick(0.1)
        if g.player.hp < hp0:
            break
    assert g.player.hp < hp0 or g.over, "enemy should melee the player"


def test_full_playthrough_is_possible_in_principle():
    # exits exist on every level, key exists before the locked door (level 2)
    for i, lv in enumerate(LEVELS):
        flat = "".join(lv["map"])
        assert "X" in flat, f"level {i} has no exit"
        assert "P" in flat, f"level {i} has no spawn"
    assert "K" in "".join(LEVELS[1]["map"]) and "L" in "".join(LEVELS[1]["map"])


def test_bot_command_vocabulary():
    from bot import ALIASES, normalize
    assert ALIASES[normalize("Вперёд")] == "forward"
    assert ALIASES[normalize("влево")] == "turn_left"
    assert ALIASES[normalize("ОГОНЬ")] == "fire"
    assert ALIASES[normalize("вправо")] == "turn_right"
    assert ALIASES[normalize("выход")] == "exit"
    assert ALIASES[normalize("старт")] == "start"


if __name__ == "__main__":
    for name, fn in sorted(dict(globals()).items()):
        if name.startswith("test_"):
            fn()
            print("ok", name)
    print("ALL TESTS PASSED")
