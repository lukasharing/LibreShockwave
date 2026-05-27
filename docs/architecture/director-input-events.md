# Director Input Events

Director exposes mouse and keyboard state both as movie properties and as
classic no-argument functions. The runtime must treat these as the same state:
`the mouseH` and `mouseH()`, `the mouseLoc` and `mouseLoc()`, `the clickOn` and
`clickOn()`, `the rollover` and `rollover()` all observe the current input
snapshot.

`clickOn` is updated on mouse press and remains visible through the matching
mouse-up dispatch. It reports the last active clicked sprite; inactive visual
helpers should not become `clickOn`, while button-like controls remain eligible
even without authored mouse scripts.

`rollover()` is a live hit-test query, not just a stale mouse-move cache. Some
movies temporarily set a sprite's `visible` property to false inside a mouse
handler and then call `sprite(rollover())` to pass the event to what is now
underneath. Hit testing must therefore respect current sprite visibility even
when the most recent rendered frame still contains the previous visible state.

Hit testing follows Director's active-area rules:

- default sprite mouse areas are rectangular
- true native-alpha bitmaps use `alphaThreshold`
- `#matte` uses the displayed matte portion
- `#backgroundTransparent` stays rectangular for general bitmap mouse targeting

This keeps rendering transparency and input transparency separate except where
Director documents them as coupled.
