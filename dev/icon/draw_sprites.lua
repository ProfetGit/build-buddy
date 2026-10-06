-- Cyanotype lettering: banner title and tagline (the textures are dev/make_textures.py, the paper is dev/make_icon.py).
-- Run: aseprite --batch --script dev/icon/draw_sprites.lua  (the Steam copy), or through the aseprite MCP.
dofile("/home/emppu/Projects/Minecraft Datapacks/.claude/skills/pack-icon-animation/assets/pixel_art.lua")
local OUT = "/home/emppu/Projects/Minecraft Datapacks/mods/Cyanotype/dev/icon/sprites/"

local bands = { "#FFFFFF", "#E8F6FF", "#E8F6FF", "#E8F6FF", "#8FE8FF", "#8FE8FF", "#8FE8FF", "#46BDEB", "#46BDEB", "#46BDEB" }
PA.title_sprite(OUT .. "banner_title", "CYANOTYPE", { bands = bands, extrude = { "#2C66C9", "#1B438E" }, outline = "#0A1B30" })
PA.SMALL[","] = {"..","..","..","..",".#",".#","#."}   -- a taller comma, the kit one reads as a full stop at x6
PA.label_sprite(OUT .. "banner_tagline", "BLUEPRINTS, MADE EASY.", function(i) return i > 12 and "#7FE3FF" or "#FFFFFF" end, "#0A1B30")
