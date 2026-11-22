package org.fogofwarlabs.sc4j.scr.game.state;

import org.fogofwarlabs.sc4j.engine.content.definition.UnitTrait;
import org.fogofwarlabs.sc4j.engine.content.registry.ContentRegistry;
import org.fogofwarlabs.sc4j.engine.content.table.GameContent;
import org.fogofwarlabs.sc4j.engine.content.table.TypeTable;
import org.fogofwarlabs.sc4j.engine.content.type.ContentType;
import org.fogofwarlabs.sc4j.engine.entity.column.IntColumn;
import org.fogofwarlabs.sc4j.scr.content.*;
import org.fogofwarlabs.sc4j.scr.content.load.ScrContentImporter;
import org.fogofwarlabs.sc4j.scr.content.table.ScrDatKind;
import org.fogofwarlabs.sc4j.scr.core.test.ExternalInput;
import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.data.format.casc.CascStorage;
import org.fogofwarlabs.sc4j.scr.data.format.dat.DatTable;
import org.fogofwarlabs.sc4j.scr.data.format.dat.ImagesDat;
import org.fogofwarlabs.sc4j.scr.data.format.dat.UnitsDat;
import org.fogofwarlabs.sc4j.scr.data.format.dat.WeaponsDat;
import org.fogofwarlabs.sc4j.scr.data.format.text.TblFile;
import org.fogofwarlabs.sc4j.scr.data.type.constant.ImageIds;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitGroupFlags;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitTypeFlags;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Checks installed game data loading: console textures, image and unit tables, image file lookups, special overlay offsets and table
 * copies.
 */
@RequiresInstallation
final class GameDataTest {
    private static final int TEXTURES_PER_RACE = 2;
    /**
     * The console textures of the three races and the bytes of a full and of an empty progress bar texture.
     */
    private static final int CONSOLE_TEXTURE_COUNT = 6;
    private static final int FULL_TEXTURE_SIZE = 776;
    private static final int EMPTY_TEXTURE_SIZE = 4016;
    /**
     * The number of units whose target acquisition range the loading changes.
     */
    private static final int CHANGED_RANGE_COUNT = 45;
    private static final int BYTE_MASK = 0xFF;
    /**
     * The elements past a field's end that the overrun check reads.
     */
    private static final int OVERRUN_ELEMENTS = 8;
    private static final int GAME_COPY_HIT_POINT_INCREASE = 256;
    private static final int SCENARIO_COPY_HIT_POINT_INCREASE = 512;
    /**
     * The byte offset of the frame count in a special overlay file.
     */
    private static final int OVERLAY_FRAME_COUNT_OFFSET = 0;
    /**
     * The byte offset of the position count in a special overlay file.
     */
    private static final int OVERLAY_POSITION_COUNT_OFFSET = 4;
    /**
     * The byte offset of the frame offset DWORDs in a special overlay file.
     */
    private static final int OVERLAY_FRAME_OFFSETS_OFFSET = 8;
    /**
     * The bytes of one overlay position: the horizontal offset byte followed by the vertical offset byte.
     */
    private static final int OVERLAY_POSITION_SIZE = 2;
    /**
     * The frames and positions past the counts of a special overlay file that the overlay read check reads.
     */
    private static final int PAST_COUNT_READS = 2;
    /**
     * An image whose {@code images.dat} Special Overlay is {@code 0}: it has no special overlay file.
     */
    private static final int IMAGE_WITHOUT_SPECIAL_OVERLAY = 956;

    private static final @NotNull String CONSOLE_DIRECTORY = "SD/game/consoles/";
    private static final @NotNull String TARGET_ACQUISITION_RANGE = "Target Acquisition Range";
    private static final @NotNull String HASH_ALGORITHM = "SHA-256";
    /**
     * The digests of the damage overlay counts of every image and of the initialized target acquisition ranges of every unit.
     */
    private static final @NotNull String DAMAGE_OVERLAY_COUNTS_HASH = "be5b5dce5c4c05a59e05dfba0e08eed8336709e570a4e9f3ec030dd7a6ddba4f";
    private static final @NotNull String TARGET_ACQUISITION_RANGES_HASH = "8612a62ef04f60071841e1a6a44146eb590ba5020bfb616064f90168f85a254e";

    private static void assertExtensionColumns(@NotNull ScrDatKind kind, @NotNull DatTable installed, @NotNull TypeTable table) {
        for (String field : kind.getExtensionFields()) {
            IntColumn column = table.getExtensionColumns().get(kind.getExtensionKey(field));

            for (int element = 0; element < installed.getCount(field); element++) {
                Assertions.assertEquals(installed.get(field, element), column.get(element + kind.getFirstType(field)),
                                        kind + " " + field + "[" + element + "]");
            }
        }
    }

    private static void assertSameTable(@NotNull ScrDatKind kind, @NotNull DatTable installed, @NotNull DatTable copy) {
        for (String field : kind.getFields()) {
            int count = installed.getCount(field);

            for (int element = 0; element < count; element++) {
                Assertions.assertEquals(installed.get(field, element), copy.get(field, element), kind + " " + field + "[" + element + "]");
            }

            for (int element : new int[]{-1, count, count + 1, count + OVERRUN_ELEMENTS}) {
                Integer expected = readNativeElementOrNull(installed, field, element);

                if (expected != null) {
                    Assertions.assertEquals(expected, readNativeElementOrNull(copy, field, element), kind + " " + field + " overrun " + element);
                }
            }
        }

        for (int block = 0; block < installed.getBlockCount(); block++) {
            for (int offset = 0; offset < installed.getBlockSize(block); offset++) {
                Assertions.assertEquals(installed.readBlockByte(block, offset), copy.readBlockByte(block, offset),
                                        kind + " block " + block + "+" + offset);
            }
        }
    }

    private static @Nullable Integer readNativeElementOrNull(@NotNull DatTable table, @NotNull String field, int element) {
        try {
            return table.readNativeElement(field, element);
        } catch (UnsupportedOperationException | IndexOutOfBoundsException exception) {
            return null;
        }
    }


    @Test
    void load_replay_progress_resources_should_return_copies_of_all_race_console_textures() throws IOException {
        try (CascStorage storage = CascStorage.open(ExternalInput.INSTALLATION.getPath()); GameData gameData = GameData.load(storage)) {
            byte[][] resources = gameData.loadReplayProgressResources();

            Assertions.assertEquals(CONSOLE_TEXTURE_COUNT, resources.length);

            String[] consoleRaces = {"zerg", "terran", "protoss"};

            for (int raceIndex = 0; raceIndex < consoleRaces.length; raceIndex++) {
                Assertions.assertArrayEquals(storage.readFile(CONSOLE_DIRECTORY + consoleRaces[raceIndex] + "/pbrfull.DDS"),
                                             resources[raceIndex * TEXTURES_PER_RACE]);
                Assertions.assertArrayEquals(storage.readFile(CONSOLE_DIRECTORY + consoleRaces[raceIndex] + "/pbrempt.DDS"),
                                             resources[raceIndex * TEXTURES_PER_RACE + 1]);
                Assertions.assertEquals(FULL_TEXTURE_SIZE, resources[raceIndex * TEXTURES_PER_RACE].length);
                Assertions.assertEquals(EMPTY_TEXTURE_SIZE, resources[raceIndex * TEXTURES_PER_RACE + 1].length);
            }

            resources[0][0] = 0;

            Assertions.assertEquals('D', gameData.loadReplayProgressResources()[0][0]);
        }
    }

    @Test
    void image_damage_overlay_counts_match_native_table_hash() throws IOException, NoSuchAlgorithmException {
        try (GameData gameData = GameData.loadFrom(ExternalInput.INSTALLATION.getPath())) {
            byte[] nativeCounts = new byte[ImagesDat.IMAGE_COUNT];

            for (int imageIndex = 0; imageIndex < nativeCounts.length; imageIndex++) {
                nativeCounts[imageIndex] = (byte) gameData.getImageDamageOverlayCount(imageIndex);

                Assertions.assertEquals(nativeCounts[imageIndex] & BYTE_MASK, gameData.getImageDamageOverlayCount(imageIndex));
            }

            Assertions.assertEquals(DAMAGE_OVERLAY_COUNTS_HASH,
                                    HexFormat.of().formatHex(MessageDigest.getInstance(HASH_ALGORITHM).digest(nativeCounts)));
        }
    }

    @Test
    void load_should_initialize_target_acquisition_ranges_to_native_values() throws IOException, NoSuchAlgorithmException {
        try (CascStorage storage = CascStorage.open(ExternalInput.INSTALLATION.getPath()); GameData gameData = GameData.load(storage)) {
            DatTable rawUnits = UnitsDat.createSchema().parse(storage.readFile("arr/units.dat"));

            Assertions.assertEquals(0, rawUnits.get(TARGET_ACQUISITION_RANGE, 0));
            Assertions.assertEquals(4, gameData.getUnits().get(TARGET_ACQUISITION_RANGE, 0));
            Assertions.assertEquals(5, rawUnits.get(TARGET_ACQUISITION_RANGE, 3));
            Assertions.assertEquals(6, gameData.getUnits().get(TARGET_ACQUISITION_RANGE, 3));
            Assertions.assertEquals(8, rawUnits.get(TARGET_ACQUISITION_RANGE, 23));
            Assertions.assertEquals(7, gameData.getUnits().get(TARGET_ACQUISITION_RANGE, 23));
            Assertions.assertEquals(12, gameData.getUnits().get(TARGET_ACQUISITION_RANGE, 30));

            int changedRanges = 0;
            byte[] initializedRanges = new byte[UnitsDat.UNIT_COUNT];

            for (int unitIndex = 0; unitIndex < UnitsDat.UNIT_COUNT; unitIndex++) {
                initializedRanges[unitIndex] = (byte) gameData.getUnits().get(TARGET_ACQUISITION_RANGE, unitIndex);

                if (rawUnits.get(TARGET_ACQUISITION_RANGE, unitIndex) != gameData.getUnits().get(TARGET_ACQUISITION_RANGE, unitIndex)) {
                    changedRanges++;
                }
            }

            Assertions.assertEquals(CHANGED_RANGE_COUNT, changedRanges);
            Assertions.assertEquals(TARGET_ACQUISITION_RANGES_HASH,
                                    HexFormat.of().formatHex(MessageDigest.getInstance(HASH_ALGORITHM).digest(initializedRanges)));
        }
    }

    @Test
    void grp_lookups_resolve_one_based_image_references_and_cache_files() throws IOException {
        try (CascStorage storage = CascStorage.open(ExternalInput.INSTALLATION.getPath()); GameData gameData = GameData.load(storage)) {
            Assertions.assertEquals("unit/zerg/avenger.grp", gameData.getGrpPath(0));
            Assertions.assertEquals("unit/zerg/defiler.grp", gameData.getGrpPath(13));
            Assertions.assertTrue(gameData.getGrpFile(0).getFrameCount() > 0);
            Assertions.assertSame(gameData.getGrpFile(0), gameData.getGrpFile(0));
        }
    }

    @Test
    void get_image_special_overlay_offset_should_read_the_resource_bytes_past_the_frame_and_position_counts() throws IOException {
        try (CascStorage storage = CascStorage.open(ExternalInput.INSTALLATION.getPath()); GameData gameData = GameData.load(storage)) {
            TblFile names = TblFile.parse(storage.readFile("arr/images.tbl"));
            int resourceIndex = gameData.getImages().get("Special Overlay", ImageIds.VESPENE_GEYSER);
            byte[] specialOverlay = storage.readFile("unit/" + names.getString(resourceIndex - 1).replace('\\', '/'));
            ByteBuffer contents = ByteBuffer.wrap(specialOverlay).order(ByteOrder.LITTLE_ENDIAN);
            int frameCount = contents.getInt(OVERLAY_FRAME_COUNT_OFFSET);
            int positionCount = contents.getInt(OVERLAY_POSITION_COUNT_OFFSET);
            int pastCountReads = 0;

            for (int frameIndex = 0; frameIndex <= frameCount + PAST_COUNT_READS; frameIndex++) {
                for (int positionIndex = 0; positionIndex <= positionCount + PAST_COUNT_READS; positionIndex++) {
                    long frameOffsetPosition = OVERLAY_FRAME_OFFSETS_OFFSET + (long) frameIndex * Integer.BYTES;
                    long position = frameOffsetPosition + Integer.BYTES > specialOverlay.length
                                    ? Long.MAX_VALUE
                                    : Integer.toUnsignedLong(contents.getInt((int) frameOffsetPosition) + positionIndex * OVERLAY_POSITION_SIZE);
                    int frame = frameIndex;
                    int overlay = positionIndex;

                    if (position + OVERLAY_POSITION_SIZE > specialOverlay.length) {
                        Assertions.assertThrows(IOException.class,
                                                () -> gameData.getImageSpecialOverlayOffset(ImageIds.VESPENE_GEYSER, frame, overlay));
                        continue;
                    }

                    int coordinate = gameData.getImageSpecialOverlayOffset(ImageIds.VESPENE_GEYSER, frameIndex, positionIndex);

                    Assertions.assertEquals(specialOverlay[(int) position], (short) coordinate);
                    Assertions.assertEquals(specialOverlay[(int) position + 1], (short) (coordinate >>> Short.SIZE));

                    if (frameIndex >= frameCount || positionIndex >= positionCount) {
                        pastCountReads++;
                    }
                }
            }

            Assertions.assertTrue(pastCountReads > 0, "A read past the counts stays inside the file.");

            Assertions.assertThrows(IOException.class, () -> gameData.getImageSpecialOverlayOffset(ImageIds.VESPENE_GEYSER, -1, 0));
        }
    }

    @Test
    void get_image_special_overlay_offset_of_an_image_without_a_special_overlay_should_fail() throws IOException {
        try (GameData gameData = GameData.loadFrom(ExternalInput.INSTALLATION.getPath())) {
            Assertions.assertEquals(0, gameData.getImages().get("Special Overlay", IMAGE_WITHOUT_SPECIAL_OVERLAY));
            Assertions.assertThrows(IOException.class, () -> gameData.getImageSpecialOverlayOffset(IMAGE_WITHOUT_SPECIAL_OVERLAY, 0, 0));
        }
    }

    @Test
    void game_copy_tables_hold_the_installed_values_in_the_native_layout() throws IOException {
        try (GameData installed = GameData.loadFrom(ExternalInput.INSTALLATION.getPath())) {
            GameData copy = installed.createGameCopy();

            assertSameTable(ScrDatKind.UNITS, installed.getUnits(), copy.getUnits());
            assertSameTable(ScrDatKind.WEAPONS, installed.getWeapons(), copy.getWeapons());
            assertSameTable(ScrDatKind.FLINGY, installed.getFlingy(), copy.getFlingy());
            assertSameTable(ScrDatKind.SPRITES, installed.getSprites(), copy.getSprites());
            assertSameTable(ScrDatKind.IMAGES, installed.getImages(), copy.getImages());
            assertSameTable(ScrDatKind.UPGRADES, installed.getUpgrades(), copy.getUpgrades());
            assertSameTable(ScrDatKind.TECHDATA, installed.getTechdata(), copy.getTechdata());
            assertSameTable(ScrDatKind.ORDERS, installed.getOrders(), copy.getOrders());
            assertSameTable(ScrDatKind.SOUNDS, installed.getSfxdata(), copy.getSfxdata());
        }
    }

    @Test
    void game_copy_writes_reach_the_game_content_and_its_views() throws IOException {
        try (GameData installed = GameData.loadFrom(ExternalInput.INSTALLATION.getPath())) {
            GameContent content = GameContent.create(installed.getGameDefinition().getContent(), ScrContentImporter.PLAYER_COUNT);
            GameData copy = installed.createGameCopy(content);
            int marine = ScrUnitIds.TERRAN_MARINE;
            int hitPoints = installed.getUnitTypes().getHitPoints(marine);

            copy.getUnits().set(UnitsDat.HIT_POINTS, marine, hitPoints + GAME_COPY_HIT_POINT_INCREASE);
            copy.getUnits().set(UnitsDat.GROUND_WEAPON, marine, ScrWeapons.NATIVE_NONE);
            copy.getUnits().set(UnitsDat.FLAGS, marine, UnitTypeFlags.BUILDING);
            copy.getUnits().set(UnitsDat.GROUP_FLAGS, marine, UnitGroupFlags.ZERG);

            Assertions.assertEquals(hitPoints + GAME_COPY_HIT_POINT_INCREASE, content.getUnitTypes().getHitPoints(marine));
            Assertions.assertEquals(hitPoints + GAME_COPY_HIT_POINT_INCREASE, copy.getUnitTypes().getHitPoints(marine));
            Assertions.assertEquals(ContentType.NO_INDEX, content.getUnitTypes().getGroundWeaponIndex(marine));
            Assertions.assertEquals(ScrWeapons.NATIVE_NONE, copy.getUnitTypes().getGroundWeapon(marine));
            Assertions.assertTrue(content.getUnitTypes().hasTrait(marine, UnitTrait.BUILDING));
            Assertions.assertEquals(UnitTypeFlags.BUILDING, copy.getUnitTypes().getFlags(marine));
            Assertions.assertEquals(ScrRaces.ZERG.getIndex(), content.getUnitTypes().getFactionIndex(marine));
            Assertions.assertEquals(hitPoints, installed.getUnitTypes().getHitPoints(marine));
            Assertions.assertThrows(IllegalArgumentException.class,
                                    () -> installed.createGameCopy(GameContent.create(new ContentRegistry().build(),
                                                                                      ScrContentImporter.PLAYER_COUNT)));
        }
    }

    @Test
    void game_definition_content_starts_with_the_installed_dat_values() throws IOException {
        try (GameData installed = GameData.loadFrom(ExternalInput.INSTALLATION.getPath())) {
            GameContent content = GameContent.create(installed.getGameDefinition().getContent(), ScrContentImporter.PLAYER_COUNT);
            DatTable units = installed.getUnits();

            Assertions.assertSame(installed.getGameDefinition(), installed.createGameCopy().getGameDefinition());

            assertExtensionColumns(ScrDatKind.UNITS, units, content.getUnitTypes());
            assertExtensionColumns(ScrDatKind.WEAPONS, installed.getWeapons(), content.getWeaponTypes());
            assertExtensionColumns(ScrDatKind.FLINGY, installed.getFlingy(), content.getMotionProfiles());
            assertExtensionColumns(ScrDatKind.SPRITES, installed.getSprites(), content.getSpriteTypes());
            assertExtensionColumns(ScrDatKind.IMAGES, installed.getImages(), content.getImageTypes());
            assertExtensionColumns(ScrDatKind.UPGRADES, installed.getUpgrades(), content.getUpgradeTypes());
            assertExtensionColumns(ScrDatKind.TECHDATA, installed.getTechdata(), content.getTechTypes());
            assertExtensionColumns(ScrDatKind.ORDERS, installed.getOrders(), content.getOrderTypes());
            assertExtensionColumns(ScrDatKind.SOUNDS, installed.getSfxdata(), content.getSoundTypes());

            for (int type = 0; type < UnitsDat.UNIT_COUNT; type++) {
                Assertions.assertEquals(units.get(UnitsDat.HIT_POINTS, type), content.getUnitTypes().getHitPoints(type));
                Assertions.assertEquals(units.get(UnitsDat.MINERAL_COST, type),
                                        content.getUnitTypes().getCost(type, ScrResources.MINERALS.getIndex()));
                Assertions.assertEquals(units.get(UnitsDat.GAS_COST, type), content.getUnitTypes().getCost(type, ScrResources.GAS.getIndex()));
                Assertions.assertEquals(units.get(UnitsDat.GROUND_WEAPON, type),
                                        ScrNativeIds.toNativeWeapon(content.getUnitTypes().getGroundWeaponIndex(type)));
                Assertions.assertEquals(units.get(UnitsDat.AIR_WEAPON, type),
                                        ScrNativeIds.toNativeWeapon(content.getUnitTypes().getAirWeaponIndex(type)));
                Assertions.assertEquals(units.get(UnitsDat.TARGET_ACQUISITION_RANGE, type), content.getUnitTypes().getTargetAcquisitionRange(type));
                Assertions.assertEquals(units.get(UnitsDat.BUILD_TIME, type), content.getUnitTypes().getBuildTime(type));
            }
        }
    }

    @Test
    void game_and_scenario_copies_see_only_their_own_table_writes() throws IOException {
        try (CascStorage storage = CascStorage.open(ExternalInput.INSTALLATION.getPath()); GameData installed = GameData.load(storage)) {
            int marine = ScrUnitIds.TERRAN_MARINE;
            int hitPoints = installed.getUnitTypes().getHitPoints(marine);
            GameData first = installed.createGameCopy();
            GameData second = installed.createGameCopy();

            first.getUnits().set(UnitsDat.HIT_POINTS, marine, hitPoints + GAME_COPY_HIT_POINT_INCREASE);

            Assertions.assertEquals(hitPoints + GAME_COPY_HIT_POINT_INCREASE, first.getUnitTypes().getHitPoints(marine));
            Assertions.assertEquals(hitPoints, second.getUnitTypes().getHitPoints(marine));
            Assertions.assertEquals(hitPoints, installed.getUnitTypes().getHitPoints(marine));

            int damage = installed.getWeaponTypes().getDamageAmount(0);
            GameData scenario = installed.createGameCopy();

            scenario.getUnits().set(UnitsDat.HIT_POINTS, marine, hitPoints + SCENARIO_COPY_HIT_POINT_INCREASE);
            scenario.getWeapons().set(WeaponsDat.DAMAGE_AMOUNT, 0, damage + 1);

            Assertions.assertEquals(hitPoints + SCENARIO_COPY_HIT_POINT_INCREASE, scenario.getUnitTypes().getHitPoints(marine));
            Assertions.assertEquals(damage + 1, scenario.getWeaponTypes().getDamageAmount(0));
            Assertions.assertEquals(hitPoints, installed.getUnitTypes().getHitPoints(marine));
            Assertions.assertEquals(damage, installed.getWeaponTypes().getDamageAmount(0));
            Assertions.assertEquals(hitPoints + GAME_COPY_HIT_POINT_INCREASE, first.getUnitTypes().getHitPoints(marine));
            Assertions.assertEquals(damage, first.getWeaponTypes().getDamageAmount(0));
        }
    }
}
