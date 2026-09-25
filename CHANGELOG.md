# Minty Radar update log

Newest version first. Downloads for every version are on the [Releases](https://github.com/patientJeff/MintyRadar/releases) page.

---

## 1.2.0

### New
- **Put the radar anywhere.** Settings → Layout → **Move Radar...** lets you drag the radar to any spot on screen. It stays in place when you resize the window or change GUI scale. The player list moves to whichever side has more room. **Reset to Corner** (or picking a corner) puts it back.
- **Ping in the tab list.** Each player's ping now shows in milliseconds (e.g. `42ms`) instead of signal bars, coloured from green (good) to red (bad).
  - Your own ping is measured live every second, like F3's network chart.
  - Other players' pings come from the server, which is the only source a client has.
  - Turn it off under Settings → **Tab List** to get the vanilla bars back.

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
