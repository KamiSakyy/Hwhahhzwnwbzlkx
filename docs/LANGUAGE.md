# Язык LS — краткий справочник

Lua-подобный язык, который компилируется в собственный байт-код (`LSB1`)
и выполняется виртуальной машиной движка.

## Переменные и области видимости

```lua
local a = 1          -- локальная переменная
global b = 2         -- явная глобальная переменная
c = 3                -- присваивание без local — тоже глобальная
```

Локальные переменные компилируются в слоты локальных значений функции
(без поиска по таблице), глобальные — в таблицу `globals`.

## Функции, замыкания, рекурсия

```lua
local function add(a, b)
  return a + b
end

local function makeCounter()
  local n = 0
  return function()
    n = n + 1
    return n
  end
end

local counter = makeCounter()
counter()   -- 1
counter()   -- 2
```

Замыкания захватывают переменные: если локал захвачен вложенной функцией,
он переносится в отдельную ячейку (`Cell`), и внешний и внутренний уровни
видят одно и то же значение.

## Управляющие конструкции

```lua
if x > 0 then
  print("плюс")
elseif x < 0 then
  print("минус")
else
  print("ноль")
end

while i < 10 do i = i + 1 end

repeat
  i = i - 1
until i <= 0

for i = 1, 10 do end          -- числовой цикл
for i = 10, 1, -1 do end      -- с шагом
for k, v in pairs(t) do end   -- по ключам и значениям
for i, v in ipairs(list) do end
```

`break` прерывает любой цикл.

## Типы данных

| Тип | Пример | Проверка |
|---|---|---|
| number | `42`, `3.14` | `type(x) == "number"` |
| string | `"текст"`, `'текст'` | `type(x) == "string"` |
| boolean | `true`, `false` | — |
| nil | `nil` | `type(x) == "nil"` |
| table | `{1, 2, name = "x"}` | `type(x) == "table"` |
| function | `function() end` | `type(x) == "function"` |

Таблицы: `t[1]`, `t.key`, `#t` (длина), `table.insert/remove/concat`.

## Операторы

`+ - * / % ^` — арифметика, `..` — конкатенация строк,
`== ~= < <= > >=` — сравнение, `and or not` — логика,
`#` — длина строки или таблицы, `:` — вызов метода (`s:upper()`).

## Стандартная библиотека

**Базовое:** `print`, `tostring`, `tonumber`, `type`, `pairs`, `ipairs`.

**`math`:** `pi`, `huge`, `floor`, `ceil`, `abs`, `sqrt`, `sin`, `cos`, `tan`,
`atan2`, `min`, `max`, `random`.

**`string`:** `len`, `upper`, `lower`, `sub`, `rep`, `format`.

**`table`:** `insert`, `remove`, `concat`.

## API движка

### Рисование — `draw`

```lua
draw.clear("#101018")                       -- очистить экран
draw.rect(x, y, w, h, "#4F46E5")           -- прямоугольник
draw.circle(x, y, r, "#0EA5E9")            -- круг
draw.line(x1, y1, x2, y2, "#FFFFFF")       -- линия
draw.text(x, y, "текст", 20, "#FFFFFF")    -- текст (y — базовая линия)
```

Цвет задаётся строкой `#RRGGBB`, `#AARRGGBB` или числом `0xFFRRGGBB`.

### Ввод — `input`

| Поле | Значение |
|---|---|
| `input.x`, `input.y` | позиция последнего касания |
| `input.down` | `true`, пока палец на экране |
| `input.pressed` | `true` в кадре нажатия |
| `input.released` | `true` в кадре отпускания |

### Время — `time`

`time.now` (секунды), `time.frame` (номер кадра), `time.dt` (время кадра).

### Интерфейс — `ui`

```lua
ui.button(40, 600, 220, 60, "Играть", function()
  print("нажато")
end)
```

Кнопка рисуется движком и обрабатывается касанием; функция возвращает
`false`, если нажатия не было.

### Прочее

`app.width`, `app.height` — размер экрана; `sound.beep(freq, millis)` — звук;
`print(...)` — вывод в консоль.

## Шаблон игры

```lua
local player = { x = 100, y = 400, w = 40, h = 20, vx = 0 }

function update(dt)
  if input.down then
    player.x = input.x - player.w / 2
  end
  player.x = math.max(0, math.min(app.width - player.w, player.x))
end

function draw()
  draw.clear("#0B0B12")
  draw.rect(player.x, player.y, player.w, player.h, "#14B8A6")
end
```

`update(dt)` и `draw()` — необязательные глобальные функции: если `update`
объявлена, она вызывается каждый кадр с временем кадра в секундах; `draw`
вызывается после очистки экрана.
