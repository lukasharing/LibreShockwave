# Director Imaging Lingo

This file records the current emulator contract for Director image objects,
`image.draw`, and `copyPixels`.

## Blank Images

`image(width, height, depth)` creates a white, opaque image. In the emulator's
RGBA buffers, that means `0xFFFFFFFF`. For paletted images with an explicit
palette reference, the white fill should also preserve palette-index provenance
so later palette remapping and indexed matte logic still have the authored
index information.

## Rect Geometry

Director rectangles are half-open regions: width is `right - left`, and height
is `bottom - top`. A rectangle `rect(0, 0, 25, 25)` covers 25 by 25 pixels. For
a one-pixel rectangle outline, the right and bottom strokes land at `x = 24`
and `y = 24`.

Point-to-point line drawing is separate: the two points are line endpoints.

## Background Transparent

`copyPixels(..., [#ink: 36])` uses exact background-color keying. If no
`#bgColor` is provided, the key is white. The key applies to every matching
source pixel, not only to edge-connected pixels. Surviving pixels keep their
source colors; `#backgroundTransparent` is not a recolor operation.

Text-rendered buffers may carry the text renderer's explicit background color
as provenance. When present and valid for the copied region, that background is
the preferred key. Otherwise the regular Director default remains white.

## Matte

Do not infer a matte color from a black border on Lingo-created UI buffers.
Blank `image()` buffers start white, and scripts often draw black outlines on
the edge before copying the image into a member or element. Those black grid or
frame pixels are real content and must survive.

`createMatte()` and sprite/element matte composition may use flood-fill rules,
native alpha, or palette-index provenance depending on source type. Plain
`copyPixels(..., [#ink: 8])` must not erase edge-connected black drawing from a
white dynamic canvas.

## Element Composition

Application-level helpers such as `feedImage` should replace the element/member
pixel buffer. They should not bake the element ink into that buffer. The
authored sprite or element ink is still applied later by the normal render
pipeline.
