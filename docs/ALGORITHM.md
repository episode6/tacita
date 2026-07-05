# Algorithm research & validation history

**Audience: a future agent (or human) changing tacita's download/ad-cutting behavior.**
This is the empirical record behind the shipped design — what was tried, what killed each
earlier approach, and which invariants exist because a cheaper idea was proven wrong on real
servings. Read this before "improving" the algorithm: several obvious-looking optimizations
below were implemented, looked correct in byte analysis, and were reverted after they were
shown to cut real show content. Update this doc whenever the process changes (see
`.agents/update-algorithm-doc-skill/`).

All research was done 2026-07-02 → 2026-07-03 against live Acast and Audioboom feeds during
the original implementation in podcast-puller-2 (PRs #5/#6 there hold the commit-level
history; the private `~/worklogs` entries hold the raw session logs).

## Vocabulary

- **canonical stitch** — the stable episode file a serving tier returns; byte-identical
  across requests, length matches the feed's declared `itunes:duration`.
- **tier** — the class of client (keyed on User-Agent) an ad server groups a request into.
- **fill / creative** — dynamically-inserted ad material; a *creative* is one ad unit.
- **tag frame** — a silent Xing/Info/LAME/VBRI frame at the start of an independently
  encoded mp3 segment; a *stitch marker* when it joins two encodes.
- **reference** — a second copy of the same episode used for diffing.

## The serving model (empirical, both hosts)

1. Ad fill is **User-Agent dependent**. The same Acast URL returned 42:49 via curl UA and
   51:07 via iPhone UA.
2. The "app tier" (okhttp — also curl, wget, googlebot) received a **stable canonical
   stitch**: byte-identical across requests, duration exactly the feed's declared duration.
   Only "player" UAs (vlc, firefox, pocketcasts, AppleCoreMedia) received dynamic fill —
   every request differed.
3. **Fill decisions are sticky short-term**: back-to-back downloads with the same client
   were byte-identical. Fill rotates over hours (a 2-hour-old copy differed). Time
   separation between same-tier copies is therefore the accuracy lever.
4. Different tiers **substitute material** — a baked-in segment present in the app tier was
   absent from player-tier servings. A cross-tier reference therefore over-cuts real
   content (this killed design #2 below).
5. Tacita pins the default UA (`okhttp/4.12.0`) because the served bytes — and every
   conclusion in this document — depend on the tier.

### Field observations from podcast-hacker (2026-07-04, tacita 0.0.1)

First real-consumer data, from podcast-hacker's stage-6 verification (okhttp engine, so
app tier; fresh installs, so every download was an immediate back-to-back
primary+reference pair — podcast-hacker v0 also deletes references after each download,
guaranteeing that pairing on every run):

- **"Conan O'Brien Needs A Friend"** (Team Coco/Earwolf; audio via podtrac/claritas
  redirects to `stitcher.simplecastaudio.com` — a Simplecast stitching host not previously
  covered by this doc): download + cut pass completed cleanly, but injected ads audibly
  survived in the output (ghackett, ear check). Consistent with either (a) sticky
  short-term fill on a host that DOES inject on the app tier — the back-to-back blind
  spot — or (b) ads baked into the app-tier stitch. Distinguishing needs a time-separated
  same-tier pair against this feed; either way it's the first observed host where the app
  tier serves ads that survive an immediate-pair diff.
- **"My Dad Wrote A Porno"** (Acast): outputs were ad-free in the same flow (ghackett,
  ear check). Given the immediate pairing, this is most consistent with Acast's app-tier
  canonical stitch (nothing injected to cut) rather than a demonstrated diff-cut.

Takeaway for consumers: the back-to-back blind spot isn't hypothetical — reference age is
the accuracy lever, so apps should persist references across sessions (or otherwise
separate the pair in time) rather than re-downloading both copies together.

## Dead ends (each looked correct in byte analysis)

### #1 — Segment-length classification (shipped briefly, reverted)

Parse the stitch structure (tag frames), classify short segments as ads. Worked perfectly
on an S1-remastered episode (9 segments: 4 long content, 2 ad blocks, jingle, empty slots).
Killed by a later-season episode (S5E11):

- Baked-in **short content** exists: a 55.7s intro and 48–108s production chunks around ad
  slots — a "<150s = ad" rule cuts ~320s of real show.
- At least one ad→content join had **no tag frame at all**: ads merged invisibly into a
  940s "content" segment (found via longest-common-suffix between copies) — ads missed.
- The simple S1 structure is the exception, not the rule. **Segment duration says nothing.**

### #2 — Cross-tier reference + gap-length threshold (shipped briefly, reverted)

Diff against a copy fetched with a different UA; recover markerless joins only when the
shared run is long (`minRecoverSeconds=300`, from the observation that markerless content
runs were ~784s while pinned creatives were ≤170s). Produced a beautiful-looking cut of
S5E11 (393s). **The user listened to the cut points: they were not ads.** The bytes unique
to the app-tier copy were baked-in show material the player tier legitimately doesn't
receive (serving-model fact 4). This would have cut ~6.5 minutes of real show. Both ideas
(different-UA reference, length-threshold recovery) were removed the same day.

**Meta-lesson (this happened twice):** byte structure genuinely looks ad-shaped. Every
wrong theory was "confirmed" by byte analysis. The only reliable oracle was a human
listening at the proposed cut points. Ask for an ear check before believing a new ad map.

### Also ruled out

- **Loudness/silence analysis**: ads are loudness-normalized to content; chat podcasts
  have hundreds of natural pauses.
- **Xing frame-count metadata**: the tiny 0.18s/1.15s segments flanking ad slots are LAME
  *flush stubs*, not Xing headers — no usable frame counts.
- **Third copy from another player UA** (to catch shared creatives): same substitution
  problem as #2; rejected without implementation.

## The shipped design

Diff the file against a **same-tier reference** (downloaded exactly like the primary — same
client, same UA). Byte runs present in both copies are kept; runs unique to the primary are
the injected ads. Identical copies → `NoAdsFound`.

- **Alignment is rsync-style, content-based** (`AdCutter.AnchorIndex`): the primary's
  aligned 4KB blocks are indexed by rolling hash; walk both copies in lockstep; at a
  divergence, scan the reference forward until a window matches an indexed block, verify
  bytes, then extend the match *backwards* to the exact rejoin point. Scanning continues
  4MB past the first hit so a creative that also appears later in the primary can't win
  over a nearer rejoin. No stitch markers needed — this is why Audioboom (which stitches
  with **no tag frames at all**) works identically to Acast.
- **Cut edges snap outward to mp3 frame boundaries** (frame headers match across encodes,
  so a divergence can land mid-frame). The splice is lossless — independently-encoded
  segments share no bit reservoir, so no re-encode is needed.
- **Guards** (`AdCutter.Config`): never remove more than `maxCutFraction` (0.25) of the
  duration; the copies must agree on at least `minMatchFraction` (0.5) of it — otherwise
  `Skipped` and the file is untouched.
- **ID3 CHAP shifting** (`Id3ChapterShifter`): Audioboom writes chapter frames that label
  ad breaks; after a cut, CHAP start/end times (and byte offsets when not the `0xFFFFFFFF`
  sentinel) are rewritten in place, shifted by the material cut before them. Conservative
  bail-outs leave the tag untouched: unsynchronisation flag, extended header, version
  ≠ 2.3/2.4, non-zero frame format flags.
- **Reference strategy** (in `Tacita.downloadPodcast`, moved from the consuming app): when
  overwriting an existing output it is *promoted* to become the reference (serving-model
  fact 3: an older same-tier copy diffs better than a fresh one); an existing reference is
  reused instead of re-downloaded; references are kept on disk for future runs. A stale
  reference is explicitly deleted before promotion (2026-07-04: `atomicMove` onto an
  existing target replaces on posix but can throw on Windows).
- **Download hygiene** (`Downloader`, 2026-07-04): non-2xx responses throw instead of
  saving the error body as an episode (dead episode URLs are a real-world case), and a
  failed download deletes its partial file. Both protect the reference strategy: a saved
  error page or partial file would otherwise be *promoted* to reference on the next
  overwrite run and poison the diff (the guards would then `Skipped` the cut, but the
  useful reference copy would already be lost).

### The invariant everything above serves

**The failure mode is always keeping too much, never cutting episode material.** Bytes
shared between two same-tier copies are never cut, regardless of how ad-like they look.
Any change that can cut shared bytes, use a cross-tier reference, or classify by
duration/structure alone re-opens a failure mode that was ear-verified twice.

## Measured results (real servings, 2026-07-03)

| Case | Result |
|---|---|
| Audioboom ~70min special, filled vs clean serving | all 4 markerless insertions cut (270.79s); output audio byte-identical to the clean serving (only delta: trailing 128-byte ID3v1 tag, dropped with the post-roll) |
| Audioboom chapters after cut | all 23 CHAP times match the clean serving; final chapter end drifts 0.037s (pointed inside the post-roll cut) |
| Acast S5E11, iPhone-filled vs 2h-old same-tier copy | 378.3s of 423.3s ground-truth fill cut (89%); remainder was byte-shared sticky creatives (designed under-cut); zero content cut |
| Acast S5E11, filled vs canonical | 347.3s cut; output stayed 76s above canonical = zero content cut |
| Identical pairs (several episodes) | `NoAdsFound`, byte-untouched |
| Decode checks | `ffmpeg -f null` full-file and splice-region decodes: zero errors, every case |
| Performance | ~4s for a 70MB file (JVM) |

## MP3-level facts worth keeping

- Tag-frame discriminator (used by `Mp3SegmentParser.scan`, retained for diagnostics; the
  cutter no longer depends on it): tag string within the first 200 bytes of the frame body
  AND fill-byte (0x00/0x55/0xAA) fraction > 0.10. Measured: audio frames max ~0.13, mean
  0.024; tag frames 0.12–0.92.
- **libmp3lame writes "LAME<version>" + 0xAA filler into its final flush frames**, which
  matches the tag-frame signature and creates a false boundary ~0.15s before EOF. The
  parser merges a trailing segment < 2s (`TRAILING_FLUSH_MERGE_SECONDS`) into the previous
  one for this reason. This affects generated test fixtures too.
- Empty ad slots appear as ~0.1–1.2s flush-stub segments in the canonical stitch.

## Validation playbook (how to re-validate a change)

1. **Unit fixtures**: `src/jvmTest/resources/audio/` — short sine-tone encodes (ffmpeg
   libmp3lame, 16kbps/22050Hz mono), no ID3v2 on the piece files so compositions don't
   embed a neighbor's header. Tests compose copies in code; "markerless" cases strip a
   piece's leading tag frame (`parser.frames(...).first().endByte`).
2. **Real pairs**: download the same episode URL twice at different times (or once each
   with the app's pinned UA and a player UA *purely to obtain a filled copy* — never as
   the reference). Run the cutter; map ground truth by byte-realign walk between filled
   and clean copies.
3. **Decode check**: `ffmpeg -f null -` over the full output and around every splice; zero
   errors expected.
4. **Chapter check** (Audioboom): CHAP times in the cut output vs the clean serving.
5. **Ear check**: before trusting any new ad map or classifier, have a human listen at the
   proposed cut points. This is the step that caught both dead ends.

## Known blind spots & open directions

- A creative served **byte-identically in both copies at the same slot** survives the cut
  (encoded as an explicit test — the "known blind spot"). Observed cost: ~45s of a 423s
  fill with a 2h-old reference. Mitigation is reference *age*, not more copies.
- On the app tier, Acast currently injects nothing (canonical stitch) — the cutter is
  insurance for when that changes, plus an active cutter for player-tier copies.
- Back-to-back downloads get identical fill → immediate primary+reference double-download
  yields `NoAdsFound` on a filled tier. Persisted references fix this across sessions.
  Confirmed in the field 2026-07-04: podcast-hacker's immediate pairs left Simplecast
  (Conan) ads uncut — see the serving-model field observations.
- If a host starts varying content encoding per request (re-encoding, not stitching),
  byte alignment finds no anchors and the guards skip the file — safe but ineffective.
  Detecting that case would need decoded-domain comparison (a large, unproven step).
