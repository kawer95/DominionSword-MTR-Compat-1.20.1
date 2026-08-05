# 1.7.1

- Added Dominion Sword add-on metadata and localized add-on names for the F9 compatibility interface.

# 1.7.0

- Removed the redundant “Start / continue” action; selecting a destination station remains the only start command.
- Fixed compatibility rider synchronization resurrecting a real player's stale MTR rider immediately after native dismount.
- Uses MTR's authoritative `RIDING_PLAYERS` state to remove old ghost riders and return an empty train to native MTR control.
- MTR trains now use exclusive selection, cannot be mixed with units, cannot be saved to or restored from squads, and cannot be interacted with once all Dominion passengers have left.
- Added a periodic interior safety pass: living entities physically inside a car for 10 ticks without a valid passenger state are moved to the nearest collision-free point outside the complete train and receive one second of collision immunity.

# 1.6.9

- Fixed persistent Dominion vehicle movement discarding its resolved MTR platform key after the first control pulse and then reporting the same invalid station every tick.
- Rejecting an invalid map destination now clears that proxy's persistent movement task after one warning.
- “Clear target station” now clears both the MTR destination and Dominion's persistent proxy movement task, then holds the train.
- Repeated control pulses no longer reset an arrived train from HOLD back to ATO.

# 1.6.8

- Injected MTR 4.0.5's actual `VehicleRidingMovement.sendUpdate(true)` and authoritative `PacketUpdateVehicleRidingEntities.runServerOutbound` dismount paths.
- A long-Shift forced dismount now moves the player to the nearest collision-free point outside every car hull, using the live MTR car pose and dimensions.
- Every MTR dismount, including ordinary doorway exits, grants the player 20 ticks of client and server train-collision immunity so the synthetic hull cannot push them back inside.

# 1.6.7

- Fixed real MTR doorway `maxY` being passed to Minecraft navigation as if it were the mob's standing surface.
- Boarding now keeps the model doorway coordinate for MTR door control while projecting a separate approach target onto the actual block collision surface outside that door.
- Door traces now report both `doorBoxWorld` and the projected standing target so path precision can be verified directly.

# 1.6.6

- Synced the exact server-reserved MTR doorway per boarding unit so Dominion Sword's command marker and server navigation use the same point.
- Added command-serial, reservation-reuse, complete door-box geometry and horizontal-distance evidence for first-command versus reissued-command boarding failures.
- No additional boarding-distance or navigation behavior was changed without a new runtime trace.

# 1.6.5

- Fixed localized closing doors rendering on top of an already closed optimized train model.
- Fixed dismount-all waiting on a server timer instead of MTR's actual client-side native door animation.
- Fixed boarding tasks stalling at a completed navigation path because the doorway's vertical offset consumed the spherical distance tolerance.

# 1.6.4

- Added bounded `MTR BOARD TRACE` diagnostics for real-door selection, unit/navigation position, path progress, collision flags, distance gates, MTR door acknowledgement, final boarding and per-passenger dismount placement.
- Added client `MTR DOOR CLIENT TRACE` diagnostics for native door multiplier/value, localized override interpolation, closing doorway injection and active override counts.
- Added server `MTR DISMOUNT TRACE` samples throughout force-open acknowledgement and the native animation wait.
- This build intentionally adds evidence only; boarding coordinates, distance thresholds and door timing behavior are unchanged.

# 1.6.3

- Fixed startup `IllegalClassLoadError` caused by placing a directly referenced bridge interface inside the configured Mixin package.
- Moved the force-door access contract to the ordinary bridge package and replaced the cross-Mixin invoker cast with a direct `@Shadow openDoors()` call.

# 1.6.2

- Fixed “Dismount All” relying on MTR's ordinary driver door-toggle input, which could remain ignored until a later native station stop.
- Added a simulation-thread forced global-door target that suppresses MTR close requests only while dismount-all is active, including away from platforms and timetables.
- Door acknowledgement is now tied to the add-on's active force-door request; unrelated native station opening can no longer be mistaken for command success.
- Removed the ineffective repeated rider door-toggle pulses. After MTR's native door animation completes, occupants are placed outside their nearest real doors and the force is released for a native animated close.

# 1.6.1

- Fixed compatibility-requested depot doors jumping instantly to their held-open pose.
- Localized `doorOverride` doors now interpolate through MTR's native 3200 ms door movement duration and animate closed after the hold is released.
- Boarding waits for the same native movement duration, keeping visual door state and the server boarding gate aligned.

# 1.6.0

- Added frigatemod-style moving-roof support: mobs and players standing on a train roof are carried by each car's previous-to-current pose transform.
- Added matching client prediction for the local player so roof travel does not rely on visible server corrections.
- Roof contact now requires a real support/crossing test and releases immediately while jumping.
- Dominion Sword now persists the MTR collision setting and synchronizes it when players log in, change dimension, or respawn.

# 1.5.0

- Gives a unit-driven train exclusive stop control: MTR's ATO next-stop index is overridden with the selected destination, so intermediate scheduled stops are skipped.
- Cancels MTR departure attempts after the controlled train has stopped at its destination; removing the unit driver immediately restores MTR's native next-stop calculation and control.
- Fixes single/all dismount being rejected unless MTR's whole-train door multiplier was open; stopped compatibility trains can now unload their occupants at real doorway positions.
- “Dismount all” forces MTR's global doors open, waits for simulation acknowledgement and the opening animation, then unloads every occupant through its nearest real doorway.
- Finishes the short final doorway approach server-side after MTR acknowledges the real door as open, eliminating navigation/collision error that could leave a unit just outside boarding range.

# 1.4.8

- Prevents MTR `Siding.simulateTrain` from deleting an untimetabled compatibility-started ATO vehicle solely because its departure index is `-1`; the exemption is scoped to the exact vehicle and cleared when it returns off-route.
- Keeps the original vehicle ID and proxy alive after selecting a destination, preserving its driver and passenger bindings while the train starts.
- Restores the driver's P1 traction pulse during STARTING; after motion is established the existing state machine switches the train back to ATO.
- Transfers the localized boarding-door override to each approaching passenger even when seat zero already has a driver, allowing multiple units to board sequentially through real MTR doors.

# 1.4.7

- Starts compatibility-controlled trains on MTR's simulation thread through MTR's own `Vehicle.startUp(-1, currentMillis)` manual-departure path, bypassing timetable departure matching while retaining native signal, speed, track and station simulation.
- Persists every non-player train passenger's full entity NBT, seat and stable MTR binding in server SavedData before a proxy is removed or the server stops.
- Materializes proxies that have pending passengers and immediately restores them after MTR recreates the matching train; exact vehicle IDs are preferred with departure index and nearby rail progress as a guarded fallback.
- Never serializes or recreates player entities, and falls back to the nearest safe stop if an entity cannot be escrowed.

# 1.4.6

- Mixes into MTR 4.0.5 `RenderVehicleHelper.canOpenDoors` so an explicit positive door request can animate outside platform, PSD and APG detection areas.
- The bypass remains request-scoped: closed doors still return the original MTR result, while compatibility-held real doors and deliberate driver door opening can work in depots and non-platform track.

# 1.4.5

- Replaces server-player collision teleports with the same post-movement `setPos` correction and inward-velocity clipping used by frigatemod, allowing the client-side predictor to provide stable contact instead of repeated network rubber-banding.
- Uses MTR's localized `doorOverride` as the authoritative boarding-door state instead of incorrectly waiting for the whole-train `doorMultiplier` to become open.
- Waits for the override rider to appear in an MTR simulation snapshot and for the door animation interval before mounting a unit.
- Opens collision only at the held real doorway on both server and client; other doors remain solid unless MTR globally opens them.
- Adds bounded boarding-door lifecycle diagnostics for request, MTR acknowledgement and boarding readiness.

# 1.4.4

- Replaces the MTR menu's 128 detailed seat rows with one driver row and one aggregate passenger row showing occupied/total capacity.
- Clicking the aggregate passenger row boards selected units into available physical seats; detailed seat allocation remains server-side.
- Sends authoritative player collision corrections immediately through the server connection instead of relying on an end-of-tick `setPos` that the next client movement packet could overwrite.
- Adds bounded collision lifecycle diagnostics for service activation, the first candidate entity and the first resolved collision.
- Computes the train selection bounds from each car's actual MTR width, length, yaw and pitch instead of using the car half-diagonal as both horizontal radii.
- Registers compatibility-controlled units in MTR's simulator riding map before vehicle simulation, allowing the temporary driver door pulse to survive MTR's stale-rider cleanup and open the real train doors.

# 1.4.3

- Fixes the server MTR bridge publishing snapshots under MTR's `namespace/path` dimension format while Forge world lookup and direct clicks use `namespace:path`.
- Normalizes dimension ids at snapshot publication, lookup, world resolution and deterministic proxy UUID generation.
- Restores proxy creation, right-click menus and server-authoritative collision from the already successful MTR train snapshots.

# 1.4.2

- Fixes short right-clicks on rendered MTR trains doing nothing when the invisible proxy is absent from or missed by Dominion Sword's generic client entity ray cast.
- Directly ray-tests every rendered car's yaw/pitch-oriented body box using MTR client vehicle poses, then requests the existing server-authoritative seat/action menu through the deterministic train proxy UUID.
- The server now materializes a missing proxy immediately from a fresh matching MTR snapshot and returns visible feedback when no snapshot or no selected unit is available instead of failing silently.
- Adds bounded first-snapshot/failure logging for the MTR simulation bridge so proxy-generation faults can be identified from `latest.log`.
- Leaves right-button drags uncancelled so Dominion Sword's existing destination and formation command handling remains active.

# 1.4.1

- Fixes train hulls being visible in the debug overlay but not reliably blocking players or mobs.
- Uses persistent per-entity, per-car local contact history like frigatemod's `lastHullCollisionPositions`, instead of treating `xo/yo/zo` as the complete collision history.
- Runs the same continuous hull solver for the local client player after vanilla movement, matching frigatemod's client prediction path while the server remains authoritative.
- Keeps deck carrying and hull-versus-world-block collision separate; this patch only implements entity-versus-train-shell collision.

# 1.4.0

- Adds the default-off "Show Train Collision Debug Boxes" option to Dominion Sword's MTR settings sub-tab.
- Renders the exact shared per-car shell generator as cyan world-space wireframes, with orange end/body sections and visible missing wall segments at open doors.
- Debug boxes follow each car's yaw and pitch independently on curves and gradients and remain entirely client-side.

# 1.3.0

- Uses each installed MTR resource pack's real per-car doorway boxes for unit approach points and open-door collision gaps.
- Nearby clients periodically report bounded doorway metadata; the server validates car indices, dimensions, distance, finite coordinates and report ownership before accepting it.
- Units stop outside the real doorway, request an MTR door-control pulse, and mount directly after the door opens instead of trying to navigate through the non-block train interior.
- Compatibility-opened doors are held while units are passing. Five seconds after the last completed boarding, the hold is released and manually opened doors receive one closing pulse.
- Retains length-based virtual doors only as a temporary fallback before client resource geometry arrives.

# 1.2.0

- Adds optional server-authoritative physical collision for every MTR train car, controlled from the Dominion Sword F9 Mod Compatibility > MTR tab.
- Uses swept, car-local rotated hull collision inspired by frigatemod instead of one oversized whole-train AABB.
- Closed trains block entities along the full car shell; open trains remove nominal doorway sections on both sides so entities can enter.
- Captures rail slope pitch so collision follows trains on gradients.

# 1.1.1

- Fixes Dominion Sword clicks not detecting MTR train proxies on the client.
- Synchronizes the full multi-car train selection bounds to clients while keeping proxies invisible and non-colliding.

# 1.1.0

- Units now approach reserved virtual door positions distributed along both sides of each car before mounting.
- Blocked or unsupported door sides are deprioritized, and multiple units spread across available doors.
- Boarding now requires reaching within 1.5 blocks of a door while the train remains stopped with doors open.

# 1.0.0

- Adds invisible server-side proxies for nearby MTR 4.0.5 trains.
- Adds multi-car boarding for up to 128 Dominion-controlled units.
- Adds start/ATO resume, emergency brake, brake hold, forward-station selection, and sampled rail-path display.
- Preserves real-player driving priority and cleans up riders and chunk tickets when bindings expire.
- Supports Forge 47.x on Minecraft 1.20.1 with Dominion Sword 1.15.0 or newer.

This add-on is released under the MIT License. MTR and Dominion Sword are required separately and are not bundled.
