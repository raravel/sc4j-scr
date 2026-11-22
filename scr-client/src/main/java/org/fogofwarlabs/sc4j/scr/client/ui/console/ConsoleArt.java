package org.fogofwarlabs.sc4j.scr.client.ui.console;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.resource.graphics.Picture;
import org.fogofwarlabs.sc4j.scr.render.OverlayPass;
import org.fogofwarlabs.sc4j.scr.render.ui.InterfaceImage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Holds the decoded art of one race console: the SD console image, or the left, center and right HD pieces with their
 * placement, and the pictures drawn instead of pieces. The alpha of a piece's picture, or else of its image, is the console's hit mask; a
 * picture keeps the piece's place and size.
 * <p>
 * The images, their sizes and their placement belong to one art, so a hit test always reads one consistent set.
 */
final class ConsoleArt {
    /**
     * The piece indexes of the HD art; the SD art has only the left piece.
     */
    static final int LEFT_PIECE = 0;
    static final int CENTER_PIECE = 1;
    static final int RIGHT_PIECE = 2;
    /**
     * The number of HD console pieces.
     */
    static final int PIECE_COUNT = 3;

    /**
     * The lowest mask alpha that counts as console.
     */
    private static final int MINIMUM_HIT_ALPHA = 5;
    /**
     * The left and top values of each piece in the piece positions.
     */
    private static final int POSITION_VALUES = 2;
    private static final int PICTURE_ALPHA_SHIFT = 24;

    /**
     * Creates a {@link ConsoleArt} instance from the SD console image, which lies on the interface bottom edge at the left.
     *
     * @param console the SD console image
     * @return the created {@link ConsoleArt} instance
     */
    static @NotNull ConsoleArt ofStandardDefinition(@NotNull InterfaceImage console) {
        return new ConsoleArt(new InterfaceImage[]{console},
                              new int[][]{{console.getWidth(), console.getHeight()}},
                              1,
                              false,
                              new float[]{0, ConsoleLayout.INTERFACE_HEIGHT - console.getHeight()});
    }

    /**
     * Creates a {@link ConsoleArt} instance from the three HD pieces, which are placed by {@link #placePieces}.
     *
     * @param pieces  the left, center and right piece images
     * @param divisor the image pixels per HD2 pixel: {@code 1} for the HD2 pieces, {@code 2} for the HD pieces
     * @return the created {@link ConsoleArt} instance
     * @throws IllegalArgumentException if the piece count is not {@link #PIECE_COUNT}
     */
    static @NotNull ConsoleArt ofPieces(@NotNull InterfaceImage @NotNull [] pieces, int divisor) {
        if (pieces.length != PIECE_COUNT) {
            throw Exceptions.newIllegalArgumentException("The HD console has {0} pieces, not {1}.", PIECE_COUNT, pieces.length);
        }

        int[][] sizes = new int[PIECE_COUNT][];

        for (int piece = 0; piece < PIECE_COUNT; piece++) {
            sizes[piece] = new int[]{pieces[piece].getWidth() / divisor, pieces[piece].getHeight() / divisor};
        }

        return new ConsoleArt(pieces.clone(), sizes, HdConsoleLayout.HD2_SCALE, true, new float[PIECE_COUNT * POSITION_VALUES]);
    }

    private static int getPixelAlpha(@NotNull InterfaceImage image, int u, int v) {
        if (u < 0 || v < 0 || u >= image.getWidth() || v >= image.getHeight()) {
            return 0;
        }

        return Byte.toUnsignedInt(image.getRgba()[(v * image.getWidth() + u) * OverlayPass.RGBA_BYTES + OverlayPass.RGBA_ALPHA]);
    }

    private static int getPixelAlpha(@NotNull Picture picture, int u, int v) {
        if (u < 0 || v < 0 || u >= picture.getWidth() || v >= picture.getHeight()) {
            return 0;
        }

        return picture.getColor().getValue(v * picture.getWidth() + u) >>> PICTURE_ALPHA_SHIFT;
    }

    private final @NotNull InterfaceImage @NotNull [] images;
    private final @Nullable Picture @NotNull [] pictures;
    /**
     * The width and height of each piece in the art's own pixels: interface pixels for the SD art, HD2 pixels for the HD
     * pieces.
     */
    private final int @NotNull [] @NotNull [] sizes;
    /**
     * The art pixels per interface pixel.
     */
    private final int pixelScale;
    private final boolean highDefinition;

    /**
     * The left and top interface position of each piece.
     */
    private float @NotNull [] positions;

    private ConsoleArt(@NotNull InterfaceImage @NotNull [] images,
                       int @NotNull [] @NotNull [] sizes,
                       int pixelScale,
                       boolean highDefinition,
                       float @NotNull [] positions) {
        this.images         = images;
        this.pictures       = new Picture[images.length];
        this.sizes          = sizes;
        this.pixelScale     = pixelScale;
        this.highDefinition = highDefinition;

        this.positions = positions;
    }

    /**
     * @param piece the piece index
     * @return the decoded image of the piece
     */
    @NotNull InterfaceImage getImage(int piece) {
        return this.images[piece];
    }

    /**
     * @param piece the piece index
     * @return the picture drawn instead of the piece, or {@code null} for its image
     */
    @Nullable Picture findPicture(int piece) {
        return this.pictures[piece];
    }

    /**
     * Sets the picture drawn instead of a piece, at the piece's place and size.
     *
     * @param piece   the piece index
     * @param picture the picture, whose colour layer is ARGB, or {@code null} for the piece's image
     */
    void setPicture(int piece, @Nullable Picture picture) {
        this.pictures[piece] = picture;
    }

    /**
     * @return {@code true} if a picture is drawn instead of a piece, {@code false} otherwise
     */
    boolean hasPicture() {
        for (Picture picture : this.pictures) {
            if (picture != null) {
                return true;
            }
        }

        return false;
    }

    /**
     * @return the number of pieces: {@code 1} for the SD art, {@link #PIECE_COUNT} for the HD art
     */
    int getPieceCount() {
        return this.images.length;
    }

    /**
     * @param piece the piece index
     * @return the left edge of the piece in interface pixels
     */
    float getLeft(int piece) {
        return this.positions[piece * POSITION_VALUES];
    }

    /**
     * @param piece the piece index
     * @return the top edge of the piece in interface pixels
     */
    float getTop(int piece) {
        return this.positions[piece * POSITION_VALUES + 1];
    }

    /**
     * @param piece the piece index
     * @return the width of the piece in interface pixels
     */
    float getWidth(int piece) {
        return this.sizes[piece][0] / (float) this.pixelScale;
    }

    /**
     * @param piece the piece index
     * @return the height of the piece in interface pixels
     */
    float getHeight(int piece) {
        return this.sizes[piece][1] / (float) this.pixelScale;
    }


    /**
     * Places the HD pieces along the interface bottom edge for an interface width ({@link HdConsoleLayout#placePieces}).
     *
     * @param interfaceWidth the interface width in pixels
     * @param variant        the console placement variant
     * @throws IllegalStateException if the art is the SD console
     */
    void placePieces(float interfaceWidth, @NotNull HdConsoleLayout.Variant variant) {
        if (!this.highDefinition) {
            throw Exceptions.newIllegalStateException("The SD console is not placed in pieces.");
        }

        this.positions = HdConsoleLayout.placePieces(interfaceWidth,
                                                     variant,
                                                     this.sizes[LEFT_PIECE],
                                                     this.sizes[CENTER_PIECE],
                                                     this.sizes[RIGHT_PIECE]);
    }

    /**
     * Tests the art's alpha mask at an interface position. The SD console covers its rectangle with an exclusive right and
     * bottom edge. Each HD piece covers the area at or below its top and within its left and right edges (inclusive); left
     * of the center piece's middle the left piece is tested before the center piece, right of it the right piece.
     *
     * @param x the interface x
     * @param y the interface y
     * @return {@code true} if the mask alpha at the position is at least the hit alpha, {@code false} otherwise
     */
    boolean isHit(float x, float y) {
        if (!this.highDefinition) {
            float width = this.getWidth(LEFT_PIECE);
            float height = this.getHeight(LEFT_PIECE);
            float offsetY = y - this.getTop(LEFT_PIECE);

            if (x < 0 || offsetY < 0 || x >= width || offsetY >= height) {
                return false;
            }

            return this.getMaskAlpha(LEFT_PIECE, x / width, offsetY / height) >= MINIMUM_HIT_ALPHA;
        }

        float top = Math.min(this.getTop(LEFT_PIECE), Math.min(this.getTop(CENTER_PIECE), this.getTop(RIGHT_PIECE)));

        if (y < top) {
            return false;
        }

        float centerX = this.getLeft(CENTER_PIECE);
        float centerWidth = this.getWidth(CENTER_PIECE);

        if (x < centerX) {
            return this.isPieceHit(LEFT_PIECE, x, y);
        }

        if (x > centerX + centerWidth) {
            return this.isPieceHit(RIGHT_PIECE, x, y);
        }

        float half = (int) centerWidth >>> 1;

        if (x < centerX + half) {
            return this.isPieceHit(LEFT_PIECE, x, y) || this.isPieceHit(CENTER_PIECE, x, y);
        }

        if (x > centerX + half) {
            return this.isPieceHit(RIGHT_PIECE, x, y) || this.isPieceHit(CENTER_PIECE, x, y);
        }

        return this.isPieceHit(CENTER_PIECE, x, y);
    }

    /**
     * Tests one HD piece: at or below its top, within its left and right edges (inclusive), then its mask texel.
     */
    private boolean isPieceHit(int piece, float x, float y) {
        float left = this.getLeft(piece);
        float top = this.getTop(piece);
        float width = this.getWidth(piece);
        float height = this.getHeight(piece);

        if (y < top || x < left || x > left + width) {
            return false;
        }

        return this.getMaskAlpha(piece, (x - left) / width, (y - top) / height) >= MINIMUM_HIT_ALPHA;
    }

    /**
     * Reads the mask alpha of a piece at a fraction of its width and height: its picture's alpha, or else its image's.
     */
    private int getMaskAlpha(int piece, float u, float v) {
        Picture picture = this.pictures[piece];

        if (picture != null) {
            return getPixelAlpha(picture, (int) (u * picture.getWidth()), (int) (v * picture.getHeight()));
        }

        InterfaceImage mask = this.images[piece];

        return getPixelAlpha(mask, (int) (u * mask.getWidth()), (int) (v * mask.getHeight()));
    }
}
