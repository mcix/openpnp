# Plankjeplacer

In the DeltaProto panel add a configuration similar to the Feeder layout, we call the Plankje layout.
We define the location of pin 1 and pin 8.
On each pin is space for a covered strip of components, so there are 8 feeders in total.
By defining pin 1 and pin 8 we know all locations of the 8 feeders; the pins in between
are linearly interpolated. The nominal distance from pin 1 to pin 8 is 73.5 mm
(7 gaps of 10.5 mm).

**Coordinate frame**: the machine origin is at the bottom left, so +Y is north
(away from the operator). The strips run in the **negative Y** direction
(southwards from the pin).

**DeltaProto panel, Settings tab** ("Plankje layout"):
- Pin 1 and Pin 8 rows, each with X/Y fields plus **Set from camera** (copy the
  current down-camera position into the fields) and **Camera →** (move the
  camera over the entered position) buttons.
- A "Pin 8 = Pin 1 + 73.5 mm X" button to fill pin 8 from pin 1.
- Shared **Pick Z** and **Cover Z** fields, and a "Save plankje" button
  (persisted in Java Preferences).
- **Feeder management**:
  - A feeder pin number input (1–8) with a **Create feeder** button: saves the
    plankje config, then creates a `DeltaProtoCoveredStripFeederV1` named
    `Plankje-<pin>` on that pin (refuses duplicates of the same pin). The
    feeder's base location is mirrored to the pin position; the pick location
    is always derived live from the saved plankje layout.
  - An **Update all feeders from config** button: saves the plankje config and
    re-syncs every existing `DeltaProtoCoveredStripFeederV1` (location mirror
    and derived positions) to it.

## DeltaProtoCoveredStripFeederV1

A strip feeder with a sliding cover (lid). Modeled on `ReferenceStripFeeder`:
the strip remains static in the machine and the pick location advances along the
strip according to which component was picked last (`feedPosition`), except that
the cover must be slid open before every pick.

**Origin**: the pin the strip is placed on. This is the origin of the (reference)
hole on the strip.

**Geometry** (strip running −Y, south):
- Component centers are 3.5 mm in +X of the origin hole line (configurable);
  the cover plunge/slide line stays at +2.25 mm.
- The first component is 2 mm south (−Y) of the origin (configurable).
- Pitch along the strip is 4 mm (standard) or 2 mm.

**Cover slide, on every feed, with the SAME nozzle that will pick the part:**
1. Move the nozzle (at safe Z) to the fixed start point just north of the origin:
   X +2.25 mm, Y +5 mm from the pin.
2. Plunge to the configured cover Z.
3. Move SLOWLY in the negative Y direction (south), overshooting the Y of the
   component we want to pick by 1 mm, pushing the lid open. On the real
   machine this uses `HwgcDriver.moveXyRelativeOpenLoop` (calibrated
   open-loop timed jog at speed 1 — a normal XY goto would make the firmware
   lift Z and release the cover); in simulation it falls back to a regular
   slow move using the configurable cover slide speed factor.
4. Retract to safe Z; the same nozzle then picks the component from the
   paper strip at the configured pick Z.

**After the pick** (`postPick`): the nozzle, retracted to Z 0, XY-moves back
over the cover plunge point, so it exits the stack of feeders straight north
through this strip's own lane instead of dragging the picked part diagonally
across neighboring covers.

The plunge always happens at the same fixed point relative to the origin; only
the end of the slow slide moves along with the feed position.

**Feed position persistence**: `feedPosition` is the index of the NEXT component
to pick (0 = first). It is advanced on every feed and persisted immediately to
Java Preferences so it survives a restart or crash; the machine.xml copy is only
a mirror.

**Configuration wizard**:
- Pin (1–8), pitch, feed position (reset / back 1), cover slide speed.
- Two OpenPnP-style location rows with the standard position-camera /
  position-tool / capture-camera / capture-tool buttons:
  - **Cover plunge** — operates on the selected nozzle.
  - **First pickup (index 0)** — operates on the selected nozzle.
  X/Y are stored back as offsets from the origin pin; the Z fields write the
  plankje-wide cover Z / pick Z (shared by all 8 feeders).
- Manual step-by-step verification buttons, so the cycle can be checked
  without ever moving to a wrong coordinate and hitting a cover:
  1. Nozzle above plunge (Safe Z) — XY travel only, no descent.
  2. Plunge to Cover Z — refuses unless the nozzle is above the plunge point.
  3. Slide open SLOWLY (−Y south) — refuses unless the nozzle is plunged at the start point.
  4. Retract Nozzle (Safe Z).
  Plus: full cover-slide cycle, camera-to-origin, camera-to-next-pickup, and
  selected-nozzle-above-next-pickup (safe Z, no plunge).

## History

The original 40-pin plankje with uncovered strips (`DeltaProtoStripFeeder`,
strip direction configurable, first pickup 8.5 mm across / 2 mm along from the
pin) was replaced by this 8-pin covered-strip implementation in August 2026.
