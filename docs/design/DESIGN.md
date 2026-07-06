# Design DNA: Phoebe Elegant Player

Use this file as the implementation brief for turning the generated Phoebe mockups
into the actual Compose Multiplatform app UI. It is intentionally prescriptive:
an implementation agent should be able to translate these rules into tokens,
components, previews, and screens without inventing a separate visual system.

## 1. Mockup References

Primary references live beside this file:

- `mobile-flow.png` - first mobile flow: library, album detail, now playing, search/queue.
- `light-archive.png` - desktop library/player archive direction.
- `desktop-nocturne.png` - cross-device album detail and dark listening mode.
- `mobile-collections.png` - mobile collections, playlist detail, lyrics/credits.
- `desktop-listening-room.png` - focused desktop now-playing mode.
- `tablet-detail.png` - tablet album detail, listening notes, queue, and transport.

Treat these as mood and structure references, not pixel-perfect screenshots.
Prioritize the design DNA below when a generated image contains awkward text,
minor icon drift, or impossible layout details.

## 2. Visual Theme And Atmosphere

Phoebe should feel like a private music archive with a listening room attached:
warm, editorial, quiet, tactile, precise, and album-art led. It is not a
generic streaming clone, not a SaaS dashboard, and not a marketing page inside
an app frame.

Intensity model:

- **Density:** 4/10. Screens breathe. Track lists and queues can be efficient,
  but the first impression should stay calm.
- **Variance:** 7/10. Desktop, tablet, and phone share tokens, but each form
  factor gets its own rhythm instead of stretched layouts.
- **Motion:** 5/10. Motion is native and musical: spring sheets, shared artwork,
  quiet waveform life, and staggered list entry.
- **Texture:** 4/10. Use a subtle paper or grain layer, not visible noise.

Core visual ideas:

- Album artwork is the emotional anchor.
- Typography carries the premium feel more than decoration.
- Controls are familiar, restrained, and stable.
- Borders and whitespace define structure more than shadows.
- Light archive mode is the default product atmosphere.
- Dark listening mode is reserved for immersive now-playing surfaces.

## 3. Color Palette And Roles

The system is warm monochrome with one interactive accent. Album artwork may
contain any color, but app chrome must stay disciplined.

### Core Neutrals

- **Warm Bone** `#F7F6F3` - primary app canvas, mobile backgrounds, tablet
  backgrounds, and large desktop content fields.
- **Porcelain Surface** `#FFFFFF` - raised panels, sheets, list areas, and
  cards that need separation.
- **Soft Paper** `#FBFAF7` - secondary panel fill and subtle alternating areas.
- **Warm Divider** `#EAE6DE` - default 1px structural lines in light mode.
- **Faint Grid Line** `rgba(17, 17, 17, 0.045)` - optional technical grid motif.
- **Charcoal Ink** `#111111` - primary text and primary filled controls.
- **Soft Charcoal** `#2F3437` - secondary headings and strong metadata.
- **Muted Stone** `#787774` - metadata, inactive navigation, helper text.
- **Disabled Stone** `#AAA6A0` - disabled labels and low-priority timestamps.

### Single Interactive Accent

- **Archive Blue** `#2F6F92` - active navigation, focus rings, selected rows,
  waveform played state, tiny active indicators, and link-like metadata.
- **Archive Blue Wash** `#E1F3FE` - selected fills, hover fills, and soft badges.

Do not introduce a second action color. Pale yellow or pale green may appear
only as non-interactive editorial washes or album-art-adjacent illustration
tones. They must not compete with Archive Blue for user attention.

### Dark Listening Mode

Use dark mode only for immersive player states such as full-screen now playing,
desktop listening room, visualizer, or a dark mini-player expansion.

- **Listening Charcoal** `#111111` - dark mode canvas.
- **Dark Panel** `#181818` - dark side panels and bottom chrome.
- **Dark Border** `rgba(255, 255, 255, 0.10)` - dark structural lines.
- **Warm White** `#F7F6F3` - dark primary text.
- **Dark Muted Text** `#B8B2A8` - dark metadata.
- **Archive Blue On Dark** `#A8CEE2` - the same accent family for progress,
  active controls, and focus.

### Banned Color Moves

- No pure black `#000000`.
- No neon purple, electric blue, or saturated startup gradients.
- No multi-accent rainbow UI outside album artwork.
- No heavy glassmorphism or glowing action buttons.
- No flat all-white screen without borders, grain, or tonal separation.

## 4. Typography

Typography is the design system. Use a two-family structure: editorial display
for music objects, refined sans for app UI.

### Font Families

- **Display:** `Instrument Serif`, `Newsreader`, or another bundled modern
  editorial serif. Use for the Phoebe wordmark, album titles, playlist titles,
  collection headings, and now-playing track titles.
- **UI Sans:** `Satoshi`, `Geist`, `SF Pro Display`, or `Helvetica Neue`. Use
  for navigation, controls, table rows, buttons, metadata, settings, and body.
- **Mono:** `Geist Mono`, `SF Mono`, or `JetBrains Mono`. Use only for durations,
  sample rates, codec labels, timestamps, and dense numeric metadata.

Do not use Inter. Do not use Times New Roman, Georgia, Garamond, or generic
system serif fallbacks for visible product identity.

### Type Scale

Use platform-appropriate sizes, but keep this hierarchy:

- **Desktop brand/title:** 40-56px serif, line-height 0.98-1.05.
- **Desktop album/track title:** 34-48px serif, line-height 1.0-1.08.
- **Tablet album title:** 36-48px serif, line-height 1.0-1.08.
- **Mobile screen title:** 36-44px serif, line-height 0.98-1.05.
- **Mobile track/playlist title:** 26-34px serif, line-height 1.05.
- **Section heading:** 16-18px sans, weight 500-600.
- **Row title:** 14-16px sans, weight 500.
- **Body/metadata:** 13-15px sans, line-height 1.45-1.6.
- **Tiny labels:** minimum 11px, letter spacing at most 0.08em.

Rules:

- Use zero letter spacing for normal UI text.
- Never shrink mobile body text below 13px.
- Keep lyric lines readable and centered with generous leading.
- Use uppercase only for short functional labels such as `NOW PLAYING`,
  `UP NEXT`, or `CREDITS`.
- Do not rely on text labels so small they only work in a mockup.

## 5. Shape And Surface System

The mockups are crisp, not bubbly.

- **App window radius:** 8-12px on desktop mockups.
- **Primary panels:** 8px radius.
- **Album artwork:** 8-12px radius, square or controlled rectangle.
- **Media cards:** 8px radius.
- **Search fields:** 10-14px radius.
- **Mobile sheets:** 22-28px top radius only.
- **Icon buttons:** circular or rounded only when the control itself is a
  standard transport button.
- **Primary play button:** circular. This is an allowed exception because it is
  a media control, not a container.

Surface rules:

- Every structural card or panel uses a 1px border.
- Default light border is Warm Divider `#EAE6DE`.
- Shadows are nearly absent: use `rgba(17, 17, 17, 0.04)` maximum for soft lift.
- Prefer dividers, spacing, and tonal fills over stacked cards.
- Do not put cards inside cards unless it is a modal/sheet with repeated rows.

## 6. Iconography

Icons should feel slightly custom and music-specific, not like a generic thin
open-source icon pack dropped into the app.

Rules:

- Use existing Phoebe icon resources where possible.
- Keep stroke or fill logic consistent within a surface.
- Minimum hit target is 44px on mobile and tablet.
- Use familiar symbols for playback: previous, play/pause, next, shuffle,
  repeat, queue, volume, cast/output.
- Active state uses Archive Blue plus weight or shape change. Do not rely on
  color alone.
- Avoid decorative icons in headings. Music UI already has enough identity from
  artwork, typography, and waveform.

## 7. Layout DNA By Form Factor

### Desktop

Desktop uses a native app shell with four stable zones:

1. **Left navigation rail:** 220-260px wide. Contains Phoebe brand, primary
   navigation, and a quiet account/control area near the bottom.
2. **Main content stage:** Library, collection shelves, album detail, search,
   or now-playing surface. This zone receives the largest artwork.
3. **Right context column:** 300-360px wide. Use for Up Next, credits, lyrics,
   listening notes, gallery, output, or inspector content.
4. **Persistent bottom transport:** 88-112px tall. Track identity left,
   transport center, volume/output/queue right.

Desktop spacing:

- Window padding: 28-36px.
- Main content gap: 24-32px.
- Shelf/card gap: 12-20px.
- Track row height: 44-56px.
- Queue row height: 56-68px.
- Album art in detail/player: 320-520px depending on viewport.

Desktop must never become a generic dashboard. Avoid fake analytics, stat cards,
or feature tiles that do not serve listening, library browsing, metadata, or
queue management.

### Tablet

Tablet is not desktop squeezed down. Use a touch-first split layout:

- Left rail navigation at 88-140px depending on orientation.
- Main detail or collection column takes 55-65 percent of width.
- Right column holds listening notes, credits, gallery, or queue.
- Bottom mini-player remains persistent and touch-friendly.
- Track rows stay at least 48px tall.
- Artwork remains large enough to feel intentional, not thumbnail-like.

### Mobile

Mobile uses a true app flow:

- **Library / Collections:** large serif title, search affordance, segmented
  collection switcher, 2-column album grid, sticky mini-player, bottom tabs.
- **Album / Playlist Detail:** framed artwork, title, artist, metadata, Play
  button, track list, credits/About at the bottom.
- **Now Playing:** large artwork or art-led dark mode, title/artist, waveform,
  scrubber, familiar controls, output, and queue affordance.
- **Search / Queue:** search results above a bottom queue sheet or dedicated
  queue screen with clear reorder handles.
- **Lyrics / Credits:** lyric lines get breathing room; credits sit in a bottom
  sheet or lower detail section.

Mobile spacing:

- Horizontal padding: 20-24px.
- Top safe-area breathing room: 16-28px after status region.
- Bottom chrome height: 72-88px plus safe area.
- Mini-player height: 72-84px.
- Album artwork: 70-88 percent of screen width on now-playing/detail.

## 8. Core Components

### Navigation

- Desktop sidebar uses quiet rows with 44-48px height and 8px selected fill.
- Mobile bottom navigation has four primary items: Library, Search, Queue,
  Settings. Keep labels readable.
- Tablet rail can use icon plus label when space allows.
- Active navigation uses Archive Blue and a soft wash, not a neon fill.

### Album Artwork

- Artwork should be the largest image in any music-object screen.
- Use stable aspect ratios: square for albums, 4:5 or 16:10 only for editorial
  headers where the design calls for it.
- No circular album covers.
- Hover on desktop scales the image inside its clipped frame to 1.03-1.05.
- Clicking desktop artwork and long-pressing mobile artwork may open gallery
  behavior where the app supports it.

### Track Lists

- Use clear columns: index/current indicator, title/artist, duration, overflow.
- Highlight the current item with Archive Blue plus a soft row fill.
- Row dividers should be faint and aligned.
- In dense lists, prefer row separators over boxed cards.
- Keep duration and technical metadata monospaced when available.

### Queue

- Queue rows contain thumbnail, title, artist, duration, and reorder handle.
- Current or upcoming track uses visual weight plus Archive Blue indicator.
- Desktop queue is a persistent right panel or lower rail.
- Mobile queue is a bottom sheet or dedicated tab.
- Never bury queue behind an unlabeled icon in now-playing.

### Transport

- Desktop: persistent bottom bar with current track left, transport center,
  scrubber spanning the central zone, and output/volume/queue right.
- Mobile: mini-player on non-player screens, full controls on now-playing.
- Primary play/pause button is high contrast: Charcoal Ink on light mode,
  Warm White on dark mode, or filled Charcoal with white icon.
- Controls must be stable. Hover and active states cannot shift layout.

### Waveform And Progress

- Use a subtle waveform as Phoebe's signature detail.
- It is musical, not analytical. Do not make it look like a stock chart.
- Played state uses Archive Blue; unplayed state uses muted stone.
- The playhead can be a fine vertical line or small handle.
- Movement should be calm and low amplitude.

### Credits, Lyrics, And Notes

- Credits belong in album/playlist detail or a right context column, not a
  separate top-level screen by default.
- Lyrics are spacious and readable, with the active line slightly stronger.
- Listening notes use simple text blocks and dividers.
- Avoid tiny metadata dumps. If data is dense, group it into rows.

### Search

- Search field is a quiet inset surface, 44-48px high.
- Results are grouped by Artists, Albums, Tracks, and Playlists.
- Use clear rows with thumbnails where helpful.
- Do not turn search into a widget dashboard.

### Settings

- Settings should remain utilitarian and quiet.
- Use grouped cells, dividers, and direct controls.
- Do not import the editorial serif into dense settings surfaces except the
  screen title.

## 9. Motion And Interaction

Motion should feel like native app polish, not page choreography.

- **Default spring:** stiffness 100, damping 20 for sheets and player expansion.
- **Fast control feedback:** 90-140ms for button press scale/opacity.
- **Screen entry:** artwork and major panels fade in and translate up 8-16px.
- **List entry:** shelves, track rows, and queue rows reveal in 30-60ms cascades.
- **Shared artwork:** album art should feel continuous between library, detail,
  and now-playing where platform support allows.
- **Sheets:** bottom sheets rise with spring physics and keep transport available.
- **Waveform life:** subtle opacity/scale rhythm while playing.

Performance rules:

- Animate transform and opacity only where possible.
- Do not animate layout size in long lists.
- Avoid blur animations during scroll.
- Keep waveform animation cheap and pause it when not visible.

## 10. Implementation Targets In This Repo

An implementation agent should first map this DNA into existing shared tokens
and surfaces rather than scattering one-off colors through feature code.

Start here:

- `ui/core/src/commonMain/kotlin/com/phoebe/app/ui/PhoebeTokens.kt`
- `ui/core/src/commonMain/kotlin/com/phoebe/app/ui/Theme.kt`
- `composeApp/src/commonMain/kotlin/com/phoebe/app/ui/PhoebeDesktopContent.kt`
- `composeApp/src/commonMain/kotlin/com/phoebe/app/ui/PhoebeDesktopPlayer.kt`
- `composeApp/src/commonMain/kotlin/com/phoebe/app/ui/PhoebeDesktopSidebar.kt`
- `composeApp/src/commonMain/kotlin/com/phoebe/app/ui/PhoebeMobile.kt`
- `feature/library/src/commonMain/kotlin/com/phoebe/app/feature/library/`
- `feature/details/src/commonMain/kotlin/com/phoebe/app/feature/details/PhoebeDetailPanels.kt`
- `feature/playback/src/commonMain/kotlin/com/phoebe/app/feature/playback/`
- `feature/lyrics/src/commonMain/kotlin/com/phoebe/app/feature/lyrics/`

Implementation order:

1. Add or adapt tokens for Warm Bone, Porcelain Surface, Archive Blue, borders,
   dark listening mode, spacing, and radii.
2. Update typography support so display serif is available for music-object
   titles and brand surfaces.
3. Build reusable surfaces: bordered panel, artwork frame, track row, queue row,
   mini-player, bottom sheet, waveform/progress.
4. Apply desktop shell and transport structure.
5. Apply mobile library/detail/player flow.
6. Add tablet split behavior once mobile and desktop tokens are stable.
7. Update screenshots/previews after each major surface lands.

## 11. Accessibility And Responsive Rules

- Minimum mobile touch target: 44px.
- Body text contrast must pass WCAG AA against its surface.
- Do not place text directly over busy artwork without a solid scrim or separate
  spatial zone.
- Support dynamic text by allowing titles to wrap to two lines before truncating.
- Track rows must keep title, artist, and duration readable at compact widths.
- Bottom sheets must not trap essential playback controls.
- Desktop hover states need keyboard/focus equivalents.
- Focus rings use Archive Blue and must be visible in light and dark modes.
- No horizontal scroll on phone layouts.

## 12. Anti-Patterns

Never do these:

- No emojis anywhere.
- No Inter font.
- No pure black `#000000`.
- No neon glows or purple/blue startup gradients.
- No multiple action accent colors.
- No generic streaming clone layout copied from Spotify or Apple Music.
- No SaaS dashboard cards, fake stats, or analytics widgets.
- No box-in-box-in-box mobile screens.
- No 3-column equal feature-card grids.
- No giant pill containers or rounded-full buttons except standard circular
  transport controls.
- No tiny decorative labels.
- No generic placeholder names such as John Doe, Acme, Nexus, Nova, or Flowbit.
- No AI copywriting cliches such as elevate, unlock, unleash, seamless,
  next-gen, or transform.
- No filler UI prompts such as scroll to explore, swipe down, bouncing arrows,
  or decorative chevrons.
- No broken remote image links. Use real artwork, local placeholders, generated
  art assets, or provider artwork.
- No destructive replacement of existing user settings, playback behavior, or
  data flows while implementing visual changes.

## 13. Acceptance Checklist

Before calling the implementation complete, verify:

- Light archive mode reads as warm, editorial, and calm.
- Dark listening mode is reserved for focused playback and does not infect every
  library/settings screen.
- Album art is the first visual anchor on library/detail/player surfaces.
- Desktop has sidebar, main stage, right context, and bottom transport.
- Mobile has bottom tabs, mini-player, detail flow, now-playing, and queue.
- Tablet uses split layout instead of stretched phone UI.
- Typography uses serif only for brand/music-object titles and sans for UI.
- Buttons, rows, cards, and sheets use the shape rules above.
- Archive Blue is the only interactive accent.
- Text remains readable in screenshots at phone, tablet, desktop, and web sizes.
- Screenshot tests or previews cover at least library, album detail, player,
  search/queue, and settings in relevant form factors.
