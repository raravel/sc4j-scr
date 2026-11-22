package org.fogofwarlabs.sc4j.screnhanced.script.core;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.data.format.dat.*;
import org.fogofwarlabs.sc4j.scr.data.type.ScrSpriteTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.constant.*;
import org.fogofwarlabs.sc4j.scr.game.state.GameData;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.region.ClassicDatRegion;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.function.Function;

/**
 * Describes the DAT tables of {@code core.dat} and of the type objects of the upper layer: units, weapons, flingy, sprites, images, upgrades,
 * techdata and orders, with one column per field of their SC:R schema ({@code UnitsDat.createSchema()} and the others).
 * <p>
 * A column takes the name of the Java constant that names its field in the SC:R definitions class, in upper camel case:
 * {@code UnitsDat.MINERAL_COST} is the column {@code MineralCost}, read with {@code getMineralCost} and written with {@code setMineralCost}.
 * A column that holds bits named by an SC:R flag class ({@code UnitTypeFlags} and the others) also has one flag per named bit, the column's
 * name without {@code Flags} followed by the bit's name ({@code GroupZerg}); bits named {@code FLAG_<bits>} have no established meaning and
 * no flag.
 * <p>
 * The values are the game's own DAT tables ({@link GameData}), which EUD writes change too. A column that the EUD emulation serves not at all
 * (the GRP and overlay columns of images.dat) is not writable; a column that the EUD emulation validates takes only the values the emulation
 * stores (a flingy, an image, a drawing function, an iscript id). The bar columns of sprites.dat, which the EUD emulation keeps from EUD
 * writes, are writable: the health bar width, the selection circle (one of the {@code 10} circle sizes) and its
 * vertical offset; a selection circle takes them when it is shown, a health bar when it is drawn. A column with fewer elements than its table
 * holds the entries from its first id on.
 */
public final class ScriptDatTables {
    /**
     * The none id of a reference that always names an object ({@link Reference#getNoneId()}).
     */
    public static final int NOT_NULLABLE = -1;
    /**
     * The limit of a column that takes every value of its type ({@link Column#getLimit()}).
     */
    public static final int NO_LIMIT = -1;
    /**
     * The prefix of the flag constants without an established meaning.
     */
    private static final @NotNull String UNNAMED_FLAG_PREFIX = "FLAG_";
    /**
     * The suffix of a flag column's constant that its flags leave out.
     */
    private static final @NotNull String FLAG_COLUMN_SUFFIX = "FLAGS";
    private static final @NotNull String CONSTANT_SEPARATOR = "_";
    /**
     * The selection circle sizes, the solid circle images from {@link ImageIds#SELECTION_CIRCLE_22PIXELS} on.
     */
    private static final int SELECTION_CIRCLE_SIZES = ImageIds.SELECTION_CIRCLE_DASHED_22PIXELS - ImageIds.SELECTION_CIRCLE_22PIXELS;
    /**
     * The tables in the order of {@code core.dat}.
     */
    private static final @NotNull List<Table> TABLES = List.of(
            createTable("units", "units.dat", "unit type", Reference.UNIT_TYPE, UnitsDat.UNIT_COUNT, GameData::getUnits, UnitsDat.class,
                        UnitsDat.createSchema(),
                        Map.ofEntries(Map.entry(UnitsDat.FLINGY_TYPE, Reference.FLINGY_TYPE),
                                      Map.entry(UnitsDat.FIRST_SUBUNIT, Reference.UNIT_TYPE),
                                      Map.entry(UnitsDat.SECOND_SUBUNIT, Reference.UNIT_TYPE),
                                      Map.entry(UnitsDat.INFESTED_TYPE, Reference.UNIT_TYPE),
                                      Map.entry(UnitsDat.CONSTRUCTION_IMAGE, Reference.IMAGE_TYPE),
                                      Map.entry(UnitsDat.COMPUTER_IDLE_ORDER, Reference.ORDER),
                                      Map.entry(UnitsDat.HUMAN_IDLE_ORDER, Reference.ORDER),
                                      Map.entry(UnitsDat.RETURN_TO_IDLE_ORDER, Reference.ORDER),
                                      Map.entry(UnitsDat.ATTACK_UNIT_ORDER, Reference.ORDER),
                                      Map.entry(UnitsDat.ATTACK_MOVE_ORDER, Reference.ORDER),
                                      Map.entry(UnitsDat.GROUND_WEAPON, Reference.WEAPON_TYPE),
                                      Map.entry(UnitsDat.AIR_WEAPON, Reference.WEAPON_TYPE),
                                      Map.entry(UnitsDat.ARMOR_UPGRADE, Reference.UPGRADE_TYPE)),
                        Map.of(UnitsDat.FLAGS, UnitTypeFlags.class,
                               UnitsDat.COMPUTER_INTERNAL_FLAGS, UnitComputerFlags.class,
                               UnitsDat.GROUP_FLAGS, UnitGroupFlags.class,
                               UnitsDat.AVAILABILITY_FLAGS, UnitAvailabilityFlags.class),
                        Map.of(UnitsDat.INFESTED_TYPE, ScrUnitTypeView.FIRST_INFESTATION_TYPE,
                               UnitsDat.ADDON_HORIZONTAL_OFFSET, ScrUnitTypeView.FIRST_INFESTATION_TYPE,
                               UnitsDat.ADDON_VERTICAL_OFFSET, ScrUnitTypeView.FIRST_INFESTATION_TYPE),
                        Map.of(UnitsDat.FLINGY_TYPE, FlingyDat.FLINGY_COUNT),
                        Set.of()),
            createTable("weapons", "weapons.dat", "weapon type", Reference.WEAPON_TYPE, WeaponsDat.WEAPON_COUNT, GameData::getWeapons,
                        WeaponsDat.class, WeaponsDat.createSchema(),
                        Map.of(WeaponsDat.FLINGY_TYPE, Reference.FLINGY_TYPE,
                               WeaponsDat.DAMAGE_UPGRADE, Reference.UPGRADE_TYPE),
                        Map.of(WeaponsDat.TARGET_FLAGS, WeaponTargetFlags.class),
                        Map.of(),
                        Map.of(),
                        Set.of()),
            createTable("flingy", "flingy.dat", "flingy type", Reference.FLINGY_TYPE, FlingyDat.FLINGY_COUNT, GameData::getFlingy,
                        FlingyDat.class, FlingyDat.createSchema(),
                        Map.of(FlingyDat.SPRITE, Reference.SPRITE_TYPE),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Set.of()),
            createTable("sprites", "sprites.dat", "sprite type", Reference.SPRITE_TYPE, SpritesDat.SPRITE_COUNT, GameData::getSprites,
                        SpritesDat.class, SpritesDat.createSchema(),
                        Map.of(SpritesDat.IMAGE, Reference.IMAGE_TYPE),
                        Map.of(),
                        Map.of(SpritesDat.HEALTH_BAR_WIDTH, ScrSpriteTypeView.FIRST_SELECTABLE_SPRITE,
                               SpritesDat.SELECTION_CIRCLE, ScrSpriteTypeView.FIRST_SELECTABLE_SPRITE,
                               SpritesDat.SELECTION_CIRCLE_OFFSET, ScrSpriteTypeView.FIRST_SELECTABLE_SPRITE),
                        Map.of(SpritesDat.IMAGE, ImagesDat.IMAGE_COUNT, SpritesDat.SELECTION_CIRCLE, SELECTION_CIRCLE_SIZES),
                        Set.of()),
            createTable("images", "images.dat", "image type", Reference.IMAGE_TYPE, ImagesDat.IMAGE_COUNT, GameData::getImages,
                        ImagesDat.class, ImagesDat.createSchema(),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Map.of(ImagesDat.DRAW_FUNCTION, ClassicDatRegion.DRAW_FUNCTION_LIMIT,
                               ImagesDat.ISCRIPT, ClassicDatRegion.ISCRIPT_LIMIT),
                        Set.of(ImagesDat.GRAPHICS_FILE, ImagesDat.SHIELD_OVERLAY, ImagesDat.ATTACK_OVERLAY, ImagesDat.DAMAGE_OVERLAY,
                               ImagesDat.SPECIAL_OVERLAY, ImagesDat.LANDING_DUST_OVERLAY, ImagesDat.LIFTOFF_DUST_OVERLAY)),
            createTable("upgrades", "upgrades.dat", "upgrade type", Reference.UPGRADE_TYPE, UpgradesDat.UPGRADE_COUNT, GameData::getUpgrades,
                        UpgradesDat.class, UpgradesDat.createSchema(),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Set.of()),
            createTable("techdata", "techdata.dat", "tech type", Reference.TECH_TYPE, TechdataDat.TECH_COUNT, GameData::getTechdata,
                        TechdataDat.class, TechdataDat.createSchema(),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Set.of()),
            createTable("orders", "orders.dat", "order", Reference.ORDER, OrdersDat.ORDER_COUNT, GameData::getOrders, OrdersDat.class,
                        OrdersDat.createSchema(),
                        Map.of(OrdersDat.WEAPON, Reference.WEAPON_TYPE,
                               OrdersDat.TECHNOLOGY, Reference.TECH_TYPE,
                               OrdersDat.OBSCURED_ORDER, Reference.ORDER),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Set.of())
    );

    /**
     * The type objects a column refers to by id.
     */
    public enum Reference {
        UNIT_TYPE("UnitType", UnitsDat.UNIT_COUNT),
        WEAPON_TYPE("WeaponType", WeaponsDat.WEAPON_COUNT),
        FLINGY_TYPE("FlingyType", NOT_NULLABLE),
        SPRITE_TYPE("SpriteType", NOT_NULLABLE),
        IMAGE_TYPE("ImageType", NOT_NULLABLE),
        UPGRADE_TYPE("UpgradeType", UpgradesDat.UPGRADE_COUNT),
        TECH_TYPE("TechType", TechdataDat.TECH_COUNT),
        ORDER("Order", OrdersDat.ORDER_COUNT);

        private final @NotNull String typeName;
        private final int noneId;

        Reference(@NotNull String typeName, int noneId) {
            this.typeName = typeName;
            this.noneId   = noneId;
        }

        /**
         * @return the name of the upper layer's type, the global of its constant table ({@code UnitType})
         */
        public @NotNull String getTypeName() {
            return this.typeName;
        }

        /**
         * @return the id that stands for no object, the count of the referred table, or {@link ScriptDatTables#NOT_NULLABLE} for a reference that always names an object
         */
        public int getNoneId() {
            return this.noneId;
        }
    }

    /**
     * One named bit of a flag column.
     */
    public static final class Flag {
        private final @NotNull String constantName;
        private final @NotNull String name;
        private final int mask;

        private Flag(@NotNull String constantName, @NotNull String name, int mask) {
            this.constantName = constantName;
            this.name         = name;
            this.mask         = mask;
        }

        /**
         * @return the name of the SC:R flag constant ({@code BUILDING})
         */
        public @NotNull String getConstantName() {
            return this.constantName;
        }

        /**
         * @return the name of the flag's methods, the column prefix followed by the constant in upper camel case ({@code Building}, {@code GroupZerg})
         */
        public @NotNull String getName() {
            return this.name;
        }

        /**
         * @return the bit of the flag
         */
        public int getMask() {
            return this.mask;
        }
    }

    /**
     * One column of a DAT table.
     */
    public static final class Column {
        private final @NotNull String constantName;
        private final @NotNull String name;
        private final @NotNull String fieldName;
        private final @NotNull DatType type;
        private final int firstId;
        private final int count;
        private final boolean writable;
        private final int limit;
        private final @Nullable Reference reference;
        private final @NotNull List<Flag> flags;

        private Column(@NotNull String constantName, @NotNull String fieldName, @NotNull DatType type, int firstId, int count, boolean writable,
                       int limit, @Nullable Reference reference, @NotNull List<Flag> flags) {
            this.constantName = constantName;
            this.name         = toPascalCase(constantName);
            this.fieldName    = fieldName;
            this.type         = type;
            this.firstId      = firstId;
            this.count        = count;
            this.writable     = writable;
            this.limit        = limit;
            this.reference    = reference;
            this.flags        = flags;
        }

        /**
         * @return the name of the SC:R constant of the field ({@code MINERAL_COST})
         */
        public @NotNull String getConstantName() {
            return this.constantName;
        }

        /**
         * @return the name of the column's methods, the constant in upper camel case ({@code MineralCost})
         */
        public @NotNull String getName() {
            return this.name;
        }

        /**
         * @return the field name of the DAT schema ({@code "Mineral Cost"})
         */
        public @NotNull String getFieldName() {
            return this.fieldName;
        }

        /**
         * @return the element type of the field
         */
        public @NotNull DatType getType() {
            return this.type;
        }

        /**
         * @return the id of the first entry the column holds
         */
        public int getFirstId() {
            return this.firstId;
        }

        /**
         * @return the number of entries the column holds
         */
        public int getCount() {
            return this.count;
        }

        /**
         * @return {@code true} if a script may write the column, {@code false} otherwise
         */
        public boolean isWritable() {
            return this.writable;
        }

        /**
         * @return the first value a write may not store, or {@link #NO_LIMIT} for a column that takes every value of its type
         */
        public int getLimit() {
            return this.limit;
        }

        /**
         * @return the type objects the column refers to by id, or {@code null} for a plain value
         */
        public @Nullable Reference getReference() {
            return this.reference;
        }

        /**
         * @return the named bits of a flag column, empty for any other column
         */
        public @NotNull List<Flag> getFlags() {
            return this.flags;
        }

        /**
         * @return the smallest value of the column's type
         */
        public long getMinimumValue() {
            return switch (this.type) {
                case S8 -> Byte.MIN_VALUE;
                case S16 -> Short.MIN_VALUE;
                default -> 0;
            };
        }

        /**
         * @return the largest value of the column's type
         */
        public long getMaximumValue() {
            return switch (this.type) {
                case U8 -> (1L << Byte.SIZE) - 1;
                case S8 -> Byte.MAX_VALUE;
                case U16 -> (1L << Short.SIZE) - 1;
                case S16 -> Short.MAX_VALUE;
                case U32 -> (1L << Integer.SIZE) - 1;
            };
        }
    }

    /**
     * One DAT table.
     */
    public static final class Table {
        private final @NotNull String name;
        private final @NotNull String fileName;
        private final @NotNull String kind;
        private final @NotNull Reference type;
        private final int count;
        private final @NotNull Function<GameData, DatTable> table;
        private final @NotNull List<Column> columns;

        private Table(@NotNull String name, @NotNull String fileName, @NotNull String kind, @NotNull Reference type, int count,
                      @NotNull Function<GameData, DatTable> table, @NotNull List<Column> columns) {
            this.name     = name;
            this.fileName = fileName;
            this.kind     = kind;
            this.type     = type;
            this.count    = count;
            this.table    = table;
            this.columns  = columns;
        }

        /**
         * @return the name of the table under {@code core.dat} ({@code units})
         */
        public @NotNull String getName() {
            return this.name;
        }

        /**
         * @return the DAT file name ({@code units.dat})
         */
        public @NotNull String getFileName() {
            return this.fileName;
        }

        /**
         * @return the kind of an entry id in messages and parameter names ({@code unit type})
         */
        public @NotNull String getKind() {
            return this.kind;
        }

        /**
         * @return the type objects of the table's entries
         */
        public @NotNull Reference getType() {
            return this.type;
        }

        /**
         * @return the number of entries
         */
        public int getCount() {
            return this.count;
        }

        /**
         * @return the columns in file order
         */
        public @NotNull List<Column> getColumns() {
            return this.columns;
        }

        /**
         * Finds the game's table.
         *
         * @param gameData the game's data
         * @return the table of the game
         */
        public @NotNull DatTable getTable(@NotNull GameData gameData) {
            return this.table.apply(gameData);
        }
    }


    /**
     * @return the tables in the order of {@code core.dat}
     */
    public static @NotNull List<Table> getTables() {
        return TABLES;
    }

    /**
     * Finds a table by its name under {@code core.dat}.
     *
     * @param name the table name ({@code units})
     * @return the table, or {@code null} if no table has the name
     */
    public static @Nullable Table findTable(@NotNull String name) {
        for (Table table : TABLES) {
            if (table.getName().equals(name)) {
                return table;
            }
        }

        return null;
    }

    /**
     * Creates the tables the prelude makes the methods of the type objects from: an array with one object per table, {@code name} (its name under
     * {@code core.dat}), {@code type} (the name of its type objects' table) and {@code columns}, one object per column with {@code name},
     * {@code reference} (the name of the referred type table, or {@code null}), {@code none} (the id of no object, {@code -1} for a reference
     * that always names one), {@code writable} and {@code flags} (an array of {@code name} and {@code mask}, {@code null} for a column that is no
     * flag column).
     *
     * @param context the current context
     * @param scope   the scope of the created objects
     * @return the created array
     */
    public static @NotNull Scriptable createPreludeTables(@NotNull Context context, @NotNull Scriptable scope) {
        List<Object> tables = new ArrayList<>();

        for (Table table : TABLES) {
            List<Object> columns = new ArrayList<>();

            for (Column column : table.getColumns()) {
                Scriptable values = context.newObject(scope);
                Reference reference = column.getReference();

                ScriptableObject.putProperty(values, "name", column.getName());
                ScriptableObject.putProperty(values, "reference", reference == null ? null : reference.getTypeName());
                ScriptableObject.putProperty(values, "none", (double) (reference == null ? NOT_NULLABLE : reference.getNoneId()));
                ScriptableObject.putProperty(values, "writable", column.isWritable());
                ScriptableObject.putProperty(values, "flags", column.getFlags().isEmpty() ? null : createFlags(context, scope, column));
                columns.add(values);
            }

            Scriptable values = context.newObject(scope);

            ScriptableObject.putProperty(values, "name", table.getName());
            ScriptableObject.putProperty(values, "type", table.getType().getTypeName());
            ScriptableObject.putProperty(values, "columns", context.newArray(scope, columns.toArray()));
            tables.add(values);
        }

        return context.newArray(scope, tables.toArray());
    }

    private static @NotNull Scriptable createFlags(@NotNull Context context, @NotNull Scriptable scope, @NotNull Column column) {
        List<Object> flags = new ArrayList<>();

        for (Flag flag : column.getFlags()) {
            Scriptable values = context.newObject(scope);

            ScriptableObject.putProperty(values, "name", flag.getName());
            ScriptableObject.putProperty(values, "mask", (double) Integer.toUnsignedLong(flag.getMask()));
            flags.add(values);
        }

        return context.newArray(scope, flags.toArray());
    }

    /**
     * Creates the description of a table from its SC:R schema and definitions class.
     *
     * @param name        the name of the table under {@code core.dat}
     * @param fileName    the DAT file name
     * @param kind        the kind of an entry id in messages and parameter names
     * @param type        the type objects of the table's entries
     * @param count       the number of entries
     * @param table       finds the table of a game's data
     * @param definitions the class whose public string constants name the schema's fields
     * @param schema      the SC:R schema of the table
     * @param references  the reference of each column that refers to type objects, by field name
     * @param flagClasses the flag class of each flag column, by field name
     * @param firstIds    the first id of each column that holds fewer entries than the table and not from id {@code 0}, by field name
     * @param limits      the first value a write may not store of each validated column, by field name
     * @param readOnly    the field names of the columns a script may not write
     * @return the created description
     * @throws IllegalStateException if a field has no constant or more than one
     */
    private static @NotNull Table createTable(@NotNull String name, @NotNull String fileName, @NotNull String kind, @NotNull Reference type,
                                              int count, @NotNull Function<GameData, DatTable> table, @NotNull Class<?> definitions,
                                              @NotNull DatSchema schema, @NotNull Map<String, Reference> references,
                                              @NotNull Map<String, Class<?>> flagClasses, @NotNull Map<String, Integer> firstIds,
                                              @NotNull Map<String, Integer> limits, @NotNull Set<String> readOnly) {
        Map<String, String> constantNames = readConstantNames(definitions, String.class);
        List<Column> columns = new ArrayList<>();

        for (DatSchema.Field field : schema.getFields()) {
            String constantName = constantNames.get(field.getName());

            if (constantName == null) {
                throw Exceptions.newIllegalStateException("The {0} field {1} has no constant in {2}.", fileName, field.getName(),
                                                          definitions.getSimpleName());
            }

            Class<?> flagClass = flagClasses.get(field.getName());
            List<Flag> flags = flagClass == null ? List.of() : readFlags(constantName, flagClass);

            columns.add(new Column(constantName, field.getName(), field.getType(), firstIds.getOrDefault(field.getName(), 0), field.getCount(),
                                   !readOnly.contains(field.getName()), limits.getOrDefault(field.getName(), NO_LIMIT),
                                   references.get(field.getName()), flags));
        }

        return new Table(name, fileName, kind, type, count, table, List.copyOf(columns));
    }

    /**
     * Reads the public static final constants of a type from a class.
     *
     * @param definitions the class
     * @param valueType   the type of the constants
     * @return the constant name of each value
     * @throws IllegalStateException if two constants have the same value
     */
    private static @NotNull Map<String, String> readConstantNames(@NotNull Class<?> definitions, @NotNull Class<?> valueType) {
        Map<String, String> names = new HashMap<>();

        for (Field field : definitions.getFields()) {
            int modifiers = field.getModifiers();

            if (!Modifier.isStatic(modifiers) || !Modifier.isFinal(modifiers) || field.getType() != valueType) {
                continue;
            }

            String value;

            try {
                value = String.valueOf(field.get(null));
            } catch (IllegalAccessException exception) {
                throw Exceptions.newIllegalStateException(exception, "The constant {0} cannot be read.", field.getName());
            }

            if (names.put(value, field.getName()) != null) {
                throw Exceptions.newIllegalStateException("Two constants of {0} have the value {1}.", definitions.getSimpleName(), value);
            }
        }

        return names;
    }

    /**
     * Reads the flags of a flag column: the int constants of its flag class in the order of their bits, without the bits named
     * {@code FLAG_<bits>}.
     *
     * @param columnConstant the constant of the column ({@code GROUP_FLAGS})
     * @param flagClass      the flag class
     * @return the flags
     */
    private static @NotNull List<Flag> readFlags(@NotNull String columnConstant, @NotNull Class<?> flagClass) {
        String prefix = columnConstant.endsWith(FLAG_COLUMN_SUFFIX)
                        ? toPascalCase(columnConstant.substring(0, columnConstant.length() - FLAG_COLUMN_SUFFIX.length()))
                        : toPascalCase(columnConstant);
        List<Flag> flags = new ArrayList<>();

        for (Field field : flagClass.getFields()) {
            int modifiers = field.getModifiers();

            if (!Modifier.isStatic(modifiers) || !Modifier.isFinal(modifiers) || field.getType() != int.class ||
                field.getName().startsWith(UNNAMED_FLAG_PREFIX)) {
                continue;
            }

            try {
                flags.add(new Flag(field.getName(), prefix + toPascalCase(field.getName()), field.getInt(null)));
            } catch (IllegalAccessException exception) {
                throw Exceptions.newIllegalStateException(exception, "The flag {0} cannot be read.", field.getName());
            }
        }

        flags.sort((first, second) -> Integer.compareUnsigned(first.getMask(), second.getMask()));

        return List.copyOf(flags);
    }

    /**
     * Converts a constant name to upper camel case: {@code MINERAL_COST} becomes {@code MineralCost}, {@code UNUSED1} {@code Unused1}.
     *
     * @param constantName the constant name
     * @return the name, empty for an empty constant name
     */
    private static @NotNull String toPascalCase(@NotNull String constantName) {
        StringBuilder name = new StringBuilder();

        for (String word : constantName.split(CONSTANT_SEPARATOR)) {
            if (!word.isEmpty()) {
                name.append(word.charAt(0)).append(word.substring(1).toLowerCase(Locale.ROOT));
            }
        }

        return name.toString();
    }


    private ScriptDatTables() {
    }
}
