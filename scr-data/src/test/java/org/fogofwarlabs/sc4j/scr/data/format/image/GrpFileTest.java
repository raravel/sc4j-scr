package org.fogofwarlabs.sc4j.scr.data.format.image;

import org.fogofwarlabs.sc4j.scr.core.test.ExternalInput;
import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.data.format.casc.CascStorage;
import org.fogofwarlabs.sc4j.scr.data.format.dat.DatTable;
import org.fogofwarlabs.sc4j.scr.data.format.dat.ImagesDat;
import org.fogofwarlabs.sc4j.scr.data.format.text.TblFile;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/**
 * Tests GRP packet boundaries, trim rectangles and coverage of synthetic frames, image-path lookup and decoded installed GRP frames.
 */
final class GrpFileTest {
    private static @Nullable CascStorage storage;

    private static void openStorage() throws IOException {
        if (storage == null) {
            storage = CascStorage.open(ExternalInput.INSTALLATION.getPath());
        }
    }

    @AfterAll
    static void closeStorage() {
        if (storage != null) {
            storage.close();
        }
    }


    @Test
    void decode_frame_should_keep_trim_rectangle_and_mark_opaque_palette_zero_as_covered() throws IOException {
        ByteBuffer contents = ByteBuffer.allocate(21).order(ByteOrder.LITTLE_ENDIAN);

        contents.putShort((short) 1).putShort((short) 5).putShort((short) 3);
        contents.put((byte) 1).put((byte) 1).put((byte) 3).put((byte) 1).putInt(14);
        contents.putShort((short) 2).put((byte) 1).put((byte) 0).put((byte) 0x81).put((byte) 1).put((byte) 9);

        GrpFile.Frame frame = GrpFile.parse(contents.array()).decodeFrame(0);

        Assertions.assertEquals(5, frame.getWidth());
        Assertions.assertEquals(3, frame.getHeight());
        Assertions.assertEquals(3, frame.getFrameWidth());
        Assertions.assertEquals(1, frame.getFrameHeight());
        Assertions.assertEquals(1, frame.getXOffset());
        Assertions.assertEquals(1, frame.getYOffset());
        Assertions.assertEquals(0, frame.getPixels()[6]);
        Assertions.assertEquals(0, frame.getPixels()[7]);
        Assertions.assertEquals(9, frame.getPixels()[8]);

        byte[] expectedCoverage = new byte[15];

        expectedCoverage[6] = 1;
        expectedCoverage[8] = 1;

        Assertions.assertArrayEquals(expectedCoverage, frame.getCoverage());
    }

    @Test
    void decode_frame_should_decode_long_transparent_packets_before_opaque_packets() throws IOException {
        for (int transparentCount : new int[]{64, 65, 127}) {
            int canvasWidth = transparentCount + 9;
            ByteBuffer data = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN);

            data.putShort((short) 1).putShort((short) canvasWidth).putShort((short) 1);
            data.put((byte) 2).put((byte) 0).put((byte) (transparentCount + 4)).put((byte) 1).putInt(14);
            data.putShort((short) 2);
            data.put((byte) (0x80 | transparentCount)).put((byte) 0x42).put((byte) 5);
            data.put((byte) 2).put((byte) 9).put((byte) 11);

            byte[] expected = new byte[canvasWidth];

            expected[transparentCount + 2] = 5;
            expected[transparentCount + 3] = 5;
            expected[transparentCount + 4] = 9;
            expected[transparentCount + 5] = 11;

            Assertions.assertArrayEquals(expected, GrpFile.parse(data.array()).decodeFrame(0).getPixels(), "Transparent length: " + transparentCount);
        }
    }

    @Test
    void parse_should_read_frame_data_offsets_counted_from_the_base() throws IOException {
        int base = 0x58A364;
        ByteBuffer contents = ByteBuffer.allocate(21).order(ByteOrder.LITTLE_ENDIAN);

        contents.putShort((short) 1).putShort((short) 5).putShort((short) 3);
        contents.put((byte) 1).put((byte) 1).put((byte) 3).put((byte) 1).putInt(base + 14);
        contents.putShort((short) 2).put((byte) 1).put((byte) 0).put((byte) 0x81).put((byte) 1).put((byte) 9);

        GrpFile file = GrpFile.parse(contents.array(), base);
        GrpFile.Frame frame = file.decodeFrame(0);

        Assertions.assertEquals(14, file.getFrameDataOffset(0));
        Assertions.assertEquals(9, frame.getPixels()[8]);
        Assertions.assertTrue(file.isFramePointCovered(0, 1, 0));
        Assertions.assertThrows(IOException.class, () -> GrpFile.parse(contents.array(), base + 0x100).decodeFrame(0));
    }

    @RequiresInstallation
    @Test
    void image_grp_path_resolves_through_images_tbl() throws IOException {
        openStorage();

        CascStorage activeStorage = storage;

        Assertions.assertNotNull(activeStorage);

        TblFile imageNames = TblFile.parse(activeStorage.readFile("arr/images.tbl"));
        DatTable imageTypes = ImagesDat.createSchema().parse(activeStorage.readFile("arr/images.dat"));

        Assertions.assertTrue(imageNames.getStringCount() > 500);

        int imageStringReference = imageTypes.get("GRP File", 0);

        Assertions.assertEquals(1, imageStringReference);

        String groupPath = "unit/" + imageNames.getString(imageStringReference - 1).replace('\\', '/');

        Assertions.assertEquals("unit/zerg/avenger.grp", groupPath);
        Assertions.assertTrue(activeStorage.hasFile(groupPath));
    }

    @RequiresInstallation
    @Test
    void decode_all_frames_should_decode_visible_marine_frames() throws IOException {
        openStorage();

        CascStorage activeStorage = storage;

        Assertions.assertNotNull(activeStorage);

        GrpFile groupFile = GrpFile.parse(activeStorage.readFile("unit/terran/marine.grp"));
        List<GrpFile.Frame> frames = groupFile.decodeAllFrames();

        Assertions.assertTrue(groupFile.getFrameCount() > 0);
        Assertions.assertTrue(groupFile.getWidth() > 0);
        Assertions.assertTrue(groupFile.getHeight() > 0);
        Assertions.assertEquals(groupFile.getFrameCount(), frames.size());

        int nonTransparentPixelCount = 0;

        for (byte pixel : frames.get(0).getPixels()) {
            if (pixel != 0) {
                nonTransparentPixelCount++;
            }
        }

        Assertions.assertTrue(nonTransparentPixelCount > 0, "The first Marine frame has no visible pixels.");
    }

    @RequiresInstallation
    @Test
    void decode_all_frames_should_decode_every_control_tower_frame() throws IOException {
        openStorage();

        CascStorage activeStorage = storage;

        Assertions.assertNotNull(activeStorage);

        GrpFile group = GrpFile.parse(activeStorage.readFile("unit/terran/control.grp"));

        Assertions.assertEquals(group.getFrameCount(), group.decodeAllFrames().size());
    }
}
