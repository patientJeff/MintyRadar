# MintyRadar update log

Newest version first. Downloads for every version are on the [Releases](https://github.com/patientJeff/MintyRadar/releases) page.

---

## 1.3.0

### New
- **Terrain map.** Settings → **Terrain**: a top-down map of the loaded ground under the radar, in map colours with height shading. **Cave View** maps the floor around your height instead (always on in the Nether). Off by default.
- **North Up.** Settings → Radar Display → **Rotation**: north stays at the top and your arrow turns instead of the map.
- **Facing lines** show which way each player is looking.
- **Team colours.** Players are framed and named in their scoreboard team colour.
- **Scroll zoom.** Hold Left Alt and scroll to zoom. The key can be changed with the other keybinds.
- **More zoom levels:** 16, 24, 192 and 256 blocks join 32 to 128.
- **Health in the player list**, coloured green to red. Distances now turn red as players get close.
- **Dropped items** as yellow dots (off by default).
- **Trails**: fading dots showing where each player has been, 3 to 120 seconds back (off by default).
- **Alert watch list.** Turn on **Watch List Only** to get alerts only for the players you add.
- **Invisible players** (off by default): show invisible players and mobs, marked "(invisible)" in the list.
- **Portals** (off by default): nether portals, end portals and end gateways in loaded chunks show as purple squares.

Invisible Players and Portals show things you couldn't normally see, so many servers count them as cheating. Check the rules first.

### Changed
- **Now on more Minecraft versions:** 1.21.11, 26.1 (also 26.1.1 and 26.1.2), 26.2 and 26.3, each with its own jar (e.g. `MintyRadar-1.3.0+1.21.11.jar`). Every version has the same features.
- The settings file is now `config/MintyRadar.json`. Your old `minty_radar.json` is moved over automatically, keeping all your settings.
- The mod is now called **MintyRadar** everywhere.
- Needs Fabric Loader 0.19.5 or newer.
- Faster drawing: the round radar's background and the distance rings are drawn once into a texture instead of hundreds of small pieces every frame.

---

## 1.2.0

### New
- **Put the radar anywhere.** Settings → Layout → **Move Radar...** lets you drag the radar to any spot on screen. It stays in place when you resize the window or change GUI scale. The player list moves to whichever side has more room. **Reset to Corner** (or picking a corner) puts it back.
- **Ping in the tab list.** Each player's ping now shows in milliseconds (e.g. `42ms`) instead of signal bars, coloured from green (good) to red (bad).
  - Your own ping is measured live every second, like F3's network chart.
  - Other players' pings come from the server, which is the only source a client has.
  - Turn it off under Settings → **Tab List** to get the vanilla bars back.
- **Mob heads.** Mobs on the radar now show as their own faces (with the outer layer, like zombies' hats) instead of dots. They come from each mob's real model and texture, so every mob and variant looks right. Hostile mobs get a red frame and others a gray frame. Turn mobs on under Settings → Radar Display → **Mobs**.
- **Reset to Corner** button next to **Move Radar...** in the settings.

### Fixed
- **Move Radar:** dragging the radar didn't work with a real mouse. It now follows your cursor.
- **Friends:** adding or removing a friend no longer duplicates the settings screen or makes it stop responding. The name box stays selected, so you can add several friends in a row.

---

## 1.1.0 (first release)

### Radar
- Rotating mini radar, **square or round**, that turns with your view
- Players shown as their **skin heads** (with hat layer), or as plain dots
- **Compass** letters around the edge, with north in mint
- **Distance rings** at half and full range, labelled with their distance
- **Δ/∇ markers** for players above or below you
- Players beyond range are pinned to the edge, faded, so you still see which way they are
- Player **names** above their heads: always, while you sneak, or never
- Optional **mobs**: hostile ones in red, others in gray (map only)

### Player list
- Every player the radar can see, nearest first, with their head, name and distance
- Optional **limit**, with the rest shown as "+N more"

### Alerts and friends
- **Player alerts**: "Steve is 40m away" with an optional sound when someone comes close
- **Friends list**: show friends with a mint ★, or hide them. Friends never trigger alerts

### Settings and controls
- Full settings screen: press **K**, or use Mod Menu's Configure button (Mod Menu is optional)
- Adjustable range (32–128 blocks), size, corner, margin, background opacity and **text size**
- Keybinds: `R` toggle radar, `=` / `-` zoom, `K` settings (all rebindable)
