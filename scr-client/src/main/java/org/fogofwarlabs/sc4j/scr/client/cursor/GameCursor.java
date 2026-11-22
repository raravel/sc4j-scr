package org.fogofwarlabs.sc4j.scr.client.cursor;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.client.cursor.CursorSurface;
import org.fogofwarlabs.sc4j.engine.client.display.DetailSet;
import org.fogofwarlabs.sc4j.engine.resource.graphics.Picture;
import org.fogofwarlabs.sc4j.scr.data.format.casc.CascStorage;
import org.fogofwarlabs.sc4j.scr.render.OverlayPass;
import org.fogofwarlabs.sc4j.scr.render.ui.InterfaceAssets;
import org.fogofwarlabs.sc4j.scr.render.ui.InterfaceImage;
import org.fogofwarlabs.sc4j.scr.render.ui.InterfaceImageCache;
import org.fogofwarlabs.sc4j.scr.render.ui.InterfaceRenderer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Displays animated cursor frames through a platform cursor surface or the frame renderer. The classic frame layout places each decoded image inside
 * the cursor box at its hot spot; surface pictures use nearest or bilinear sampling.
 * <p>
 * Every screen places its cursor while it draws ({@link #place}); the present draws the last placed cursor once, over the whole frame and the status
 * overlay ({@link #drawPlaced}).
 */
public final class GameCursor implements AutoCloseable {
    /**
     * The real time one cursor frame is shown.
     */
    public static final int FRAME_MILLISECONDS = 100;
    /**
     * The arrow of the menus and of the console area.
     */
    public static final @NotNull String ARROW = "arrow";
    /**
     * The edge scroll cursors by direction: up, then clockwise.
     */
    public static final @NotNull String @NotNull [] SCROLL = {
            "ScrollU", "ScrollUR", "ScrollR", "ScrollDR",
            "ScrollD", "ScrollDL", "ScrollL", "ScrollUL"
    };

    private static final @NotNull String SHEET_FOLDER = "cursor/";
    private static final @NotNull String SHEET_SUFFIX = ".dds.grp";
    private static final @NotNull String CLASSIC_SHEET_SUFFIX = ".grp";
    private static final float CENTER = 0.5f;
    private static final float MAGNIFIER_CENTER = 0.48f;
    private static final float NEAR_EDGE = 0;
    private static final float FAR_EDGE = 1;
    private static final int OPAQUE_WHITE = 0xFFFFFFFF;

    /**
     * The classic {@code .grp} header size: frame count, canvas width and height; {@code 8} bytes per frame follow (left, top, width, height, data
     * offset).
     */
    private static final int HEADER_SIZE = 6;
    private static final int FRAME_RECORD_SIZE = 8;
    private static final int FRAME_COUNT_LOW_BYTE_INDEX = 0;
    private static final int FRAME_COUNT_HIGH_BYTE_INDEX = 1;
    private static final int FRAME_TOP_BYTE_OFFSET = 1;
    private static final int FRAME_WIDTH_BYTE_OFFSET = 2;
    private static final int FRAME_HEIGHT_BYTE_OFFSET = 3;

    private static final int LAYOUT_HEADER_FIELDS = 2;
    private static final int LAYOUT_FRAME_FIELDS = 2;
    private static final int LAYOUT_BOX_WIDTH_INDEX = 0;
    private static final int LAYOUT_BOX_HEIGHT_INDEX = 1;
    private static final int LAYOUT_FRAME_LEFT_INDEX = 2;
    private static final int LAYOUT_FRAME_TOP_INDEX = 3;
    private static final int LAYOUT_COMPLETE_FRAME_FIELDS = 4;

    private static final int HOT_SPOT_HORIZONTAL_INDEX = 0;
    private static final int HOT_SPOT_VERTICAL_INDEX = 1;

    private static final float TEXEL_CENTER_OFFSET = 0.5f;

    /**
     * The hot spots of {@link #getHotSpot} as horizontal and vertical fractions of the layout box, shared by every draw.
     */
    private static final float @NotNull [] CENTER_HOT_SPOT = {CENTER, CENTER};
    private static final float @NotNull [] MAGNIFIER_HOT_SPOT = {MAGNIFIER_CENTER, MAGNIFIER_CENTER};
    private static final float @NotNull [] TOP_HOT_SPOT = {CENTER, NEAR_EDGE};
    private static final float @NotNull [] TOP_RIGHT_HOT_SPOT = {FAR_EDGE, NEAR_EDGE};
    private static final float @NotNull [] RIGHT_HOT_SPOT = {FAR_EDGE, CENTER};
    private static final float @NotNull [] BOTTOM_RIGHT_HOT_SPOT = {FAR_EDGE, FAR_EDGE};
    private static final float @NotNull [] BOTTOM_HOT_SPOT = {CENTER, FAR_EDGE};
    private static final float @NotNull [] BOTTOM_LEFT_HOT_SPOT = {NEAR_EDGE, FAR_EDGE};
    private static final float @NotNull [] LEFT_HOT_SPOT = {NEAR_EDGE, CENTER};
    private static final float @NotNull [] TOP_LEFT_HOT_SPOT = {NEAR_EDGE, NEAR_EDGE};

    /**
     * A platform cursor picture: the cursor frame it shows, the surface key built from it and the picture builder.
     */
    private static final class CursorPicture {
        private final @NotNull InterfaceAssets assets;
        private final @NotNull String qualityPrefix;
        private final @NotNull String name;
        private final int frame;
        private final float scale;

        private final @NotNull String key;
        private final @NotNull Supplier<CursorImage> builder;

        private CursorPicture(@NotNull InterfaceAssets assets, @NotNull String qualityPrefix, @NotNull String name, int frame, float scale,
                              @NotNull String key, @NotNull Supplier<CursorImage> builder) {
            this.assets        = assets;
            this.qualityPrefix = qualityPrefix;
            this.name          = name;
            this.frame         = frame;
            this.scale         = scale;

            this.key     = key;
            this.builder = builder;
        }

        /**
         * @return the surface key of the picture
         */
        private @NotNull String getKey() {
            return this.key;
        }

        /**
         * @return the builder of the picture
         */
        private @NotNull Supplier<CursorImage> getBuilder() {
            return this.builder;
        }

        /**
         * Checks whether this picture shows a cursor frame; equal frames have equal keys and build equal pictures.
         *
         * @param assets        the interface assets of the current set
         * @param qualityPrefix the quality prefix of the current set
         * @param name          the classic cursor name
         * @param frame         the frame
         * @param scale         the window pixels per interface pixel of the picture
         * @return {@code true} if this picture shows the cursor frame, {@code false} otherwise
         */
        private boolean isShowing(@NotNull InterfaceAssets assets, @NotNull String qualityPrefix, @NotNull String name, int frame, float scale) {
            return this.assets == assets && this.qualityPrefix.equals(qualityPrefix) && this.name.equals(name) && this.frame == frame &&
                   Float.floatToIntBits(this.scale) == Float.floatToIntBits(scale);
        }
    }

    /**
     * Reads the cursor hot spot as horizontal and vertical fractions of its layout box.
     *
     * @param name the classic cursor asset name
     * @return the horizontal and vertical fractions
     */
    public static float @NotNull [] getHotSpot(@NotNull String name) {
        return findHotSpot(name).clone();
    }

    /**
     * Finds the shared cursor hot spot of {@link #getHotSpot}; callers must not modify it.
     *
     * @param name the classic cursor asset name
     * @return the horizontal and vertical fractions
     */
    private static float @NotNull [] findHotSpot(@NotNull String name) {
        return switch (name) {
            case "TargN", "TargG", "TargY", "TargR", "Drag" -> CENTER_HOT_SPOT;
            case "MagG", "MagY", "MagR" -> MAGNIFIER_HOT_SPOT;
            case "ScrollU" -> TOP_HOT_SPOT;
            case "ScrollUR" -> TOP_RIGHT_HOT_SPOT;
            case "ScrollR" -> RIGHT_HOT_SPOT;
            case "ScrollDR" -> BOTTOM_RIGHT_HOT_SPOT;
            case "ScrollD" -> BOTTOM_HOT_SPOT;
            case "ScrollDL" -> BOTTOM_LEFT_HOT_SPOT;
            case "ScrollL" -> LEFT_HOT_SPOT;
            default -> TOP_LEFT_HOT_SPOT;
        };
    }

    private static @NotNull String createSheetPath(@NotNull String name) {
        return SHEET_FOLDER + name.toLowerCase(Locale.ROOT) + SHEET_SUFFIX;
    }

    /**
     * Creates a {@link CursorImage} instance from a frame at its layout hot spot, using the frame bounds when no layout is available. Sampling is
     * nearest when the rounded surface frame width is at least the texture width, bilinear otherwise.
     *
     * @param image        the decoded sheet frame
     * @param textureScale the sheet's texture pixels per interface pixel
     * @param box          the cursor's frame layout ({@link #getLayout}), empty without one
     * @param hotSpot      the hot spot fractions of the box ({@link #getHotSpot})
     * @param frame        the frame
     * @param scale        the screen pixels per interface pixel
     * @return the created {@link CursorImage} instance
     */
    static @NotNull CursorImage createImage(@NotNull InterfaceImage image, int textureScale,
                                            int @NotNull [] box, float @NotNull [] hotSpot,
                                            int frame, float scale) {
        int textureWidth = image.getWidth();
        int textureHeight = image.getHeight();

        // The drawn frame size in interface pixels (InterfaceImageCache.Sprite: texture size over the sheet scale).
        float frameWidth = textureWidth / Math.max(1, textureScale);
        float frameHeight = textureHeight / Math.max(1, textureScale);
        float frameLeft = 0;
        float frameTop = 0;
        float boxWidth = frameWidth;
        float boxHeight = frameHeight;

        if (box.length >= LAYOUT_FRAME_FIELDS * frame + LAYOUT_COMPLETE_FRAME_FIELDS) {
            frameLeft = box[LAYOUT_FRAME_FIELDS * frame + LAYOUT_FRAME_LEFT_INDEX];
            frameTop  = box[LAYOUT_FRAME_FIELDS * frame + LAYOUT_FRAME_TOP_INDEX];
            boxWidth  = box[LAYOUT_BOX_WIDTH_INDEX];
            boxHeight = box[LAYOUT_BOX_HEIGHT_INDEX];
        }

        float left = Math.min(0, frameLeft);
        float top = Math.min(0, frameTop);
        float right = Math.max(boxWidth, frameLeft + frameWidth);
        float bottom = Math.max(boxHeight, frameTop + frameHeight);
        int width = Math.max(1, Math.round((right - left) * scale));
        int height = Math.max(1, Math.round((bottom - top) * scale));
        int offsetX = Math.round((frameLeft - left) * scale);
        int offsetY = Math.round((frameTop - top) * scale);
        int drawnWidth = Math.max(1, Math.round(frameWidth * scale));
        int drawnHeight = Math.max(1, Math.round(frameHeight * scale));

        byte[] source = image.getRgba();
        byte[] colorChannels = new byte[width * height * OverlayPass.RGBA_BYTES];

        for (int pixelY = 0; pixelY < drawnHeight; pixelY++) {
            int targetY = offsetY + pixelY;

            if (targetY < 0 || targetY >= height) {
                continue;
            }

            for (int pixelX = 0; pixelX < drawnWidth; pixelX++) {
                int targetX = offsetX + pixelX;

                if (targetX >= 0 && targetX < width) {
                    sample(source, textureWidth, textureHeight,
                           (pixelX + TEXEL_CENTER_OFFSET) * textureWidth / drawnWidth, (pixelY + TEXEL_CENTER_OFFSET) * textureHeight / drawnHeight,
                           drawnWidth >= textureWidth,
                           colorChannels, (targetY * width + targetX) * OverlayPass.RGBA_BYTES);
                }
            }
        }

        int hotSpotX = Math.round((hotSpot[HOT_SPOT_HORIZONTAL_INDEX] * boxWidth - left) * scale);
        int hotSpotY = Math.round((hotSpot[HOT_SPOT_VERTICAL_INDEX] * boxHeight - top) * scale);

        return new CursorImage(width, height, hotSpotX, hotSpotY, colorChannels);
    }

    /**
     * Samples a texel-space point with the requested nearest sampling or bilinear interpolation over clamped edges.
     *
     * @param source     the source RGBA pixel bytes
     * @param width      the source width
     * @param height     the source height
     * @param horizontal the horizontal texel coordinate
     * @param vertical   the vertical texel coordinate
     * @param nearest    whether to use nearest sampling instead of linear sampling
     * @param target     the destination RGBA pixel bytes
     * @param offset     the destination pixel byte offset
     */
    private static void sample(byte @NotNull [] source, int width, int height,
                               float horizontal, float vertical, boolean nearest,
                               byte @NotNull [] target, int offset) {
        if (nearest) {
            int index = (Math.min(height - 1, (int) vertical) * width + Math.min(width - 1, (int) horizontal)) * OverlayPass.RGBA_BYTES;

            System.arraycopy(source, index, target, offset, OverlayPass.RGBA_BYTES);

            return;
        }

        float texelX = horizontal - TEXEL_CENTER_OFFSET;
        float texelY = vertical - TEXEL_CENTER_OFFSET;
        int column = (int) Math.floor(texelX);
        int row = (int) Math.floor(texelY);
        float columnWeight = texelX - column;
        float rowWeight = texelY - row;
        int left = Math.clamp(column, 0, width - 1);
        int right = Math.clamp(column + 1, 0, width - 1);
        int top = Math.clamp(row, 0, height - 1);
        int bottom = Math.clamp(row + 1, 0, height - 1);

        for (int channel = 0; channel < OverlayPass.RGBA_BYTES; channel++) {
            float upper = Byte.toUnsignedInt(source[(top * width + left) * OverlayPass.RGBA_BYTES + channel]) * (1 - columnWeight) +
                          Byte.toUnsignedInt(source[(top * width + right) * OverlayPass.RGBA_BYTES + channel]) * columnWeight;
            float lower = Byte.toUnsignedInt(source[(bottom * width + left) * OverlayPass.RGBA_BYTES + channel]) * (1 - columnWeight) +
                          Byte.toUnsignedInt(source[(bottom * width + right) * OverlayPass.RGBA_BYTES + channel]) * columnWeight;

            target[offset + channel] = (byte) Math.round(upper * (1 - rowWeight) + lower * rowWeight);
        }
    }

    private final @NotNull CascStorage storage;
    private final @NotNull InterfaceRenderer renderer;

    private final @NotNull InterfaceImageCache images;
    /**
     * The frame layouts by cursor name: box width and height, then each frame's x and y in the box; empty when missing.
     */
    private final @NotNull Map<String, int[]> layouts;
    /**
     * Reads a cursor's frame layout on its first lookup ({@link #readLayout}), shared by every lookup.
     */
    private final @NotNull Function<String, int[]> layoutReader;

    private @Nullable CursorSurface surface;
    /**
     * The pictures handed to the platform pointer by their surface keys: the same cursor frame shows the same picture again.
     */
    private final @NotNull Map<String, DetailSet<Picture>> surfacePictures;
    /**
     * The window pixels per interface pixel of the surface pictures, or {@code 0} for the drawn scale.
     */
    private float surfaceScale;
    /**
     * The picture last handed to the platform pointer, reused while the same cursor frame is shown.
     */
    private @Nullable CursorPicture lastPicture;

    /**
     * The cursor drawn last and its sheet path, reused while the same cursor is drawn.
     */
    private @NotNull String sheetName;
    private @NotNull String sheetPath;

    /**
     * The interface assets of the cursor placed for the next present, or {@code null} when no screen placed one.
     */
    private @Nullable InterfaceAssets placedAssets;
    private @NotNull String placedName;
    private float placedX;
    private float placedY;
    private float placedScale;
    private long placedMilliseconds;

    /**
     * Creates a {@link GameCursor} instance.
     *
     * @param storage  the installed game storage (the classic cursor headers)
     * @param renderer the interface renderer the cursor is drawn with
     */
    public GameCursor(@NotNull CascStorage storage, @NotNull InterfaceRenderer renderer) {
        this.storage  = storage;
        this.renderer = renderer;

        this.images       = new InterfaceImageCache(renderer);
        this.layouts      = new HashMap<>();
        this.layoutReader = this::readLayout;

        this.surface         = null;
        this.surfacePictures = new HashMap<>();
        this.surfaceScale    = 0;
        this.lastPicture     = null;

        this.sheetName = ARROW;
        this.sheetPath = createSheetPath(ARROW);

        this.placedAssets       = null;
        this.placedName         = ARROW;
        this.placedX            = 0;
        this.placedY            = 0;
        this.placedScale        = 1;
        this.placedMilliseconds = 0;
    }

    /**
     * Sets the platform pointer that shows the cursor from now on ({@code null}: draw it into the frame). The previous surface is not closed.
     *
     * @param surface the platform pointer, or {@code null}
     */
    public void setSurface(@Nullable CursorSurface surface) {
        this.surface = surface;
    }

    /**
     * Sets the window pixels per interface pixel used to build platform cursor pictures. A positive value overrides the placement scale for those
     * pictures; otherwise they use the placement scale. The drawn cursor always uses the scale supplied to {@link #place}, and {@link #drawPlaced}
     * draws it last over the window.
     *
     * @param scale the window pixels per interface pixel; nonpositive values and {@code NaN} use the placement scale
     */
    public void setSurfaceScale(float scale) {
        this.surfaceScale = scale;
    }

    /**
     * Returns the frame layout of a cursor from its classic {@code cursor/<name>.grp} header, read once per name.
     *
     * @param name the classic cursor asset name
     * @return the width and height of the union of the frame rectangles, then each frame's offset from the union's corner; empty if no valid layout
     * could be read
     */
    private int @NotNull [] getLayout(@NotNull String name) {
        return this.layouts.computeIfAbsent(name, this.layoutReader);
    }

    private int @NotNull [] readLayout(@NotNull String name) {
        try {
            byte[] data = this.storage.readFile(SHEET_FOLDER + name + CLASSIC_SHEET_SUFFIX);
            int count = Byte.toUnsignedInt(data[FRAME_COUNT_LOW_BYTE_INDEX]) | Byte.toUnsignedInt(data[FRAME_COUNT_HIGH_BYTE_INDEX]) << Byte.SIZE;

            if (count == 0 || HEADER_SIZE + FRAME_RECORD_SIZE * count > data.length) {
                return new int[0];
            }

            int left = Integer.MAX_VALUE;
            int top = Integer.MAX_VALUE;
            int right = 0;
            int bottom = 0;

            for (int frame = 0; frame < count; frame++) {
                int record = HEADER_SIZE + FRAME_RECORD_SIZE * frame;

                left   = Math.min(left, Byte.toUnsignedInt(data[record]));
                top    = Math.min(top, Byte.toUnsignedInt(data[record + FRAME_TOP_BYTE_OFFSET]));
                right  = Math.max(right, Byte.toUnsignedInt(data[record]) + Byte.toUnsignedInt(data[record + FRAME_WIDTH_BYTE_OFFSET]));
                bottom = Math.max(bottom,
                                  Byte.toUnsignedInt(data[record + FRAME_TOP_BYTE_OFFSET]) +
                                  Byte.toUnsignedInt(data[record + FRAME_HEIGHT_BYTE_OFFSET]));
            }

            int[] layout = new int[LAYOUT_HEADER_FIELDS + LAYOUT_FRAME_FIELDS * count];

            layout[LAYOUT_BOX_WIDTH_INDEX]  = right - left;
            layout[LAYOUT_BOX_HEIGHT_INDEX] = bottom - top;

            for (int frame = 0; frame < count; frame++) {
                int layoutOffset = LAYOUT_FRAME_FIELDS * frame;
                int recordOffset = HEADER_SIZE + FRAME_RECORD_SIZE * frame;

                layout[LAYOUT_FRAME_LEFT_INDEX + layoutOffset] = Byte.toUnsignedInt(data[recordOffset]) - left;
                layout[LAYOUT_FRAME_TOP_INDEX + layoutOffset]  = Byte.toUnsignedInt(data[recordOffset + FRAME_TOP_BYTE_OFFSET]) - top;
            }

            return layout;
        } catch (IOException | RuntimeException exception) {
            return new int[0];
        }
    }


    /**
     * Makes the platform pointer invisible over the window, for frames that show no cursor; without a surface there is nothing to do.
     */
    public void hide() {
        if (this.surface != null) {
            this.surface.hide();
        }
    }

    /**
     * Places the cursor of the frame being drawn, replacing a cursor placed earlier in the same frame; the present draws it ({@link #drawPlaced}).
     *
     * @param assets       the interface assets of the current set (the sheet is read with its quality prefix)
     * @param name         the classic cursor name
     * @param screenX      the pointer column in framebuffer pixels
     * @param screenY      the pointer row in framebuffer pixels
     * @param scale        the framebuffer pixels per interface pixel
     * @param milliseconds a real-time clock in milliseconds that drives the animation
     */
    public void place(@NotNull InterfaceAssets assets, @NotNull String name, float screenX, float screenY, float scale, long milliseconds) {
        this.placedAssets       = assets;
        this.placedName         = name;
        this.placedX            = screenX;
        this.placedY            = screenY;
        this.placedScale        = scale;
        this.placedMilliseconds = milliseconds;
    }

    /**
     * Draws the cursor placed for this present, if any, and clears the placement. Call between {@link InterfaceRenderer#begin} and
     * {@link InterfaceRenderer#end} over the window, after everything else (also after the SD compose with SD graphics).
     *
     * @throws IOException if the initial sheet read or fallback drawn frame read fails
     */
    public void drawPlaced() throws IOException {
        InterfaceAssets assets = this.placedAssets;

        if (assets == null) {
            return;
        }

        this.placedAssets = null;

        this.draw(assets, this.placedName, this.placedX, this.placedY, this.placedScale, this.placedMilliseconds);
    }

    /**
     * Draws a cursor: hands its picture to the platform pointer when one shows the cursor, else draws it into the frame.
     *
     * @param assets       the interface assets of the current set (the sheet is read with its quality prefix)
     * @param name         the classic cursor name
     * @param screenX      the pointer column in framebuffer pixels
     * @param screenY      the pointer row in framebuffer pixels
     * @param scale        the framebuffer pixels per interface pixel
     * @param milliseconds a real-time clock in milliseconds that drives the animation
     * @throws IOException          if the initial sheet read or fallback drawn frame read fails
     * @throws UncheckedIOException if a platform cursor requests a picture and its sheet cannot be decoded
     */
    private void draw(@NotNull InterfaceAssets assets, @NotNull String name, float screenX, float screenY, float scale, long milliseconds) throws IOException {
        String sheet = this.getSheetPath(name);
        int frameCount = assets.getSheetFrameCount(sheet);

        if (frameCount <= 0) {
            return;
        }

        int frame = (int) Math.floorMod(milliseconds / FRAME_MILLISECONDS, (long) frameCount);
        CursorSurface pointer = this.surface;

        if (pointer != null && pointer.isAvailable()) {
            float pictureScale = this.surfaceScale > 0 ? this.surfaceScale : scale;
            CursorPicture picture = this.getPicture(assets, name, sheet, frame, pictureScale);

            pointer.show(this.surfacePictures.computeIfAbsent(picture.getKey(), key -> DetailSet.of(picture.getBuilder().get().createPicture())));

            if (pointer.isAvailable()) {
                return;
            }
        }

        InterfaceImageCache.Sprite sprite = this.images.getFrame(assets, sheet, frame, null);
        float[] hotSpot = findHotSpot(name);
        float width = sprite.getWidth() * scale;
        float height = sprite.getHeight() * scale;
        int[] box = this.getLayout(name);

        if (box.length >= LAYOUT_FRAME_FIELDS * frame + LAYOUT_COMPLETE_FRAME_FIELDS) {
            // The frame at its place in the box, the box at the hot spot.
            float left = screenX + (box[LAYOUT_FRAME_FIELDS * frame + LAYOUT_FRAME_LEFT_INDEX] -
                                    hotSpot[HOT_SPOT_HORIZONTAL_INDEX] * box[LAYOUT_BOX_WIDTH_INDEX]) * scale;
            float top = screenY + (box[LAYOUT_FRAME_FIELDS * frame + LAYOUT_FRAME_TOP_INDEX] -
                                   hotSpot[HOT_SPOT_VERTICAL_INDEX] * box[LAYOUT_BOX_HEIGHT_INDEX]) * scale;

            this.renderer.drawTexture(sprite.getTexture(), left, top, width, height, OPAQUE_WHITE);

            return;
        }

        this.renderer.drawTexture(sprite.getTexture(),
                                  screenX - hotSpot[HOT_SPOT_HORIZONTAL_INDEX] * width, screenY - hotSpot[HOT_SPOT_VERTICAL_INDEX] * height,
                                  width, height, OPAQUE_WHITE);
    }

    /**
     * @param name the classic cursor name
     * @return the sheet path of the cursor, built again only when another cursor is drawn
     */
    private @NotNull String getSheetPath(@NotNull String name) {
        if (!name.equals(this.sheetName)) {
            this.sheetName = name;
            this.sheetPath = createSheetPath(name);
        }

        return this.sheetPath;
    }

    /**
     * Returns the platform picture of a cursor frame, created again only when another cursor frame is shown. Its builder decodes the sheet frame
     * the first time the platform pointer shows its key.
     *
     * @param assets       the interface assets of the current set
     * @param name         the classic cursor name
     * @param sheet        the sheet path of the cursor
     * @param frame        the frame
     * @param pictureScale the window pixels per interface pixel of the picture
     * @return the platform picture
     */
    private @NotNull CursorPicture getPicture(@NotNull InterfaceAssets assets,
                                              @NotNull String name, @NotNull String sheet,
                                              int frame, float pictureScale) {
        String qualityPrefix = assets.getQualityPrefix();
        CursorPicture picture = this.lastPicture;

        if (picture == null || !picture.isShowing(assets, qualityPrefix, name, frame, pictureScale)) {
            String key = qualityPrefix + sheet + "#" + frame + "@" + pictureScale;

            picture          = new CursorPicture(assets, qualityPrefix, name, frame, pictureScale, key, () -> {
                try {
                    InterfaceAssets.ScaledImage decoded = assets.decodeSheetFrame(sheet, frame);

                    return createImage(decoded.getImage(), decoded.getScale(), this.getLayout(name), findHotSpot(name), frame, pictureScale);
                } catch (IOException exception) {
                    throw Exceptions.newUncheckedIOException(exception);
                }
            });
            this.lastPicture = picture;
        }

        return picture;
    }

    /**
     * Releases the cursor textures (for example when the asset set changes); they are loaded again on use.
     */
    public void release() {
        this.images.releaseAll();
    }

    /**
     * Closes this resource, relinquishing any underlying resources.
     * This method is invoked automatically on objects managed by the
     * {@code try}-with-resources statement.
     *
     * @apiNote While this interface method is declared to throw {@code
     * Exception}, implementers are <em>strongly</em> encouraged to
     * declare concrete implementations of the {@code close} method to
     * throw more specific exceptions, or to throw no exception at all
     * if the close operation cannot fail.
     *
     * <p>
     * Cases where the close operation may fail require careful
     * attention by implementers. It is strongly advised to relinquish
     * the underlying resources and to internally <em>mark</em> the
     * resource as closed, prior to throwing the exception. The {@code
     * close} method is unlikely to be invoked more than once and so
     * this ensures that the resources are released in a timely manner.
     * Furthermore it reduces problems that could arise when the resource
     * wraps, or is wrapped, by another resource.
     *
     * <p>
     * <em>Implementers of this interface are also strongly advised
     * to not have the {@code close} method throw {@link
     * InterruptedException}.</em>
     *
     * <p>
     * This exception interacts with a thread's interrupted status,
     * and runtime misbehavior is likely to occur if an {@code
     * InterruptedException} is {@linkplain Throwable#addSuppressed
     * suppressed}.
     *
     * <p>
     * More generally, if it would cause problems for an
     * exception to be suppressed, the {@code AutoCloseable.close}
     * method should not throw it.
     *
     * <p>
     * Note that unlike the {@link Closeable#close close}
     * method of {@link Closeable}, this {@code close} method
     * is <em>not</em> required to be idempotent.  In other words,
     * calling this {@code close} method more than once may have some
     * visible side effect, unlike {@code Closeable.close} which is
     * required to have no effect if called more than once.
     *
     * <p>
     * However, implementers of this interface are strongly encouraged
     * to make their {@code close} methods idempotent.
     * @implSpec Releases cached image textures; the supplied cursor surface remains borrowed.
     */
    @Override
    public void close() {
        this.images.close();
    }
}
