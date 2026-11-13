# ADR 0009: Design system after Mornye's Material style

Date: 2026-11-13 · Status: accepted (requested by the project owner)

The owner asked for the look of [Mornye](https://github.com/mornyeapp/Mornye), whose "Material" style takes its
colors from the wallpaper. Mornye is a Flutter app under LGPL-3.0, so its code cannot be used in this Kotlin app.
Its design tokens and patterns were studied and rebuilt natively in Compose. No Mornye code or brand assets are
included. This replaces the visual parts of ADR 0008; the screen structure is unchanged.

## What was adopted
- **Color:**
  - Wallpaper colors (Material You) on Android 12+.
  - Otherwise, a seed accent turned into a full tonal-spot scheme with Google's material-color-utilities
    (Kotlin port), which is how Android builds wallpaper schemes. The user picks from 8 accents.
  - An optional **pure black** dark mode for OLED screens. Pages are black; cards stay a step above black.
- **Type:** Google Sans Flex, bundled as a 223 KB Latin subset keeping the `wght` and `ROND` axes (OFL).
  Display, headline and title roles use fully rounded terminals (`ROND` 100); body and label roles use plain
  terminals.
- **Icons:** Material Symbols Rounded, converted to 53 vector drawables (Apache 2.0) through a generator script,
  instead of a 5 MB icon font. Selected tabs use the filled variants.
- **Shapes (`Tokens`):**
  - badge 6, thumb 8, icon tile 12;
  - control, menu and field 16;
  - card 20;
  - sheet and dialog 28;
  - pill-shaped buttons.
- **Motion:** `MaterialExpressiveTheme` with `MotionScheme.expressive()` (spring physics).
- **Components (`core-ui`):**
  - **Connected settings groups:** rows are separate tiles 2dp apart, with 20dp outer and 4dp inner corners, on
    the page color tinted 4% darker (8% lighter in dark mode).
  - **Section headers:** the title-small style, in the primary color.
  - **Rows:** `SettingsRow` and `SettingsSwitchRow` with 20/16dp padding, a 24dp icon and a chevron when
    tappable.
  - **Switch** (`DsSwitch`): a check mark inside the thumb.
  - **Connected toggle-button groups**, the expressive button group, for direction, theme and conflict answers.
  - **Text fields:** filled, with no outline until focused.
  - **Cards:** flat (no shadow), on the low surface container.
  - **Icon tiles.**
  - **Empty states:** the icon sits in a 9-sided cookie shape.
  - **Progress:** the expressive morphing loading indicator and wavy progress bars.
  - **Top bars:** a large collapsing title on tab roots; a centered title with back on sub-pages.
- **Navigation:** a bottom navigation bar (the expressive short bar) with Folders, Conflicts (with a badge),
  Activity and Settings. The add-folder wizard and pair detail open full-screen on top.

## Consequences
- **Material 3 is pinned to 1.5.0-rc01**, above the Compose BOM's 1.4.0, because toggle-button groups, the
  loading indicator and the wavy progress bars are only there. Move to 1.5.0 stable when it ships.
- **The `material-icons-extended` dependency was dropped.**
- **Screenshots use the seed theme**, because Robolectric has no wallpaper. On a phone with Material You, colors
  follow the wallpaper.
- **Third-party notices** are in `docs/licenses/`.
