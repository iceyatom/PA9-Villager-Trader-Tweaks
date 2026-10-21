# Trade Reorder — Minecraft 1.19–1.19.2 (Fabric)

A mod for the villager trading screen that lets you reorder trades per
villager, restore that order on every subsequent visit, and view the exact
future trades generated for a villager in singleplayer. Orders are saved
independently for each villager and scoped to each world/server so copies of a
world never bleed into each other.

---

## How it works

Villager offer lists are server-authoritative — the server owns the list and
the client receives a copy. The reorder feature works on that client-side copy
and never sends anything to the server outside of normal trade packets.

**Future trade view.** In singleplayer, the mod also runs on the integrated
server. When a villager's trade screen opens, future level-up trades are
generated immediately using the same vanilla trade-set generation path, saved
on that villager, and sent to the client for display. When the villager later
levels up, the saved future trade set is appended instead of allowing vanilla
to roll a new set, so **View All** shows trades the villager will actually
offer.

**Display order.** On screen open, the mod captures the server's offer list as
a sequence of content fingerprints (`costA|costB|result` by item id and count),
then physically reorders the client's copy to match the saved order for that
villager. Because the reorder happens on the actual list object the screen
reads, the display follows without touching any render internals.

**Selection translation.** When you click a trade, vanilla computes
`shopItem = button.index + scrollOffset` and sends that index to the server via
`ServerboundSelectTradePacket`. Since the server's list is in the original
order, the mod intercepts `postButtonClick` and translates the display index
back to the original server index before the packet is sent, so the trade that
executes always matches the one you clicked.

**Persistence.** Orders are stored in
`.minecraft/config/trade-reorder/orders.json` as a flat map of
`<scope>|villager:<UUID>` → `[fingerprint, ...]`. The scope prefix is
`sp:<world folder>` for singleplayer or `mp:<server address>` for multiplayer,
which prevents UUID collisions between copied worlds or different servers.
Fingerprint matching means a saved order survives a villager gaining, losing,
or re-leveling trades — unrecognised trades are appended at the end.

---

## Requirements to build

- **JDK 17** for the Gradle Java toolchain (Minecraft 1.19–1.19.2 requires Java 17), plus
  **JDK 21 or newer** to run Gradle itself (JDK 25 works).
- **Gradle 9.6+** on your `PATH` — needed once to generate the wrapper, then
  `gradlew` is self-contained.
- Internet access to `maven.fabricmc.net` and Mojang's servers on first build
  (Loom downloads the 1.19 client jar).
- IntelliJ IDEA **2025.3+** if developing (earlier versions don't resolve
  mixins correctly for this MC version).

---

## Build

```bash
# 1. Unzip and enter the project folder.
cd trade-reorder

# 2. Confirm a JDK 21+ is active for Gradle (JDK 17 must also be installed).
java -version

# 3. Generate the Gradle wrapper (once only).
gradle wrapper --gradle-version 9.6.0

# 4. Build.
gradlew.bat build        # Windows
./gradlew build          # macOS / Linux

# Output:
#   build/libs/villager-trade-tweaks-1.19-1.2.2.jar          ← install this
#   build/libs/villager-trade-tweaks-1.19-1.2.2-sources.jar  ← ignore
```

On subsequent rebuilds after source changes, step 3 is not needed — run
`gradlew.bat build` directly.

The build also runs `mixinSmokeTest`, which loads the packaged mod through Fabric,
checks its mixin targets and trade-button access, and runs Minecraft bootstrap
without opening a game window. This catches runtime compatibility errors that
Java compilation alone misses. In-game interaction still needs manual testing.

---

## Install

1. Install **Fabric Loader** for your Minecraft version (1.19–1.19.2) via the Fabric
   installer.
2. Place the **Fabric API** build for your Minecraft version in `.minecraft/mods/`
   (this jar was built against Fabric API 0.58.0+1.19).
3. Place `build/libs/villager-trade-tweaks-1.19-1.2.2.jar` in `.minecraft/mods/`.
4. Launch the matching `fabric-loader-<version>` profile.

---

## Using it in-game

1. Open a villager or wandering trader's trade screen.
2. The **Mode: Trade** toggle sits above the top-left of the trade list.
   A **Reset** button sits immediately to its right.

**To reorder:**
1. Click **Mode: Trade** → it becomes **Mode: Reorder**.
2. Click any trade row to select it — the `#N/total` counter on the left
   updates to show which row is selected.
3. Click **Up** or **Down** (left side of the screen) to move the selected
   trade. The new order saves to disk immediately.
4. Click the mode button until it returns to **Mode: Trade** to resume normal
   trading.

**To view all generated trades:**
- Click the mode button until it shows **Mode: View All**. The trade list shows
  current offers plus a villager's generated future offers. Future offers are
  display-only and cannot be selected for trading or reordering. Wandering
  traders do not have generated future offer tiers, so this mode only shows
  their current offers.

**To refresh (cycle) a fresh villager's trades:**
- In **Mode: View All** a **Cycle** button appears on the left (in the slot the
  Up/Down buttons use in reorder mode). It re-rolls the villager's trades — both
  the current offer set and all generated future tiers.
- It is **only enabled for a villager that has never been traded with**: its XP
  must be zero and it must still be at the lowest level. Trading with a villager
  even once raises its XP and permanently disables the button for that villager.
  When the villager isn't eligible the button is shown greyed-out. Wandering
  traders are never eligible.
- Each click generates a brand-new random set, so you can keep cycling until you
  get trades you like, then start trading.

**To reset:**
- Click **Reset** at any time to restore the villager's original trade order
  and delete the saved ordering for that villager. Works whether reorder mode
  is on or off.

Saved orders persist across sessions. The next time you open the same villager
the list is restored to your saved order automatically.

---

## Technical notes

- **Class tweaker.** `trade-reorder.classtweaker` makes
  `MerchantScreen$TradeOfferButton` accessible to the compiler (it is private
  in the dev jar). No fields are made mutable — the mod reorders the
  `MerchantOffers` list instead of rewriting the buttons' `final index` field,
  which would cause an `IllegalAccessError` at runtime on Loader 0.18.
- **Mixin targets.** The mod injects into `MerchantScreen.init` (adds buttons),
  `MerchantScreen.extractContents` (calls `tryInit` on render),
  `MerchantScreen.mouseClicked` (click-to-select in reorder mode), and
  `MerchantScreen.postButtonClick` (index translation). All targets are methods
  declared on `MerchantScreen` itself; the mod does not target inherited methods
  from `AbstractContainerScreen`.
- **Trade refresh (Cycle).** The client sends an empty `cycle_trades` payload;
  the server resolves the villager from the requesting player's open
  `MerchantMenu` (via a `trader`-field accessor), and — only if the villager is
  still fresh (`getVillagerXp() == 0`, lowest level) — clears its offers, drops
  the cached future tiers, and re-runs the vanilla `updateTrades` generation
  path to roll a new set. The refreshed current offers and regenerated future
  tiers are then pushed back to the client. The button's eligibility is gated
  client-side using the XP/level the merchant offers packet already syncs
  (`MerchantMenu.getTraderXp()` / `getTraderLevel()`), and the server re-checks
  before acting, so a tampered client can't refresh a villager that has traded.
- **Re-init on content change.** When the screen receives a refreshed offer set
  it must recapture the server's original order (used for index translation).
  `tryInit` keys off an order-independent fingerprint signature of the offers, so
  reordering, reset and restock (which only permute or reset uses) don't trigger
  a recapture, but a genuine re-roll does.
- **Orphaned data.** If a villager dies its `orders.json` entry is never
  automatically removed. Entries are small (a handful of strings each) so this
  is not a performance problem, but you can manually delete `orders.json` to
  clear all saved orders, or use the Reset button per villager while it is
  alive.
- **Source sets.** Client UI code lives in the `client` source set. Future
  trade generation and persistence live in the common source set so the
  integrated singleplayer server can store generated future offers on villager
  entities.

---

## Planned features

### Trade cycling (unlocked trades)

> Distinct from the **Cycle** button shipped in 1.2.0, which re-rolls *all* of a
> never-traded villager's trades at once. The idea below is a finer-grained,
> per-offer variant cycler for villagers you have already traded with.

When a villager still has uses remaining on a trade, add a button to cycle
through and force-refresh that offer's presented variant — useful for trades
with RNG outcomes (enchanted books, maps) where you want a specific result
without having to close and reopen. This would work by sending repeated
select + deselect packets client-side to trigger the offer cycling behaviour
the server already supports, without actually consuming items.

The main considerations here are rate-limiting (to avoid looking like an
autoclicker to server anti-cheat), only enabling the button when the trade has
uses remaining (checking `MerchantOffer.isOutOfStock()`), and making it visually
clear which trade is being cycled.

---

## Version history

### 1.2.2 — 2026-06-01

- Fixed reordered trades previewing the wrong items in the trade squares. When a
  trade had been moved, clicking it executed correctly but the result-slot
  preview, payment-slot fill, and out-of-stock/progress overlays showed a
  different trade. The selection translation that maps a clicked display row back
  to the server's original index was overwriting the screen's `shopItem` field,
  so every consumer that reads the (reordered) client offer list resolved the
  wrong offer. Only the outgoing `ServerboundSelectTradePacket` now carries the
  server index; `shopItem` stays a display index, so the preview matches what was
  clicked while the executed trade remains correct.

### 1.2.1 — 2026-06-01

- Fixed a bug where scrolling down in **Mode: View All** on a fresh (level 0)
  villager and then switching back to **Trade** or **Reorder** left the
  villager's currently available trades greyed out / unselectable until the
  screen was closed and reopened. Leaving View All shrinks the display list back
  to the current offers, but the leftover scroll offset pushed every visible row
  past the end of the shorter list, so the real trades were treated as
  unavailable. The scroll offset is now clamped back into range on every mode
  switch (mirroring vanilla's own scroll clamp).

### 1.2.0 — 2026-06-01

- Added a **Cycle** button in **Mode: View All** that re-rolls a villager's
  trades (current set + all generated future tiers).
- Only enabled for villagers that have never been traded with (zero XP, lowest
  level); the server re-validates before re-rolling, so the gate can't be
  bypassed by the client.
- The trade screen recaptures the server's original order after a refresh, so
  reordering and index translation keep working on the new trades.

### 1.1.0 — 2026-06-01

- Added **Mode: View All** to display current offers plus generated future
  villager trades in singleplayer.
- Future trades are generated server-side, saved on the villager, sent to the
  client for display, and consumed when the villager levels up.
- Reorder mode continues to operate only on real/current offers.

### 1.0.0 — 2026-05-31

Initial release.

- Per-villager trade reordering via **Reorder: ON/OFF** toggle.
- **Up / Down** buttons move the selected trade row; order saves to disk immediately.
- **Reset** button restores the server's original trade order and removes the saved ordering for that villager.
- Orders persisted in `.minecraft/config/trade-reorder/orders.json` keyed by scope + villager UUID.
- Scope prefix (`sp:<world>` / `mp:<address>`) prevents UUID collisions across copied worlds or different servers.
- Fingerprint matching (`costA|costB|result`) keeps saved orders valid when a villager gains, loses, or re-levels trades — unknown trades are appended at the end.
- Index translation in `postButtonClick` maps the display-side index back to the server-side index before `ServerboundSelectTradePacket` is sent, ensuring the correct trade executes regardless of display order.
- Targets Minecraft **26.1.2** with Fabric Loader **0.18.4** and Fabric API **0.150.0+26.1.2**.
