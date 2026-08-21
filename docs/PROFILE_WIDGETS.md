# Profile widgets

The profile card is assembled from an ordered list of widgets instead of a fixed
stack of sections. A user reorders them, hides them, and adds new ones; the
server owns the list of widget *types*, so a new type ships without a client
release.

## Storage

Two additive columns on `User`, no new tables for the widgets themselves:

```prisma
profileWidgets Json @default("[]")
profileFields  Json @default("{}")
```

`profileWidgets` is the ordered array — position is the array index, which
removes the `position Int` bookkeeping that `Connection` needs. Each entry:

```json
{ "id": "w_a1b2c3", "type": "bio", "hidden": false, "config": {} }
```

`profileFields` is the bag of externally-pushed values, `{ "field": "value" }`.
It is written only by the push endpoint, never by `PATCH /auth/me`.

Caps: 24 widgets, 4 KiB serialized config per widget, 32 custom fields, 200
chars per field value, 64 chars per field key (`[a-z0-9_-]`).

Tokens need revocation, a hint and a `lastUsedAt`, so they get a table modelled
on `BotToken`:

```prisma
model ProfileFieldToken {
  id         String    @id @default(cuid())
  userId     String
  tokenHash  String    @unique
  hint       String
  label      String
  createdAt  DateTime  @default(now())
  lastUsedAt DateTime?
  user User @relation(fields: [userId], references: [id], onDelete: Cascade)
  @@index([userId])
}
```

## Widget types

Built in, mapping onto sections the card already renders:

| type | renders |
|---|---|
| `bio` | About Me, markdown |
| `pronouns` | inline next to the name |
| `badges` | badge row |
| `member-since` | join date |
| `now-playing` | current listening/game activity |
| `connections` | linked account cards |

New in this feature:

| type | renders |
|---|---|
| `text` | free text with `{placeholder}` substitution, markdown |
| `fields` | key/value rows drawn from `profileFields` |
| `spacer` | vertical gap, configurable size |
| `divider` | horizontal rule |
| `links` | labelled link list |
| `heading` | a section label on its own |
| `image` | a single image, by URL |

### Placeholders

Widget text substitutes `{name}` tokens against a resolver: built-ins
(`{username}`, `{displayName}`, `{pronouns}`, `{joinedYear}`), the widget's own
config as `{config.key}`, and every key in `profileFields` as `{field.key}`.
Unknown tokens render literally.

This is *not* the i18n `t()` interpolation, which uses the same `{name}` shape.
Widget text must never be passed through `t()` — it is user data, not a catalog
key.

## Server-driven catalog

`packages/server-rs/widgets.json` is read at boot into an `Arc`-shared store and
served by `GET /api/profile/widgets/catalog?rev=`, returning `304` when the
client's `rev` matches. Same shape as `http/i18n.rs`.

A definition carries its config schema and a declarative render spec:

```json
{
  "type": "links",
  "label": "widget.links",
  "icon": "link",
  "config": [
    { "key": "title", "kind": "string", "max": 60 },
    { "key": "items", "kind": "list", "max": 8,
      "of": [{ "key": "label", "kind": "string", "max": 40 },
             { "key": "url", "kind": "url" }] }
  ],
  "render": { "block": "section", "heading": "{config.title}", "body": { "block": "links", "from": "config.items" } }
}
```

Clients implement a fixed set of render primitives — `section`, `text`, `rows`,
`links`, `spacer`, `divider`, `image`. A new widget type is a new composition of
those primitives, so it needs no client change. A widget type whose `render`
references a primitive the client does not know is skipped silently.

Entries flagged `"default": true` form the layout of an account that has never
opened the editor, in catalogue order. That order is duplicated as `BUILTIN_LAYOUT`
in `widgets.ts` and `util/ProfileWidgets.kt` so a client with no catalogue yet
draws the same card. `the_shipped_catalog_parses_and_matches_the_built_in_card_order`
asserts that order literally, so reordering `widgets.json` fails the build until
both clients are moved with it.

## Pushing field values

A user mints a token in settings and sees it once. Their own service POSTs:

```
POST /api/profile/fields
Authorization: Widget <token>
Content-Type: application/json

{ "field": "status", "value": "shipping" }
```

Batch form `{ "fields": { "a": "1", "b": "2" } }` is also accepted. Values are
strings, numbers or booleans; anything else is rejected. Control characters and
bidi overrides are stripped, as `services/game.rs` does for custom game names.

Rate limited per owner, modelled on `GAME_ACTIVITY_PER_USER`. A successful push
broadcasts `user:updated` to `get_profile_audience_rooms`, same as `PATCH /me`.

Auth is a third scheme alongside `Bearer` and `Bot` in
`AuthUser::from_request_parts`, resolving to the owning user with a caller kind
that is barred from every other route.

## Activity de-branding

The provider is not named anywhere a user can see it. The wire value of
`ActivityDto.kind` becomes `listening`; clients accept `spotify` as well so
already-released builds keep working. The `provider = 'spotify'` column value
and the AES-GCM AAD in `services/spotify.rs` are left alone — changing the AAD
makes every stored OAuth token undecryptable.

`docs/PROFILE_CARD_CSS.md` documents `[data-kind="spotify"]`; it gains
`[data-kind="listening"]` and keeps the old selector working.

## Theming contract

Every `.oc-pf-*` hook class documented in `docs/PROFILE_CARD_CSS.md` and listed
in the marketplace's `profile-themes/api.ts` survives. Widgets render into the
same class names they render into today; reordering changes DOM order, not
names. Each widget wrapper also gains `data-widget="<type>"` so a theme can
target a type directly.
