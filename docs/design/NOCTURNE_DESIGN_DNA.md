# Phoebe Nocturne Design DNA

This file is the implementation contract for the Nocturne direction in Phoebe.
It is written for an AI implementation agent working in the Compose
Multiplatform app. Follow this document before improvising.

Nocturne is the immersive listening system: dark, lunar, analog, brass-accented,
and album-art-led. It should make Phoebe feel like a private night-listening
room, not a generic purple dark mode.

## 1. Reference Boards

Use these local boards as visual references:

- `docs/design/phoebe-elegant/desktop-nocturne.png` - primary Nocturne desktop
  direction.
- `docs/design/phoebe-elegant/mobile-flow.png` - primary Nocturne mobile flow.
- `docs/design/phoebe-elegant/desktop-listening-room.png` - expanded desktop
  listening-room posture.
- `docs/design/phoebe-elegant/tablet-detail.png` - split detail and context
  rhythm when a darker detail state is needed.

Treat generated boards as mood and structure references, not pixel-perfect
screenshots. When an image contains awkward text or impossible spacing, follow
this DNA file.

## 2. Product Atmosphere

Nocturne should feel like night listening: moonlit artwork, analog hi-fi
controls, tactile matte panels, warm lyric/liner-note typography, and a precise
queue. It should stand apart through brass, lunar imagery, waveform structure,
and warm ivory type.

Intensity targets:

- **Density:** 5/10 on mobile, 6/10 on desktop.
- **Variance:** 7/10. More cinematic than Porcelain, still usable.
- **Motion:** 5/10. Musical and weighty, never flashy.
- **Texture:** 4/10. Subtle grain and artwork haze only.

Nocturne is for:

- Full now-playing.
- Dark desktop home/listening room.
- Queue and lyrics while music is active.
- Visualizer/player surfaces.
- Album detail hero states when artwork should dominate.
- Compact audio settings, Gapless, Crossfade, Keep Playing, output/cast.

Nocturne is not:

- Bright purple dark mode.
- Neon nightlife UI.
- A game-like music visualizer.
- A Spotify clone with a darker palette.

## 3. Existing App Mapping

Map this system onto current Phoebe structures:

- Theme tokens: `ui/core/src/commonMain/kotlin/com/phoebe/app/ui/PhoebeTokens.kt`.
- Theme access: `PhoebeTheme`, `PhoebeUi`, and `PhoebeVisualPalette`.
- Desktop shell: `PhoebeDesktopPlayer`, `PhoebeDesktopSidebar`, desktop
  transport components.
- Mobile player: `PhoebeMobile` and `feature/playback`.
- Details: `feature/details`, especially artwork, credits/About, and gallery.
- Playback controls/settings: queue, lyrics, waveform, output/cast, Gapless
  Playback, Crossfade, and Keep Playing.

Nocturne should usually map to the app's dark appearance and immersive player
states.

## 4. Color Palette

Use a dark lunar substrate with Brass as the primary action accent.

- **Deep Graphite** `#0C0F12` - primary dark canvas.
- **Ink Panel** `#12161B` - main panels and list wells.
- **Smoked Glass** `#181C22` - bottom transport, inspectors, modal fields.
- **Warm Ivory** `#F3EFE6` - primary dark text.
- **Moonwash Text** `#C9C0B3` - secondary text and row metadata.
- **Dim Ash** `#817A72` - disabled text, inactive controls, time marks.
- **Night Border** `rgba(243, 239, 230, 0.10)` - 1px dividers and panel seams.
- **Soft Night Fill** `rgba(243, 239, 230, 0.055)` - row hover and quiet fills.
- **Brass Accent** `#D2AE74` - active playback, play button, waveform played
  state, selected source, focus details.
- **Brass Wash** `rgba(210, 174, 116, 0.16)` - selected state fill.
- **Muted Violet** `#75619A` - secondary atmospheric tone for lyric quotes and
  artwork-adjacent emphasis only.
- **Mineral Green** `#6E946F` - positive status, enabled toggles, Keep Playing,
  source online indicators.

Color rules:

- Brass is the primary action accent.
- Muted Violet is atmospheric, not the CTA system.
- Mineral Green is status, not navigation.
- Never use pure black `#000000`.
- No neon glow, electric blue, saturated purple gradients, or rainbow UI.
- Album artwork may contain any color, but app chrome must stay disciplined.

## 5. Typography

Nocturne uses warm editorial identity and readable app UI.

- **Display:** `Instrument Serif`, `Newsreader`, or another bundled modern
  editorial serif. Use for Phoebe wordmark, album titles, now-playing titles,
  playlist titles, lyric/liner-note moments.
- **UI Sans:** `Satoshi`, `Geist`, `SF Pro Display`, or `Helvetica Neue`. Use
  for navigation, rows, metadata, controls, settings, and body copy.
- **Mono:** `Geist Mono`, `SF Mono`, or `JetBrains Mono`. Use only for
  durations, bit depth, sample rate, codec labels, timestamps, and numeric
  metadata.

Do not use Inter as the premium identity font. Do not use Times New Roman,
Georgia, Garamond, or generic serif fallbacks.

Scale targets:

- **Desktop display title:** 48-68sp, line-height 0.98-1.06.
- **Desktop player/detail title:** 34-48sp, line-height 1.0-1.08.
- **Tablet title:** 34-46sp, line-height 1.0-1.08.
- **Mobile player title:** 28-38sp, line-height 1.0-1.08.
- **Mobile track/detail title:** 24-32sp, line-height 1.05-1.12.
- **Section heading:** 15-18sp sans, weight 600.
- **Row title:** 14-16sp sans, weight 500-600.
- **Metadata/body:** 12-15sp sans, line-height 1.45-1.6.
- **Micro-label:** 11-12sp minimum.

Rules:

- Normal UI text uses zero letter spacing.
- Mobile body text never drops below 13sp.
- Lyrics use generous leading and strong contrast.
- Use uppercase only for short structural labels such as `UP NEXT`, `LYRICS`,
  `SOURCE`, and `PLAYBACK`.
- Do not make every heading serif. Serif is for music-object identity and
  immersive editorial moments.

## 6. Shape and Surface

Nocturne is tactile and crisp, not bubbly.

- **Desktop app window:** 8-12dp outer radius if platform-provided.
- **Primary panels:** 8dp radius.
- **Album artwork:** 8-12dp radius. Never circular.
- **Media thumbnails:** 6-8dp radius.
- **Search fields:** 12-16dp radius.
- **Segmented source selector:** 14-18dp outer radius, 10-14dp active segment.
- **Desktop transport:** 0-8dp radius depending on shell attachment.
- **Mobile mini-player:** 12-18dp radius.
- **Mobile bottom sheets:** 24-28dp top radius only.
- **Primary play button:** circular, 56-72dp mobile player, 48-64dp desktop.

Surface rules:

- Every major panel uses a 1px Night Border.
- Prefer borders, washes, and spacing over stacked shadows.
- Use subtle artwork haze behind player/detail areas only when text remains
  protected.
- No cards inside cards except modals/sheets containing repeated rows.
- No heavy blur glass, neon edges, or glossy plastic.

## 7. Desktop Layout

Use four stable zones:

1. **Left navigation rail:** 220-260dp. Brand, Home, Search, Library,
   Playlists, Radio, source/profile area.
2. **Main listening stage:** flexible home/detail/player surface with strong
   album artwork, shelves, or visualizer.
3. **Right context rail:** 300-380dp. Now Playing, Up Next, lyrics, credits,
   audio inspector, gallery, output.
4. **Bottom transport:** 88-112dp. Current track left, waveform and transport
   center, output/volume/queue/settings right.

Desktop spacing:

- Outer shell padding: 24-36dp.
- Column gap: 20-32dp.
- Shelf/card gap: 12-20dp.
- Track row: 44-56dp.
- Queue row: 56-68dp.
- Large player artwork: 340-520dp depending on viewport.

Desktop rules:

- Keep the bottom transport persistent and serious.
- The right rail must be a useful listening/context surface.
- Let one piece of artwork dominate player and detail screens.
- Use queue, lyrics, credits, waveform, and audio settings as primary content.
- Avoid fake analytics and generic dashboard cards.

## 8. Mobile Layout

Mobile Nocturne is a real app flow:

- **Library/Home:** dark source selector, album shelf, recently played list,
  sticky mini-player, bottom navigation.
- **Album Detail:** artwork-first, title, artist, metadata, Play action, tracks,
  credits/About, artwork gallery entry.
- **Now Playing:** large artwork or visualizer, track/artist, waveform, scrubber,
  transport, favorite, output/cast, queue, audio controls.
- **Queue/Lyrics/Audio Sheet:** bottom sheet with queue rows, Keep Playing,
  lyrics, Gapless Playback, Crossfade.

Mobile spacing:

- Horizontal padding: 20-24dp.
- Safe-area top breathing room: 16-28dp after status region.
- Bottom chrome: 72-88dp plus safe-area inset.
- Mini-player: 72-84dp.
- Now-playing artwork: 70-88 percent of screen width.
- Touch targets: 44dp minimum.

Mobile rules:

- Now-playing artwork is the emotional anchor.
- Queue and lyrics sheets must not hide primary transport controls unexpectedly.
- Bottom sheets must leave the home indicator clear.
- Do not squeeze desktop panels into phone screens.

## 9. Components

### Navigation

- Desktop nav rows are 44-48dp tall with 8dp selected fill.
- Mobile bottom nav has four stable entries: Library, Search, Queue, Settings.
- Active state uses Brass text/marker plus Brass Wash.
- Avoid oversized icon pills and decorative badges.

### Source Selector

- Used for Local, Plex, Jellyfin, Navidrome, and related providers.
- Treat as a segmented control, not a tag cloud.
- Height: 40-48dp desktop, 36-44dp mobile.
- Active item gets Brass fill/marker, icon, and label.
- Inactive items remain readable and separated by Night Border dividers.

### Album Artwork

- Square by default.
- Stable crop and stable frame dimensions.
- Desktop hover may scale artwork internally to 1.03-1.05.
- Desktop click and mobile long-press may launch gallery where supported.
- No circular album covers.
- Imagery should feel nocturnal, coastal, analog, lunar, archival, or tactile.

### Track and Queue Rows

- Structure: playing indicator/index, thumbnail optional, title, artist/album,
  duration, overflow/reorder action.
- Current row uses Brass plus type weight and/or marker.
- Enabled/positive states may use Mineral Green, but not for current playback
  progress.
- Use dividers rather than individual row cards unless inside a bottom sheet.
- Queue rows are 56-68dp and stay readable.

### Transport

- Play/pause is the dominant control.
- Previous, next, shuffle, repeat, queue, output/cast, volume, and settings are
  stable 44dp controls.
- Waveform uses Brass played state over Moonwash/Dim Ash unplayed bars.
- Track identity stays visible on desktop left side and mobile mini-player.
- No unrelated large controls in the transport.

### Now Playing

- Large artwork first, controls second.
- Metadata is short and readable: track title, artist, album/context.
- Favorite, output/cast, queue, and audio settings are secondary actions.
- Text over imagery must be protected with scrims, fades, or separated zones.

### Lyrics and Credits

- Lyrics must be readable, not decorative.
- Active lyric line may use Warm Ivory with a Brass marker.
- Inactive lyric lines use Moonwash Text or Dim Ash.
- Credits belong in About/credits sections on detail screens.
- On desktop, lyrics and queue fit the right context rail.
- On mobile, lyrics and queue can share a sheet with a segmented switch.

### Audio Settings

Include playback settings in a compact, polished surface:

- Gapless Playback as a toggle.
- Crossfade as a slider or stepped control with seconds.
- Keep Playing as a queue-continuation control.
- Output/cast and volume remain nearby but visually separate from processing.
- Enabled toggles may use Mineral Green. Active playback and progress use Brass.

## 10. Motion

Motion is musical and weighty:

- Sheet rise: 220-280ms with spring easing.
- Row/list reveal: 18-35ms stagger, opacity and vertical translation only.
- Artwork shared transition: scale and position, no blur-heavy morph.
- Button active: subtle scale to 0.97 or 1dp press translation.
- Hover desktop artwork: internal scale to 1.03-1.05.
- Waveform: low-amplitude idle life only while playing.
- Respect reduced-motion settings.

Do not animate layout width/height/top/left during scroll. Prefer transform and
opacity.

## 11. Acceptance Checklist

- Nocturne reads as lunar, analog, brass-accented, and private.
- It does not look like generic purple dark mode.
- Album artwork is the strongest visual object on player and detail screens.
- Desktop has a persistent transport and useful right context rail.
- Mobile now-playing feels native and immersive.
- Queue, lyrics, Gapless Playback, Crossfade, Keep Playing, output/cast, and
  waveform all have clear visual homes.
- Text is readable in screenshots without zooming.
- No neon gradients, fake stats, random badges, nested cards, circular album
  covers, pure black, or tiny decorative labels.
