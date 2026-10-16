"""
DOOM-style raycasting engine rendered in ASCII / Unicode blocks.

Pure game logic + text renderer. No Telegram / network code here,
so the engine is fully unit-testable offline (see test_doom.py).

Features (a real little DOOM, not a picture):
  * textured/shaded raycast walls, z-buffered billboard sprites
  * 3 levels, doors (incl. a key-locked door), exit portals
  * enemies with line-of-sight AI, chasing and melee attacks
  * pistol + shotgun, ammo / shells, health / armour pickups, key
  * weapon view-model with muzzle flash, HUD, optional minimap
"""
from __future__ import annotations

import html
import math
import random
from dataclasses import dataclass, field

# --------------------------------------------------------------------------
# Constants
# --------------------------------------------------------------------------
VIEW_W = 48           # viewport columns (fits a phone in <pre> monospace)
VIEW_H = 18           # viewport rows
FOV = math.radians(66)
MAX_DEPTH = 22.0

WALL_CHARS = "█▓▒░"   # near -> far shading

# Enemy archetypes: hp, speed (tiles/s), damage, score, sprite palette
ENEMY_TYPES = {
    "E": dict(name="ИМП",    hp=50,  speed=0.85, dmg=8,  score=100, body="▓", face="◉"),
    "M": dict(name="ДЕМОН",  hp=110, speed=1.25, dmg=14, score=250, body="█", face="◉"),
    "B": dict(name="БАРОН",  hp=320, speed=0.95, dmg=22, score=1000, body="█", face="☠"),
}

PICKUP_CHARS = {"H": "✚", "A": "▮", "R": "⛨", "S": "═", "K": "⚿"}

# --------------------------------------------------------------------------
# Level maps.  Legend:
#   # % +   walls (different textures)      D  door          L  locked door
#   P       player start                    X  exit portal
#   E/M/B   imp / demon / baron             H  medkit (+25)
#   A       bullets                         R  armour (+50)
#   S       shotgun + shells                K  key
# --------------------------------------------------------------------------
LEVELS = [
    {
        "name": "E1M1 · АНГАР",
        "map": [
            "########################",
            "#..........#...........#",
            "#..H.......D.....A.....#",
            "#..........#...........#",
            "###..#######..######...#",
            "#..........%.....E...###",
            "#..P.......%...........#",
            "#..........%..##..##...#",
            "######D#####..#....#...#",
            "#..........#..#....#..##",
            "#..E....A..D..E....X..##",
            "#..........#..#....#..##",
            "########################",
        ],
    },
    {
        "name": "E1M2 · ЯДЕРНАЯ СТАНЦИЯ",
        "map": [
            "%%%%%%%%%%%%%%%%%%%%%%%%",
            "%...#..........%....K..%",
            "%.P.#....E.....%.%%%%%.%",
            "%...#..........D.......%",
            "%.#####..####..%.%%%%%.%",
            "%.....%..%..%..%.......%",
            "%.E...D..M..D...E...%%%%",
            "%.....%..%..%..%....A..%",
            "%..####..####..%%%%....%",
            "%..#..............D..R.%",
            "%..#..H....M......#....%",
            "%..####......%%%%##L####",
            "%......S..........#..X.%",
            "%%%%%%%%%%%%%%%%%%%%%%%%",
        ],
    },
    {
        "name": "E1M3 · АД",
        "map": [
            "++++++++++++++++++++++++",
            "+....+.........+....H..+",
            "+.P..+...M.....D..####.+",
            "+....+.........+..#....+",
            "+.++++..+++++..+..#.E..+",
            "+.......D...D.....#....+",
            "+..M....+...++.#####D###",
            "+.......+++.++....A....+",
            "+.++++.....M......##+###",
            "+....+..++++++....#B#..+",
            "+.H..D.......D....#X#..+",
            "+....+...S....R...###..+",
            "++++++++++++++++++++++++",
        ],
    },
]


def _norm_angle(a: float) -> float:
    while a > math.pi:
        a -= 2 * math.pi
    while a < -math.pi:
        a += 2 * math.pi
    return a


@dataclass
class Enemy:
    x: float
    y: float
    kind: str
    hp: int
    max_hp: int
    attack_cd: float = 0.0
    hit_flash: float = 0.0
    alive: bool = True

    @property
    def spec(self):
        return ENEMY_TYPES[self.kind]


@dataclass
class Pickup:
    x: float
    y: float
    kind: str
    taken: bool = False


@dataclass
class Player:
    x: float
    y: float
    angle: float = 0.0  # facing east
    hp: int = 100
    armour: int = 0
    bullets: int = 50
    shells: int = 0
    has_shotgun: bool = False
    weapon: str = "pistol"  # pistol | shotgun
    has_key: bool = False


@dataclass
class Game:
    level_idx: int = 0
    player: Player = field(default_factory=lambda: Player(1.5, 1.5))
    walls: dict = field(default_factory=dict)       # (x,y) -> char
    doors: dict = field(default_factory=dict)       # (x,y) -> "open"/"closed"/"locked"
    enemies: list = field(default_factory=list)
    pickups: list = field(default_factory=list)
    width: int = 0
    height: int = 0
    score: int = 0
    kills: int = 0
    shots: int = 0
    hits: int = 0
    time: float = 0.0
    muzzle: float = 0.0          # seconds of muzzle-flash left
    hurt_flash: float = 0.0
    show_map: bool = False
    over: bool = False
    won: bool = False
    paused: bool = False
    log: str = "Убей всех. Найди выход X."
    rng: random.Random = field(default_factory=random.Random)

    # ------------------------------------------------------------------ setup
    def __post_init__(self):
        self.load_level(0)

    def load_level(self, idx: int):
        self.level_idx = idx
        data = LEVELS[idx]
        rows = data["map"]
        self.height = len(rows)
        self.width = max(len(r) for r in rows)
        self.walls, self.doors, self.enemies, self.pickups = {}, {}, [], []
        for y, row in enumerate(rows):
            for x, ch in enumerate(row):
                if ch in "#%+":
                    self.walls[(x, y)] = ch
                elif ch == "D":
                    self.doors[(x, y)] = "closed"
                elif ch == "L":
                    self.doors[(x, y)] = "locked"
                elif ch == "P":
                    self.player.x, self.player.y = x + 0.5, y + 0.5
                elif ch in ENEMY_TYPES:
                    spec = ENEMY_TYPES[ch]
                    self.enemies.append(Enemy(x + 0.5, y + 0.5, ch, spec["hp"], spec["hp"]))
                elif ch in PICKUP_CHARS:
                    self.pickups.append(Pickup(x + 0.5, y + 0.5, ch))
                elif ch == "X":
                    self.walls[(x, y)] = "X"  # special: portal, handled in is_solid/move
        self.log = f"{data['name']}. Врагов: {len(self.enemies)}"

    # ---------------------------------------------------------------- helpers
    def tile_at(self, x: float, y: float) -> str:
        return self.walls.get((int(x), int(y)), "")

    def is_solid(self, x: float, y: float, for_enemy: bool = False) -> bool:
        tx, ty = int(x), int(y)
        if tx < 0 or ty < 0 or tx >= self.width or ty >= self.height:
            return True
        w = self.walls.get((tx, ty))
        if w and w != "X":
            return True
        d = self.doors.get((tx, ty))
        if d and d != "open":
            # enemies can push normal doors open, never locked ones
            if for_enemy and d == "closed":
                self.doors[(tx, ty)] = "open"
                return False
            return True
        return False

    def _try_move(self, nx: float, ny: float, radius: float = 0.22) -> bool:
        p = self.player
        moved = False
        if not (self.is_solid(nx + math.copysign(radius, nx - p.x), p.y)
                or self.is_solid(nx - math.copysign(radius, nx - p.x), p.y)):
            p.x = nx
            moved = True
        if not (self.is_solid(p.x, ny + math.copysign(radius, ny - p.y))
                or self.is_solid(p.x, ny - math.copysign(radius, ny - p.y))):
            p.y = ny
            moved = True
        return moved

    def _los(self, x0, y0, x1, y1) -> bool:
        """Line of sight (walls/closed doors block)."""
        dist = math.hypot(x1 - x0, y1 - y0)
        steps = max(1, int(dist * 4))
        for i in range(1, steps):
            t = i / steps
            if self.is_solid(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t):
                return False
        return True

    # ---------------------------------------------------------------- actions
    def action(self, cmd: str) -> str:
        """Apply one player command, return a short log line."""
        if self.over:
            return self.log
        p = self.player
        step, turn = 0.55, math.radians(30)

        if cmd == "forward":
            self._try_move(p.x + math.cos(p.angle) * step, p.y + math.sin(p.angle) * step)
        elif cmd == "back":
            self._try_move(p.x - math.cos(p.angle) * step * 0.7, p.y - math.sin(p.angle) * step * 0.7)
        elif cmd == "turn_left":
            p.angle -= turn
        elif cmd == "turn_right":
            p.angle += turn
        elif cmd == "strafe_left":
            a = p.angle - math.pi / 2
            self._try_move(p.x + math.cos(a) * step * 0.8, p.y + math.sin(a) * step * 0.8)
        elif cmd == "strafe_right":
            a = p.angle + math.pi / 2
            self._try_move(p.x + math.cos(a) * step * 0.8, p.y + math.sin(a) * step * 0.8)
        elif cmd == "turn_180":
            p.angle += math.pi
        elif cmd == "fire":
            self._fire()
        elif cmd == "use":
            self._use()
        elif cmd == "weapon":
            if p.has_shotgun:
                p.weapon = "shotgun" if p.weapon == "pistol" else "pistol"
                self.log = f"Оружие: {'ДРОБОВИК' if p.weapon == 'shotgun' else 'ПИСТОЛЕТ'}"
            else:
                self.log = "Дробовик ещё не найден (S)."
        elif cmd == "map":
            self.show_map = not self.show_map
            self.log = "Карта: " + ("ВКЛ" if self.show_map else "ВЫКЛ")
        elif cmd == "pause":
            self.paused = not self.paused
            self.log = "⏸ ПАУЗА" if self.paused else "▶ Игра идёт"
        else:
            return self.log

        self._after_move()
        return self.log

    def _use(self):
        p = self.player
        tx, ty = int(p.x + math.cos(p.angle) * 1.1), int(p.y + math.sin(p.angle) * 1.1)
        state = self.doors.get((tx, ty))
        if state == "closed":
            self.doors[(tx, ty)] = "open"
            self.log = "Дверь открыта."
        elif state == "locked":
            if p.has_key:
                self.doors[(tx, ty)] = "open"
                self.log = "🔑 Замок открыт ключом!"
            else:
                self.log = "🔒 Нужен ключ (⚿ на уровне)."
        elif state == "open":
            self.doors[(tx, ty)] = "closed"
            self.log = "Дверь закрыта."
        else:
            self.log = "Нечего открывать."

    def _fire(self):
        p = self.player
        if p.weapon == "shotgun":
            if p.shells <= 0:
                self.log = "Нет картечи! (огонь = пистолет при 0)"
                p.weapon = "pistol" if False else p.weapon
                if p.bullets <= 0:
                    self.log = "СОВСЕМ нет патронов! Ищи ▮"
                    return
                self._shoot_once(dmg=18, spread=0.03)
                return
            p.shells -= 1
            self.muzzle = 0.18
            self.shots += 1
            hit_any = False
            for _ in range(6):  # pellet spread
                hit_any |= self._shoot_once(dmg=14, spread=0.14, silent=True)
            self.log = "💥 БАХ! " + ("Попал!" if hit_any else "Мимо.")
            if hit_any:
                self.hits += 1
            return
        # pistol
        if p.bullets <= 0:
            self.log = "Нет патронов! Ищи ▮"
            return
        p.bullets -= 1
        self._shoot_once(dmg=22, spread=0.015)

    def _shoot_once(self, dmg: int, spread: float, silent: bool = False) -> bool:
        p = self.player
        self.muzzle = 0.14
        if not silent:
            self.shots += 1
        ang = p.angle + self.rng.uniform(-spread, spread)
        # distance to nearest wall along the shot
        wall_d = self._cast_ray(ang)[0]
        best, best_d = None, wall_d
        for e in self.enemies:
            if not e.alive:
                continue
            d = math.hypot(e.x - p.x, e.y - p.y)
            if d > best_d or d > MAX_DEPTH:
                continue
            rel = abs(_norm_angle(math.atan2(e.y - p.y, e.x - p.x) - ang))
            if rel < math.atan2(0.42, max(d, 0.001)):  # body radius ~0.42 tile
                best, best_d = e, d
        if best is None:
            if not silent:
                self.log = "Выстрел мимо."
            return False
        best.hp -= dmg
        best.hit_flash = 0.25
        if not silent:
            self.hits += 1
        if best.hp <= 0:
            best.alive = False
            self.kills += 1
            self.score += best.spec["score"]
            if not silent or True:
                self.log = f"☠ {best.spec['name']} убит! +{best.spec['score']}"
            # 35% drop: ammo or medkit
            r = self.rng.random()
            if r < 0.20:
                self.pickups.append(Pickup(best.x, best.y, "A"))
            elif r < 0.35:
                self.pickups.append(Pickup(best.x, best.y, "H"))
        else:
            if not silent:
                self.log = f"Попадание по {best.spec['name']} ({best.hp} HP)"
        return True

    def _after_move(self):
        p = self.player
        # exit portal?
        if self.tile_at(p.x, p.y) == "X":
            if self.level_idx + 1 >= len(LEVELS):
                self.won = True
                self.over = True
                self.score += p.hp * 5
                self.log = "🏆 ТЫ ПРОШЁЛ DOOM!"
            else:
                self.score += 500
                self.load_level(self.level_idx + 1)
                self.log = "🌀 Портал! " + self.log
            return
        # pickups
        for pk in self.pickups:
            if pk.taken or math.hypot(pk.x - p.x, pk.y - p.y) > 0.55:
                continue
            if pk.kind == "H" and p.hp < 100:
                p.hp = min(100, p.hp + 25); pk.taken = True; self.log = "✚ +25 HP"
            elif pk.kind == "A":
                p.bullets += 20; p.shells += 4; pk.taken = True; self.log = "▮ +20 патронов, +4 картечи"
            elif pk.kind == "R" and p.armour < 100:
                p.armour = min(100, p.armour + 50); pk.taken = True; self.log = "⛨ +50 брони"
            elif pk.kind == "S":
                p.has_shotgun = True; p.shells += 8; p.weapon = "shotgun"
                pk.taken = True; self.log = "🔫 ДРОБОВИК! +8 картечи (команда: оружие)"
            elif pk.kind == "K":
                p.has_key = True; pk.taken = True; self.log = "⚿ КЛЮЧ подобран!"
            if pk.taken:
                self.score += 25

    # ------------------------------------------------------------------- tick
    def tick(self, dt: float):
        if self.over or self.paused:
            return
        self.time += dt
        self.muzzle = max(0.0, self.muzzle - dt)
        self.hurt_flash = max(0.0, self.hurt_flash - dt)
        p = self.player
        for e in self.enemies:
            if not e.alive:
                continue
            e.hit_flash = max(0.0, e.hit_flash - dt)
            e.attack_cd = max(0.0, e.attack_cd - dt)
            d = math.hypot(p.x - e.x, p.y - e.y)
            if d < 0.75:  # melee
                if e.attack_cd <= 0:
                    e.attack_cd = 0.9
                    dmg = e.spec["dmg"] + self.rng.randint(-3, 4)
                    absorbed = 0
                    if p.armour > 0:
                        absorbed = min(p.armour, dmg // 3)
                        p.armour -= absorbed
                    p.hp -= (dmg - absorbed)
                    self.hurt_flash = 0.35
                    self.log = f"👹 {e.spec['name']} бьёт тебя! -{dmg - absorbed} HP"
                    if p.hp <= 0:
                        p.hp = 0
                        self.over = True
                        self.log = "💀 ТЫ МЁРТВ. /start — заново."
                continue
            if d < 11 and self._los(e.x, e.y, p.x, p.y):
                sp = e.spec["speed"] * dt
                nx = e.x + (p.x - e.x) / d * sp
                ny = e.y + (p.y - e.y) / d * sp
                # slide along walls, don't stack on other enemies
                if not self.is_solid(nx, e.y, for_enemy=True):
                    e.x = nx
                if not self.is_solid(e.x, ny, for_enemy=True):
                    e.y = ny
        # standing on a pickup cell also counts (walked between actions)
        self._after_move()

    # -------------------------------------------------------------- raycasting
    def _cast_ray(self, angle: float):
        """DDA raycast. Returns (distance_corrected, wall_char, side, raw_dist)."""
        p = self.player
        dx, dy = math.cos(angle), math.sin(angle)
        mx, my = int(p.x), int(p.y)
        ddx = abs(1 / dx) if dx != 0 else 1e30
        ddy = abs(1 / dy) if dy != 0 else 1e30
        if dx < 0:
            step_x, sdx = -1, (p.x - mx) * ddx
        else:
            step_x, sdx = 1, (mx + 1 - p.x) * ddx
        if dy < 0:
            step_y, sdy = -1, (p.y - my) * ddy
        else:
            step_y, sdy = 1, (my + 1 - p.y) * ddy
        side, wall = 0, "#"
        for _ in range(64):
            if sdx < sdy:
                sdx += ddx; mx += step_x; side = 0
            else:
                sdy += ddy; my += step_y; side = 1
            w = self.walls.get((mx, my))
            if w:
                wall = w
                break
            dstate = self.doors.get((mx, my))
            if dstate and dstate != "open":
                wall = "L" if dstate == "locked" else "D"
                break
            if not (0 <= mx < self.width and 0 <= my < self.height):
                wall = "#"
                break
        raw = (sdx - ddx) if side == 0 else (sdy - ddy)
        corrected = raw * math.cos(angle - p.angle)
        return max(corrected, 0.0001), wall, side, raw

    # templates for sprite bodies (8 rows), resampled to sprite size
    _SPRITES = {
        "E": [" ▄▄▄▄ ", "◉████◉", " ████ ", "▐████▌", " ▐██▌ ", " ▐██▌ ", " ▟  ▙ ", "▟▙  ▟▙"],
        "M": ["▟████▙", "◉████◉", "██████", "▐████▌", "▐████▌", " ▐██▌ ", " ▟██▙ ", "▟▙  ▟▙"],
        "B": ["▟████▙", "☠████☠", "██████", "██████", "▐████▌", "▐████▌", "▟████▙", "▟▙  ▟▙"],
    }

    def render(self, fps: float = 0.0) -> str:
        p = self.player
        screen = [[" "] * VIEW_W for _ in range(VIEW_H)]
        zbuf = [MAX_DEPTH] * VIEW_W
        mid = VIEW_H // 2

        # ceiling / floor background
        for y in range(VIEW_H):
            ch = "·" if y < mid else ("░" if y > mid + 3 else ":")
            for x in range(VIEW_W):
                screen[y][x] = ch

        # walls
        for col in range(VIEW_W):
            angle = p.angle - FOV / 2 + FOV * col / VIEW_W
            dist, wall, side, _ = self._cast_ray(angle)
            zbuf[col] = dist
            h = min(VIEW_H, int(VIEW_H / max(dist, 0.05)))
            top, bot = mid - h // 2, mid + h // 2
            if wall == "X":
                ch = "✦" if (col // 2) % 2 else "✧"
            elif wall == "D":
                ch = "▦"
            elif wall == "L":
                ch = "▩"
            else:
                idx = 0 if dist < 2 else 1 if dist < 4.5 else 2 if dist < 8 else 3
                ch = WALL_CHARS[idx]
                if side == 1:
                    ch = WALL_CHARS[min(3, idx + 1)]
                if wall == "%" and col % 3 == 0:
                    ch = "▒"
                if wall == "+" and col % 4 == 0:
                    ch = "▓"
            for y in range(max(0, top), min(VIEW_H, bot)):
                # door centre line
                if wall in "DL" and y == mid:
                    screen[y][col] = "─"
                else:
                    screen[y][col] = ch

        # sprites: pickups then enemies, far -> near
        sprites = []
        for pk in self.pickups:
            if not pk.taken:
                sprites.append((math.hypot(pk.x - p.x, pk.y - p.y), "pickup", pk))
        for e in self.enemies:
            if e.alive:
                sprites.append((math.hypot(e.x - p.x, e.y - p.y), "enemy", e))
        sprites.sort(key=lambda s: -s[0])

        for dist, kind, obj in sprites:
            if dist < 0.35 or dist > MAX_DEPTH:
                continue
            rel = _norm_angle(math.atan2(obj.y - p.y, obj.x - p.x) - p.angle)
            if abs(rel) > FOV / 2 + 0.35:
                continue
            cx = int((0.5 + rel / FOV) * VIEW_W)
            if kind == "pickup":
                size = max(1, int(VIEW_H / dist * 0.35))
                ch = PICKUP_CHARS[obj.kind]
                for yy in range(mid + size // 2 - size, mid + size // 2):
                    if 0 <= yy < VIEW_H and 0 <= cx < VIEW_W and dist < zbuf[cx]:
                        screen[yy][cx] = ch
                continue
            # enemy
            t = self._SPRITES[obj.kind]
            size = int(VIEW_H / dist * 0.95)
            if size <= 0:
                continue
            wpx = max(1, int(size * 0.8))
            x0, y0 = cx - wpx // 2, mid + size // 2 - size
            for sy in range(size):
                yy = y0 + sy
                if not (0 <= yy < VIEW_H):
                    continue
                trow = t[min(len(t) - 1, sy * len(t) // size)]
                for sx in range(wpx):
                    xx = x0 + sx
                    if not (0 <= xx < VIEW_W) or dist >= zbuf[xx]:
                        continue
                    ch = trow[min(len(trow) - 1, sx * len(trow) // max(1, wpx))]
                    if ch == " ":
                        continue
                    if obj.hit_flash > 0 and ch in "█▓▌▐▙▟▄":
                        ch = "▒"  # flash on hit
                    screen[yy][xx] = ch
            # HP bar over close enemies
            if dist < 6 and 0 <= y0 - 1 < VIEW_H:
                frac = max(0.0, obj.hp / obj.max_hp)
                bar_w = max(2, int(wpx * frac))
                for i in range(bar_w):
                    xx = x0 + i
                    if 0 <= xx < VIEW_W:
                        screen[y0 - 1][xx] = "▀"

        # crosshair
        if 0 <= mid < VIEW_H:
            screen[mid][VIEW_W // 2] = "✛" if self.muzzle <= 0 else "💥"[0] if False else "✺"

        # weapon view-model (bottom centre)
        flash = self.muzzle > 0
        if p.weapon == "shotgun":
            gun = ["   ║ ║   ", "  ╔╩═╩╗  ", " ╔╝   ╚╗ ", " █     █ "] if not flash else \
                  [" * ║ ║ * ", "✦╔╩═╩╗✦", " ╔╝   ╚╗ ", " █     █ "]
        else:
            gun = ["    ║    ", "   ╔╩╗   ", "  ╔╝ ╚╗  ", "  █   █  "] if not flash else \
                  [" ✦  ║  ✦ ", "   ╔╩╗   ", "  ╔╝ ╚╗  ", "  █   █  "]
        gx = VIEW_W // 2 - len(gun[0]) // 2
        for i, row in enumerate(gun):
            yy = VIEW_H - len(gun) + i
            for j, ch in enumerate(row):
                if ch != " " and 0 <= gx + j < VIEW_W:
                    screen[yy][gx + j] = ch

        view = "\n".join("".join(r) for r in screen)

        # ------------------------------------------------------------ HUD
        alive = sum(1 for e in self.enemies if e.alive)
        hp_bar = "█" * (p.hp // 10) + "░" * (10 - p.hp // 10)
        ammo = f"🔫{p.bullets}" + (f" 💥{p.shells}" if p.has_shotgun else "")
        weapon = "ДРОБОВИК" if p.weapon == "shotgun" else "ПИСТОЛЕТ"
        head = "🔴" if self.hurt_flash > 0 else "😈"
        lines = [
            f"👹 DOOM · {LEVELS[self.level_idx]['name']} · ⏱{int(self.time)}с" + (f" · {fps:.0f} FPS" if fps else ""),
            view,
            f"{head} HP {p.hp:3d} [{hp_bar}]  ⛨{p.armour}  {ammo}  [{weapon}]" + ("  ⚿" if p.has_key else ""),
            f"⭐{self.score}  ☠{self.kills}  👾осталось:{alive}  🎯{self.hits}/{self.shots}",
            f"💬 {self.log}",
        ]
        if self.over:
            lines.append("🏆 ПОБЕДА! /start — ещё раз" if self.won else "💀 GAME OVER · /start — заново")
        if self.show_map:
            lines.append(self._minimap())
        lines.append("⌨️ вперёд·назад·влево·вправо·огонь·открыть·оружие·карта·выход")
        return "\n".join(lines)

    def _minimap(self) -> str:
        p = self.player
        rows = []
        for y in range(self.height):
            row = ""
            for x in range(self.width):
                if int(p.x) == x and int(p.y) == y:
                    row += "☻"
                elif any(e.alive and int(e.x) == x and int(e.y) == y for e in self.enemies):
                    row += "E"
                elif self.tile_at(x + 0.5, y + 0.5) == "X":
                    row += "X"
                elif (x, y) in self.doors:
                    row += "D" if self.doors[(x, y)] != "open" else "·"
                elif (x, y) in self.walls:
                    row += "█"
                else:
                    row += "·"
            rows.append(row)
        return "\n".join(rows)

    def render_html(self, fps: float = 0.0) -> str:
        """Frame ready for Telegram HTML parse_mode (<pre> = monospace)."""
        return "<pre>" + html.escape(self.render(fps)) + "</pre>"
