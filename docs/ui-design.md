# Interface decisions

Record is the primary action. The first card controls capture and shows the actual available audio history. The second card selects and saves a recent clip. Automatic/continuous saving is secondary. The app never labels uncaptured time as available audio.

Library puts readable titles, date, duration and file size before filenames. Categories filter audio, notes and other captures. Playback stays in a dismissible sheet; share, rename and delete use explicit item actions. Destructive deletion requires confirmation and describes which copy is removed.

Tools distinguish a durable enabled choice from its current ability to run. Location, keywords and camera resume with recording. Screen sharing remains a native per-session consent flow. Notes, questionnaires and server backup have focused setup screens rather than a single form full of unrelated actions.

Settings save where they are changed. Numeric validation stays beside the input. Buffer changes explain when they apply. System/Light/Dark are explicit, persistent choices with System as the default. Android system typography, 48 dp or larger touch targets, dp/sp spacing, scrollable content, insets and a wider-window navigation rail keep the same actions available across display sizes.

Brand colors were checked against [klob.io](https://klob.io): #00FF00 for dark-mode accents and #007500 for light-mode contrast. Dark surfaces are black and neutral charcoal; light surfaces are neutral off-white and white.

Implementation references: [Android navigation patterns](https://developer.android.com/design/ui/mobile/guides/layout-and-content/layout-and-nav-patterns), [Android dark themes](https://developer.android.com/develop/ui/views/theming/darktheme), [Material Components for Android](https://github.com/material-components/material-components-android/tree/master/docs/components). These guide the native UI; no web wrapper is used.
