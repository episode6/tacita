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

### 2026-07-04 follow-up: the app tier is no longer exempt (Audioboom, Simplecast)

Live probes against the failing feeds (The Nextlander Podcast on Audioboom, Conan on
Simplecast), latest episodes, `curl` under different UAs, all numbers Content-Length:

- **Audioboom now injects on the app tier.** Nextlander episode 8923058 (declared
  `itunes:duration` 4170s, `enclosure length="0"` — Audioboom declares no size):
  okhttp UA → **70,539,410 bytes** twice back-to-back (sticky), AppleCoreMedia →
  72,227,090. The clean size is ~66.7MB (4170s @128kbps), so okhttp received **~231s of
  fill**. Serving-model fact 2 no longer holds for Audioboom; fact 1/3/4 still do.
- **Audioboom's redirect chain leaks the clean original.** The chain (prfx.byspotify →
  arttrk → clrtpod → pscrb → podtrac → audioboom) lands on a CloudFront
  `/v1/variant/….mp3` URL with `media_type=dynamic` whose query params include a signed
  `fallback_url` (`media_type=static`, path `…/attachments/<id>/<slug>.mp3`): the
  publisher's original upload, **66,843,410 bytes** — declared duration × 128kbps plus
  ~117KB of ID3. By construction it contains no injected ads. The intermediate
  `/v1/download/…` hop also leaks DAI metadata: `m=[1478560,1478560,2682539,2682539]`
  (two mid-roll slots, ms, start==end), `o=4170360` (original ms), `ab=128` (kbps); the
  final variant URL carries `al`/`ab` too.
- **Simplecast fills okhttp but serves bots clean.** Conan episode 6e993d49 (declared
  `enclosure length="58529858"`, 1:00:58): okhttp UA → **66,379,952 bytes** twice
  (sticky; ~490s of fill), AppleCoreMedia → 66,263,760 (different fill), plain
  **`curl` default UA → 58,529,431 bytes ≈ the declared enclosure length** — the clean
  canonical. The stitched URL embeds `x-total-bytes`.

So both field failures share one cause: on these hosts the pinned okhttp tier is a
*filled* tier with sticky fill, which blinds an immediate same-tier reference — while a
provably clean copy is discoverable out-of-band. Tier membership is host-specific:
okhttp is "app/clean" on Acast but "player/filled" on Audioboom and Simplecast.

### 2026-07-05: Audioboom DAI leaks are per-show, not per-host (Pod Save America)

First real-feed runs of the aggressive candidate pass (probe harness on the published
0.0.3-SNAPSHOT), prompted by a consumer report that PSA's pre-roll ads produced no
candidates. Pod Save America episode 8923605 ("Trump's 4th Threesome", declared
`itunes:duration` 4562s, `enclosure length="0"` as usual for Audioboom):

- **The pipeline behaves optimally — zero candidates is the correct output.** The pinned
  probe resolved to the dynamic variant (**76,611,592 bytes** twice back-to-back — sticky,
  ~2.9MB ≈ 3min of fill vs clean), was rightly rejected by the duration heuristic
  (implied 134.4kbps > 128k × 1.015), and the leaked `fallback_url` static copy
  (**73,695,496 bytes**, implied 129.2kbps ≈ 128k + ID3 art) validated → served directly.
  The injected pre-roll never reaches the output file, so there is nothing to mark: log
  shows `clean serving downloaded directly … no ad-cut needed`, `Complete` carried 0
  candidates. Any ad still heard at the top of a clean-served PSA episode is baked into
  the publisher's canonical upload (host-read), which byte evidence cannot see — that's
  the fingerprinting idea in "Alternative-detection research", not a candidate-pass gap.
- **PSA leaks no `m=[…]` and writes no CHAP frames**, unlike Nextlander on the same host:
  its variant URL carries only `al`/`ab`/`ao` (`ao` = audio offset: 691,592 bytes, exactly
  the ID3v2 tag size — the 690KB APIC artwork) plus an unparsed
  `metadata=dist%3Dgeneral_run_ads%26one_min%3D1651592…` blob, and its static copy's ID3
  tag has no CHAP frames at all. So DAI_SLOT/ID3_CHAPTER availability is **per-show
  publisher configuration, not per-host** — Audioboom carrying a signal for one show
  proves nothing about another. (`one_min=1651592` is noted but undeciphered — one
  observation is too little to parse against.)
- **Nextlander remains the known-results feed**: the same harness on its latest episode
  (8946742, 4170s) emitted 24 candidates, all `ID3_CHAPTER` STARTs from its rich chapter
  map (this special leaked no `m=`; the ear-verified episode 8923058 leaked two slots).
  Chapter-heavy shows demonstrate the designed false-positive tradeoff: most of those 24
  markers are content chapters, not ads — skippable suggestions, never cut points.

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

- **Clean-source discovery** (`CleanSourceResolver`, added 2026-07-04 after the field
  failures above): when the caller passes feed-declared expectations
  (`declaredEnclosureBytes` from `enclosure length`, `expectedDurationSeconds` from
  `itunes:duration`), tacita first probes (1-byte Range requests) for a serving that
  matches them: the pinned-tier serving itself, a `fallback_url` leaked in the resolved
  redirect chain, then bot-tier UAs (`curl/8.5.0`, `Wget/1.21.4`). A validated hit is
  **downloaded directly and served as-is — it is never used as a diff reference**
  (cross-tier diffing is dead end #2 and stays dead; serving the publisher's own upload
  cannot cut show content because it *is* the show). Validation tolerances are sized so
  one ad creative (~10s ≈ 160KB @128kbps) cannot validate: declared bytes must match
  within min(0.5%, 100KB) (floor 4KB); or the implied bitrate over the declared duration
  must sit within [-0.1%, +1.5%] of a standard CBR mp3 bitrate (VBR files simply never
  validate and fall through to the diff). After download the file size must equal the
  probed Content-Length (a short read looks like a completed download); mismatch deletes
  the copy and falls back to the diff pipeline. No expectations passed → resolution is
  skipped entirely and behavior is unchanged.

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
| Nextlander 8923058 via Audioboom static fallback (2026-07-04) | 66,843,410 bytes, decodes clean, duration 4170.37s vs declared 4170; ear-verified clean at both leaked mid-roll slots (ghackett, 2026-07-05) |
| Conan 6e993d49 via Simplecast bot tier (2026-07-04) | 58,529,431 bytes vs declared 58,529,858, decodes clean, 3658.08s vs declared 3658; ear-verified: pre-roll ads gone (ghackett, 2026-07-05) |

## Alternative-detection research (2026-07-04)

Surveyed after the Nextlander/Conan failures, looking for approaches that don't depend on
a same-tier reference (including waveform-level ideas). Findings, so nobody re-runs this:

- **Acoustic classification (waveform/ML)** — Adblock Radio (open source) is the
  strongest prior art: MFCC features → LSTM over 4s windows, ~95% train accuracy, plus a
  fingerprint hotlist of known ads to patch ML errors. Its own author documents the
  failure modes: music misread as ads, promos misread as content, host-read ads
  invisible. A probabilistic classifier cannot honor the under-cut invariant (it will
  eventually cut show content with no way to know), so this stays **rejected** — same
  conclusion as the loudness/silence dead end, now with outside evidence.
- **Transcript + LLM** — MinusPod / AGPAR / similar self-hosted projects (2025–26):
  Whisper transcription → LLM ad-span detection → ffmpeg cut, with VAD/loudness boundary
  refinement and cross-episode pattern learning. Genuinely effective per user reports,
  but needs GPU-scale transcription plus an LLM per episode, and wrong spans cut real
  content. **Rejected for tacita's core** (wrong layer for a KMP library); if ever
  wanted, the right shape is a pluggable ad-map hook a consuming app feeds from its own
  pipeline.
- **Splice-point detection** (audio-forensics literature, e.g. arXiv 2207.14682:
  high-frequency discontinuities, spectral-phase and local-noise-level breaks): only
  locates *joins*, says nothing about which side is the ad. At most a future boundary
  refinement aid. Not pursued.
- **Same-feed creative fingerprinting** — the one waveform idea worth building (tracked
  in TODO.md, not yet implemented): injected creatives repeat across *episodes* of a
  feed while show content is unique per episode, so a per-feed fingerprint index
  (landmark/ChromaPrint-style over decoded PCM) can flag repeats as ad candidates. This
  would catch sticky-fill ads that defeat the same-episode diff using only same-tier
  data. Known risks to design around: recurring intros/jingles also repeat (never cut a
  repeat that also appears in a verified-clean copy), and per the meta-lesson it ships
  as a log-only detector until ear-verified on real feeds.
- **Decoded-domain alignment** (PCM/spectral cross-correlation instead of byte rolling
  hash): would handle a host that re-encodes per request — the documented
  guards-skip-the-file blind spot. Large lift (mp3 decoder across all KMP targets);
  deliberately deferred until such a host is actually observed. None is today.

## Cross-episode creative reuse — the fingerprint experiment (2026-07-19)

Prompted by a proposal (ghackett) to add **human-confirmed ad fingerprints**: a consumer
app surfaces a suspected ad, a human confirms it is one, tacita stores a fingerprint and
cuts that creative from future episodes (tacita owns the logic only, never the UI). The
load-bearing question for viability: does the same creative recur **byte-identically
across different episodes** of a feed, or does the ad platform re-transcode per serving?
Byte-identity decides whether fingerprints can reuse the existing rolling-hash machinery
or need an mp3 decoder + acoustic (PCM-level) fingerprints across every KMP target.

Method (live probes, 2026-07-19): The Nextlander Podcast (Audioboom), episodes 8923058
(the known 4170s special) and 8920601 (ep 254, 10737s), fetched minutes apart: filled
okhttp-tier copies (sticky — 1-byte-Range probed twice each: 71,444,498 / 176,995,454
bytes) plus each episode's leaked `fallback_url` static copy (66,843,410 — byte-count
identical to the 07-04 measurement, so the static copy is stable across 15 days — and
171,912,062). Insertion-only alignment of filled vs clean recovered each episode's
injected fill exactly (both files fully consumed by the walk); shared-run analysis
(≥4KB runs, any byte alignment, hash-seeded + byte-verified + maximally extended)
compared the two fills; collision checks compared each fill against the *other*
episode's clean copy, and 8923058's clean intro/outro (5min each) against all of
8920601's clean copy (≥2KB).

Findings:

- Fill: 4 ranges in each episode (pre-roll, two mid-rolls at the leaked `m=` slots,
  post-roll): 287.5s / 4,600,720B in 8923058; 317.7s / 5,083,181B in 8920601.
- **Creatives are byte-identical across episodes**: 4,119,532B (257.5s) of fill is
  shared — 89.5% of 8923058's fill, 81.0% of 8920601's. Both mid-roll breaks match
  end-to-end across the two episodes.
- **Creatives are slot-independent**: 8923058's post-roll material also appears inside
  8920601's *pre-roll*, and 8923058's two mid-roll breaks are byte-identical *to each
  other*. Matching must be content-addressed, never slot/position-based.
- **Show content never byte-repeats**: zero ≥4KB runs between either fill and the other
  episode's clean copy, and zero ≥2KB (0.13s) runs between 8923058's clean intro/outro
  and anywhere in 8920601's clean copy — recurring intro music does *not* repeat
  byte-identically, because each episode is a fresh whole-episode encode; injected
  creatives repeat *because* they're stitched in as pre-encoded segments. On this host
  the two classes are byte-disjoint — the structural safety argument for byte-exact
  fingerprints.
- **Rotation is real but unquantified**: 8923058's filled serving grew 70,539,410 →
  71,444,498 between 07-04 and 07-19 (a different fill decision). The two episodes'
  fills compared here were served minutes apart; cross-*week* recurrence of a specific
  creative is not yet measured.

### Same day, second host: Simplecast normalizes creatives per episode (Conan)

Same protocol against Conan (Simplecast), "Mick Jagger" (3602s, declared 57,632,505) and
"Danny McBride Returns" (3435s, declared 54,971,777): okhttp-tier filled copies (sticky,
probed twice: 66,523,320 / 62,648,835) vs bot-tier clean canonicals (57,632,078 /
54,971,350 ≈ declared). Findings, materially different from Audioboom:

- **The insertion-only alignment assumption fails here** — each clean canonical carries
  2×416-byte stubs at the mid-roll splice points that the filled serving *replaces*
  (serving-model fact 4 at frame granularity; the canonical itself is a stitch). A
  two-sided AnchorIndex-style walk aligns cleanly: pre-roll + 2 mid-rolls + post-roll in
  both episodes (555.8s / 479.9s of fill).
- **Zero cross-episode byte sharing** (≥4KB) between the two fills, despite being served
  minutes apart. Yet **within one episode creative bytes do repeat**: a 71.52s creative
  appears byte-identically in both of Jagger's mid-rolls — the stitch concatenates
  pre-encoded segments on this host too.
- **Same creative, different bytes**: both episodes' pre-rolls open with a 42.68s
  creative — identical duration, identical encoded size (682,946B in both) — sharing
  zero bytes. Decoded PCM (gstreamer): zero-lag correlation 0.998, amplitude ratio
  ~1.23× (+1.8dB). **Ear-verified (ghackett, 2026-07-19): the two extracted creatives
  are the same ad.** **Simplecast loudness-normalizes each creative into the episode
  before encoding**, so encoded bytes are per-episode even for the same creative.
- **Content is not perfectly unique across episodes on this host**: 1.13s (two runs,
  ≤14KB each) of the two clean copies' outro tails are byte-identical — the canonical
  stitch embeds shared pre-encoded outro material. Minimum-fingerprint-length and
  never-match-verified-clean safeguards are therefore *required by observation*, not
  theoretical (though both runs sit far below any real creative length).

### Verdict

**Viable — as a layered design, with the layer depending on host class:**

- **Audioboom-class** (global creative cache, byte-identical cross-episode fill):
  byte-level fingerprints (anchor-block rolling hashes + a strong digest over the
  creative + frame-snapped edges) reuse the cutter's existing machinery with no mp3
  decoder and get full cross-episode power. Cheaper than the 07-04 research assumed.
- **Simplecast-class** (per-episode gain-normalized transcode): byte fingerprints only
  match within a single episode (including future re-downloads of it — still useful);
  cross-episode matching needs a level-invariant *acoustic* fingerprint over decoded
  PCM (spectral-peak constellation à la Wang 2003 — gain changes don't move peak
  locations), which requires an mp3 decoder in common code (the known large lift;
  minimp3 is CC0 and portable, and fingerprinting only needs mono downsampled output).
- **Bridge signal, no decoder needed**: creative *duration + encoded CBR byte-length*
  survives Simplecast's re-encode exactly (42.68s / 682,946B recurred to the byte) —
  candidate-grade cross-episode corroboration only, never cut authority (standard ad
  lengths will collide).

Literature (2026-07-19 web survey, agent-assisted) supports the acoustic layer when
needed: audfprint (MIT) ships a documented known-ad-search workflow with field-tuned
parameters (11.025kHz, ~100 hashes/s for ads, high min-hit thresholds and time-range
output); Olaf proves Wang-style matching runs on microcontrollers (compute is a
non-issue); Chromaprint is a whole-track matcher, wrong shape for ad-in-episode search;
SponsorBlock-style crowdsourced *timestamps* are structurally broken for DAI (per-listener
stitches) while fingerprints are position-independent — which is exactly this proposal;
industry guidance puts creative rotation at 2–4 weeks and campaign flights at 4–8 weeks,
so confirmed fingerprints should stay useful for weeks and (because DAI backfills old
episodes with current campaigns) fingerprints learned on new episodes also clean
back-catalog downloads. No published source addresses byte-stability of re-served
creatives — the probes above are ahead of public knowledge there.

Design constraints recorded now so they survive until it's built:

- **Provenance grades**: `DIFF_PROVEN` (auto-seeded from applied diff cuts — free, no
  human, every successful diff bootstraps the store) vs `HUMAN_CONFIRMED` (an
  ear-verified candidate — the meta-lesson's only-trusted-oracle applied per-creative).
- **Cutting authority**: at most HUMAN_CONFIRMED + byte-exact full-digest match may cut;
  DIFF_PROVEN matches surface as high-confidence candidates first and the whole feature
  ships log-only until ear-verified on real feeds (the unchanged rule). This would be
  the first cut path with no same-tier reference — permissible only because the human
  ear replaces the reference as the oracle.
- **Escape hatches are mandatory**: never keep a fingerprint that also matches a
  verified-clean serving (protects against stitched-in recurring intros / cross-promos
  a human might mis-confirm), and consumers must be able to revoke a fingerprint.
- A confirmed range that accidentally includes episode-unique edge bytes will simply
  never match again (full digest fails) — the miss direction, which is the acceptable
  failure. Trimming to segment-join/diff evidence at extraction time keeps the reusable
  creative core matchable.
- **Minimum fingerprint length** (several seconds at least): the observed byte-shared
  clean-content runs (Simplecast outro stubs, ≤0.9s) sit far below real creative
  lengths (shortest observed ~7s; typical 30–90s), so a length floor alone excludes
  the known benign-repeat class.
- Byte fingerprints inherit the known re-encode blind spot: a host that re-transcodes
  per request defeats them exactly like it defeats byte alignment; the acoustic layer
  is the escalation path (and Simplecast's per-episode normalization already motivates
  it — see the verdict above).

Open before shipping: Acast player-tier reuse measurement, cross-week creative
recurrence (both same-host and the fill-rotation shelf life of a stored fingerprint),
and the store format / `Tacita` API shape.

## The aggressive candidate pass (2026-07-05, additive)

`AdBoundaryDetector` is a read-only last pass over the final output file that emits
`AdBoundaryCandidate(timeMs, source, role)` markers on `DownloadState.Complete` — points
that *might* be an ad start/end, for consumers to render as skippable chapter markers.

**Why this doesn't violate the under-cut invariant:** the invariant governs bytes removed,
and this pass removes none — it cannot touch the file, cannot fail the download (every
signal is independently guarded), and runs after the cutter has already done whatever the
guards allowed. Because a false positive costs the listener one bogus marker instead of
lost show content, the pass is deliberately aggressive where the cutter is deliberately
conservative. The two live on opposite sides of the meta-lesson: byte-shaped evidence is
good enough to *suggest*, never to *cut*.

Signals surfaced (all data the pipeline already computed and previously discarded):

1. **Segment joins** (`Mp3SegmentParser.scan`, first production use): tag-frame stitch
   boundaries in the output. Dead end #1 stands unchanged — segment structure still
   classifies nothing; joins are emitted as `JOIN` candidates, not ad verdicts.
2. **The diff** (`AdCutter.Result` now carries its frame-snapped cut list): applied cuts
   collapse to single splice-point `JOIN`s mapped into the post-cut timeline (each splice
   shifts back by the material removed before it); guard-refused (`Skipped`) ranges — the
   sticky-fill/garbage-reference cases where the file stays untouched — map directly to
   `START`/`END` pairs. This is the first time the guards' refusals are visible to
   consumers at all.
3. **Leaked DAI slots** (`CleanSourceResolver` now returns the parsed `m=[…]` positions it
   previously only logged): emitted as `JOIN`s in the host's original/clean timeline. On
   the clean-source path that maps 1:1 to the output; on the diff path they're emitted
   as-is — the post-cut output approximates the clean timeline, and any drift is an
   accepted false positive. Slots only exist when the caller passed feed metadata (the
   resolver never probes without it — no new network requests were added).
4. **Host-written CHAP edges** (`Id3FrameReader`, extracted read-only from
   `Id3ChapterShifter`; shifting behavior unchanged): every chapter start plus the final
   end, read from the output file's tag *after* any CHAP shifting, so times are already in
   the output timeline. Audioboom labels ad slots this way.

Hygiene: near-duplicates within one source merge (250ms window, earliest wins); different
sources are never merged — two signals agreeing at the same timestamp is corroboration the
consumer should see. The list is capped at 64 (a wholesale-disagreeing reference can
produce hundreds of `Skipped` ranges; aggressive ≠ unbounded) and sorted by time.

### Confidence model (2026-07-05, additive)

Each candidate carries `confidence: Float` in `0..1` so consumers can sort or threshold a
skip list. The values are **uncalibrated priors chosen by evidence semantics, not measured
error rates** — per the meta-lesson, nothing here has been ear-calibrated, so only the
*ordering* is defensible:

| signal | base | reasoning |
|---|---|---|
| `DIFF_CUT` applied splice | 0.9 | the diff *proved* injected material and the cutter acted |
| `DAI_SLOT` | 0.8 | the host's own ad server placed a slot here |
| `DIFF_CUT` guard-refused range | 0.65 | real byte-diff evidence the guards declined to act on |
| `SEGMENT_BOUNDARY` | 0.4 | an encode join — ads and content assembly alike (dead end 1) |
| `ID3_CHAPTER` edge | 0.3 | usually a content chapter; only sometimes labels an ad slot |

Corroboration: candidates from different sources within the 250ms merge window combine as
independent evidence — each agreeing candidate's confidence becomes `1 - Π(1 - cᵢ)` (e.g. a
segment join confirmed by a DAI slot: `1-(0.6)(0.2)` = 0.88). The 64-cap now keeps the
highest-confidence candidates rather than the earliest, so a garbage-scale diff can't crowd
out a corroborated marker late in the episode.

Calibration path (open): the priors could be fit against ear-verified maps once enough
exist (the Nextlander "First Break"/"Second Break" chapters are obvious anchors — a
title-aware chapter signal is a cheap future upgrade). Until then consumers should treat
the values as ordinal, and no confidence — including 1.0 — licenses auto-cutting or
auto-skipping.

**Verification status:** unit/e2e coverage only (synthetic fixtures + the MockEngine
pipeline tests). Per the meta-lesson, no candidate map has been ear-checked against a real
serving yet — consumers get the do-not-auto-cut warning in the API docs, and the first
real-feed candidate lists (e.g. Nextlander's leaked slots at ~24:38 / ~44:42) should be
spot-checked by ear before podcast-hacker surfaces markers to users.

### 2026-07-05 field failure: whole-file read OOMed silently on Android

First consumer deployment (podcast-hacker on Android) surfaced as "candidates work on
desktop and on short episodes, but long episodes yield zero, silently":

- The initial pass read the entire output file into one ByteArray. Nextlander episode 252
  (8844s, **141,637,667-byte** clean copy, 55 CHAP frames intact on disk — verified by
  pulling the device file) allocated over the Android per-app heap; the resulting
  `OutOfMemoryError` was caught by the pass's own per-signal guards (they intentionally
  catch `Throwable` so no signal can fail a download) and yielded an empty list. The
  4170s / 66.8MB episode fit, which made the failure look episode-specific. Desktop JVMs
  (multi-GB heaps) never reproduced it.
- Two lessons already paid for: **the guards' silence has a diagnostic cost** — consumers
  that discard the `log` lambda (podcast-hacker did) see nothing at all, so consumers
  should wire `log` somewhere visible; and **whole-file byte arrays are not mobile-safe**
  — episodes routinely exceed 100MB.
- Fix (same day): the segment scan streams through a fixed 1MB refilling window
  (`Mp3SegmentParser.scan(FileHandle)`; back-margin covers within-frame re-reads, which
  are bounded by one mp3 frame < 2KB), and the chapter read fetches only the leading
  ID3v2 tag (header + declared size, capped at 16MB against corrupt sizes). Detection is
  byte-identical to the array path — window-equivalence is tested down to 4KB windows.
- **Still open:** `AdCutter` reads both full copies into memory on the diff path (2× the
  episode size at once). Unreproduced in the field so far only because big Audioboom
  episodes take the clean-source path; a large episode on a diff-path host (e.g. Acast)
  would hit the same Android ceiling — likely as a failed download rather than a silent
  no-op, since the cutter is not guarded. Streaming the diff is a larger lift (the
  rolling-hash diff wants random access); deferred until observed.

## MP3-level facts worth keeping

- Tag-frame discriminator (used by `Mp3SegmentParser.scan`; the cutter no longer depends
  on it, but since 2026-07-05 the aggressive candidate pass uses it for segment-join
  markers): tag string within the first 200 bytes of the frame body AND fill-byte
  (0x00/0x55/0xAA) fraction > 0.10. Measured: audio frames max ~0.13, mean 0.024; tag
  frames 0.12–0.92.
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
  fill with a 2h-old reference. Mitigation is reference *age*, not more copies — or, once
  built, the confirmed-creative fingerprint store (see the 2026-07-19 experiment), which
  needs no reference at all for creatives it has already seen.
- On the app tier, Acast currently injects nothing (canonical stitch) — the cutter is
  insurance for when that changes, plus an active cutter for player-tier copies.
- Back-to-back downloads get identical fill → immediate primary+reference double-download
  yields `NoAdsFound` on a filled tier. Persisted references fix this across sessions.
  Confirmed in the field 2026-07-04: podcast-hacker's immediate pairs left Simplecast
  (Conan) ads uncut — see the serving-model field observations. Same-day mitigation:
  clean-source discovery sidesteps the reference entirely when the caller supplies feed
  metadata and the host exposes a clean serving (both failing hosts do). Feeds where no
  candidate validates (declared size wrong AND VBR audio, or a host that fills every
  tier and leaks nothing) still depend on reference age.
- Clean-source discovery leans on feed honesty: a wrong `enclosure length` /
  `itunes:duration` can't make it cut content (worst case it validates a copy that
  still carries fill — the under-cut direction), but ear checks on the first
  clean-served episodes of each host are still owed before trusting the map
  (playbook step 5). **Ear-verified 2026-07-05 (ghackett)**: Nextlander's static
  fallback is clean at both leaked mid-roll slots (~24:38 and ~44:42), and Conan's
  bot-tier copy has no pre-roll ads — the servings that previously carried fill.
- Audioboom's leaked `m=[…]` slot positions are surfaced as ad-boundary *candidates*
  (2026-07-05, see the aggressive candidate pass) but still don't drive any cutting; if
  the static fallback ever disappears they're the obvious next input for a slot-targeted
  diff.
- If a host starts varying content encoding per request (re-encoding, not stitching),
  byte alignment finds no anchors and the guards skip the file — safe but ineffective.
  Detecting that case would need decoded-domain comparison (a large, unproven step).
