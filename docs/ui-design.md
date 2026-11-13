# Interface decisions

Record is the primary action. The first card controls capture and shows the actual available audio history. The second card selects and saves a recent clip. Automatic/continuous saving is secondary. The app never labels uncaptured time as available audio.

Library lists audio clips with readable titles, date, duration, source and available GPS/note/capture context. Tapping a card opens a focused recording detail with inline playback, a native map and timestamped related items. Saved notes and captures remain accessible in a secondary Library menu and Tools row. Share, rename and delete use explicit actions; deleting a clip keeps independent context files. GPS gaps are not joined into invented routes.

Record offers three presets and a Custom chip. The selected duration and the last custom value persist independently, so switching to a preset does not erase the custom value. The custom-duration row always displays the remembered value.

Tools distinguish a durable enabled choice from its current ability to run. Location, keywords and camera resume with recording. Screen sharing remains a native per-session consent flow. Notes, questionnaires and server backup have focused setup screens rather than a single form full of unrelated actions.

Settings save where they are changed. Numeric validation stays beside the input. Buffer changes explain when they apply. System/Light/Dark are explicit, persistent choices with System as the default. Android system typography, 48 dp or larger touch targets, dp/sp spacing, scrollable content, insets and a wider-window navigation rail keep the same actions available across display sizes.

Brand colors were checked against [klob.io](https://klob.io): #00FF00 for dark-mode accents and #007500 for light-mode contrast. Dark surfaces are black and neutral charcoal; light surfaces are neutral off-white and white.

Implementation references: [Android navigation patterns](https://developer.android.com/design/ui/mobile/guides/layout-and-content/layout-and-nav-patterns), [Android dark themes](https://developer.android.com/develop/ui/views/theming/darktheme), [Material Components for Android](https://github.com/material-components/material-components-android/tree/master/docs/components). These guide the native UI; no web wrapper is used.
