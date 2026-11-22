package org.fogofwarlabs.sc4j.scr.data.format.image;

import org.fogofwarlabs.sc4j.scr.core.test.ExternalInput;
import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.data.format.casc.CascStorage;
import org.fogofwarlabs.sc4j.scr.data.format.dat.DatTable;
import org.fogofwarlabs.sc4j.scr.data.format.dat.ImagesDat;
import org.fogofwarlabs.sc4j.scr.data.format.text.TblFile;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

/**
 * Verifies that anim files parse entries, frames and layers, and match the installed GRP frames and image relations.
 */
final class AnimFileTest {
    private static final int HEADER_SIZE = 332;
    private static final int LAYER_NAMES_OFFSET = 12;
    private static final int LAYER_NAME_SIZE = 32;
    private static final int TEXTURE_SIZE = 136;
    private static final int HD_SCALE = 4;
    private static final int HD2_SCALE = 2;
    private static final int IMAGE_COUNT = 999;

    private static @NotNull ByteBuffer createHeader(int contentSize,
                                                    int imageScale, int entryType,
                                                    int entryCount,
                                                    @NotNull String @NotNull ... layerNames) {
        ByteBuffer contents = ByteBuffer.allocate(contentSize).order(ByteOrder.LITTLE_ENDIAN);

        contents.put("ANIM".getBytes(StandardCharsets.US_ASCII)).put((byte) imageScale).put((byte) entryType).putShort((short) 0);
        contents.putShort((short) layerNames.length).putShort((short) entryCount);

        for (int layerIndex = 0; layerIndex < layerNames.length; layerIndex++) {
            contents.put(LAYER_NAMES_OFFSET + layerIndex * LAYER_NAME_SIZE, layerNames[layerIndex].getBytes(StandardCharsets.US_ASCII));
        }

        contents.position(HEADER_SIZE);

        return contents;
    }


    @Test
    void parse_should_read_single_hd_entry_with_frames_and_layers() throws IOException {
        ByteBuffer contents = createHeader(HEADER_SIZE + 36 + TEXTURE_SIZE + 32, HD2_SCALE, 2, 1, "diffuse", "teamcolor");
        int textureOffset = HEADER_SIZE + 36;
        int frameOffset = textureOffset + TEXTURE_SIZE;

        contents.putShort((short) 2).putShort((short) 0xFFFF).putShort((short) 196).putShort((short) 182).putInt(frameOffset);
        contents.putInt(textureOffset).putInt(TEXTURE_SIZE).putShort((short) 4).putShort((short) 4);
        contents.putInt(0).putInt(0).putShort((short) 0).putShort((short) 0);
        contents.putInt(textureOffset, DdsFileScenario.MAGIC)
                .putInt(textureOffset + DdsFileScenario.DESCRIPTOR_SIZE_FIELD, DdsFileScenario.DESCRIPTOR_SIZE)
                .putInt(textureOffset + DdsFileScenario.HEIGHT_FIELD, 4)
                .putInt(textureOffset + DdsFileScenario.WIDTH_FIELD, 4)
                .putInt(textureOffset + DdsFileScenario.PIXEL_FLAGS_FIELD, DdsFileScenario.PIXEL_FORMAT_FOUR_CHARACTER_CODE)
                .putInt(textureOffset + DdsFileScenario.FOUR_CHARACTER_CODE_FIELD, DdsFileScenario.FOUR_CHARACTER_DXT1);
        contents.position(frameOffset);
        contents.putShort((short) 200).putShort((short) 724).putShort((short) 53).putShort((short) -3)
                .putShort((short) 94).putShort((short) 80).putInt(9);
        contents.putShort((short) 4).putShort((short) 8).putShort((short) 1).putShort((short) 2).putShort((short) 3).putShort((short) 4).putInt(1);

        AnimFile file = AnimFile.parse(contents.array());

        Assertions.assertEquals(HD2_SCALE, file.getScale());
        Assertions.assertEquals(2, file.getLayerCount());
        Assertions.assertEquals(1, file.findLayer("teamcolor"));
        Assertions.assertEquals(-1, file.findLayer("normal"));

        AnimFile.Entry entry = file.getEntry(0);

        Assertions.assertFalse(entry.isReference());
        Assertions.assertEquals(196, entry.getWidth());
        Assertions.assertEquals(182, entry.getHeight());
        Assertions.assertEquals(2, entry.getFrameCount());
        Assertions.assertEquals(724, entry.getFrameY(0));
        Assertions.assertEquals(-3, entry.getFrameYOffset(0));
        Assertions.assertEquals(80, entry.getFrameHeight(0));
        Assertions.assertEquals(9, entry.getFrameFlags(0));
        Assertions.assertEquals(3, entry.getFrameWidth(1));
        Assertions.assertTrue(entry.hasLayer(0));
        Assertions.assertFalse(entry.hasLayer(1));
        Assertions.assertNull(entry.getLayerTexture(1));

        DdsFile diffuse = entry.getLayerTexture(0);

        Assertions.assertNotNull(diffuse);
        Assertions.assertEquals(DdsFile.Format.DXT1, diffuse.getFormat());
    }

    @Test
    void parse_should_read_offset_table_and_reference_entries() throws IOException {
        ByteBuffer contents = createHeader(HEADER_SIZE + 8 + 16 + 12, 1, 1, 2, "diffuse");
        int firstEntryOffset = HEADER_SIZE + 8;

        contents.putInt(firstEntryOffset).putInt(firstEntryOffset);
        contents.putShort(firstEntryOffset, (short) 0).putShort(firstEntryOffset + 2, (short) 11);

        AnimFile file = AnimFile.parse(contents.array());

        Assertions.assertEquals(2, file.getEntryCount());
        Assertions.assertTrue(file.getEntry(1).isReference());
        Assertions.assertEquals(11, file.getEntry(1).getReferenceImage());
        Assertions.assertEquals(0, file.getEntry(1).getFrameCount());
    }

    @Test
    void find_content_size_should_ask_for_the_records_then_give_the_end_of_frames_and_layers() {
        int textureOffset = HEADER_SIZE + 36;
        int frameOffset = textureOffset + TEXTURE_SIZE;
        int size = frameOffset + 16;
        ByteBuffer contents = createHeader(size + 8, HD_SCALE, 2, 1, "diffuse", "teamcolor");

        contents.putShort((short) 1).putShort((short) 0).putShort((short) 16).putShort((short) 16).putInt(frameOffset);
        contents.putInt(textureOffset).putInt(TEXTURE_SIZE).putShort((short) 4).putShort((short) 4);

        byte[] data = contents.array();

        Assertions.assertEquals(HEADER_SIZE, AnimFile.findContentSize(data, 0));
        Assertions.assertEquals(HEADER_SIZE + Integer.BYTES, AnimFile.findContentSize(data, HEADER_SIZE));
        Assertions.assertEquals(HEADER_SIZE + 36, AnimFile.findContentSize(data, HEADER_SIZE + Integer.BYTES));
        Assertions.assertEquals(size, AnimFile.findContentSize(data, HEADER_SIZE + 36));
        Assertions.assertEquals(size, AnimFile.findContentSize(data, data.length));
        Assertions.assertEquals(-1, AnimFile.findContentSize(new byte[HEADER_SIZE], HEADER_SIZE));
    }

    @Test
    void parse_should_reject_frames_outside_the_content_and_short_headers() {
        ByteBuffer contents = createHeader(HEADER_SIZE + 24, HD_SCALE, 2, 1, "diffuse");

        contents.putShort((short) 5).putShort((short) -1).putShort((short) 4).putShort((short) 4).putInt(300);

        Assertions.assertThrows(IOException.class, () -> AnimFile.parse(contents.array()));
        Assertions.assertThrows(IOException.class, () -> AnimFile.parse(new byte[16]));
    }

    @RequiresInstallation
    @Test
    void installed_hd2_anims_match_classic_grp_frames_and_image_relations() throws IOException {
        try (CascStorage storage = CascStorage.open(ExternalInput.INSTALLATION.getPath())) {
            DatTable images = ImagesDat.createSchema().parse(storage.readFile("arr/images.dat"));
            TblFile names = TblFile.parse(storage.readFile("arr/images.tbl"));
            ImageRelations relations = ImageRelations.parse(storage.readFile("images.rel"));

            Assertions.assertEquals(IMAGE_COUNT, relations.getImageCount());

            Map<Integer, Integer> relationTypeCounts = new TreeMap<>();
            int animationCount = 0;

            for (int imageIndex = 0; imageIndex < ImagesDat.IMAGE_COUNT; imageIndex++) {
                relationTypeCounts.merge(relations.getType(imageIndex), 1, Integer::sum);

                boolean hasOwnAnimation = storage.hasFile(String.format("HD2/anim/main_%03d.anim", imageIndex));
                boolean sharesAnimation = relations.getType(imageIndex) == ImageRelations.TYPE_SHARED_ANIMATION;

                Assertions.assertEquals(!sharesAnimation, hasOwnAnimation,
                                        "Image " + imageIndex + " has its own anim unless its relation shares one.");

                if (sharesAnimation) {
                    int relatedImageIndex = relations.getRelatedImage(imageIndex);
                    String relatedPath = "unit/" + names.getString(images.get("GRP File", relatedImageIndex) - 1).replace('\\', '/');
                    String imagePath = "unit/" + names.getString(images.get("GRP File", imageIndex) - 1).replace('\\', '/');

                    Assertions.assertArrayEquals(storage.readFile(relatedPath), storage.readFile(imagePath),
                                                 "Shared anims share identical GRP content.");
                }

                int animationImage = relations.getAnimationImage(imageIndex);
                AnimFile file = AnimFile.parse(storage.readFile(String.format("HD2/anim/main_%03d.anim", animationImage)));

                Assertions.assertEquals(HD2_SCALE, file.getScale());

                AnimFile.Entry entry = file.getEntry(0);
                String groupPath = "unit/" + names.getString(images.get("GRP File", imageIndex) - 1).replace('\\', '/');

                Assertions.assertEquals(GrpFile.parse(storage.readFile(groupPath)).getFrameCount(), entry.getFrameCount(),
                                        "Image " + imageIndex + " frames");
                Assertions.assertTrue(entry.hasLayer(file.findLayer("diffuse")), "Image " + imageIndex + " diffuse");

                animationCount += hasOwnAnimation ? 1 : 0;
            }

            Assertions.assertEquals(868, animationCount);
            Assertions.assertEquals(Map.of(1, 561, 4, 63, 8, 230, 16, 14, 512, 131), relationTypeCounts);

            AnimFile highResolutionAnimation = AnimFile.parse(storage.readFile("anim/main_000.anim"));
            AnimFile reducedResolutionAnimation = AnimFile.parse(storage.readFile("HD2/anim/main_000.anim"));

            Assertions.assertEquals(HD_SCALE, highResolutionAnimation.getScale());

            for (int frameIndex = 0; frameIndex < highResolutionAnimation.getEntry(0).getFrameCount(); frameIndex++) {
                Assertions.assertEquals(highResolutionAnimation.getEntry(0).getFrameX(frameIndex),
                                        reducedResolutionAnimation.getEntry(0).getFrameX(frameIndex),
                                        "HD2 frame rectangles are in HD units.");
                Assertions.assertEquals(highResolutionAnimation.getEntry(0).getFrameWidth(frameIndex),
                                        reducedResolutionAnimation.getEntry(0).getFrameWidth(frameIndex));
            }

            Assertions.assertEquals(highResolutionAnimation.getEntry(0).getLayerWidth(0),
                                    reducedResolutionAnimation.getEntry(0).getLayerWidth(0) * (HD_SCALE / HD2_SCALE));
        }
    }
}
