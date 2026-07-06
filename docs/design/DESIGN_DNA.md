# Phoebe Tactical Hi-Fi Design DNA

This document is an implementation brief for the tactical hi-fi direction shown
in the generated mobile and desktop mockups. It is meant for an AI implementation
agent. Treat it as a product design contract, not as inspiration.

This is a separate visual direction from `docs/design/phoebe-elegant/DESIGN.md`.
Do not blend the two. The elegant system uses soft glass and amethyst. Tactical
Hi-Fi uses a rigid black/white/red telemetry language.

## 1. Product Atmosphere

Phoebe Tactical Hi-Fi should feel like a beautiful listening terminal: a premium
music player with the discipline of aerospace telemetry, the tactility of a
studio hardware rack, and the emotional focus of album artwork.

Use this intensity model:

- **Density:** Cockpit Balanced, 7/10. Desktop can hold dense metadata, queues,
  lyrics, credits, and library grids. Mobile must stay readable and calmer.
- **Variance:** Engineered Asymmetric, 7/10. Layouts use strong grid zones,
  off-center album art, and distinct screen rhythm instead of centered templates.
- **Motion:** Mechanical Fluid, 6/10. Motion is precise, weighty, and minimal:
  waveform life, row cascades, transport feedback, and sheet rise.
- **Emotion:** Album art supplies warmth and color. The chrome stays strict.

The app must still behave like a music player first. Navigation, playback,
queue, lyrics, album detail, artwork gallery, and library search remain more
important than decorative telemetry.

## 2. Color Palette and Roles

Use one dark tactical substrate. Never mix this direction with light mode inside
the same surface.

- **Deactivated CRT** (#0A0A0A) - Primary app background. Never use pure black.
- **Terminal Panel** (#101010) - Main panels, desktop content wells, mobile
  screen background.
- **Raised Instrument** (#151515) - Bottom transport, queue sheets, inspectors,
  selected album detail panes.
- **Pressed Metal** (#1E1E1E) - Hover and pressed states, active table rows,
  subtle selected navigation backgrounds.
- **Grid Line** (rgba(234,234,234,0.14)) - 1px structural dividers and table
  row separators.
- **Faint Rule** (rgba(234,234,234,0.075)) - Secondary dividers, waveform
  tracks, inactive outlines.
- **White Phosphor** (#EAEAEA) - Primary text, active icons, current track.
- **Cool Ash** (#A8A8A8) - Artist names, secondary labels, subtitles.
- **Dim Signal** (#6F6F6F) - Disabled controls, timestamps, secondary metadata.
- **Aviation Red** (#FF2A2A) - The only real accent. Use for active playback,
  progress, selected rows, focus rings, current-track markers, and one or two
  critical action states.
- **Red Wash** (rgba(255,42,42,0.14)) - Selected-row fill, focus area tint,
  and pressed red states only.
- **Playback Green** (#4AF626) - Optional. Use only for a single "playing/live"
  status dot or source-online indicator. Never use green as a general accent.

Album artwork may contain any color, but the app chrome must not introduce
additional chromatic accents. No purple, blue neon, rainbow gradients, or
secondary brand colors.

## 3. Typography Rules

Use typography as structure. The system needs two voices: heavy display type for
music identity and mono telemetry for technical information.

- **Display:** Cabinet Grotesk, Satoshi, or Archivo Black. Use for large album
  names, artist names, screen titles, and "PHOEBE" branding. Weight 700-900.
  Line height 0.9-1.05. Letter spacing 0 to -0.02em for display only.
- **Body:** Satoshi, Geist, or the existing app sans. Use for track titles,
  lyrics, labels that must be read quickly, settings, and action copy. Line
  height 1.35-1.55. Body text never drops below 14px on mobile.
- **Mono:** JetBrains Mono, Geist Mono, IBM Plex Mono, or the existing app mono.
  Use for durations, codec, bitrate, sample rate, source, catalog IDs, queue
  numbers, timestamps, and dense metadata. Letter spacing 0.04em-0.08em.

Scale targets:

- **Desktop macro title:** 44-72px, 800-900 weight, max 2 lines.
- **Desktop screen title:** 28-40px, 700-800 weight.
- **Desktop body/track row:** 14-16px.
- **Desktop mono metadata:** 11-13px, uppercase where short.
- **Mobile player title:** 24-30px, 700-800 weight, max 2 lines.
- **Mobile section title:** 18-22px, 700 weight.
- **Mobile body/track row:** 14-16px.
- **Mobile metadata:** 11-12px, but only when non-essential.

Rules:

- Use uppercase for structural labels only: "NOW PLAYING", "QUEUE", "CREDITS",
  "SOURCE", "LOCAL", "LOSSLESS". Do not uppercase long track names or lyrics.
- Use numbers in monospace wherever rows need alignment.
- Do not use Inter as the chosen premium typeface for this direction.
- Do not use serif type.
- Avoid tiny decorative text. If it cannot be read in a normal screenshot, cut it.

## 4. Shape System

This direction is mechanical. Internal app geometry should be rigid and square.

- **Primary panels:** 0px radius. Use visible 1px dividers instead of soft cards.
- **Desktop app window:** May keep platform-native outer radius if the host
  window provides one. Do not add large decorative corner radius inside it.
- **Album artwork:** 0-4px radius. Prefer square covers. Never circle-crop albums.
- **Artist imagery:** 0-6px radius. Avoid generic circular avatars unless the
  current app pattern absolutely requires them.
- **Buttons:** Rectangular or circular only when the control is a standard media
  action. Play/pause may be circular. Utility buttons are square 44px targets.
- **Bottom sheets:** Prefer square or 8px top corners. Avoid soft 24px mobile
  sheet corners from the elegant system.
- **Focus rings:** 1-2px Aviation Red outline, offset by 2px, no glow.

No pill spam. Segmented controls may be rectangular tab strips, not bubbly chips.

## 5. Surface and Texture

The app should look physically grounded without becoming noisy.

- Use 1px grid lines and panel seams as the primary depth tool.
- Add subtle scanline or noise texture only as a global overlay or background
  layer. Keep opacity low enough that text remains crisp.
- Do not use glassmorphism, blur-heavy panels, neon shadows, glossy plastic, or
  decorative gradient blobs.
- Red accents are flat fills or rules, not glows.
- Shadows should be nearly absent inside the app. Let borders, spacing, and
  artwork create hierarchy.

If texture affects performance, remove it from scrolling lists first. The design
works without texture as long as the grid, typography, and palette are correct.

## 6. Iconography

Icons should feel like hardware symbols and music controls, not generic template
line icons.

- Use consistent stroke width, preferably 1.75-2px.
- Use squared line caps where the icon library allows it.
- Media icons may use filled geometry for stronger recognition.
- Utility icons sit in 44px square hit areas.
- Active icons use White Phosphor or Aviation Red. Inactive icons use Cool Ash.
- Do not mix filled, outlined, rounded, and thin icons randomly.

## 7. Layout Architecture

### Desktop App Shell

Desktop uses five persistent zones:

1. **Left navigation rail:** 220-260px. Brand, primary navigation, playlists,
   library shortcuts, account/source state.
2. **Main content stage:** Flexible. Home, library, search, album/artist detail,
   or Now Playing hero.
3. **Right context rail:** 300-380px. Queue, lyrics, credits, artwork gallery,
   related albums, source details, or inspector metadata.
4. **Bottom transport:** 92-112px. Current track, waveform/progress, transport,
   volume, device/output, queue toggle.
5. **Top utility strip:** 48-64px where needed. Search, filters, source state,
   sync status, and view options.

Desktop composition rules:

- Use CSS Grid or Compose layout primitives with explicit widths and dividers.
- Avoid soft floating cards. Panels touch the grid and are separated by 1px lines.
- Keep one large emotional anchor per screen, usually album artwork.
- Let dense metadata sit in tables or definition-list blocks, not in stat cards.
- Do not create a generic SaaS dashboard. This is a music player.

### Mobile App Flow

Mobile uses a real app flow with a persistent mini-player:

- **Library / Listen:** Top brand/search, curated shelves, recent albums, sticky
  mini player, bottom navigation.
- **Album Detail:** Large square artwork, clear primary action, track list,
  credits/about block, artwork gallery entry.
- **Now Playing:** Large artwork, current track, waveform/progress, transport,
  output/like/more controls, compact queue affordance.
- **Queue / Lyrics Sheet:** Bottom sheet or pushed surface with queue rows,
  lyrics, and reorder affordances.

Mobile composition rules:

- Respect safe areas and bottom navigation/home indicator space.
- Keep touch targets at least 44px.
- Avoid nested cards. Use dividers, section spacing, and flat panels.
- Keep mobile density lower than desktop. Two dense blocks per screen is enough.
- Every screen needs a clear playback path back to Now Playing.

## 8. Core Components

### Navigation Rail

- Black substrate, 1px right divider, square active indicator.
- Active destination uses Aviation Red left rule plus White Phosphor text.
- Use mono labels only for source/status metadata, not every nav item.
- Keep playlist/library entries readable and left aligned.

### Album Cover

- Square frame, stable aspect ratio, hard edge or 4px radius max.
- Artwork may use halftone, monochrome, red marks, or editorial photography.
- Hover on desktop scales the image internally to 1.03 without resizing the frame.
- Current playing artwork can receive a red 1px outline or corner marker.

### Track and Queue Rows

- Row height: 52-64px desktop, 56-68px mobile.
- Structure: index/status, thumbnail optional, title/artist, duration, actions.
- Current row: red left rule or red index, stronger title weight, optional green
  live dot. Do not rely on color alone.
- Use dividers instead of row cards.
- Drag/reorder handles are visible only where reordering is available.

### Transport

- Bottom transport is always present on desktop and as mini-player on mobile
  non-player screens.
- Play/pause is the only dominant media button. It may be circular.
- Previous/next/shuffle/repeat are 44px controls with quiet states.
- Progress uses Aviation Red over a Faint Rule track.
- Waveform bars are music visualization, not fake analytics. Keep them slim,
  irregular, and secondary.

### Search and Filters

- Search is a rectangular field with 1px border and dark fill.
- Height: 40-48px desktop, 44-52px mobile.
- Focus uses Aviation Red outline. No glow.
- Filters are rectangular tab strips or segmented controls. No pill clouds.

### Lyrics and Credits

- Lyrics should be readable first, stylish second.
- Active lyric line may use White Phosphor with a red left marker.
- Inactive lyric lines use Cool Ash or Dim Signal.
- Credits use definition-list/table formatting: role, person, source, catalog.
- Avoid long paragraphs inside tiny panels.

### Artwork Gallery

- Use a horizontal strip or grid of stable media frames.
- Selected artwork uses a red outline and a hard-edged focus state.
- Gallery entry must work from both framed artwork and full-bleed/detail artwork
  paths when the implementation has both.

### Empty, Loading, and Error States

- Loading uses skeleton rows and square album placeholders matching final layout.
- Empty states use a single square artwork placeholder plus direct action.
- Errors are inline, red-accented, and specific. No giant modal for recoverable
  media/library failures.

## 9. Motion and Interaction

Motion must feel like calibrated hardware.

- **Spring feel:** Medium stiffness, medium damping. No bouncy toy motion.
- **Rows:** Stagger in 20-40ms cascades using opacity and translateY only.
- **Album art:** Fade and translate 8-12px on entry. Hover scales internal image.
- **Transport:** Button press scales to 0.96-0.98 and translates 1px.
- **Sheets:** Rise from bottom with a short spring. Scrim is subtle and flat.
- **Waveform:** While playing, use quiet continuous amplitude/opacity movement.
- **Focus:** Keyboard and D-pad focus use red outlines with no layout shift.
- **Performance:** Animate transform and opacity only. Do not animate width,
  height, top, left, blur, or expensive filters during scroll.

Respect reduced motion. When reduced motion is enabled, keep state changes
instant or use a short opacity fade.

## 10. Accessibility and Usability

- Meet WCAG AA contrast for text and controls.
- Color is never the only active/current indicator.
- Every tappable/clickable target is at least 44px.
- Text must not overlap artwork or controls.
- Long track names truncate with a clear strategy and should not shift controls.
- Keyboard, D-pad, and screen-reader navigation must preserve logical order:
  navigation, main content, context rail/sheet, transport.
- Lyrics, queue, and search results need stable reading order.

## 11. Screen Contracts

### Desktop Now Playing

Must include:

- Left navigation rail.
- Large current album artwork.
- Current track and artist.
- Queue or lyrics in the right rail.
- Bottom transport with waveform/progress.
- Source/output and volume controls.
- At least one structured metadata block.

Avoid:

- Centered marketing hero composition.
- Floating glass cards.
- Fake chart dashboards.

### Desktop Library and Album Detail

Must include:

- Search/filter strip.
- Album grid or collection shelf with square covers.
- Selected album detail panel.
- Track list with durations.
- Credits/about block.
- Artwork gallery strip.
- Sticky bottom mini transport.

Avoid:

- Three equal feature cards.
- Overly soft card stacks.
- Empty album grids with decorative filler.

### Mobile Library

Must include:

- Brand/search header.
- Album shelves or recent listening rows.
- Bottom navigation.
- Sticky mini-player.

Avoid:

- Dense desktop table squeezed into phone width.
- More than one row of filter pills.

### Mobile Now Playing

Must include:

- Large album artwork.
- Current track and artist.
- Waveform/progress.
- Play/pause, previous, next, shuffle, repeat.
- Queue/lyrics affordance.

Avoid:

- Tiny progress labels.
- Decorative metadata above the artwork.
- Album art hidden by overlays.

### Mobile Album Detail

Must include:

- Artwork-led header.
- Primary playback action.
- Track list.
- Credits/about section.
- Artwork gallery entry.

Avoid:

- Full-screen poster with no app navigation.
- Nested cards inside cards.

## 12. Implementation Rules for AI Agents

Follow this order:

1. Define design tokens first: colors, typography, spacing, shape, dividers, and
   motion specs.
2. Build reusable primitives: square panel, divider, media frame, track row,
   transport button, waveform, tab strip, metadata table, queue row.
3. Apply the system to desktop shell and mobile shell before individual screens.
4. Convert one screen family at a time: desktop transport, desktop player,
   desktop library/detail, mobile mini-player, mobile player, mobile detail.
5. Preserve existing Phoebe behavior. This document changes visual language, not
   playback, queue, search, auth, or metadata semantics.
6. Add screenshot tests or previews for at least desktop player, desktop library,
   mobile player, and mobile album detail if the repo test setup supports them.

When in doubt, choose fewer components with stronger grid discipline. The design
should look intentionally engineered, not decorated.

## 13. Anti-Patterns

Never implement these in Tactical Hi-Fi:

- No pure black (#000000).
- No purple or blue neon accents.
- No glassmorphism.
- No gradient blobs, orbs, or bokeh backgrounds.
- No soft rounded card stacks.
- No nested cards inside cards.
- No generic three-column card layouts.
- No fake analytics charts in music screens.
- No oversized marketing hero pages.
- No AI copy such as "elevate", "unlock", "unleash", "seamless", "next-gen",
  or "transform".
- No generic placeholder names such as John Doe, Acme, Nexus, Nova, or Flowbit.
- No emoji.
- No decorative tiny text.
- No scroll arrows, bouncing chevrons, or "scroll to explore" prompts.
- No custom mouse cursors.
- No animated blur or layout-size animation during scroll.
- No album circle-cropping.
- No multiple competing accent colors outside artwork.

## 14. Final Quality Checklist

Before considering the implementation complete, verify:

- The app reads as a music player immediately.
- The chrome uses only black, white/gray, red, and optional one green status dot.
- Album artwork is the emotional anchor on player and detail screens.
- Desktop has real navigation, context rail, and persistent transport.
- Mobile has safe areas, bottom navigation, and a persistent mini-player path.
- Internal panels are square and grid-driven.
- Text is readable in normal screenshots.
- The current track is identifiable without relying only on color.
- Queue, lyrics, credits, and artwork gallery all have clear visual treatment.
- There is no purple glassmorphism from the elegant design direction.
- There are no generic AI-dashboard artifacts.
