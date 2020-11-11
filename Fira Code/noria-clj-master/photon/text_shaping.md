##Shaping process
* [Blink's Text Stack](https://chromium.googlesource.com/chromium/src/+/master/third_party/blink/renderer/platform/fonts/README.md)


##Cache? 
Text editor view successfully employs cache, splitting the string on space characters. 

I am not sure this can be applied generally. First, i don't understand where this heuristic comes from. I don't see that many repeating words often.
Wide adoption means nothing by itself, cause your usual ui framework needs caches to reuse work between two frames.
Second, laying out words one by one proves two times slower then one-pass baseline (may be this can go away, i doubt there is some inherent overhead of hb_shape call). 
Can't think of a good use case to measure hit/miss ratio. Project view = miss, navigation dialog = hit, but only if i cheat and split by slashes first 

```
Shaping editor impl
327.949752ms 170996
test harfbuzz::tests::test_perf ... ok
343.607347ms 170996
test harfbuzz::tests::test_perf_layout_lines ... ok
693.089291ms 170996
test harfbuzz::tests::test_perf_layout_words ... ok
```

###Better cache?
Interesting points for opimization:
* UNSAFE_TO_BREAK flag  may be used to perform word wraps using single layout run https://harfbuzz.github.io/harfbuzz-hb-buffer.html#hb-glyph-flags-t
* discussion on different approaches to text shape caches with raph and behdad https://github.com/linebender/skribo/issues/4. It explains why UNSAFE_TO_BREAK cannot be used to find better cache keys
* this proposal seems relevant to my perception of ideal cache https://github.com/harfbuzz/harfbuzz/issues/1463

##Prior art

####EditorImpl
See TextFragmentFactory and his friends.
sun.swing.SwingUtilities2/isComplexLayout or java.awt.Font/textRequiresLayout allows fast path avoiding complex shaping.
Basically ascii + list of scripts, ignores font completely
https://github.com/AdoptOpenJDK/openjdk-jdk9/blob/master/jdk/src/java.desktop/share/classes/sun/font/FontUtilities.java#L154.
There is a short article from 2015 about blink eliminating similar approach and leaving only harfbuzz pass https://www.chromium.org/teams/layout-team/eliminating-simple-text

###Druid
Uses [Piet](https://github.com/linebender/piet) with cairo toy backend.
Raph has some [thoughts](https://github.com/linebender/piet/tree/master/piet-cairo) on migration 

TLDR, three options:
* pango works well on linux, but depends on glib
* harfbuzz is harfbuzz
* flutter's libtxt has no rust bindings

###libtxt
harfbuzz + minikin for shaping, rendered via skia
https://github.com/flutter/engine/tree/master/third_party/txt
said to be state of the art

###minikin
The low-level text layout library in Android. Forked by flutter, lives in libtxt source base, based on harfbuzz.

Shaping uses simplistic word cache where word boundary is a space character(s) or CJK ideograph. 
See Layout.cpp/doLayoutRunCached + LayoutUtils.cpp/getNextWordBreakForCache

###skia
Shaping is out of scope, [relies on harfbuzz](https://github.com/google/skia/blob/master/site/user/tips.md#does-skia-shape-text-kerning) 

###Pango
* FontConfig for font discovery
* FreeType for font access
* HarfBuzz for complex text shaping
* fribidi for bidirectional text handling

rust bindings do exist. LGPL?

Text rendering pipeline https://developer.gnome.org/pango/stable/pango-Text-Processing.html.
I know there is shape cache somewhere but it is well hidden

###servo
* [harfbuzz wrapper](https://github.com/servo/servo/blob/master/components/gfx/text/shaping/harfbuzz.rs)
* [fastpath for ascii characters(with kerning)](https://github.com/servo/servo/blob/8f7440f36881fa60f4237d0dec8928799a6aa747/components/gfx/font.rs) + [shape cache](https://github.com/servo/servo/blob/886c2fad9217e87b4a67410e194fdf063af9332d/components/gfx/text/text_run.rs)

### ICU (International Components for Unicode)
The [ParagraphLayout](https://unicode-org.github.io/icu-docs/apidoc/released/icu4c/classicu_1_1ParagraphLayout.html) object will analyze the text into runs of text in the same font, script and direction, and will create a LayoutEngine object for each run. The LayoutEngine will transform the characters into glyph codes in visual order. Clients can use this to break a paragraph into lines, and to display the glyphs in each line.
* Underlying LayoutEnding is deprecated and is replaced by [harfbuzz implementation](https://github.com/harfbuzz/icu-le-hb)
* There are several rust bindings most notably from [servo](https://github.com/servo/rust-icu) and from [google](https://github.com/google/rust_icu). None of them exposes ParagraphLayout and friends afaiu
* java library exists, but paragraph layout is not included
* seems that layout is not cached
* don't know anyone who uses it for text layout and not for it's knowledge of unicode


##Shaping performance
It greatly depends on the font used. Shaping EditorImpl.java in single pass:
```
shaping with "Verdana", is_monospace: false
100 iterations took 2.015595246s, avg 20.155952ms

shaping with "Menlo Regular", is_monospace: true
100 iterations took 5.183516797s, avg 51.835167ms

shaping with Fira Code v1, is_monospace: true
100 iterations took 14.739386553s, avg 147.393865ms

shaping with Fira Code v2, is_monospace: true
100 iterations took 18.507155032s, avg 185.07155ms

shaping with "JetBrains Mono Regular", is_monospace: false
100 iterations took 15.119650449s, avg 151.196504ms
```

Verdana contains 913 glyphs, Menlo has 3 157, maybe it explains the difference.

I have a suspicion about Fira Code/JetBrains Mono case. 
With Fira Code and EditorImpl.java we spend 80% of total `hb_shape` time inside of
[hb_ot_map_t::apply](https://github.com/harfbuzz/harfbuzz/blob/b28c282585afd3bff844e84eae7f29e1a1267aef/src/hb-ot-layout.cc#L1868).
Both fonts perform contextual glyph substitution in several passes (163 in case of fira code v2).
It is not a bug, Fira Code has over a hundred lookup clauses for `calt` feature. 
[Open type spec](http://adobe-type-tools.github.io/afdko/OpenTypeFeatureFileSpecification.html#7a-an-opentype-layout-engines-layout-algorithm) states that lookup has semantic of an independent glyph run.
Harfbuzz implements it exactly like that, it can be easily demonstrated with harfbuzz cli tools `hb-shape --trace frontend/resources/Fira\ Code/ttf/FiraCode-Regular.ttf "hello -> fira"`.

Same benchmark with `calt` feature disabled
```
shaping with "Verdana", is_monospace: false
100 iterations took 2.059393944s, avg 20.593939ms

shaping with "Menlo Regular", is_monospace: true
100 iterations took 4.96255013s, avg 49.625501ms

shaping with Fira Code v1, is_monospace: true
100 iterations took 3.405012071s, avg 34.05012ms

shaping with Fira Code v2, is_monospace: true
100 iterations took 3.689856737s, avg 36.898567ms

shaping with "JetBrains Mono Regular", is_monospace: false
100 iterations took 1.446767685s, avg 14.467676ms

```