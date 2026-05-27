# Cast Resource Mapping

Director cast-library lookup must distinguish visible slot numbers from file
resource identity.

For file-backed casts, `MCsL` provides the logical cast-library entry, including
the cast resource ID and the visible `minMember` range. `KEY*` then maps that
cast resource ID to the `CAS*`/`CASp` cast mapping chunk. The mapping chunk
contains member resource IDs in visible slot order:

```text
MCsL entry.castResourceId
  -> KEY*(owner = castResourceId, fourCC = CAS*) gives cast mapping resource ID
  -> CAS* mapping index plus minMember gives visible member slot
  -> mapping value gives CASt member resource ID
```

`memberCount` is not a stable identity key. It remains useful as a fallback for
older or incomplete files, but when `KEY*` is available it must win over any
member-count heuristic. This prevents two casts with the same member count from
swapping their visible member tables.

Member-name lookup is still driven by visible cast order and visible slot order.
Resource IDs identify file records; they do not define member lookup order.
