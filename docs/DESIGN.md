# DiscordX design

The plugin manager uses Vencord-style plugin cards inside Phoenix Settings. Keep the title and Add plugin action together, then search and All/Enabled filters. Each card shows the plugin name, description, author, version, and an accessible switch. Open package information and plugin settings through Details. Group imports and bulk actions away from the plugin list.

| Role | Dark | Light |
| --- | --- | --- |
| Panel | `#242532` | `#F9F8FF` |
| Control surface | `#313344` | `#EAEAF6` |
| Text | `#F5F3FF` | `#252637` |
| Secondary text | `#C5C6DB` | `#515369` |
| Accent | `#ACB9FD` | `#4D59A3` |

Use Android's system typeface. Headings use 24 sp, plugin names 16 sp, and supporting text 13 sp. Switches expose their checked state to accessibility. Touch controls are at least 48 dp. Content caps at 880 dp with 16 dp outside margins. Cards use two columns from 700 dp of content width, and one column below that. The page scrolls on short windows. Keep 12 dp between cards and 16 dp between control groups.
