# Phoebe Porcelain Design DNA

This file is the implementation contract for the Porcelain direction in Phoebe.
It is written for an AI implementation agent working in the Compose
Multiplatform app. Follow this document before improvising.

Porcelain is the default catalog and archive atmosphere: warm light mode,
paper-like, editorial, calm, and practical. It should make Phoebe feel like a
private music archive with beautiful playback attached.

## 1. Reference Boards

Use these local boards as visual references:

- `docs/design/phoebe-elegant/light-archive.png` - primary Porcelain
  cross-device direction.
- `docs/design/phoebe-elegant/tablet-detail.png` - tablet split-detail rhythm.
- `docs/design/phoebe-elegant/mobile-collections.png` - mobile collection and
  playlist rhythm.

Treat generated boards as mood and structure references, not pixel-perfect
screenshots. When an image contains awkward text or impossible spacing, follow
this DNA file.

## 2. Product Atmosphere

Porcelain should feel like a warm music archive: tactile paper, refined catalog
typography, quiet source management, image-led album shelves, and a compact
transport that never interrupts browsing.

Intensity targets:

- **Density:** 4/10 on mobile, 5/10 on desktop.
- **Variance:** 6/10. Calm asymmetry, not chaotic art direction.
- **Motion:** 4/10. Native, subtle, and functional.
- **Texture:** 3/10. Gentle paper grain only.

Porcelain is for:

- Home and Library.
- Search.
- Albums, artists, playlists, collections.
- Source management and library picker.
- Album/playlist detail when not in immersive player mode.
- Settings and utility surfaces.

Porcelain is not:

- Flat beige default UI.
- A marketing landing page.
- A generic three-card dashboard.
- A neon or glassmorphism system.

## 3. Existing App Mapping

Map this system onto current Phoebe structures:

- Theme tokens: `ui/core/src/commonMain/kotlin/com/phoebe/app/ui/PhoebeTokens.kt`.
- Theme access: `PhoebeTheme`, `PhoebeUi`, and `PhoebeVisualPalette`.
- Desktop shell: `PhoebeDesktopPlayer`, `PhoebeDesktopSidebar`, desktop
  transport components.
- Mobile shell/player: `PhoebeMobile` and `feature/playback`.
- Details: `feature/details`, including artwork gallery and About/credits.

Porcelain should usually map to the app's light appearance.

## 4. Color Palette

Use one warm light substrate with one interactive accent.

- **Porcelain Canvas** `#F6F1E8` - main app background.
- **Warm Paper** `#FBF7EF` - secondary panels and list backgrounds.
- **Porcelain Surface** `#FFFDF8` - raised sheets, modals, search fields.
- **Charcoal Ink** `#171A1E` - primary text and filled controls.
- **Soft Ink** `#34383D` - secondary headings and control labels.
- **Muted Stone** `#77716A` - metadata, inactive navigation, helper text.
- **Faint Stone** `#AAA49C` - disabled text and low-priority timestamps.
- **Hairline Warmth** `#E4DCD0` - 1px borders and dividers.
- **Aubergine Accent** `#4A2D45` - selected navigation, primary playback,
  waveform played state, focus rings, active segmented control.
- **Aubergine Wash** `#E9DDEA` - selected fills and hover washes.
- **Soft Clay** `#A99586` - non-interactive editorial wash only.
- **Muted Sage** `#7D917C` - secondary status tone only.

Color rules:

- Aubergine is the only interactive accent.
- Clay and Sage cannot become CTA colors.
- Use Charcoal Ink generously so the UI does not become beige soup.
- No pure white-only layouts without borders or tonal separation.
- No purple-blue gradients, neon glows, or multi-accent rainbow UI.

## 5. Typography

Typography carries the premium feel.

- **Display:** `Instrument Serif`, `Newsreader`, or another bundled modern
  editorial serif. Use for the Phoebe wordmark, major collection headings,
  album titles, playlist titles, and detail hero titles.
- **UI Sans:** `Satoshi`, `Geist`, `SF Pro Display`, or `Helvetica Neue`. Use
  for navigation, rows, metadata, controls, settings, and body copy.
- **Mono:** `Geist Mono`, `SF Mono`, or `JetBrains Mono`. Use only for
  durations, timestamps, bit depth, sample rates, and dense numeric metadata.

Do not use Inter as the premium identity font. Do not use Times New Roman,
Georgia, Garamond, or generic serif fallbacks.

Scale targets:

- **Desktop display title:** 44-60sp, line-height 0.98-1.06.
- **Desktop detail title:** 34-46sp, line-height 1.0-1.08.
- **Tablet title:** 34-46sp, line-height 1.0-1.08.
- **Mobile screen title:** 30-40sp, line-height 1.0-1.08.
- **Mobile track/detail title:** 24-32sp, line-height 1.05-1.12.
- **Section heading:** 15-18sp sans, weight 600.
- **Row title:** 14-16sp sans, weight 500-600.
- **Metadata/body:** 12-15sp sans, line-height 1.45-1.6.
- **Micro-label:** 11-12sp minimum.

Rules:

- Normal UI text uses zero letter spacing.
- Mobile body text never drops below 13sp.
- Use uppercase only for short structural labels such as `SOURCE`, `UP NEXT`,
  and `PLAYBACK`.
- Do not make every heading serif. Serif is for music-object identity and major
  editorial moments.

## 6. Shape and Surface

Porcelain is crisp and tactile, not bubbly.

- **Desktop app window:** 8-12dp outer radius.
- **Primary panels:** 8dp radius.
- **Album artwork:** 8-12dp radius. Never circular.
- **Media thumbnails:** 6-8dp radius.
- **Search fields:** 12-16dp radius.
- **Segmented source selector:** 14-18dp outer radius, 10-14dp active segment.
- **Mobile mini-player:** 12-18dp radius.
- **Mobile bottom sheets:** 24-28dp top radius only.
- **Primary play button:** circular, 56-72dp on mobile player, 48-64dp on
  desktop.

Surface rules:

- Every major panel uses a 1px Hairline Warmth border.
- Prefer dividers and tonal fills over stacked elevation.
- Shadow opacity should stay at or below `rgba(17, 17, 17, 0.06)`.
- No cards inside cards except modals/sheets containing repeated rows.

## 7. Desktop Layout

Use four stable zones:

1. **Left navigation rail:** 220-260dp. Brand, Home, Search, Library,
   Playlists, Radio, source/profile area.
2. **Main content stage:** flexible library, search, detail, or collection
   content.
3. **Right context rail:** 300-360dp when useful. Queue, detail preview, credits,
   lyrics, or source context.
4. **Bottom transport:** 88-104dp. Current track left, waveform and transport
   center, output/volume/queue/settings right.

Desktop spacing:

- Outer shell padding: 24-36dp.
- Column gap: 20-32dp.
- Shelf/card gap: 12-20dp.
- Track row: 44-56dp.
- Queue row: 56-68dp.
- Detail artwork: 300-460dp depending on viewport.

Desktop rules:

- Keep browsing surfaces light and archive-led.
- Use shelves, lists, detail previews, and album art instead of fake dashboard
  cards.
- The transport is persistent but visually quiet.
- The right rail should be useful, not decorative.

## 8. Mobile Layout

Mobile uses native app rhythm:

- **Library/Home:** title, search, source selector, editorial album shelf,
  recently played list, sticky mini-player, bottom navigation.
- **Album/Playlist Detail:** framed artwork, title, artist, metadata, Play
  action, track list, credits/About and gallery entry lower down.
- **Search:** direct search field, readable result rows, source-aware filters.
- **Queue/Lyrics/Audio Sheet:** bottom sheet or pushed screen when invoked from
  mini-player or now-playing.

Mobile spacing:

- Horizontal padding: 20-24dp.
- Safe-area top breathing room: 16-28dp after status region.
- Bottom chrome: 72-88dp plus safe-area inset.
- Mini-player: 72-84dp.
- Detail artwork: 70-86 percent of screen width.
- Touch targets: 44dp minimum.

Mobile rules:

- Do not squeeze desktop panels into phone screens.
- Bottom sheets must leave the home indicator clear.
- The mini-player must stay legible over the Porcelain canvas.

## 9. Components

### Navigation

- Desktop nav rows are 44-48dp tall with 8dp selected fill.
- Mobile bottom nav has four stable entries: Library, Search, Queue, Settings.
- Active state uses Aubergine text plus Aubergine Wash.
- Avoid oversized icon pills and decorative badges.

### Source Selector

- Used for Local, Plex, Jellyfin, Navidrome, and related providers.
- Treat as a segmented control, not a tag cloud.
- Height: 40-48dp desktop, 36-44dp mobile.
- Active item gets stronger fill, icon, and label.
- Inactive items remain readable and separated by hairline dividers.

### Album Artwork

- Square by default.
- Stable crop and stable frame dimensions.
- Desktop hover may scale artwork internally to 1.03-1.05.
- Desktop click and mobile long-press may launch gallery where supported.
- No circular album covers.

### Library Shelves and Grids

- Image-first with captions below or beside artwork.
- Keep shelves airy and readable.
- Use 2-column grids on phones, flexible shelves on desktop/tablet.
- Avoid three equal feature cards.

### Track and Queue Rows

- Structure: indicator/index, thumbnail optional, title, artist/album,
  duration, overflow/reorder action.
- Current row uses Aubergine plus type weight and/or marker.
- Use dividers rather than individual row cards unless inside a bottom sheet.

### Transport

- Play/pause is the dominant control.
- Previous, next, shuffle, repeat, queue, output/cast, volume, and settings are
  stable 44dp controls.
- Waveform uses Aubergine played state over a quiet charcoal/stone track.
- Track identity stays visible on desktop left side and mobile mini-player.

### Details, Credits, and Gallery

- Credits belong in the lower About/credits area on detail screens.
- Artwork gallery entry should be visible but not loud.
- Avoid long paragraphs inside tiny panels.

## 10. Motion

Motion is native and restrained:

- Sheet rise: 220-280ms with spring easing.
- Row reveal: 18-35ms stagger, opacity and vertical translation only.
- Artwork hover: internal scale 1.03-1.05.
- Button active: scale to 0.97 or 1dp press translation.
- Respect reduced-motion settings.

Do not add bouncing, game-like, or rubbery motion.

## 11. Acceptance Checklist

- The app feels like a warm private music archive.
- It does not look flat beige or generic white.
- Album artwork is the strongest visual object on detail screens.
- Source selector, library shelves, transport, search, and mini-player are all
  visually integrated.
- Text is readable in screenshots without zooming.
- No neon gradients, fake stats, random badges, nested cards, circular album
  covers, or tiny decorative labels.
