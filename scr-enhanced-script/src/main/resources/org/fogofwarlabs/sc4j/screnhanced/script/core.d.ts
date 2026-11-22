// SC:R Enhanced Script, script API version 1: the lower layer, the global core. It works on ids, raw values and plain records of the game:
// hit points, shields, energy and damage in 1/256, supply in halves, DAT values as the tables hold them. The upper layer (scr-enhanced.d.ts)
// is built on it, and scripts may call it as well. No function takes a callback: what happened since the previous frame is read as the
// records of the frame.
//
// Shared code is the module top level, onStart, onFrame, the event and decision hooks, the timers and the channel handlers. It runs
// identically on every player's game and may change the game state. Local code is onLocalFrame, onRender and the local input hooks. It runs
// only on this player's machine, for display and input. A function documented "Shared code only." throws a TypeError in local code, "Local
// code only." throws a TypeError in shared code, and "Only in onRender." throws a TypeError outside onRender; a function without such a note
// works in both. A function that changes the game state also throws a TypeError while the game is read-only, inside a decision hook.
//
// An integer argument must be an integral number inside the signed 32-bit range, else the function throws a TypeError. An id outside its
// range, a unit, sprite, image or bullet id that names nothing live ("... does not exist") and a value outside its range throw a RangeError.
// Ids are 0-based slot or table indices; -1 stands for none. Positions are map pixels unless a function names tiles (32 pixels).

// ---------------------------------------------------------------------------------------------------------------------------------------
// The records.
// ---------------------------------------------------------------------------------------------------------------------------------------

/**
 * An event of the frame ({@link CoreEvents.getFrameEvents}), read-only. Every record has the kind and the frame; the other properties are
 * those of its kind. A unit is its id with the generation of its slot in the property named after it plus Generation; unit, player, type
 * and order ids are -1 for none.
 * <p>
 * UnitCreated, UnitCompleted: unit, unitType, owner. UnitDied: also killer, killerPlayer, weapon. UnitDamaged: also source, sourcePlayer,
 * weapon, amount. OrderIssued: also order, target, targetX, targetY, buildType, queued. TargetAcquired: also target. UnitTrained: also
 * producer. ResearchCompleted: also tech. UpgradeCompleted: also upgrade, level. SpellCast: also tech, target, targetX, targetY.
 * OwnerChanged: also previousOwner. UnitLoaded, UnitUnloaded: also transport. BulletCreated, BulletHit: bullet and bulletGeneration
 * ({@link CoreBullet}), weapon, player, source, target, targetX, targetY, and missed for BulletHit. PlayerLeft: player, reason.
 * PlayerResult: player, result.
 */
interface CoreEventRecord {
    /** The event kind ({@link EventKind}). */
    readonly kind: number;
    /** The frame the event happened in. */
    readonly frame: number;
    /** The unit slot id. */
    readonly unit?: number;
    /** The generation of the unit's slot. */
    readonly unitGeneration?: number;
    /** The unit type id. */
    readonly unitType?: number;
    /** The owner's player id. */
    readonly owner?: number;
    /** The killing unit, -1 for none. */
    readonly killer?: number;
    /** The generation of the killer's slot. */
    readonly killerGeneration?: number;
    /** The killing player, -1 for none. */
    readonly killerPlayer?: number;
    /** The attacking unit, -1 for none. */
    readonly source?: number;
    /** The generation of the source's slot. */
    readonly sourceGeneration?: number;
    /** The attacking player, -1 for none. */
    readonly sourcePlayer?: number;
    /** The weapon type id, -1 for none. */
    readonly weapon?: number;
    /** The hit point damage in 1/256. */
    readonly amount?: number;
    /** The order id. */
    readonly order?: number;
    /** The target unit, -1 for none. */
    readonly target?: number;
    /** The generation of the target's slot. */
    readonly targetGeneration?: number;
    /** The target x in map pixels. */
    readonly targetX?: number;
    /** The target y in map pixels. */
    readonly targetY?: number;
    /** The unit type the order builds or trains, -1 for none. */
    readonly buildType?: number;
    /** Whether the order was queued. */
    readonly queued?: boolean;
    /** The unit that trained the unit. */
    readonly producer?: number;
    /** The generation of the producer's slot. */
    readonly producerGeneration?: number;
    /** The tech type id. */
    readonly tech?: number;
    /** The upgrade type id. */
    readonly upgrade?: number;
    /** The new upgrade level. */
    readonly level?: number;
    /** The player who owned the unit before. */
    readonly previousOwner?: number;
    /** The transport or Bunker. */
    readonly transport?: number;
    /** The generation of the transport's slot. */
    readonly transportGeneration?: number;
    /** The player id. */
    readonly player?: number;
    /** The bullet slot id. */
    readonly bullet?: number;
    /** The generation of the bullet's slot. */
    readonly bulletGeneration?: number;
    /** Whether the bullet missed. */
    readonly missed?: boolean;
    /** The signed departure reason of the player's Leave Game command. */
    readonly reason?: number;
    /** The raw result: 1 dropped, 2 defeat, 3 victory, 4 secured victory, 5 draw. */
    readonly result?: number;
}

/**
 * A decision ({@link DecisionKind}) that the decision hooks receive through their decision objects ({@link DamageDecision.getRecord}). The
 * changes of a hook that returns go to the next hook and to the game.
 * <p>
 * Damage: unit, unitGeneration, unitType, owner, source, sourceGeneration, sourcePlayer, weapon; a hook may change amount and cancelled.
 * Order: unit, unitGeneration, unitType, owner, buildType, queued; a hook may change order, target, targetGeneration, targetX, targetY and
 * rejected. TargetAcquired: unit, unitGeneration, unitType, owner; a hook may change target and targetGeneration.
 */
interface CoreDecisionRecord {
    /** The decision kind ({@link DecisionKind}). */
    readonly kind: number;
    /** The unit slot id. */
    readonly unit: number;
    /** The generation of the unit's slot. */
    readonly unitGeneration: number;
    /** The unit type id. */
    readonly unitType: number;
    /** The owner's player id. */
    readonly owner: number;
    /** The attacking unit, -1 for none. */
    readonly source?: number;
    /** The generation of the source's slot. */
    readonly sourceGeneration?: number;
    /** The attacking player, -1 for none. */
    readonly sourcePlayer?: number;
    /** The weapon type id. */
    readonly weapon?: number;
    /** The unit type the order builds or trains, -1 for none. */
    readonly buildType?: number;
    /** Whether the order is queued. */
    readonly queued?: boolean;
    /** The damage in 1/256. */
    amount?: number;
    /** Whether the damage is cancelled. */
    cancelled?: boolean;
    /** The order id. */
    order?: number;
    /** The target unit, -1 for none. */
    target?: number;
    /** The generation of the target's slot. */
    targetGeneration?: number;
    /** The target x in map pixels. */
    targetX?: number;
    /** The target y in map pixels. */
    targetY?: number;
    /** Whether the order is rejected. */
    rejected?: boolean;
}

/**
 * A local input ({@link InputKind}), read-only once the input went to the game.
 * <p>
 * A key has the key code, a mouse button the button; both have the modifier mask and, when positioned, the map position x, y. A chat line has
 * the text. A command card button press (ButtonPressed) has the shown set, the button's index in the set (button), its position, its
 * condition and action ids ({@link CoreButtons.getConditionName}, {@link CoreButtons.getActionName}) with their values, queued and
 * hotkey. The target of a ScriptTarget button (ButtonTargeted) also has the map position x, y and the target unit with its generation.
 */
interface CoreInputRecord {
    /** The input kind ({@link InputKind}). */
    readonly kind: number;
    /** The key code ({@link Key}). */
    readonly key?: number;
    /** The mouse button ({@link MouseButton}), or the pressed button's index in its set, -1 for a button of no table entry. */
    readonly button?: number;
    /** The modifier mask ({@link Modifier}). */
    readonly modifiers?: number;
    /** Whether the input has a map position. */
    readonly positioned?: boolean;
    /** The map x in pixels. */
    readonly x?: number;
    /** The map y in pixels. */
    readonly y?: number;
    /** The chat text. */
    readonly text?: string;
    /** The shown button set, 0 to 249. */
    readonly set?: number;
    /** The button position, 1 to 9. */
    readonly position?: number;
    /** The condition id. */
    readonly condition?: number;
    /** The condition value. */
    readonly conditionValue?: number;
    /** The action id. */
    readonly action?: number;
    /** The action value. */
    readonly actionValue?: number;
    /** Whether Shift was held. */
    readonly queued?: boolean;
    /** Whether a hotkey pressed the button. */
    readonly hotkey?: boolean;
    /** The target unit, -1 for the ground. */
    readonly target?: number;
    /** The generation of the target's slot. */
    readonly targetGeneration?: number;
    /** Set by a hook to keep the input from the game: a consumed press runs no action, a consumed target keeps the targeting mode. */
    consumed: boolean;
}

/**
 * A script command that ran ({@link CoreCommand.getExecuted}).
 */
interface CoreCommandRecord {
    /** The sending player. */
    readonly player: number;
    /** The sent value. */
    readonly value: any;
    /** The live units the player had selected when the command ran. */
    readonly selection: number[];
    /** The generations of the selected units' slots, at the same indices. */
    readonly selectionGenerations: number[];
}

/**
 * An order waiting in a unit's queue ({@link CoreUnit.getOrderQueue}).
 */
interface CoreOrderRecord {
    /** The order id. */
    readonly order: number;
    /** The target unit, -1 for none or a unit that is gone. */
    readonly target: number;
    /** The generation of the target's slot. */
    readonly targetGeneration: number;
    /** The target x in map pixels. */
    readonly x: number;
    /** The target y in map pixels. */
    readonly y: number;
    /** The unit type the order builds or trains, -1 for none. */
    readonly unitType: number;
}

// ---------------------------------------------------------------------------------------------------------------------------------------
// The DAT tables.
// ---------------------------------------------------------------------------------------------------------------------------------------

// BEGIN GENERATED CORE DAT: the DAT columns of the game data tables.

/**
 * The units.dat values by unit type ({@code core.dat.units}).
 * <p>
 * A get function returns the raw value of an entry; outside the entries of its column, the value the game reads there. A
 * set function stores a value as an EUD write does and works in shared code only. It throws a RangeError for an entry outside
 * the entries of its column, a value the column cannot hold or a value at or above the column's limit.
 */
interface CoreDatUnits {
    /** Graphics (below 209) */
    getFlingyType(unitType: number): number;

    setFlingyType(unitType: number, value: number): void;

    /** Subunit1 */
    getFirstSubunit(unitType: number): number;

    setFirstSubunit(unitType: number, value: number): void;

    /** Subunit2 */
    getSecondSubunit(unitType: number): number;

    setSecondSubunit(unitType: number, value: number): void;

    /** Infestation (unit types 106 to 201) */
    getInfestedType(unitType: number): number;

    setInfestedType(unitType: number, value: number): void;

    /** Construction Animation */
    getConstructionImage(unitType: number): number;

    setConstructionImage(unitType: number, value: number): void;

    /** Unit Direction */
    getInitialDirection(unitType: number): number;

    setInitialDirection(unitType: number, value: number): void;

    /** Shield Enable */
    getShieldEnabled(unitType: number): number;

    setShieldEnabled(unitType: number, value: number): void;

    /** Shield Amount */
    getShieldAmount(unitType: number): number;

    setShieldAmount(unitType: number, value: number): void;

    /** Hit Points */
    getHitPoints(unitType: number): number;

    setHitPoints(unitType: number, value: number): void;

    /** Elevation Level */
    getElevationLevel(unitType: number): number;

    setElevationLevel(unitType: number, value: number): void;

    /** Unknown */
    getUnknownByte(unitType: number): number;

    setUnknownByte(unitType: number, value: number): void;

    /** Sublabel */
    getSublabel(unitType: number): number;

    setSublabel(unitType: number, value: number): void;

    /** Comp AI Idle */
    getComputerIdleOrder(unitType: number): number;

    setComputerIdleOrder(unitType: number, value: number): void;

    /** Human AI Idle */
    getHumanIdleOrder(unitType: number): number;

    setHumanIdleOrder(unitType: number, value: number): void;

    /** Return to Idle */
    getReturnToIdleOrder(unitType: number): number;

    setReturnToIdleOrder(unitType: number, value: number): void;

    /** Attack Unit */
    getAttackUnitOrder(unitType: number): number;

    setAttackUnitOrder(unitType: number, value: number): void;

    /** Attack Move */
    getAttackMoveOrder(unitType: number): number;

    setAttackMoveOrder(unitType: number, value: number): void;

    /** Ground Weapon */
    getGroundWeapon(unitType: number): number;

    setGroundWeapon(unitType: number, value: number): void;

    /** Max Ground Hits */
    getMaximumGroundHits(unitType: number): number;

    setMaximumGroundHits(unitType: number, value: number): void;

    /** Air Weapon */
    getAirWeapon(unitType: number): number;

    setAirWeapon(unitType: number, value: number): void;

    /** Max Air Hits */
    getMaximumAirHits(unitType: number): number;

    setMaximumAirHits(unitType: number, value: number): void;

    /** AI Internal */
    getComputerInternalFlags(unitType: number): number;

    setComputerInternalFlags(unitType: number, value: number): void;

    /** Special Ability Flags */
    getFlags(unitType: number): number;

    setFlags(unitType: number, value: number): void;

    /** Target Acquisition Range */
    getTargetAcquisitionRange(unitType: number): number;

    setTargetAcquisitionRange(unitType: number, value: number): void;

    /** Sight Range */
    getSightRange(unitType: number): number;

    setSightRange(unitType: number, value: number): void;

    /** Armor Upgrade */
    getArmorUpgrade(unitType: number): number;

    setArmorUpgrade(unitType: number, value: number): void;

    /** Unit Size */
    getSizeClass(unitType: number): number;

    setSizeClass(unitType: number, value: number): void;

    /** Armor */
    getArmor(unitType: number): number;

    setArmor(unitType: number, value: number): void;

    /** Right-click Action */
    getRightClickAction(unitType: number): number;

    setRightClickAction(unitType: number, value: number): void;

    /** Ready Sound (unit types 0 to 105) */
    getReadySound(unitType: number): number;

    setReadySound(unitType: number, value: number): void;

    /** What Sound Start */
    getWhatSoundStart(unitType: number): number;

    setWhatSoundStart(unitType: number, value: number): void;

    /** What Sound End */
    getWhatSoundEnd(unitType: number): number;

    setWhatSoundEnd(unitType: number, value: number): void;

    /** Piss Sound Start (unit types 0 to 105) */
    getAnnoyedSoundStart(unitType: number): number;

    setAnnoyedSoundStart(unitType: number, value: number): void;

    /** Piss Sound End (unit types 0 to 105) */
    getAnnoyedSoundEnd(unitType: number): number;

    setAnnoyedSoundEnd(unitType: number, value: number): void;

    /** Yes Sound Start (unit types 0 to 105) */
    getYesSoundStart(unitType: number): number;

    setYesSoundStart(unitType: number, value: number): void;

    /** Yes Sound End (unit types 0 to 105) */
    getYesSoundEnd(unitType: number): number;

    setYesSoundEnd(unitType: number, value: number): void;

    /** Placement Box Width */
    getPlacementWidth(unitType: number): number;

    setPlacementWidth(unitType: number, value: number): void;

    /** Placement Box Height */
    getPlacementHeight(unitType: number): number;

    setPlacementHeight(unitType: number, value: number): void;

    /** Addon Horizontal (unit types 106 to 201) */
    getAddonHorizontalOffset(unitType: number): number;

    setAddonHorizontalOffset(unitType: number, value: number): void;

    /** Addon Vertical (unit types 106 to 201) */
    getAddonVerticalOffset(unitType: number): number;

    setAddonVerticalOffset(unitType: number, value: number): void;

    /** Unit Size Left */
    getDimensionLeft(unitType: number): number;

    setDimensionLeft(unitType: number, value: number): void;

    /** Unit Size Up */
    getDimensionUp(unitType: number): number;

    setDimensionUp(unitType: number, value: number): void;

    /** Unit Size Right */
    getDimensionRight(unitType: number): number;

    setDimensionRight(unitType: number, value: number): void;

    /** Unit Size Down */
    getDimensionDown(unitType: number): number;

    setDimensionDown(unitType: number, value: number): void;

    /** Portrait */
    getPortrait(unitType: number): number;

    setPortrait(unitType: number, value: number): void;

    /** Mineral Cost */
    getMineralCost(unitType: number): number;

    setMineralCost(unitType: number, value: number): void;

    /** Vespene Cost */
    getGasCost(unitType: number): number;

    setGasCost(unitType: number, value: number): void;

    /** Build Time */
    getBuildTime(unitType: number): number;

    setBuildTime(unitType: number, value: number): void;

    /** Requirement Index */
    getRequirementIndex(unitType: number): number;

    setRequirementIndex(unitType: number, value: number): void;

    /** StarEdit Group Flags */
    getGroupFlags(unitType: number): number;

    setGroupFlags(unitType: number, value: number): void;

    /** Supply Provided */
    getSupplyProvided(unitType: number): number;

    setSupplyProvided(unitType: number, value: number): void;

    /** Supply Required */
    getSupplyRequired(unitType: number): number;

    setSupplyRequired(unitType: number, value: number): void;

    /** Space Required */
    getSpaceRequired(unitType: number): number;

    setSpaceRequired(unitType: number, value: number): void;

    /** Space Provided */
    getSpaceProvided(unitType: number): number;

    setSpaceProvided(unitType: number, value: number): void;

    /** Build Score */
    getBuildScore(unitType: number): number;

    setBuildScore(unitType: number, value: number): void;

    /** Destroy Score */
    getDestroyScore(unitType: number): number;

    setDestroyScore(unitType: number, value: number): void;

    /** Unit Map String */
    getMapStringIndex(unitType: number): number;

    setMapStringIndex(unitType: number, value: number): void;

    /** Broodwar Unit Flag */
    getBroodWarFlag(unitType: number): number;

    setBroodWarFlag(unitType: number, value: number): void;

    /** StarEdit Availability Flags */
    getAvailabilityFlags(unitType: number): number;

    setAvailabilityFlags(unitType: number, value: number): void;
}

/**
 * The weapons.dat values by weapon type ({@code core.dat.weapons}).
 * <p>
 * A get function returns the raw value of an entry; outside the entries of its column, the value the game reads there. A
 * set function stores a value as an EUD write does and works in shared code only. It throws a RangeError for an entry outside
 * the entries of its column, a value the column cannot hold or a value at or above the column's limit.
 */
interface CoreDatWeapons {
    /** Label */
    getLabel(weaponType: number): number;

    setLabel(weaponType: number, value: number): void;

    /** Sprite */
    getFlingyType(weaponType: number): number;

    setFlingyType(weaponType: number, value: number): void;

    /** Special Attack */
    getSpecialAttack(weaponType: number): number;

    setSpecialAttack(weaponType: number, value: number): void;

    /** Target Flags */
    getTargetFlags(weaponType: number): number;

    setTargetFlags(weaponType: number, value: number): void;

    /** Minimum Range */
    getMinimumRange(weaponType: number): number;

    setMinimumRange(weaponType: number, value: number): void;

    /** Maximum Range */
    getMaximumRange(weaponType: number): number;

    setMaximumRange(weaponType: number, value: number): void;

    /** Upgrade Group */
    getDamageUpgrade(weaponType: number): number;

    setDamageUpgrade(weaponType: number, value: number): void;

    /** Damage Type */
    getDamageType(weaponType: number): number;

    setDamageType(weaponType: number, value: number): void;

    /** Weapon Behavior */
    getBehavior(weaponType: number): number;

    setBehavior(weaponType: number, value: number): void;

    /** Remove After */
    getLifetime(weaponType: number): number;

    setLifetime(weaponType: number, value: number): void;

    /** Explosion Type */
    getExplosionType(weaponType: number): number;

    setExplosionType(weaponType: number, value: number): void;

    /** Inner Splash Radius */
    getInnerSplashRadius(weaponType: number): number;

    setInnerSplashRadius(weaponType: number, value: number): void;

    /** Medium Splash Radius */
    getMediumSplashRadius(weaponType: number): number;

    setMediumSplashRadius(weaponType: number, value: number): void;

    /** Outer Splash Radius */
    getOuterSplashRadius(weaponType: number): number;

    setOuterSplashRadius(weaponType: number, value: number): void;

    /** Damage Amount */
    getDamageAmount(weaponType: number): number;

    setDamageAmount(weaponType: number, value: number): void;

    /** Damage Bonus */
    getDamageBonus(weaponType: number): number;

    setDamageBonus(weaponType: number, value: number): void;

    /** Weapon Cooldown */
    getCooldown(weaponType: number): number;

    setCooldown(weaponType: number, value: number): void;

    /** Damage Factor */
    getDamageFactor(weaponType: number): number;

    setDamageFactor(weaponType: number, value: number): void;

    /** Attack Angle */
    getAttackAngle(weaponType: number): number;

    setAttackAngle(weaponType: number, value: number): void;

    /** Launch Spin */
    getLaunchSpin(weaponType: number): number;

    setLaunchSpin(weaponType: number, value: number): void;

    /** Forward Offset */
    getForwardOffset(weaponType: number): number;

    setForwardOffset(weaponType: number, value: number): void;

    /** Upward Offset */
    getUpwardOffset(weaponType: number): number;

    setUpwardOffset(weaponType: number, value: number): void;

    /** Target Error Message */
    getTargetErrorMessage(weaponType: number): number;

    setTargetErrorMessage(weaponType: number, value: number): void;

    /** Icon */
    getIcon(weaponType: number): number;

    setIcon(weaponType: number, value: number): void;
}

/**
 * The flingy.dat values by flingy type ({@code core.dat.flingy}).
 * <p>
 * A get function returns the raw value of an entry; outside the entries of its column, the value the game reads there. A
 * set function stores a value as an EUD write does and works in shared code only. It throws a RangeError for an entry outside
 * the entries of its column, a value the column cannot hold or a value at or above the column's limit.
 */
interface CoreDatFlingy {
    /** Sprite */
    getSprite(flingyType: number): number;

    setSprite(flingyType: number, value: number): void;

    /** Top Speed */
    getTopSpeed(flingyType: number): number;

    setTopSpeed(flingyType: number, value: number): void;

    /** Acceleration */
    getAcceleration(flingyType: number): number;

    setAcceleration(flingyType: number, value: number): void;

    /** Halt Distance */
    getHaltDistance(flingyType: number): number;

    setHaltDistance(flingyType: number, value: number): void;

    /** Turn Rate */
    getTurnRate(flingyType: number): number;

    setTurnRate(flingyType: number, value: number): void;

    /** Unused */
    getUnused(flingyType: number): number;

    setUnused(flingyType: number, value: number): void;

    /** Movement Control */
    getMovementControl(flingyType: number): number;

    setMovementControl(flingyType: number, value: number): void;
}

/**
 * The sprites.dat values by sprite type ({@code core.dat.sprites}).
 * <p>
 * A get function returns the raw value of an entry; outside the entries of its column, the value the game reads there. A
 * set function stores a value as an EUD write does and works in shared code only. It throws a RangeError for an entry outside
 * the entries of its column, a value the column cannot hold or a value at or above the column's limit.
 */
interface CoreDatSprites {
    /** Image (below 999) */
    getImage(spriteType: number): number;

    setImage(spriteType: number, value: number): void;

    /** Health Bar (sprite types 130 to 516) */
    getHealthBarWidth(spriteType: number): number;

    setHealthBarWidth(spriteType: number, value: number): void;

    /** Unused */
    getUnused(spriteType: number): number;

    setUnused(spriteType: number, value: number): void;

    /** Is Visible */
    getVisible(spriteType: number): number;

    setVisible(spriteType: number, value: number): void;

    /** Selection Circle (sprite types 130 to 516, below 10) */
    getSelectionCircle(spriteType: number): number;

    setSelectionCircle(spriteType: number, value: number): void;

    /** Y Position (sprite types 130 to 516) */
    getSelectionCircleOffset(spriteType: number): number;

    setSelectionCircleOffset(spriteType: number, value: number): void;
}

/**
 * The images.dat values by image type ({@code core.dat.images}).
 * <p>
 * A get function returns the raw value of an entry; outside the entries of its column, the value the game reads there. A
 * set function stores a value as an EUD write does and works in shared code only. It throws a RangeError for an entry outside
 * the entries of its column, a value the column cannot hold or a value at or above the column's limit.
 */
interface CoreDatImages {
    /** GRP File (read only) */
    getGraphicsFile(imageType: number): number;

    /** Graphic Turns */
    getGraphicTurns(imageType: number): number;

    setGraphicTurns(imageType: number, value: number): void;

    /** Clickable */
    getClickable(imageType: number): number;

    setClickable(imageType: number, value: number): void;

    /** Use Full Iscript */
    getFullIscript(imageType: number): number;

    setFullIscript(imageType: number, value: number): void;

    /** Draw If Cloaked */
    getDrawIfCloaked(imageType: number): number;

    setDrawIfCloaked(imageType: number, value: number): void;

    /** Draw Function (below 18) */
    getDrawFunction(imageType: number): number;

    setDrawFunction(imageType: number, value: number): void;

    /** Color Shift */
    getColorShift(imageType: number): number;

    setColorShift(imageType: number, value: number): void;

    /** Iscript (below 411) */
    getIscript(imageType: number): number;

    setIscript(imageType: number, value: number): void;

    /** Shield Overlay (read only) */
    getShieldOverlay(imageType: number): number;

    /** Attack Overlay (read only) */
    getAttackOverlay(imageType: number): number;

    /** Damage Overlay (read only) */
    getDamageOverlay(imageType: number): number;

    /** Special Overlay (read only) */
    getSpecialOverlay(imageType: number): number;

    /** Landing Dust Overlay (read only) */
    getLandingDustOverlay(imageType: number): number;

    /** Liftoff Dust Overlay (read only) */
    getLiftoffDustOverlay(imageType: number): number;
}

/**
 * The upgrades.dat values by upgrade type ({@code core.dat.upgrades}).
 * <p>
 * A get function returns the raw value of an entry; outside the entries of its column, the value the game reads there. A
 * set function stores a value as an EUD write does and works in shared code only. It throws a RangeError for an entry outside
 * the entries of its column, a value the column cannot hold or a value at or above the column's limit.
 */
interface CoreDatUpgrades {
    /** Ore Cost */
    getMineralCost(upgradeType: number): number;

    setMineralCost(upgradeType: number, value: number): void;

    /** Ore Cost Factor */
    getMineralCostFactor(upgradeType: number): number;

    setMineralCostFactor(upgradeType: number, value: number): void;

    /** Gas Cost */
    getGasCost(upgradeType: number): number;

    setGasCost(upgradeType: number, value: number): void;

    /** Gas Cost Factor */
    getGasCostFactor(upgradeType: number): number;

    setGasCostFactor(upgradeType: number, value: number): void;

    /** Time Cost */
    getResearchTime(upgradeType: number): number;

    setResearchTime(upgradeType: number, value: number): void;

    /** Time Cost Factor */
    getResearchTimeFactor(upgradeType: number): number;

    setResearchTimeFactor(upgradeType: number, value: number): void;

    /** DatReqs Index */
    getRequirementIndex(upgradeType: number): number;

    setRequirementIndex(upgradeType: number, value: number): void;

    /** Icon */
    getIcon(upgradeType: number): number;

    setIcon(upgradeType: number, value: number): void;

    /** Label */
    getLabel(upgradeType: number): number;

    setLabel(upgradeType: number, value: number): void;

    /** Race */
    getRace(upgradeType: number): number;

    setRace(upgradeType: number, value: number): void;

    /** Max Repeats */
    getMaximumLevel(upgradeType: number): number;

    setMaximumLevel(upgradeType: number, value: number): void;

    /** BroodWar */
    getBroodWarFlag(upgradeType: number): number;

    setBroodWarFlag(upgradeType: number, value: number): void;
}

/**
 * The techdata.dat values by tech type ({@code core.dat.techdata}).
 * <p>
 * A get function returns the raw value of an entry; outside the entries of its column, the value the game reads there. A
 * set function stores a value as an EUD write does and works in shared code only. It throws a RangeError for an entry outside
 * the entries of its column, a value the column cannot hold or a value at or above the column's limit.
 */
interface CoreDatTechdata {
    /** Ore Cost */
    getMineralCost(techType: number): number;

    setMineralCost(techType: number, value: number): void;

    /** Gas Cost */
    getGasCost(techType: number): number;

    setGasCost(techType: number, value: number): void;

    /** Time Cost */
    getResearchTime(techType: number): number;

    setResearchTime(techType: number, value: number): void;

    /** Energy */
    getEnergyCost(techType: number): number;

    setEnergyCost(techType: number, value: number): void;

    /** DatReqs Index */
    getResearchRequirementIndex(techType: number): number;

    setResearchRequirementIndex(techType: number, value: number): void;

    /** UseReqs Index */
    getUseRequirementIndex(techType: number): number;

    setUseRequirementIndex(techType: number, value: number): void;

    /** Icon */
    getIcon(techType: number): number;

    setIcon(techType: number, value: number): void;

    /** Label */
    getLabel(techType: number): number;

    setLabel(techType: number, value: number): void;

    /** Race */
    getRace(techType: number): number;

    setRace(techType: number, value: number): void;

    /** Researched */
    getResearchedFlag(techType: number): number;

    setResearchedFlag(techType: number, value: number): void;

    /** BroodWar */
    getBroodWarFlag(techType: number): number;

    setBroodWarFlag(techType: number, value: number): void;
}

/**
 * The orders.dat values by order ({@code core.dat.orders}).
 * <p>
 * A get function returns the raw value of an entry; outside the entries of its column, the value the game reads there. A
 * set function stores a value as an EUD write does and works in shared code only. It throws a RangeError for an entry outside
 * the entries of its column, a value the column cannot hold or a value at or above the column's limit.
 */
interface CoreDatOrders {
    /** Label */
    getLabel(order: number): number;

    setLabel(order: number, value: number): void;

    /** Use Weapon Targeting */
    getUseWeaponTargeting(order: number): number;

    setUseWeaponTargeting(order: number, value: number): void;

    /** Unused1 */
    getUnused1(order: number): number;

    setUnused1(order: number, value: number): void;

    /** Unused2 */
    getUnused2(order: number): number;

    setUnused2(order: number, value: number): void;

    /** Subunit Inheritance */
    getSubunitInheritance(order: number): number;

    setSubunitInheritance(order: number, value: number): void;

    /** Unused3 */
    getUnused3(order: number): number;

    setUnused3(order: number, value: number): void;

    /** Can Be Interrupted */
    getCanBeInterrupted(order: number): number;

    setCanBeInterrupted(order: number, value: number): void;

    /** Can Be Lifted */
    getCanBeLifted(order: number): number;

    setCanBeLifted(order: number, value: number): void;

    /** Can Be Queued */
    getCanBeQueued(order: number): number;

    setCanBeQueued(order: number, value: number): void;

    /** Disabling Keeps Target */
    getDisablingKeepsTarget(order: number): number;

    setDisablingKeepsTarget(order: number, value: number): void;

    /** Can Be Obstructed */
    getCanBeObstructed(order: number): number;

    setCanBeObstructed(order: number, value: number): void;

    /** Fleeable */
    getFleeable(order: number): number;

    setFleeable(order: number, value: number): void;

    /** Unused4 */
    getUnused4(order: number): number;

    setUnused4(order: number, value: number): void;

    /** Weapon */
    getWeapon(order: number): number;

    setWeapon(order: number, value: number): void;

    /** Energy */
    getTechnology(order: number): number;

    setTechnology(order: number, value: number): void;

    /** Animation */
    getAnimation(order: number): number;

    setAnimation(order: number, value: number): void;

    /** Highlight */
    getHighlight(order: number): number;

    setHighlight(order: number, value: number): void;

    /** DatReqs Index */
    getRequirementIndex(order: number): number;

    setRequirementIndex(order: number, value: number): void;

    /** Obscured */
    getObscuredOrder(order: number): number;

    setObscuredOrder(order: number, value: number): void;
}

/**
 * The DAT tables of the current game ({@code core.dat}).
 */
interface CoreDat {
    /** The units.dat values. */
    readonly units: CoreDatUnits;
    /** The weapons.dat values. */
    readonly weapons: CoreDatWeapons;
    /** The flingy.dat values. */
    readonly flingy: CoreDatFlingy;
    /** The sprites.dat values. */
    readonly sprites: CoreDatSprites;
    /** The images.dat values. */
    readonly images: CoreDatImages;
    /** The upgrades.dat values. */
    readonly upgrades: CoreDatUpgrades;
    /** The techdata.dat values. */
    readonly techdata: CoreDatTechdata;
    /** The orders.dat values. */
    readonly orders: CoreDatOrders;
}

// END GENERATED CORE DAT

// ---------------------------------------------------------------------------------------------------------------------------------------
// The namespaces.
// ---------------------------------------------------------------------------------------------------------------------------------------

/**
 * The game state, the trigger state and the game settings ({@code core.game}).
 */
interface CoreGame {
    /**
     * @returns the number of the current frame
     */
    getFrame(): number;

    /**
     * @returns the map width in tiles
     */
    getMapTileWidth(): number;

    /**
     * @returns the map height in tiles
     */
    getMapTileHeight(): number;

    /**
     * @returns the tileset id of the map
     */
    getTileset(): number;

    /**
     * @returns the milliseconds of a frame at the fastest speed (42)
     */
    getFrameDuration(): number;

    /**
     * Shows a text to every player as Display Text Message does with forced display. Shared code only; works while the game is read-only.
     */
    print(text: string): void;

    /**
     * @returns {@code true} while the game is read-only (inside a decision hook), {@code false} otherwise
     */
    isReadOnly(): boolean;

    /**
     * @param index the switch, 0 to 255
     * @returns {@code true} if the switch is set
     */
    isSwitchSet(index: number): boolean;

    /**
     * Sets or clears a switch as Set Switch does. Shared code only.
     *
     * @param index the switch, 0 to 255
     */
    setSwitch(index: number, set: boolean): void;

    /**
     * Toggles a switch as Set Switch does. Shared code only.
     *
     * @param index the switch, 0 to 255
     */
    toggleSwitch(index: number): void;

    /**
     * @returns the countdown timer in game seconds (16 frames a second)
     */
    getCountdownTimer(): number;

    /**
     * Sets the countdown timer as Set Countdown Timer does, kept from 0 to 359999 seconds. Shared code only.
     *
     * @param seconds the game seconds
     */
    setCountdownTimer(seconds: number): void;

    /**
     * Adds game seconds to the countdown timer as Set Countdown Timer does, kept from 0 to 359999. Shared code only.
     */
    addCountdownTimer(seconds: number): void;

    /**
     * Subtracts game seconds from the countdown timer as Set Countdown Timer does, kept from 0 to 359999. Shared code only.
     */
    subtractCountdownTimer(seconds: number): void;

    /**
     * @returns {@code true} if the countdown timer is paused
     */
    isCountdownTimerPaused(): boolean;

    /**
     * Pauses or resumes the countdown timer as Pause Timer and Unpause Timer do. Shared code only.
     */
    setCountdownTimerPaused(paused: boolean): void;

    /**
     * @returns the elapsed game seconds that the Elapsed Time condition compares (16 frames a second)
     */
    getElapsedTime(): number;

    /**
     * @returns {@code true} if the game is paused, by Pause Game or by the pause of the game menu
     */
    isPaused(): boolean;

    /**
     * Runs Pause Game without a trigger record at this frame's trigger step. Does nothing in a multiplayer session or while Pause Game already
     * paused the game. A game that Pause Game paused runs no frame, but the trigger step and the script's frame entry keep running, with the
     * frame number unchanged, until Unpause Game. Shared code only.
     */
    pause(): void;

    /**
     * Runs Unpause Game without a trigger record at this frame's trigger step. Does nothing in a multiplayer session or while Pause Game did
     * not pause the game. Shared code only.
     */
    resume(): void;

    /**
     * @returns the game speed, 0 slowest to 6 fastest
     */
    getSpeed(): number;

    /**
     * @param speed the game speed, 0 to 6
     * @returns the milliseconds of a frame at the speed in the frame duration table
     */
    getSpeedDuration(speed: number): number;

    /**
     * Stores an entry of the frame duration table as an EUD write does. The frame duration takes it at once unless the game name overrides the
     * speed; the turn timeouts of a network game keep the durations of their last fill. Shared code only.
     *
     * @param speed        the game speed, 0 to 6
     * @param milliseconds the frame duration, 0 to 2^32 - 1; a negative value throws a RangeError
     */
    setSpeedDuration(speed: number, milliseconds: number): void;

    /**
     * @returns {@code true} if the game is a multiplayer session
     */
    isMultiplayer(): boolean;

    /**
     * @returns the game type WORD of the game header ({@link GameMode})
     */
    getGameType(): number;

    /**
     * @returns the game subtype WORD of the game header
     */
    getGameSubtype(): number;

    /**
     * @returns the victory condition of the game options
     */
    getVictoryCondition(): number;

    /**
     * @returns the starting resources setting of the game options
     */
    getResourceType(): number;

    /**
     * @returns the starting minerals of the game options
     */
    getStartingMinerals(): number;

    /**
     * @returns the starting units setting of the game options
     */
    getStartingUnits(): number;

    /**
     * @returns the tournament mode setting of the game options
     */
    getTournamentMode(): number;

    /**
     * @returns {@code true} if the game options permit the cheat command
     */
    areCheatsEnabled(): boolean;

    /**
     * @returns the expansion byte, 0 for StarCraft, which the upgrade, technology, requirement, speed and computer player routines test
     */
    getExpansion(): number;

    /**
     * Stores the expansion byte. Shared code only.
     *
     * @param value the expansion byte, 0 to 255; another value throws a RangeError
     */
    setExpansion(value: number): void;

    /**
     * @returns the cheat flags ({@link Cheat}), one bit per cheat
     */
    getCheatFlags(): number;

    /**
     * Stores the cheat flags as an EUD write does, without the reveal update and the one-shot grants of the cheat routine; the game's reset of
     * the tiles may clear the reveal bits again ({@link Game.setCheatEnabled} keeps them). Shared code only.
     *
     * @param flags the cheat flags ({@link Cheat}), an unsigned 32-bit value
     */
    setCheatFlags(flags: number): void;

    /**
     * Runs the cheat routine with the flags. Does nothing when the game options forbid cheats or more than one human plays a network game.
     * Shared code only.
     *
     * @param flags the cheat flags ({@link Cheat}), an unsigned 32-bit value
     */
    applyCheats(flags: number): void;
}

/**
 * The players by slot id from 0 to 11; players 0 to 7 are the playable players ({@code core.player}). A function that reads a player's own
 * state throws a RangeError for a slot without a player.
 */
interface CorePlayer {
    /**
     * @returns the number of player slots, 12
     */
    getCount(): number;

    /**
     * @returns the player's name from the game header, an empty string for none
     */
    getName(player: number): string;

    /**
     * @returns the player's race id
     */
    getRace(player: number): number;

    /**
     * @returns the player's type id
     */
    getType(player: number): number;

    /**
     * @returns {@code true} if the player is a human player
     */
    isHuman(player: number): boolean;

    /**
     * @returns the player's minerals
     */
    getMinerals(player: number): number;

    /**
     * Sets the player's minerals as Set Resources does, which sets the gathered minerals too; players 8 to 11 keep theirs. Shared code only.
     */
    setMinerals(player: number, amount: number): void;

    /**
     * Adds to the player's minerals as Set Resources does, which adds to the gathered minerals too; players 8 to 11 keep theirs. Shared code
     * only.
     */
    addMinerals(player: number, amount: number): void;

    /**
     * Subtracts from the player's minerals as Set Resources does, leaving 0 when the amount is not below the minerals as unsigned numbers;
     * players 8 to 11 keep theirs. Shared code only.
     */
    subtractMinerals(player: number, amount: number): void;

    /**
     * @returns the player's gas
     */
    getGas(player: number): number;

    /**
     * Sets the player's gas as Set Resources does, which sets the gathered gas too; players 8 to 11 keep theirs. Shared code only.
     */
    setGas(player: number, amount: number): void;

    /**
     * Adds to the player's gas as Set Resources does, which adds to the gathered gas too; players 8 to 11 keep theirs. Shared code only.
     */
    addGas(player: number, amount: number): void;

    /**
     * Subtracts from the player's gas as Set Resources does, leaving 0 when the amount is not below the gas as unsigned numbers; players 8 to
     * 11 keep theirs. Shared code only.
     */
    subtractGas(player: number, amount: number): void;

    /**
     * Reads the player's resources as the Accumulate condition does.
     *
     * @param resourceType 0 minerals, 1 gas, 2 minerals and gas
     * @returns the amount
     */
    getResources(player: number, resourceType: number): number;

    /**
     * @returns the gathered minerals of the player's score
     */
    getGatheredMinerals(player: number): number;

    /**
     * Stores the gathered minerals of the player's score. Shared code only.
     */
    setGatheredMinerals(player: number, amount: number): void;

    /**
     * @returns the gathered gas of the player's score
     */
    getGatheredGas(player: number): number;

    /**
     * Stores the gathered gas of the player's score. Shared code only.
     */
    setGatheredGas(player: number, amount: number): void;

    /**
     * @param race 0 Zerg, 1 Terran, 2 Protoss
     * @returns the supply the player uses for the race, in half supply
     */
    getSupplyUsed(player: number, race: number): number;

    /**
     * @param race 0 Zerg, 1 Terran, 2 Protoss
     * @returns the supply the player's units provide for the race, in half supply
     */
    getSupplyProvided(player: number, race: number): number;

    /**
     * @param race 0 Zerg, 1 Terran, 2 Protoss
     * @returns the supply limit of the player for the race, in half supply
     */
    getSupplyMax(player: number, race: number): number;

    /**
     * @returns the player's alliance toward the other player: 0 enemy, 1 allied, 2 allied victory
     */
    getAlliance(player: number, other: number): number;

    /**
     * Sets the player's alliance toward the other player as Set Alliance Status does. Shared code only.
     *
     * @param player   the player, 0 to 7
     * @param other    the other player, 0 to 11
     * @param alliance 0 enemy, 1 allied, 2 allied victory
     */
    setAlliance(player: number, other: number, alliance: number): void;

    /**
     * Ends the game for the player as the Victory, Defeat and Draw actions do. Shared code only.
     *
     * @param player the player, 0 to 7
     * @param result 3 victory, 2 defeat or 5 draw; another value throws a RangeError
     * @returns {@code false} when a defeat or a draw meets a victory
     */
    endGame(player: number, result: number): boolean;

    /**
     * Reads the player's deaths as the Deaths condition does.
     *
     * @param unitType a unit type id or a unit group (229 any unit, 230 men, 231 buildings, 232 factories)
     */
    getDeaths(player: number, unitType: number): number;

    /**
     * Sets the player's deaths as Set Deaths does. Shared code only.
     *
     * @param unitType a unit type id or a unit group (229 to 232)
     */
    setDeaths(player: number, unitType: number, amount: number): void;

    /**
     * Adds to the player's deaths as Set Deaths does. Shared code only.
     *
     * @param unitType a unit type id or a unit group (229 to 232)
     */
    addDeaths(player: number, unitType: number, amount: number): void;

    /**
     * Subtracts from the player's deaths as Set Deaths does. Shared code only.
     *
     * @param unitType a unit type id or a unit group (229 to 232)
     */
    subtractDeaths(player: number, unitType: number, amount: number): void;

    /**
     * Reads the player's kills as the Kill condition does.
     *
     * @param unitType a unit type id or a unit group (229 to 232)
     */
    getKills(player: number, unitType: number): number;

    /**
     * Reads the player's score as the Score condition does.
     *
     * @param scoreType 0 total, 1 units, 2 buildings, 3 units and buildings, 4 kills, 5 razings, 6 kills and razings, 7 custom
     */
    getScore(player: number, scoreType: number): number;

    /**
     * Sets the player's score as Set Score does. Shared code only.
     *
     * @param player    the player, 0 to 7
     * @param scoreType the score type, 0 to 7
     */
    setScore(player: number, scoreType: number, amount: number): void;

    /**
     * Adds to the player's score as Set Score does. Shared code only.
     *
     * @param player    the player, 0 to 7
     * @param scoreType the score type, 0 to 7
     */
    addScore(player: number, scoreType: number, amount: number): void;

    /**
     * Subtracts from the player's score as Set Score does. Shared code only.
     *
     * @param player    the player, 0 to 7
     * @param scoreType the score type, 0 to 7
     */
    subtractScore(player: number, scoreType: number, amount: number): void;

    /**
     * Counts the player's units as the Command condition does.
     *
     * @param unitType  a unit type id or a unit group (229 to 232)
     * @param completed {@code true} to count completed units only
     */
    countUnits(player: number, unitType: number, completed: boolean): number;

    /**
     * Counts the player's units at a location as the Bring condition does.
     *
     * @param unitType  a unit type id or a unit group (229 to 232)
     * @param location  the location, 0 to 254
     * @param completed {@code true} to count completed units only
     */
    countUnitsAt(player: number, unitType: number, location: number, completed: boolean): number;

    /**
     * @returns the number of the player's opponents, as the Opponents condition counts them
     */
    countOpponents(player: number): number;

    /**
     * Kills units of the player as Kill Unit and Kill Unit At Location do. Shared code only.
     *
     * @param unitType a unit type id or a unit group (229 to 232)
     * @param location the location, -1 for everywhere
     * @param count    the most units, 0 for all; a negative count throws a RangeError
     */
    killUnits(player: number, unitType: number, location: number, count: number): void;

    /**
     * Removes units of the player as Remove Unit and Remove Unit At Location do. Shared code only.
     *
     * @param unitType a unit type id or a unit group (229 to 232)
     * @param location the location, -1 for everywhere
     * @param count    the most units, 0 for all; a negative count throws a RangeError
     */
    removeUnits(player: number, unitType: number, location: number, count: number): void;

    /**
     * Starts an AI script for the player as Run AI Script does. Shared code only.
     *
     * @param script the four-character script id, its first character in the low byte
     * @returns {@code false} if the player may not run the script
     */
    runAiScript(player: number, script: number): boolean;

    /**
     * Starts an AI script for the player at a location as Run AI Script At Location does. Shared code only.
     *
     * @param script   the four-character script id, its first character in the low byte
     * @param location the location, 0 to 254
     * @returns {@code false} if the player may not run the script
     */
    runAiScriptAt(player: number, script: number, location: number): boolean;

    /**
     * Sets the player's mission objectives as Set Mission Objectives does, with the text as a map string. Shared code only.
     *
     * @throws RangeError if the text holds a NUL character or no map string index is left
     */
    setMissionObjectives(player: number, text: string): void;

    /**
     * Shows a text to the player as Display Text Message does, when the player is the local player. Shared code only; works while the game is
     * read-only.
     *
     * @param forced {@code true} to always show the text, {@code false} to show it only when the subtitle option is on
     */
    print(player: number, text: string, forced: boolean): void;

    /**
     * Pings the minimap of the player as Minimap Ping does, when the player is the local player. Shared code only; works while the game is
     * read-only.
     *
     * @param x the map x in pixels
     * @param y the map y in pixels
     */
    pingMinimap(player: number, x: number, y: number): void;

    /**
     * Plays a sound to the player as Play WAV does, when the player is the local player. Shared code only; works while the game is read-only.
     *
     * @param path the path of a sound file in the map archive
     */
    playSound(player: number, path: string): void;

    /**
     * Shows the portrait of a unit type talking to the player as Talking Portrait does, when the player is the local player. Shared code only;
     * works while the game is read-only.
     *
     * @param unitType     the unit type id
     * @param milliseconds the duration; a negative value throws a RangeError
     */
    showTalkingPortrait(player: number, unitType: number, milliseconds: number): void;

    /**
     * Mutes or unmutes unit speech for the player as Mute Unit Speech and Unmute Unit Speech do, when the player is the local player. Shared
     * code only; works while the game is read-only.
     */
    setUnitSpeechMuted(player: number, muted: boolean): void;

    /**
     * Centers the local screen on a map position at once as Center View does, when the player is the local player; does nothing while the
     * local view is unknown. Shared code only; works while the game is read-only.
     *
     * @param x the map x in pixels
     * @param y the map y in pixels
     */
    centerView(player: number, x: number, y: number): void;

    /**
     * @returns the player's level of the upgrade type
     */
    getUpgradeLevel(player: number, upgradeType: number): number;

    /**
     * Sets the player's level of the upgrade type and refreshes the upgrades of the player's units, as a finished upgrade does. Shared code
     * only.
     *
     * @param level the level, 0 to 255; another value throws a RangeError
     */
    setUpgradeLevel(player: number, upgradeType: number, level: number): void;

    /**
     * @returns the player's maximum level of the upgrade type
     */
    getMaxUpgradeLevel(player: number, upgradeType: number): number;

    /**
     * Sets the player's maximum level of the upgrade type. Shared code only.
     *
     * @param level the level, 0 to 255; another value throws a RangeError
     */
    setMaxUpgradeLevel(player: number, upgradeType: number, level: number): void;

    /**
     * @returns {@code true} if the player is researching the upgrade type
     */
    isUpgradeInProgress(player: number, upgradeType: number): boolean;

    /**
     * @returns {@code true} if the player has researched the tech type
     */
    isTechResearched(player: number, techType: number): boolean;

    /**
     * Sets whether the player has researched the tech type. Shared code only.
     */
    setTechResearched(player: number, techType: number, researched: boolean): void;

    /**
     * @returns {@code true} if the player may research the tech type
     */
    isTechAvailable(player: number, techType: number): boolean;

    /**
     * Sets whether the player may research the tech type. Shared code only.
     */
    setTechAvailable(player: number, techType: number, available: boolean): void;

    /**
     * @returns {@code true} if the player is researching the tech type
     */
    isTechInProgress(player: number, techType: number): boolean;

    /**
     * @returns {@code true} if the map settings let the player produce the unit type; {@code true} in a game without map settings
     */
    isUnitAvailable(player: number, unitType: number): boolean;

    /**
     * Sets whether the map settings let the player produce the unit type. Shared code only.
     *
     * @throws TypeError if {@code available} is {@code false} in a game without map settings
     */
    setUnitAvailable(player: number, unitType: number, available: boolean): void;

    /**
     * @returns the ids of the live units of the player's selection, empty for players 8 to 11
     */
    getSelection(player: number): number[];

    /**
     * Replaces the player's selection as a selection command of the player does ({@link Player.setSelection}) and shows it as the local
     * selection when the player is the local player. Shared code only.
     *
     * @param player the player, 0 to 7
     * @param units  at most 12 ids of live units; more units or a unit that does not exist throws a RangeError
     * @returns the number of selected units
     */
    setSelection(player: number, units: number[]): number;

    /**
     * @returns the players that see what the player's units see, one bit per player, the player's own bit included
     */
    getSharedVision(player: number): number;

    /**
     * Sets the players that see what the player's units see, as the vision command does but without its permission test. Shared code only.
     *
     * @param mask one bit per player, the player's own bit included
     */
    setSharedVision(player: number, mask: number): void;

    /**
     * @param player the player, 0 to 7
     * @returns the network pauses the player has left
     */
    getPausesLeft(player: number): number;

    /**
     * Sets the network pauses the player has left. Shared code only.
     *
     * @param player the player, 0 to 7
     * @param pauses 0 to 255; another value throws a RangeError
     */
    setPausesLeft(player: number, pauses: number): void;

    /**
     * @returns the x of the player's start location in pixels, -1 for a player without one
     */
    getStartLocationX(player: number): number;

    /**
     * @returns the y of the player's start location in pixels, -1 for a player without one
     */
    getStartLocationY(player: number): number;

    /**
     * Moves the player's start location, which the computer players and the start screen read. Shared code only.
     *
     * @param x the map x in pixels, 0 to 65535; another value throws a RangeError
     * @param y the map y in pixels, 0 to 65535; another value throws a RangeError
     * @returns {@code false} for a player without a start location
     */
    setStartLocation(player: number, x: number, y: number): boolean;

    /**
     * @param index the remapped unit color, 0 to 7; color 0 is also the HD team color
     * @returns the palette index of the player's unit color row
     */
    getUnitColor(player: number, index: number): number;

    /**
     * Stores a palette index of the player's unit color row as an EUD write does; the next drawn frame shows it. A player shown in a custom
     * color of the game keeps that color as its HD team color and on the minimap. Changes no game state: shared code changes the color on
     * every player's game, local code on this machine only.
     *
     * @param index        the remapped unit color, 0 to 7
     * @param paletteIndex the palette index, 0 to 255
     */
    setUnitColor(player: number, index: number, paletteIndex: number): void;

    /**
     * @returns the palette index of the player's minimap dots
     */
    getMinimapColor(player: number): number;

    /**
     * Stores the palette index of the player's minimap dots. Changes no game state: shared code changes the color on every player's game, local
     * code on this machine only.
     *
     * @param paletteIndex the palette index, 0 to 255
     */
    setMinimapColor(player: number, paletteIndex: number): void;
}

/**
 * The units by slot id ({@code core.unit}): slots 1 to {@link getCapacity}. A slot holds a live unit while it is allocated, has a sprite and
 * does not run the Die order; every function but {@link getCapacity}, {@link getIds}, {@link exists} and {@link getGeneration} needs a live
 * unit. Hit points, shields and energy are raw values in 1/256; positions are map pixels.
 */
interface CoreUnit {
    /**
     * Returns the number of unit slots.
     */
    getCapacity(): number;

    /**
     * Returns the ids of the live units in slot order.
     */
    getIds(): number[];

    /**
     * Returns whether the slot holds a live unit.
     *
     * @returns {@code false} for a slot outside 1 to {@link getCapacity}, an empty slot or a dying unit
     */
    exists(unit: number): boolean;

    /**
     * Returns the generation of the slot, which tells apart the units that one slot holds in turn. Works on any slot, live or not.
     *
     * @throws RangeError if the slot is not from 1 to {@link getCapacity}
     */
    getGeneration(unit: number): number;

    /**
     * Returns the unit's type id.
     */
    getType(unit: number): number;

    /**
     * Returns the owner's player id, 0 to 11.
     */
    getOwner(unit: number): number;

    /**
     * Returns the x of the unit's position in pixels.
     */
    getX(unit: number): number;

    /**
     * Returns the y of the unit's position in pixels.
     */
    getY(unit: number): number;

    /**
     * Returns the hit points in 1/256.
     */
    getHp(unit: number): number;

    /**
     * Returns the shields in 1/256.
     */
    getShields(unit: number): number;

    /**
     * Returns the energy in 1/256.
     */
    getEnergy(unit: number): number;

    /**
     * Returns the maximum energy in 1/256 after the energy upgrades of the unit's owner, 0 for a unit without energy.
     */
    getMaxEnergy(unit: number): number;

    /**
     * Returns the unit's current order id.
     */
    getOrder(unit: number): number;

    /**
     * Returns the target unit of the current order, -1 for none or a target that is gone.
     */
    getTarget(unit: number): number;

    /**
     * Returns the x of the current order's target position in pixels.
     */
    getTargetX(unit: number): number;

    /**
     * Returns the y of the current order's target position in pixels.
     */
    getTargetY(unit: number): number;

    /**
     * Returns the unit's status flags DWORD.
     */
    getStatus(unit: number): number;

    /**
     * Returns the image type of the main image of the unit's sprite, -1 for none.
     */
    getImageType(unit: number): number;

    /**
     * Returns the slot of the unit's sprite ({@link CoreSprite}), -1 for none.
     */
    getSprite(unit: number): number;

    /**
     * Returns the resource amount of a resource container.
     */
    getResources(unit: number): number;

    /**
     * Returns the number of Interceptors or Scarabs the unit has, inside and outside the hangar.
     */
    getHangarCount(unit: number): number;

    /**
     * Returns whether the unit is completed.
     */
    isCompleted(unit: number): boolean;

    /**
     * Returns whether the unit is cloaked, by a spell or passively.
     */
    isCloaked(unit: number): boolean;

    /**
     * Returns whether the unit is burrowed.
     */
    isBurrowed(unit: number): boolean;

    /**
     * Returns whether the unit is invincible.
     */
    isInvincible(unit: number): boolean;

    /**
     * Returns whether the unit is disabled: a disabled doodad, or a unit under Lockdown, Stasis Field or Maelstrom.
     */
    isDisabled(unit: number): boolean;

    /**
     * Sets the hit points in 1/256 through the hit point routine, at least 1 and at most the maximum. Shared code only.
     *
     * @throws RangeError if the hit points are negative
     */
    setHp(unit: number, hp: number): void;

    /**
     * Sets the shields in 1/256, at most the maximum. Shared code only.
     *
     * @returns {@code false} for a unit type without shields
     * @throws RangeError if the shields are negative
     */
    setShields(unit: number, shields: number): boolean;

    /**
     * Sets the energy in 1/256, at most the maximum. Shared code only.
     *
     * @returns {@code false} for a unit without energy
     * @throws RangeError if the energy is negative
     */
    setEnergy(unit: number, energy: number): boolean;

    /**
     * Sets the hit points to a percentage of the maximum, as Modify Unit Hit Points does. Shared code only.
     *
     * @throws RangeError if the percentage is negative
     */
    setHpPercent(unit: number, percent: number): void;

    /**
     * Sets the shields to a percentage of the maximum, as Modify Unit Shield Points does. Shared code only.
     *
     * @returns {@code false} if the action does not take the unit
     * @throws RangeError if the percentage is negative
     */
    setShieldsPercent(unit: number, percent: number): boolean;

    /**
     * Sets the energy to a percentage of the maximum, as Modify Unit Energy does. Shared code only.
     *
     * @returns {@code false} if the action does not take the unit
     * @throws RangeError if the percentage is negative
     */
    setEnergyPercent(unit: number, percent: number): boolean;

    /**
     * Makes the unit invincible or not, as Set Invincibility does. Shared code only.
     */
    setInvincible(unit: number, invincible: boolean): void;

    /**
     * Enables a doodad, as Set Doodad State does. Shared code only.
     */
    enableDoodad(unit: number): void;

    /**
     * Disables a doodad, as Set Doodad State does. Shared code only.
     */
    disableDoodad(unit: number): void;

    /**
     * Sets the resource amount of a resource container, 0 to 65535, as Modify Unit Resource Amount does. Shared code only.
     *
     * @returns {@code false} for a unit that is no resource container
     * @throws RangeError if the amount is negative or above 65535
     */
    setResources(unit: number, amount: number): boolean;

    /**
     * Adds up to {@code count} Interceptors or Scarabs while the hangar takes another one, as Modify Unit Hanger Count does. Shared code
     * only.
     *
     * @returns {@code false} for a unit without a hangar
     * @throws RangeError if the count is negative
     */
    addHangarCount(unit: number, count: number): boolean;

    /**
     * Creates a unit at a position in pixels, as Create Unit does; a mineral field or a Vespene Geyser belongs to the neutral player. Shared
     * code only.
     *
     * @param owner the player id, 0 to 11
     * @returns the new unit, -1 if none was created
     */
    create(unitType: number, owner: number, x: number, y: number): number;

    /**
     * Kills the unit, as Kill Unit does. Shared code only.
     *
     * @returns {@code false} for a subunit, which stays with its unit
     */
    kill(unit: number): boolean;

    /**
     * Deals damage as a hit does after armor and shields: the amount comes off the hit points, and a unit whose hit points run out dies. The
     * death and the kill count for the source: the unit event names the source unit as the killer and the source player as the killer's
     * player, the source unit's kill count grows against an enemy, and the source player's kill and score counters count the unit. A source
     * unit is revealed to the damaged unit's owner as an attacker is. Shared code only.
     *
     * @param amount       the raw damage in 1/256 hit points, at least 0
     * @param source       the attacking unit, or -1 for none
     * @param sourcePlayer the attacking player slot, or -1 for none
     * @returns {@code false} for a subunit, which stays with its unit
     */
    damage(unit: number, amount: number, source: number, sourcePlayer: number): boolean;

    /**
     * Removes the unit without a death, as Remove Unit does. Shared code only.
     *
     * @returns {@code false} for a subunit, which stays with its unit
     */
    remove(unit: number): boolean;

    /**
     * Moves the unit to a position in pixels, as Move Unit does. Shared code only.
     *
     * @returns {@code false} if Move Unit does not take the unit
     */
    move(unit: number, x: number, y: number): boolean;

    /**
     * Gives the unit to a player, as Give Units to Player does. Shared code only.
     *
     * @param player the player id, 0 to 11
     * @returns {@code false} if the routine does not take the unit
     */
    give(unit: number, player: number): boolean;

    /**
     * Issues an order. Not queued and without a target unit, it orders the position as the Order action does; queued or to a target unit, it
     * goes through the order system after the order requirement test, which appends a queued order behind the queue when the order takes
     * one. Shared code only.
     *
     * @param target the target unit, -1 for the position
     * @param x      the target x in pixels
     * @param y      the target y in pixels
     * @throws RangeError for the Cloak order, or a target that is not a live unit
     */
    issueOrder(unit: number, order: number, target: number, x: number, y: number, queued: boolean): void;

    /**
     * Runs the use-weapon routine of the unit scripts: the bullets of the weapon toward the unit's order target, two for a weapon of damage
     * factor 2, none for a weapon without a flingy. Shared code only.
     */
    useWeapon(unit: number, weapon: number): void;

    /**
     * Plays an iscript animation on every image of the unit's sprite, as the orders request one: each image switches to the animation and runs
     * it at once. An image whose request gate ignores the animation, and every image while the sprite runs a section that must not be broken,
     * keeps its own. Shared code only.
     *
     * @param animation the animation of the iscript animation table, 2 (GndAttkInit) to 27 (Enable); another value throws a RangeError
     */
    playAnimation(unit: number, animation: number): void;

    /**
     * Returns the orders waiting behind the current one, first to last.
     */
    getOrderQueue(unit: number): CoreOrderRecord[];

    /**
     * Returns the secondary order id.
     */
    getSecondaryOrder(unit: number): number;

    /**
     * Returns the WORD of the unit's command card button set ({@link CoreButtons}).
     */
    getButtonSet(unit: number): number;

    /**
     * Sets the button set WORD, 0 to 65535; the game sets it again on morphs, completion and burrowing. Shared code only.
     */
    setButtonSet(unit: number, set: number): void;

    /**
     * Returns the state byte of the secondary order.
     */
    getSecondaryOrderState(unit: number): number;

    /**
     * Sets the secondary order as the game does: a changed order clears its state and the unit in production. Shared code only.
     */
    setSecondaryOrder(unit: number, order: number): void;

    /**
     * Returns the hit points in 1/256 that a unit under construction gains each frame.
     */
    getHpGain(unit: number): number;

    /**
     * Sets the value {@link getHpGain} returns, 0 to 65535. Shared code only.
     */
    setHpGain(unit: number, hp: number): void;

    /**
     * Returns the shields in 1/256 that a unit under construction gains each frame.
     */
    getShieldGain(unit: number): number;

    /**
     * Sets the value {@link getShieldGain} returns, 0 to 65535. Shared code only.
     */
    setShieldGain(unit: number, shields: number): void;

    /**
     * Returns the whole hit points the unit had before it morphed, which a cancelled Zerg building gets back.
     */
    getPreviousHp(unit: number): number;

    /**
     * Returns the players whose sight covers the unit, one bit per player.
     */
    getVisibilityFlags(unit: number): number;

    /**
     * Returns the players that detect the unit, one bit per player.
     */
    getDetectionFlags(unit: number): number;

    /**
     * Returns whether a player sees the unit: in sight and, when cloaked or burrowed, detected.
     *
     * @param player the player id, 0 to 11
     */
    isVisibleTo(unit: number, player: number): boolean;

    /**
     * Returns whether a player detects the unit.
     *
     * @param player the player id, 0 to 11
     */
    isDetectedBy(unit: number, player: number): boolean;

    /**
     * Returns the unit's air strength for the computer players.
     */
    getAirStrength(unit: number): number;

    /**
     * Returns the unit's ground strength for the computer players.
     */
    getGroundStrength(unit: number): number;

    /**
     * Finds the live units whose bounds meet a rectangle in pixels, in the unit finder's order. Shared code only, as it uses the finder's
     * working state.
     */
    findInRectangle(left: number, top: number, right: number, bottom: number): number[];

    /**
     * Finds the live units whose bounds hold a point in pixels, in the unit finder's order. Shared code only.
     */
    findAtPoint(x: number, y: number): number[];

    /**
     * Returns the Stim Packs timer in status steps of 8 frames.
     */
    getStimTimer(unit: number): number;

    /**
     * Sets the Stim Packs timer, 0 to 255 status steps; starting or ending it updates the unit's speed. Shared code only.
     */
    setStimTimer(unit: number, timer: number): void;

    /**
     * Returns the Ensnare timer in status steps of 8 frames.
     */
    getEnsnareTimer(unit: number): number;

    /**
     * Sets the Ensnare timer, 0 to 255 status steps: a timer that starts applies the effect first, 0 ends it. Shared code only.
     */
    setEnsnareTimer(unit: number, timer: number): void;

    /**
     * Returns the Lockdown timer in status steps of 8 frames.
     */
    getLockdownTimer(unit: number): number;

    /**
     * Sets the Lockdown timer, 0 to 255 status steps: a timer that starts applies the effect first, 0 ends it. Shared code only.
     */
    setLockdownTimer(unit: number, timer: number): void;

    /**
     * Returns the Stasis Field timer in status steps of 8 frames.
     */
    getStasisTimer(unit: number): number;

    /**
     * Sets the Stasis Field timer, 0 to 255 status steps: a timer that starts applies the effect first, 0 ends it. Shared code only.
     */
    setStasisTimer(unit: number, timer: number): void;

    /**
     * Returns the Maelstrom timer in status steps of 8 frames.
     */
    getMaelstromTimer(unit: number): number;

    /**
     * Sets the Maelstrom timer, 0 to 255 status steps: a timer that starts applies the effect first, 0 ends it. Shared code only.
     */
    setMaelstromTimer(unit: number, timer: number): void;

    /**
     * Returns the Plague timer in status steps of 8 frames.
     */
    getPlagueTimer(unit: number): number;

    /**
     * Sets the Plague timer, 0 to 255 status steps: a timer that starts applies the effect first, 0 ends it. Shared code only.
     */
    setPlagueTimer(unit: number, timer: number): void;

    /**
     * Returns the Irradiate timer in status steps of 8 frames.
     */
    getIrradiateTimer(unit: number): number;

    /**
     * Sets the Irradiate timer, 0 to 255 status steps: a timer that starts applies the effect first, 0 ends it. Shared code only.
     */
    setIrradiateTimer(unit: number, timer: number): void;

    /**
     * Returns the Psionic Storm timer.
     */
    getStormTimer(unit: number): number;

    /**
     * Stores the Psionic Storm timer, 0 to 255, without running any effect. Shared code only.
     */
    setStormTimer(unit: number, timer: number): void;

    /**
     * Returns the Defensive Matrix timer in status steps of 8 frames.
     */
    getDefensiveMatrixTimer(unit: number): number;

    /**
     * Sets the Defensive Matrix timer, 0 to 255 status steps: a timer that starts adds the overlays, 0 ends the matrix as its expiry does.
     * Shared code only.
     */
    setDefensiveMatrixTimer(unit: number, timer: number): void;

    /**
     * Returns the hit points of the Defensive Matrix in 1/256.
     */
    getDefensiveMatrixHp(unit: number): number;

    /**
     * Stores the hit points of the Defensive Matrix in 1/256, 0 to 65535. Shared code only.
     */
    setDefensiveMatrixHp(unit: number, hp: number): void;

    /**
     * Returns the number of Acid Spores on the unit.
     */
    getAcidSporeCount(unit: number): number;

    /**
     * Sets the number of Acid Spores, 0 to 255, and shows the overlay of the count; 0 removes the spores. Shared code only.
     */
    setAcidSporeCount(unit: number, count: number): void;

    /**
     * Returns the frames until a timed unit is removed.
     */
    getRemoveTimer(unit: number): number;

    /**
     * Stores the frames until a timed unit is removed, 0 to 65535. Shared code only.
     */
    setRemoveTimer(unit: number, frames: number): void;

    /**
     * Returns the players whose Parasite is on the unit, one bit per player.
     */
    getParasiteFlags(unit: number): number;

    /**
     * Stores the Parasite player bits, 0 to 255. Shared code only.
     */
    setParasiteFlags(unit: number, flags: number): void;

    /**
     * Returns the players whose Optical Flare blinded the unit, one bit per player.
     */
    getBlindFlags(unit: number): number;

    /**
     * Stores the Optical Flare player bits, 0 to 255. Shared code only.
     */
    setBlindFlags(unit: number, flags: number): void;

    /**
     * Returns the frames until the ground weapon can fire again.
     */
    getGroundCooldown(unit: number): number;

    /**
     * Stores the ground weapon cooldown in frames, 0 to 255. Shared code only.
     */
    setGroundCooldown(unit: number, frames: number): void;

    /**
     * Returns the frames until the air weapon can fire again.
     */
    getAirCooldown(unit: number): number;

    /**
     * Stores the air weapon cooldown in frames, 0 to 255. Shared code only.
     */
    setAirCooldown(unit: number, frames: number): void;

    /**
     * Returns the frames until the unit can cast a spell again.
     */
    getSpellCooldown(unit: number): number;

    /**
     * Stores the spell cooldown in frames, 0 to 255. Shared code only.
     */
    setSpellCooldown(unit: number, frames: number): void;

    /**
     * Returns the unit's kill count.
     */
    getKillCount(unit: number): number;

    /**
     * Stores the unit's kill count, 0 to 255. Shared code only.
     */
    setKillCount(unit: number, kills: number): void;

    /**
     * Returns the unit types in the build queue, from the one in production.
     */
    getBuildQueue(unit: number): number[];

    /**
     * Returns the frames left of the current production.
     */
    getRemainingBuildTime(unit: number): number;

    /**
     * Stores the frames left of the current production, 0 to 65535. Shared code only.
     */
    setRemainingBuildTime(unit: number, frames: number): void;

    /**
     * Returns the frames left of the current research or upgrade.
     */
    getRemainingResearchTime(unit: number): number;

    /**
     * Stores the frames left of the current research or upgrade, 0 to 65535. Shared code only.
     */
    setRemainingResearchTime(unit: number, frames: number): void;

    /**
     * Returns the technology in research, -1 for none.
     */
    getResearch(unit: number): number;

    /**
     * Returns the upgrade in progress, -1 for none.
     */
    getUpgrade(unit: number): number;

    /**
     * Returns the add-on, -1 for none.
     */
    getAddon(unit: number): number;

    /**
     * Returns the x of the rally point in pixels.
     */
    getRallyX(unit: number): number;

    /**
     * Returns the y of the rally point in pixels.
     */
    getRallyY(unit: number): number;

    /**
     * Returns the rally unit, -1 for none.
     */
    getRallyUnit(unit: number): number;

    /**
     * Sets the rally point to a position in pixels, 0 to 65535 each. Shared code only.
     *
     * @returns {@code false} for a unit type without a rally point
     */
    setRallyPosition(unit: number, x: number, y: number): boolean;

    /**
     * Sets the rally unit. Shared code only.
     *
     * @param target the rally unit, -1 for none
     * @returns {@code false} for a unit type without a rally point
     */
    setRallyUnit(unit: number, target: number): boolean;

    /**
     * Returns the direction the unit faces, 0 to 255.
     */
    getDirection(unit: number): number;

    /**
     * Turns the unit at once to a direction, 0 to 255. Shared code only.
     */
    setDirection(unit: number, direction: number): void;

    /**
     * Returns the current speed in 1/256 pixels per frame.
     */
    getCurrentSpeed(unit: number): number;

    /**
     * Returns the top speed in 1/256 pixels per frame.
     */
    getTopSpeed(unit: number): number;

    /**
     * Returns the x of the movement destination in pixels.
     */
    getMoveTargetX(unit: number): number;

    /**
     * Returns the y of the movement destination in pixels.
     */
    getMoveTargetY(unit: number): number;

    /**
     * Stores the movement destination in pixels, 0 to 65535 each. Shared code only.
     */
    setMoveTarget(unit: number, x: number, y: number): void;

    /**
     * Changes the unit's type through the morph routine, which keeps the unit, its owner, order and links and scales its hit points. Shared
     * code only.
     *
     * @returns {@code false} for a subunit, a subunit type, or a type whose turret does not match the unit's
     */
    setType(unit: number, unitType: number): boolean;
}

/**
 * The map locations by id ({@code core.location}): 0 to {@link getCount} minus one. Bounds and centers are map pixels.
 */
interface CoreLocation {
    /**
     * Returns the number of locations.
     */
    getCount(): number;

    /**
     * Returns the location's name from the map strings, {@code null} for a location without one.
     */
    getName(location: number): string | null;

    /**
     * Returns the first location with the name, -1 for none.
     */
    findByName(name: string): number;

    /**
     * Returns the left bound in pixels.
     */
    getLeft(location: number): number;

    /**
     * Returns the top bound in pixels.
     */
    getTop(location: number): number;

    /**
     * Returns the right bound in pixels.
     */
    getRight(location: number): number;

    /**
     * Returns the bottom bound in pixels.
     */
    getBottom(location: number): number;

    /**
     * Returns the x of the location's center in pixels.
     */
    getCenterX(location: number): number;

    /**
     * Returns the y of the location's center in pixels.
     */
    getCenterY(location: number): number;

    /**
     * Stores the four bounds in pixels as given, without masking or clamping. Shared code only.
     */
    setBounds(location: number, left: number, top: number, right: number, bottom: number): void;

    /**
     * Moves the location's center to a position in pixels, as Move Location does, kept inside the map. Shared code only.
     */
    setCenter(location: number, x: number, y: number): void;

    /**
     * Returns the elevation flags ({@link LocationFlag}): a set bit leaves the units at its elevation out of the location.
     */
    getFlags(location: number): number;

    /**
     * Stores the elevation flags, 0 to 65535. Shared code only.
     *
     * @throws RangeError if the flags are outside 0 to 65535
     */
    setFlags(location: number, flags: number): void;
}

/**
 * The tiles of the map ({@code core.terrain}) by tile coordinates (32 pixels a tile), as MTXM values: {@code group << 4 | index}, the CV5
 * group and the tile's index in it. A tile coordinate outside the map or a value that names no tile of the map's tileset throws a RangeError.
 */
interface CoreTerrain {
    /**
     * Reads the tile at the tile coordinates after the changes queued so far; for a tile with creep, the tile below the creep.
     *
     * @returns the MTXM value
     */
    getTile(tileX: number, tileY: number): number;

    /**
     * Reads the flags the game holds for the tile now. Throws a TypeError when the map has no tile flags.
     *
     * @returns the flag DWORD (unsigned): the sight bits of {@link CoreVision}, walkability, creep, elevation and occupation
     */
    getTileFlags(tileX: number, tileY: number): number;

    /**
     * Queues a tile change, which the game applies at its next trigger step, after the frame start hooks and before the triggers, as on a map
     * that had the tile from the start. Shared code only.
     *
     * @param value the MTXM value
     */
    setTile(tileX: number, tileY: number, value: number): void;

    /**
     * @returns the number of CV5 groups of the map's tileset
     */
    getTileGroupCount(): number;

    /**
     * @returns true if the MTXM value names a tile of the map's tileset
     */
    isValidTile(value: number): boolean;

    /**
     * Tests whether the tile of an MTXM value is walkable: more than twelve of its sixteen mini-tiles are walkable.
     */
    isTileWalkable(value: number): boolean;

    /**
     * Tests whether some but not all mini-tiles of the tile of an MTXM value are walkable.
     */
    isTilePartiallyWalkable(value: number): boolean;

    /**
     * Tests whether the tile of an MTXM value is buildable.
     */
    isTileBuildable(value: number): boolean;

    /**
     * Tests whether the tile of an MTXM value is a creep tile.
     */
    hasTileCreep(value: number): boolean;

    /**
     * @returns the elevation of the tile of an MTXM value: 0 low, 1 middle, 2 high ground
     */
    getTileElevation(value: number): number;

    /**
     * Tests whether the tile of an MTXM value blocks the sight of ground units at every elevation.
     */
    isTileBlockingSight(value: number): boolean;

    /**
     * Tests the creep flag of the tile, which the rules of the game read (placement, larvae, spells) and {@link CoreCreep} sets and clears.
     * Throws a TypeError when the map has no tile flags.
     */
    hasCreep(tileX: number, tileY: number): boolean;
}

/**
 * The routines of the game that add and remove creep ({@code core.creep}). Creep they add has no source: it stays until it is removed or
 * recedes. A unit type is from 0 to 227, a map position in pixels inside the map. Every function throws a TypeError when the game has no creep.
 */
interface CoreCreep {
    /**
     * Covers the footprint of a building of the unit type centered at the map position with creep and grows it until no tile is added, as the
     * game does for a building that needs creep. Shared code only. Throws a RangeError when the footprint leaves the map.
     */
    fill(unitType: number, x: number, y: number): void;

    /**
     * Runs one spread step of a creep source of the unit type at the map position; a type that spreads creep keeps to its ellipse. Shared code
     * only. Throws a RangeError when the spread leaves the map.
     *
     * @returns 1 when it added a tile, 2 when it added none and an occupied tile blocked it, else 0
     */
    spread(unitType: number, x: number, y: number): number;

    /**
     * Removes the creep of the tile at once and gives back the terrain below it. Shared code only.
     */
    removeTile(tileX: number, tileY: number): void;

    /**
     * Starts the recession of a creep tile as a removed building does: the tile recedes later, at the pace of the recession. Shared code only.
     * Throws a TypeError when the game has no creep recession. Nothing happens for a tile that is not creep, already recedes, is occupied or
     * that a grounded Zerg building sustains, or when no recession record is free.
     *
     * @returns {@code true} if the tile started to recede
     */
    recede(tileX: number, tileY: number): boolean;
}

/**
 * The sprites of the sprite pool ({@code core.sprite}) by slot, from 0 to getCapacity() - 1. A slot holds a sprite while it is allocated;
 * every allocation takes the next generation of the pool, so a sprite is its slot and generation. The functions other than getCapacity,
 * getIds, exists and getGeneration throw a RangeError for a slot without a sprite. The pools of sprites and images also hold the buildings the
 * local player remembers in the fog of war, so the slots and generations can differ from player to player in a network game: shared code may
 * show them but should not let them decide the game.
 */
interface CoreSprite {
    /**
     * @returns the number of sprite slots
     */
    getCapacity(): number;

    /**
     * @returns the allocated sprites in slot order
     */
    getIds(): number[];

    /**
     * @returns true if the slot holds a sprite, false for an empty slot or an id outside the pool
     */
    exists(sprite: number): boolean;

    /**
     * @returns the generation of the slot: that of the sprite it holds, or of the last sprite it held
     */
    getGeneration(sprite: number): number;

    /**
     * @returns the sprites.dat entry
     */
    getType(sprite: number): number;

    /**
     * @returns the x position in map pixels
     */
    getX(sprite: number): number;

    /**
     * @returns the y position in map pixels
     */
    getY(sprite: number): number;

    /**
     * @returns the owner byte, a player slot
     */
    getOwner(sprite: number): number;

    /**
     * @returns the elevation level, 0 to 255
     */
    getElevation(sprite: number): number;

    /**
     * @returns the sprite's flags
     */
    getFlags(sprite: number): number;

    /**
     * @returns the players that see the sprite, one bit per player
     */
    getVisibilityFlags(sprite: number): number;

    /**
     * Returns the images of the sprite in the order of its list, from the image drawn on top. The local selection circles and health bars are
     * no images of the pool.
     *
     * @returns the image slot ids
     */
    getImages(sprite: number): number[];

    /**
     * @returns the main image, -1 for none
     */
    getMainImage(sprite: number): number;

    /**
     * Creates a sprite that lives as long as its image scripts run, as the game creates the sprite of a spell or of dust, then sets its
     * elevation and its visibility from the tile below it, as every native caller does. Shared code only.
     *
     * @param spriteType the sprites.dat entry
     * @param x          the x position in map pixels, inside the map
     * @param y          the y position in map pixels, inside the map
     * @param owner      the owner byte, a player slot from 0 to 11
     * @param elevation  the elevation level, 0 to 255
     * @returns the sprite, or -1 when the pools are full
     */
    createEffect(spriteType: number, x: number, y: number, owner: number, elevation: number): number;
}

/**
 * The images of the image pool ({@code core.image}) by slot, from 0 to getCapacity() - 1, with a generation per slot as {@link CoreSprite}.
 * The functions other than getCapacity, exists and getGeneration throw a RangeError for a slot without an image.
 */
interface CoreImage {
    /**
     * @returns the number of image slots
     */
    getCapacity(): number;

    /**
     * @returns true if the slot holds an image, false for an empty slot or an id outside the pool
     */
    exists(image: number): boolean;

    /**
     * @returns the generation of the slot: that of the image it holds, or of the last image it held
     */
    getGeneration(image: number): number;

    /**
     * @returns the images.dat entry
     */
    getType(image: number): number;

    /**
     * @returns the sprite of the image, -1 for none
     */
    getSprite(image: number): number;

    /**
     * @returns the drawn frame
     */
    getFrame(image: number): number;

    /**
     * @returns the frame offset of the direction inside the frame group
     */
    getDirection(image: number): number;

    /**
     * @returns the draw function, 0 to 17 ({@link DrawFunction})
     */
    getDrawFunction(image: number): number;

    /**
     * @returns the image's flags
     */
    getFlags(image: number): number;

    /**
     * @returns the x offset from the sprite in pixels
     */
    getOffsetX(image: number): number;

    /**
     * @returns the y offset from the sprite in pixels
     */
    getOffsetY(image: number): number;
}

/**
 * The bullets of the bullet table ({@code core.bullet}) by entry, from 0 to getCapacity() - 1. An entry holds a bullet while the bullet is in
 * the active list, from its creation until it is released after dying; every bullet takes the next generation when it joins the list, so a
 * bullet is its entry and generation. The functions other than getCapacity, getActiveCount, getIds, exists and getGeneration throw a RangeError
 * for an entry without an active bullet.
 */
interface CoreBullet {
    /**
     * @returns the number of entries of the bullet table
     */
    getCapacity(): number;

    /**
     * @returns the number of active bullets
     */
    getActiveCount(): number;

    /**
     * @returns the active bullets in the order of the active list
     */
    getIds(): number[];

    /**
     * @returns true if the entry holds an active bullet, false for another entry or an id outside the table
     */
    exists(bullet: number): boolean;

    /**
     * @returns the generation of the entry: that of the bullet it holds, or of the last bullet it held
     */
    getGeneration(bullet: number): number;

    /**
     * @returns the weapons.dat entry
     */
    getWeapon(bullet: number): number;

    /**
     * @returns the flingy.dat entry
     */
    getFlingyType(bullet: number): number;

    /**
     * @returns the x position in map pixels
     */
    getX(bullet: number): number;

    /**
     * @returns the y position in map pixels
     */
    getY(bullet: number): number;

    /**
     * @returns the player slot that owns the bullet
     */
    getPlayer(bullet: number): number;

    /**
     * @returns the unit that fired the bullet, -1 once it is gone
     */
    getSource(bullet: number): number;

    /**
     * @returns the target unit, -1 for none
     */
    getTarget(bullet: number): number;

    /**
     * @returns the x of the center of the hit in map pixels
     */
    getTargetX(bullet: number): number;

    /**
     * @returns the y of the center of the hit in map pixels
     */
    getTargetY(bullet: number): number;

    /**
     * @returns 0 init, 1 move, 2 follow, 3 bounce, 4 damage over time, 5 dying, 6 hit near the target ({@link BulletState})
     */
    getState(bullet: number): number;

    /**
     * @returns the remaining time in frames, which the move and damage-over-time states count down
     */
    getRemainingTime(bullet: number): number;

    /**
     * @returns the bounces left
     */
    getRemainingBounces(bullet: number): number;

    /**
     * @returns the heading of the bullet
     */
    getDirection(bullet: number): number;

    /**
     * @returns the sprite, -1 once its last image ended
     */
    getSprite(bullet: number): number;

    /**
     * Sets the target unit as an EUD write does: the follow, bounce and hit-near-target states move toward it from the next frame and the hit
     * damages it; the move state keeps its move target. Shared code only.
     *
     * @param unit a live unit, -1 for none
     */
    setTarget(bullet: number, unit: number): void;

    /**
     * Sets the center of the hit as an EUD write does; the move target does not change. Shared code only.
     *
     * @param x the x in map pixels, inside the map
     * @param y the y in map pixels, inside the map
     */
    setTargetPosition(bullet: number, x: number, y: number): void;

    /**
     * Sets the remaining time, which the move and damage-over-time states count down. Shared code only.
     *
     * @param frames the frames, 0 to 255
     */
    setRemainingTime(bullet: number, frames: number): void;

    /**
     * Runs the bullet creation of the game at the map position for a live unit, which aims the bullet at its order target unit, else its order
     * target position. Shared code only.
     *
     * @param weapon the weapons.dat entry
     * @param source the live unit that fires
     * @param x      the x in map pixels, inside the map
     * @param y      the y in map pixels, inside the map
     * @returns the bullet, or -1 when the game creates none (no free entry, no sprite, an attack the unit cannot make)
     */
    create(weapon: number, source: number, x: number, y: number): number;
}

/**
 * The sight of the players on the map ({@code core.vision}). A player is a slot from 0 to 11. The functions that read or write tile flags
 * throw a TypeError when the map has none.
 */
interface CoreVision {
    /**
     * Reads the visibility bits of the tile. The game marks every tile unseen every hundred frames and the units' sight clears the bits again;
     * the explored bits stay clear.
     *
     * @returns bit p set while player p does not see the tile, bit 8 + p while player p has not explored it
     */
    getTileVisibility(tileX: number, tileY: number): number;

    /**
     * Replaces the visibility bits of the tile as an EUD write does: the visible bits last until the game marks the tiles unseen or the units'
     * sight clears them, the explored bits stay. Shared code only.
     *
     * @param bits the bits as {@link getTileVisibility} returns them, 0 to 0xFFFF
     */
    setTileVisibility(tileX: number, tileY: number, bits: number): void;

    /**
     * Reads the fog brightness of visible tiles: the world's fog draws visible tiles at this level, explored tiles at half of it and unexplored
     * tiles black; the minimap does not use it.
     *
     * @returns the level, 0 to 255, default 31 (full brightness)
     */
    getFogBrightness(): number;

    /**
     * Sets the fog brightness of visible tiles ({@link getFogBrightness}), world fog only, not the minimap. It changes no game state: shared code
     * sets it on every player's game, local code on this machine only.
     *
     * @param level the level, 0 to 255
     */
    setFogBrightness(level: number): void;

    /**
     * Tests whether the player sees the tile at the map position (pixels), as the visibility system does.
     */
    isVisible(player: number, x: number, y: number): boolean;

    /**
     * Tests whether the player has explored the tile at the map position (pixels), as the visibility system does.
     */
    isExplored(player: number, x: number, y: number): boolean;

    /**
     * Runs a unit's sight routine at the map position (pixels) for the players of the mask: an air reveal sees over the terrain, a ground
     * reveal not over higher ground; the tiles stay seen until the game marks them unseen again. Shared code only. Throws a TypeError when the
     * game has no sight coverage.
     *
     * @param players    the players, one bit per player, 0 to 0xFF
     * @param sightRange the sight range in tiles, 0 to 11
     */
    reveal(players: number, x: number, y: number, sightRange: number, air: boolean): void;
}

/**
 * The combat tables of the game that EUD writes change ({@code core.combat}): the damage multipliers and the flee distances.
 */
interface CoreCombat {
    /**
     * Reads the factor that scales the damage of a damage type against a unit size.
     *
     * @param damageType 0 independent, 1 explosive, 2 concussive, 3 normal, 4 ignore armor
     * @param size       0 independent, 1 small, 2 medium, 3 large
     * @returns the factor in 1/256, an unsigned 32-bit value
     */
    getDamageMultiplier(damageType: number, size: number): number;

    /**
     * Sets the factor of a damage type against a unit size ({@link getDamageMultiplier}). Shared code only.
     *
     * @param value the factor in 1/256, 0 to 2^32 - 1
     */
    setDamageMultiplier(damageType: number, size: number, value: number): void;

    /**
     * @returns the number of distances a fleeing unit tries
     */
    getFleeDistanceCount(): number;

    /**
     * @param index the index, from 0 to getFleeDistanceCount() - 1, in the order a fleeing unit tries the distances
     * @returns the distance in pixels, an unsigned 32-bit value
     */
    getFleeDistance(index: number): number;

    /**
     * Sets a distance a fleeing unit tries ({@link getFleeDistance}). Shared code only.
     *
     * @param pixels the distance in pixels, 0 to 2^32 - 1
     */
    setFleeDistance(index: number, pixels: number): void;
}

/**
 * The requirement scripts and the native requirement tests ({@code core.requirements}). The scripts by kind: 0 orders, 1 technology use,
 * 2 research, 3 upgrades, 4 unit production, each an array of WORDs (0xFF01 or, 0xFF02 the unit is, ... 0xFFFF end). A script keeps its
 * length. The DAT requirement index columns ({@link CoreDat}) keep the start positions their first test built; a column entry set to 0xFFFF
 * makes the next test of the kind build the column again from the script.
 */
interface CoreRequirements {
    /**
     * @param kind the script kind, 0 to 4
     * @returns the number of WORDs of the script
     */
    getScriptLength(kind: number): number;

    /**
     * @param kind     the script kind, 0 to 4
     * @param position the position, from 0 to the script length - 1
     * @returns the WORD at the position
     */
    getScriptWord(kind: number, position: number): number;

    /**
     * Writes a WORD of a script, as an EUD write does. Shared code only.
     *
     * @param kind     the script kind, 0 to 4
     * @param position the position, from 0 to the script length - 1
     * @param word     the WORD, 0 to 0xFFFF
     */
    setScriptWord(kind: number, position: number, word: number): void;

    /**
     * Runs the native order test for a live unit and a player slot; a test may build a DAT index column, as the command card's tests do.
     *
     * The result is 0 when the requirements hold, else the failure code in the low byte, with 0x10000 set when the command card shows the
     * button disabled rather than hidden.
     *
     * @returns the test result
     */
    checkOrder(unit: number, order: number, player: number): number;

    /** Runs the native technology use test; as {@link checkOrder}. */
    checkTechnologyUse(unit: number, tech: number, player: number): number;

    /** Runs the native research test; as {@link checkOrder}. */
    checkResearch(unit: number, tech: number, player: number): number;

    /** Runs the native upgrade test; as {@link checkOrder}. */
    checkUpgrade(unit: number, upgrade: number, player: number): number;

    /** Runs the native unit production test; as {@link checkOrder}. */
    checkBuild(unit: number, unitType: number, player: number): number;
}

/**
 * The state of the computer players ({@code core.ai}). Its functions throw a TypeError when the game has no unit strength table.
 */
interface CoreAi {
    /**
     * Reads the strength of a unit type against air or ground targets, from which a unit takes its own strengths
     * ({@link CoreUnit.getAirStrength}, {@link CoreUnit.getGroundStrength}) when it is created or changes.
     *
     * @param unitType the unit type, 0 to 227
     * @returns the strength, an unsigned 32-bit value
     */
    getUnitStrength(unitType: number, air: boolean): number;

    /**
     * Sets the strength of a unit type against air or ground targets ({@link getUnitStrength}), as an EUD write does. Shared code only.
     *
     * @param value the strength, 0 to 2^32 - 1
     */
    setUnitStrength(unitType: number, air: boolean, value: number): void;
}

/**
 * The leaderboards of the triggers ({@code core.leaderboard}). The shown leaderboard and the inclusion of computer players are game state. A
 * label is shown as a map string: a label with a NUL character, or one when no map string is left, throws a RangeError.
 */
interface CoreLeaderboard {
    /**
     * Shows a leaderboard without a location, as the leaderboard actions do. Shared code only.
     *
     * @param type    the action id: 17 control, 19 resources, 20 kills, 21 points, or the goal forms 33, 35, 36, 37
     * @param operand a unit type or {@link UnitGroup} (control, kills), a resource type 0 to 2 (resources) or a score type 0 to 7 (points)
     * @param goal    the goal of the goal forms; the other forms ignore it
     * @throws RangeError if the type is not one of these or the operand does not fit it
     */
    show(type: number, operand: number, goal: number, label: string): void;

    /**
     * Shows Leader Board Control At Location or its goal form. Shared code only.
     *
     * @param type     the action id: 18, or 34 for the goal form
     * @param location the location index
     * @param unitType a unit type or unit group ({@link UnitGroup})
     * @param goal     the goal of the goal form; the other form ignores it
     * @throws RangeError if the type is neither 18 nor 34
     */
    showAt(type: number, location: number, unitType: number, goal: number, label: string): void;

    /**
     * Shows the Greed leaderboard of the ore and gas of every player toward the goal. Shared code only.
     */
    showGreed(goal: number): void;

    /**
     * @returns true if the leaderboards include computer players
     */
    areComputersIncluded(): boolean;

    /**
     * Sets whether the leaderboards include computer players, as Leaderboard Computer Players does. Shared code only.
     */
    setComputersIncluded(included: boolean): void;
}

/**
 * The entries of a DAT file by index ({@code core.unitType}, {@code core.weaponType}, {@code core.flingyType}, {@code core.spriteType},
 * {@code core.imageType}, {@code core.upgradeType}, {@code core.techType}, {@code core.orderType}).
 */
interface CoreTypes {
    /**
     * @returns the number of entries
     */
    getCount(): number;

    /**
     * Reads the name of an entry: the original data name ("Terran Marine"). Image types are named after their GRP path
     * ({@code UnitTerranMarine}), sprite types after their image type and flingy types after their sprite type; a taken name gets _2, _3 and so
     * on in entry order.
     */
    getName(id: number): string;
}

/**
 * The image types ({@code core.imageType}) with their shared HD anims and damage overlays.
 */
interface CoreImageTypes extends CoreTypes {
    /**
     * Returns the image type whose HD anim the image type draws (images.rel): the image type itself for one with its own anim and when
     * images.rel cannot be read.
     *
     * @returns the image type id
     */
    getAnimImageType(imageType: number): number;

    /**
     * Reads the damage overlay byte of the image type. Throws a TypeError when the damage overlay cannot be read.
     *
     * @returns twice the number of flame positions of the image's damage overlay
     */
    getDamageOverlayCount(imageType: number): number;

    /**
     * Stores the damage overlay byte of the image type as an EUD write does; a unit with the image as its main image takes it as its number of
     * damage states. Shared code only.
     *
     * @param count the byte, 0 to 255
     */
    setDamageOverlayCount(imageType: number, count: number): void;
}

/**
 * The GRPs, anims and images of the map's resources ({@code core.resources}). A resource name is relative to the resource folder with /
 * separators. getGrp, getAnim and getImage check a resource once and return its handle, the same for the same name; a missing or invalid
 * resource throws an Error.
 */
interface CoreResources {
    /**
     * @returns true if the map has a resource of the name, without checking its contents
     */
    has(name: string): boolean;

    /**
     * Reads a GRP: a header with a readable frame table whose frames decode.
     *
     * @returns the resource handle
     */
    getGrp(name: string): number;

    /**
     * Reads an anim: a single-entry anim of asset scale 4 (HD) or 2 (HD2) with a canvas and a diffuse layer.
     *
     * @returns the resource handle
     */
    getAnim(name: string): number;

    /**
     * Reads an image: a PNG or DDS picture that decodes.
     *
     * @returns the resource handle
     */
    getImage(name: string): number;

    /**
     * Reads a resource as UTF-8 text, a leading byte order mark left out; bytes that are no UTF-8 throw an Error. The text is decoded once.
     *
     * @returns the text
     */
    readText(name: string): string;

    /**
     * @param image an image handle ({@link getImage}); another handle or -1 throws a RangeError
     * @returns the width in pixels
     */
    getImageWidth(image: number): number;

    /**
     * @param image an image handle ({@link getImage}); another handle or -1 throws a RangeError
     * @returns the height in pixels
     */
    getImageHeight(image: number): number;
}

/**
 * The replacements of the graphics that images draw ({@code core.graphics}). They change only what is drawn and no game state: shared code
 * replaces on every player's game, local code on this machine only. The graphics are three handles of {@link CoreResources}, each -1 for none:
 * at least one is given, the HD anim has asset scale 4 and the HD2 anim 2, else a RangeError is thrown. The classic graphics draw the GRP, else
 * the installed GRP; HD draws the HD anim, else the HD2 anim; HD2 draws the HD2 anim, else the HD anim at half size; without an anim both draw
 * the GRP. A replacement wins over the GRP and anim pointers that EUD writes give the image.
 */
interface CoreGraphics {
    /**
     * Replaces the graphics of every image of the image type.
     *
     * @param grp     the GRP handle, -1 for none
     * @param hdAnim  the HD anim handle, -1 for none
     * @param hd2Anim the HD2 anim handle, -1 for none
     */
    replaceImage(imageType: number, grp: number, hdAnim: number, hd2Anim: number): void;

    /**
     * Takes back the replacement of the image type.
     */
    restoreImage(imageType: number): void;

    /**
     * Replaces the graphics of a live unit's images of the image type while the unit lives; they win over those of the image type.
     */
    replaceUnitImage(unit: number, imageType: number, grp: number, hdAnim: number, hd2Anim: number): void;

    /**
     * Takes back the replacement of a live unit's images of the image type.
     */
    restoreUnitImage(unit: number, imageType: number): void;

    /**
     * Replaces the graphics of one image ({@link CoreImage}) while its slot holds it; they win over those of its unit and image type.
     */
    replaceImageGraphics(image: number, grp: number, hdAnim: number, hd2Anim: number): void;

    /**
     * Takes back the replacement of one image.
     */
    restoreImageGraphics(image: number): void;

    /**
     * Draws a frame of one image until the image's own drawn frame changes, as an EUD write of the frame does; it wins over an EUD write.
     *
     * @param frame the frame, 0 to 65535, or -1 to draw the image's own frame again
     */
    setImageFrame(image: number, frame: number): void;

    /**
     * Holds a drawn frame of one image while its iscript steps its own frame, until -1 releases it, another frame is set or held, or the image
     * is gone; it wins over an EUD write.
     *
     * @param frame the frame, 0 to 65535, or -1 to draw the image's own frame again
     */
    holdImageFrame(image: number, frame: number): void;

    /**
     * Draws one image with a draw function until the image's own draw function changes, as an EUD write of the draw function does; it wins
     * over an EUD write.
     *
     * @param drawFunction the draw function, 0 to 17 ({@link DrawFunction}), or -1 to draw with the image's own again
     */
    setImageDrawFunction(image: number, drawFunction: number): void;
}

/**
 * What happened in the simulation ({@code core.events}), delivered at the frame start after it happened.
 */
interface CoreEvents {
    /**
     * Reads the frame's events. Reading does not take them: every call of a frame returns the same records, and the events that happen
     * meanwhile wait for the next frame start. Shared code only.
     *
     * @returns a new array of the records of the frame's events, in the order they happened
     */
    getFrameEvents(): CoreEventRecord[];

    /**
     * @returns the number of event kinds
     */
    getKindCount(): number;

    /**
     * @param kind the event kind, from 0 to getKindCount() - 1
     * @returns the name of the event kind, that of {@link EventKind}
     */
    getKindName(kind: number): string;
}

/**
 * The script commands ({@code core.command}): mod commands that carry one value from a player to the scripts of every player's game.
 */
interface CoreCommand {
    /**
     * Submits a value in the local player's next outgoing turn that has room. It changes no game state; a replay and an observer submit nothing,
     * and shared code submits once from every human player's game.
     *
     * @param value null, a boolean, a number, a string, an array or a plain object of these; anything else throws a TypeError
     */
    submit(value: any): void;

    /**
     * Reads the script commands that ran since the previous frame start. Shared code only.
     *
     * @returns new records of the commands, in the order they ran, each with a new copy of its value
     */
    getExecuted(): CoreCommandRecord[];
}

/**
 * The information only this machine has ({@code core.local}, {@link Local}): the local player, the local view, the local selection, the local
 * inputs and the local command card. It differs from player to player, so every function is local code only and changes no game state.
 */
interface CoreLocal {
    /**
     * Returns the local player. Local code only.
     *
     * @returns the local player's id (0 to 11), -1 in a replay and for an observer
     */
    getPlayer(): number;

    /**
     * Returns whether this machine plays a replay. Local code only.
     */
    isReplay(): boolean;

    /**
     * Returns whether the chat box is open. Local code only.
     */
    isChatOpen(): boolean;

    /**
     * Returns whether the client has supplied the screen and pointer positions; until then the screen and mouse positions are 0. Local code only.
     */
    isViewKnown(): boolean;

    /**
     * Returns the map x of the local screen's top left, in map pixels. Local code only.
     */
    getScreenX(): number;

    /**
     * Returns the map y of the local screen's top left, in map pixels. Local code only.
     */
    getScreenY(): number;

    /**
     * Returns the map x under the pointer, in map pixels: the screen position plus the pointer's position on the screen. Local code only.
     */
    getMouseX(): number;

    /**
     * Returns the map y under the pointer, in map pixels. Local code only.
     */
    getMouseY(): number;

    /**
     * Local code only.
     *
     * @returns true once the client supplied the pointer in interface units
     */
    isPointerKnown(): boolean;

    /**
     * Local code only.
     *
     * @returns the pointer column in interface units from the interface's left edge, the units of {@link CoreScreen}; 0 while unknown
     */
    getPointerX(): number;

    /**
     * Local code only.
     *
     * @returns the pointer row in interface units from the game screen's top edge; 0 while unknown
     */
    getPointerY(): number;

    /**
     * Moves the local screen's top left to a map position at once, as the camera move of Center View does. Local code only.
     *
     * @param x the map x in pixels
     * @param y the map y in pixels
     * @returns false while the view or its display size is unknown, true otherwise
     */
    setScreenPosition(x: number, y: number): boolean;

    /**
     * Returns the local selection. Local code only.
     *
     * @returns a new array of the ids of the live units the local player has selected, up to the first empty selection slot
     */
    getSelection(): number[];

    /**
     * Returns the local inputs of this local frame. Local code only.
     *
     * @returns a new array of the records of the local inputs that reached the game before the current local frame, read-only
     */
    getInputEvents(): CoreInputRecord[];

    /**
     * Returns the local state that the Script button condition gives for a condition value. Every state starts at 0 (hidden). Local code only.
     *
     * @param conditionValue the condition value, 0 to 255
     * @returns 1 enabled, 0 hidden, -1 disabled
     */
    getButtonState(conditionValue: number): number;

    /**
     * Sets the local state that the Script button condition gives for a condition value. Local code only.
     *
     * @param conditionValue the condition value, 0 to 255
     * @param state          1 enabled, 0 hidden, -1 disabled; any other value throws a RangeError
     */
    setButtonState(conditionValue: number, state: number): void;

    /**
     * Returns the button set the local command card shows. Local code only.
     *
     * @returns the button set, 0xE4 for none
     */
    getDisplayedButtonSet(): number;

    /**
     * Shows a button set on the local command card at its next refresh, as the Change Buttons action does, until the selection changes. Local
     * code only.
     *
     * @param set the button set, 0 to 249
     */
    changeButtons(set: number): void;

    /**
     * Sends the local player's Pause command of the game menu; it runs in a later turn on every player's game. A network pause takes one of the
     * player's pauses left. Does nothing in a replay or while the game is paused. Local code only.
     */
    requestPause(): void;

    /**
     * Sends the local player's Resume command; it runs in a later turn on every player's game. Does nothing in a replay or while the game runs.
     * Local code only.
     */
    requestResume(): void;
}

/**
 * The command card's button set table ({@code core.buttons}): sets 0 to 249, each an array of buttons and a connected set. The set of a unit
 * type is its id. The card fills each position 1 to 9 with the first button of the shown set, in array order, whose condition does not hide it.
 * <p>
 * The table is presentation, so every function works anywhere: a change in shared code changes the card on every player's game, a change in
 * local code only on this machine. Several sets may share one array, as a unit and its hero do; a change of a button or of the array changes
 * every set that shares it. An EUD write of the table rebuilds every set and so replaces the changes made before it.
 * <p>
 * Buttons are addressed by set and 0-based index in the set's array. Condition and action ids follow the order of
 * {@link CoreButtons.getConditionName} and {@link CoreButtons.getActionName}; a button whose function is unknown reads -1. The last condition,
 * Script, gives the local state of its condition value ({@link CoreLocal.setButtonState}); the last actions, Script and ScriptTarget, send no
 * command: the press, or the target of the targeting mode, reaches the local input hooks only.
 */
interface CoreButtons {
    /**
     * Returns the number of button sets, 250.
     */
    getSetCount(): number;

    /**
     * Returns the number of buttons in a set's array.
     *
     * @param set the button set, 0 to 249
     */
    getButtonCount(set: number): number;

    /**
     * Returns a set's connected set.
     *
     * @param set the button set, 0 to 249
     * @returns the connected set WORD, 0xFFFF for none
     */
    getConnectedSet(set: number): number;

    /**
     * Sets a set's connected set.
     *
     * @param set       the button set, 0 to 249
     * @param connected the connected set WORD, 0 to 0xFFFF; 0xFFFF for none
     */
    setConnectedSet(set: number, connected: number): void;

    /**
     * Names the array a set holds by the first set that holds the same array.
     *
     * @param set the button set, 0 to 249
     * @returns the lowest set that shares the set's array
     */
    getArray(set: number): number;

    /**
     * Makes a set hold the array of another set; later changes of either change both.
     *
     * @param set     the button set that takes the array, 0 to 249
     * @param fromSet the button set whose array it takes, 0 to 249
     */
    share(set: number, fromSet: number): void;

    /**
     * Gives a set its own copy of its array, so that later changes of it change no other set.
     *
     * @param set the button set, 0 to 249
     */
    unshare(set: number): void;

    /**
     * Gives a set its installed array and connected set again.
     *
     * @param set the button set, 0 to 249
     */
    restore(set: number): void;

    /**
     * Restores the whole installed table, also after EUD writes of the table.
     */
    restoreAll(): void;

    /**
     * Returns the card position of a button.
     *
     * @param set   the button set, 0 to 249
     * @param index the 0-based button index in the set
     * @returns the position, 1 to 9
     */
    getPosition(set: number, index: number): number;

    /**
     * Sets the card position of a button.
     *
     * @param position the position, 1 to 9
     */
    setPosition(set: number, index: number, position: number): void;

    /**
     * Returns the icon of a button.
     *
     * @returns the cmdicons frame, a WORD
     */
    getIcon(set: number, index: number): number;

    /**
     * Sets the icon of a button.
     *
     * @param icon the cmdicons frame, 0 to 0xFFFF
     */
    setIcon(set: number, index: number, icon: number): void;

    /**
     * Returns the condition of a button.
     *
     * @returns the condition id, -1 for an unknown function
     */
    getCondition(set: number, index: number): number;

    /**
     * Sets the condition of a button.
     *
     * @param condition the condition id, 0 to {@link CoreButtons.getConditionCount} minus 1
     */
    setCondition(set: number, index: number, condition: number): void;

    /**
     * Returns the value the condition of a button takes, a BYTE.
     */
    getConditionValue(set: number, index: number): number;

    /**
     * Sets the value the condition of a button takes.
     *
     * @param value the condition value, 0 to 255
     */
    setConditionValue(set: number, index: number, value: number): void;

    /**
     * Returns the action of a button.
     *
     * @returns the action id, -1 for an unknown function
     */
    getAction(set: number, index: number): number;

    /**
     * Sets the action of a button.
     *
     * @param action the action id, 0 to {@link CoreButtons.getActionCount} minus 1
     */
    setAction(set: number, index: number, action: number): void;

    /**
     * Returns the value the action of a button takes, a WORD.
     */
    getActionValue(set: number, index: number): number;

    /**
     * Sets the value the action of a button takes.
     *
     * @param value the action value, 0 to 0xFFFF
     */
    setActionValue(set: number, index: number, value: number): void;

    /**
     * Returns the string a button shows while enabled.
     *
     * @returns the 1-based stat_txt id, 0 for none
     */
    getEnabledString(set: number, index: number): number;

    /**
     * Sets the string a button shows while enabled.
     *
     * @param stringId the 1-based stat_txt id, 0 for none; 0 to 0xFFFF
     */
    setEnabledString(set: number, index: number, stringId: number): void;

    /**
     * Returns the string a button shows while disabled.
     *
     * @returns the 1-based stat_txt id, 0 for none
     */
    getDisabledString(set: number, index: number): number;

    /**
     * Sets the string a button shows while disabled.
     *
     * @param stringId the 1-based stat_txt id, 0 for none; 0 to 0xFFFF
     */
    setDisabledString(set: number, index: number, stringId: number): void;

    /**
     * Returns the text a button shows instead of its enabled string.
     *
     * @returns the text, null while the button shows its enabled string
     */
    getEnabledText(set: number, index: number): string | null;

    /**
     * Returns the text a button shows instead of its disabled string.
     *
     * @returns the text, null while the button shows its disabled string
     */
    getDisabledText(set: number, index: number): string | null;

    /**
     * Sets the texts a button shows instead of its strings. Both may hold line breaks and the text color control characters. The enabled text
     * has the button string format: the hotkey character, the button kind character, then the text.
     *
     * @param enabled  the enabled text, null for the enabled string
     * @param disabled the plain disabled text, null for the disabled string
     */
    setText(set: number, index: number, enabled: string | null, disabled: string | null): void;

    /**
     * Inserts a button into a set's array.
     *
     * @param set            the button set, 0 to 249
     * @param index          the 0-based index to insert before, or the button count to append
     * @param position       the card position, 1 to 9
     * @param icon           the cmdicons frame, 0 to 0xFFFF
     * @param condition      the condition id
     * @param conditionValue the condition value, 0 to 255
     * @param action         the action id
     * @param actionValue    the action value, 0 to 0xFFFF
     * @param enabledString  the 1-based stat_txt id of the enabled string, 0 for none
     * @param disabledString the 1-based stat_txt id of the disabled string, 0 for none
     */
    insert(set: number, index: number, position: number, icon: number, condition: number, conditionValue: number, action: number,
           actionValue: number, enabledString: number, disabledString: number): void;

    /**
     * Removes a button from a set's array.
     *
     * @param set   the button set, 0 to 249
     * @param index the 0-based button index in the set
     */
    remove(set: number, index: number): void;

    /**
     * Returns the number of button conditions.
     */
    getConditionCount(): number;

    /**
     * Returns the name of a button condition, such as "ScvCanGather".
     *
     * @param condition the condition id, 0 to {@link CoreButtons.getConditionCount} minus 1
     */
    getConditionName(condition: number): string;

    /**
     * Returns the number of button actions.
     */
    getActionCount(): number;

    /**
     * Returns the name of a button action.
     *
     * @param action the action id, 0 to {@link CoreButtons.getActionCount} minus 1
     */
    getActionName(action: number): string;
}

/**
 * The drawing of one space ({@link Drawing}) with numbers: {@code core.screen} draws in interface units over the console, {@code core.world} in
 * map pixels above the units and {@code core.minimap} in map pixels on the minimap. Coordinates and sizes may have fractions; a negative size or
 * thickness throws a RangeError. Colors are packed 0xAARRGGBB, 0 to 0xFFFFFFFF: a negative color throws a RangeError, a larger one a
 * TypeError. Fonts are 0 Small, 1 Medium, 2 Large, 3 Huge; alignments 0 Left, 1 Center, 2 Right. What one onRender call draws stays on screen
 * until the next call completes.
 */
interface CoreDrawing {
    /**
     * Fills a rectangle. Only in onRender.
     *
     * @param x    the left
     * @param y    the top
     * @param argb the color, 0xAARRGGBB
     */
    fillRectangle(x: number, y: number, width: number, height: number, argb: number): void;

    /**
     * Draws the outline of a rectangle. Only in onRender.
     *
     * @param x         the left
     * @param y         the top
     * @param argb      the color, 0xAARRGGBB
     * @param thickness the line thickness
     */
    drawRectangle(x: number, y: number, width: number, height: number, argb: number, thickness: number): void;

    /**
     * Fills a circle around a center. Only in onRender.
     *
     * @param argb the color, 0xAARRGGBB
     */
    fillCircle(x: number, y: number, radius: number, argb: number): void;

    /**
     * Draws the outline of a circle around a center. Only in onRender.
     *
     * @param argb      the color, 0xAARRGGBB
     * @param thickness the line thickness
     */
    drawCircle(x: number, y: number, radius: number, argb: number, thickness: number): void;

    /**
     * Draws a line between two points. Only in onRender.
     *
     * @param argb      the color, 0xAARRGGBB
     * @param thickness the line thickness
     */
    drawLine(x1: number, y1: number, x2: number, y2: number, argb: number, thickness: number): void;

    /**
     * Draws a point. Only in onRender.
     *
     * @param argb the color, 0xAARRGGBB
     */
    drawPoint(x: number, y: number, argb: number): void;

    /**
     * Draws a text at a point. Only in onRender.
     *
     * @param font  the font id, 0 to 3
     * @param argb  the color, 0xAARRGGBB
     * @param align the alignment id relative to x, 0 to 2
     */
    drawText(x: number, y: number, text: string, font: number, argb: number, align: number): void;

    /**
     * Draws an image scaled to a rectangle. Only in onRender.
     *
     * @param image   the image handle ({@link CoreResources.getImage}); -1 or a handle of no image throws a RangeError
     * @param x       the left
     * @param y       the top
     * @param opacity the opacity, 0 to 1; values outside are clamped
     */
    drawImage(image: number, x: number, y: number, width: number, height: number, opacity: number): void;

    /**
     * Draws a rectangle of an image, as a frame of a sprite sheet or a cropped image, scaled to a rectangle. Only in onRender.
     *
     * @param image        the image handle ({@link CoreResources.getImage}); -1 or a handle of no image throws a RangeError
     * @param sourceX      the left of the drawn part in image pixels
     * @param sourceY      the top of the drawn part in image pixels
     * @param sourceWidth  the width of the drawn part in image pixels, at least 0
     * @param sourceHeight the height of the drawn part in image pixels, at least 0; a part not inside the image throws a RangeError
     * @param x            the left
     * @param y            the top
     * @param opacity      the opacity, 0 to 1; values outside are clamped
     */
    drawImageRegion(image: number, sourceX: number, sourceY: number, sourceWidth: number, sourceHeight: number, x: number, y: number,
                    width: number, height: number, opacity: number): void;

    /**
     * Measures a text in the client's fonts. Local code only.
     *
     * @param font the font id, 0 to 3
     * @returns the width of the widest line in interface units, 0 before the client drew a frame
     */
    measureText(text: string, font: number): number;
}

/**
 * The game screen ({@code core.screen}, {@link Screen}) with numbers: the drawing of the screen space, the local screen's size and graphics, and
 * the classic 640 by 480 palette screen that EUD maps write. The classic screen pixels may be written from any code and stay shown.
 */
interface CoreScreen extends CoreDrawing {
    /**
     * Returns the width of the local screen in interface units. Local code only.
     */
    getWidth(): number;

    /**
     * Returns the height of the local screen in interface units. Local code only.
     */
    getHeight(): number;

    /**
     * Returns the real pixels per interface unit of the local screen. Local code only.
     */
    getScale(): number;

    /**
     * Returns the graphics the local machine shows. Local code only.
     *
     * @returns 0 SD, 1 HD, 2 HD2
     */
    getGraphics(): number;

    /**
     * Shows a palette pixel of the classic screen from now on. Throws a TypeError when the game has no classic screen.
     *
     * @param x            the column of the 640 by 480 screen
     * @param y            the row of the 640 by 480 screen
     * @param paletteIndex the palette index, 0 to 255
     */
    setPixel(x: number, y: number, paletteIndex: number): void;

    /**
     * Shows a rectangle of palette pixels of the classic screen from now on. Throws a TypeError when the game has no classic screen.
     *
     * @param x            the left column of the 640 by 480 screen
     * @param y            the top row of the 640 by 480 screen
     * @param width        the width in pixels
     * @param height       the height in pixels
     * @param paletteIndex the palette index, 0 to 255
     */
    fillPixels(x: number, y: number, width: number, height: number, paletteIndex: number): void;

    /**
     * Returns a color of the map's tileset palette.
     *
     * @param index the palette index, 0 to 255
     * @returns the opaque color, 0xAARRGGBB
     */
    getPaletteColor(index: number): number;
}

/**
 * The console ({@code core.console}, {@link Console}) with numbers: the dialogs by {@link ConsoleControl} id, the console images by
 * {@link ConsoleImage} id, the resource icons by frame (0 minerals, 1 to 3 gas and 4 to 6 supply of the Zerg, Terran and Protoss), images by
 * handle ({@link CoreResources.getImage}), -1 for none. What the script sets wins over what EUD memory gives, item by item. The functions work
 * anywhere and change no game state: shared code changes the console on every player's game, local code only on this machine.
 */
interface CoreConsole {
    /**
     * Shows or hides the whole console.
     */
    setVisible(visible: boolean): void;

    /**
     * Returns false when the script hid the console, true otherwise.
     */
    isVisible(): boolean;

    /**
     * Shows or hides a console dialog.
     *
     * @param dialog the dialog id ({@link ConsoleControl})
     */
    setDialogVisible(dialog: number, visible: boolean): void;

    /**
     * Returns false when the script hid a console dialog, true when it showed it or never set it.
     *
     * @param dialog the dialog id ({@link ConsoleControl})
     */
    isDialogVisible(dialog: number): boolean;

    /**
     * Places a console dialog by its top left.
     *
     * @param dialog the dialog id ({@link ConsoleControl})
     * @param x      the left column of the classic 640 by 480 screen
     * @param y      the top row of the classic 640 by 480 screen
     */
    setDialogPosition(dialog: number, x: number, y: number): void;

    /**
     * Shows or hides a control of a console dialog until {@link CoreConsole.restoreControl}.
     *
     * @param dialog  the dialog id ({@link ConsoleControl})
     * @param control the control's identifier in the dialog's definition, -32768 to 32767
     */
    setControlVisible(dialog: number, control: number, visible: boolean): void;

    /**
     * Places a control of a console dialog by its top left until {@link CoreConsole.restoreControl}.
     *
     * @param dialog  the dialog id ({@link ConsoleControl})
     * @param control the control's identifier in the dialog's definition, -32768 to 32767
     * @param x       the left column of the classic 640 by 480 screen
     * @param y       the top row of the classic 640 by 480 screen
     */
    setControlPosition(dialog: number, control: number, x: number, y: number): void;

    /**
     * Forgets the visibility and the position set on a control, so that EUD memory drives it again.
     *
     * @param dialog  the dialog id ({@link ConsoleControl})
     * @param control the control's identifier in the dialog's definition, -32768 to 32767
     */
    restoreControl(dialog: number, control: number): void;

    /**
     * Sets the interface edge that the classic screen of a dialog's positions, and those of its controls, stands at.
     *
     * @param anchor 0 the left edge, 1 centered (the installed place), 2 the right edge; another value throws a RangeError
     */
    setDialogAnchor(dialog: number, anchor: number): void;

    /**
     * Sets the side of the drawn minimap area. The minimap dialog grows or shrinks with it by size - 128 in width and height, its controls kept at
     * their offsets from its top left: a dialog placed by {@link CoreConsole.setDialogPosition} keeps its top left, an unplaced one its bottom
     * left.
     *
     * @param size the side in interface units, 1 to 480 (128 installed); another value throws a RangeError
     */
    setMinimapSize(size: number): void;

    /**
     * @returns the side of the drawn minimap area in interface units
     */
    getMinimapSize(): number;

    /**
     * Local code only.
     *
     * @returns the dialog's rectangle as the client last drew it, [x, y, width, height] in interface units, or null while it is hidden or not
     * drawn
     */
    getDialogRectangle(dialog: number): number[] | null;

    /**
     * Local code only.
     *
     * @param control the control's identifier in the dialog's definition, -32768 to 32767 ({@link ConsoleChild} names them)
     * @returns the control's rectangle as the client last drew it, [x, y, width, height] in interface units, or null while it or its dialog is
     * hidden, the dialog has no such control or the console is not drawn
     */
    getControlRectangle(dialog: number, control: number): number[] | null;

    /**
     * Local code only.
     *
     * @returns the rectangle of the minimap area (control 1 of the minimap dialog) as the client last drew it, [x, y, width, height] in interface
     * units, the minimap size wide and high, or null while it or the minimap dialog is hidden or the console is not drawn
     */
    getMinimapRectangle(): number[] | null;

    /**
     * Replaces a console art file with an image.
     *
     * @param consoleImage the console image id ({@link ConsoleImage})
     * @param image        the image handle, -1 for the installed file
     */
    replaceImage(consoleImage: number, image: number): void;

    /**
     * Replaces a resource icon of the console with an image.
     *
     * @param icon  the icon frame, 0 to 6
     * @param image the image handle, -1 for the icon of EUD memory or the installed one
     */
    setResourceIcon(icon: number, image: number): void;
}

/**
 * The game strings ({@code core.strings}). The functions work anywhere and change no game state: shared code changes the texts on every
 * player's game, local code only on this machine.
 */
interface CoreStrings {
    /**
     * Shows a text instead of a stat_txt string. The text wins over the installed table and the table EUD memory commits; the name string of a
     * unit type also wins over the map's name of the type.
     *
     * @param id   the 1-based stat_txt id, 1 to 65535
     * @param text the text, null to show the game's string again
     */
    setStatText(id: number, text: string | null): void;
}

/**
 * The message lines on the screen ({@code core.messages}): the messages take lines 0 to 10 in turn from the next line, line 12 holds the status
 * texts. A write stores the bytes as an EUD write does: the line keeps the expiry time and the style of the message it held, so a line whose
 * message expired is cleared at the next drawn frame. The functions work anywhere and change no game state: shared code changes the lines on
 * every player's game, local code only on this machine.
 */
interface CoreMessages {
    /**
     * Empties every message line.
     */
    clear(): void;

    /**
     * Returns the number of message lines, 13.
     */
    getLineCount(): number;

    /**
     * Returns the text of a message line up to its first zero byte.
     *
     * @param line the line, 0 to 12
     */
    getLine(line: number): string;

    /**
     * Stores a text in a message line: its UTF-8 bytes, cut to 217, and a zero byte.
     *
     * @param line the line, 0 to 12
     */
    setLine(line: number, text: string): void;

    /**
     * Returns the line the next message is written to.
     *
     * @returns the next line, a byte; 11 or more writes line 0
     */
    getNextLine(): number;

    /**
     * Sets the line the next message is written to.
     *
     * @param value the next line, 0 to 255; 11 or more writes line 0
     */
    setNextLine(value: number): void;
}

/**
 * The game sounds ({@code core.sound}). The functions work anywhere and change no game state: shared code plays a sound on every player's game,
 * local code only on this machine.
 */
interface CoreSound {
    /**
     * Plays a sound at a map position as an animation script does: louder the nearer the position is to the screen, whether the players see the
     * position or not. A sound played by local code plays with the sounds of the next frame start.
     *
     * @param soundId the sfxdata.dat sound id, 0 to 1143
     * @param x       the map x in pixels
     * @param y       the map y in pixels
     */
    playAt(soundId: number, x: number, y: number): void;
}

/**
 * The namespaces of the lower layer.
 */
interface Core {
    /** The game: frame, switches, countdown timer, pause, speed and game settings. */
    readonly game: CoreGame;
    /** The players: resources, counters, scores, upgrades, technologies, selection, shared vision and colors. */
    readonly player: CorePlayer;
    /** The units by slot id. */
    readonly unit: CoreUnit;
    /** The locations of the map. */
    readonly location: CoreLocation;
    /** The tiles of the map. */
    readonly terrain: CoreTerrain;
    /** The creep of the map. */
    readonly creep: CoreCreep;
    /** The sprites by slot id. */
    readonly sprite: CoreSprite;
    /** The images by slot id. */
    readonly image: CoreImage;
    /** The bullets by slot id. */
    readonly bullet: CoreBullet;
    /** The players' sight and the fog of war. */
    readonly vision: CoreVision;
    /** The leaderboard. */
    readonly leaderboard: CoreLeaderboard;
    /** The combat tables: damage multipliers and flee distances. */
    readonly combat: CoreCombat;
    /** The state of the computer players. */
    readonly ai: CoreAi;
    /** The requirement scripts and the requirement tests. */
    readonly requirements: CoreRequirements;
    /** The names of the unit types. */
    readonly unitType: CoreTypes;
    /** The names of the weapon types. */
    readonly weaponType: CoreTypes;
    /** The names of the flingy types. */
    readonly flingyType: CoreTypes;
    /** The names of the sprite types. */
    readonly spriteType: CoreTypes;
    /** The names of the image types, their anim image types and damage overlays. */
    readonly imageType: CoreImageTypes;
    /** The names of the upgrade types. */
    readonly upgradeType: CoreTypes;
    /** The names of the technology types. */
    readonly techType: CoreTypes;
    /** The names of the orders. */
    readonly orderType: CoreTypes;
    /** The DAT tables of the current game. */
    readonly dat: CoreDat;
    /** The events of the frame. */
    readonly events: CoreEvents;
    /** The script commands the players send. */
    readonly command: CoreCommand;
    /** The resources of the map script: GRP, anim and image files. */
    readonly resources: CoreResources;
    /** The graphics replacements of the game's images. */
    readonly graphics: CoreGraphics;
    /** The information only this machine has. */
    readonly local: CoreLocal;
    /** The game screen: drawing in interface units and the classic palette screen. */
    readonly screen: CoreScreen;
    /** The drawing in map pixels above the units. */
    readonly world: CoreDrawing;
    /** The drawing in map pixels on the minimap. */
    readonly minimap: CoreDrawing;
    /** The console. */
    readonly console: CoreConsole;
    /** The game strings. */
    readonly strings: CoreStrings;
    /** The message lines on the screen. */
    readonly messages: CoreMessages;
    /** The game sounds. */
    readonly sound: CoreSound;
    /** The command card's button set table. */
    readonly buttons: CoreButtons;
}

/**
 * The lower layer of the script API: ids, raw values and records over the game.
 */
declare const core: Core;
