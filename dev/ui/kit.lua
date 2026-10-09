dofile("/home/emppu/Projects/Minecraft Datapacks/.claude/skills/pack-icon-animation/assets/pixel_art.lua")
local ROOT = "/home/emppu/Projects/Minecraft Datapacks/Blueprinter/dev/ui/out/"
local key = PA.key
PA.SMALL["%"] = {"##..#","##.#.","...#.","..#..",".#...",".#.##","#..##"}

-- canvas ---------------------------------------------------------------------------------------------------------
local N8o = { {0,-1},{0,1},{-1,0},{1,0},{-1,-1},{1,-1},{-1,1},{1,1} }
local C = {}; C.__index = C
local function newc() return setmetatable({ p = {} }, C) end
function C:px(x, y, col) self.p[key(x, y)] = col end
function C:rect(x, y, w, h, col) for j = 0, h - 1 do for i = 0, w - 1 do self.p[key(x + i, y + j)] = col end end end
function C:rr(x, y, w, h, r, col)
  for j = 0, h - 1 do for i = 0, w - 1 do
    local dx, dy = math.min(i, w - 1 - i), math.min(j, h - 1 - j)
    if dx + dy >= r then self.p[key(x + i, y + j)] = col end
  end end
end
function C:hline(x, y, w, col) for i = 0, w - 1 do self.p[key(x + i, y)] = col end end
function C:vline(x, y, h, col) for j = 0, h - 1 do self.p[key(x, y + j)] = col end end
function C:frame(x, y, w, h, col) self:hline(x, y, w, col); self:hline(x, y + h - 1, w, col); self:vline(x, y, h, col); self:vline(x + w - 1, y, h, col) end
local function tw(s) local _, r = PA.layout(s, PA.SMALL, 1, 3, 0, 0); return r - 0 end
function C:text(x, y, s, col, sh)
  local m = PA.layout(s, PA.SMALL, 1, 3, x, y)
  if sh then for k in pairs(m) do self.p[key(k % 4096 + 1, k // 4096 + 1)] = sh end end
  for k in pairs(m) do self.p[k] = col end
end
function C:otext(x, y, s, col, ol)
  local m = PA.layout(s, PA.SMALL, 1, 3, x, y)
  for k in pairs(m) do
    local px, py = k % 4096, k // 4096
    for _, d in ipairs(N8o) do local n = key(px + d[1], py + d[2]); if not m[n] then self.p[n] = ol end end
  end
  for k in pairs(m) do self.p[k] = col end
end
function C:ctext(x, y, w, s, col, sh) self:text(x + (w - tw(s)) // 2, y, s, col, sh) end
function C:save(path, w, h) PA.save_pixels(path, w, h, self.p) end

-- icons: 12x12 masks, '#' main, 'o' detail -----------------------------------------------------------------------
local ICONS = {
  move = { ".....##.....", "....####....", "...######...", ".....##.....", ".#...##...#.", "##.######.##", "##.######.##", ".#...##...#.", ".....##.....", "...######...", "....####....", ".....##....." },
  rotate = { "...#####....", "..#######...", ".###....####", ".##......##.", ".##.......#.", ".##.......##", ".##.......##", ".###.....###", "..#########.", "...#######..", "....#####...", "............" },
  mirror = { "............", ".#....o....#", ".##...o...##", ".###..o..###", ".####.o.####", ".#####o#####", ".#####o#####", ".####.o.####", ".###..o..###", ".##...o...##", ".#....o....#", "............" },
  layers = { ".....##.....", "...######...", ".##########.", "...######...", ".....##.....", "............", "##........##", ".###....###.", "...######...", ".....##.....", "............", "............" },
  save = { "###########.", "#.#######.##", "#.#######.##", "#.#######.##", "#..........#", "#.########.#", "#.#oooooo#.#", "#.#oooooo#.#", "#.#oooooo#.#", "#.########.#", "############", "............" },
  list = { "..########..", ".#.oooooo.#.", ".#........#.", ".#.#.####.#.", ".#........#.", ".#.#.####.#.", ".#........#.", ".#.#.####.#.", ".#........#.", ".##########.", "............", "............" },
  eye = { "............", "....####....", "..########..", ".##########.", "###oooooo###", "###oo##oo###", "###oooooo###", ".##########.", "..########..", "....####....", "............", "............" },
  trash = { "....####....", "############", ".##########.", ".#o#o##o#o#.", ".#o#o##o#o#.", ".#o#o##o#o#.", ".#o#o##o#o#.", ".#o#o##o#o#.", ".##########.", "............", "............", "............" },
  check = { "..........##", ".........##.", "........##..", "#......##...", "##....##....", ".##..##.....", "..####......", "...##.......", "............", "............", "............", "............" },
  cross = { "##........##", ".##......##.", "..##....##..", "...##..##...", "....####....", "....####....", "...##..##...", "..##....##..", ".##......##.", "##........##", "............", "............" },
  plus = { "....####....", "....####....", "....####....", "....####....", "############", "############", "############", "############", "....####....", "....####....", "....####....", "....####...." },
  select = { "###......###", "#..........#", "#..........#", "............", "............", "............", "............", "............", "............", "#..........#", "#..........#", "###......###" },
  folder = { "............", "####........", "############", "#oooooooooo#", "#oooooooooo#", "#oooooooooo#", "#oooooooooo#", "#oooooooooo#", "#oooooooooo#", "############", "............", "............" },
  arrow = { ".....##.....", "....####....", "...######...", "..########..", ".##########.", "....####....", "....####....", "....####....", "....####....", "....####....", "....####....", "....####...." },
}
local ICON_COL = { move = "#FF8A3D", rotate = "#3DB8FF", mirror = "#B26BFF", layers = "#FFD23D", save = "#5BD66B", list = "#FF6B9A", eye = "#3DD6C8", trash = "#FF5A5A", check = "#5BD66B", cross = "#FF5A5A", plus = "#5BD66B", select = "#3DB8FF", folder = "#FFC23D", arrow = "#FF8A3D" }
local ICON_ORDER = { "move", "rotate", "mirror", "layers", "select", "save", "folder", "list", "eye", "trash", "plus", "check", "cross", "arrow" }
local function mask(name)
  local m = {}
  local g = ICONS[name]
  for r = 1, 12 do
    local row = g[r] or ""
    row = row .. string.rep(".", 12 - #row)
    for col = 1, 12 do
      local ch = row:sub(col, col)
      if ch ~= "." then m[key(col, r)] = ch end
    end
  end
  return m
end
local function lighten(h, k)
  local r, g, b = tonumber(h:sub(2, 3), 16), tonumber(h:sub(4, 5), 16), tonumber(h:sub(6, 7), 16)
  local f = function(c) return math.max(0, math.min(255, math.floor(c + (k > 0 and (255 - c) or c) * k + 0.5))) end
  return string.format("#%02X%02X%02X", f(r), f(g), f(b))
end
local N4 = { { 0, -1 }, { 0, 1 }, { -1, 0 }, { 1, 0 } }
local N8 = { { 0, -1 }, { 0, 1 }, { -1, 0 }, { 1, 0 }, { -1, -1 }, { 1, -1 }, { -1, 1 }, { 1, 1 } }

-- generic icon painter: fill(col_main, col_hi, col_lo, col_detail), outline colour or nil
local function paint_icon(c, x, y, name, P)
  local m = mask(name)
  for k, ch in pairs(m) do
    local col, r = k % 4096, k // 4096
    local fill
    if ch == "o" then fill = P.detail(name)
    else
      local up, dn = m[key(col, r - 1)], m[key(col, r + 1)]
      if P.shade then
        if not up then fill = P.hi(name) elseif not dn then fill = P.lo(name) else fill = P.main(name) end
      else fill = P.main(name) end
    end
    c:px(x + col, y + r, fill)
  end
  if P.outline then
    for k in pairs(m) do
      local col, r = k % 4096, k // 4096
      for _, d in ipairs(N8) do
        local n = key(col + d[1], r + d[2])
        if not m[n] then c:px(x + col + d[1], y + r + d[2], P.outline) end
      end
    end
  end
end

-- wheel ----------------------------------------------------------------------------------------------------------
local function wheel(c, x, y, W, wedge)
  for j = 0, 63 do for i = 0, 63 do
    local dx, dy = i - 31.5, j - 31.5
    local d = math.sqrt(dx * dx + dy * dy)
    local ang = math.deg(math.atan(dx, -dy)) % 360
    local delta = (ang + 30) % 60
    local dist = math.rad(math.min(delta, 60 - delta)) * d
    local col
    if d <= 31.5 and d >= 13.5 then
      if d > 30.3 or d < 14.7 then col = W.ink
      elseif dist < 0.9 then col = W.ink
      else
        if wedge and d > 16 and (ang < 30 or ang > 330) then col = W.wedge
        elseif d > 28.5 and dx + dy < 0 then col = W.hi
        elseif d > 28.5 then col = W.lo
        else col = W.ring end
      end
    elseif d < 13.5 then
      if d > 12.3 then col = W.ink
      elseif d > 10.8 then col = (dx + dy < 0) and W.hubhi or W.hublo
      else col = W.hub end
    end
    if col then c:px(x + i, y + j, col) end
  end end
end

-- themes ---------------------------------------------------------------------------------------------------------
local THEMES = {}

-- A: Brass Works --------------------------------------------------------------------------------------------------
do
  local O, H, B, L, D = "#2A1D12", "#F6D77E", "#D8A844", "#A06E27", "#5F3F18"
  local Ah, A, Al, Ad = "#BDC0BB", "#8F9391", "#686C6B", "#3B3E3D"
  local T = { name = "A_brass_works", bg = "#262321" }
  function T.panel(c, x, y, w, h)
    c:rr(x, y, w, h, 2, O)
    c:rect(x + 1, y + 1, w - 2, h - 2, B)
    c:hline(x + 1, y + 1, w - 2, H); c:vline(x + 1, y + 1, h - 2, H)
    c:hline(x + 1, y + h - 2, w - 2, L); c:vline(x + w - 2, y + 1, h - 2, L)
    c:rect(x + 3, y + 3, w - 6, h - 6, A)
    c:hline(x + 3, y + 3, w - 6, Al); c:vline(x + 3, y + 3, h - 6, Al)
    c:hline(x + 3, y + h - 4, w - 6, Ah); c:vline(x + w - 4, y + 3, h - 6, Ah)
    if w >= 20 and h >= 20 then
      for _, p in ipairs({ { 5, 5 }, { w - 7, 5 }, { 5, h - 7 }, { w - 7, h - 7 } }) do
        c:rect(x + p[1], y + p[2], 2, 2, Ah); c:px(x + p[1] + 1, y + p[2] + 1, Al)
      end
    end
  end
  function T.inset(c, x, y, w, h)
    c:rect(x, y, w, h, O); c:rect(x + 1, y + 1, w - 2, h - 2, Ad)
    c:hline(x + 1, y + h - 2, w - 2, Al); c:vline(x + w - 2, y + 1, h - 2, Al)
  end
  function T.button(c, x, y, w, h, st)
    c:rr(x, y, w, h, 1, O)
    local f, hi, lo = B, H, L
    if st == 1 then f, hi, lo = "#EBC25E", "#FFF0B0", B end
    if st == 2 then f, hi, lo = L, D, B end
    c:rect(x + 1, y + 1, w - 2, h - 2, f)
    if st ~= 2 then
      c:hline(x + 1, y + 1, w - 2, hi); c:vline(x + 1, y + 1, h - 2, hi)
      c:hline(x + 1, y + h - 2, w - 2, lo); c:vline(x + w - 2, y + 1, h - 2, lo)
    else
      c:hline(x + 1, y + 1, w - 2, hi); c:vline(x + 1, y + 1, h - 2, hi)
      c:hline(x + 1, y + h - 2, w - 2, lo); c:vline(x + w - 2, y + 1, h - 2, lo)
    end
  end
  function T.label(c, x, y, w, h, s, st)
    local oy = st == 2 and 1 or 0
    local tx = x + (w - tw(s)) // 2
    c:text(tx, y + (h - 7) // 2 + oy, s, O, (st == 2) and L or H)
  end
  function T.slot(c, x, y, s, st)
    c:rect(x, y, s, s, O)
    c:rect(x + 1, y + 1, s - 2, s - 2, st == 1 and "#4B4F4D" or Ad)
    c:hline(x + 1, y + s - 2, s - 2, Al); c:vline(x + s - 2, y + 1, s - 2, Al)
    if st == 2 then c:frame(x, y, s, s, H); c:frame(x + 1, y + 1, s - 2, s - 2, B) end
  end
  function T.bar(c, x, y, w, h, f)
    c:rect(x, y, w, h, O); c:rect(x + 1, y + 1, w - 2, h - 2, D)
    local fw = math.floor((w - 2) * f + 0.5)
    if fw > 0 then
      c:rect(x + 1, y + 1, fw, h - 2, B)
      c:hline(x + 1, y + 1, fw, H); c:hline(x + 1, y + h - 2, fw, L)
      for i = 3, fw - 1, 4 do c:vline(x + i, y + 2, h - 4, L) end
    end
  end
  function T.tab(c, x, y, w, h, on)
    c:rr(x, y, w, h + 1, 2, O)
    c:rect(x + 1, y + 1, w - 2, h, on and B or Al)
    c:hline(x + 1, y + 1, w - 2, on and H or A)
    if on then c:hline(x + 1, y + h, w - 2, A) end
  end
  function T.tabtext(c, x, y, w, h, s, on) c:ctext(x, y + (h - 7) // 2 + 1, w, s, on and O or Ah, on and H or nil) end
  function T.tooltip(c, x, y, w, h)
    c:rect(x, y, w, h, O); c:frame(x + 1, y + 1, w - 2, h - 2, L); c:rect(x + 2, y + 2, w - 4, h - 4, "#1D1710")
    c:hline(x + 2, y + 2, w - 4, D)
  end
  T.text_main, T.text_dim, T.text_sh = O, "#4A3A22", nil
  T.title = { H, O }
  function T.checkbox(c, x, y, on)
    T.slot(c, x, y, 10, 0)
    if on then c:rect(x + 3, y + 3, 4, 4, H); c:rect(x + 4, y + 4, 2, 2, B) end
  end
  T.icon = {
    outline = O, shade = true,
    main = function() return B end, hi = function() return H end, lo = function() return L end, detail = function() return D end,
  }
  T.wheel = { ink = O, ring = A, hi = Ah, lo = Al, hub = B, hubhi = H, hublo = L, wedge = "#EBC25E" }
  T.item_sh = O
  THEMES[#THEMES + 1] = T
end

-- B: Build Buddy ----------------------------------------------------------------------------------------------------
do
  local N0, N1, N2, N3, W, CY = "#0A1E45", "#0F2A5C", "#173B7E", "#2A64C4", "#E8F7FF", "#6FC4F5"
  local T = { name = "B_buildbuddy", bg = "#101820" }
  function T.panel(c, x, y, w, h)
    c:rect(x, y, w, h, N1)
    for j = 0, h - 1 do for i = 0, w - 1 do
      if (i + 1) % 4 == 0 or (j + 1) % 4 == 0 then c:px(x + i, y + j, N2) end
    end end
    c:frame(x, y, w, h, CY)
    for _, p in ipairs({ { 0, 0, 1, 1 }, { w - 1, 0, -1, 1 }, { 0, h - 1, 1, -1 }, { w - 1, h - 1, -1, -1 } }) do
      for k = 0, 3 do c:px(x + p[1] + p[3] * k, y + p[2], W); c:px(x + p[1], y + p[2] + p[4] * k, W) end
    end
  end
  function T.inset(c, x, y, w, h)
    c:rect(x, y, w, h, N0)
    for i = 0, w - 1 do if i % 4 < 2 then c:px(x + i, y, CY); c:px(x + i, y + h - 1, CY) end end
    for j = 0, h - 1 do if j % 4 < 2 then c:px(x, y + j, CY); c:px(x + w - 1, y + j, CY) end end
  end
  function T.button(c, x, y, w, h, st)
    c:rect(x, y, w, h, st == 2 and W or (st == 1 and N3 or N1))
    c:frame(x, y, w, h, st == 0 and CY or W)
    if st == 1 then
      c:px(x + 2, y + 2, W); c:px(x + w - 3, y + 2, W); c:px(x + 2, y + h - 3, W); c:px(x + w - 3, y + h - 3, W)
    end
  end
  function T.label(c, x, y, w, h, s, st)
    c:text(x + (w - tw(s)) // 2, y + (h - 7) // 2, s, st == 2 and N0 or W)
  end
  function T.slot(c, x, y, s, st)
    c:rect(x, y, s, s, st == 1 and N3 or N0)
    for i = 0, s - 1 do if i % 4 < 2 then c:px(x + i, y, CY); c:px(x + i, y + s - 1, CY); c:px(x, y + i, CY); c:px(x + s - 1, y + i, CY) end end
    if st == 2 then c:frame(x, y, s, s, W); c:frame(x + 1, y + 1, s - 2, s - 2, W) end
  end
  function T.bar(c, x, y, w, h, f)
    c:rect(x, y, w, h, N0); c:frame(x, y, w, h, W)
    local fw = math.floor((w - 4) * f + 0.5)
    for j = 0, h - 5 do for i = 0, fw - 1 do
      c:px(x + 2 + i, y + 2 + j, ((i + j) % 4 < 2) and W or CY)
    end end
  end
  function T.tab(c, x, y, w, h, on)
    c:rect(x, y, w, h + 1, on and N3 or N0)
    c:hline(x, y, w, on and W or CY); c:vline(x, y, h + 1, on and W or CY); c:vline(x + w - 1, y, h + 1, on and W or CY)
    if not on then c:hline(x, y + h, w, CY) end
  end
  function T.tabtext(c, x, y, w, h, s, on) c:ctext(x, y + (h - 7) // 2 + 1, w, s, on and W or CY) end
  function T.tooltip(c, x, y, w, h)
    c:rect(x, y, w, h, N0); c:frame(x, y, w, h, CY)
    c:rect(x, y, 3, 1, W); c:rect(x, y, 1, 3, W); c:rect(x + w - 3, y + h - 1, 3, 1, W); c:rect(x + w - 1, y + h - 3, 1, 3, W)
  end
  T.text_main, T.text_dim = W, CY
  function T.checkbox(c, x, y, on)
    T.slot(c, x, y, 10, 0)
    if on then c:rect(x + 3, y + 3, 4, 4, W) end
  end
  T.icon_pressed = { shade = false, main = function() return N0 end, hi = function() return N0 end, lo = function() return N0 end, detail = function() return N3 end }
  T.icon = { shade = false, main = function() return W end, hi = function() return W end, lo = function() return W end, detail = function() return CY end }
  T.wheel = { ink = W, ring = N1, hi = N1, lo = N1, hub = N0, hubhi = N0, hublo = N0, wedge = N3 }
  T.item_sh = N0
  THEMES[#THEMES + 1] = T
end

-- C: Toybox -------------------------------------------------------------------------------------------------------
do
  local I, P, P2, S = "#2D1B4E", "#FFF4D9", "#EAD7AB", "#CDB88A"
  local G, Gh, Gl = "#5BD66B", "#A4F2AD", "#2FA84A"
  local T = { name = "C_toybox", bg = "#3A2F63" }
  function T.panel(c, x, y, w, h)
    c:rr(x, y, w, h, 3, I)
    c:rr(x + 2, y + 2, w - 4, h - 4, 2, P2)
    c:rr(x + 2, y + 2, w - 4, h - 6, 2, P)
    c:hline(x + 4, y + 3, w - 8, "#FFFFFF")
  end
  function T.inset(c, x, y, w, h)
    c:rr(x, y, w, h, 2, I); c:rr(x + 1, y + 1, w - 2, h - 2, 1, "#E2CC98")
    c:hline(x + 2, y + 1, w - 4, S)
  end
  function T.button(c, x, y, w, h, st)
    local f, hi, lo = G, Gh, Gl
    if st == 1 then f, hi = "#80EC8E", "#D2FFD8" end
    c:rr(x, y, w, h, 2, I)
    if st == 2 then
      c:rr(x + 1, y + 1, w - 2, h - 2, 1, Gl)
      c:rr(x + 1, y + 3, w - 2, h - 4, 1, G)
    else
      c:rr(x + 1, y + 1, w - 2, h - 2, 1, lo)
      c:rr(x + 1, y + 1, w - 2, h - 4, 1, f)
      c:hline(x + 3, y + 2, w - 6, hi)
    end
  end
  function T.label(c, x, y, w, h, s, st)
    local oy = st == 2 and 2 or 0
    c:otext(x + (w - tw(s)) // 2, y + (h - 7) // 2 - 1 + oy, s, "#FFFFFF", I)
  end
  function T.slot(c, x, y, s, st)
    c:rr(x, y, s, s, 2, I); c:rr(x + 1, y + 1, s - 2, s - 2, 1, st == 1 and "#F4E3B8" or "#E2CC98")
    c:hline(x + 2, y + 1, s - 4, S)
    if st == 2 then c:rr(x - 1, y - 1, s + 2, s + 2, 2, I); c:rr(x, y, s, s, 2, G); c:rr(x + 1, y + 1, s - 2, s - 2, 1, "#F4E3B8") end
  end
  function T.bar(c, x, y, w, h, f)
    c:rr(x, y, w, h, 2, I); c:rr(x + 1, y + 1, w - 2, h - 2, 1, P2)
    local fw = math.floor((w - 2) * f + 0.5)
    if fw > 1 then
      c:rr(x + 1, y + 1, fw, h - 2, 1, "#FF8A3D")
      c:hline(x + 2, y + 2, math.max(1, fw - 2), "#FFC27A")
      c:hline(x + 2, y + h - 3, math.max(1, fw - 2), "#E4601F")
    end
  end
  function T.tab(c, x, y, w, h, on)
    c:rr(x, y, w, h + 2, 2, I); c:rr(x + 1, y + 1, w - 2, h + 1, 1, on and P or "#B9A2E8")
    if on then c:hline(x + 2, y + 2, w - 4, "#FFFFFF") else c:hline(x + 2, y + 2, w - 4, "#D3C2F5") end
  end
  function T.tabtext(c, x, y, w, h, s, on) c:ctext(x, y + (h - 7) // 2 + 1, w, s, on and I or "#FFFFFF", nil) end
  function T.tooltip(c, x, y, w, h)
    c:rr(x, y, w, h, 2, I); c:rr(x + 1, y + 1, w - 2, h - 2, 1, "#5A3F92"); c:hline(x + 2, y + 1, w - 4, "#7A5CB8")
  end
  T.text_main, T.text_dim, T.text_sh = I, "#8A6FB5", nil
  T.light_text = true
  function T.checkbox(c, x, y, on)
    c:rr(x, y, 10, 10, 2, I); c:rr(x + 1, y + 1, 8, 8, 1, on and G or "#E2CC98")
    if on then c:hline(x + 3, y + 2, 4, Gh); c:px(x + 2, y + 5, "#FFFFFF"); c:px(x + 3, y + 6, "#FFFFFF"); c:px(x + 4, y + 7, "#FFFFFF"); c:px(x + 5, y + 6, "#FFFFFF"); c:px(x + 6, y + 5, "#FFFFFF"); c:px(x + 7, y + 4, "#FFFFFF") end
  end
  T.icon = {
    outline = I, shade = true,
    main = function(n) return ICON_COL[n] end,
    hi = function(n) return lighten(ICON_COL[n], 0.45) end,
    lo = function(n) return lighten(ICON_COL[n], -0.22) end,
    detail = function(n) return "#FFFFFF" end,
  }
  T.wheel = { ink = I, ring = P, hi = "#FFFFFF", lo = P2, hub = G, hubhi = Gh, hublo = Gl, wedge = "#FFD23D" }
  T.item_sh = I
  THEMES[#THEMES + 1] = T
end

-- composition ----------------------------------------------------------------------------------------------------
local ITEMS = {
  { "STONE BRICK", "X128", "#8E8E99", "#B9B9C4", 0.80 },
  { "OAK PLANKS", "X64", "#B8935A", "#D9B97C", 0.35 },
  { "GLASS PANE", "X24", "#9AD8F0", "#D6F4FF", 0.0 },
}
local function item(c, T, x, y, col, hi)
  c:rect(x, y, 10, 10, T.item_sh); c:rect(x + 1, y + 1, 8, 8, col); c:rect(x + 1, y + 1, 8, 2, hi); c:rect(x + 1, y + 1, 2, 8, hi)
end
local function icon_at(c, T, name, x, y, pressed) paint_icon(c, x, y, name, (pressed and T.icon_pressed) or T.icon) end

local function preview(T)
  local c = newc()
  c:rect(0, 0, 288, 168, T.bg)
  -- toolbar
  T.panel(c, 4, 6, 30, 114)
  local tools = { "move", "rotate", "mirror", "layers", "eye" }
  for i, n in ipairs(tools) do
    local st = (i == 1) and 2 or (i == 3 and 1 or 0)
    local y = 11 + (i - 1) * 21
    T.button(c, 9, y, 20, 19, st)
    icon_at(c, T, n, 9 + 3, y + 2 + (st == 2 and 1 or 0), st == 2)
  end
  -- title
  T.panel(c, 40, 6, 152, 32)
  c:text(48, 12, "CASTLE.LITEMATIC", T.text_main, T.text_sh)
  T.bar(c, 48, 24, 112, 8, 0.62)
  c:text(166, 25, "62%", T.text_main)
  -- tabs + materials
  T.tab(c, 42, 44, 60, 12, true); T.tabtext(c, 42, 44, 60, 12, "MATERIALS", true)
  T.tab(c, 104, 44, 46, 12, false); T.tabtext(c, 104, 44, 46, 12, "LAYERS", false)
  T.panel(c, 40, 56, 152, 72)
  for i, it in ipairs(ITEMS) do
    local y = 61 + (i - 1) * 21
    T.slot(c, 46, y, 18, i == 1 and 2 or 0)
    item(c, T, 50, y + 4, it[3], it[4])
    c:text(70, y + 2, it[1], T.text_main, T.text_sh)
    c:text(186 - tw(it[2]), y + 2, it[2], T.text_dim)
    T.bar(c, 70, y + 11, 90, 5, it[5])
  end
  -- buttons
  T.button(c, 40, 136, 48, 18, 0); T.label(c, 40, 136, 48, 18, "PLACE", 0)
  T.button(c, 92, 136, 48, 18, 1); T.label(c, 92, 136, 48, 18, "CANCEL", 1)
  T.button(c, 144, 136, 48, 18, 2); T.label(c, 144, 136, 48, 18, "SAVE", 2)
  -- wheel
  wheel(c, 204, 6, T.wheel, true)
  local ring = { "move", "rotate", "mirror", "layers", "save", "list" }
  for k, n in ipairs(ring) do
    local a = math.rad((k - 1) * 60)
    local ix = math.floor(204 + 31.5 + math.sin(a) * 22.5 - 6.5 + 0.5)
    local iy = math.floor(6 + 31.5 - math.cos(a) * 22.5 - 6.5 + 0.5)
    icon_at(c, T, n, ix, iy)
  end
  icon_at(c, T, "eye", 204 + 25, 6 + 25)
  -- tooltip + checkboxes
  T.tooltip(c, 198, 76, 84, 32)
  local tcol = (T.name:sub(1, 1) == "B") and "#E8F7FF" or ((T.name:sub(1, 1) == "C") and "#FFFFFF" or "#F6D77E")
  c:text(204, 82, "MOVE", tcol)
  c:text(204, 92, "DRAG ARROWS", (T.name:sub(1, 1) == "B") and "#6FC4F5" or ((T.name:sub(1, 1) == "C") and "#D3C2F5" or "#B79A62"))
  T.checkbox(c, 200, 114, true); c:text(214, 115, "GHOST", "#FFFFFF")
  T.checkbox(c, 200, 128, false); c:text(214, 129, "GRID", "#FFFFFF")
  -- axis arrows
  return c
end

local function sheet(T)
  local c = newc()
  c:rect(0, 0, 288, 150, T.bg)
  T.panel(c, 6, 6, 44, 38)
  T.inset(c, 56, 6, 30, 38)
  for i = 0, 2 do
    T.button(c, 92, 6 + i * 13, 50, 12, i); T.label(c, 92, 6 + i * 13, 50, 12, "BUTTON", i)
  end
  for i = 0, 2 do T.slot(c, 150 + i * 22, 6, 18, i) end
  T.checkbox(c, 150, 30, true); T.checkbox(c, 166, 30, false)
  T.bar(c, 150, 46, 60, 7, 0.0); 
  T.bar(c, 150, 56, 60, 7, 0.5)
  T.bar(c, 150, 66, 60, 7, 1.0)
  T.tab(c, 6, 52, 44, 12, true); T.tabtext(c, 6, 52, 44, 12, "TAB", true)
  T.tab(c, 52, 52, 44, 12, false); T.tabtext(c, 52, 52, 44, 12, "TAB", false)
  T.tooltip(c, 6, 72, 64, 22)
  c:text(12, 78, "TOOLTIP", (T.name:sub(1,1)=="B") and "#E8F7FF" or ((T.name:sub(1,1)=="C") and "#FFFFFF" or "#F6D77E"))
  wheel(c, 216, 6, T.wheel, false)
  wheel(c, 216, 74, T.wheel, true)
  for i, n in ipairs(ICON_ORDER) do icon_at(c, T, n, 6 + ((i - 1) % 7) * 16, 100 + ((i - 1) // 7) * 16) end
  for i, ax in ipairs({ "#FF4D4D", "#4DD968", "#4D8BFF" }) do
    -- axis handles reuse the arrow icon recoloured
    local P = { outline = T.icon.outline, shade = true, main = function() return ax end, hi = function() return lighten(ax, 0.45) end, lo = function() return lighten(ax, -0.25) end, detail = function() return "#FFFFFF" end }
    if not T.icon.outline then P.shade = false end
    paint_icon(c, 130 + (i - 1) * 20, 108, "arrow", P)
  end
  return c
end

-- run
for _, T in ipairs(THEMES) do
  local dir = ROOT .. T.name .. "/"
  os.execute('mkdir -p "' .. dir .. '"')
  preview(T):save(dir .. "preview", 288, 168)
  sheet(T):save(dir .. "sheet", 288, 150)
end

-- final export of theme B ----------------------------------------------------------------------------------------
local B
for _, T in ipairs(THEMES) do if T.name == "B_buildbuddy" then B = T end end
local FD = ROOT .. "B_final/"
os.execute('mkdir -p "' .. FD .. '"')
local function one(name, w, h, fn) local c = newc(); fn(c); c:save(FD .. name, w, h) end
one("panel", 24, 24, function(c) B.panel(c, 0, 0, 24, 24) end)
one("inset", 16, 16, function(c) B.inset(c, 0, 0, 16, 16) end)
for st = 0, 2 do
  one("button_" .. st, 16, 16, function(c) B.button(c, 0, 0, 16, 16, st) end)
  one("slot_" .. st, 18, 18, function(c) B.slot(c, 0, 0, 18, st) end)
end
one("bar_track", 16, 8, function(c) B.bar(c, 0, 0, 16, 8, 0) end)
one("bar_fill", 4, 4, function(c) for j = 0, 3 do for i = 0, 3 do c:px(i, j, ((i + j) % 4 < 2) and "#E8F7FF" or "#6FC4F5") end end end)
one("tab_on", 16, 13, function(c) B.tab(c, 0, 0, 16, 12, true) end)
one("tab_off", 16, 13, function(c) B.tab(c, 0, 0, 16, 12, false) end)
one("tooltip", 16, 16, function(c) B.tooltip(c, 0, 0, 16, 16) end)
one("checkbox_on", 10, 10, function(c) B.checkbox(c, 0, 0, true) end)
one("checkbox_off", 10, 10, function(c) B.checkbox(c, 0, 0, false) end)
for _, n in ipairs(ICON_ORDER) do
  one("icon_" .. n, 14, 14, function(c) paint_icon(c, 0, 0, n, B.icon) end)
  one("icon_" .. n .. "_dark", 14, 14, function(c) paint_icon(c, 0, 0, n, B.icon_pressed) end)
end
one("wheel_base", 64, 64, function(c) wheel(c, 0, 0, B.wheel, false) end)
one("wheel_wedge", 64, 64, function(c)
  local full, base = newc(), newc()
  wheel(full, 0, 0, B.wheel, true); wheel(base, 0, 0, B.wheel, false)
  for k, v in pairs(full.p) do if base.p[k] ~= v then c.p[k] = v end end
end)
print("final done")
print("done")
