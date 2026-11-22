package org.fogofwarlabs.sc4j.scr.game.combat;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.entity.Projectile;
import org.fogofwarlabs.sc4j.engine.movement.MotionProfileProperties;
import org.fogofwarlabs.sc4j.engine.movement.MovementFlags;
import org.fogofwarlabs.sc4j.engine.movement.StandardMotionStep;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.content.ScrWeaponIds;
import org.fogofwarlabs.sc4j.scr.data.format.replay.ReplayLimits;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.fogofwarlabs.sc4j.scr.data.type.ScrFlingyTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.ScrWeaponTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitTypeFlags;
import org.fogofwarlabs.sc4j.scr.data.type.constant.WeaponBehaviors;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrSimulationPlugins;
import org.fogofwarlabs.sc4j.scr.game.movement.FlingyMotionUtils;
import org.fogofwarlabs.sc4j.scr.game.save.SaveReferences;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.sprite.script.IscriptExecutor;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.terrain.GameMap;
import org.fogofwarlabs.sc4j.scr.game.terrain.MapTileFlags;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitStatusFlags;
import org.fogofwarlabs.sc4j.scr.game.unit.finder.UnitSpatialIndex;
import org.fogofwarlabs.sc4j.scr.game.visibility.SpriteVisibility;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Retains pooled projectile storage and advances projectile image and order lifetimes.
 */
public final class ProjectilePhase {
    /**
     * The size of a saved bullet record, the classic {@code 112}-byte bullet ({@link Context#writeSaveRecord}).
     */
    public static final int SAVE_RECORD_SIZE = 0x70;

    /**
     * The miss chance against a target on higher ground or under cover (out of {@code 256}).
     */
    static final int MISS_CHANCE_COVERED = 119;
    /**
     * The miss chance against a target under a Dark Swarm: every attack misses.
     */
    static final int MISS_CHANCE_ALWAYS = 255;
    /**
     * The hit flag of a bullet that missed its target.
     */
    static final int HIT_FLAG_MISSED = 0x1;
    /**
     * The hit flag of a bullet of a hallucination: it deals no damage.
     */
    static final int HIT_FLAG_HALLUCINATION = 0x2;

    /**
     * The list link and saved bullet index of no bullet.
     */
    private static final int NO_BULLET = -1;

    private static final int BULLET_PREVIOUS = 0x00;
    private static final int BULLET_NEXT = 0x04;
    /**
     * The offset of the thingy life DWORD, {@link #BULLET_CREATED_LIFE} in every saved bullet.
     */
    private static final int BULLET_LIFE = 0x08;
    /**
     * The life value the flingy initializer stores.
     */
    private static final int BULLET_CREATED_LIFE = 1;
    private static final int BULLET_SPRITE = 0x0C;
    private static final int BULLET_MOVE_TARGET_X = 0x10;
    private static final int BULLET_MOVE_TARGET_Y = 0x12;
    private static final int BULLET_WAYPOINT_X = 0x18;
    private static final int BULLET_WAYPOINT_Y = 0x1A;
    private static final int BULLET_TARGET_WAYPOINT_X = 0x1C;
    private static final int BULLET_TARGET_WAYPOINT_Y = 0x1E;
    private static final int BULLET_MOVEMENT_FLAGS = 0x20;
    private static final int BULLET_HEADING = 0x21;
    private static final int BULLET_TURN_RATE = 0x22;
    private static final int BULLET_NEXT_VELOCITY_DIRECTION = 0x23;
    private static final int BULLET_FLINGY_TYPE = 0x24;
    private static final int BULLET_MOVEMENT_CONTROL = 0x27;
    private static final int BULLET_POSITION_X = 0x28;
    private static final int BULLET_POSITION_Y = 0x2A;
    private static final int BULLET_EXACT_X = 0x2C;
    private static final int BULLET_EXACT_Y = 0x30;
    private static final int BULLET_TOP_SPEED = 0x34;
    private static final int BULLET_CURRENT_SPEED = 0x38;
    private static final int BULLET_NEXT_SPEED = 0x3C;
    private static final int BULLET_VELOCITY_X = 0x40;
    private static final int BULLET_VELOCITY_Y = 0x44;
    private static final int BULLET_ACCELERATION = 0x48;
    private static final int BULLET_CURRENT_VELOCITY_DIRECTION = 0x4A;
    private static final int BULLET_DESIRED_VELOCITY_DIRECTION = 0x4B;
    private static final int BULLET_OWNER = 0x4C;
    private static final int BULLET_STATE = 0x4D;
    private static final int BULLET_ORDER_SIGNAL = 0x4F;
    /**
     * The offset of the order target type WORD, {@link ScrUnitIds#NONE} for every bullet order.
     */
    private static final int BULLET_ORDER_TARGET_TYPE = 0x50;
    private static final int BULLET_TARGET_X = 0x58;
    private static final int BULLET_TARGET_Y = 0x5A;
    private static final int BULLET_TARGET = 0x5C;
    private static final int BULLET_WEAPON = 0x60;
    private static final int BULLET_REMAINING_TIME = 0x61;
    private static final int BULLET_HIT_FLAGS = 0x62;
    private static final int BULLET_REMAINING_BOUNCES = 0x63;
    private static final int BULLET_OWNER_UNIT = 0x64;
    private static final int BULLET_PREVIOUS_BOUNCE_TARGET = 0x68;
    private static final int BULLET_NEAR_TARGET_INDEX = 0x6C;

    private static final int STATE_INIT = 0;
    private static final int STATE_MOVE = 1;
    private static final int STATE_FOLLOW = 2;
    private static final int STATE_BOUNCE = 3;
    private static final int STATE_DAMAGE_OVER_TIME = 4;
    private static final int STATE_DYING = 5;
    private static final int STATE_HIT_NEAR_TARGET = 6;

    /**
     * The random call site of the launch spin direction.
     */
    private static final int LAUNCH_SPIN_RANDOM_SOURCE = 0;
    /**
     * The bit of the launch spin random value that selects a clockwise spin.
     */
    private static final int CLOCKWISE_SPIN_BIT = 1;
    /**
     * The random call site of the miss roll.
     */
    private static final int MISS_RANDOM_SOURCE = 1;
    /**
     * The {@code flingy.dat} entry {@code 0}, with which a weapon launches no bullet.
     */
    private static final int NO_FLINGY_TYPE = 0;
    /**
     * The {@code weapons.dat} damage factor of a weapon that launches two bullets per use.
     */
    private static final int TWO_BULLET_DAMAGE_FACTOR = 2;
    /**
     * The number of bullets a weapon with {@link #TWO_BULLET_DAMAGE_FACTOR} launches per use.
     */
    private static final int TWO_BULLET_COUNT = 2;
    /**
     * The number of bullets every other weapon with a flingy launches per use.
     */
    private static final int SINGLE_BULLET_COUNT = 1;
    /**
     * The fraction of the created bullet capacity from which no further Halo Rockets bullet is created ({@link Context#getHaloRocketsLimit()}).
     */
    private static final float HALO_ROCKETS_LIMIT_FRACTION = 0.8f;
    /**
     * The distance in pixels a missed bullet lands short of its target.
     */
    private static final int MISS_DISTANCE = 30;
    /**
     * The pixels a bullet that extends to the maximum range flies beyond the weapon's maximum range.
     */
    private static final int MAXIMUM_RANGE_EXTENSION = 20;
    /**
     * The frames between two hits of a bullet that persists on the target position.
     */
    private static final int PERSIST_HIT_INTERVAL = 7;
    /**
     * The number of targets a bouncing bullet hits.
     */
    private static final int BOUNCE_COUNT = 3;
    /**
     * The divisor a bouncing bullet's damage takes for each earlier target.
     */
    private static final int BOUNCE_DAMAGE_DIVISOR = 3;
    /**
     * The half size in pixels of the box searched for the next target of a bouncing bullet.
     */
    private static final int BOUNCE_SEARCH_RADIUS = 96;
    /**
     * The number of positions around the target that bullets attacking near the target cycle through.
     */
    private static final int NEAR_TARGET_POSITIONS = 14;
    /**
     * The number of entries of one position in {@link #NEAR_TARGET_OFFSETS}.
     */
    private static final int NEAR_TARGET_COORDINATE_COUNT = 2;
    /**
     * The index of the Y offset within one position of {@link #NEAR_TARGET_OFFSETS}, after the X offset.
     */
    private static final int NEAR_TARGET_Y_ENTRY = 1;

    /**
     * The X and Y pixel offsets of the positions around the target ({@link #NEAR_TARGET_POSITIONS} pairs).
     */
    private static final int @NotNull [] NEAR_TARGET_OFFSETS = {
            -1, -1,
            -51, -31,
            -7, -12,
            36, 52,
            49, -25,
            -40, 46,
            4, 19,
            -28, -50,
            -50, 15,
            54, 13,
            -11, 53,
            -15, 9,
            19, -50,
            17, -8
    };

    /**
     * The mask of an unsigned byte value.
     */
    private static final int BYTE_MASK = 0xFF;
    /**
     * The mask of an unsigned WORD value.
     */
    private static final int WORD_MASK = 0xFFFF;
    /**
     * The multiplier of the state hash.
     */
    private static final int HASH_MULTIPLIER = 31;
    /**
     * The fraction bits of a fixed-point position, speed or distance.
     */
    private static final int FIXED_POINT_FRACTION_BITS = 8;

    /**
     * Retains bounded bullet storage, intrusive lists and alternating launch direction.
     */
    public static final class Context {
        /**
         * The capacity the context was created with: the bullet limit, unchanged by {@link #expandFreeList(int)}.
         */
        private final int nativeCapacity;

        private @NotNull Bullet @NotNull [] bullets;

        /**
         * Whether the empty active lists were established before the first frame.
         */
        private boolean emptyListsInitialized;
        private int firstFree;
        private int firstActive;
        private int activeCount;
        /**
         * The generation of the latest bullet that joined the active list, {@code 0} before the first.
         */
        private long generation;

        /**
         * The source of the previous spinning launch, whose next launch spins the other way.
         */
        private @Nullable Unit previousSource;
        private boolean previousClockwise;
        /**
         * The bullet whose image and order run now, the subject of the bullet script commands.
         */
        private @Nullable Bullet currentBullet;

        /**
         * Creates a {@link Context} instance with the classic bullet capacity.
         */
        public Context() {
            this(ReplayLimits.CLASSIC_BULLET_CAPACITY);
        }

        /**
         * Creates a {@link Context} instance with the replay-established capacity.
         *
         * @param capacity the effective bullet limit
         * @throws IllegalArgumentException if {@code capacity} is not positive
         */
        public Context(int capacity) {
            if (capacity <= 0) {
                throw Exceptions.newIllegalArgumentException("Bullet capacity must be positive.");
            }

            this.nativeCapacity = capacity;

            this.bullets = new Bullet[capacity];

            for (int index = 0; index < capacity; index++) {
                this.bullets[index] = new Bullet(index);
            }

            this.bullets[0].next = capacity > 1 ? capacity - 1 : NO_BULLET;

            for (int index = 1; index < capacity; index++) {
                this.bullets[index].previous = index == capacity - 1 ? 0 : index + 1;
                this.bullets[index].next     = index == 1 ? NO_BULLET : index - 1;
            }

            this.emptyListsInitialized = false;
            this.firstFree             = 0;
            this.firstActive           = NO_BULLET;
            this.activeCount           = 0;
            this.generation            = 0;

            this.previousSource    = null;
            this.previousClockwise = false;
            this.currentBullet     = null;
        }

        /**
         * @return the first free bullet index, or {@code -1} when exhausted
         */
        public int getFirstFree() {
            return this.firstFree;
        }

        /**
         * @param index the bullet index
         * @return the index of the following bullet in its list, or {@code -1}
         */
        public int getNext(int index) {
            return this.bullets[index].next;
        }

        /**
         * @return the index of the first active bullet, or {@code -1} when none is active
         */
        public int getFirstActive() {
            return this.firstActive;
        }

        /**
         * @param index the bullet index, from {@code 0} to {@link #getCapacity()} minus one
         * @return the bullet entry at that index, active or free
         */
        public @NotNull Bullet getBullet(int index) {
            return this.bullets[index];
        }

        /**
         * @return the number of bullet entries, including the ones a free-list commit added
         */
        public int getCapacity() {
            return this.bullets.length;
        }

        /**
         * @return the number of active bullets
         */
        public int getActiveCount() {
            return this.activeCount;
        }

        /**
         * Sets the active bullet count, as an EUD write of the classic active count does (generic host region): the Halo Rockets creation limit
         * ({@link #getHaloRocketsLimit()}) then compares the new value as an unsigned DWORD, so a count with the sign bit set refuses the bullet.
         *
         * @param value the active bullet count DWORD
         */
        public void setActiveCount(int value) {
            this.activeCount = value;
        }

        /**
         * @return the active bullet count from which no Halo Rockets bullet is created: eight tenths of the capacity the context was created with,
         * multiplied in single precision and truncated toward zero
         */
        public int getHaloRocketsLimit() {
            return (int) (this.nativeCapacity * HALO_ROCKETS_LIMIT_FRACTION);
        }

        /**
         * @param index the bullet index
         * @return {@code true} if the bullet has a sprite, the bullets a saved game keeps, {@code false} otherwise
         */
        public boolean hasSprite(int index) {
            return this.bullets[index].sprite != null;
        }


        /**
         * Establishes empty active projectile lists before the first frame.
         *
         * @throws IllegalStateException if bullets are already active
         */
        public void initializeEmptyLists() {
            if (this.activeCount != 0) {
                throw Exceptions.newIllegalStateException("Cannot initialize active projectile storage.");
            }

            this.emptyListsInitialized = true;
        }

        /**
         * Appends {@code count + 1} initialized entries and replaces the free head with the first new entry. Later entries are inserted after that
         * head, giving {@code new[0], new[count], ..., new[1]}. Previously free entries leave the free list; active entries keep their list and
         * return to the new free list when released.
         *
         * @param count the length of the classic chain the map built
         * @return the pool index of {@code new[0]}
         */
        public int expandFreeList(int count) {
            int first = this.bullets.length;

            this.bullets = Arrays.copyOf(this.bullets, first + count + 1);

            for (int index = first; index < this.bullets.length; index++) {
                this.bullets[index] = new Bullet(index);
            }

            Bullet head = this.bullets[first];

            head.previous  = NO_BULLET;
            head.next      = NO_BULLET;
            this.firstFree = first;

            for (int entry = 1; entry <= count; entry++) {
                Bullet bullet = this.bullets[first + entry];

                bullet.previous = first;
                bullet.next     = head.next;

                if (head.next >= 0) {
                    this.bullets[head.next].previous = first + entry;
                }

                head.next = first + entry;
            }

            return first;
        }

        /**
         * Removes the source and target references cleared by unit hiding and destruction.
         *
         * @param target the unit leaving targetable world lists
         */
        public void removeTargetReferences(@NotNull Unit target) {
            for (int index = this.firstActive; index >= 0; index = this.bullets[index].next) {
                Bullet bullet = this.bullets[index];

                if (bullet.ownerUnit == target) {
                    bullet.ownerUnit = null;
                }

                if (bullet.targetUnit == target) {
                    bullet.targetUnit = null;
                }
            }
        }

        /**
         * Writes a bullet into its saved record, the classic {@code 112}-byte bullet of the bullet section: the flingy part, then the bullet fields,
         * with the list links as one-based indices. The weapon behavior is not part of it; a read bullet takes it from {@code weapons.dat}.
         *
         * @param index      the bullet index
         * @param record     the record, little-endian, at offset {@code 0}
         * @param references the reference conversions of the game
         */
        public void writeSaveRecord(int index, @NotNull ByteBuffer record, @NotNull SaveReferences references) {
            Bullet bullet = this.bullets[index];

            record.putInt(BULLET_PREVIOUS, bullet.previous + 1);
            record.putInt(BULLET_NEXT, bullet.next + 1);
            record.putInt(BULLET_LIFE, BULLET_CREATED_LIFE);
            record.putInt(BULLET_SPRITE, SaveReferences.encodeSprite(bullet.sprite));
            record.putShort(BULLET_MOVE_TARGET_X, (short) bullet.getMovementDestinationX());
            record.putShort(BULLET_MOVE_TARGET_Y, (short) bullet.getMovementDestinationY());
            record.putShort(BULLET_WAYPOINT_X, (short) bullet.getMovementWaypointX());
            record.putShort(BULLET_WAYPOINT_Y, (short) bullet.getMovementWaypointY());
            record.putShort(BULLET_TARGET_WAYPOINT_X, (short) bullet.getDirectionTargetX());
            record.putShort(BULLET_TARGET_WAYPOINT_Y, (short) bullet.getDirectionTargetY());
            record.put(BULLET_MOVEMENT_FLAGS, (byte) bullet.getMovementFlags());
            record.put(BULLET_HEADING, (byte) bullet.getHeading());
            record.put(BULLET_TURN_RATE, (byte) bullet.getTurnRate());
            record.put(BULLET_NEXT_VELOCITY_DIRECTION, (byte) bullet.getTargetHeading());
            record.putShort(BULLET_FLINGY_TYPE, (short) bullet.flingyType);
            record.put(BULLET_MOVEMENT_CONTROL, (byte) bullet.getMovementControl());
            record.putShort(BULLET_POSITION_X, (short) bullet.getPositionX());
            record.putShort(BULLET_POSITION_Y, (short) bullet.getPositionY());
            record.putInt(BULLET_EXACT_X, bullet.getFixedPositionX());
            record.putInt(BULLET_EXACT_Y, bullet.getFixedPositionY());
            record.putInt(BULLET_TOP_SPEED, bullet.getTopSpeed());
            record.putInt(BULLET_CURRENT_SPEED, bullet.getCurrentSpeed());
            record.putInt(BULLET_NEXT_SPEED, bullet.getNextSpeed());
            record.putInt(BULLET_VELOCITY_X, bullet.getVelocityX());
            record.putInt(BULLET_VELOCITY_Y, bullet.getVelocityY());
            record.putShort(BULLET_ACCELERATION, (short) bullet.getAcceleration());
            record.put(BULLET_CURRENT_VELOCITY_DIRECTION, (byte) bullet.getMovementDirection());
            record.put(BULLET_DESIRED_VELOCITY_DIRECTION, (byte) bullet.getDesiredMovementDirection());

            record.put(BULLET_OWNER, (byte) bullet.owner);
            record.put(BULLET_STATE, (byte) bullet.orderState);
            record.put(BULLET_ORDER_SIGNAL, (byte) bullet.orderSignal);
            record.putShort(BULLET_ORDER_TARGET_TYPE, (short) ScrUnitIds.NONE);
            record.putShort(BULLET_TARGET_X, (short) bullet.targetX);
            record.putShort(BULLET_TARGET_Y, (short) bullet.targetY);
            record.putInt(BULLET_TARGET, references.encodeUnit(bullet.targetUnit));

            record.put(BULLET_WEAPON, (byte) bullet.weapon);
            record.put(BULLET_REMAINING_TIME, (byte) bullet.remainingTime);
            record.put(BULLET_HIT_FLAGS, (byte) bullet.hitFlags);
            record.put(BULLET_REMAINING_BOUNCES, (byte) bullet.remainingBounces);
            record.putInt(BULLET_OWNER_UNIT, references.encodeUnit(bullet.ownerUnit));
            record.putInt(BULLET_PREVIOUS_BOUNCE_TARGET, references.encodeUnit(bullet.previousBounceTarget));
            record.put(BULLET_NEAR_TARGET_INDEX, (byte) bullet.nearTargetIndex);
        }

        /**
         * Reads a bullet from its saved record ({@link #writeSaveRecord}) into a slot that {@link #clear} zeroed; the list links are rebuilt by
         * {@link #rebuildLists}.
         *
         * @param index      the bullet index
         * @param record     the record, little-endian, at offset {@code 0}
         * @param weapons    the weapon type table, which gives the behavior of the bullet's weapon
         * @param references the reference conversions of the game
         */
        public void readSaveRecord(int index, @NotNull ByteBuffer record, @NotNull ScrWeaponTypeView weapons, @NotNull SaveReferences references) {
            Bullet bullet = this.bullets[index];

            bullet.sprite = references.decodeSprite(record.getInt(BULLET_SPRITE));
            bullet.setMovementDestinationX(record.getShort(BULLET_MOVE_TARGET_X));
            bullet.setMovementDestinationY(record.getShort(BULLET_MOVE_TARGET_Y));
            bullet.setMovementWaypointX(record.getShort(BULLET_WAYPOINT_X));
            bullet.setMovementWaypointY(record.getShort(BULLET_WAYPOINT_Y));
            bullet.setDirectionTargetX(record.getShort(BULLET_TARGET_WAYPOINT_X));
            bullet.setDirectionTargetY(record.getShort(BULLET_TARGET_WAYPOINT_Y));
            bullet.setMovementFlags(record.get(BULLET_MOVEMENT_FLAGS) & BYTE_MASK);
            bullet.setHeading(record.get(BULLET_HEADING) & BYTE_MASK);
            bullet.setTurnRate(record.get(BULLET_TURN_RATE) & BYTE_MASK);
            bullet.setTargetHeading(record.get(BULLET_NEXT_VELOCITY_DIRECTION) & BYTE_MASK);
            bullet.flingyType = record.getShort(BULLET_FLINGY_TYPE) & WORD_MASK;
            bullet.setMovementControl(record.get(BULLET_MOVEMENT_CONTROL) & BYTE_MASK);
            bullet.setPosition(record.getShort(BULLET_POSITION_X), record.getShort(BULLET_POSITION_Y));
            bullet.setFixedPositionX(record.getInt(BULLET_EXACT_X));
            bullet.setFixedPositionY(record.getInt(BULLET_EXACT_Y));
            bullet.setTopSpeed(record.getInt(BULLET_TOP_SPEED));
            bullet.setCurrentSpeed(record.getInt(BULLET_CURRENT_SPEED));
            bullet.setNextSpeed(record.getInt(BULLET_NEXT_SPEED));
            bullet.setVelocityX(record.getInt(BULLET_VELOCITY_X));
            bullet.setVelocityY(record.getInt(BULLET_VELOCITY_Y));
            bullet.setAcceleration(record.getShort(BULLET_ACCELERATION) & WORD_MASK);
            bullet.setMovementDirection(record.get(BULLET_CURRENT_VELOCITY_DIRECTION) & BYTE_MASK);
            bullet.setDesiredMovementDirection(record.get(BULLET_DESIRED_VELOCITY_DIRECTION) & BYTE_MASK);

            bullet.owner       = record.get(BULLET_OWNER) & BYTE_MASK;
            bullet.orderState  = record.get(BULLET_STATE) & BYTE_MASK;
            bullet.orderSignal = record.get(BULLET_ORDER_SIGNAL) & BYTE_MASK;
            bullet.targetX     = record.getShort(BULLET_TARGET_X);
            bullet.targetY     = record.getShort(BULLET_TARGET_Y);
            bullet.targetUnit  = references.decodeUnit(record.getInt(BULLET_TARGET));

            bullet.weapon               = record.get(BULLET_WEAPON) & BYTE_MASK;
            bullet.behavior             = weapons.getBehavior(bullet.weapon);
            bullet.remainingTime        = record.get(BULLET_REMAINING_TIME) & BYTE_MASK;
            bullet.hitFlags             = record.get(BULLET_HIT_FLAGS) & BYTE_MASK;
            bullet.remainingBounces     = record.get(BULLET_REMAINING_BOUNCES) & BYTE_MASK;
            bullet.ownerUnit            = references.decodeLiveUnit(record.getInt(BULLET_OWNER_UNIT));
            bullet.previousBounceTarget = references.decodeLiveUnit(record.getInt(BULLET_PREVIOUS_BOUNCE_TARGET));
            bullet.nearTargetIndex      = record.get(BULLET_NEAR_TARGET_INDEX) & BYTE_MASK;
        }

        /**
         * Resets the current bullet, launch alternation, active count and both list heads, then replaces every bullet with an initialized entry
         * before restoring a saved game.
         */
        public void clear() {
            this.currentBullet     = null;
            this.previousClockwise = false;
            this.previousSource    = null;

            this.activeCount = 0;
            this.firstActive = NO_BULLET;
            this.firstFree   = NO_BULLET;

            for (int index = 0; index < this.bullets.length; index++) {
                this.bullets[index] = new Bullet(index);
            }
        }

        /**
         * Rebuilds the free and active lists after a saved game's bullets were read: the bullets without a sprite form the free list and the bullets
         * with one the active list, each in ascending order (the first is head and tail, every later one is appended after the tail), and the active
         * count counts the latter.
         */
        public void rebuildLists() {
            int lastFree = NO_BULLET;
            int lastActive = NO_BULLET;

            for (Bullet bullet : this.bullets) {
                boolean active = bullet.sprite != null;
                int last = active ? lastActive : lastFree;

                bullet.previous = last;
                bullet.next     = NO_BULLET;
                bullet.active   = active;

                if (last >= 0) {
                    this.bullets[last].next = bullet.index;
                }

                if (active) {
                    if (last < 0) {
                        this.firstActive = bullet.index;
                    }

                    lastActive = bullet.index;
                    this.activeCount++;
                } else {
                    if (last < 0) {
                        this.firstFree = bullet.index;
                    }

                    lastFree = bullet.index;
                }
            }

            this.emptyListsInitialized = true;
        }

        /**
         * Folds the projectile lists and the retained bullet state into a state hash.
         *
         * @param hash the preceding domain hash
         * @return the hash including projectile lists and retained bullet state
         */
        public int computeStateHash(int hash) {
            hash = HASH_MULTIPLIER * hash + (this.emptyListsInitialized ? 1 : 0);
            hash = HASH_MULTIPLIER * hash + this.firstFree;
            hash = HASH_MULTIPLIER * hash + this.firstActive;
            hash = HASH_MULTIPLIER * hash + this.activeCount;
            hash = HASH_MULTIPLIER * hash + (this.previousSource == null ? -1 : this.previousSource.getIndex());
            hash = HASH_MULTIPLIER * hash + (this.previousClockwise ? 1 : 0);

            for (Bullet bullet : this.bullets) {
                hash = HASH_MULTIPLIER * hash + bullet.next;
                hash = HASH_MULTIPLIER * hash + bullet.previous;
                hash = HASH_MULTIPLIER * hash + (bullet.sprite == null ? -1 : bullet.sprite.getPoolIndex());
                hash = HASH_MULTIPLIER * hash + (bullet.ownerUnit == null ? -1 : bullet.ownerUnit.getIndex());
                hash = HASH_MULTIPLIER * hash + (bullet.targetUnit == null ? -1 : bullet.targetUnit.getIndex());
                hash = HASH_MULTIPLIER * hash + bullet.owner;
                hash = HASH_MULTIPLIER * hash + bullet.weapon;
                hash = HASH_MULTIPLIER * hash + bullet.behavior;
                hash = HASH_MULTIPLIER * hash + bullet.orderState;
                hash = HASH_MULTIPLIER * hash + bullet.orderSignal;
                hash = HASH_MULTIPLIER * hash + bullet.getHeading();
                hash = HASH_MULTIPLIER * hash + bullet.remainingTime;
                hash = HASH_MULTIPLIER * hash + bullet.hitFlags;
                hash = HASH_MULTIPLIER * hash + bullet.targetX;
                hash = HASH_MULTIPLIER * hash + bullet.targetY;
                hash = HASH_MULTIPLIER * hash + bullet.remainingBounces;
                hash = HASH_MULTIPLIER * hash + (bullet.previousBounceTarget == null ? -1 : bullet.previousBounceTarget.getIndex());
                hash = HASH_MULTIPLIER * hash + bullet.nearTargetIndex;
                hash = HASH_MULTIPLIER * hash + bullet.getMovementFlags();
                hash = HASH_MULTIPLIER * hash + bullet.getPositionX();
                hash = HASH_MULTIPLIER * hash + bullet.getPositionY();
                hash = HASH_MULTIPLIER * hash + bullet.getFixedPositionX();
                hash = HASH_MULTIPLIER * hash + bullet.getFixedPositionY();
                hash = HASH_MULTIPLIER * hash + (short) bullet.getMovementDestinationX();
                hash = HASH_MULTIPLIER * hash + (short) bullet.getMovementDestinationY();
                hash = HASH_MULTIPLIER * hash + bullet.getTargetHeading();
                hash = HASH_MULTIPLIER * hash + bullet.getMovementDirection();
                hash = HASH_MULTIPLIER * hash + bullet.getCurrentSpeed();
                hash = HASH_MULTIPLIER * hash + bullet.getNextSpeed();
            }

            return hash;
        }
    }

    /**
     * Holds the state of one bullet: its list links, owner and weapon, order state, target and native flingy type WORD; its position and motion
     * are the inherited engine projectile fields at their native widths, moved by the flingy motion ({@link FlingyMotionUtils}).
     * <p>
     * A bullet that joins the active list takes the next generation of its context ({@link #getGeneration()}), a serial that tells bullets
     * apart that reuse an entry. The generations are bookkeeping of the bullet table: no simulation rule reads them, the state hash and saved
     * games leave them out.
     */
    public static final class Bullet extends Projectile {
        final int index;
        int next;
        int previous;
        boolean active;
        long generation;

        @Nullable Sprite sprite;
        @Nullable Unit ownerUnit;
        int owner;
        int weapon;
        int behavior;

        int orderState;
        int orderSignal;
        int remainingTime;
        int hitFlags;

        @Nullable Unit targetUnit;
        int targetX;
        int targetY;
        int remainingBounces;
        @Nullable Unit previousBounceTarget;
        int nearTargetIndex;

        int flingyType;

        Bullet(int index) {
            super(index + 1);

            this.index      = index;
            this.next       = NO_BULLET;
            this.previous   = NO_BULLET;
            this.active     = false;
            this.generation = 0;

            this.sprite    = null;
            this.ownerUnit = null;
            this.owner     = 0;
            this.weapon    = 0;
            this.behavior  = 0;

            this.orderState    = 0;
            this.orderSignal   = 0;
            this.remainingTime = 0;
            this.hitFlags      = 0;

            this.targetUnit           = null;
            this.targetX              = 0;
            this.targetY              = 0;
            this.remainingBounces     = 0;
            this.previousBounceTarget = null;
            this.nearTargetIndex      = 0;

            this.flingyType = 0;
        }

        /**
         * @return the index of the entry in the bullet table, from {@code 0}
         */
        public int getPoolIndex() {
            return this.index;
        }

        /**
         * @return {@code true} while the bullet is in the active list, from its creation until it is released after dying, {@code false} otherwise
         */
        public boolean isActive() {
            return this.active;
        }

        /**
         * @return the generation the bullet took when it joined the active list, {@code 0} for a bullet of a saved game or an entry never used
         */
        public long getGeneration() {
            return this.generation;
        }

        /**
         * @return the sprite, or {@code null} once the last image of the bullet ended
         */
        public @Nullable Sprite getSprite() {
            return this.sprite;
        }

        /**
         * @return the unit that fired the bullet (the parent of a subunit or of a Scarab), or {@code null} once it is gone
         */
        public @Nullable Unit getSourceUnit() {
            return this.ownerUnit;
        }

        /**
         * @return the owning player slot
         */
        public int getOwner() {
            return this.owner;
        }

        /**
         * @return the {@code weapons.dat} entry
         */
        public int getWeapon() {
            return this.weapon;
        }

        /**
         * @return the {@code flingy.dat} entry
         */
        public int getFlingyType() {
            return this.flingyType;
        }

        /**
         * Returns the order state: {@code 0} init, {@code 1} move, {@code 2} follow, {@code 3} bounce, {@code 4} damage over time, {@code 5} dying,
         * {@code 6} hit near the target.
         *
         * @return the order state
         */
        public int getState() {
            return this.orderState;
        }

        /**
         * @return the remaining time BYTE: the frames until a moving bullet dies, or until a bullet that persists on its target position ends
         */
        public int getRemainingTime() {
            return this.remainingTime;
        }

        /**
         * Sets the remaining time BYTE, as a write of the field does: the move and damage-over-time states count it down from the next frame.
         *
         * @param remainingTime the frames, truncated to eight bits
         */
        public void setRemainingTime(int remainingTime) {
            this.remainingTime = remainingTime & BYTE_MASK;
        }

        /**
         * @return the targets a bouncing bullet still hits
         */
        public int getRemainingBounces() {
            return this.remainingBounces;
        }

        /**
         * @return the target unit, or {@code null} for a position target or once the target is gone
         */
        public @Nullable Unit getTargetUnit() {
            return this.targetUnit;
        }

        /**
         * Sets the target unit, as a write of the field does: the follow, bounce and hit-near-target states move toward it from the next frame,
         * and the hit damages it; the move state keeps its move target.
         *
         * @param targetUnit the target unit, or {@code null} for none
         */
        public void setTargetUnit(@Nullable Unit targetUnit) {
            this.targetUnit = targetUnit;
        }

        /**
         * @return the target X coordinate, the center of the hit
         */
        public int getTargetX() {
            return this.targetX;
        }

        /**
         * @return the target Y coordinate, the center of the hit
         */
        public int getTargetY() {
            return this.targetY;
        }

        /**
         * Sets the target position WORDs, as a write of the fields does: the hit is centered there; the move target of the bullet does not change.
         *
         * @param targetX the X coordinate, truncated to a signed WORD
         * @param targetY the Y coordinate, truncated to a signed WORD
         */
        public void setTargetPosition(int targetX, int targetY) {
            this.targetX = (short) targetX;
            this.targetY = (short) targetY;
        }

        /**
         * Sets the movement control, narrowed to its native BYTE.
         *
         * @param movementControl the movement control, truncated to eight bits
         */
        @Override
        public void setMovementControl(int movementControl) {
            super.setMovementControl(movementControl & BYTE_MASK);
        }

        /**
         * Sets the movement flags, narrowed to its native BYTE.
         *
         * @param movementFlags the movement flags, truncated to eight bits
         */
        @Override
        public void setMovementFlags(int movementFlags) {
            super.setMovementFlags(movementFlags & BYTE_MASK);
        }

        /**
         * Sets the direction the object faces, narrowed to its native BYTE.
         *
         * @param heading the direction the object faces, truncated to eight bits
         */
        @Override
        public void setHeading(int heading) {
            super.setHeading(heading & BYTE_MASK);
        }

        /**
         * Sets the direction the object turns to, narrowed to its native BYTE.
         *
         * @param targetHeading the direction the object turns to, truncated to eight bits
         */
        @Override
        public void setTargetHeading(int targetHeading) {
            super.setTargetHeading(targetHeading & BYTE_MASK);
        }

        /**
         * Sets the turn rate, narrowed to its native BYTE.
         *
         * @param turnRate the turn rate, truncated to eight bits
         */
        @Override
        public void setTurnRate(int turnRate) {
            super.setTurnRate(turnRate & BYTE_MASK);
        }

        /**
         * Sets the direction the object moves in, narrowed to its native BYTE.
         *
         * @param movementDirection the direction the object moves in, truncated to eight bits
         */
        @Override
        public void setMovementDirection(int movementDirection) {
            super.setMovementDirection(movementDirection & BYTE_MASK);
        }

        /**
         * Sets the direction toward the current waypoint, narrowed to its native BYTE.
         *
         * @param desiredMovementDirection the direction toward the current waypoint, truncated to eight bits
         */
        @Override
        public void setDesiredMovementDirection(int desiredMovementDirection) {
            super.setDesiredMovementDirection(desiredMovementDirection & BYTE_MASK);
        }

        /**
         * Sets the acceleration per frame, narrowed to its native WORD.
         *
         * @param acceleration the acceleration per frame, truncated to sixteen bits
         */
        @Override
        public void setAcceleration(int acceleration) {
            super.setAcceleration(acceleration & WORD_MASK);
        }

        /**
         * Sets the horizontal map position of the movement destination, narrowed to its native WORD.
         *
         * @param movementDestinationX the horizontal map position of the movement destination, truncated to sixteen bits
         */
        @Override
        public void setMovementDestinationX(int movementDestinationX) {
            super.setMovementDestinationX(movementDestinationX & WORD_MASK);
        }

        /**
         * Sets the vertical map position of the movement destination, narrowed to its native WORD.
         *
         * @param movementDestinationY the vertical map position of the movement destination, truncated to sixteen bits
         */
        @Override
        public void setMovementDestinationY(int movementDestinationY) {
            super.setMovementDestinationY(movementDestinationY & WORD_MASK);
        }

        /**
         * Sets the horizontal map position of the current waypoint, narrowed to its native WORD.
         *
         * @param movementWaypointX the horizontal map position of the current waypoint, truncated to sixteen bits
         */
        @Override
        public void setMovementWaypointX(int movementWaypointX) {
            super.setMovementWaypointX(movementWaypointX & WORD_MASK);
        }

        /**
         * Sets the vertical map position of the current waypoint, narrowed to its native WORD.
         *
         * @param movementWaypointY the vertical map position of the current waypoint, truncated to sixteen bits
         */
        @Override
        public void setMovementWaypointY(int movementWaypointY) {
            super.setMovementWaypointY(movementWaypointY & WORD_MASK);
        }

        /**
         * Sets the horizontal map position the object turns to when it stands on its waypoint, narrowed to its native WORD.
         *
         * @param directionTargetX the horizontal map position the object turns to when it stands on its waypoint, truncated to sixteen bits
         */
        @Override
        public void setDirectionTargetX(int directionTargetX) {
            super.setDirectionTargetX(directionTargetX & WORD_MASK);
        }

        /**
         * Sets the vertical map position the object turns to when it stands on its waypoint, narrowed to its native WORD.
         *
         * @param directionTargetY the vertical map position the object turns to when it stands on its waypoint, truncated to sixteen bits
         */
        @Override
        public void setDirectionTargetY(int directionTargetY) {
            super.setDirectionTargetY(directionTargetY & WORD_MASK);
        }
    }

    /**
     * Applies the retained empty-list branch for callers without a simulation state.
     *
     * @param context the initialized projectile context
     * @throws UnsupportedOperationException if the context is missing or its lists were not initialized
     * @throws IllegalStateException         if bullets are active
     */
    public static void updateEmptyBullets(@Nullable Context context) {
        if (context == null || !context.emptyListsInitialized) {
            throw Exceptions.newUnsupportedOperationException("Projectile list initialization is unavailable.");
        }

        if (context.activeCount != 0) {
            throw Exceptions.newIllegalStateException("Active bullets require the simulation state.");
        }
    }

    /**
     * Applies the established empty independent-flingy branch.
     *
     * @param context the initialized projectile context
     * @throws UnsupportedOperationException if the lists were not initialized
     */
    public static void updateFlingies(@NotNull Context context) {
        if (!context.emptyListsInitialized) {
            throw Exceptions.newUnsupportedOperationException("Native projectile list initialization is unavailable.");
        }
    }

    private static void setMoveTarget(@NotNull Bullet bullet, int positionX, int positionY) {
        if ((short) bullet.getMovementDestinationX() == positionX && (short) bullet.getMovementDestinationY() == positionY) {
            return;
        }

        bullet.setMovementDestinationX(positionX);
        bullet.setMovementDestinationY(positionY);
        bullet.setMovementWaypointX(positionX);
        bullet.setMovementWaypointY(positionY);
        bullet.setMovementFlags(bullet.getMovementFlags() & ~MovementFlags.BRAKING | MovementFlags.ORDERED);
    }

    private static int clamp(int position, int limit) {
        return position < 0 ? 0 : position >= limit ? limit - 1 : position;
    }

    /**
     * Returns the sprite of a bullet for a state step that animates, moves or hits with it.
     *
     * @param bullet the bullet
     * @return the sprite of the bullet
     * @throws IllegalStateException if the bullet has no sprite
     */
    private static @NotNull Sprite requireSprite(@NotNull Bullet bullet) {
        Sprite sprite = bullet.sprite;

        if (sprite == null) {
            throw Exceptions.newIllegalStateException("Bullet {0} has no sprite.", bullet.index);
        }

        return sprite;
    }


    private final @NotNull Game game;

    private final @NotNull MotionProfileProperties<Bullet> bulletFlingyProperties;
    private final @NotNull StandardMotionStep motionStep;

    /**
     * Creates a {@link ProjectilePhase} instance for one game.
     *
     * @param game the game this system belongs to
     */
    public ProjectilePhase(@NotNull Game game) {
        this.game = game;

        this.bulletFlingyProperties = new MotionProfileProperties<>() {
            /**
             * @param object the moving object
             * @return the top speed of the object's motion profile, fixed-point with eight fraction bits
             */
            @Override
            public int getTopSpeed(@NotNull Bullet object) {
                return game.requireGameData().getFlingyTypes().getTopSpeed(object.flingyType);
            }

            /**
             * @param object the moving object
             * @return the acceleration of the object's motion profile, fixed-point with eight fraction bits
             */
            @Override
            public int getAcceleration(@NotNull Bullet object) {
                return game.requireGameData().getFlingyTypes().getAcceleration(object.flingyType);
            }

            /**
             * @param object the moving object
             * @return the distance the object's motion profile needs to stop from its top speed, fixed-point with eight fraction bits
             */
            @Override
            public int getStoppingDistance(@NotNull Bullet object) {
                return game.requireGameData().getFlingyTypes().getHaltDistance(object.flingyType);
            }

            /**
             * @param object the moving object
             * @return {@code true} if the turn rate of the object grows by one each frame it turns, {@code false} otherwise
             */
            @Override
            public boolean isAcceleratingTurn(@NotNull Bullet object) {
                return game.requireGameData().getFlingyProperties().isAcceleratingTurn(object.flingyType);
            }
        };
        this.motionStep             = new StandardMotionStep();
    }

    /**
     * Returns the owner unit of the bullet whose image runs now, for the bullet script commands.
     *
     * @return the owner unit of the current bullet, or {@code null} when no bullet runs or it has no owner unit
     */
    public @Nullable Unit getCurrentOwner() {
        Context context = this.findContext();

        return context == null || context.currentBullet == null ? null : context.currentBullet.ownerUnit;
    }

    /**
     * @return the projectile context of the current frame, or {@code null} when the game has no frame
     */
    private @Nullable Context findContext() {
        return this.game.getFrame() == null ? null : this.game.getFrame().getProjectiles();
    }


    /**
     * Advances each bullet image before dispatching its order, reading the next link first. A bullet whose last image ended has no sprite and
     * keeps its order state: it waits in the init state for an order signal and counts down in the damage-over-time state, and a dying bullet
     * without a sprite is released.
     *
     * @param context          the retained projectile context
     * @param instructionLimit the per-sprite instruction budget
     * @throws UnsupportedOperationException if the lists were not initialized
     * @throws IllegalStateException         if a bullet without a sprite reaches a state step that animates, moves or hits with its sprite
     */
    public void updateBullets(@NotNull Context context, int instructionLimit) {
        if (!context.emptyListsInitialized) {
            throw Exceptions.newUnsupportedOperationException("Projectile list initialization is unavailable.");
        }

        try {
            for (int index = context.firstActive; index >= 0; ) {
                Bullet bullet = context.bullets[index];
                int following = bullet.next;

                context.currentBullet = bullet;

                if (bullet.sprite != null) {
                    SpriteVisibility.updatePositionTile(bullet.sprite, this.game.getMap(), this.game.getLocalVision().getComparisonMask());

                    if (!this.game.getIscriptExecutor().executeSprite(bullet.sprite, null, instructionLimit)) {
                        bullet.sprite = null;
                    }
                }

                this.execute(bullet, instructionLimit);

                if (bullet.orderState == STATE_DYING && bullet.sprite == null) {
                    if (bullet.previous >= 0) {
                        context.bullets[bullet.previous].next = bullet.next;
                    } else {
                        context.firstActive = bullet.next;
                    }

                    if (bullet.next >= 0) {
                        context.bullets[bullet.next].previous = bullet.previous;
                    }

                    bullet.previous = NO_BULLET;
                    bullet.active   = false;

                    if (context.firstFree < 0) {
                        bullet.next       = NO_BULLET;
                        context.firstFree = index;
                    } else {
                        bullet.previous = context.firstFree;
                        bullet.next     = context.bullets[context.firstFree].next;

                        if (bullet.next >= 0) {
                            context.bullets[bullet.next].previous = index;
                        }

                        context.bullets[context.firstFree].next = index;
                    }

                    context.activeCount--;
                }

                index = following;
            }
        } finally {
            context.currentBullet = null;
        }
    }

    /**
     * Sets order signal bits of the bullet whose image runs now, as a bullet script's order signal command does.
     *
     * @param signal the signal bits
     */
    public void signal(int signal) {
        Context context = this.findContext();

        if (context != null && context.currentBullet != null) {
            context.currentBullet.orderSignal |= signal;
        }
    }

    /**
     * Applies the hit of the bullet whose image runs now, as a bullet script's damage command does.
     *
     * @param instructionLimit the image and order instruction budget
     */
    public void hit(int instructionLimit) {
        Context context = this.findContext();

        if (context == null || context.currentBullet == null) {
            return;
        }

        this.hitBullet(context.currentBullet, instructionLimit);
    }

    /**
     * Launches the bullets of a weapon from a unit toward its order target position, as a unit script's use-weapon command does: two bullets for a
     * weapon with damage factor {@code 2}, none for a weapon without a flingy.
     *
     * @param source           the attacking unit
     * @param weapon           the weapon
     * @param instructionLimit the image and order instruction budget
     * @throws NullPointerException if the world was built without game data
     */
    public void useWeapon(@NotNull Unit source, int weapon, int instructionLimit) {
        if (!this.launchesBullets(weapon)) {
            return;
        }

        int count = this.game.requireGameData().getWeaponTypes().getDamageFactor(weapon) == TWO_BULLET_DAMAGE_FACTOR
                    ? TWO_BULLET_COUNT
                    : SINGLE_BULLET_COUNT;

        for (int index = 0; index < count; index++) {
            this.create(source, weapon, source.getOrderTargetX(), source.getOrderTargetY(), instructionLimit);
        }
    }

    /**
     * Checks whether a weapon launches bullets: a weapon whose flingy is entry {@code 0}, the none weapon included, launches none.
     *
     * @param weapon the weapon
     * @return {@code true} if the weapon launches bullets, {@code false} otherwise
     * @throws NullPointerException if the world was built without game data
     */
    boolean launchesBullets(int weapon) {
        return this.game.requireGameData().getWeaponTypes().getFlingyType(weapon) != NO_FLINGY_TYPE;
    }

    /**
     * Returns the miss chance of an attack, out of {@code 256}: the dodge chance of the target ({@link #getDodgeChance}), and at least the cover
     * chance from a ground attacker against a ground target on higher ground. The computer Dark Swarm decision tests it for {@code 0}.
     *
     * @param source the attacking unit
     * @param target the attacked unit
     * @return the miss chance, {@code 0} through {@link #MISS_CHANCE_ALWAYS}
     * @throws IllegalStateException if the source or the target has no sprite or the terrain data cannot be loaded
     * @throws NullPointerException  if the world was built without game data
     */
    public int getMissChance(@NotNull Unit source, @NotNull Unit target) {
        int chance = this.getDodgeChance(target);

        if ((source.getStatus() & UnitStatusFlags.FLYING) == 0 && (target.getStatus() & UnitStatusFlags.FLYING) == 0) {
            Sprite sourceSprite = source.requireSprite();
            int sourceVertical = (short) sourceSprite.getY();
            int sourceHorizontal = (short) sourceSprite.getX();
            GameMap map = this.game.getMap();

            try {
                TerrainTileset terrain = this.game.requireGameData().getTerrainTileset(map.getTileset());
                int sourceHeight = map.getTerrainHeight(terrain, sourceHorizontal, sourceVertical);
                Sprite targetSprite = target.requireSprite();
                int targetVertical = (short) targetSprite.getY();
                int targetHorizontal = (short) targetSprite.getX();

                if (sourceHeight < map.getTerrainHeight(terrain, targetHorizontal, targetVertical)) {
                    chance = Math.max(chance, MISS_CHANCE_COVERED);
                }
            } catch (IOException exception) {
                throw Exceptions.newIllegalStateException(exception, "Cannot load projectile terrain height.");
            }
        }

        return chance;
    }

    /**
     * Returns the chance, out of {@code 256}, that an attack misses a unit wherever the attacker stands: none for a flying unit, every attack under a
     * Dark Swarm, otherwise the cover chance on a tile that provides cover. The tile is the one under the sprite position WORDs
     * ({@link GameMap#getTileFlagsAtPosition}).
     *
     * @param unit the attacked unit
     * @return the dodge chance: {@code 0}, {@link #MISS_CHANCE_COVERED} or {@link #MISS_CHANCE_ALWAYS}
     * @throws IllegalStateException if the unit has no sprite
     */
    int getDodgeChance(@NotNull Unit unit) {
        if ((unit.getStatus() & UnitStatusFlags.FLYING) != 0) {
            return 0;
        }

        if (this.game.getUnitTargeting().isUnderDarkSwarm(unit)) {
            return MISS_CHANCE_ALWAYS;
        }

        Sprite sprite = unit.requireSprite();

        return (this.game.getMap().getTileFlagsAtPosition(sprite.getX(), sprite.getY()) & MapTileFlags.PROVIDES_COVER) != 0 ? MISS_CHANCE_COVERED : 0;
    }

    /**
     * Forgets the source of the previous spinning launch, so the next spinning launch rolls its direction.
     *
     * @throws UnsupportedOperationException if the projectile storage is not initialized
     */
    void resetPreviousSource() {
        Context context = this.findContext();

        if (context == null) {
            throw Exceptions.newUnsupportedOperationException("Weapon launch requires initialized projectile storage.");
        }

        context.previousSource = null;
    }

    /**
     * Creates a bullet of a weapon at a position: the flingy and sprite, the launch spin, the miss roll and the behavior's start position, then links
     * it into the active list. The bullet takes the order target unit of the source, else its order target position, as its target.
     *
     * @param source           the attacking unit
     * @param weapon           the weapon
     * @param positionX        the start position X
     * @param positionY        the start position Y
     * @param instructionLimit the image and order instruction budget
     * @return the created bullet, or {@code null} when none is created (limit reached, no free entry, no sprite, or an attack the source cannot make)
     * @throws UnsupportedOperationException if the projectile storage is not initialized
     * @throws IllegalStateException         if the order target unit, or the source without an order target unit, has no sprite, or the source of a
     *                                       bullet that extends to the maximum range has no sprite
     * @throws NullPointerException          if the world was built without game data
     */
    public @Nullable Bullet create(@NotNull Unit source, int weapon, int positionX, int positionY, int instructionLimit) {
        Context context = this.findContext();

        if (context == null || !context.emptyListsInitialized) {
            throw Exceptions.newUnsupportedOperationException("Projectile creation requires initialized frame storage.");
        }

        if (weapon == ScrWeaponIds.HALO_ROCKETS && Integer.compareUnsigned(context.activeCount, context.getHaloRocketsLimit()) >= 0) {
            return null;
        }

        if ((source.getStatus() & UnitStatusFlags.CANNOT_ATTACK) != 0) {
            boolean spell = switch (ScrWeaponIds.orNone(weapon)) {
                case ScrWeaponIds.SPIDER_MINES, ScrWeaponIds.LOCKDOWN, ScrWeaponIds.EMP_SHOCKWAVE, ScrWeaponIds.IRRADIATE, ScrWeaponIds.UNKNOWN_50,
                     ScrWeaponIds.UNKNOWN_51, ScrWeaponIds.SUICIDE_INFESTED_TERRAN, ScrWeaponIds.PARASITE, ScrWeaponIds.SPAWN_BROODLINGS,
                     ScrWeaponIds.ENSNARE, ScrWeaponIds.DARK_SWARM, ScrWeaponIds.PLAGUE, ScrWeaponIds.CONSUME, ScrWeaponIds.UNKNOWN_68,
                     ScrWeaponIds.PSI_ASSAULT, ScrWeaponIds.SCARAB, ScrWeaponIds.STASIS_FIELD, ScrWeaponIds.PSIONIC_STORM, ScrWeaponIds.RESTORATION,
                     ScrWeaponIds.MIND_CONTROL, ScrWeaponIds.FEEDBACK, ScrWeaponIds.OPTICAL_FLARE, ScrWeaponIds.MAELSTROM -> true;
                default -> false;
            };

            if (!spell) {
                return null;
            }
        }

        if (context.firstFree < 0) {
            return null;
        }

        ScrWeaponTypeView weapons = this.game.requireGameData().getWeaponTypes();
        int behavior = weapons.getBehavior(weapon);
        Bullet bullet = context.bullets[context.firstFree];
        ScrFlingyTypeView flingies = this.game.requireGameData().getFlingyTypes();
        int flingyType = weapons.getFlingyType(weapon);

        bullet.flingyType = flingyType;
        bullet.setMovementFlags(0);
        bullet.setNextSpeed(0);
        bullet.setTopSpeed(flingies.getTopSpeed(flingyType));
        bullet.setAcceleration(flingies.getAcceleration(flingyType));
        bullet.setTurnRate(flingies.getTurnRate(flingyType));
        bullet.setMovementControl(flingies.getMovementControl(flingyType));
        bullet.setPosition(positionX, positionY);
        bullet.setFixedPositionX(positionX << FIXED_POINT_FRACTION_BITS);
        bullet.setFixedPositionY(positionY << FIXED_POINT_FRACTION_BITS);

        setMoveTarget(bullet, positionX, positionY);

        bullet.setDirectionTargetX(positionX);
        bullet.setDirectionTargetY(positionY);
        bullet.setHeading(source.getHeading());
        bullet.setTargetHeading(source.getHeading());

        Sprite sprite = this.game.getIscriptExecutor().createSprite(flingies.getSprite(flingyType),
                                                                    positionX, positionY,
                                                                    source.getOwnerIndex(),
                                                                    null,
                                                                    instructionLimit);

        if (sprite == null) {
            return null;
        }

        bullet.sprite = sprite;

        IscriptExecutor.applySpriteDirection(this.game, sprite, source.getHeading());

        bullet.setMovementFlags(bullet.getMovementFlags() | MovementFlags.STARTING_ATTACK);
        bullet.orderState       = STATE_INIT;
        bullet.orderSignal      = 0;
        bullet.owner            = source.getOwnerIndex();
        bullet.weapon           = weapon;
        bullet.behavior         = behavior;
        bullet.remainingTime    = weapons.getLifetime(weapon);
        bullet.hitFlags         = 0;
        bullet.remainingBounces = 0;

        int headingOffset = (byte) weapons.getLaunchSpin(weapon);

        if (headingOffset != 0) {
            boolean clockwise = context.previousSource == source ? !context.previousClockwise
                                                                 : (this.game.getRandom().next(LAUNCH_SPIN_RANDOM_SOURCE) & CLOCKWISE_SPIN_BIT) != 0;

            bullet.setTargetHeading((bullet.getTargetHeading() + (clockwise ? headingOffset : -headingOffset)) & BYTE_MASK);
            bullet.setHeading(bullet.getTargetHeading());
            context.previousSource    = source;
            context.previousClockwise = clockwise;
        }

        bullet.ownerUnit = source;

        if ((this.game.requireGameData().getUnitTypes().getFlags(source.getUnitId()) & UnitTypeFlags.SUBUNIT) != 0) {
            bullet.ownerUnit = source.getSubunit();
        }

        // A Scarab's bullet belongs to its hangar parent.
        if (this.game.getBaseType(source) == ScrUnitIds.PROTOSS_SCARAB) {
            bullet.ownerUnit = source.getHangarParent();
        }

        if ((source.getStatus() & UnitStatusFlags.HALLUCINATION) != 0) {
            bullet.hitFlags |= HIT_FLAG_HALLUCINATION;
        }

        bullet.previousBounceTarget = null;
        bullet.targetUnit           = source.getOrderTargetUnit();

        Sprite targetSprite = bullet.targetUnit == null ? source.getSpriteOrNull() : bullet.targetUnit.getSpriteOrNull();

        if (targetSprite == null) {
            throw Exceptions.newIllegalStateException("Projectile source or target has no sprite.");
        }

        sprite.setElevationLevel(targetSprite.getElevationLevel() + 1);

        int targetX = bullet.targetUnit == null ? (short) source.getOrderTargetX() : targetSprite.getX();
        int targetY = bullet.targetUnit == null ? (short) source.getOrderTargetY() : targetSprite.getY();

        switch (behavior) {
            case WeaponBehaviors.FLY, WeaponBehaviors.FOLLOW_TARGET, WeaponBehaviors.BOUNCE -> {
                if (behavior == WeaponBehaviors.BOUNCE) {
                    bullet.remainingBounces = BOUNCE_COUNT;
                }

                if (this.rollMiss(bullet)) {
                    bullet.hitFlags |= HIT_FLAG_MISSED;

                    long vector = FlingyMotionUtils.calculateDirectionVector(source.getHeading(), MISS_DISTANCE);

                    targetX -= (int) vector;
                    targetY -= (int) (vector >>> FlingyMotionUtils.DIRECTION_VECTOR_VERTICAL_SHIFT);
                }

                setMoveTarget(bullet, targetX, targetY);
            }
            case WeaponBehaviors.APPEAR_AT_TARGET_UNIT, WeaponBehaviors.APPEAR_AT_TARGET_POSITION -> {
                if (this.rollMiss(bullet)) {
                    bullet.hitFlags |= HIT_FLAG_MISSED;

                    long vector = FlingyMotionUtils.calculateDirectionVector(bullet.getHeading(), MISS_DISTANCE);
                    int missX = sprite.getX() - (int) vector;
                    int missY = sprite.getY() - (int) (vector >>> FlingyMotionUtils.DIRECTION_VECTOR_VERTICAL_SHIFT);

                    bullet.setFixedPositionX(missX << FIXED_POINT_FRACTION_BITS);
                    bullet.setFixedPositionY(missY << FIXED_POINT_FRACTION_BITS);
                    bullet.setPosition(missX, missY);

                    this.relocateSprite(sprite, missX, missY);
                }
            }
            case WeaponBehaviors.PERSIST_AT_TARGET_POSITION -> {
                bullet.setFixedPositionX(targetX << FIXED_POINT_FRACTION_BITS);
                bullet.setFixedPositionY(targetY << FIXED_POINT_FRACTION_BITS);
                bullet.setPosition(targetX, targetY);

                this.relocateSprite(sprite, targetX, targetY);
            }
            case WeaponBehaviors.SELF_DESTRUCT -> {
                Unit ownerUnit = bullet.ownerUnit;

                if (ownerUnit != null) {
                    ownerUnit.setStatus(ownerUnit.getStatus() | UnitStatusFlags.LIFETIME_EXPIRED);
                    ownerUnit.setUserActionFlags(ownerUnit.getUserActionFlags() | Unit.USER_ACTION_HIDE_ON_DEATH);
                    this.game.getUnitLifecycle().kill(ownerUnit, instructionLimit);
                }
            }
            case WeaponBehaviors.ATTACK_TARGET_POSITION -> {
                // Cycle through the fourteen target offsets.
                int index = source.getNextHitNearTargetIndex();

                bullet.nearTargetIndex = index;

                source.setNextHitNearTargetIndex(index == NEAR_TARGET_POSITIONS - 1 ? 0 : index + 1);
                setMoveTarget(bullet,
                              targetX + NEAR_TARGET_OFFSETS[index * NEAR_TARGET_COORDINATE_COUNT],
                              targetY + NEAR_TARGET_OFFSETS[index * NEAR_TARGET_COORDINATE_COUNT + NEAR_TARGET_Y_ENTRY]);
            }
            case WeaponBehaviors.EXTEND_TO_MAXIMUM_RANGE -> {
                Sprite sourceSprite = source.requireSprite();
                // Scale the 8.8 length in int arithmetic and add the shifted components as signed WORDs.
                int fixedLength = weapons.getMaximumRange(weapon) + MAXIMUM_RANGE_EXTENSION << FIXED_POINT_FRACTION_BITS;
                long vector = FlingyMotionUtils.calculateDirectionVector(bullet.getHeading(), fixedLength);

                targetX = (short) (sourceSprite.getX() + ((int) vector >> FIXED_POINT_FRACTION_BITS));
                targetY = (short) (sourceSprite.getY() +
                                   ((int) (vector >>> FlingyMotionUtils.DIRECTION_VECTOR_VERTICAL_SHIFT) >> FIXED_POINT_FRACTION_BITS));

                setMoveTarget(bullet, targetX, targetY);
            }
            default -> {
            }
        }

        bullet.targetX = targetX;
        bullet.targetY = targetY;

        context.firstFree = bullet.next;

        if (context.firstFree >= 0) {
            context.bullets[context.firstFree].previous = NO_BULLET;
        }

        if (context.firstActive < 0) {
            bullet.previous     = NO_BULLET;
            bullet.next         = NO_BULLET;
            context.firstActive = bullet.index;
        } else {
            bullet.previous = context.firstActive;
            bullet.next     = context.bullets[context.firstActive].next;

            if (bullet.next >= 0) {
                context.bullets[bullet.next].previous = bullet.index;
            }

            context.bullets[context.firstActive].next = bullet.index;
        }

        context.activeCount++;

        bullet.active     = true;
        bullet.generation = ++context.generation;

        ScrSimulationPlugins simulation = this.game.getSimulationPlugins();

        if (simulation != null) {
            simulation.reportBulletCreated(bullet, weapon, bullet.owner, source, bullet.targetUnit, bullet.targetX, bullet.targetY);
        }

        return bullet;
    }

    /**
     * Rolls the miss of a created bullet that has an owner unit and a target unit, drawing from the miss random call site only then.
     *
     * @param bullet the created bullet
     * @return {@code true} if the bullet has both units and the roll is within the miss chance, {@code false} otherwise
     */
    private boolean rollMiss(@NotNull Bullet bullet) {
        return bullet.targetUnit != null && bullet.ownerUnit != null &&
               (this.game.getRandom().next(MISS_RANDOM_SOURCE) & BYTE_MASK) <= this.getMissChance(bullet.ownerUnit, bullet.targetUnit);
    }

    /**
     * Dispatches bullet states, continuing in the same frame when a state permits it.
     *
     * @param bullet           the active bullet
     * @param instructionLimit the image and order instruction budget
     * @throws IllegalStateException if the bullet's order state is unknown, its target unit has no sprite, or the bullet has no sprite in a step
     *                               that animates, moves or hits with it
     */
    private void execute(@NotNull Bullet bullet, int instructionLimit) {
        while (true) {
            switch (bullet.orderState) {
                case STATE_INIT -> {
                    if (!this.initializeState(bullet, instructionLimit)) {
                        return;
                    }
                }
                case STATE_MOVE -> {
                    this.updateMoveState(bullet, instructionLimit);

                    return;
                }
                case STATE_FOLLOW -> {
                    Unit target = bullet.targetUnit;

                    if (target != null) {
                        if ((bullet.hitFlags & HIT_FLAG_MISSED) == 0) {
                            Sprite targetSprite = target.requireSprite();

                            bullet.setDirectionTargetX(targetSprite.getX());
                            bullet.setDirectionTargetY(targetSprite.getY());

                            setMoveTarget(bullet, targetSprite.getX(), targetSprite.getY());
                        } else {
                            bullet.orderState = STATE_MOVE;
                        }
                    } else {
                        bullet.orderState = STATE_MOVE;
                    }

                    this.updateMoveState(bullet, instructionLimit);

                    return;
                }
                case STATE_BOUNCE -> {
                    this.updateBounceState(bullet, instructionLimit);

                    return;
                }
                case STATE_DAMAGE_OVER_TIME -> {
                    int remaining = bullet.remainingTime;

                    bullet.remainingTime = (remaining - 1) & BYTE_MASK;

                    if (remaining == 0) {
                        this.kill(bullet, instructionLimit);
                    } else if (bullet.remainingTime % PERSIST_HIT_INTERVAL == 0) {
                        this.hitBullet(bullet, instructionLimit);
                    }

                    return;
                }
                case STATE_HIT_NEAR_TARGET -> {
                    Unit target = bullet.targetUnit;

                    if (target != null) {
                        if ((bullet.hitFlags & HIT_FLAG_MISSED) == 0) {
                            Sprite targetSprite = target.requireSprite();
                            int offset = bullet.nearTargetIndex * NEAR_TARGET_COORDINATE_COUNT;
                            int positionX = clamp(targetSprite.getX() + NEAR_TARGET_OFFSETS[offset],
                                                  this.game.getMap().getWidth() * TerrainTileset.TILE_SIZE);
                            int positionY = clamp(targetSprite.getY() + NEAR_TARGET_OFFSETS[offset + NEAR_TARGET_Y_ENTRY],
                                                  this.game.getMap().getHeight() * TerrainTileset.TILE_SIZE);

                            setMoveTarget(bullet, positionX, positionY);

                            bullet.setDirectionTargetX((short) bullet.getMovementDestinationX());
                            bullet.setDirectionTargetY((short) bullet.getMovementDestinationY());
                        } else {
                            bullet.orderState = STATE_MOVE;
                        }
                    } else {
                        bullet.orderState = STATE_MOVE;
                    }

                    this.updateMoveState(bullet, instructionLimit);

                    return;
                }
                case STATE_DYING -> {
                    return;
                }
                default -> throw Exceptions.newIllegalStateException("Unknown projectile state {0}", bullet.orderState);
            }
        }
    }

    /**
     * Leaves the init state once the bullet's script signaled it, choosing the state and animation of the weapon behavior.
     *
     * @param bullet           the bullet in its init state
     * @param instructionLimit the image and order instruction budget
     * @return {@code true} if the bullet left the init state, {@code false} otherwise
     * @throws IllegalStateException if the bullet's weapon behavior is unknown, or the signaled bullet has no sprite
     */
    private boolean initializeState(@NotNull Bullet bullet, int instructionLimit) {
        if ((bullet.orderSignal & Unit.ORDER_SIGNAL_ACTION_DONE) == 0) {
            return false;
        }

        bullet.orderSignal &= ~Unit.ORDER_SIGNAL_ACTION_DONE;

        switch (bullet.behavior) {
            case WeaponBehaviors.FLY, WeaponBehaviors.EXTEND_TO_MAXIMUM_RANGE -> {
                bullet.orderState = STATE_MOVE;

                this.game.getIscriptExecutor().runSpriteAnimation(requireSprite(bullet),
                                                                  null,
                                                                  IscriptExecutor.ANIMATION_GROUND_ATTACK_INIT,
                                                                  0,
                                                                  instructionLimit);
            }
            case WeaponBehaviors.APPEAR_AT_TARGET_UNIT, WeaponBehaviors.APPEAR_AT_TARGET_POSITION, WeaponBehaviors.APPEAR_AT_SOURCE_UNIT,
                 WeaponBehaviors.SELF_DESTRUCT -> this.kill(bullet, instructionLimit);
            case WeaponBehaviors.FOLLOW_TARGET -> {
                bullet.orderState = STATE_FOLLOW;

                this.game.getIscriptExecutor().runSpriteAnimation(requireSprite(bullet),
                                                                  null,
                                                                  IscriptExecutor.ANIMATION_GROUND_ATTACK_INIT,
                                                                  0,
                                                                  instructionLimit);
            }
            case WeaponBehaviors.PERSIST_AT_TARGET_POSITION -> {
                bullet.orderState = STATE_DAMAGE_OVER_TIME;

                this.game.getIscriptExecutor().runSpriteAnimation(requireSprite(bullet),
                                                                  null,
                                                                  IscriptExecutor.ANIMATION_SPECIAL_STATE_2,
                                                                  0,
                                                                  instructionLimit);
            }
            case WeaponBehaviors.BOUNCE -> {
                bullet.orderState = STATE_BOUNCE;

                this.game.getIscriptExecutor().runSpriteAnimation(requireSprite(bullet),
                                                                  null,
                                                                  IscriptExecutor.ANIMATION_GROUND_ATTACK_INIT,
                                                                  0,
                                                                  instructionLimit);
            }
            case WeaponBehaviors.ATTACK_TARGET_POSITION -> {
                bullet.orderState = STATE_HIT_NEAR_TARGET;

                this.game.getIscriptExecutor().runSpriteAnimation(requireSprite(bullet),
                                                                  null,
                                                                  IscriptExecutor.ANIMATION_GROUND_ATTACK_INIT,
                                                                  0,
                                                                  instructionLimit);
            }
            default -> throw Exceptions.newIllegalStateException("Unknown projectile behavior {0}", bullet.behavior);
        }

        return true;
    }

    private void kill(@NotNull Bullet bullet, int instructionLimit) {
        bullet.orderState = STATE_DYING;

        this.game.getIscriptExecutor().runSpriteAnimation(requireSprite(bullet), null, IscriptExecutor.ANIMATION_DEATH, 0, instructionLimit);
    }

    private void updateMoveState(@NotNull Bullet bullet, int instructionLimit) {
        this.move(bullet);

        int remaining = bullet.remainingTime;

        bullet.remainingTime = (remaining - 1) & BYTE_MASK;

        if (remaining == 0 || FlingyMotionUtils.isAtMovementDestination(bullet)) {
            this.kill(bullet, instructionLimit);
        }
    }

    /**
     * Moves a bouncing bullet toward its target, which it follows unless it missed; at the move target a remaining bounce retargets the bullet
     * to the first enemy within {@code 96} pixels that the owner unit can attack, other than the current and the previous target, and
     * otherwise the bullet dies.
     *
     * @param bullet           the bouncing bullet
     * @param instructionLimit the image and order instruction budget
     * @throws IllegalStateException         if an unmissed target or the bullet has no sprite
     * @throws UnsupportedOperationException if a bounce remains for a bullet with an owner unit and the game has no unit finder
     */
    private void updateBounceState(@NotNull Bullet bullet, int instructionLimit) {
        Unit target = bullet.targetUnit;

        if (target != null && (bullet.hitFlags & HIT_FLAG_MISSED) == 0) {
            Sprite targetSprite = target.requireSprite();

            setMoveTarget(bullet, targetSprite.getX(), targetSprite.getY());

            bullet.setDirectionTargetX((short) bullet.getMovementDestinationX());
            bullet.setDirectionTargetY((short) bullet.getMovementDestinationY());
        }

        this.move(bullet);

        if (!FlingyMotionUtils.isAtMovementDestination(bullet)) {
            return;
        }

        bullet.remainingBounces--;

        Unit next = null;

        if (bullet.remainingBounces != 0) {
            Unit source = bullet.ownerUnit;

            if (source != null) {
                UnitSpatialIndex spatialIndex = this.game.getUnitSystem().requireUnitSpatialIndex();
                Sprite sprite = requireSprite(bullet);
                int count = spatialIndex.collectPlacementCandidates(sprite.getX() - BOUNCE_SEARCH_RADIUS, sprite.getY() - BOUNCE_SEARCH_RADIUS,
                                                                    sprite.getX() + BOUNCE_SEARCH_RADIUS, sprite.getY() + BOUNCE_SEARCH_RADIUS);

                for (int index = 0; index < count; index++) {
                    Unit candidate = spatialIndex.getPlacementCandidate(index);

                    if (this.game.getUnitTargeting().canAttackTarget(source, candidate) &&
                        this.game.getEngineGame().getCombat().isEnemy(source, candidate) &&
                        candidate != target && candidate != bullet.previousBounceTarget) {
                        next = candidate;

                        break;
                    }
                }
            }

            bullet.previousBounceTarget = target;
        }

        if (next != null) {
            this.game.getIscriptExecutor().runSpriteAnimation(requireSprite(bullet),
                                                              null,
                                                              IscriptExecutor.ANIMATION_SPECIAL_STATE_1,
                                                              0,
                                                              instructionLimit);

            bullet.targetUnit = next;
        } else {
            this.kill(bullet, instructionLimit);
        }
    }

    /**
     * Moves the bullet one flingy motion step ({@link FlingyMotionUtils}) and turns it, then clamps it to the map.
     *
     * @param bullet the moving bullet
     * @throws IllegalStateException if the bullet has no sprite
     */
    private void move(@NotNull Bullet bullet) {
        FlingyMotionUtils.update(this.motionStep, bullet, this.bulletFlingyProperties);
        this.motionStep.commit(bullet);

        Sprite sprite = requireSprite(bullet);
        int positionX = bullet.getPositionX();
        int positionY = bullet.getPositionY();

        this.relocateSprite(sprite, positionX, positionY);
        FlingyMotionUtils.updateFacing(bullet, this.bulletFlingyProperties);
        IscriptExecutor.applySpriteDirection(this.game, sprite, bullet.getHeading());

        int mapWidth = this.game.getMap().getWidth() * TerrainTileset.TILE_SIZE;
        int mapHeight = this.game.getMap().getHeight() * TerrainTileset.TILE_SIZE;

        if (positionX < 0 || positionY < 0 || positionX >= mapWidth || positionY >= mapHeight) {
            bullet.remainingTime = 0;
        }

        int clampedX = clamp(positionX, mapWidth);
        int clampedY = clamp(positionY, mapHeight);

        this.relocateSprite(sprite, clampedX, clampedY);

        if (clampedX != positionX || clampedY != positionY) {
            bullet.setPosition(clampedX, clampedY);
            bullet.setFixedPositionX(clampedX << FIXED_POINT_FRACTION_BITS);
            bullet.setFixedPositionY(clampedY << FIXED_POINT_FRACTION_BITS);
        }
    }

    private void relocateSprite(@NotNull Sprite sprite, int positionX, int positionY) {
        if (sprite.getX() != positionX || sprite.getY() != positionY) {
            this.game.getSpriteSystem().requireSpritePool().relocate(sprite, positionX, positionY, this.game.getMap().getHeight());
        }
    }

    /**
     * Applies the bullet hit through {@link UnitDamage#hitBullet}; a bouncing weapon multiplies the damage divisor by {@code 3} once for each
     * step its remaining bounces lie below {@code 2}.
     *
     * @param bullet           the hitting bullet
     * @param instructionLimit the image and order instruction budget
     * @throws NullPointerException  if the world was built without game data
     * @throws IllegalStateException if the bullet has no sprite
     */
    private void hitBullet(@NotNull Bullet bullet, int instructionLimit) {
        int divisor = 1;

        if (this.game.requireGameData().getWeaponTypes().getBehavior(bullet.weapon) == WeaponBehaviors.BOUNCE) {
            for (int bounce = 0; bounce < BOUNCE_COUNT - 1 - bullet.remainingBounces; bounce++) {
                divisor *= BOUNCE_DAMAGE_DIVISOR;
            }
        }

        ScrSimulationPlugins simulation = this.game.getSimulationPlugins();

        if (simulation != null) {
            simulation.reportBulletHit(bullet,
                                       bullet.weapon,
                                       bullet.owner,
                                       bullet.ownerUnit, bullet.targetUnit,
                                       bullet.targetX, bullet.targetY,
                                       (bullet.hitFlags & HIT_FLAG_MISSED) != 0);
        }

        this.game.getUnitDamage().hitBullet(bullet.weapon,
                                            bullet.owner, bullet.ownerUnit,
                                            bullet.targetUnit, bullet.getHeading(),
                                            bullet.hitFlags,
                                            divisor,
                                            requireSprite(bullet),
                                            bullet.targetX, bullet.targetY,
                                            instructionLimit);
    }
}
