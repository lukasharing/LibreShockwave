# Director Runtime Semantics

This document records the current Director-compatible rules that should guide
LibreShockwave runtime changes. It is a contract document: code may still need
edge-case fixes, but new fixes should preserve these rules unless a Director
manual or real Director test proves otherwise.

The default model is pure Director emulation. Project-specific compatibility
policies can exist, but they must be explicit opt-ins and must not redefine the
base Director behavior.

## 1. Core Runtime Boundary

Director semantics and project compatibility are separate layers.

Base Director layer:

- implements documented Lingo behavior
- resolves casts and members through Director-visible cast order and slot order
- treats runtime members as normal cast members in normal slots
- replaces an external cast's visible content when `castLib.fileName` changes
- invalidates caches aggressively when script-visible identity changes

Compatibility layer:

- may reserve high dynamic slots for specific content
- may preserve selected runtime members across a cast switch
- may add application-level alias files or launcher variables
- must be named and configured as compatibility behavior

Do not bake compatibility shortcuts into the Director layer. If a behavior is
not part of Director, put it behind a policy flag or an application bootstrap
path.

## 2. Data Structures

### 2.1 PropList

The canonical representation for a property list is an ordered list of entries:

```text
PropList.entries: [PropEntry(keyDatum, normalizedKey, value, keyType), ...]
```

This is required because Director property lists can contain duplicate keys and
because many operations are defined by first occurrence in the current order.

Indexes are accelerators only:

- first string-like key index
- first string key index
- first symbol key index

They must never become the source of truth. Any mutation that can change first
occurrence must update or invalidate the affected index.

The practical complexity target is:

- append: amortized O(1)
- first lookup after invalidation: O(n)
- repeated lookup with stable list: O(1)
- ordinal `setAt`: O(1)

This is better than a plain map because it preserves duplicate-key semantics,
and better than a plain list for repeated `getaProp`/`setaProp` patterns.

### 2.2 Cast Slots And Names

The canonical cast identity is the visible slot table:

```text
castLibNumber + memberNumber -> current visible member content
```

For file-backed members, the file/resource identity is separate:

```text
castResourceId + memberResourceId -> persistent authored resource
```

Name lookup is a derived view:

```text
normalizedMemberName -> sorted visible member slot candidates
```

The name index must preserve visible slot order. Resource ID order, parse order,
and Java map iteration order are not lookup semantics.

### 2.3 Member References

A script-visible member reference should behave as an indirect handle:

```text
MemberRef {
  castLibNumber
  memberNumber
}
```

It should not be a permanent pointer to an old `CastMember` object and should
not be late-bound by name. If the cast content changes, the reference derefers
the current content at the same cast and slot, or becomes empty/invalid for
operations that require an existing member.

An implementation may store a generation for debugging or stale-reference
detection, but the generation is not part of Lingo's normal identity model.

### 2.4 Alias Tables

Aliases are application-level data, not Director member lookup semantics.

The source of an imported alias set must be explicit:

```text
AliasSource {
  castLibNumber
  fieldKey
}
```

Re-reading an alias field replaces only aliases imported from that same source.
It must not scan every cast, keep stale aliases from a reloaded cast, or imply
that `member()` has a global alias layer.

### 2.5 Render And Media Caches

Keep source media separate from derived render outputs.

Examples:

- indexed bitmap source pixels + reference palette
- decoded text/styled-text data
- generated text member image
- palette-remapped bitmap cache
- ink-preprocessed bitmap cache
- baked sprite output

Derived caches are invalidated by versioned inputs: palette revision, member
revision, sprite revision, ink/blend colors, text properties, cast reloads, and
file-backed binding changes. Source media should not be destructively rewritten
by a palette change or a temporary render operation.

## 3. Lists And Property Lists

`setaProp(pl, key, value)` updates the first matching property in the current
property-list order. If no property exists, it appends a new property.

`setProp(pl, key, value)` updates the first matching property and errors if the
property is missing.

`setAt(pl, index, value)` is ordinal. It updates the value at the given position
and preserves the existing key. It does not search by key.

For linear lists, `setAt(list, index, value)` is positional and can expand the
list with `VOID` entries to reach the requested index.

Manual verification: the Director reference documents these failures only as
"script error"; it does not provide a stable message string or numeric error
code. Internally, categorize these as semantic errors rather than exact Director
diagnostics:

```text
setAt(propList, index, value):
  if index < 1 or index > propList.count:
      ListIndexOutOfRange / script error

setAt(linearList, index, value):
  if index < 1:
      ListIndexOutOfRange / script error
  else:
      expand with empty/VOID entries and assign

setProp(propList, key, value):
  if first matching property is missing:
      PropertyNotFound / script error

setProp(linearList, key, value):
  WrongListType / script error
```

Compatibility note: some legacy bytecode in exported Director movies uses
`setAt(propList, key, value)` as associative property assignment. That behavior
is available only behind the explicit `compatPropListSetAtByKey` runtime flag,
where it is treated like `setaProp`. Director-strict mode keeps the documented
ordinal behavior.

The manual explicitly covers `setAt(propList, index, value)` when `index` is
greater than the number of property-list entries. Treating `0` and negative
indexes as script errors follows from Director's 1-based list model, but the
manual entry does not spell out those exact inputs.

Do not implement property-list semantics with a normal JavaScript-style map.
That loses duplicates, order, and typed key distinctions.

String and symbol keys use the same comparison path for Director property
access: `findPos`, `getaProp`, `setaProp`, `setProp`, `deleteProp`, and bracket
style access compare `#foo` and `"foo"` by compatible property name. Preserve
the original key token in the list so `getPropAt`, formatting, duplicate order,
and round-trip behavior still see whether the entry was authored as a symbol or
as a string.

## 4. Member Lookup

Name lookup is case-insensitive and follows visible cast order and visible slot
order. Preserve the authored/runtime casing in `member.name`, but normalize when
searching.

```text
for cast in currentCastLibsOrdered:
  for slot in visibleMemberNumbersAscending:
    if caseFold(member(slot, cast).name) == caseFold(requestedName):
      return member(slot, cast)
```

The first match wins. Runtime-created members do not automatically beat
authored members. A renamed member does not automatically beat an earlier
duplicate name.

Invalidate member-name caches when any of these happen:

- `member.name` changes
- a member is created
- a member is erased or retired
- a member is moved
- `castLib.fileName` changes
- an external cast becomes visible
- cast libraries are attached, detached, or reordered

If a stale-name cache quirk is ever needed, it belongs in a compatibility mode,
not the default runtime.

Missing member references must keep `.number` safe. The documented Director
existence pattern is `member("name").number > 0`, so:

```text
member("missing").number -> 0
member("missing").memberNum -> 0
member("missing").name -> "" or compatibility-profile result
member("missing").image/text/media mutation -> operation-specific error/VOID
```

Do not make `.number` on a missing member throw.

## 5. Dynamic Member Allocation

Director-compatible dynamic allocation uses the first visible empty slot in the
target cast.

```text
newMember(type)
new(type, castLib)
```

Both should allocate the lowest visible empty slot in the chosen cast. A cast
file's `minMember` is a storage compression detail, not a runtime allocation
floor. Slots below `minMember` are visible empty slots unless occupied by some
runtime member.

High-slot allocation is a project policy, not Director semantics:

```text
allocateRuntimeMember(type, castLib, minSlot = 10000)
```

That kind of allocator can exist for compatibility content, but it should not
change the behavior of Director's standard `newMember` or `new(#type, castLib)`.

## 6. `castLib.fileName`

Changing `castLib.fileName` replaces the visible content of that external cast.

Director-strict behavior:

- load the new external cast transactionally
- if loading fails, keep the old cast visible
- if loading succeeds, replace the slot table and file-backed resources
- discard runtime members that belonged to the old cast
- keep only the logical cast library identity: cast ordinal, cast handle, name
- invalidate name indexes, member caches, media caches, script visibility, and
  sprite/member bindings that depended on the old content

Do not merge non-colliding runtime members into the new cast by default. That
creates visible members that do not exist in the newly selected cast file.

Optional compatibility behavior may preserve selected runtime members, but only
behind an explicit policy such as:

```text
preserveRuntimeMembersAcrossFileNameChange = true
preserveOnlyIfNoCollision = true
preserveOnlySlotsAtOrAbove = 10000
```

Manual verification: `castLib.fileName` is documented as read/write for external
casts, and after assignment the movie uses the assigned external cast file. The
manual does not explicitly specify the lifetime of an already-resolved variable
such as:

```lingo
old = member("x")
castLib("External").fileName = "Other.cst"
put old.name
```

The emulator policy remains an indirect cast+slot handle. It should not retain a
snapshot of old CASt/XMED/media content and should not re-resolve by the old
name. The only unresolved detail is what exact value/error Director returns when
that old slot is empty or changed after the cast replacement.

Raw network preloads are not always cast installation. A `preloadNetThing` or
similar download should cache bytes in the runtime and mark the network task
done, but a cast payload should not be parsed and exposed unless the runtime can
tie it to a specific visible cast slot:

- authored preload mode for that cast slot
- `castLib.fileName` assignment on that slot
- an explicit runtime cast-load request that marks the slot pending/fetching
- a unique stable cast-list binding that represents an authored visible cast
  library

This rule is format- and client-agnostic: `.cst` and `.cct` are both possible
external cast payloads, but the extension is not the installation policy.
Parsing every downloaded asset cast into the visible registry is not required
by Director's external-cast model and can stall the frame loop in large movies.
For WASM this means the bytes still need to cross into the VM's raw cast cache:
keeping them only in the browser response cache is too late for authored code
that observes `netDone()`, assigns `castLib.fileName`, and immediately looks up
members in the same tick.

## 7. `field`, `getMemNum`, And Decompiled `field(0)`

Source-level `field()` behavior is direct:

```text
field("x") -> resolve member named "x" now and return field text
field(123) -> read the field member at slot/member number 123
field(0) -> slot 0 / invalid, not "last resolved member"
```

Do not add a global "last getMemNum" register to emulate decompiler artifacts.
If bytecode decompiles to `field(0)` in a suspicious expression, reconstruct the
bytecode-level expression instead:

```text
Field(expr)
```

Then evaluate `expr` normally as either a string name or numeric member number.

Existence and text content are separate. An existing field member can contain
`EMPTY` or `""`; empty text is not a failed lookup and must not trigger fallback
resolution.

## 8. Loading And Resource Identity

For authored content, resource IDs are stable file identities. `memberCount` or
"number of members" is not a stable identity key.

The robust flow is:

```text
MCsL entry:
  cast name
  cast file path
  minMember / maxMember
  castResourceId

KEY*:
  owner/castId == castResourceId
  fourCC == CAS* -> cast mapping resource id

CAS* mapping:
  visible slot offset -> memberResourceId / empty

CASt and related chunks:
  memberResourceId -> member properties, scripts, media, text, owned chunks
```

Use `minMember` and the mapping index to materialize visible slot numbers. Do
not use ResourceID order as member-name lookup order.

Afterburner and packed-file support should keep enough source bytes or chunk
metadata to reparse immutable derived objects on demand. It is safe to evict
parsed AST/media wrappers when they can be reconstructed from retained bytes
and metadata. It is not safe to evict live mutable runtime state or backing
buffers still referenced by WASM views.

## 9. Text And Field Rendering

Fields and text members are related but not identical.

`#field`:

- classic system text
- platform/font-map dependent metrics
- no Director-level antialias property
- line height and margins affect layout
- editable caret and selection are sprite/UI overlay state

`#text`:

- Director text member renderer
- supports text member properties such as antialias, threshold, box type,
  spacing, indents, and character spacing
- `member.image` is a local member image, not final stage compositing

Manual verification for dynamic text defaults is partial:

| Property | Manual status | Emulation policy |
| --- | --- | --- |
| `#text antiAlias` | Documented for MX 2004 as true by default for text/vector/Flash-style rendering. | Default true in an MX 2004 profile. |
| `#text antiAliasThreshold` | Documented as text-member property with default `14` pt, effective only when `antiAlias` is true. | Default `14` in an MX 2004 profile. |
| `#field` antialias | Director 11.5 describes fields as standard system text and distinguishes them from antialiased text members. | No Director-level antialias property for classic fields; host smoothing is renderer divergence. |
| `boxType` | Values are documented, but no default for newly created members is given. | Use authored value when present; make dynamic default profile-configurable. |
| `regPoint` | Manual documents `regPoint`; Flash has an explicit center case, bitmap/vector often default center, other types are described as upper-left. | Use `point(0, 0)` for dynamic `#field/#text` as an inference, not a version-proof fact. |
| `backColor` / `bgColor` | Mostly sprite/stage/tool-color behavior, not a complete dynamic member default table. | Do not model unset as magic transparency; background visibility is decided by member box plus sprite ink/compositing. |
| `lineHeight` | Field line height is a runtime display property. | Treat it as the effective line advance for classic fields. Underline stays anchored to font baseline metrics and is clipped by the field rect; do not move it just to keep it visible. |

`fontStyle` is exposed as Director's documented string form, for example
`"underline"` or `"bold, italic"`. The runtime may accept a symbol or list of
symbols as tolerant input, but it normalizes that value before storing it so the
renderer sees one canonical style string.

Do not hardcode undocumented dynamic defaults as universal Director truths. Store
them in a creation profile such as Director 8, MX 2004, Director 11.5, or a
project compatibility profile.

Director 5/6 rich text can be stored more like authored bitmap media. In those
cases, prefer the authored raster unless Lingo mutates the member.

`member.image` for text should include only member-local visuals: glyphs and
member-local fill/background when explicitly applicable. A default white text
background should not by itself become an opaque copied rectangle; explicit
`bgColor` / `txtBgColor` is preserved. It must not include stage background,
sprite ink, sprite transform, caret, selection, or focus UI.

`member.image` is documented for bitmap and text cast members. Classic `#field`
image readback is less strongly documented. If code asks for a field image,
return a local compatible field raster when practical, but treat that as a
compatibility service rather than a strongly verified Director guarantee.

## 10. Palette And Ink

Palette changes must remap display/cache output, not destructively rewrite the
source bitmap.

For indexed bitmaps:

```text
source pixels: indexed or truecolor media data
reference palette: authored/member palette
current palette: movie/stage palette
draw cache: remapped pixels for current palette revision
```

If the current palette differs from the member's reference palette and palette
mapping is enabled, use a lookup table or nearest-color remap at draw/cache
time. Keep the original source pixels intact.

Text and field sprites should go through the same sprite composition pipeline
as bitmap sprites after their member image is rasterized:

```text
member raster -> sprite placement/transform -> ink/blend -> stage
```

Editable caret/selection is an overlay after normal sprite composition.

Ink semantics must stay separate from palette interpretation. In particular,
`#backgroundTransparent` uses the resolved background color as a transparency
key. It does not authorize a palette-index foreColor/backColor ramp recolor of
the pixels that survive the keying step. Palette-index ramp recolor is only
appropriate when a separate colorizing path explicitly asks for it.

For `#matte` copy operations on 32-bit images created and mutated by Lingo,
prefer the white background implied by `image(width, height, 32)` over
dominant-edge matte inference. Scripted UI buffers often draw black outlines
on the image edge and then copy the buffer into window members with
`[#ink: 8]`; treating those outlines as the matte erases real content.

Palette remapping affects display/cache interpretation. It must not
destructively change source media, and a palette switch should not rewrite the
logical contents of `member.image`. If an image object stores indexed pixels,
the current palette may affect how a view is interpreted, but the source pixels
remain the same.

## 11. Memory And Lifetime

The emulator has multiple memory ownership domains:

- file bytes and chunk metadata
- parsed file-backed resources
- runtime cast members
- script-visible member references
- sprite runtime state
- text/image/palette render caches
- VM globals, locals, instances, lists, and temporary datums
- host bridge buffers and pending async network jobs

The key rule is to separate identity from cached representation. A cache can be
recreated; a script-visible identity or mutable runtime buffer cannot be dropped
unless the Director operation invalidates it.

Examples:

- changing `member.name` invalidates name indexes, but not the member slot
- changing `castLib.fileName` replaces the cast slot table
- changing palette invalidates remap caches, not source pixels
- changing text invalidates text raster caches, not sprite identity
- erasing a runtime member invalidates the slot content and related bindings

## 12. Manual Verification Notes

Confirmed from manual/reference:

- `setAt` on a property-list index greater than count is a script error.
- `setAt` on a linear list can expand the list with empty entries.
- `setProp` errors when the property is missing and is property-list-only.
- `newMember()` creates a new cast member, assigns type, and places it in the
  first empty cast slot; the manual does not list every initial property value.
- `castLib.fileName` is writable for external casts and changes the external
  cast file the movie uses.
- Member name lookup is case-insensitive while preserving the visible `name`
  string casing.
- The official existence-check pattern implies `member("missing").number`
  returns `0` safely.
- `#text antiAlias` and `antiAliasThreshold` are documented; MX 2004 threshold
  default is `14`.
- Classic fields are system text rather than Director antialiased text members.

Not specified by manual:

- exact message/code for list and property-list script errors
- dynamic `#field/#text` complete default table by version
- exact behavior of an already-resolved `Member` variable after external cast
  replacement
- exact runtime output for invalid member refs such as `member(0)` or
  non-`.number` properties on `member("missing")`

## 13. Implementation Confidence

High confidence:

- property-list duplicate-key model
- `setaProp`, `setProp`, and `setAt` separation
- first visible empty slot for Director dynamic allocation
- cast/slot member references rather than name-late-bound references
- name lookup by cast order and slot order
- `castLib.fileName` replacement model
- source-scoped alias import model
- palette remap without source mutation
- text `member.image` as pre-sprite-compositing member image

Medium confidence:

- exact default values for new dynamic text/field members by Director version
- internal error categories for list and property-list failures
- exact stale-reference behavior after external cast replacement
- exact system text metrics and antialias behavior on old Mac/Windows players
- edge cases around old rich-text-as-bitmap formats

Low confidence without real Director tests:

- version-specific authoring-time name cache quirks
- obscure interactions between editable text, IME, selection, and sprite ink
- exact purge/preload behavior under memory pressure
- exact network and external-cast timing across browser plugin/projector modes

Unknown and intentionally not promised:

- exact Director diagnostic strings for script errors
- complete versioned creation defaults for dynamic text and field members
- precise result of dereferencing an old member reference after external cast
  replacement

## 14. Open Questions For Manual Or Real Director Tests

These are the remaining questions worth checking. Prefer manual references when
available; otherwise write small Director movies that print concrete values.

### 13.1 Property Lists And Linear Lists

- Does `setaProp([#foo: 1, "foo": 2], "foo", 9)` update the first physical
  compatible entry in every target Director version?
- Is key comparison case-sensitive for strings in `getaProp`, `setaProp`,
  `findPos`, bracket access, and JavaScript syntax?
- Does `setaProp` preserve the original key datum when updating the first match,
  or can the key's visible representation change?
- Does `setProp` update only the first duplicate or all duplicate keys?
- If we need bit-identical diagnostics, what exact script error text/code is
  thrown by `setProp` when the property is missing?
- If we need bit-identical diagnostics, what exact script error text/code is
  thrown by `setAt(propList, index, value)` when the index is out of range?
- What happens for `setAt(propList, 0, value)` and negative indexes?
- Does a float index coerce to integer, error, or use exact numeric equality?
- Does `setAt(linearList, index, value)` expand with `VOID`, `0`, `EMPTY`, or
  another placeholder?
- Does linear-list expansion happen for all positive indexes or only one past
  the current end?
- Does `deleteProp` delete the first matching duplicate or all duplicates?
- Does `findPos` return the first duplicate key in current order after sorting
  or after deletion?
- What does `getPropAt(pl, i)` return when `i` is out of range?
- Does bracket syntax `pl[#x] = value` behave exactly like `setaProp`?
- Does bracket syntax with a missing property append or error?
- How do nested property lists preserve duplicate keys during `duplicate()` or
  deep copy?
- Does `sort()` on a property list preserve duplicate-key relative order?
- How does `convertToPropList` in authored movies treat duplicate keys and
  malformed values?

### 13.2 Member Numbers And Member References

- What exact integer packing does `member.number` use for multiple casts in each
  Director version?
- What is the maximum visible slot per cast before Director errors?
- What does `member(0)` return in Lingo source?
- What does `member(-1)` return?
- What properties can be safely read from an empty or invalid member reference?
- Does `voidP(member("missing"))` return true, false, or error?
- For non-number properties on `member("missing")`, which return empty/VOID and
  which raise script errors?
- Does a `Member` object reference store cast ordinal and slot, or a direct
  object pointer, after `castLib.fileName` changes?
- If a member reference points to a slot that becomes empty after cast reload,
  what do `.name`, `.type`, `.number`, and `.text` return?
- If the same slot in the new cast contains another member, does an old member
  reference see the new member immediately?
- Do member references survive `erase member` as empty handles, or do later
  property reads error?
- Does assigning `member.name` to `EMPTY` or `""` remove it from name lookup?
- Are member name comparisons case-sensitive?
- Do duplicate member names inside one cast resolve by visible slot number?
- Do duplicate member names across casts resolve by cast library order even if a
  later cast was loaded more recently?
- Does cast library reordering change future `member("x")` lookups immediately?
- What happens if two cast libraries have the same castLib name?
- Does `member("x", "Cast")` use cast name lookup by first castLib name match?
- Can scripts address slots below a file's `minMember` as empty slots?

### 13.3 Dynamic Members

- Does `_movie.newMember(#bitmap)` choose the first visible empty slot in every
  Director version or only in MX/11.x?
- What target cast is used by `newMember(type)` when no cast is specified?
- Does `new(type, castLib)` share the same allocation rule as `newMember`?
- What happens if the target cast is external but not yet loaded?
- Can a dynamic member be created below an external cast's authored `minMember`?
- Does erasing a dynamic member free the slot for reuse by the next `newMember`?
- Does erasing an authored member leave an empty slot that `newMember` can use?
- What default member properties are assigned by real Director for new
  `#field`, `#text`, `#bitmap`, `#shape`, `#button`, `#palette`, and `#script`
  members in each target version?
- Is the inferred `point(0, 0)` default `regPoint` for dynamic `#field/#text`
  correct in each target version?
- What is the default `rect` or width/height for dynamic text and field members
  in each target version?
- Does a newly created text or field member have a visible white background,
  transparent member image, or ink-dependent background?
- Is a runtime-created member saved by `save castLib` or movie save operations?
- Does Director expose a distinction between authored and runtime-created
  members through any property?

### 13.4 External Casts And `castLib.fileName`

- Does assigning `castLib.fileName` synchronously reload the cast, or can it
  defer until the next access/frame?
- What is the exact failure behavior when the new file does not exist?
- Does a failed cast load keep all old runtime-created members?
- Does a successful cast load discard all old runtime-created members?
- Do sprites using the old cast refresh immediately, next frame, or after
  explicit `updateStage`?
- Do behavior/script instances from the old external cast remain attached to
  existing sprites after the cast file changes?
- Does handler lookup immediately include scripts from the new external cast?
- Are missing-handler caches invalidated by `castLib.fileName` in Director?
- Does `castLib.fileName` preserve the logical cast name or take the new file's
  internal cast name?
- Does Director clear selection or authoring metadata on cast switch?
- Does changing `fileName` affect `preLoadMode`, `modified`, `number of
  members`, and `member.count` immediately?
- Does changing an internal cast's `fileName` have any effect or error?
- Is a relative `fileName` resolved against the movie path, current folder,
  search path list, or the previous cast path?
- Does Director allow switching to `.cst`, `.cct`, `.dcr`, or only certain cast
  file formats by version?

### 13.5 File Format And Resource Mapping

- Is `CAS*` always the cast mapping chunk for all relevant file versions, or do
  some versions use `CASp` or another fourCC?
- Is `MCsL.castResourceId` always the owner ID used by `KEY*` entries?
- Can a cast mapping contain holes before, inside, or after the authored range?
- Does `memberCount` mean highest visible slot, number of non-empty slots, or UI
  count in each file version?
- Can two `KEY*` entries point to the same member resource ID?
- Can multiple slots reference the same member resource?
- Does ResourceID ordering ever differ from visible slot ordering in real files?
- How are deleted authored members represented in `CAS*` mapping?
- Are external casts allowed to have overlapping resource IDs with the main
  movie, and is the cast resource ID required to disambiguate them?
- Which chunks must remain retained for lazy reparsing in Afterburner files?
- Which parsed chunks can be discarded safely after cast integration?
- Are XMED, STXT, BITD, palette, and script resources independently purgeable?
- Does Director decompress all external cast data at load or lazily by member?
- Does `preLoadMode` force media decode or only file/resource availability?

### 13.6 Field And Text Rendering

- Are classic fields antialiased on any Director player/OS combination, or only
  by host OS font smoothing outside Director's property model?
- What is the default font, size, color, margin, line height, and background for
  new dynamic fields?
- What is the default font, size, color, box type, antialias flag, and
  antialias threshold for new dynamic text members?
- Does `antiAliasThreshold` use `>= threshold` or `> threshold`?
- Is threshold compared to point size, rendered pixel height, or transformed
  sprite size?
- Does text antialiasing happen before or after sprite scale/rotation?
- Does `member.image` for a text member include transparent alpha or a matte
  color background?
- Does `member.image` for a field member include border, margin, box shadow, and
  background?
- Are caret and selection ever included in `member.image`?
- If two sprites share one editable field member, is selection state per sprite,
  per member, or global focus state?
- What exact blinking interval does Director use for text caret?
- Does editable text selection invert pixels, draw highlight color, or use OS
  selection painting?
- How do `scrollTop`, `pageHeight`, and line-height interact for clipped fields?
- Does setting `scrollTop` clamp to line boundaries or pixels?
- Does `boxType #adjust` resize the cast member rect, the sprite rect, or both?
- Does `boxType #limit` limit by visible area, character count, or text height?
- How are `fixedLineSpace`, `topSpacing`, `bottomSpacing`, and paragraph
  spacing combined?
- Does `charSpacing` affect hit testing and caret position exactly like layout?
- How are embedded PFR fonts prioritized against system fonts?
- What is the exact font substitution chain when a font is missing?
- Do Director 5/6 rich text members expose text layout data, bitmap data, or
  both?
- When Lingo modifies old rich text, does Director re-layout it or convert it to
  a newer text representation?
- How does `htmlText` map into plain `text` and styled ranges?

### 13.7 Images, Ink, Palette, And Compositing

- Does `member.image` return a live image object for all bitmap/text members or
  only for specific types?
- If the returned image is mutated, when does the cast member visual update?
- Does assigning `member.image` copy pixels or keep a shared image reference?
- Do text and field sprites honor every bitmap ink mode, or are some ignored for
  editable text?
- For `#backgroundTransparent`, is the key color `backColor`, `bgColor`,
  palette index 0, or another source?
- Does matte prefer native alpha over RGB matte extraction in all versions?
- What exact RGB key does `#transparent` use for indexed and truecolor images?
- How does `blend` interact with non-copy inks?
- Are ink calculations done in palette index space or RGB space for 8-bit
  movies?
- Does palette remapping happen before ink processing, after ink processing, or
  both depending on mode?
- What nearest-color metric does Director use for palette remapping?
- Does changing `the palette` immediately remap already baked sprites or only on
  next draw?
- Does changing a bitmap member's `palette` invalidate its source or only its
  display mapping?
- Does palette remapping mutate `(member.image)` readback or only stage output?
- What is the exact initialization color/alpha for `image(w, h, 1/8/16/32)`?
- Are 32-bit image canvases transparent white, transparent black, or undefined?
- How are alpha channels handled in old Director versions before full alpha
  support?
- Does `copyPixels` clip source and destination rects before or after ink math?
- Does `copyPixels` with quad transform use nearest, bilinear, or another
  sampler?

### 13.8 Sprites, Score, And Frame Execution

- What is the exact event order for `beginSprite`, `prepareFrame`, `enterFrame`,
  `exitFrame`, `endSprite`, and frame-script handlers?
- When a score channel changes member, which sprite properties reset and which
  runtime mutations persist?
- How does puppeting affect score rebinding across frames?
- Does `locZ` order always beat channel order?
- Are equal `locZ` sprites sorted by channel, creation order, or score order?
- Does `updateStage` flush immediately inside a handler or schedule a draw?
- Can `member.image` mutations during rendering affect the same frame?
- Are film loop internal sprites ticked independently or only rendered as a
  nested score?
- Do film loops inherit parent sprite ink, palette, blend, and transform after
  internal composition?
- Does a sprite keep script instances when its member changes but channel stays
  the same?
- How are sprite behaviors cleaned up when a member is erased or cast reloads?
- Does `sprite.memberNum` store global packed member number or local slot plus
  cast?
- Does changing `sprite.castLibNum` preserve `memberNum` exactly?
- When a sprite's cast changes, does Director re-resolve by name or by same
  member slot?

### 13.9 Input And Editable Text

- Do old-version native alpha thresholds differ from the Director 11.5
  `alphaThreshold` behavior?
- Are there non-Flash edge cases where `#backgroundTransparent` changes mouse
  active area instead of remaining rectangular?
- What is the exact ordering of `mouseDown`, `mouseUp`, `mouseUpOutside`,
  `mouseEnter`, `mouseLeave`, and `mouseWithin`?
- Is button cursor behavior driven by sprite type, cursor property, behavior
  scripts, or all of them?
- How do editable fields capture keyboard focus?
- Does clicking a non-editable sprite clear editable text focus?
- Does keyboard input mutate `member.text` immediately or after commit?
- How are clipboard, IME composition, dead keys, and non-roman input exposed?
- What are exact mappings for `key`, `keyCode`, `charToNum`, and modifier keys?
- Do key repeat events follow OS repeat timing or Director frame timing?

### 13.10 Networking, Params, And Platform

- What is the exact resolution order for `getVariable`: authored handler,
  built-in, external Shockwave parameters, or environment?
- Are external `swN` parameter names case-sensitive?
- Do duplicate launch variable names keep first, last, or error?
- Are launch variables URL-decoded before exposure to Lingo?
- Does `getNetTextResult` return partial data before `netDone`?
- What are exact `netDone`, `netError`, and stream status values during pending,
  success, HTTP error, DNS failure, and cancellation?
- Does Director cache external cast downloads by URL, castLib, or resource ID?
- Can the same external cast URL be loaded into two cast libraries with distinct
  runtime state?
- What is the precise timing of external cast visibility after download?
- Does `preLoadNetThing` share state with `castLib.fileName` loading?
- What security restrictions differ between projector and Shockwave plugin?

### 13.11 Xtras, Sound, And Timers

- Which Xtras must be modeled as pure VM services and which require platform
  state?
- How does Director represent missing Xtras: script error, stub object, or
  inactive instance?
- What is sound channel lifetime when a frame changes, cast reloads, or movie
  stops?
- Are sound volumes clamped, wrapped, or coerced for values outside 0..255?
- How are queued sounds affected by changing channel volume/pan during playback?
- Do timeouts tick in wall-clock time, frame time, or both depending on mode?
- Can timeout handlers reenter the VM while another handler is running?
- What exact cleanup happens when a timeout is forgotten or a script instance is
  destroyed?

### 13.12 Memory, Purging, And Lifetime

- What does Director's purge priority actually evict: decoded media, source
  bytes, cast member objects, or render surfaces?
- Does `preLoad` decode media or only prevent purge?
- Is `member.image` kept alive by image references after the member is erased?
- If a bitmap image object is shared by multiple members, do mutations alias?
- Are script instances kept alive by sprite behaviors after the sprite leaves
  the score?
- Does changing external cast file release old media immediately or wait for
  purge?
- Does Director keep old external cast bytes if any member reference still
  exists?
- Can a cast reload happen while a member image from the old cast is being used
  by `copyPixels`?
- How should WASM avoid dangling views when source buffers are evicted?
- Which caches must be global, per movie, per cast, per member, per sprite, or
  per frame?

## 15. Test Snippets To Prioritize

When real Director is available, these small scripts should close the largest
semantic gaps first.

```lingo
-- Property-list duplicate update
on startMovie
  pl = [#x: 1, #x: 2]
  setaProp(pl, #x, 9)
  put pl
  setAt(pl, 2, 8)
  put pl
end
```

```lingo
-- Dynamic slot allocation
on startMovie
  m = _movie.newMember(#field)
  put m.number && m.name && m.type
end
```

```lingo
-- Duplicate member name order
on startMovie
  m = _movie.newMember(#field)
  m.name = "x"
  put "dynamic:" && m.number
  put "lookup:" && member("x").number
end
```

```lingo
-- Cast switch lifetime
on startMovie
  old = member("oldName")
  put "old before:" && old.number && old.name
  castLib("External").fileName = _movie.path & "Other.cst"
  put "lookup after:" && member("oldName").number
  put "old ref after:" && old.number && old.name
end
```

```lingo
-- Text member image scope
on startMovie
  m = _movie.newMember(#text)
  m.text = "abc"
  img = m.image
  put img.rect && img.depth
end
```

## 16. Maintenance Rule

When a compatibility fix changes any behavior in this document, update this file
in the same change. If the fix is based on a manual citation or a real Director
test, add the source or test script near the relevant rule.
