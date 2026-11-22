package org.fogofwarlabs.sc4j.scr.render;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.exception.IgnoreException;
import org.fogofwarlabs.sc4j.engine.client.render.GraphicsDevice;
import org.fogofwarlabs.sc4j.engine.client.render.ShaderProgram;
import org.fogofwarlabs.sc4j.engine.resource.graphics.SpriteSheet;
import org.fogofwarlabs.sc4j.scr.content.ScrAddedGraphics;
import org.fogofwarlabs.sc4j.scr.data.format.image.AnimFile;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.fogofwarlabs.sc4j.scr.data.resource.ScrResourceProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.*;
import java.util.function.IntFunction;
import java.util.function.IntUnaryOperator;

import static org.lwjgl.opengl.GL33C.*;

/**
 * Draws classic terrain, explicit image frames, image effects and overlays with an OpenGL 3.3 context. The caller owns the context and borrowed
 * storage and uses this renderer on the context thread.
 */
public final class WorldRenderer implements AutoCloseable {
    /**
     * The default SD compose and present filter mode.
     */
    public static final int DEFAULT_SD_FILTER_MODE = 1;
    /**
     * The four supported SD compose and present filter modes.
     */
    public static final int SD_FILTER_MODES = 4;
    /**
     * The SD compose target's size over the {@code 640x480} SD target (the compose target is {@code 1280x960}).
     */
    public static final int SD_COMPOSE_SCALE = 2;
    /**
     * The SD target's height, the game's {@code 480} lines.
     */
    public static final int SD_TARGET_HEIGHT = 480;

    /**
     * The world target's color draw buffer.
     */
    public static final int COLOR_TARGET = 0;
    /**
     * The world target's effect draw buffer (the shadow channel).
     */
    public static final int EFFECT_TARGET = 1;
    /**
     * The world target's destination palette index draw buffer.
     */
    public static final int INDEX_TARGET = 2;
    /**
     * The draw buffers of the world target (color, effect and index) and of the single-target SD framebuffers.
     */
    private static final int WORLD_TARGET_COUNT = INDEX_TARGET + 1;
    private static final int SD_TARGET_COUNT = 1;

    /**
     * The renderer name of the shader failure messages.
     */
    private static final @NotNull String SHADER_PROGRAM_NAME = "Renderer";
    /**
     * The vertex layout of the classic world programs: position, texture coordinates, color and palette row.
     */
    private static final int POSITION_ATTRIBUTE = 0;
    private static final int COORDINATES_ATTRIBUTE = 1;
    private static final int COLOR_ATTRIBUTE = 2;
    private static final int PALETTE_ROW_ATTRIBUTE = 3;
    private static final int POSITION_COMPONENTS = 2;
    private static final int COORDINATES_COMPONENTS = 2;
    private static final int COLOR_COMPONENTS = 4;
    private static final int PALETTE_ROW_COMPONENTS = 1;
    private static final int COORDINATES_OFFSET = POSITION_COMPONENTS;
    private static final int COLOR_OFFSET = COORDINATES_OFFSET + COORDINATES_COMPONENTS;
    private static final int PALETTE_ROW_OFFSET = COLOR_OFFSET + COLOR_COMPONENTS;
    /**
     * The floats of one vertex of the classic world programs.
     */
    static final int VERTEX_FLOATS = PALETTE_ROW_OFFSET + PALETTE_ROW_COMPONENTS;
    /**
     * The floats of one quad.
     */
    static final int QUAD_FLOATS = GraphicsDevice.QUAD_VERTICES * VERTEX_FLOATS;

    /**
     * The texture units of the program's samplers: image, palette, destination index copy and lighting tables.
     */
    private static final int IMAGE_UNIT = 0;
    private static final int PALETTE_UNIT = 1;
    private static final int DESTINATION_UNIT = 2;
    private static final int LIGHTING_UNIT = 3;

    /**
     * The fragment shader modes ({@code indexed}): a solid quad multiplied by its vertex color.
     */
    private static final int MODE_SOLID = 0;
    /**
     * A palette-indexed frame through its palette row.
     */
    private static final int MODE_PALETTE = 1;
    /**
     * The world color with the shadow composite.
     */
    private static final int MODE_COMPOSITE = 2;
    /**
     * A warp texture mask: palette index and coverage.
     */
    private static final int MODE_WARP = 3;
    /**
     * A white warp flash scaled by frame coverage.
     */
    private static final int MODE_BRIGHTEN = 4;
    /**
     * A lighting remap of the destination index.
     */
    private static final int MODE_LIGHTING = 5;
    /**
     * A lighting remap selected by the player-colored frame index.
     */
    private static final int MODE_PLAYER_LIGHTING = 6;
    /**
     * The player-colored pixels brighter than a fade value.
     */
    private static final int MODE_FADE = 7;
    /**
     * A distortion of the destination by the frame's palette index.
     */
    private static final int MODE_DISTORTION = 8;
    /**
     * A palette-indexed frame with the hallucination tint.
     */
    private static final int MODE_HALLUCINATION = 9;
    /**
     * The cloak refraction of the destination.
     */
    private static final int MODE_REFRACTION = 10;
    /**
     * A plain copy of a texture.
     */
    private static final int MODE_COPY = 11;
    /**
     * A bicubic copy of a texture.
     */
    private static final int MODE_BICUBIC = 12;

    /**
     * The palette row of the opaque terrain draw, which writes every target pixel.
     */
    private static final int PALETTE_ROW_OPAQUE = -1;
    /**
     * The palette row value that marks a shadow quad.
     */
    private static final int PALETTE_ROW_SHADOW = -2;
    /**
     * The first player palette row ({@link SceneRenderer#PLAYER_COLOR}{@code + player} selects it).
     */
    private static final int PLAYER_PALETTE_ROW = RenderAssets.PALETTE_ROWS;
    /**
     * The packed {@code 0xRRGGBBAA} opaque white.
     */
    private static final int OPAQUE_WHITE = 0xFFFFFFFF;
    /**
     * The packed {@code 0xRRGGBBAA} white without its alpha byte.
     */
    private static final int WHITE_RGB = 0xFFFFFF00;
    /**
     * The largest alpha byte.
     */
    private static final int MAXIMUM_ALPHA = 255;
    /**
     * The positions of red, green and blue in a packed {@code 0xRRGGBBAA} color.
     */
    private static final int RGBA_RED_SHIFT = 24;
    private static final int RGBA_GREEN_SHIFT = 16;
    private static final int RGBA_BLUE_SHIFT = 8;
    /**
     * The mask of one channel of a packed color.
     */
    private static final int CHANNEL_MASK = 0xFF;
    /**
     * The positions of green and blue in a packed {@code 0x00BBGGRR} screen palette color.
     */
    private static final int PALETTE_COLOR_GREEN_SHIFT = 8;
    private static final int PALETTE_COLOR_BLUE_SHIFT = 16;
    /**
     * The mask of an unsigned byte.
     */
    private static final int BYTE_MASK = 0xFF;
    /**
     * The mask of an unsigned WORD.
     */
    private static final int WORD_MASK = 0xFFFF;

    /**
     * The side of a texture atlas in texels.
     */
    private static final int ATLAS_SIZE = 2048;
    /**
     * The {@code trans50} index in {@link RenderAssets#LIGHTING_TABLE_NAMES}, table index {@code 4}.
     */
    private static final int CLOAK_LIGHTING_TABLE = 4;
    /**
     * The {@code dark.pcx} row that darkens the destination below a shadow.
     */
    private static final int SHADOW_LIGHTING_ROW = 18;
    /**
     * The number of terrain tile identifiers, the unsigned WORD range.
     */
    private static final int TILE_IDENTIFIER_COUNT = 0x10000;
    /**
     * The largest GRP frame index, an unsigned WORD.
     */
    private static final int MAXIMUM_FRAME_INDEX = 0xFFFF;
    /**
     * The positions of the frame, the texture frame and the flip in the frame cache keys.
     */
    private static final int KEY_FRAME_SHIFT = 10;
    private static final int KEY_TEXTURE_FRAME_SHIFT = 26;
    private static final int KEY_FLIP_SHIFT = 42;
    /**
     * The frame slots of a newly cached image.
     */
    private static final int INITIAL_FRAME_CAPACITY = 16;
    /**
     * The number of override sheet frames whose atlas entries are kept per kind, the frames and the coverage frames; the frame drawn least recently
     * gives its atlas place to the next one of its size.
     */
    private static final int MAXIMUM_SHEET_FRAMES = 256;
    /**
     * The factor by which a full frame or held quad array grows.
     */
    private static final int CAPACITY_GROWTH = 2;
    /**
     * The view pixels right of the nonzero pixels that a distortion reads per render scale unit: the largest palette index.
     */
    private static final int DISTORTION_READ_EXTENSION = 255;
    /**
     * The view pixels right of the nonzero pixels that the cloak refraction reads.
     */
    private static final int REFRACTION_READ_EXTENSION = 9;

    /**
     * The classic graphics' render scale limit: one target pixel per game pixel.
     */
    private static final int CLASSIC_SCALE_LIMIT = 1;
    /**
     * The smallest and the largest world render scale.
     */
    private static final int MINIMUM_RENDER_SCALE = 2;
    private static final int MAXIMUM_RENDER_SCALE = 4;
    /**
     * The values of {@code GL_MAX_VIEWPORT_DIMS}: width and height.
     */
    private static final int VIEWPORT_DIMENSIONS = 2;
    /**
     * The first SD filter mode whose first step (the world into the compose target) samples linearly.
     */
    private static final int SD_FILTER_LINEAR_COMPOSE = 2;
    /**
     * The first SD filter mode whose present samples linearly.
     */
    private static final int SD_FILTER_LINEAR_PRESENT = 1;
    /**
     * The SD filter mode that presents bicubically.
     */
    private static final int SD_FILTER_BICUBIC = 3;

    /**
     * The vertices that the streamed vertex buffer holds.
     */
    private static final int MAXIMUM_VERTICES = 24576;
    /**
     * The log2 of the side of a view cell whose destination index copy is tracked as current or changed.
     */
    private static final int INDEX_CELL_SHIFT = 1;
    /**
     * The log2 of the side of a view tile whose index changes and copies are tracked ({@link #changedIndexTiles}).
     */
    private static final int INDEX_TILE_SHIFT = 4;
    /**
     * The highest draw level a held-back quad takes before the held quads are drawn ({@link #holdImageQuad}).
     */
    private static final int MAXIMUM_DRAW_LEVEL = 0xFFFF;
    /**
     * The change and copy stamp after which a frame start clears the cells and counts from {@code 0} again.
     */
    private static final int MAXIMUM_INDEX_STAMP = 1 << 30;
    /**
     * The packing of a cell's level entry: {@code epoch << 16 | level}.
     */
    private static final int LEVEL_EPOCH_SHIFT = 16;
    private static final int LEVEL_EPOCH_MASK = 0xFFFF0000;
    private static final int LEVEL_MASK = 0xFFFF;
    /**
     * The held quads a frame starts with room for.
     */
    private static final int INITIAL_HELD_CAPACITY = 64;
    /**
     * The cell values of a held quad: the written cells, then the read cells, each first column, last column, first row, last row.
     */
    private static final int HELD_CELL_VALUES = 8;
    private static final int WRITE_FIRST_COLUMN = 0;
    private static final int WRITE_LAST_COLUMN = 1;
    private static final int WRITE_FIRST_ROW = 2;
    private static final int WRITE_LAST_ROW = 3;
    private static final int READ_FIRST_COLUMN = 4;
    private static final int READ_LAST_COLUMN = 5;
    private static final int READ_FIRST_ROW = 6;
    private static final int READ_LAST_ROW = 7;
    /**
     * The all-zero clear value of the effect and index draw buffers.
     */
    private static final float @NotNull [] ZERO_CLEAR_VALUE = {0, 0, 0, 0};
    private static final @NotNull String VERTEX_SHADER = """
                                                         #version 330 core
                                                         layout(location = 0) in vec2 position;
                                                         layout(location = 1) in vec2 coordinates;
                                                         layout(location = 2) in vec4 color;
                                                         layout(location = 3) in float paletteRow;
                                                         uniform vec2 framebufferSize;
                                                         out vec2 sampleCoordinates;
                                                         out vec4 vertexColor;
                                                         flat out float colorRow;
                                                         void main() {
                                                             gl_Position = vec4(position.x * 2.0 / framebufferSize.x - 1.0,
                                                                                1.0 - position.y * 2.0 / framebufferSize.y, 0.0, 1.0);
                                                             sampleCoordinates = coordinates;
                                                             vertexColor = color;
                                                             colorRow = paletteRow;
                                                         }
                                                         """;
    private static final @NotNull String FRAGMENT_SHADER = """
                                                           #version 330 core
                                                           in vec2 sampleCoordinates;
                                                           in vec4 vertexColor;
                                                           flat in float colorRow;
                                                           uniform sampler2D imageTexture;
                                                           uniform sampler2D paletteTexture;
                                                           uniform int indexed;
                                                           uniform sampler2D destinationIndices;
                                                           uniform sampler2D lightingTexture;
                                                           uniform int lightingRow;
                                                           uniform int lightingRows;
                                                           uniform int shadowRow;
                                                           uniform int shadowStacking;
                                                           uniform int cloakValue;
                                                           uniform float distortionScale;
                                                           layout(location = 0) out vec4 resultColor;
                                                           layout(location = 1) out vec4 resultEffect;
                                                           layout(location = 2) out vec4 resultIndex;
                                                           vec4 cubic(float v) {
                                                               vec4 n = vec4(1.0, 2.0, 3.0, 4.0) - v;
                                                               vec4 s = n * n * n;
                                                               float x = s.x;
                                                               float y = s.y - 4.0 * s.x;
                                                               float z = s.z - 4.0 * s.y + 6.0 * s.x;
                                                               float w = 6.0 - x - y - z;
                                                               return vec4(x, y, z, w) * (1.0 / 6.0);
                                                           }
                                                           // textured_frag.glsl with BICUBIC (SC:R's present pass 1).
                                                           vec4 textureBicubic(sampler2D source, vec2 coordinates) {
                                                               vec2 size = vec2(textureSize(source, 0));
                                                               vec2 inverseSize = 1.0 / size;
                                                               coordinates = coordinates * size - 0.5;
                                                               vec2 fraction = fract(coordinates);
                                                               coordinates -= fraction;
                                                               vec4 xcubic = cubic(fraction.x);
                                                               vec4 ycubic = cubic(fraction.y);
                                                               vec4 c = coordinates.xxyy + vec2(-0.5, 1.5).xyxy;
                                                               vec4 s = vec4(xcubic.xz + xcubic.yw, ycubic.xz + ycubic.yw);
                                                               vec4 offset = (c + vec4(xcubic.yw, ycubic.yw) / s) * inverseSize.xxyy;
                                                               vec4 s0 = texture(source, offset.xz);
                                                               vec4 s1 = texture(source, offset.yz);
                                                               vec4 s2 = texture(source, offset.xw);
                                                               vec4 s3 = texture(source, offset.yw);
                                                               float sx = s.x / (s.x + s.y);
                                                               float sy = s.z / (s.z + s.w);
                                                               return mix(mix(s3, s2, sx), mix(s1, s0, sx), sy);
                                                           }
                                                           void main() {
                                                               vec4 sampled = texture(imageTexture, sampleCoordinates);
                                                               resultIndex = vec4(0.0);
                                                               if (indexed == 11 || indexed == 12) {
                                                                   resultColor = vec4((indexed == 12 ? textureBicubic(imageTexture, sampleCoordinates) : sampled).rgb, 1.0);
                                                                   resultEffect = vec4(0.0);
                                                                   return;
                                                               }
                                                               if (indexed == 2) {
                                                                   float shadow = texture(paletteTexture, sampleCoordinates).r;
                                                                   resultColor = vec4(sampled.rgb * (1.0 - shadow * 0.5), sampled.a);
                                                                   resultEffect = vec4(0.0);
                                                                   return;
                                                               }
                                                               if (indexed == 4) {
                                                                   // sprite_brighten_frag.glsl (draw function 17): white at the coverage alpha times the
                                                                   // multiply alpha; the effect and index targets keep their values.
                                                                   if (sampled.r == 0.0) discard;
                                                                   resultColor = vec4(1.0, 1.0, 1.0, sampled.r * vertexColor.a);
                                                                   resultEffect = vec4(0.0);
                                                                   return;
                                                               }
                                                               if (indexed == 3) {
                                                                   if (sampled.g == 0.0) discard;
                                                                   int paletteIndex = int(sampled.r * 255.0 + 0.5);
                                                                   resultColor = vec4(texelFetch(paletteTexture, ivec2(paletteIndex, 0), 0).rgb, 1.0);
                                                                   resultEffect = vec4(0.0, 0.0, 0.0, 1.0);
                                                                   resultIndex = vec4(float(paletteIndex) / 255.0, 0.0, 0.0, 1.0);
                                                                   return;
                                                               }
                                                               if (indexed == 5 || indexed == 6) {
                                                                   int sourceIndex = int(sampled.r * 255.0 + 0.5);
                                                                   if (sourceIndex == 0) discard;
                                                                   if (indexed == 6)
                                                                       sourceIndex = int(texelFetch(paletteTexture, ivec2(sourceIndex, max(0, int(colorRow))), 0).a * 255.0 + 0.5);
                                                                   int previousIndex = int(texelFetch(destinationIndices, ivec2(gl_FragCoord.xy), 0).r * 255.0 + 0.5);
                                                                   int glowIndex = 0;
                                                                   if (sourceIndex - 1 < lightingRows)
                                                                       glowIndex = int(texelFetch(lightingTexture, ivec2(previousIndex, lightingRow + sourceIndex - 1), 0).r * 255.0 + 0.5);
                                                                   resultColor = vec4(texelFetch(paletteTexture, ivec2(glowIndex, 0), 0).rgb, 1.0);
                                                                   resultEffect = vec4(0.0, 0.0, 0.0, 1.0);
                                                                   resultIndex = vec4(float(glowIndex) / 255.0, 0.0, 0.0, 1.0);
                                                                   return;
                                                               }
                                                               if (indexed == 8) {
                                                                   int shift = int(sampled.r * 255.0 + 0.5);
                                                                   if (shift == 0) discard;
                                                                   ivec2 source = ivec2(gl_FragCoord.xy) + ivec2(int(float(shift) * distortionScale), 0);
                                                                   int shiftedIndex = int(texelFetch(destinationIndices, source, 0).r * 255.0 + 0.5);
                                                                   resultColor = vec4(texelFetch(paletteTexture, ivec2(shiftedIndex, 0), 0).rgb, 1.0);
                                                                   resultEffect = vec4(0.0, 0.0, 0.0, 1.0);
                                                                   resultIndex = vec4(float(shiftedIndex) / 255.0, 0.0, 0.0, 1.0);
                                                                   return;
                                                               }
                                                               if (indexed == 10) {
                                                                   // SC:R cloak refraction (fbo_cloak_frag.glsl ApplyEffect_Cloak): the background shifted
                                                                   // right by (brightness + 0.0625) * 8 framebuffer pixels, brightness weighted as in
                                                                   // the cloaked sprite pass (0.302, 0.592, 0.110).
                                                                   int sourceIndex = int(sampled.r * 255.0 + 0.5);
                                                                   if (sourceIndex == 0) discard;
                                                                   vec3 sourceColor = texelFetch(paletteTexture, ivec2(sourceIndex, 0), 0).rgb;
                                                                   float brightness = dot(sourceColor, vec3(0.30196078, 0.59215686, 0.10980392));
                                                                   ivec2 source = ivec2(gl_FragCoord.xy) + ivec2(int((brightness + 0.0625) * 8.0), 0);
                                                                   int shiftedIndex = int(texelFetch(destinationIndices, source, 0).r * 255.0 + 0.5);
                                                                   resultColor = vec4(texelFetch(paletteTexture, ivec2(shiftedIndex, 0), 0).rgb, 1.0);
                                                                   resultEffect = vec4(0.0, 0.0, 0.0, 1.0);
                                                                   resultIndex = vec4(float(shiftedIndex) / 255.0, 0.0, 0.0, 1.0);
                                                                   return;
                                                               }
                                                               if (indexed == 7) {
                                                                   int sourceIndex = int(sampled.r * 255.0 + 0.5);
                                                                   vec3 baseColor = texelFetch(paletteTexture, ivec2(sourceIndex, 0), 0).rgb * 255.0 + 0.5;
                                                                   int strength = (int(baseColor.r) * 28 + int(baseColor.g) * 77 + int(baseColor.b) * 151 + 4096) / 8192;
                                                                   if (sourceIndex == 0 || strength <= cloakValue) discard;
                                                               }
                                                               if (indexed == 1 || indexed == 7 || indexed == 9) {
                                                                   int paletteIndex = int(sampled.r * 255.0 + 0.5);
                                                                   if (colorRow == -2.0) {
                                                                       // A shadow samples its coverage mask: the SD sprite alpha.
                                                                       float coverage = sampled.r;
                                                                       if (coverage == 0.0) discard;
                                                                       int previousIndex = int(texelFetch(destinationIndices, ivec2(gl_FragCoord.xy), 0).r * 255.0 + 0.5);
                                                                       float darkIndex = texelFetch(lightingTexture, ivec2(previousIndex, shadowRow), 0).r;
                                                                       // A partly transparent shadow leaves no single palette index; the index stays.
                                                                       resultIndex = vec4(darkIndex, 0.0, 0.0, coverage == 1.0 ? 1.0 : 0.0);
                                                                       if (shadowStacking != 0) {
                                                                           // Shadow Stacking (sprite_solid_frag.glsl, solid color (0, 0, 0, 0.5), multiply alpha
                                                                           // 0): every shadow darkens the color below it by half its sprite alpha.
                                                                           resultColor = vec4(0.0, 0.0, 0.0, 0.5 * coverage);
                                                                           resultEffect = vec4(0.0);
                                                                       } else {
                                                                           // sprite_effect.glsl SPRITE_SHADOW: the shadow channel at the sprite alpha times the
                                                                           // multiply alpha (the warping-in dimming of WorldRenderer.drawShadowImage).
                                                                           resultColor = vec4(0.0);
                                                                           resultEffect = vec4(1.0, 0.0, 0.0, coverage * vertexColor.a);
                                                                       }
                                                                       return;
                                                                   }
                                                                   sampled = texelFetch(paletteTexture, ivec2(paletteIndex, max(0, int(colorRow))), 0);
                                                                   float opacity = colorRow >= 0.0 && paletteIndex == 0 ? 0.0 : 1.0;
                                                                   resultIndex = vec4(sampled.a, 0.0, 0.0, opacity);
                                                                   sampled.a = opacity;
                                                                   if (indexed == 9)
                                                                       sampled.rgb = vec3(0.75, 1.125, 2.65) * dot(sampled.rgb, vec3(0.30196078, 0.59215686, 0.10980392));
                                                               }
                                                               resultColor = sampled * vertexColor;
                                                               resultEffect = vec4(0.0, 0.0, 0.0, resultColor.a);
                                                           }
                                                           """;

    private static final class AtlasEntry {
        private final int texture;

        private final int horizontal;
        private final int vertical;

        private final int width;
        private final int height;

        /**
         * The smallest rectangle of the unflipped frame holding every nonzero pixel (all of it when it has none).
         */
        private final int opaqueLeft;
        private final int opaqueTop;
        private final int opaqueRight;
        private final int opaqueBottom;

        private AtlasEntry(int texture,
                           int horizontal, int vertical,
                           int width, int height,
                           int opaqueLeft, int opaqueTop, int opaqueRight, int opaqueBottom) {
            this.texture = texture;

            this.horizontal = horizontal;
            this.vertical   = vertical;

            this.width  = width;
            this.height = height;

            this.opaqueLeft   = opaqueLeft;
            this.opaqueTop    = opaqueTop;
            this.opaqueRight  = opaqueRight;
            this.opaqueBottom = opaqueBottom;
        }
    }

    /**
     * Returns the one-based creep-edge frame selected by the eight neighboring tile directions.
     *
     * @param neighborMask the eight-bit mask of neighboring creep tiles
     * @return the one-based creep GRP frame, or {@code 0} when no edge is drawn
     */
    public static int getCreepEdgeFrame(int neighborMask) {
        return CreepEdgeTable.getFrame(neighborMask);
    }

    /**
     * @return the number of creep-edge neighbor directions of a creep edge
     */
    public static int getCreepNeighborCount() {
        return CreepEdgeTable.getDirectionCount();
    }

    /**
     * @param direction the creep-edge neighbor direction from {@code 0} through {@code 7}
     * @return the neighbor's horizontal tile delta
     */
    public static int getCreepNeighborHorizontal(int direction) {
        return CreepEdgeTable.getNeighborHorizontal(direction);
    }

    /**
     * @param direction the creep-edge neighbor direction from {@code 0} through {@code 7}
     * @return the neighbor's vertical tile delta
     */
    public static int getCreepNeighborVertical(int direction) {
        return CreepEdgeTable.getNeighborVertical(direction);
    }

    /**
     * Returns the larger power-of-two-rounded window-to-game ratio, clamped to {@code 2} through {@code 4} and a positive limit.
     *
     * @param windowWidth  the window view width
     * @param windowHeight the window view height
     * @param gameWidth    the game view width in game pixels
     * @param gameHeight   the game view height in game pixels
     * @param limit        the asset-scale limit, or {@code 0} for none
     * @return the integer render scale
     */
    public static int getRenderScale(int windowWidth, int windowHeight, int gameWidth, int gameHeight, int limit) {
        int horizontal = roundUpToPowerOfTwo((int) Math.ceil((float) windowWidth / gameWidth));
        int vertical = roundUpToPowerOfTwo((int) Math.ceil((float) windowHeight / gameHeight));
        int scale = Math.clamp(Math.max(horizontal, vertical), MINIMUM_RENDER_SCALE, MAXIMUM_RENDER_SCALE);

        if (limit > 0 && limit < scale) {
            scale = limit;
        }

        return scale;
    }

    /**
     * Decodes a frame of an override sheet on the sheet's full frame box.
     *
     * @param sheet      the indexed sheet
     * @param frameIndex the frame index
     * @return the decoded frame
     * @throws IOException if the sheet has no such frame
     */
    private static @NotNull ClassicFrame decodeSheetFrame(@NotNull SpriteSheet sheet, int frameIndex) throws IOException {
        if (frameIndex < 0 || frameIndex >= sheet.getFrameCount()) {
            throw Exceptions.newIOException("The image sheet has no frame {0}.", frameIndex);
        }

        return ClassicFrame.of(sheet, frameIndex);
    }

    /**
     * Computes the key of an atlas place size.
     *
     * @param width  the place width
     * @param height the place height
     * @return the key
     */
    private static long getPlaceKey(int width, int height) {
        return (long) width << Integer.SIZE | height;
    }

    /**
     * Creates an {@link AtlasEntry} instance of pixels at a place: the place and the smallest rectangle holding every nonzero pixel (all of it
     * when none is).
     *
     * @param texture    the atlas texture
     * @param horizontal the left of the place in the atlas
     * @param vertical   the top of the place in the atlas
     * @param pixels     the row-major palette indices
     * @param width      the place width
     * @param height     the place height
     * @return the created {@link AtlasEntry} instance
     */
    private static @NotNull AtlasEntry createEntry(int texture, int horizontal, int vertical, byte @NotNull [] pixels, int width, int height) {
        int opaqueLeft = width;
        int opaqueTop = height;
        int opaqueRight = 0;
        int opaqueBottom = 0;

        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                if (pixels[row * width + column] != 0) {
                    opaqueLeft   = Math.min(opaqueLeft, column);
                    opaqueTop    = Math.min(opaqueTop, row);
                    opaqueRight  = Math.max(opaqueRight, column + 1);
                    opaqueBottom = Math.max(opaqueBottom, row + 1);
                }
            }
        }

        if (opaqueRight == 0) {
            opaqueLeft   = 0;
            opaqueTop    = 0;
            opaqueRight  = width;
            opaqueBottom = height;
        }

        return new AtlasEntry(texture, horizontal, vertical, width, height, opaqueLeft, opaqueTop, opaqueRight, opaqueBottom);
    }

    private static int roundUpToPowerOfTwo(int value) {
        if (value <= 1) {
            return value;
        }

        return Integer.highestOneBit(value - 1) << 1;
    }

    /**
     * Sets the vertex layout of the classic world programs on the bound vertex array and array buffer.
     */
    static void setVertexLayout() {
        glEnableVertexAttribArray(POSITION_ATTRIBUTE);
        glEnableVertexAttribArray(COORDINATES_ATTRIBUTE);
        glEnableVertexAttribArray(COLOR_ATTRIBUTE);
        glEnableVertexAttribArray(PALETTE_ROW_ATTRIBUTE);
        glVertexAttribPointer(POSITION_ATTRIBUTE, POSITION_COMPONENTS, GL_FLOAT, false, VERTEX_FLOATS * Float.BYTES, 0L);
        glVertexAttribPointer(COORDINATES_ATTRIBUTE,
                              COORDINATES_COMPONENTS,
                              GL_FLOAT,
                              false,
                              VERTEX_FLOATS * Float.BYTES,
                              (long) COORDINATES_OFFSET * Float.BYTES);
        glVertexAttribPointer(COLOR_ATTRIBUTE, COLOR_COMPONENTS, GL_FLOAT, false, VERTEX_FLOATS * Float.BYTES, (long) COLOR_OFFSET * Float.BYTES);
        glVertexAttribPointer(PALETTE_ROW_ATTRIBUTE,
                              PALETTE_ROW_COMPONENTS,
                              GL_FLOAT,
                              false,
                              VERTEX_FLOATS * Float.BYTES,
                              (long) PALETTE_ROW_OFFSET * Float.BYTES);
    }

    private static int putVertex(float horizontal, float vertical,
                                 float textureHorizontal, float textureVertical,
                                 float red, float green, float blue, float alpha,
                                 int paletteRow,
                                 float @NotNull [] destination, int position) {
        int offset = position;

        destination[offset++] = horizontal;
        destination[offset++] = vertical;
        destination[offset++] = textureHorizontal;
        destination[offset++] = textureVertical;
        destination[offset++] = red;
        destination[offset++] = green;
        destination[offset++] = blue;
        destination[offset++] = alpha;
        destination[offset++] = paletteRow;

        return offset;
    }


    private final @NotNull ShaderProgram program;
    private final int vertexArray;
    private final int vertexBuffer;
    private final @NotNull FloatBuffer vertices;

    private final int framebufferUniform;
    private final int indexedUniform;
    private final int lightingRowUniform;
    private final int lightingRowsUniform;
    private final int shadowRowUniform;
    private final int shadowStackingUniform;
    private final int cloakValueUniform;
    private final int distortionScaleUniform;

    private final int whiteTexture;
    private int paletteTexture;
    /**
     * Palette row {@code 0} (RGB, palette index in alpha), the base of the live player rows.
     */
    private byte @Nullable [] basePaletteRow;
    /**
     * The player color rows last uploaded as palette rows {@code 17..28}.
     */
    private final byte @NotNull [] playerColorRows;
    /**
     * Whether the paused game's gray palette ({@link PaletteGrayscaleUtils}) replaces palette row {@code 0} and the player rows.
     */
    private boolean paletteGrayscale;
    private int lightingTexture;
    private final int @NotNull [] lightingRowOffsets;
    private final int @NotNull [] lightingRowCounts;

    private final @NotNull List<Integer> atlasTextures;
    private int atlasHorizontal;
    private int atlasVertical;
    private int atlasRowHeight;
    private final @Nullable AtlasEntry @NotNull [] terrainEntries;
    /**
     * The atlas entries of replaced megatile pictures by megatile index, and the picture each one holds.
     */
    private final @NotNull Map<Integer, AtlasEntry> megatileEntries;
    private final @NotNull Map<Integer, byte[]> megatilePictures;
    private final @Nullable AtlasEntry @NotNull [] @Nullable [] spriteEntries;
    private @NotNull AtlasEntry @Nullable [] creepEdgeEntries;
    private final @NotNull SequencedMap<Long, AtlasEntry> warpFrames;
    private final @NotNull Map<Long, AtlasEntry> coverageMasks;
    /**
     * The atlas entries of the frames and of the coverage frames of the override sheets ({@link #setImageSheet}), the one drawn least recently
     * first, and the atlas places released by them, by size ({@link #getPlaceKey}).
     */
    private final @NotNull SequencedMap<SpriteSheet, AtlasEntry> sheetEntries;
    private final @NotNull SequencedMap<SpriteSheet, AtlasEntry> sheetCoverageMasks;
    private final @NotNull Map<Long, Deque<AtlasEntry>> releasedSheetPlaces;
    /**
     * The sheet the image draws take their frames from instead of the image's installed graphics, or {@code null}.
     */
    private @Nullable SpriteSheet imageSheet;

    private @Nullable RenderAssets assets;
    private final @NotNull IntUnaryOperator loadedTileIdentifiers;
    private int @Nullable [] mapTiles;
    private int mapWidth;
    private int mapHeight;

    private int batchTexture;
    private int batchIndexed;

    /**
     * The values last given to the effect uniforms ({@code -1} before the first), so that equal draws share a batch.
     */
    private int lightingRowValue;
    private int lightingRowsValue;
    private int cloakValueValue;
    private float distortionScaleValue;

    /**
     * Per {@link #INDEX_TILE_SHIFT} view tile, the stamp ({@link #indexStamp}) of the last draw that may have changed the world index target there. A
     * tile differs from the destination index copy when this stamp or {@link #allIndicesChangedStamp} is newer than its {@link #copiedIndexTiles}
     * stamp: a draw that reads the destination copies again only when its rectangle meets such a tile, so reads of unchanged regions share one copy
     * and one batch. Copies cover whole tiles, so marking a copy costs one entry per tile, not per cell.
     */
    private int @NotNull [] changedIndexTiles;
    /**
     * Per view tile, the stamp of the last destination index copy that covered it.
     */
    private int @NotNull [] copiedIndexTiles;
    private int indexTileColumns;
    private int indexTileRows;
    private int indexCellColumns;
    private int indexCellRows;
    /**
     * The counter of change and copy stamps.
     */
    private int indexStamp;
    /**
     * The stamp after which every cell counts as changed (a new frame, an external pass), whatever its own stamp.
     */
    private int allIndicesChangedStamp;

    /**
     * The image quads of {@link #drawImage} held back until the next submit ({@link #drawHeldImages}). Each has a draw level: one more than the
     * levels of the earlier held quads that wrote where it reads or writes, and at least the levels of those that read where it writes. Drawn level
     * by level, every pixel still sees its draws in their order, while all draws of a level that read the destination share one destination copy.
     */
    private int heldCount;
    private int @NotNull [] heldTextures;
    private int @NotNull [] heldModes;
    private int @NotNull [] heldUniforms;
    private int @NotNull [] heldLevels;
    /**
     * Per quad: the written cells (first column, last column, first row, last row), then the read cells (none: {@code first > last}).
     */
    private int @NotNull [] heldCells;
    private float @NotNull [] heldVertices;
    /**
     * The draw order of the held quads ({@link #drawHeldImages}).
     */
    private final @NotNull HeldImageOrder heldOrder;
    /**
     * Per view cell: {@code epoch << 16 | level} of the last held quad that wrote it, and the highest level that read it.
     */
    private int @NotNull [] writtenLevels;
    private int @NotNull [] readLevels;
    /**
     * The epoch of the current held quads, so that the level arrays need no clearing.
     */
    private int levelEpoch;
    /**
     * Whether {@link #drawImage} draws with the hallucination tint ({@link #drawHallucinatedImage}).
     */
    private boolean hallucinating;

    private int worldFramebuffer;

    /**
     * The largest world render scale: {@code 1} for classic graphics, {@code 2} for HD2 and {@code 4} for HD.
     */
    private int renderScaleLimit;
    /**
     * The largest world target side the context allows (texture size and viewport), read on the first frame.
     */
    private int maximumTargetSize;
    private int windowFramebufferWidth;
    private int windowFramebufferHeight;
    private int windowViewWidth;
    private int windowViewHeight;
    private float windowScale;
    private int worldTargetWidth;
    private int worldTargetHeight;

    /**
     * Whether the SD present to the window waits for the interface ({@link #beginSdInterface}, {@link #presentSd}).
     */
    private boolean sdPresentPending;
    /**
     * Whether {@link #finishWorld} leaves an SD frame's present for the caller's interface ({@link #setSdInterfaceDeferred}).
     */
    private boolean sdInterfaceDeferred;
    /**
     * The SD target at one pixel per game pixel, holding the world and the interface.
     */
    private int sdSourceFramebuffer;
    private int sdSourceTexture;
    private int sdSourceWidth;
    private int sdSourceHeight;
    private int sdFilterMode;
    private int sdFramebuffer;
    private int sdTexture;
    private int sdTargetWidth;
    private int sdTargetHeight;

    private int worldIndexTexture;
    private int destinationIndexTexture;
    private int worldColorTexture;
    private int worldEffectTexture;
    private boolean worldFinished;
    private boolean worldClipped;
    private boolean shadowStacking;
    /**
     * The vertex color of classic frame quads: opaque white, or a translucency for the placement ghost.
     */
    private int entryColor;
    /**
     * The multiply alpha of the next held image quads, on top of {@link #entryColor}.
     */
    private float entryOpacity;

    private int framebufferWidth;
    private int framebufferHeight;
    private int viewWidth;
    private int viewHeight;
    private float cameraX;
    private float cameraY;
    private float scale;

    private boolean closed;

    /**
     * Creates a {@link WorldRenderer} instance on the current OpenGL 3.3 context.
     */
    public WorldRenderer() {
        this.program      = GraphicsDevice.getCurrent().createShaderProgram(SHADER_PROGRAM_NAME, VERTEX_SHADER, FRAGMENT_SHADER);
        this.vertexArray  = glGenVertexArrays();
        this.vertexBuffer = glGenBuffers();
        this.vertices     = MemoryUtil.memAllocFloat(MAXIMUM_VERTICES * VERTEX_FLOATS);

        this.framebufferUniform     = this.program.getUniformLocation("framebufferSize");
        this.indexedUniform         = this.program.getUniformLocation("indexed");
        this.lightingRowUniform     = this.program.getUniformLocation("lightingRow");
        this.lightingRowsUniform    = this.program.getUniformLocation("lightingRows");
        this.shadowRowUniform       = this.program.getUniformLocation("shadowRow");
        this.shadowStackingUniform  = this.program.getUniformLocation("shadowStacking");
        this.cloakValueUniform      = this.program.getUniformLocation("cloakValue");
        this.distortionScaleUniform = this.program.getUniformLocation("distortionScale");

        this.whiteTexture       = glGenTextures();
        this.paletteTexture     = 0;
        this.basePaletteRow     = null;
        this.playerColorRows    = new byte[SceneRenderer.PLAYER_COLOR_ROWS * RenderAssets.REMAP_INDEX_COUNT];
        this.paletteGrayscale   = false;
        this.lightingTexture    = 0;
        this.lightingRowOffsets = new int[RenderAssets.LIGHTING_TABLE_NAMES.length];
        this.lightingRowCounts  = new int[RenderAssets.LIGHTING_TABLE_NAMES.length];

        this.atlasTextures    = new ArrayList<>();
        this.atlasHorizontal  = 0;
        this.atlasVertical    = 0;
        this.atlasRowHeight   = 0;
        this.terrainEntries   = new AtlasEntry[TILE_IDENTIFIER_COUNT];
        this.megatileEntries  = new HashMap<>();
        this.megatilePictures = new HashMap<>();
        this.spriteEntries    = new AtlasEntry[ScrAddedGraphics.LAST_ID + 1][];
        this.creepEdgeEntries = null;
        this.warpFrames       = new LinkedHashMap<>();
        this.coverageMasks    = new HashMap<>();

        this.sheetEntries        = new LinkedHashMap<>();
        this.sheetCoverageMasks  = new LinkedHashMap<>();
        this.releasedSheetPlaces = new HashMap<>();
        this.imageSheet          = null;

        this.assets                = null;
        this.loadedTileIdentifiers = tileIndex -> this.mapTiles[tileIndex];
        this.mapTiles              = null;
        this.mapWidth              = 0;
        this.mapHeight             = 0;

        this.batchTexture = 0;
        this.batchIndexed = 0;

        this.lightingRowValue     = -1;
        this.lightingRowsValue    = -1;
        this.cloakValueValue      = -1;
        this.distortionScaleValue = -1;

        this.changedIndexTiles      = new int[0];
        this.copiedIndexTiles       = new int[0];
        this.indexTileColumns       = 0;
        this.indexTileRows          = 0;
        this.indexCellColumns       = 0;
        this.indexCellRows          = 0;
        this.indexStamp             = 0;
        this.allIndicesChangedStamp = 0;

        this.heldCount     = 0;
        this.heldTextures  = new int[INITIAL_HELD_CAPACITY];
        this.heldModes     = new int[INITIAL_HELD_CAPACITY];
        this.heldUniforms  = new int[INITIAL_HELD_CAPACITY];
        this.heldLevels    = new int[INITIAL_HELD_CAPACITY];
        this.heldCells     = new int[INITIAL_HELD_CAPACITY * HELD_CELL_VALUES];
        this.heldVertices  = new float[INITIAL_HELD_CAPACITY * QUAD_FLOATS];
        this.heldOrder     = new HeldImageOrder(INITIAL_HELD_CAPACITY);
        this.writtenLevels = new int[0];
        this.readLevels    = new int[0];
        this.levelEpoch    = 1;
        this.hallucinating = false;

        this.worldFramebuffer = 0;

        this.renderScaleLimit        = CLASSIC_SCALE_LIMIT;
        this.maximumTargetSize       = 0;
        this.windowFramebufferWidth  = 0;
        this.windowFramebufferHeight = 0;
        this.windowViewWidth         = 0;
        this.windowViewHeight        = 0;
        this.windowScale             = 0;
        this.worldTargetWidth        = 0;
        this.worldTargetHeight       = 0;

        this.sdPresentPending    = false;
        this.sdInterfaceDeferred = false;
        this.sdSourceFramebuffer = 0;
        this.sdSourceTexture     = 0;
        this.sdSourceWidth       = 0;
        this.sdSourceHeight      = 0;
        this.sdFilterMode        = DEFAULT_SD_FILTER_MODE;
        this.sdFramebuffer       = 0;
        this.sdTexture           = 0;
        this.sdTargetWidth       = 0;
        this.sdTargetHeight      = 0;

        this.worldIndexTexture       = 0;
        this.destinationIndexTexture = 0;
        this.worldColorTexture       = 0;
        this.worldEffectTexture      = 0;
        this.worldFinished           = false;
        this.worldClipped            = false;
        this.shadowStacking          = false;
        this.entryColor              = OPAQUE_WHITE;
        this.entryOpacity            = 1;

        this.framebufferWidth  = 0;
        this.framebufferHeight = 0;
        this.viewWidth         = 0;
        this.viewHeight        = 0;
        this.cameraX           = 0;
        this.cameraY           = 0;
        this.scale             = 1;

        this.closed = false;

        glBindVertexArray(this.vertexArray);
        glBindBuffer(GL_ARRAY_BUFFER, this.vertexBuffer);
        glBufferData(GL_ARRAY_BUFFER, (long) this.vertices.capacity() * Float.BYTES, GL_STREAM_DRAW);
        setVertexLayout();
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, this.whiteTexture);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 1, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, new int[]{OPAQUE_WHITE});
        this.program.use();
        glUniform1i(this.program.getUniformLocation("imageTexture"), IMAGE_UNIT);
        glUniform1i(this.program.getUniformLocation("paletteTexture"), PALETTE_UNIT);
        glUniform1i(this.program.getUniformLocation("destinationIndices"), DESTINATION_UNIT);
        glUniform1i(this.program.getUniformLocation("lightingTexture"), LIGHTING_UNIT);
    }

    /**
     * Returns a unit's base image index from the loaded graphics tables.
     *
     * @param unitType the DAT unit type
     * @return the DAT image index
     * @throws IllegalStateException if no map is loaded
     */
    public int getUnitImageIndex(int unitType) {
        return this.requireAssets().getUnitImage(unitType);
    }

    /**
     * Reads the installed GRP frame count of an image without decoding its pixel data.
     *
     * @param imageIndex the image type index
     * @return the number of explicit GRP frames
     * @throws IOException           if the image cannot be loaded
     * @throws IllegalStateException if no map is loaded
     */
    public int getImageFrameCount(int imageIndex) throws IOException {
        return this.requireAssets().getImageFrameCount(imageIndex);
    }

    /**
     * Reads a unit's base image frame count without decoding its pixel data.
     *
     * @param unitType the DAT unit type
     * @return the number of explicit GRP frames
     * @throws IOException           if the sprite cannot be loaded
     * @throws IllegalStateException if no map is loaded
     */
    public int getUnitFrameCount(int unitType) throws IOException {
        RenderAssets loadedAssets = this.requireAssets();

        return loadedAssets.getImageFrameCount(loadedAssets.getUnitImage(unitType));
    }

    /**
     * Returns the canvas-center world coordinate that places a frame rectangle at its frozen top coordinate.
     *
     * @param imageIndex the image identifier
     * @param frameIndex the current frame
     * @param frozenMapY the frozen top map coordinate of the frame rectangle
     * @return the canvas-center world coordinate
     * @throws IOException           if the image or frame is unavailable
     * @throws IllegalStateException if no map is loaded
     */
    public int getFrozenCenterY(int imageIndex, int frameIndex, int frozenMapY) throws IOException {
        return this.requireAssets().getFrozenCanvasCenterY(imageIndex, frameIndex, frozenMapY);
    }

    /**
     * Returns the packed offset from the image canvas center to its classic frame rectangle's top-left corner.
     *
     * @param imageIndex the image identifier
     * @param frameIndex the current frame
     * @param flipped    whether the image is horizontally flipped
     * @return the packed offset (horizontal in the low {@code 16} bits, vertical in the high {@code 16} bits)
     * @throws IOException           if the image or frame is unavailable
     * @throws IllegalStateException if no map is loaded
     */
    public int getFrameMapOffset(int imageIndex, int frameIndex, boolean flipped) throws IOException {
        return this.requireAssets().getFrameMapOffset(imageIndex, frameIndex, flipped);
    }

    /**
     * @param imageIndex the image identifier
     * @return the image's {@code images.rel} relation type ({@link RenderAssets#getImageRelationType})
     * @throws IOException           if {@code images.rel} is missing or malformed
     * @throws IllegalStateException if no map is loaded
     */
    int getImageRelationType(int imageIndex) throws IOException {
        return this.requireAssets().getImageRelationType(imageIndex);
    }

    /**
     * @param imageIndex the image identifier
     * @return the image's {@code SD/mainSD.anim} entry ({@link RenderAssets#getSdEntry})
     * @throws IOException           if the anim is missing or malformed
     * @throws IllegalStateException if no map is loaded
     */
    AnimFile.@NotNull Entry getSdEntry(int imageIndex) throws IOException {
        return this.requireAssets().getSdEntry(imageIndex);
    }

    /**
     * @return {@code true} if shadow images stack ({@link #setShadowStacking(boolean)}), {@code false} otherwise
     */
    public boolean isShadowStacking() {
        return this.shadowStacking;
    }

    /**
     * Sets stacked shadow blending or a single composite shadow mask for subsequent frames.
     *
     * @param enabled {@code true} for stacking shadows
     */
    public void setShadowStacking(boolean enabled) {
        this.shadowStacking = enabled;
    }

    /**
     * @return the loaded map width in tiles, or {@code 0} without a map
     */
    public int getMapWidth() {
        return this.mapTiles == null ? 0 : this.mapWidth;
    }

    /**
     * @return the loaded map height in tiles, or {@code 0} without a map
     */
    public int getMapHeight() {
        return this.mapTiles == null ? 0 : this.mapHeight;
    }

    /**
     * @return the world framebuffer object that {@link #finishWorld()} resolves
     */
    public int getWorldFramebuffer() {
        return this.worldFramebuffer;
    }

    /**
     * @return the physical framebuffer width of the current frame
     */
    public int getFramebufferWidth() {
        return this.framebufferWidth;
    }

    /**
     * @return the physical framebuffer height of the current frame
     */
    public int getFramebufferHeight() {
        return this.framebufferHeight;
    }

    /**
     * @return the world viewport width of the current frame
     */
    public int getViewWidth() {
        return this.viewWidth;
    }

    /**
     * @return the world viewport height of the current frame
     */
    public int getViewHeight() {
        return this.viewHeight;
    }

    /**
     * @return the pixel-snapped world coordinate at the viewport's left edge
     */
    public float getCameraX() {
        return this.cameraX;
    }

    /**
     * @return the pixel-snapped world coordinate at the viewport's top edge
     */
    public float getCameraY() {
        return this.cameraY;
    }

    /**
     * @return the screen pixels per world pixel of the current frame
     */
    public float getScale() {
        return this.scale;
    }

    /**
     * Sets the largest world render scale, with classic graphics normally using one target pixel per game pixel.
     *
     * @param limit the largest scale, {@code 1}, {@code 2} or {@code 4}
     * @throws IllegalArgumentException if the limit is below {@code 1}
     */
    public void setRenderScaleLimit(int limit) {
        if (limit < 1) {
            throw Exceptions.newIllegalArgumentException("Invalid render scale limit: {0}", limit);
        }

        this.renderScaleLimit = limit;
    }

    /**
     * Sets the SD compose and present filters: nearest, linear or bicubic according to mode.
     *
     * @param mode the filter mode, {@code 0} to {@code 3}
     * @throws IllegalArgumentException if the mode is outside {@code 0} to {@code 3}
     */
    public void setSdFilterMode(int mode) {
        if (mode < 0 || mode >= SD_FILTER_MODES) {
            throw Exceptions.newIllegalArgumentException("SDFilterMode must be 0 through 3: {0}", mode);
        }

        this.sdFilterMode = mode;
    }

    /**
     * Sets whether SD presentation remains pending for interface drawing or presents at the world boundary.
     *
     * @param deferred whether the present waits for the interface
     */
    public void setSdInterfaceDeferred(boolean deferred) {
        this.sdInterfaceDeferred = deferred;
    }

    /**
     * @return {@code true} if an SD frame waits for its interface before the present ({@link #beginSdInterface}, {@link #presentSd}), {@code false}
     * otherwise
     */
    public boolean isSdPresentPending() {
        return this.sdPresentPending;
    }

    /**
     * @return the SD compose target's width in pixels
     */
    public int getSdInterfaceWidth() {
        return this.sdTargetWidth;
    }

    /**
     * @return the SD compose target's height in pixels
     */
    public int getSdInterfaceHeight() {
        return this.sdTargetHeight;
    }


    /**
     * Tests whether the nonnegative frame index lies within the image's unsigned GRP frame count.
     *
     * @param imageIndex the image type index
     * @param frameIndex the image's frame index
     * @return {@code true} if the frame is within the image's GRP, {@code false} otherwise
     * @throws IOException           if the image does not resolve to an available GRP
     * @throws IllegalStateException if no map is loaded
     */
    public boolean isDrawnFrame(int imageIndex, int frameIndex) throws IOException {
        return frameIndex >= 0 && frameIndex < (this.requireAssets().getImageFrameCount(imageIndex) & WORD_MASK);
    }

    /**
     * Returns whether an image's classic GRP frame repeats the frame before it (same line data).
     *
     * @param imageIndex the image identifier
     * @param frameIndex the current frame
     * @return {@code true} if the frame is not the first and shares the previous frame's data, {@code false} otherwise
     * @throws IOException           if the image or frame is unavailable
     * @throws IllegalStateException if no map is loaded
     */
    public boolean isPreviousFrameRepeated(int imageIndex, int frameIndex) throws IOException {
        return this.requireAssets().isPreviousFrameRepeated(imageIndex, frameIndex);
    }

    /**
     * Decodes a classic image frame on its full canvas.
     *
     * @param imageIndex the image identifier
     * @param frameIndex the GRP frame
     * @return the decoded classic frame (full canvas), or {@code null} before the map's assets are loaded
     * @throws IOException if the image or frame is unavailable
     */
    public @Nullable ClassicFrame decodeImageFrame(int imageIndex, int frameIndex) throws IOException {
        if (this.assets == null) {
            return null;
        }

        return this.imageSheet != null ? decodeSheetFrame(this.imageSheet, frameIndex) : this.assets.decodeImage(imageIndex, frameIndex);
    }

    /**
     * Sets the sheet the image draws take their frame from instead of the image's installed graphics: the palette, shadow, cloak and effect draws
     * draw its frame {@code 0}, the baked-remap and warp-texture draws fall back to the palette draw. The frame is uploaded on first use and kept
     * for the {@value #MAXIMUM_SHEET_FRAMES} sheets drawn last while the map is loaded.
     *
     * @param imageSheet a one-frame indexed sheet, or {@code null} for the installed graphics
     */
    void setImageSheet(@Nullable SpriteSheet imageSheet) {
        this.imageSheet = imageSheet;
    }

    /**
     * Tests whether the loaded image's SD texture carries a baked remap.
     *
     * @param imageIndex the image identifier
     * @return {@code true} if the image has a baked remap, {@code false} otherwise or before the map's assets are loaded
     */
    boolean hasBakedRemap(int imageIndex) {
        return this.imageSheet == null && this.assets != null && this.assets.hasBakedRemap(imageIndex);
    }

    /**
     * Decodes the RGBA diffuse atlas of the image's {@code SD/mainSD.anim} entry ({@link RenderAssets#decodeSdDiffuse}).
     *
     * @param imageIndex the image identifier
     * @return the decoded RGBA diffuse atlas
     * @throws IOException           if the anim or its texture is missing or malformed
     * @throws IllegalStateException if no map is loaded
     */
    byte @NotNull [] decodeSdDiffuse(int imageIndex) throws IOException {
        return this.requireAssets().decodeSdDiffuse(imageIndex);
    }

    private @NotNull RenderAssets requireAssets() {
        RenderAssets loadedAssets = this.assets;

        if (loadedAssets == null) {
            throw Exceptions.newIllegalStateException("No map is loaded.");
        }

        return loadedAssets;
    }


    /**
     * Loads terrain and graphics tables, replacing any previously loaded map.
     * <p>
     * A failed asset decode leaves no partially rendered map. The caller retains ownership of the provider.
     *
     * @param resources       the resource provider of the installation, kept open while this map is displayed
     * @param tileset         the tileset index
     * @param width           the map width in terrain tiles
     * @param height          the map height in terrain tiles
     * @param tileIdentifiers the row-major {@code MTXM} values
     * @throws IOException if a required asset or tile reference is invalid
     */
    public void loadMap(@NotNull ScrResourceProvider resources, int tileset, int width, int height, int @NotNull [] tileIdentifiers) throws IOException {
        if (width <= 0 || height <= 0 || (long) width * height > tileIdentifiers.length) {
            throw Exceptions.newIOException("Terrain dimensions exceed the supplied map tiles.");
        }

        RenderAssets loadedAssets = RenderAssets.load(resources, tileset);

        this.flush();
        this.clearMap();

        try {
            this.uploadPalette(loadedAssets);
            this.uploadLightingTables(loadedAssets);

            for (int tileIndex = 0; tileIndex < width * height; tileIndex++) {
                int tileIdentifier = tileIdentifiers[tileIndex];

                if (tileIdentifier < 0 || tileIdentifier >= this.terrainEntries.length) {
                    throw Exceptions.newIOException("Terrain tile value is outside the unsigned word range: {0}", tileIdentifier);
                }

                if (this.terrainEntries[tileIdentifier] == null) {
                    this.terrainEntries[tileIdentifier] = this.uploadFrame(loadedAssets.decodeTile(tileIdentifier),
                                                                           TerrainTileset.TILE_SIZE,
                                                                           TerrainTileset.TILE_SIZE);
                }
            }

            AtlasEntry[] loadedCreepEdges = new AtlasEntry[loadedAssets.getCreepEdgeFrameCount()];

            for (int frameIndex = 0; frameIndex < loadedCreepEdges.length; frameIndex++) {
                ClassicFrame frame = loadedAssets.decodeCreepEdge(frameIndex);

                loadedCreepEdges[frameIndex] = this.uploadFrame(frame.getPixels(), frame.getWidth(), frame.getHeight());
            }

            this.creepEdgeEntries = loadedCreepEdges;
            this.assets           = loadedAssets;
            this.mapTiles         = Arrays.copyOf(tileIdentifiers, width * height);
            this.mapWidth         = width;
            this.mapHeight        = height;
        } catch (IOException | RuntimeException exception) {
            this.clearMap();

            throw exception;
        }
    }

    /**
     * Uploads the palette rows of a newly loaded map: rows {@code 1..16} remap by color index; rows {@code 17..28} by player, from the game context's
     * live rows ({@link #setPlayerColorRows}), which start as the rows of colors {@code 0..11}.
     *
     * @param loadedAssets the assets of the new map
     */
    private void uploadPalette(@NotNull RenderAssets loadedAssets) {
        this.paletteTexture = glGenTextures();
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, this.paletteTexture);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);

        byte[] colorRows = loadedAssets.copyIndexedPalette();
        byte[] paletteColors = Arrays.copyOf(colorRows, colorRows.length + SceneRenderer.PLAYER_COLOR_ROWS * RenderAssets.PALETTE_BYTES);

        System.arraycopy(colorRows, RenderAssets.PALETTE_BYTES,
                         paletteColors, colorRows.length,
                         SceneRenderer.PLAYER_COLOR_ROWS * RenderAssets.PALETTE_BYTES);

        this.basePaletteRow = Arrays.copyOf(colorRows, RenderAssets.PALETTE_BYTES);
        // A new map's palette starts as loaded (a pause of the previous game does not carry over).
        this.paletteGrayscale = false;

        for (int player = 0; player < SceneRenderer.PLAYER_COLOR_ROWS; player++) {
            int paletteRowOffset = (player + 1) * RenderAssets.PALETTE_BYTES;

            for (int index = 0; index < RenderAssets.REMAP_INDEX_COUNT; index++) {
                int entryOffset = paletteRowOffset + (RenderAssets.REMAP_FIRST_INDEX + index) * RenderAssets.PALETTE_ENTRY_SIZE;

                this.playerColorRows[player * RenderAssets.REMAP_INDEX_COUNT + index] = colorRows[entryOffset + RenderAssets.PALETTE_FOURTH];
            }
        }

        ByteBuffer palette = MemoryUtil.memAlloc(paletteColors.length);

        try {
            palette.put(paletteColors).flip();
            glTexImage2D(GL_TEXTURE_2D,
                         0,
                         GL_RGBA8,
                         RenderAssets.PALETTE_ENTRIES,
                         PLAYER_PALETTE_ROW + SceneRenderer.PLAYER_COLOR_ROWS,
                         0,
                         GL_RGBA,
                         GL_UNSIGNED_BYTE,
                         palette);
        } finally {
            MemoryUtil.memFree(palette);
            glActiveTexture(GL_TEXTURE0);
        }
    }

    /**
     * Uploads the tileset lighting tables of a newly loaded map as one texture of consecutive rows.
     *
     * @param loadedAssets the assets of the new map
     * @throws IOException if a lighting table is missing, malformed or not {@code 256} columns wide
     */
    private void uploadLightingTables(@NotNull RenderAssets loadedAssets) throws IOException {
        this.lightingTexture = glGenTextures();
        glActiveTexture(GL_TEXTURE3);
        glBindTexture(GL_TEXTURE_2D, this.lightingTexture);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);

        byte[][] lightingTables = new byte[RenderAssets.LIGHTING_TABLE_NAMES.length][];
        int lightingRows = 0;

        for (int tableIndex = 0; tableIndex < lightingTables.length; tableIndex++) {
            lightingTables[tableIndex]          = loadedAssets.getLightingTable(tableIndex);
            this.lightingRowOffsets[tableIndex] = lightingRows;
            this.lightingRowCounts[tableIndex]  = lightingTables[tableIndex].length / RenderAssets.PALETTE_ENTRIES;

            lightingRows += this.lightingRowCounts[tableIndex];
        }

        ByteBuffer lighting = MemoryUtil.memAlloc(RenderAssets.PALETTE_ENTRIES * lightingRows);

        try {
            for (byte[] lightingTable : lightingTables) {
                lighting.put(lightingTable);
            }

            lighting.flip();
            glTexImage2D(GL_TEXTURE_2D, 0, GL_R8, RenderAssets.PALETTE_ENTRIES, lightingRows, 0, GL_RED, GL_UNSIGNED_BYTE, lighting);
        } finally {
            MemoryUtil.memFree(lighting);
            glActiveTexture(GL_TEXTURE0);
        }
    }

    /**
     * Begins a world frame and full-framebuffer overlay surface at an integer render scale. Framebuffer, view and scale getters describe the world
     * target until it is resolved into the window.
     *
     * @param framebufferWidth  the physical framebuffer width
     * @param framebufferHeight the physical framebuffer height
     * @param viewWidth         the world viewport width
     * @param viewHeight        the world viewport height
     * @param cameraX           the world coordinate at the viewport's left edge
     * @param cameraY           the world coordinate at the viewport's top edge
     * @param scale             the screen pixels per world pixel
     * @throws IllegalStateException if the renderer is closed or the frame dimensions are invalid
     */
    public void beginFrame(int framebufferWidth, int framebufferHeight, int viewWidth, int viewHeight, float cameraX, float cameraY, float scale) {
        if (this.closed || framebufferWidth <= 0 || framebufferHeight <= 0 || !Float.isFinite(scale) || scale <= 0) {
            throw Exceptions.newIllegalStateException("The renderer or frame dimensions are invalid.");
        }

        this.vertices.clear();
        this.batchTexture            = 0;
        this.worldClipped            = false;
        this.worldFinished           = false;
        this.windowFramebufferWidth  = framebufferWidth;
        this.windowFramebufferHeight = framebufferHeight;
        this.windowViewWidth         = Math.clamp(viewWidth, 0, framebufferWidth);
        this.windowViewHeight        = Math.clamp(viewHeight, 0, framebufferHeight);
        this.windowScale             = scale;

        int gameWidth = Math.max(1, (int) Math.ceil(Math.max(1, this.windowViewWidth) / scale));
        int gameHeight = Math.max(1, (int) Math.ceil(Math.max(1, this.windowViewHeight) / scale));
        float renderScale = getRenderScale(Math.max(1, this.windowViewWidth),
                                           Math.max(1, this.windowViewHeight),
                                           gameWidth,
                                           gameHeight,
                                           this.renderScaleLimit);

        // Limits target dimensions when a zoomed-out view exceeds the OpenGL context limits.
        if (this.maximumTargetSize == 0) {
            int[] viewport = new int[VIEWPORT_DIMENSIONS];

            glGetIntegerv(GL_MAX_VIEWPORT_DIMS, viewport);
            this.maximumTargetSize = Math.max(1, Math.min(glGetInteger(GL_MAX_TEXTURE_SIZE), Math.min(viewport[0], viewport[1])));
        }

        // Classic graphics present through a target of twice the world target's size (presentSd).
        int targetLimit = this.renderScaleLimit == CLASSIC_SCALE_LIMIT ? this.maximumTargetSize / SD_COMPOSE_SCALE : this.maximumTargetSize;

        renderScale = Math.min(renderScale, targetLimit * scale / Math.max(framebufferWidth, framebufferHeight));

        // The target covers the whole framebuffer at the render scale; the world viewport is its top-left part.
        int targetWidth = Math.max(1, Math.round(framebufferWidth / scale * renderScale));
        int targetHeight = Math.max(1, Math.round(framebufferHeight / scale * renderScale));
        int targetViewWidth = Math.clamp(Math.round(this.windowViewWidth / scale * renderScale), 1, targetWidth);
        int targetViewHeight = Math.clamp(Math.round(this.windowViewHeight / scale * renderScale), 1, targetHeight);

        this.bindWorldTarget(targetWidth, targetHeight);

        // Uses linear window filtering for a scaled HD target; classic graphics use the selected SD filter mode.
        int filter = this.renderScaleLimit > CLASSIC_SCALE_LIMIT && targetWidth != framebufferWidth ? GL_LINEAR : GL_NEAREST;

        // Classic graphics sample the world into the 2x target with the SD filter table's first byte.
        if (this.renderScaleLimit == CLASSIC_SCALE_LIMIT) {
            filter = this.sdFilterMode >= SD_FILTER_LINEAR_COMPOSE ? GL_LINEAR : GL_NEAREST;
        }

        glBindTexture(GL_TEXTURE_2D, this.worldColorTexture);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter);
        glBindTexture(GL_TEXTURE_2D, this.worldEffectTexture);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter);

        this.framebufferWidth  = targetWidth;
        this.framebufferHeight = targetHeight;
        this.viewWidth         = targetViewWidth;
        this.viewHeight        = targetViewHeight;

        this.beginIndexTracking(targetViewWidth, targetViewHeight);

        // Snap the camera to whole target pixels: a fractional offset (for example the centered camera of an odd view height) puts nearest-sampled
        // tile and frame edges on texel boundaries, which repeats neighboring atlas rows.
        this.cameraX = Math.round(cameraX * renderScale) / renderScale;
        this.cameraY = Math.round(cameraY * renderScale) / renderScale;
        this.scale   = renderScale;

        glViewport(0, 0, targetWidth, targetHeight);
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);
        glDisable(GL_SCISSOR_TEST);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        // Clears the world target to black before drawing terrain.
        glClearColor(0, 0, 0, 1);
        glClear(GL_COLOR_BUFFER_BIT);
        glClearBufferfv(GL_COLOR, EFFECT_TARGET, ZERO_CLEAR_VALUE);
        glClearBufferfv(GL_COLOR, INDEX_TARGET, ZERO_CLEAR_VALUE);
        this.program.use();
        glUniform2f(this.framebufferUniform, targetWidth, targetHeight);
        glUniform1i(this.shadowRowUniform, this.lightingRowOffsets[RenderAssets.LIGHTING_TABLE_NAMES.length - 1] + SHADOW_LIGHTING_ROW);
        glUniform1i(this.shadowStackingUniform, this.shadowStacking ? 1 : 0);
        glBindVertexArray(this.vertexArray);
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, this.paletteTexture);
        // Other renderers sharing the context (the HD scene, interface drawing) may leave their textures on unit 3.
        glActiveTexture(GL_TEXTURE3);
        glBindTexture(GL_TEXTURE_2D, this.lightingTexture);
        glActiveTexture(GL_TEXTURE0);
    }

    /**
     * Binds the world framebuffer, creating it on first use and resizing its color, effect and index targets to a new size.
     *
     * @param targetWidth  the world target width in pixels
     * @param targetHeight the world target height in pixels
     * @throws IllegalStateException if the resized framebuffer is incomplete
     */
    private void bindWorldTarget(int targetWidth, int targetHeight) {
        if (this.worldFramebuffer == 0) {
            this.worldFramebuffer        = glGenFramebuffers();
            this.worldColorTexture       = glGenTextures();
            this.worldEffectTexture      = glGenTextures();
            this.worldIndexTexture       = glGenTextures();
            this.destinationIndexTexture = glGenTextures();
        }

        glBindFramebuffer(GL_FRAMEBUFFER, this.worldFramebuffer);

        if (this.worldTargetWidth == targetWidth && this.worldTargetHeight == targetHeight) {
            return;
        }

        for (int texture : new int[]{this.worldColorTexture, this.worldEffectTexture, this.worldIndexTexture, this.destinationIndexTexture}) {
            glBindTexture(GL_TEXTURE_2D, texture);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            glTexImage2D(GL_TEXTURE_2D,
                         0,
                         texture == this.worldColorTexture || texture == this.worldIndexTexture ? GL_RGBA8 : GL_R8,
                         targetWidth,
                         targetHeight,
                         0,
                         GL_RGBA,
                         GL_UNSIGNED_BYTE,
                         (ByteBuffer) null);
        }

        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, this.worldColorTexture, 0);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT1, GL_TEXTURE_2D, this.worldEffectTexture, 0);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT2, GL_TEXTURE_2D, this.worldIndexTexture, 0);
        GraphicsDevice.getCurrent().selectColorTargets(WORLD_TARGET_COUNT);

        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
            throw Exceptions.newIllegalStateException("The world color/effect framebuffer is incomplete.");
        }

        this.worldTargetWidth  = targetWidth;
        this.worldTargetHeight = targetHeight;
    }

    /**
     * Sizes the index tracking of a new frame's view and marks every cell as changed.
     *
     * @param targetViewWidth  the world viewport width in target pixels
     * @param targetViewHeight the world viewport height in target pixels
     */
    private void beginIndexTracking(int targetViewWidth, int targetViewHeight) {
        this.indexCellColumns = (targetViewWidth + (1 << INDEX_CELL_SHIFT) - 1) >> INDEX_CELL_SHIFT;
        this.indexCellRows    = (targetViewHeight + (1 << INDEX_CELL_SHIFT) - 1) >> INDEX_CELL_SHIFT;
        this.indexTileColumns = (targetViewWidth + (1 << INDEX_TILE_SHIFT) - 1) >> INDEX_TILE_SHIFT;
        this.indexTileRows    = (targetViewHeight + (1 << INDEX_TILE_SHIFT) - 1) >> INDEX_TILE_SHIFT;

        if (this.changedIndexTiles.length < this.indexTileColumns * this.indexTileRows) {
            this.changedIndexTiles = new int[this.indexTileColumns * this.indexTileRows];
            this.copiedIndexTiles  = new int[this.indexTileColumns * this.indexTileRows];
        }

        if (this.writtenLevels.length < this.indexCellColumns * this.indexCellRows) {
            this.writtenLevels = new int[this.indexCellColumns * this.indexCellRows];
            this.readLevels    = new int[this.indexCellColumns * this.indexCellRows];
        }

        // A new frame: every cell counts as changed. The stamps restart before they could wrap.
        if (this.indexStamp > MAXIMUM_INDEX_STAMP) {
            Arrays.fill(this.changedIndexTiles, 0);
            Arrays.fill(this.copiedIndexTiles, 0);
            this.indexStamp = 0;
        }

        this.allIndicesChangedStamp = ++this.indexStamp;
        this.heldCount              = 0;
        this.advanceLevelEpoch();
    }

    /**
     * Draws the visible terrain tiles retained by {@link #loadMap}, including creep edges.
     *
     * @throws UncheckedIOException if a loaded terrain tile cannot be drawn
     */
    public void drawTerrain() {
        if (this.mapTiles == null) {
            return;
        }

        try {
            this.drawTerrain(this.loadedTileIdentifiers);
        } catch (IOException exception) {
            throw Exceptions.newUncheckedIOException(exception, "A loaded terrain tile could not be drawn.");
        }
    }

    /**
     * Draws visible terrain with creep edges selected from current neighboring tile identifiers. Newly encountered tile identifiers are decoded on
     * demand.
     *
     * @param tileIdentifiers the current row-major tile identifier for each tile index of the loaded map extent
     * @throws IOException if a newly encountered tile identifier is outside the installed assets
     */
    public void drawTerrain(@NotNull IntUnaryOperator tileIdentifiers) throws IOException {
        this.drawTerrain(tileIdentifiers, null);
    }

    /**
     * Draws visible terrain with creep edges selected from current neighboring tile identifiers, with megatile pictures that replace installed
     * ones. A replaced megatile keeps one atlas entry, whose texels are replaced when the picture array changes. Newly encountered tile identifiers
     * are decoded on demand.
     *
     * @param tileIdentifiers the current row-major tile identifier for each tile index of the loaded map extent
     * @param megatiles       the picture of a megatile index ({@code 32x32} palette indices, the same array while it is unchanged), or
     *                        {@code null} for the installed picture; {@code null} for no replacements
     * @throws IOException if a newly encountered tile identifier is outside the installed assets
     */
    public void drawTerrain(@NotNull IntUnaryOperator tileIdentifiers, @Nullable IntFunction<byte @Nullable []> megatiles) throws IOException {
        if (this.mapTiles == null || this.assets == null || this.creepEdgeEntries == null) {
            return;
        }

        int firstColumn = Math.max(0, (int) Math.floor(this.cameraX / TerrainTileset.TILE_SIZE));
        int firstRow = Math.max(0, (int) Math.floor(this.cameraY / TerrainTileset.TILE_SIZE));
        int lastColumn = Math.min(this.mapWidth, (int) Math.ceil((this.cameraX + this.viewWidth / this.scale) / TerrainTileset.TILE_SIZE));
        int lastRow = Math.min(this.mapHeight, (int) Math.ceil((this.cameraY + this.viewHeight / this.scale) / TerrainTileset.TILE_SIZE));

        this.setWorldClipped(true);

        for (int row = firstRow; row < lastRow; row++) {
            for (int column = firstColumn; column < lastColumn; column++) {
                int tileIdentifier = tileIdentifiers.applyAsInt(row * this.mapWidth + column);

                if (tileIdentifier < 0 || tileIdentifier >= this.terrainEntries.length) {
                    throw Exceptions.newIOException("Terrain tile value is outside the unsigned word range: {0}", tileIdentifier);
                }

                AtlasEntry entry = this.terrainEntries[tileIdentifier];

                if (entry == null) {
                    this.flush();
                    entry                               = this.uploadFrame(this.assets.decodeTile(tileIdentifier), TerrainTileset.TILE_SIZE, TerrainTileset.TILE_SIZE);
                    this.terrainEntries[tileIdentifier] = entry;
                }

                if (megatiles != null) {
                    entry = this.findMegatileEntry(megatiles, this.assets.getMegatileIndex(tileIdentifier), entry);
                }

                float horizontal = (column * TerrainTileset.TILE_SIZE - this.cameraX) * this.scale;
                float vertical = (row * TerrainTileset.TILE_SIZE - this.cameraY) * this.scale;

                this.selectTexture(entry.texture, MODE_PALETTE);

                // Palette index 0 changes no target except in the opaque terrain draw: creep edges write only within the frame's nonzero pixels.
                this.markIndicesChanged(horizontal, vertical, horizontal + entry.width * this.scale, vertical + entry.height * this.scale);

                float terrainTextureLeft = (float) entry.horizontal / ATLAS_SIZE;
                float terrainTextureRight = (float) (entry.horizontal + entry.width) / ATLAS_SIZE;

                this.appendQuad(horizontal, vertical, horizontal + entry.width * this.scale, vertical + entry.height * this.scale,
                                terrainTextureLeft, (float) entry.vertical / ATLAS_SIZE,
                                terrainTextureRight, (float) (entry.vertical + entry.height) / ATLAS_SIZE,
                                this.entryColor, PALETTE_ROW_OPAQUE);

                if (TerrainTileset.isCreepTile(tileIdentifier)) {
                    continue;
                }

                int neighborMask = 0;

                for (int direction = 0; direction < CreepEdgeTable.getDirectionCount(); direction++) {
                    int neighborColumn = column + CreepEdgeTable.getNeighborHorizontal(direction);
                    int neighborRow = row + CreepEdgeTable.getNeighborVertical(direction);

                    if (neighborColumn < 0 || neighborColumn >= this.mapWidth || neighborRow < 0 || neighborRow >= this.mapHeight) {
                        continue;
                    }

                    if (TerrainTileset.isCreepTile(tileIdentifiers.applyAsInt(neighborRow * this.mapWidth + neighborColumn))) {
                        neighborMask |= 1 << direction;
                    }
                }

                int creepFrame = CreepEdgeTable.getFrame(neighborMask);

                if (creepFrame != 0) {
                    if (creepFrame > this.creepEdgeEntries.length) {
                        throw Exceptions.newIOException("The tileset creep-edge GRP has no frame {0}", creepFrame - 1);
                    }

                    AtlasEntry creepEntry = this.creepEdgeEntries[creepFrame - 1];

                    this.selectTexture(creepEntry.texture, MODE_PALETTE);
                    this.markIndicesChanged(horizontal + creepEntry.opaqueLeft * this.scale,
                                            vertical + creepEntry.opaqueTop * this.scale,
                                            horizontal + creepEntry.opaqueRight * this.scale,
                                            vertical + creepEntry.opaqueBottom * this.scale);

                    float creepTextureLeft = (float) creepEntry.horizontal / ATLAS_SIZE;
                    float creepTextureRight = (float) (creepEntry.horizontal + creepEntry.width) / ATLAS_SIZE;

                    this.appendQuad(horizontal, vertical, horizontal + creepEntry.width * this.scale, vertical + creepEntry.height * this.scale,
                                    creepTextureLeft, (float) creepEntry.vertical / ATLAS_SIZE,
                                    creepTextureRight, (float) (creepEntry.vertical + creepEntry.height) / ATLAS_SIZE,
                                    this.entryColor, 0);
                }
            }
        }
    }

    /**
     * Draws a unit's base image at frame {@code 0}; this is a static preview, not an animation decision.
     *
     * @param unitType    the DAT unit type
     * @param playerColor the player color index from {@code 0} through {@code 15}
     * @param worldX      the canvas center world coordinate
     * @param worldY      the canvas center world coordinate
     * @throws IOException if the sprite cannot be loaded
     */
    public void drawUnit(int unitType, int playerColor, float worldX, float worldY) throws IOException {
        this.drawUnit(unitType, 0, playerColor, worldX, worldY, false);
    }

    /**
     * Draws an explicit frame of a unit's base image without advancing animation or simulation.
     *
     * @param unitType    the DAT unit type
     * @param frameIndex  the explicit GRP frame index
     * @param playerColor the player color index from {@code 0} through {@code 15}
     * @param worldX      the canvas center world coordinate
     * @param worldY      the canvas center world coordinate
     * @param flipped     whether to mirror the full canvas horizontally
     * @throws IOException if the sprite cannot be loaded or the frame index is out of bounds
     */
    public void drawUnit(int unitType, int frameIndex, int playerColor, float worldX, float worldY, boolean flipped) throws IOException {
        if (this.assets != null) {
            this.drawImage(this.assets.getUnitImage(unitType), frameIndex, playerColor, worldX, worldY, flipped);
        }
    }

    /**
     * Draws an explicit image frame centered at the given world position.
     *
     * @param imageIndex  the DAT image index
     * @param frameIndex  the GRP frame index
     * @param playerColor the player color index from {@code 0} through {@code 15}
     * @param worldX      the canvas center world coordinate
     * @param worldY      the canvas center world coordinate
     * @param flipped     whether to mirror the full canvas horizontally
     * @throws IOException if the sprite cannot be loaded
     */
    public void drawImage(int imageIndex, int frameIndex, int playerColor, float worldX, float worldY, boolean flipped) throws IOException {
        this.drawImage(imageIndex, frameIndex, playerColor, worldX, worldY, flipped, false, false, -1, -1, false, false, false);
    }

    private void drawImage(int imageIndex, int frameIndex, int playerColor,
                           float worldX, float worldY, boolean flipped,
                           boolean shadow, boolean brighten,
                           int lightingTable, int cloakValue,
                           boolean remapPlayer, boolean distortion, boolean refraction) throws IOException {
        if (this.assets == null) {
            return;
        }

        if (imageIndex < 0 || imageIndex >= this.spriteEntries.length || frameIndex < 0) {
            throw Exceptions.newIOException("Invalid image or frame index: {0}, {1}", imageIndex, frameIndex);
        }

        AtlasEntry[] imageFrames = this.spriteEntries[imageIndex];
        // Shadows and brightening draw only the frame's coverage, the SD sprite texture's alpha (RenderAssets.decodeCoverage).
        boolean coverage = shadow || brighten;
        long maskKey = imageIndex | (long) frameIndex << KEY_FRAME_SHIFT;
        AtlasEntry entry = coverage
                           ? this.coverageMasks.get(maskKey)
                           : imageFrames != null && frameIndex < imageFrames.length ? imageFrames[frameIndex] : null;

        if (this.imageSheet != null) {
            entry = this.findSheetEntry(this.imageSheet, frameIndex, coverage);
        } else if (entry == null) {
            this.flush();

            ClassicFrame frame = this.assets.decodeImage(imageIndex, frameIndex);
            byte[] framePixels = coverage ? this.assets.decodeCoverage(imageIndex, frameIndex) : frame.getPixels();

            entry = this.uploadFrame(framePixels, frame.getWidth(), frame.getHeight());

            if (coverage) {
                this.coverageMasks.put(maskKey, entry);
            } else {
                if (imageFrames == null || imageFrames.length <= frameIndex) {
                    imageFrames                    = imageFrames == null
                                                     ? new AtlasEntry[Math.max(INITIAL_FRAME_CAPACITY, frameIndex + 1)]
                                                     : Arrays.copyOf(imageFrames, Math.max(imageFrames.length * CAPACITY_GROWTH, frameIndex + 1));
                    this.spriteEntries[imageIndex] = imageFrames;
                }

                imageFrames[frameIndex] = entry;
            }

            this.batchTexture = 0;
        }

        int halfWidth = entry.width / RenderAssets.HALF_DIVISOR;
        float horizontal = (worldX - (flipped ? entry.width - halfWidth : halfWidth) - this.cameraX) * this.scale;
        float vertical = (worldY - entry.height / RenderAssets.HALF_DIVISOR - this.cameraY) * this.scale;

        if (horizontal >= this.viewWidth ||
            vertical >= this.viewHeight ||
            horizontal + entry.width * this.scale <= 0 ||
            vertical + entry.height * this.scale <= 0) {
            return;
        }

        this.setWorldClipped(true);

        int indexed = this.hallucinating ? MODE_HALLUCINATION : MODE_PALETTE;
        int uniform = 0;

        if (brighten) {
            indexed = MODE_BRIGHTEN;
        }

        if (lightingTable >= 0) {
            indexed = remapPlayer ? MODE_PLAYER_LIGHTING : MODE_LIGHTING;
            uniform = lightingTable;
        }

        if (cloakValue >= 0) {
            indexed = MODE_FADE;
            uniform = cloakValue;
        }

        if (distortion) {
            indexed = MODE_DISTORTION;
        }

        if (refraction) {
            indexed = MODE_REFRACTION;
        }

        boolean readsDestination = shadow || lightingTable >= 0 || cloakValue >= 0 || distortion || refraction;
        float readExtension = distortion ? DISTORTION_READ_EXTENSION * this.scale : refraction ? REFRACTION_READ_EXTENSION : 0;
        int paletteRow = shadow
                         ? PALETTE_ROW_SHADOW
                         : Math.clamp(playerColor, 0, SceneRenderer.PLAYER_COLOR + SceneRenderer.PLAYER_COLOR_ROWS - 1) + 1;

        this.holdImageQuad(entry, indexed, uniform, readsDestination, readExtension, horizontal, vertical, paletteRow, flipped);
    }

    /**
     * Returns the atlas entry of the frame of a one-frame override sheet, uploading it on first use: the palette indices, or the coverage (nonzero
     * indices opaque) of shadows and brightening. Past {@value #MAXIMUM_SHEET_FRAMES} sheets, the one drawn least recently releases its atlas
     * place, which the next frame of its size takes.
     *
     * @param sheet      the indexed sheet
     * @param frameIndex the frame index
     * @param coverage   whether the coverage frame is drawn
     * @return the atlas entry of the frame on the sheet's full frame box
     * @throws IOException if the sheet has no such frame or the frame does not fit the atlas
     */
    private @NotNull AtlasEntry findSheetEntry(@NotNull SpriteSheet sheet, int frameIndex, boolean coverage) throws IOException {
        SequencedMap<SpriteSheet, AtlasEntry> entries = coverage ? this.sheetCoverageMasks : this.sheetEntries;
        AtlasEntry entry = entries.get(sheet);

        if (entry != null) {
            entries.putLast(sheet, entry);

            return entry;
        }

        this.flush();

        ClassicFrame frame = decodeSheetFrame(sheet, frameIndex);
        byte[] pixels = frame.getPixels();

        if (coverage) {
            byte[] mask = new byte[pixels.length];

            for (int index = 0; index < mask.length; index++) {
                mask[index] = pixels[index] == 0 ? 0 : OverlayPass.OPAQUE_ALPHA;
            }

            pixels = mask;
        }

        if (entries.size() >= MAXIMUM_SHEET_FRAMES) {
            AtlasEntry released = entries.pollFirstEntry().getValue();

            this.releasedSheetPlaces.computeIfAbsent(getPlaceKey(released.width, released.height), key -> new ArrayDeque<>()).push(released);
        }

        Deque<AtlasEntry> places = this.releasedSheetPlaces.get(getPlaceKey(frame.getWidth(), frame.getHeight()));

        if (places == null || places.isEmpty()) {
            entry = this.uploadFrame(pixels, frame.getWidth(), frame.getHeight());
        } else {
            AtlasEntry place = places.pop();

            entry = createEntry(place.texture, place.horizontal, place.vertical, pixels, place.width, place.height);
            this.replaceFrame(entry, pixels);
        }

        entries.put(sheet, entry);

        this.batchTexture = 0;

        return entry;
    }

    /**
     * Copies the palette a player color draws with: the screen palette with indices {@code 8..15} remapped by the player color's row, the live row
     * of a player color {@link SceneRenderer#PLAYER_COLOR}{@code + player}.
     *
     * @param playerColor the player color index {@code 0..15}, or {@link SceneRenderer#PLAYER_COLOR}{@code + player}
     * @return the {@link RenderAssets#PALETTE_ENTRIES} entries of {@link RenderAssets#PALETTE_ENTRY_SIZE} bytes, red, green and blue first, or
     * {@code null} before a map is loaded
     */
    public byte @Nullable [] copyPlayerColorPalette(int playerColor) {
        RenderAssets loadedAssets = this.assets;
        byte[] base = this.basePaletteRow;

        if (loadedAssets == null || base == null) {
            return null;
        }

        if (playerColor < SceneRenderer.PLAYER_COLOR) {
            int row = Math.clamp(playerColor, 0, RenderAssets.REMAP_ROWS - 1) + 1;

            return Arrays.copyOfRange(loadedAssets.copyPalette(), row * RenderAssets.PALETTE_BYTES, (row + 1) * RenderAssets.PALETTE_BYTES);
        }

        int player = Math.min(playerColor - SceneRenderer.PLAYER_COLOR, SceneRenderer.PLAYER_COLOR_ROWS - 1);
        byte[] row = base.clone();

        for (int index = 0; index < RenderAssets.REMAP_INDEX_COUNT; index++) {
            int remapped = this.playerColorRows[player * RenderAssets.REMAP_INDEX_COUNT + index] & BYTE_MASK;

            System.arraycopy(base, remapped * RenderAssets.PALETTE_ENTRY_SIZE,
                             row, (RenderAssets.REMAP_FIRST_INDEX + index) * RenderAssets.PALETTE_ENTRY_SIZE,
                             RenderAssets.PALETTE_FOURTH);
        }

        return row;
    }

    /**
     * Sets the player remapping rows (palette rows {@code 17..28}, selected by player colors {@link SceneRenderer#PLAYER_COLOR}{@code + player}) with
     * the game context's live rows.
     *
     * @param rows the twelve rows of eight palette indices
     */
    public void setPlayerColorRows(byte @NotNull [] rows) {
        byte[] base = this.basePaletteRow;

        if (base == null || Arrays.equals(rows, 0, this.playerColorRows.length, this.playerColorRows, 0, this.playerColorRows.length)) {
            return;
        }

        this.flush();
        System.arraycopy(rows, 0, this.playerColorRows, 0, this.playerColorRows.length);
        this.uploadPlayerColorRows(base);
    }

    /**
     * Sets whether the screen palette is gray while retaining remapping indices.
     *
     * @param grayscale whether the gray palette applies
     */
    public void setPaletteGrayscale(boolean grayscale) {
        byte[] base = this.basePaletteRow;

        if (base == null || grayscale == this.paletteGrayscale) {
            return;
        }

        this.flush();
        this.paletteGrayscale = grayscale;
        this.uploadScreenPaletteRow(base);
        this.uploadPlayerColorRows(base);
    }

    /**
     * Sets screen palette RGB colors and updates the player rows, retaining any active grayscale conversion.
     *
     * @param colors the {@code 256} packed {@code 0x00BBGGRR} colors
     */
    public void setPaletteColors(int @NotNull [] colors) {
        byte[] base = this.basePaletteRow;

        if (base == null) {
            return;
        }

        boolean changed = false;

        for (int index = 0; index < RenderAssets.PALETTE_ENTRIES && index < colors.length; index++) {
            int offset = index * RenderAssets.PALETTE_ENTRY_SIZE;
            byte red = (byte) colors[index];
            byte green = (byte) (colors[index] >> PALETTE_COLOR_GREEN_SHIFT);
            byte blue = (byte) (colors[index] >> PALETTE_COLOR_BLUE_SHIFT);

            if (base[offset] == red && base[offset + RenderAssets.PALETTE_GREEN] == green && base[offset + RenderAssets.PALETTE_BLUE] == blue) {
                continue;
            }

            base[offset]                              = red;
            base[offset + RenderAssets.PALETTE_GREEN] = green;
            base[offset + RenderAssets.PALETTE_BLUE]  = blue;

            changed = true;
        }

        if (!changed) {
            return;
        }

        this.flush();
        this.uploadScreenPaletteRow(base);
        this.uploadPlayerColorRows(base);
    }

    private void uploadScreenPaletteRow(byte @NotNull [] base) {
        ByteBuffer upload = MemoryUtil.memAlloc(base.length);

        try {
            upload.put(this.paletteGrayscale ? PaletteGrayscaleUtils.apply(base) : base).flip();
            glActiveTexture(GL_TEXTURE1);
            glBindTexture(GL_TEXTURE_2D, this.paletteTexture);
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, RenderAssets.PALETTE_ENTRIES, 1, GL_RGBA, GL_UNSIGNED_BYTE, upload);
        } finally {
            MemoryUtil.memFree(upload);
            glActiveTexture(GL_TEXTURE0);
        }
    }

    private void uploadPlayerColorRows(byte @NotNull [] loadedBase) {
        byte[] base = this.paletteGrayscale ? PaletteGrayscaleUtils.apply(loadedBase) : loadedBase;
        byte[] rows = this.playerColorRows;
        ByteBuffer upload = MemoryUtil.memAlloc(SceneRenderer.PLAYER_COLOR_ROWS * RenderAssets.PALETTE_BYTES);

        try {
            for (int player = 0; player < SceneRenderer.PLAYER_COLOR_ROWS; player++) {
                byte[] row = base.clone();

                for (int index = 0; index < RenderAssets.REMAP_INDEX_COUNT; index++) {
                    int remapped = rows[player * RenderAssets.REMAP_INDEX_COUNT + index] & BYTE_MASK;
                    int target = (RenderAssets.REMAP_FIRST_INDEX + index) * RenderAssets.PALETTE_ENTRY_SIZE;

                    System.arraycopy(base, remapped * RenderAssets.PALETTE_ENTRY_SIZE, row, target, RenderAssets.PALETTE_FOURTH);
                    row[target + RenderAssets.PALETTE_FOURTH] = (byte) remapped;
                }

                upload.put(row);
            }

            upload.flip();
            glActiveTexture(GL_TEXTURE1);
            glBindTexture(GL_TEXTURE_2D, this.paletteTexture);
            glTexSubImage2D(GL_TEXTURE_2D,
                            0,
                            0,
                            PLAYER_PALETTE_ROW,
                            RenderAssets.PALETTE_ENTRIES,
                            SceneRenderer.PLAYER_COLOR_ROWS,
                            GL_RGBA,
                            GL_UNSIGNED_BYTE,
                            upload);
        } finally {
            MemoryUtil.memFree(upload);
            glActiveTexture(GL_TEXTURE0);
        }
    }

    /**
     * Draws a coverage-scaled shadow, using stacked blending or the composite shadow mask.
     *
     * @param imageIndex the DAT shadow image index
     * @param frameIndex the GRP frame index
     * @param worldX     the canvas center world coordinate
     * @param worldY     the canvas center world coordinate
     * @param flipped    whether to mirror the full canvas horizontally
     * @throws IOException           if the sprite cannot be loaded
     * @throws IllegalStateException if world rendering has already finished
     */
    public void drawShadowImage(int imageIndex, int frameIndex, float worldX, float worldY, boolean flipped) throws IOException {
        this.drawShadowImage(imageIndex, frameIndex, worldX, worldY, flipped, 1);
    }

    /**
     * Draws a shadow as {@link #drawShadowImage(int, int, float, float, boolean)} with a multiply alpha that applies without Shadow Stacking
     * ({@link SceneRenderer#getShadowWarpAlpha}): the shadow channel takes the coverage times it.
     *
     * @param imageIndex    the DAT shadow image index
     * @param frameIndex    the GRP frame index
     * @param worldX        the canvas center world coordinate
     * @param worldY        the canvas center world coordinate
     * @param flipped       whether to mirror the full canvas horizontally
     * @param multiplyAlpha the multiply alpha of the unstacked shadow, {@code 1} for none
     * @throws IOException           if the sprite cannot be loaded
     * @throws IllegalStateException if world rendering has already finished
     */
    public void drawShadowImage(int imageIndex, int frameIndex, float worldX, float worldY, boolean flipped, float multiplyAlpha) throws IOException {
        if (this.worldFinished) {
            throw Exceptions.newIllegalStateException("Shadows must be drawn before finishWorld.");
        }

        this.entryOpacity = this.shadowStacking ? 1 : Math.clamp(multiplyAlpha, 0, 1);

        try {
            this.drawImage(imageIndex, frameIndex, 0, worldX, worldY, flipped, true, false, -1, -1, false, false, false);
        } finally {
            this.entryOpacity = 1;
        }
    }

    /**
     * Draws an image mask filled with the actual warp texture frame.
     *
     * @param imageIndex   the image identifier
     * @param frameIndex   the image frame
     * @param textureFrame the warp texture frame
     * @param worldX       the horizontal world coordinate
     * @param worldY       the vertical world coordinate
     * @param flipped      whether to flip the image mask
     * @throws IOException           if the required frame cannot be decoded
     * @throws IllegalStateException if no world frame is active
     */
    public void drawWarpTexture(int imageIndex, int frameIndex, int textureFrame, float worldX, float worldY, boolean flipped) throws IOException {
        if (this.assets == null || this.worldFinished) {
            throw Exceptions.newIllegalStateException("Warp textures require an active world frame.");
        }

        if (this.imageSheet != null) {
            this.drawImage(imageIndex, frameIndex, 0, worldX, worldY, flipped);

            return;
        }

        if (imageIndex < 0 || imageIndex >= this.spriteEntries.length ||
            frameIndex < 0 || frameIndex > MAXIMUM_FRAME_INDEX ||
            textureFrame < 0 || textureFrame > MAXIMUM_FRAME_INDEX) {
            throw Exceptions.newIOException("Invalid warp image or frame identifier.");
        }

        long key = imageIndex | (long) frameIndex << KEY_FRAME_SHIFT | (long) textureFrame << KEY_TEXTURE_FRAME_SHIFT |
                   (flipped ? 1L << KEY_FLIP_SHIFT : 0);
        AtlasEntry entry = this.warpFrames.get(key);

        if (entry == null) {
            this.flush();

            ClassicFrame frame = this.assets.decodeImage(imageIndex, frameIndex);
            byte[] pixels = this.assets.createWarpPixels(imageIndex, frameIndex, textureFrame, flipped);
            int texture = glGenTextures();

            glBindTexture(GL_TEXTURE_2D, texture);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);

            ByteBuffer upload = MemoryUtil.memAlloc(pixels.length);

            try {
                upload.put(pixels).flip();
                glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
                glTexImage2D(GL_TEXTURE_2D, 0, GL_RG8, frame.getWidth(), frame.getHeight(), 0, GL_RG, GL_UNSIGNED_BYTE, upload);
            } finally {
                MemoryUtil.memFree(upload);
            }

            entry = new AtlasEntry(texture, 0, 0, frame.getWidth(), frame.getHeight(), 0, 0, frame.getWidth(), frame.getHeight());
            this.warpFrames.put(key, entry);

            this.batchTexture = 0;
        }

        this.setWorldClipped(true);
        this.selectTexture(entry.texture, MODE_WARP);

        int halfWidth = entry.width / RenderAssets.HALF_DIVISOR;
        float horizontal = (worldX - (flipped ? entry.width - halfWidth : halfWidth) - this.cameraX) * this.scale;
        float vertical = (worldY - entry.height / RenderAssets.HALF_DIVISOR - this.cameraY) * this.scale;

        this.markIndicesChanged(horizontal, vertical, horizontal + entry.width * this.scale, vertical + entry.height * this.scale);
        this.appendQuad(horizontal, vertical, horizontal + entry.width * this.scale, vertical + entry.height * this.scale,
                        0, 0, 1, 1,
                        OPAQUE_WHITE, 0);
    }

    /**
     * Draws a white coverage-scaled warp flash using the modifier's brighten alpha.
     *
     * @param imageIndex   the image identifier
     * @param frameIndex   the image frame
     * @param modifierData the image modifier data byte
     * @param worldX       the horizontal world coordinate
     * @param worldY       the vertical world coordinate
     * @param flipped      whether to flip the image
     * @throws IOException           if the image is unavailable
     * @throws IllegalStateException if no world frame is active
     */
    public void drawBrightenImage(int imageIndex, int frameIndex, int modifierData, float worldX, float worldY, boolean flipped) throws IOException {
        if (this.worldFinished) {
            throw Exceptions.newIllegalStateException("Brightened images must be drawn before finishWorld.");
        }

        float alpha = SceneRenderer.getBrightenAlpha(modifierData);

        if (alpha <= 0) {
            return;
        }

        this.entryOpacity = alpha;

        try {
            this.drawImage(imageIndex, frameIndex, 0, worldX, worldY, flipped, false, true, -1, -1, false, false, false);
        } finally {
            this.entryOpacity = 1;
        }
    }

    /**
     * Draws an image whose opaque palette indices select lighting-table rows that remap the destination index.
     *
     * @param imageIndex the image identifier
     * @param frameIndex the image frame
     * @param worldX     the horizontal world coordinate of the image center
     * @param worldY     the vertical world coordinate of the image center
     * @param flipped    whether to mirror the full canvas horizontally
     * @throws IOException           if the image is unavailable or its color shift selects no lighting table
     * @throws IllegalStateException if no world frame is active
     */
    public void drawGlowImage(int imageIndex, int frameIndex, float worldX, float worldY, boolean flipped) throws IOException {
        if (this.assets == null || this.worldFinished) {
            throw Exceptions.newIllegalStateException("Lighting remaps require an active world frame.");
        }

        if (imageIndex < 0 || imageIndex >= this.spriteEntries.length) {
            throw Exceptions.newIOException("Invalid glow image identifier: {0}", imageIndex);
        }

        this.drawGlowImage(imageIndex, frameIndex, this.assets.getColorShift(imageIndex), worldX, worldY, flipped);
    }

    /**
     * Draws a draw function {@code 9} image with a given color remapping: the image's own value, copied from the live {@code images.dat} when the
     * game created the image, rather than the installed table ({@link #drawGlowImage(int, int, float, float, boolean)}).
     *
     * @param imageIndex the image identifier
     * @param frameIndex the image frame
     * @param colorShift the color remapping, {@code 1} {@code ofire} through {@code 7} {@code green}
     * @param worldX     the horizontal world coordinate of the image center
     * @param worldY     the vertical world coordinate of the image center
     * @param flipped    whether to mirror the full canvas horizontally
     * @throws IOException           if the image is unavailable or the remapping selects no lighting table
     * @throws IllegalStateException if no world frame is active
     */
    public void drawGlowImage(int imageIndex, int frameIndex, int colorShift, float worldX, float worldY, boolean flipped) throws IOException {
        if (this.assets == null || this.worldFinished) {
            throw Exceptions.newIllegalStateException("Lighting remaps require an active world frame.");
        }

        if (imageIndex < 0 || imageIndex >= this.spriteEntries.length) {
            throw Exceptions.newIOException("Invalid glow image identifier: {0}", imageIndex);
        }

        if (colorShift < 1 || colorShift > RenderAssets.LIGHTING_TABLE_NAMES.length - 1) {
            throw Exceptions.newIOException("Image {0} color shift {1} selects no lighting table.", imageIndex, colorShift);
        }

        this.drawImage(imageIndex, frameIndex, 0, worldX, worldY, flipped, false, false, colorShift - 1, -1, false, false, false);
    }

    /**
     * Draws a cloaked image with the player-colored frame index selecting a destination lighting remap.
     *
     * @param imageIndex  the image identifier
     * @param frameIndex  the image frame
     * @param playerColor the owner color slot
     * @param worldX      the horizontal world coordinate of the image center
     * @param worldY      the vertical world coordinate of the image center
     * @param flipped     whether to mirror the full canvas horizontally
     * @throws IOException           if the image is unavailable
     * @throws IllegalStateException if no world frame is active
     */
    public void drawCloakedImage(int imageIndex, int frameIndex, int playerColor, float worldX, float worldY, boolean flipped) throws IOException {
        if (this.assets == null || this.worldFinished) {
            throw Exceptions.newIllegalStateException("Cloak remaps require an active world frame.");
        }

        this.drawImage(imageIndex, frameIndex, playerColor, worldX, worldY, flipped, false, false, CLOAK_LIGHTING_TABLE, -1, true, false, false);
    }

    /**
     * Draws a player-colored image at the requested fixed opacity and restores the default vertex color.
     *
     * @param imageIndex  the image identifier
     * @param frameIndex  the image frame
     * @param playerColor the owner color slot
     * @param worldX      the horizontal world coordinate of the image center
     * @param worldY      the vertical world coordinate of the image center
     * @param flipped     whether to mirror the full canvas horizontally
     * @param opacity     the blend opacity, {@code 0} to {@code 1}
     * @throws IOException if the image is unavailable
     */
    public void drawTranslucentImage(int imageIndex, int frameIndex, int playerColor, float worldX, float worldY, boolean flipped, float opacity) throws IOException {
        this.entryColor = WHITE_RGB | Math.clamp(Math.round(opacity * MAXIMUM_ALPHA), 0, MAXIMUM_ALPHA);

        try {
            this.drawImage(imageIndex, frameIndex, playerColor, worldX, worldY, flipped);
        } finally {
            this.entryColor = OPAQUE_WHITE;
        }
    }

    /**
     * Draws the cloaked pass followed by player-colored pixels whose brightness exceeds the fade value.
     *
     * @param imageIndex  the image identifier
     * @param frameIndex  the image frame
     * @param playerColor the owner color slot
     * @param fadeValue   the image modifier data
     * @param worldX      the horizontal world coordinate of the image center
     * @param worldY      the vertical world coordinate of the image center
     * @param flipped     whether to mirror the full canvas horizontally
     * @throws IOException if the image is unavailable
     */
    public void drawCloakingImage(int imageIndex, int frameIndex, int playerColor, int fadeValue, float worldX, float worldY, boolean flipped) throws IOException {
        this.drawCloakedImage(imageIndex, frameIndex, playerColor, worldX, worldY, flipped);
        this.drawImage(imageIndex, frameIndex, playerColor, worldX, worldY, flipped, false, false, -1, fadeValue, false, false, false);
    }

    /**
     * Draws cloak refraction by sampling destination palette indices to the right of opaque frame pixels.
     *
     * @param imageIndex the image identifier
     * @param frameIndex the image frame
     * @param worldX     the horizontal world coordinate of the image center
     * @param worldY     the vertical world coordinate of the image center
     * @param flipped    whether to mirror the full canvas horizontally
     * @throws IOException           if the image is unavailable
     * @throws IllegalStateException if no world frame is active
     */
    public void drawUnseenCloakedImage(int imageIndex, int frameIndex, float worldX, float worldY, boolean flipped) throws IOException {
        if (this.assets == null || this.worldFinished) {
            throw Exceptions.newIllegalStateException("Cloak refraction requires an active world frame.");
        }

        this.drawImage(imageIndex, frameIndex, 0, worldX, worldY, flipped, false, false, -1, -1, false, false, true);
    }

    /**
     * Draws cloak refraction followed by player-colored pixels brighter than the fade value.
     *
     * @param imageIndex  the image identifier
     * @param frameIndex  the image frame
     * @param playerColor the owner color slot
     * @param fadeValue   the image modifier data
     * @param worldX      the horizontal world coordinate of the image center
     * @param worldY      the vertical world coordinate of the image center
     * @param flipped     whether to mirror the full canvas horizontally
     * @throws IOException if the image is unavailable
     */
    public void drawUnseenCloakingImage(int imageIndex, int frameIndex, int playerColor, int fadeValue, float worldX, float worldY, boolean flipped) throws IOException {
        this.drawUnseenCloakedImage(imageIndex, frameIndex, worldX, worldY, flipped);
        this.drawImage(imageIndex, frameIndex, playerColor, worldX, worldY, flipped, false, false, -1, fadeValue, false, false, false);
    }

    /**
     * Draws opaque frame pixels by sampling the destination to their right by the unscaled palette-index distance.
     *
     * @param imageIndex the image identifier
     * @param frameIndex the image frame
     * @param worldX     the horizontal world coordinate of the image center
     * @param worldY     the vertical world coordinate of the image center
     * @param flipped    whether to mirror the full canvas horizontally
     * @throws IOException           if the image is unavailable
     * @throws IllegalStateException if no world frame is active
     */
    public void drawDistortionImage(int imageIndex, int frameIndex, float worldX, float worldY, boolean flipped) throws IOException {
        if (this.assets == null || this.worldFinished) {
            throw Exceptions.newIllegalStateException("Distortion requires an active world frame.");
        }

        this.drawImage(imageIndex, frameIndex, 0, worldX, worldY, flipped, false, false, -1, -1, false, true, false);
    }

    /**
     * Draws a player-colored image with the hallucination tint and clears the tint mode afterward.
     *
     * @param imageIndex  the image identifier
     * @param frameIndex  the image frame
     * @param playerColor the owner color slot
     * @param worldX      the horizontal world coordinate of the image center
     * @param worldY      the vertical world coordinate of the image center
     * @param flipped     whether to mirror the full canvas horizontally
     * @throws IOException if the image is unavailable
     */
    public void drawHallucinatedImage(int imageIndex, int frameIndex, int playerColor, float worldX, float worldY, boolean flipped) throws IOException {
        this.hallucinating = true;

        try {
            this.drawImage(imageIndex, frameIndex, playerColor, worldX, worldY, flipped, false, false, -1, -1, false, false, false);
        } finally {
            this.hallucinating = false;
        }
    }

    /**
     * Draws a screen-space rectangle using packed {@code 0xRRGGBBAA} color.
     *
     * @param horizontal the left screen coordinate
     * @param vertical   the top screen coordinate
     * @param width      the screen width
     * @param height     the screen height
     * @param color      the packed color
     */
    public void fillRectangle(float horizontal, float vertical, float width, float height, int color) {
        this.setWorldClipped(false);
        this.selectTexture(this.whiteTexture, MODE_SOLID);
        this.markIndicesChanged(horizontal, vertical, horizontal + width, vertical + height);
        this.appendQuad(horizontal, vertical, horizontal + width, vertical + height, 0, 0, 1, 1, color, 0);
    }

    /**
     * Submits pending world quads before another renderer in this package draws into the world frame with its own OpenGL state.
     * {@link #endExternalWorldPass()} restores this renderer's state.
     *
     * @throws IllegalStateException if no world frame is active
     */
    public void beginExternalWorldPass() {
        if (this.worldFinished || this.worldFramebuffer == 0) {
            throw Exceptions.newIllegalStateException("External world drawing requires an active world frame.");
        }

        this.flush();
        this.setWorldClipped(false);
    }

    /**
     * Restores the world framebuffer, program, vertex array, blending and textures after an external world pass.
     */
    public void endExternalWorldPass() {
        glBindFramebuffer(GL_FRAMEBUFFER, this.worldFramebuffer);
        glViewport(0, 0, this.framebufferWidth, this.framebufferHeight);
        glDisable(GL_SCISSOR_TEST);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glColorMask(true, true, true, true);
        this.program.use();
        glBindVertexArray(this.vertexArray);
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, this.paletteTexture);
        // External passes use texture units 2 and 3 for their own targets; the classic effects expect the destination index copy on unit 2 and the
        // lighting tables on unit 3.
        glActiveTexture(GL_TEXTURE2);
        glBindTexture(GL_TEXTURE_2D, this.destinationIndexTexture);
        glActiveTexture(GL_TEXTURE3);
        glBindTexture(GL_TEXTURE_2D, this.lightingTexture);
        glActiveTexture(GL_TEXTURE0);

        this.worldClipped = false;
        this.batchTexture = 0;
        // The external pass may have written any index.
        this.allIndicesChangedStamp = ++this.indexStamp;
    }

    /**
     * Resolves the world shadow mask before screen-space overlays and selection markers.
     * <p>
     * Repeated calls have no effect. Callers draw all world images before this boundary.
     */
    public void finishWorld() {
        if (this.worldFinished) {
            return;
        }

        this.flush();
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glDisable(GL_SCISSOR_TEST);
        glDisable(GL_BLEND);
        this.worldClipped = false;

        // Restores the window viewport for screen-space overlays and interface drawing.
        this.framebufferWidth  = this.windowFramebufferWidth;
        this.framebufferHeight = this.windowFramebufferHeight;
        this.viewWidth         = this.windowViewWidth;
        this.viewHeight        = this.windowViewHeight;
        this.scale             = this.windowScale;
        glViewport(0, 0, this.framebufferWidth, this.framebufferHeight);
        glUniform2f(this.framebufferUniform, this.framebufferWidth, this.framebufferHeight);

        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, this.worldEffectTexture);
        glActiveTexture(GL_TEXTURE0);

        if (this.renderScaleLimit == CLASSIC_SCALE_LIMIT) {
            // SD graphics draw the interface into the 640x480 target too; the 2x compose and the present to the window follow it (presentSd).
            this.resolveClassicWorld();
            this.composeSd();
            this.sdPresentPending = true;

            if (!this.sdInterfaceDeferred) {
                this.presentSd();
            }
        } else {
            this.selectTexture(this.worldColorTexture, MODE_COMPOSITE);
            this.appendQuad(0, 0, this.framebufferWidth, this.framebufferHeight, 0, 1, 1, 0, OPAQUE_WHITE, 0);
            this.flush();
        }

        glEnable(GL_BLEND);
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, this.paletteTexture);
        glActiveTexture(GL_TEXTURE0);

        this.batchTexture  = 0;
        this.worldFinished = true;
    }

    /**
     * Binds the SD compose target for the caller's interface program before presentation.
     *
     * @return the target's framebuffer, bound with a viewport of {@link #getSdInterfaceWidth} by {@link #getSdInterfaceHeight}
     * @throws IllegalStateException if no SD present is pending
     */
    public int beginSdInterface() {
        if (!this.sdPresentPending) {
            throw Exceptions.newIllegalStateException("No SD frame waits for its interface.");
        }

        this.flush();
        glBindFramebuffer(GL_FRAMEBUFFER, this.sdFramebuffer);
        glViewport(0, 0, this.sdTargetWidth, this.sdTargetHeight);

        return this.sdFramebuffer;
    }

    /**
     * Begins an SD frame without a world (the menus with SD graphics): the SD compose target of the window's {@code 480}-line game size times
     * {@link #SD_COMPOSE_SCALE}, cleared to black, bound for the caller's drawing, and presented by {@link #presentSd} as a game frame is.
     *
     * @param windowWidth  the window framebuffer width
     * @param windowHeight the window framebuffer height
     * @return the target's framebuffer, bound with a viewport of {@link #getSdInterfaceWidth} by {@link #getSdInterfaceHeight}
     * @throws IllegalStateException if the renderer is closed or the window dimensions are invalid
     */
    public int beginSdFrame(int windowWidth, int windowHeight) {
        if (this.closed || windowWidth <= 0 || windowHeight <= 0) {
            throw Exceptions.newIllegalStateException("The renderer or frame dimensions are invalid.");
        }

        this.flush();

        this.framebufferWidth  = windowWidth;
        this.framebufferHeight = windowHeight;

        int width = Math.max(1, Math.round(windowWidth * SD_TARGET_HEIGHT / (float) windowHeight));

        this.bindSdCompose(width * SD_COMPOSE_SCALE, SD_TARGET_HEIGHT * SD_COMPOSE_SCALE);
        glDisable(GL_SCISSOR_TEST);
        glClearColor(0, 0, 0, 1);
        glClear(GL_COLOR_BUFFER_BIT);

        this.sdPresentPending = true;

        return this.sdFramebuffer;
    }

    /**
     * Presents a pending SD compose target with the selected filter and restores the window drawing state.
     */
    public void presentSd() {
        if (!this.sdPresentPending) {
            return;
        }

        this.flush();
        this.program.use();
        glBindVertexArray(this.vertexArray);
        glDisable(GL_SCISSOR_TEST);
        glDisable(GL_BLEND);
        glActiveTexture(GL_TEXTURE0);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glViewport(0, 0, this.framebufferWidth, this.framebufferHeight);
        glUniform2f(this.framebufferUniform, this.framebufferWidth, this.framebufferHeight);
        this.presentComposedClassicWorld();
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, this.paletteTexture);
        glActiveTexture(GL_TEXTURE0);

        this.batchTexture     = 0;
        this.sdPresentPending = false;
    }

    /**
     * Resolves unfinished world rendering, submits overlays and releases the clipping rectangle.
     */
    public void endFrame() {
        this.finishWorld();
        this.presentSd();
        this.flush();
        glDisable(GL_SCISSOR_TEST);
        glBindVertexArray(0);
        glUseProgram(0);
    }

    /**
     * Makes the destination index copy hold the world index target's current indices in a block of view cells, copying the block only when one of its
     * cells changed since its last copy.
     *
     * @param firstColumn the first view cell column
     * @param lastColumn  the last view cell column
     * @param firstRow    the first view cell row
     * @param lastRow     the last view cell row
     */
    private void copyDestinationCells(int firstColumn, int lastColumn, int firstRow, int lastRow) {
        if (firstColumn > lastColumn || firstRow > lastRow) {
            return;
        }

        int tileShift = INDEX_TILE_SHIFT - INDEX_CELL_SHIFT;
        int firstTileColumn = firstColumn >> tileShift;
        int lastTileColumn = Math.min(this.indexTileColumns - 1, lastColumn >> tileShift);
        int firstTileRow = firstRow >> tileShift;
        int lastTileRow = Math.min(this.indexTileRows - 1, lastRow >> tileShift);
        boolean changed = false;

        for (int row = firstTileRow; row <= lastTileRow && !changed; row++) {
            for (int column = firstTileColumn; column <= lastTileColumn; column++) {
                int tile = row * this.indexTileColumns + column;
                int copied = this.copiedIndexTiles[tile];

                if (this.changedIndexTiles[tile] > copied || this.allIndicesChangedStamp > copied) {
                    changed = true;

                    break;
                }
            }
        }

        if (!changed) {
            return;
        }

        this.submitVertices();

        // Whole tiles: a copy brings more of the destination up to date than the reads need, never less.
        int copyLeft = firstTileColumn << INDEX_TILE_SHIFT;
        int copyRight = Math.min(this.viewWidth, lastTileColumn + 1 << INDEX_TILE_SHIFT);
        int copyTop = firstTileRow << INDEX_TILE_SHIFT;
        int copyBottom = Math.min(this.viewHeight, lastTileRow + 1 << INDEX_TILE_SHIFT);

        glActiveTexture(GL_TEXTURE2);
        glBindTexture(GL_TEXTURE_2D, this.destinationIndexTexture);
        glReadBuffer(GL_COLOR_ATTACHMENT2);
        glCopyTexSubImage2D(GL_TEXTURE_2D,
                            0,
                            copyLeft,
                            this.framebufferHeight - copyBottom,
                            copyLeft,
                            this.framebufferHeight - copyBottom,
                            copyRight - copyLeft,
                            copyBottom - copyTop);
        glActiveTexture(GL_TEXTURE0);

        int stamp = ++this.indexStamp;

        for (int row = firstTileRow; row <= lastTileRow; row++) {
            Arrays.fill(this.copiedIndexTiles,
                        row * this.indexTileColumns + firstTileColumn,
                        row * this.indexTileColumns + lastTileColumn + 1,
                        stamp);
        }
    }

    /**
     * Records that a quad may write the world index target in a view rectangle ({@link #copyDestinationCells}).
     *
     * @param left   the left view coordinate
     * @param top    the top view coordinate
     * @param right  the right view coordinate
     * @param bottom the bottom view coordinate
     */
    private void markIndicesChanged(float left, float top, float right, float bottom) {
        this.markCellsChanged(this.toFirstCell(Math.min(left, right)),
                              this.toLastCell(Math.max(left, right), this.indexCellColumns),
                              this.toFirstCell(Math.min(top, bottom)),
                              this.toLastCell(Math.max(top, bottom), this.indexCellRows));
    }

    private void markCellsChanged(int firstColumn, int lastColumn, int firstRow, int lastRow) {
        if (firstColumn > lastColumn || firstRow > lastRow) {
            return;
        }

        int tileShift = INDEX_TILE_SHIFT - INDEX_CELL_SHIFT;
        int lastTileColumn = Math.min(this.indexTileColumns - 1, lastColumn >> tileShift);
        int stamp = ++this.indexStamp;

        for (int row = firstRow >> tileShift; row <= Math.min(this.indexTileRows - 1, lastRow >> tileShift); row++) {
            Arrays.fill(this.changedIndexTiles,
                        row * this.indexTileColumns + (firstColumn >> tileShift),
                        row * this.indexTileColumns + lastTileColumn + 1,
                        stamp);
        }
    }

    /**
     * @param start the view coordinate where the span starts
     * @return the view cell holding the first pixel of a span starting at a view coordinate, at least {@code 0}
     */
    private int toFirstCell(float start) {
        return Math.max(0, (int) Math.floor(start)) >> INDEX_CELL_SHIFT;
    }

    /**
     * @param end   the view coordinate where the span ends
     * @param cells the number of cells of the axis
     * @return the view cell holding the last pixel of a span ending at a view coordinate, at most {@code cells - 1}
     */
    private int toLastCell(float end, int cells) {
        return Math.min(cells - 1, ((int) Math.ceil(end) - 1) >> INDEX_CELL_SHIFT);
    }

    /**
     * Holds back an image quad of {@link #drawImage} and gives it its draw level ({@link #heldCount}).
     * <p>
     * Image draws change targets only at the frame's nonzero pixels (palette index {@code 0} is transparent or discarded), and those that read the
     * destination read only there, or right of them by the read extension (distortion and refraction).
     *
     * @param entry            the frame's atlas entry
     * @param indexed          the shader mode
     * @param uniform          the mode's effect value
     * @param readsDestination whether the draw reads the destination index copy
     * @param readExtension    the view pixels right of the nonzero pixels that the draw also reads
     * @param horizontal       the left view coordinate of the canvas
     * @param vertical         the top view coordinate of the canvas
     * @param paletteRow       the palette row of the vertices
     * @param flipped          whether the frame is mirrored horizontally
     */
    private void holdImageQuad(@NotNull AtlasEntry entry, int indexed, int uniform,
                               boolean readsDestination, float readExtension,
                               float horizontal, float vertical,
                               int paletteRow, boolean flipped) {
        float opaqueLeft = horizontal + (flipped ? entry.width - entry.opaqueRight : entry.opaqueLeft) * this.scale;
        float opaqueRight = horizontal + (flipped ? entry.width - entry.opaqueLeft : entry.opaqueRight) * this.scale;
        float opaqueTop = vertical + entry.opaqueTop * this.scale;
        float opaqueBottom = vertical + entry.opaqueBottom * this.scale;
        int writeFirstColumn = this.toFirstCell(opaqueLeft);
        int writeLastColumn = this.toLastCell(opaqueRight, this.indexCellColumns);
        int writeFirstRow = this.toFirstCell(opaqueTop);
        int writeLastRow = this.toLastCell(opaqueBottom, this.indexCellRows);
        int readLastColumn = readsDestination ? this.toLastCell(opaqueRight + readExtension, this.indexCellColumns) : -1;
        int epoch = this.levelEpoch << LEVEL_EPOCH_SHIFT;
        int level = 1;

        for (int row = writeFirstRow; row <= writeLastRow; row++) {
            for (int column = writeFirstColumn; column <= Math.max(writeLastColumn, readLastColumn); column++) {
                int cell = row * this.indexCellColumns + column;
                int written = this.writtenLevels[cell];
                int read = this.readLevels[cell];

                if ((written & LEVEL_EPOCH_MASK) == epoch) {
                    level = Math.max(level, (written & LEVEL_MASK) + 1);
                }

                if (column <= writeLastColumn && (read & LEVEL_EPOCH_MASK) == epoch) {
                    level = Math.max(level, read & LEVEL_MASK);
                }
            }
        }

        if (level > MAXIMUM_DRAW_LEVEL) {
            this.drawHeldImages();
            epoch = this.levelEpoch << LEVEL_EPOCH_SHIFT;
            level = 1;
        }

        for (int row = writeFirstRow; row <= writeLastRow; row++) {
            for (int column = writeFirstColumn; column <= writeLastColumn; column++) {
                this.writtenLevels[row * this.indexCellColumns + column] = epoch | level;
            }

            for (int column = writeFirstColumn; column <= readLastColumn; column++) {
                int cell = row * this.indexCellColumns + column;

                if ((this.readLevels[cell] & LEVEL_EPOCH_MASK) != epoch || (this.readLevels[cell] & LEVEL_MASK) < level) {
                    this.readLevels[cell] = epoch | level;
                }
            }
        }

        int index = this.heldCount;

        if (index == this.heldTextures.length) {
            int capacity = index * CAPACITY_GROWTH;

            this.heldTextures = Arrays.copyOf(this.heldTextures, capacity);
            this.heldModes    = Arrays.copyOf(this.heldModes, capacity);
            this.heldUniforms = Arrays.copyOf(this.heldUniforms, capacity);
            this.heldLevels   = Arrays.copyOf(this.heldLevels, capacity);
            this.heldCells    = Arrays.copyOf(this.heldCells, capacity * HELD_CELL_VALUES);
            this.heldVertices = Arrays.copyOf(this.heldVertices, capacity * QUAD_FLOATS);
        }

        int cells = index * HELD_CELL_VALUES;

        this.heldTextures[index]                   = entry.texture;
        this.heldModes[index]                      = indexed;
        this.heldUniforms[index]                   = uniform;
        this.heldLevels[index]                     = level;
        this.heldCells[cells + WRITE_FIRST_COLUMN] = writeFirstColumn;
        this.heldCells[cells + WRITE_LAST_COLUMN]  = writeLastColumn;
        this.heldCells[cells + WRITE_FIRST_ROW]    = writeFirstRow;
        this.heldCells[cells + WRITE_LAST_ROW]     = writeLastRow;
        this.heldCells[cells + READ_FIRST_COLUMN]  = writeFirstColumn;
        this.heldCells[cells + READ_LAST_COLUMN]   = readLastColumn;
        this.heldCells[cells + READ_FIRST_ROW]     = writeFirstRow;
        this.heldCells[cells + READ_LAST_ROW]      = writeLastRow;

        float left = (float) entry.horizontal / ATLAS_SIZE;
        float right = (float) (entry.horizontal + entry.width) / ATLAS_SIZE;
        float textureLeft = flipped ? right : left;
        float textureRight = flipped ? left : right;
        float textureTop = (float) entry.vertical / ATLAS_SIZE;
        float textureBottom = (float) (entry.vertical + entry.height) / ATLAS_SIZE;
        float quadRight = horizontal + entry.width * this.scale;
        float quadBottom = vertical + entry.height * this.scale;
        int color = this.entryColor;
        float red = (color >>> RGBA_RED_SHIFT) / OverlayPass.CHANNEL_MAXIMUM;
        float green = (color >>> RGBA_GREEN_SHIFT & CHANNEL_MASK) / OverlayPass.CHANNEL_MAXIMUM;
        float blue = (color >>> RGBA_BLUE_SHIFT & CHANNEL_MASK) / OverlayPass.CHANNEL_MAXIMUM;
        float alpha = (color & CHANNEL_MASK) / OverlayPass.CHANNEL_MAXIMUM * this.entryOpacity;
        float[] quad = this.heldVertices;
        int position = index * QUAD_FLOATS;

        position = putVertex(horizontal, vertical, textureLeft, textureTop, red, green, blue, alpha, paletteRow, quad, position);
        position = putVertex(quadRight, vertical, textureRight, textureTop, red, green, blue, alpha, paletteRow, quad, position);
        position = putVertex(quadRight, quadBottom, textureRight, textureBottom, red, green, blue, alpha, paletteRow, quad, position);
        position = putVertex(horizontal, vertical, textureLeft, textureTop, red, green, blue, alpha, paletteRow, quad, position);
        position = putVertex(quadRight, quadBottom, textureRight, textureBottom, red, green, blue, alpha, paletteRow, quad, position);
        putVertex(horizontal, quadBottom, textureLeft, textureBottom, red, green, blue, alpha, paletteRow, quad, position);

        this.heldCount = index + 1;
    }

    /**
     * Draws the held image quads level by level: before a level, one copy brings the destination indices of all its reads up to date; within a level,
     * quads of the same texture, mode and effect value share a draw call.
     */
    private void drawHeldImages() {
        int count = this.heldCount;

        this.heldCount = 0;

        int[] levels = this.heldLevels;
        int[] textures = this.heldTextures;
        int[] modes = this.heldModes;
        int[] uniforms = this.heldUniforms;
        int[] order = this.heldOrder.sort(count, levels, textures, modes, uniforms);
        int[] cells = this.heldCells;

        for (int start = 0; start < count; ) {
            int level = levels[order[start]];
            int end = start;
            int firstColumn = Integer.MAX_VALUE;
            int lastColumn = -1;
            int firstRow = Integer.MAX_VALUE;
            int lastRow = -1;

            for (; end < count && levels[order[end]] == level; end++) {
                int base = order[end] * HELD_CELL_VALUES;

                if (cells[base + READ_FIRST_COLUMN] <= cells[base + READ_LAST_COLUMN] &&
                    cells[base + READ_FIRST_ROW] <= cells[base + READ_LAST_ROW]) {
                    firstColumn = Math.min(firstColumn, cells[base + READ_FIRST_COLUMN]);
                    lastColumn  = Math.max(lastColumn, cells[base + READ_LAST_COLUMN]);
                    firstRow    = Math.min(firstRow, cells[base + READ_FIRST_ROW]);
                    lastRow     = Math.max(lastRow, cells[base + READ_LAST_ROW]);
                }
            }

            this.copyDestinationCells(firstColumn, lastColumn, firstRow, lastRow);

            for (int position = start; position < end; position++) {
                int index = order[position];
                int base = index * HELD_CELL_VALUES;

                this.setHeldEffect(modes[index], uniforms[index]);

                if (this.batchTexture != textures[index] || this.batchIndexed != modes[index]) {
                    this.submitVertices();
                    this.batchTexture = textures[index];
                    this.batchIndexed = modes[index];
                }

                if (this.vertices.remaining() < QUAD_FLOATS) {
                    this.submitVertices();
                }

                this.vertices.put(this.heldVertices, index * QUAD_FLOATS, QUAD_FLOATS);
                this.markCellsChanged(cells[base + WRITE_FIRST_COLUMN],
                                      cells[base + WRITE_LAST_COLUMN],
                                      cells[base + WRITE_FIRST_ROW],
                                      cells[base + WRITE_LAST_ROW]);
            }

            start = end;
        }

        this.advanceLevelEpoch();
    }

    /**
     * Sets the effect uniform of a held quad's mode, submitting the quads drawn with its previous value first.
     *
     * @param indexed the held quad's shader mode
     * @param uniform the held quad's effect value
     */
    private void setHeldEffect(int indexed, int uniform) {
        if (indexed == MODE_LIGHTING || indexed == MODE_PLAYER_LIGHTING) {
            int lightingRow = this.lightingRowOffsets[uniform];
            int lightingRows = this.lightingRowCounts[uniform];

            if (lightingRow != this.lightingRowValue || lightingRows != this.lightingRowsValue) {
                this.submitVertices();
                glUniform1i(this.lightingRowUniform, lightingRow);
                glUniform1i(this.lightingRowsUniform, lightingRows);
                this.lightingRowValue  = lightingRow;
                this.lightingRowsValue = lightingRows;
            }
        } else if (indexed == MODE_FADE && uniform != this.cloakValueValue) {
            this.submitVertices();
            glUniform1i(this.cloakValueUniform, uniform);
            this.cloakValueValue = uniform;
        } else if (indexed == MODE_DISTORTION && this.scale != this.distortionScaleValue) {
            this.submitVertices();
            glUniform1f(this.distortionScaleUniform, this.scale);
            this.distortionScaleValue = this.scale;
        }
    }

    private void advanceLevelEpoch() {
        this.levelEpoch++;

        if (this.levelEpoch > Short.MAX_VALUE) {
            this.levelEpoch = 1;
            Arrays.fill(this.writtenLevels, 0);
            Arrays.fill(this.readLevels, 0);
        }
    }

    /**
     * Runs the first step of the SD present: draws the {@code 640x480} target into the compose target with the first filter.
     */
    private void composeSd() {
        this.bindSdCompose(this.sdSourceWidth * SD_COMPOSE_SCALE, this.sdSourceHeight * SD_COMPOSE_SCALE);
        glDisable(GL_BLEND);
        glUniform2f(this.framebufferUniform, this.sdTargetWidth, this.sdTargetHeight);

        int filter = this.sdFilterMode >= SD_FILTER_LINEAR_COMPOSE ? GL_LINEAR : GL_NEAREST;

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, this.sdSourceTexture);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter);
        this.batchTexture = 0;
        this.selectTexture(this.sdSourceTexture, MODE_COPY);
        this.appendQuad(0, 0, this.sdTargetWidth, this.sdTargetHeight, 0, 1, 1, 0, OPAQUE_WHITE, 0);
        this.flush();
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glViewport(0, 0, this.framebufferWidth, this.framebufferHeight);
        glUniform2f(this.framebufferUniform, this.framebufferWidth, this.framebufferHeight);
    }

    private void bindSdCompose(int width, int height) {
        if (this.sdFramebuffer == 0) {
            this.sdFramebuffer = glGenFramebuffers();
            this.sdTexture     = glGenTextures();
        }

        glBindFramebuffer(GL_FRAMEBUFFER, this.sdFramebuffer);

        if (this.sdTargetWidth != width || this.sdTargetHeight != height) {
            glBindTexture(GL_TEXTURE_2D, this.sdTexture);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, this.sdTexture, 0);
            GraphicsDevice.getCurrent().selectColorTargets(SD_TARGET_COUNT);

            if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
                throw Exceptions.newIllegalStateException("The SD present framebuffer is incomplete.");
            }

            this.sdTargetWidth  = width;
            this.sdTargetHeight = height;
        }

        glViewport(0, 0, width, height);
    }

    /**
     * Resolves the classic world into the SD target: the world color with the shadow composite, one pixel per target pixel, for the interface to draw
     * over before {@link #presentSd}.
     */
    private void resolveClassicWorld() {
        this.bindSdSource(this.worldTargetWidth, this.worldTargetHeight);
        glUniform2f(this.framebufferUniform, this.sdSourceWidth, this.sdSourceHeight);
        this.selectTexture(this.worldColorTexture, MODE_COMPOSITE);
        this.appendQuad(0, 0, this.sdSourceWidth, this.sdSourceHeight, 0, 1, 1, 0, OPAQUE_WHITE, 0);
        this.flush();
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glViewport(0, 0, this.framebufferWidth, this.framebufferHeight);
        glUniform2f(this.framebufferUniform, this.framebufferWidth, this.framebufferHeight);
    }

    private void bindSdSource(int width, int height) {
        if (this.sdSourceFramebuffer == 0) {
            this.sdSourceFramebuffer = glGenFramebuffers();
            this.sdSourceTexture     = glGenTextures();
        }

        glBindFramebuffer(GL_FRAMEBUFFER, this.sdSourceFramebuffer);

        if (this.sdSourceWidth != width || this.sdSourceHeight != height) {
            glBindTexture(GL_TEXTURE_2D, this.sdSourceTexture);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, this.sdSourceTexture, 0);
            GraphicsDevice.getCurrent().selectColorTargets(SD_TARGET_COUNT);

            if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
                throw Exceptions.newIllegalStateException("The SD target framebuffer is incomplete.");
            }

            this.sdSourceWidth  = width;
            this.sdSourceHeight = height;
        }

        glViewport(0, 0, width, height);
    }

    /**
     * Runs the second step of the SD present: draws the {@code 2x} target into the window with the second filter, or bicubically in mode {@code 3}.
     */
    private void presentComposedClassicWorld() {
        int filter = this.sdFilterMode >= SD_FILTER_LINEAR_PRESENT ? GL_LINEAR : GL_NEAREST;

        glBindTexture(GL_TEXTURE_2D, this.sdTexture);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter);
        this.batchTexture = 0;
        this.selectTexture(this.sdTexture, this.sdFilterMode == SD_FILTER_BICUBIC ? MODE_BICUBIC : MODE_COPY);
        this.appendQuad(0, 0, this.framebufferWidth, this.framebufferHeight, 0, 1, 1, 0, OPAQUE_WHITE, 0);
        this.flush();
    }

    /**
     * Returns the atlas entry a terrain tile draws: the entry of its megatile's replacing picture, uploaded on first use and replaced in place when
     * the picture array changes, or the installed tile's entry.
     *
     * @param megatiles the picture of a megatile index, or {@code null} for the installed picture
     * @param megatile  the megatile index of the tile
     * @param installed the atlas entry of the installed tile
     * @return the atlas entry to draw
     * @throws IOException if the picture is not {@code 32x32} palette indices
     */
    private @NotNull AtlasEntry findMegatileEntry(@NotNull IntFunction<byte @Nullable []> megatiles, int megatile, @NotNull AtlasEntry installed) throws IOException {
        byte[] picture = megatiles.apply(megatile);

        if (picture == null) {
            return installed;
        }

        if (picture.length != TerrainTileset.TILE_SIZE * TerrainTileset.TILE_SIZE) {
            throw Exceptions.newIOException("A megatile picture has {0} pixels.", picture.length);
        }

        AtlasEntry entry = this.megatileEntries.get(megatile);

        if (entry == null) {
            this.flush();
            entry = this.uploadFrame(picture, TerrainTileset.TILE_SIZE, TerrainTileset.TILE_SIZE);
            this.megatileEntries.put(megatile, entry);
            this.megatilePictures.put(megatile, picture);
        } else if (this.megatilePictures.get(megatile) != picture) {
            this.flush();
            this.replaceFrame(entry, picture);
            this.megatilePictures.put(megatile, picture);
        }

        return entry;
    }

    /**
     * Replaces the texels of an atlas entry with pixels of its size.
     *
     * @param entry  the atlas entry
     * @param pixels the row-major palette indices
     */
    private void replaceFrame(@NotNull AtlasEntry entry, byte @NotNull [] pixels) {
        ByteBuffer upload = MemoryUtil.memAlloc(pixels.length);

        try {
            upload.put(pixels).flip();
            glBindTexture(GL_TEXTURE_2D, entry.texture);
            glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
            glTexSubImage2D(GL_TEXTURE_2D, 0, entry.horizontal, entry.vertical, entry.width, entry.height, GL_RED, GL_UNSIGNED_BYTE, upload);
        } finally {
            MemoryUtil.memFree(upload);
        }

        this.batchTexture = 0;
    }

    private @NotNull AtlasEntry uploadFrame(byte @NotNull [] pixels, int width, int height) throws IOException {
        if (width <= 0 || height <= 0 || width > ATLAS_SIZE || height > ATLAS_SIZE) {
            throw Exceptions.newIOException("Sprite dimensions exceed the texture atlas: {0} by {1}", width, height);
        }

        if (this.atlasHorizontal + width > ATLAS_SIZE) {
            this.atlasHorizontal = 0;
            this.atlasVertical += this.atlasRowHeight;
            this.atlasRowHeight  = 0;
        }

        if (this.atlasTextures.isEmpty() || this.atlasVertical + height > ATLAS_SIZE) {
            int texture = glGenTextures();

            this.atlasTextures.add(texture);
            glBindTexture(GL_TEXTURE_2D, texture);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_R8, ATLAS_SIZE, ATLAS_SIZE, 0, GL_RED, GL_UNSIGNED_BYTE, (ByteBuffer) null);
            this.atlasHorizontal = 0;
            this.atlasVertical   = 0;
            this.atlasRowHeight  = 0;
        }

        int texture = this.atlasTextures.getLast();
        AtlasEntry entry = createEntry(texture, this.atlasHorizontal, this.atlasVertical, pixels, width, height);
        ByteBuffer upload = MemoryUtil.memAlloc(pixels.length);

        try {
            upload.put(pixels).flip();
            glBindTexture(GL_TEXTURE_2D, texture);
            glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
            glTexSubImage2D(GL_TEXTURE_2D, 0, entry.horizontal, entry.vertical, width, height, GL_RED, GL_UNSIGNED_BYTE, upload);
        } finally {
            MemoryUtil.memFree(upload);
        }

        this.atlasHorizontal += width;
        this.atlasRowHeight = Math.max(this.atlasRowHeight, height);

        return entry;
    }

    private void setWorldClipped(boolean clipped) {
        if (this.worldClipped == clipped) {
            return;
        }

        this.flush();
        this.worldClipped = clipped;

        if (clipped) {
            glEnable(GL_SCISSOR_TEST);
            glScissor(0, this.framebufferHeight - this.viewHeight, this.viewWidth, this.viewHeight);
        } else {
            glDisable(GL_SCISSOR_TEST);
        }
    }

    private void selectTexture(int texture, int indexed) {
        if (this.heldCount > 0) {
            this.drawHeldImages();
        }

        if (this.batchTexture == texture && this.batchIndexed == indexed) {
            return;
        }

        this.flush();
        this.batchTexture = texture;
        this.batchIndexed = indexed;
    }

    private void appendQuad(float left, float top, float right, float bottom,
                            float textureLeft, float textureTop, float textureRight, float textureBottom,
                            int color, int paletteRow) {
        if (this.vertices.remaining() < QUAD_FLOATS) {
            this.flush();
        }

        float red = (color >>> RGBA_RED_SHIFT) / OverlayPass.CHANNEL_MAXIMUM;
        float green = (color >>> RGBA_GREEN_SHIFT & CHANNEL_MASK) / OverlayPass.CHANNEL_MAXIMUM;
        float blue = (color >>> RGBA_BLUE_SHIFT & CHANNEL_MASK) / OverlayPass.CHANNEL_MAXIMUM;
        float alpha = (color & CHANNEL_MASK) / OverlayPass.CHANNEL_MAXIMUM;

        this.appendVertex(left, top, textureLeft, textureTop, red, green, blue, alpha, paletteRow);
        this.appendVertex(right, top, textureRight, textureTop, red, green, blue, alpha, paletteRow);
        this.appendVertex(right, bottom, textureRight, textureBottom, red, green, blue, alpha, paletteRow);
        this.appendVertex(left, top, textureLeft, textureTop, red, green, blue, alpha, paletteRow);
        this.appendVertex(right, bottom, textureRight, textureBottom, red, green, blue, alpha, paletteRow);
        this.appendVertex(left, bottom, textureLeft, textureBottom, red, green, blue, alpha, paletteRow);
    }

    private void appendVertex(float horizontal, float vertical,
                              float textureHorizontal, float textureVertical,
                              float red, float green, float blue, float alpha,
                              int paletteRow) {
        this.vertices.put(horizontal).put(vertical)
                     .put(textureHorizontal).put(textureVertical)
                     .put(red).put(green).put(blue).put(alpha)
                     .put(paletteRow);
    }

    /**
     * Submits everything drawn so far: the held image quads, then the pending vertices.
     */
    private void flush() {
        if (this.heldCount > 0) {
            this.drawHeldImages();
        }

        this.submitVertices();
    }

    private void submitVertices() {
        if (this.vertices.position() == 0) {
            return;
        }

        this.vertices.flip();
        glBindTexture(GL_TEXTURE_2D, this.batchTexture);
        glUniform1i(this.indexedUniform, this.batchIndexed);
        glBindBuffer(GL_ARRAY_BUFFER, this.vertexBuffer);
        glBufferSubData(GL_ARRAY_BUFFER, 0, this.vertices);
        glDrawArrays(GL_TRIANGLES, 0, this.vertices.remaining() / VERTEX_FLOATS);
        this.vertices.clear();
    }

    private void clearMap() {
        for (AtlasEntry entry : this.warpFrames.reversed().values()) {
            IgnoreException.execute(
                    () -> glDeleteTextures(entry.texture)
            );
        }

        this.warpFrames.clear();
        this.coverageMasks.clear();
        this.sheetEntries.clear();
        this.sheetCoverageMasks.clear();
        this.releasedSheetPlaces.clear();

        for (int index = this.atlasTextures.size() - 1; index >= 0; index--) {
            int texture = this.atlasTextures.get(index);

            IgnoreException.execute(
                    () -> glDeleteTextures(texture)
            );
        }

        this.atlasTextures.clear();

        IgnoreException.execute(
                () -> glDeleteTextures(this.lightingTexture),
                () -> glDeleteTextures(this.paletteTexture)
        );
        this.lightingTexture = 0;
        this.paletteTexture  = 0;

        Arrays.fill(this.spriteEntries, null);
        this.mapTiles         = null;
        this.assets           = null;
        this.creepEdgeEntries = null;
        Arrays.fill(this.terrainEntries, null);
        this.megatileEntries.clear();
        this.megatilePictures.clear();

        this.batchTexture    = 0;
        this.atlasRowHeight  = 0;
        this.atlasVertical   = 0;
        this.atlasHorizontal = 0;
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
     */
    @Override
    public void close() {
        if (this.closed) {
            return;
        }

        this.closed = true;

        IgnoreException.execute(
                () -> glDeleteTextures(this.sdTexture),
                () -> glDeleteFramebuffers(this.sdFramebuffer),
                () -> glDeleteTextures(this.sdSourceTexture),
                () -> glDeleteFramebuffers(this.sdSourceFramebuffer),
                () -> glDeleteTextures(this.destinationIndexTexture),
                () -> glDeleteTextures(this.worldIndexTexture),
                () -> glDeleteTextures(this.worldEffectTexture),
                () -> glDeleteTextures(this.worldColorTexture),
                () -> glDeleteFramebuffers(this.worldFramebuffer),
                this::clearMap,
                () -> glDeleteTextures(this.whiteTexture),
                () -> MemoryUtil.memFree(this.vertices),
                () -> glDeleteBuffers(this.vertexBuffer),
                () -> glDeleteVertexArrays(this.vertexArray),
                () -> this.program.close()
        );
    }
}
