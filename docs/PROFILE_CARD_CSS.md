# Profile card: "now playing" card CSS

The activity card inside a profile card is the most recently reworked piece of
the `.oc-pf-*` hook surface. It went from a single muted text line ("Listening
to X") to a Discord-style card with artwork, a small-caps label, the activity's
name, its details and a live elapsed counter. This document lists the newest
CSS overrides as they exist today on both platforms.

The card appears in the profile card of any user with an active activity
(friends list, member list, DMs, search results). It is built from the first
listening activity, falling back to the first activity of any other kind.

## Structure

The container is a `<div class="oc-pf-activity">` (an `<a>` on web when the
activity has a URL). It carries the `data-kind` attribute, so a theme can react
to the activity type:

```css
.oc-pf-activity[data-kind="listening"] .oc-pf-activity-label { ... }
```

`listening` is the current wire value. Clients released before the rename still
send `spotify` for the same thing, so a theme that wants to cover both should
select on either:

```css
.oc-pf-activity[data-kind="listening"],
.oc-pf-activity[data-kind="spotify"] { ... }
```

Inside it: `.oc-pf-activity-artwork` (image, or the fallback glyph), then
`.oc-pf-activity-text .oc-pf-activity-meta` wrapping the label, name, details
and elapsed time.

## Hooks and their newest styles

The base overrides, applied to the card in its normal (non-compact) form. The
Android base styles live in `ProfileCardHtml.kt` (`baseCss`); the web card in
`ActivityStatus.tsx` uses the same hooks with equivalent Tailwind utilities.

### `.oc-pf-activity` - the card itself

```css
.oc-pf-activity {
  margin-top: 8px;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px;
  border: 1px solid var(--oc-border);
  border-radius: 7px;
  background: var(--oc-surface-3);
}
```

The card sits between the identity row and the badges, inside the body block
(`.oc-pf-body`). It never carries margin on its own in the compact (row) form.

### `.oc-pf-activity-artwork` - the cover image

```css
.oc-pf-activity-artwork {
  width: 48px;
  height: 48px;
  flex-shrink: 0;
  border-radius: 4px;
  object-fit: cover;
  display: block;
}
```

### `.oc-pf-activity-artwork-fallback` - no cover image

A static glyph (music note for Spotify, gamepad otherwise) centered on the
surface:

```css
.oc-pf-activity-artwork-fallback {
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--oc-surface-2);
  font-size: 24px;
  line-height: 1;
}
```

On web the glyph is a Lucide icon that also gets an `.oc-pf-activity-icon`
hook.

### `.oc-pf-activity-text` and `.oc-pf-activity-meta`

Stacked column holding the label, name, details and elapsed line:

```css
.oc-pf-activity-text {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
}
```

### `.oc-pf-activity-label` - the small-caps header

"LISTENING TO" for Spotify, "NOW PLAYING" for everything else:

```css
.oc-pf-activity-label {
  font-size: 11px;
  font-weight: 500;
  text-transform: uppercase;
  letter-spacing: 0.4px;
  color: var(--oc-ink-muted);
}
```

### `.oc-pf-activity-name` - the activity name

```css
.oc-pf-activity-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--oc-ink-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
```

### `.oc-pf-activity-details` - the detail line

Hidden entirely when the activity has no details:

```css
.oc-pf-activity-details {
  font-size: 12px;
  color: var(--oc-ink-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
```

### `.oc-pf-activity-elapsed` - the live counter

```css
.oc-pf-activity-elapsed {
  font-size: 12px;
  color: var(--oc-ink-muted);
  white-space: nowrap;
}
```

Rendered as "for h:mm:ss" (or "for m:ss" under an hour). The elapsed value is
kept live: on Android a script inside the card HTML rewrites the text content
of `[data-started-at]` every second; on web a `useNow` tick does the same.

## Widgets

Everything below the name on a profile card is a widget, placed by the profile
owner. The widget list is wrapped in `.oc-pf-widgets`, and each entry is an
`.oc-pf-widget` carrying `data-widget="<type>"`:

```css
.oc-pf-widgets { display: flex; flex-direction: column; gap: 10px; }
.oc-pf-widget[data-widget="bio"] { ... }
.oc-pf-widget[data-widget="now-playing"] { ... }
```

A widget renders a small tree of blocks, each with its own hook:

| Block | Hooks |
| --- | --- |
| `section` | `.oc-pf-section` wrapping `.oc-pf-heading` and the body block |
| `text` | `.oc-pf-text` (markdown is rendered inside it) |
| `rows` | `.oc-pf-rows` > `.oc-pf-row` > `.oc-pf-row-label` + `.oc-pf-row-value` |
| `links` | `.oc-pf-links` > `.oc-pf-link-item` > `.oc-pf-link` |
| `image` | `.oc-pf-image` |
| `divider` | `.oc-pf-divider` |
| `spacer` | no element of its own - it only reserves height |

The built-in widgets reuse the hooks they always had: `bio` renders
`.oc-pf-bio-text` inside a section, `member-since` renders `.oc-pf-member-text`,
`pronouns` renders `.oc-pf-pronouns`, `badges` renders `.oc-pf-badges`, and
`now-playing` renders the `.oc-pf-activity*` card documented above. A widget
with nothing to show is dropped entirely, so a theme never has to style an
empty heading.

New widget types can appear without a client update - they arrive in the
server's widget catalogue - so prefer styling `.oc-pf-widget` and the block
hooks over enumerating `data-widget` values.

## Notes

- User-provided profile CSS is sanitized and scoped to the card, so all of
  these hooks are themable per user via the profile theme setting.
- The compact variant (member/friend rows) keeps the same `.oc-pf-activity*`
  hooks but drops the card chrome - no padding, border or radius.
- The card is a link when the activity has a URL; `:hover` is the only state
  hook that receives a default style.