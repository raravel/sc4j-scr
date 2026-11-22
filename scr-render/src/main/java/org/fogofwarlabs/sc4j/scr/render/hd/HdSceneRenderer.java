package org.fogofwarlabs.sc4j.scr.render.hd;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.exception.IgnoreException;
import org.fogofwarlabs.sc4j.base.log.Logger;
import org.fogofwarlabs.sc4j.engine.client.render.GraphicsDevice;
import org.fogofwarlabs.sc4j.engine.client.render.ShaderProgram;
import org.fogofwarlabs.sc4j.engine.resource.graphics.SpriteFrame;
import org.fogofwarlabs.sc4j.engine.resource.graphics.SpriteSheet;
import org.fogofwarlabs.sc4j.engine.resource.graphics.TextureSheet;
import org.fogofwarlabs.sc4j.scr.data.format.image.AnimFile;
import org.fogofwarlabs.sc4j.scr.data.format.image.AnimLights;
import org.fogofwarlabs.sc4j.scr.data.format.image.DdsFile;
import org.fogofwarlabs.sc4j.scr.data.format.image.DdsGrpFile;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.fogofwarlabs.sc4j.scr.data.resource.ScrImageSheet;
import org.fogofwarlabs.sc4j.scr.render.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.*;
import java.util.function.IntFunction;
import java.util.function.IntUnaryOperator;

import static org.lwjgl.opengl.GL33C.*;

/**
 * Draws HD or HD2 terrain, images and overlays into color and effect targets, then resolves them into the world frame.
 * <p>
 * The optional deferred path adds lighting and bloom; terrain effects are drawn over masked terrain. Anim textures come from a shared or owned
 * {@link HdImageSet}. Preloading completes the image set and uploads map megatiles; otherwise images can stream and megatiles upload on first use.
 */
public final class HdSceneRenderer implements SceneRenderer {
    /**
     * The names of the lighting debug views of {@link #setLightingDebugView(int)}.
     */
    public static final @NotNull String @NotNull [] LIGHTING_DEBUG_VIEWS = {
            "off", "lights", "lighting", "albedo", "normals", "ao", "emissive", "amount"
    };

    /**
     * The anim frame flag of frames placed by their offset in the anim canvas. Frames without it are SD frames scaled by {@code 4} whose stored
     * offset is the GRP offset; they use the image's classic map position.
     */
    static final int FRAME_CANVAS_PLACED = 1;

    private static final @NotNull Logger LOGGER = Logger.of(HdSceneRenderer.class);
    /**
     * The renderer name of the shader failure messages.
     */
    private static final @NotNull String SHADER_PROGRAM_NAME = "HD renderer";

    /**
     * The vertex attribute sizes: position, texture coordinates, map coordinates, team color, parameter, layer, flip sign and lighting amount.
     */
    private static final int @NotNull [] ATTRIBUTE_SIZES = {2, 2, 2, 3, 1, 1, 1, 1};
    /**
     * The floats of one vertex, the sum of {@link #ATTRIBUTE_SIZES}.
     */
    private static final int VERTEX_FLOATS = 13;
    /**
     * The scene color outside the map: black. Its alpha is the tile mask that the terrain pass writes and the water/heat effect reads
     * ({@link HdTerrainEffect}), so it is {@code 0}: no water beyond the map.
     */
    private static final float @NotNull [] CLEAR_COLOR = {0, 0, 0, 0};
    private static final float @NotNull [] CLEAR_EFFECT = {0, 0, 0, 0};
    private static final int MAXIMUM_VERTICES = 24576;
    /**
     * The vertex ring size in batches: each flush writes behind the previous one and the buffer is orphaned only when it wraps, so a flush never
     * overwrites vertices the GPU may still read (an implicit synchronization per draw call).
     */
    private static final int VERTEX_RING_BATCHES = 16;
    private static final int ARRAY_LAYERS = 512;
    /**
     * The position of the texture array in a megatile slot ({@code array << 16 | layer}).
     */
    private static final int SLOT_ARRAY_SHIFT = 16;
    /**
     * The mask of the texture array layer in a megatile slot.
     */
    private static final int SLOT_LAYER_MASK = (1 << SLOT_ARRAY_SHIFT) - 1;
    /**
     * The draw buffers of the scene framebuffer without the deferred G-buffer: color and effect.
     */
    private static final int SCENE_TARGET_COUNT = WorldRenderer.EFFECT_TARGET + 1;

    /**
     * The texture units of the sprite program's samplers; the lighting layers follow {@link #FIRST_LIGHTING_UNIT}.
     */
    private static final int SPRITE_UNIT = 0;
    private static final int TEAM_COLOR_UNIT = 1;
    private static final int TILE_UNIT = 2;
    private static final int FIRST_LIGHTING_UNIT = 3;
    private static final int MASK_UNIT = 7;

    /**
     * The upload time after which a streaming frame defers further new images to the next frame.
     */
    private static final long UPLOAD_BUDGET_NANOSECONDS = 3_000_000;
    private static final long NANOSECONDS_PER_MILLISECOND = 1_000_000L;
    /**
     * Prints draw-call statistics every {@code 600} frames when the system property {@code sc4j.hdStatistics} is {@code true}.
     */
    private static final boolean STATISTICS = Boolean.getBoolean("sc4j.hdStatistics");
    private static final int STATISTICS_INTERVAL = 600;

    private static final int MODE_TILE = 0;
    private static final int MODE_SPRITE = 1;
    private static final int MODE_PART_SOLID = 2;
    private static final int MODE_SHADOW = 3;
    private static final int MODE_CLOAKED = 4;
    private static final int MODE_WARPED = 5;
    private static final int MODE_EDGE = 7;
    private static final int MODE_MAPPED = 8;
    private static final int MODE_BRIGHTEN = 9;
    private static final int MODE_SOLID = 10;
    private static final int MODE_TINT = 12;
    private static final int MODE_GHOST = 13;
    /**
     * A lit, team-colored sprite whose alpha is multiplied by the parameter.
     */
    private static final int MODE_TRANSLUCENT = 14;
    /**
     * A shadow drawn as black at the parameter's alpha when shadow stacking is enabled.
     */
    private static final int MODE_STACKED_SHADOW = 15;
    /**
     * An opaque terrain tile with a terrain effect: the color target white, the effect target the tile color with the water mask in alpha, which the
     * water or heat pass reads.
     */
    private static final int MODE_TILE_EFFECT = 16;
    /**
     * A terrain tile without a mask before the terrain effect: color and effect.
     */
    private static final int MODE_TILE_LAND = 17;
    /**
     * A creep edge before the terrain effect: the color and effect targets blended by the edge alpha, and the water mask in the effect alpha scaled
     * by one minus the edge alpha, so the water or heat pass leaves the creep still, like the creep tiles drawn after it.
     */
    private static final int MODE_EDGE_EFFECT = 18;
    /**
     * The number of shader modes, for the per-mode draw-call statistics.
     */
    private static final int MODE_COUNT = MODE_EDGE_EFFECT + 1;
    private static final int TILES_ALL = 0;
    private static final int TILES_UNMASKED = 1;
    private static final int TILES_MASKED = 2;
    private static final int TILES_CREEP = 3;

    /**
     * The solid-color alpha of stacked shadows.
     */
    private static final float STACKED_SHADOW_ALPHA = 0.5f;
    /**
     * The palette selections of an override sheet's textures: the static player colors and the live player color rows.
     */
    private static final int SHEET_PALETTE_SELECTIONS = PLAYER_COLOR + PLAYER_COLOR_ROWS;
    /**
     * The number of override sheet frames whose textures are kept; the frame drawn least recently releases its textures for the next one.
     */
    private static final int MAXIMUM_SHEET_FRAMES = 256;
    /**
     * The multiply-color alpha of visible cloaked images.
     */
    private static final float VISIBLE_CLOAK_ALPHA = 0.5f;
    /**
     * The divisor of the modifier data that gives the brightness threshold of the cloaking and decloaking pass.
     */
    private static final float CLOAK_THRESHOLD_DIVISOR = 8.0f;
    /**
     * The default lighting layers of a frame without lighting data ({@code 1x1} textures): emissive transparent black, normal {@code 0x80000080}
     * (flat: x and y {@code 0.5}), specular {@code 0xFF000000} (black) and AO/depth {@code 0x00FFFFFF} (AO {@code 1}, depth {@code 0}), as RGBA8
     * DWORDs.
     */
    private static final int @NotNull [] DEFAULT_LIGHTING_COLORS = {0, 0x80000080, 0xFF000000, 0x00FFFFFF};
    /**
     * The frame units per game pixel ({@link HdFrameGeometry#UNIT_SCALE}) as a float divisor.
     */
    private static final float FRAME_UNIT_SCALE = HdFrameGeometry.UNIT_SCALE;
    /**
     * The divisor of {@code main.lit} light intensities.
     */
    private static final float LIGHT_INTENSITY_DIVISOR = 100.0f;
    /**
     * The anim layers bound to texture units {@code 3} through {@code 6} in the deferred path.
     */
    private static final @NotNull String @NotNull [] LIGHTING_LAYERS = HdImageLoader.LIGHTING_LAYERS;

    /**
     * The scale of an HD2 foliage anim, whose frame coordinates are doubled to scale-{@code 4} pixels.
     */
    private static final int HALF_SCALE_ANIMATION = 2;
    /**
     * The factor that brings the frame coordinates of an HD2 foliage anim to scale-{@code 4} pixels.
     */
    private static final int HALF_SCALE_COORDINATE_FACTOR = 2;
    /**
     * The divisor of the foliage rectangle: the quad builder's rectangle divisor at every render scale.
     */
    private static final int FOLIAGE_RECTANGLE_DIVISOR = 8;
    /**
     * The multiplier of the placement scale in the foliage rectangle, which is computed at twice its size over {@link #FOLIAGE_RECTANGLE_DIVISOR}.
     */
    private static final int FOLIAGE_SCALE_MULTIPLIER = 2;
    /**
     * The anim layer of the foliage diffuse texture.
     */
    private static final int FOLIAGE_DIFFUSE_LAYER = 0;
    /**
     * The values of one foliage texture size: width, then height.
     */
    private static final int FOLIAGE_SIZE_VALUES = 2;
    /**
     * The values of one resource footprint of {@link #drawFoliage}: column, row, columns, rows.
     */
    private static final int RESOURCE_VALUES = 4;
    private static final int RESOURCE_COLUMN = 0;
    private static final int RESOURCE_ROW = 1;
    private static final int RESOURCE_COLUMNS = 2;
    private static final int RESOURCE_ROWS = 3;

    /**
     * The values of a frame rectangle of {@link SceneRenderer#getFrameRectangle}: left, top, width, height.
     */
    private static final int RECTANGLE_LEFT = 0;
    private static final int RECTANGLE_TOP = 1;
    private static final int RECTANGLE_WIDTH = 2;
    private static final int RECTANGLE_HEIGHT = 3;
    /**
     * The outline width of an update rectangle in game pixels.
     */
    private static final int OUTLINE_WIDTH = 1;
    /**
     * The top and bottom outline rows of an update rectangle, which its vertical sides leave out.
     */
    private static final int OUTLINE_ROWS = 2;

    /**
     * The values of an image drawn after the fog: image, frame, modifier, data, color, x, y and flipped.
     */
    private static final int POST_FOG_IMAGE = 0;
    private static final int POST_FOG_FRAME = 1;
    private static final int POST_FOG_MODIFIER = 2;
    private static final int POST_FOG_DATA = 3;
    private static final int POST_FOG_COLOR = 4;
    private static final int POST_FOG_X = 5;
    private static final int POST_FOG_Y = 6;
    private static final int POST_FOG_FLIPPED = 7;

    /**
     * The masks of a byte, a word and an unsigned {@code int} value.
     */
    private static final int BYTE_MASK = 0xFF;
    private static final int WORD_MASK = 0xFFFF;
    private static final long DWORD_MASK = 0xFFFFFFFFL;
    /**
     * The shift of the vertical offset in a packed offset pair (the horizontal offset in the low {@code short}).
     */
    private static final int PACKED_OFFSET_Y_SHIFT = 16;
    /**
     * The shift of the left edge in a packed frame rectangle origin (the top edge in the low {@code int}).
     */
    private static final int ORIGIN_LEFT_SHIFT = 32;
    /**
     * The result of {@link #findPlacementOffset} without an override sheet frame.
     */
    private static final long NO_PLACEMENT = -1L;

    private static final @NotNull String VERTEX_SHADER = """
                                                         #version 330 core
                                                         layout(location = 0) in vec2 position;
                                                         layout(location = 1) in vec2 coordinates;
                                                         layout(location = 2) in vec2 mapCoordinates;
                                                         layout(location = 3) in vec3 color;
                                                         layout(location = 4) in float parameter;
                                                         layout(location = 5) in float layer;
                                                         layout(location = 6) in float flip;
                                                         layout(location = 7) in float lighting;
                                                         uniform vec2 framebufferSize;
                                                         out vec2 texCoord;
                                                         out vec2 mapCoord;
                                                         flat out vec3 teamColor;
                                                         flat out float shaderParameter;
                                                         flat out float textureLayer;
                                                         flat out float flipSign;
                                                         flat out float lightingAmount;
                                                         void main() {
                                                             gl_Position = vec4(position.x * 2.0 / framebufferSize.x - 1.0,
                                                                                1.0 - position.y * 2.0 / framebufferSize.y, 0.0, 1.0);
                                                             texCoord = coordinates;
                                                             mapCoord = mapCoordinates;
                                                             teamColor = color;
                                                             shaderParameter = parameter;
                                                             textureLayer = layer;
                                                             flipSign = flip;
                                                             lightingAmount = lighting;
                                                         }
                                                         """;
    private static final @NotNull String FRAGMENT_SHADER = """
                                                           #version 330 core
                                                           in vec2 texCoord;
                                                           in vec2 mapCoord;
                                                           flat in vec3 teamColor;
                                                           flat in float shaderParameter;
                                                           flat in float textureLayer;
                                                           flat in float flipSign;
                                                           flat in float lightingAmount;
                                                           uniform sampler2D spriteTex;
                                                           uniform sampler2D teamcolorTex;
                                                           uniform sampler2DArray tileTex;
                                                           uniform sampler2D emissiveTex;
                                                           uniform sampler2D normalTex;
                                                           uniform sampler2D specularTex;
                                                           uniform sampler2D ao_depthTex;
                                                           uniform sampler2DArray maskTex;
                                                           uniform int mode;
                                                           uniform int deferred;
                                                           layout(location = 0) out vec4 frag_color;
                                                           layout(location = 1) out vec4 frag_effect;
                                                           layout(location = 2) out vec4 frag_emissive;
                                                           layout(location = 3) out vec4 frag_specular;
                                                           layout(location = 4) out vec4 frag_normal;
                                                           layout(location = 5) out vec4 frag_ao_d_l;
                                                           const vec3 BRIGHTNESS = vec3(0.30196078, 0.59215686, 0.10980392);
                                                           void shade();
                                                           void main() {
                                                               shade();
                                                               frag_emissive = vec4(0.0);
                                                               frag_specular = vec4(0.0);
                                                               frag_normal = vec4(0.0);
                                                               frag_ao_d_l = vec4(0.0);
                                                               if (deferred == 0)
                                                                   return;
                                                               // Terrain is lit as a flat surface (normal up, AO 1, lighting 1): deferred_blit.glsl applies
                                                               // ApplyEffect_Shadow only to the lit color, and SC:R shows unit shadows and the directional
                                                               // lambert (0.92) on terrain.
                                                               if (mode == 0 || mode == 16 || mode == 17) {
                                                                   frag_normal = vec4(0.5, 0.5, 0.0, 1.0);
                                                                   frag_ao_d_l = vec4(1.0, 0.0, 1.0, 1.0);
                                                               } else if (mode == 1 || mode == 2 || mode == 14) {
                                                                   float a = frag_color.a;
                                                                   if (a == 0.0) discard;
                                                                   if (a >= 0.1) {
                                                                       frag_specular = vec4(texture(specularTex, texCoord).rgb, a);
                                                                       frag_emissive = vec4(texture(emissiveTex, texCoord).rgb, a);
                                                                       vec4 aoDepth = texture(ao_depthTex, texCoord);
                                                                       vec4 normalSample = texture(normalTex, texCoord);
                                                                       frag_ao_d_l = vec4(aoDepth.g, aoDepth.a, lightingAmount, a);
                                                                       vec3 normal = vec3(normalSample.x, normalSample.a, 0.0) * 2.0 - 1.0;
                                                                       normal.x *= flipSign;
                                                                       frag_normal = vec4((normal.xy + 1.0) / 2.0, 0.0, a);
                                                                   }
                                                               } else if (mode == 10 || mode == 12 || mode == 13) {
                                                                   frag_ao_d_l = vec4(0.0, 0.0, 0.0, mode == 10 ? 1.0 : frag_effect.a);
                                                               } else if (mode == 9) {
                                                                   // sprite_brighten_frag DEFERRED (pass 0x25): white into the emissive target at half alpha.
                                                                   frag_emissive = vec4(vec3(1.0), frag_color.a * 0.5);
                                                               }
                                                           }
                                                           void shade() {
                                                               if (mode == 0) {
                                                                   float waterMask = mapCoord.x > 0.5 ? texture(maskTex, vec3(texCoord, mapCoord.x - 1.0)).r : 0.0;
                                                                   frag_color = vec4(texture(tileTex, vec3(texCoord, textureLayer)).rgb, waterMask);
                                                                   frag_effect = vec4(vec3(0.0), 1.0);
                                                                   return;
                                                               }
                                                               if (mode == 17) {
                                                                   // sprite_tile.glsl DRAW_EFFECT=1: the tile into the color and effect targets (opaque tiles keep alpha 1).
                                                                   vec4 tile = vec4(texture(tileTex, vec3(texCoord, textureLayer)).rgb, 1.0);
                                                                   frag_color = tile;
                                                                   frag_effect = tile;
                                                                   return;
                                                               }
                                                               if (mode == 16) {
                                                                   // effect_mask.glsl: color target white, effect target the tile color with the water mask in alpha.
                                                                   float waterMask = mapCoord.x > 0.5 ? texture(maskTex, vec3(texCoord, mapCoord.x - 1.0)).r : 0.0;
                                                                   frag_color = vec4(1.0);
                                                                   frag_effect = vec4(texture(tileTex, vec3(texCoord, textureLayer)).rgb, waterMask);
                                                                   return;
                                                               }
                                                               vec4 spriteColor = texture(spriteTex, texCoord);
                                                               if (mode == 7) {
                                                                   if (spriteColor.a == 0.0) discard;
                                                                   frag_color = spriteColor;
                                                                   frag_effect = vec4(vec3(0.0), spriteColor.a);
                                                               } else if (mode == 18) {
                                                                   // The edge color into both targets; the alpha blend clears the water mask under the edge.
                                                                   if (spriteColor.a == 0.0) discard;
                                                                   frag_color = spriteColor;
                                                                   frag_effect = spriteColor;
                                                               } else if (mode == 1 || mode == 2 || mode == 14) {
                                                                   if (mode == 2 && dot(BRIGHTNESS, spriteColor.rgb) < shaderParameter) discard;
                                                                   float maskValue = texture(teamcolorTex, texCoord).r;
                                                                   spriteColor.rgb = mix(spriteColor.rgb, spriteColor.rgb * teamColor, maskValue);
                                                                   if (mode == 14)
                                                                       spriteColor.a *= shaderParameter;
                                                                   frag_effect = vec4(vec3(0.0), spriteColor.a);
                                                                   if (mode == 1) {
                                                                       float brightness = dot(spriteColor.rgb, BRIGHTNESS);
                                                                       spriteColor.rgb = mix(spriteColor.rgb, vec3(0.75, 1.125, 2.65) * brightness, shaderParameter);
                                                                   }
                                                                   frag_color = spriteColor;
                                                               } else if (mode == 3) {
                                                                   // sprite_effect.glsl SPRITE_SHADOW times the multiply alpha.
                                                                   frag_color = vec4(0.0);
                                                                   frag_effect = vec4(0.0, 1.0, 0.0, spriteColor.a * shaderParameter);
                                                               } else if (mode == 15) {
                                                                   // sprite_solid_frag.glsl: solid color (0, 0, 0, shaderParameter), multiply color alpha 0
                                                                   // (the effect and G-buffer targets are left as they are).
                                                                   if (spriteColor.a == 0.0) discard;
                                                                   frag_color = vec4(0.0, 0.0, 0.0, spriteColor.a * shaderParameter);
                                                                   frag_effect = vec4(0.0);
                                                               } else if (mode == 4) {
                                                                   frag_color = vec4(0.0);
                                                                   // sprite_effect.glsl: SPRITE_CLOAKED (pass 0x13) writes the brightness; the lit path uses SPRITE_DEFERRED_CLOAK
                                                                   // (pass 0x15, native render commands) with the blue channel 1.
                                                                   frag_effect = deferred == 1 ? vec4(0.0, 0.0, 1.0, spriteColor.a)
                                                                                               : vec4(vec3(dot(BRIGHTNESS, spriteColor.rgb)), spriteColor.a);
                                                               } else if (mode == 5) {
                                                                   frag_color = vec4(0.0);
                                                                   frag_effect = vec4(vec3((spriteColor.r + spriteColor.g + spriteColor.b) / 3.0), spriteColor.a);
                                                               } else if (mode == 10) {
                                                                   frag_color = vec4(teamColor, 1.0);
                                                                   frag_effect = vec4(0.0, 0.0, 0.0, 1.0);
                                                               } else if (mode == 12) {
                                                                   frag_color = vec4(teamColor, spriteColor.a);
                                                                   frag_effect = vec4(vec3(0.0), spriteColor.a);
                                                               } else if (mode == 13) {
                                                                   float ghostMask = texture(teamcolorTex, texCoord).r;
                                                                   spriteColor.rgb = mix(spriteColor.rgb, spriteColor.rgb * teamColor, ghostMask);
                                                                   frag_color = vec4(spriteColor.rgb, spriteColor.a * 0.5);
                                                                   frag_effect = vec4(vec3(0.0), spriteColor.a * 0.5);
                                                               } else if (mode == 8) {
                                                                   frag_color = vec4(texture(teamcolorTex, mapCoord).rgb, spriteColor.a);
                                                                   frag_effect = vec4(vec3(0.0), spriteColor.a);
                                                               } else {
                                                                   frag_color = vec4(vec3(1.0), spriteColor.a * shaderParameter);
                                                                   frag_effect = vec4(0.0);
                                                               }
                                                           }
                                                           """;
    private static final @NotNull String COMPOSITE_FRAGMENT_SHADER = """
                                                                     #version 330 core
                                                                     in vec2 texCoord;
                                                                     uniform sampler2D spriteTex;
                                                                     uniform sampler2D teamcolorTex;
                                                                     uniform float spriteWidth;
                                                                     layout(location = 0) out vec4 frag_color;
                                                                     layout(location = 1) out vec4 frag_effect;
                                                                     void main() {
                                                                         vec4 effect = texture(teamcolorTex, texCoord);
                                                                         float brightness = effect.b;
                                                                         vec2 tc = vec2((brightness + 0.0625) * 8.0 * spriteWidth, texCoord.y);
                                                                         tc.x += texCoord.x;
                                                                         vec4 adjustedEffect = texture(teamcolorTex, tc);
                                                                         float warpOffset = effect.r * spriteWidth * 8.0;
                                                                         vec4 spriteColor;
                                                                         if (effect.b * adjustedEffect.b > 0.0) {
                                                                             tc.x += warpOffset;
                                                                             spriteColor = texture(spriteTex, tc);
                                                                         } else {
                                                                             spriteColor = texture(spriteTex, vec2(texCoord.x + warpOffset, texCoord.y));
                                                                         }
                                                                         frag_color = vec4(spriteColor.rgb * (1.0 - effect.g * 0.5), 1.0);
                                                                         frag_effect = vec4(0.0);
                                                                     }
                                                                     """;

    private static @NotNull ShaderProgram compileProgram(@NotNull String fragmentSource) {
        ShaderProgram program = GraphicsDevice.getCurrent().createShaderProgram(SHADER_PROGRAM_NAME, VERTEX_SHADER, fragmentSource);

        program.use();
        glUniform1i(program.getUniformLocation("spriteTex"), SPRITE_UNIT);
        glUniform1i(program.getUniformLocation("teamcolorTex"), TEAM_COLOR_UNIT);
        glUniform1i(program.getUniformLocation("tileTex"), TILE_UNIT);
        glUniform1i(program.getUniformLocation("emissiveTex"), FIRST_LIGHTING_UNIT);
        glUniform1i(program.getUniformLocation("normalTex"), FIRST_LIGHTING_UNIT + HdImageLoader.LIGHTING_NORMAL);
        glUniform1i(program.getUniformLocation("specularTex"), FIRST_LIGHTING_UNIT + HdImageLoader.LIGHTING_SPECULAR);
        glUniform1i(program.getUniformLocation("ao_depthTex"), FIRST_LIGHTING_UNIT + HdImageLoader.LIGHTING_AO_DEPTH);
        glUniform1i(program.getUniformLocation("maskTex"), MASK_UNIT);

        return program;
    }

    private static void deleteTextures(int @NotNull [] textures) {
        for (int texture : textures) {
            if (texture != 0) {
                IgnoreException.execute(
                        () -> glDeleteTextures(texture)
                );
            }
        }
    }


    private final @NotNull WorldRenderer world;
    private final @NotNull HdRenderAssets assets;
    private final @NotNull HdImageSet imageSet;
    private final boolean ownsImageSet;
    private final boolean compressedTextures;

    /**
     * Flushes the pending vertices before an image upload ({@link #beforeImageUpload}), shared by every image acquisition.
     */
    private final @NotNull Runnable imageUploadPreparation;

    private boolean lightingEnabled;
    private int lightingDebugView;
    private boolean smoothTurning;
    private boolean environmentEffects;
    private boolean streaming;
    private @Nullable ImageOverlayOffsets imageOverlayOffsets;
    private boolean preloading;
    private int @NotNull [] preloadMegatiles;
    private int preloadMegatile;

    private int megatileCount;
    private int @NotNull [] tileSlots;
    private int @NotNull [] tileWaterFrames;
    private boolean @NotNull [] tileCreep;
    private float @NotNull [] maskedTileRectangles;
    private final float @NotNull [] teamColor;
    /**
     * The team colors of the live player color rows.
     */
    private final float @NotNull [] playerTeamColors;

    private boolean foliageLoaded;
    private @Nullable HdFoliage foliage;
    private @Nullable Object foliageMap;
    private int foliageSeed;
    private int @Nullable [] foliageTextures;
    private float @Nullable [] foliageTextureSizes;

    private @Nullable IntUnaryOperator fogTileFlags;
    /**
     * Images drawn after the fog of a scene (click markers), drawn over the fogged world: image, frame, modifier, data, color, x, y, flipped.
     */
    private final @NotNull List<int @NotNull []> postFogImages;
    /**
     * The RGBA textures of the override sheets' frames ({@link #drawImageSheet}) per one-frame sheet and palette selection, {@code 0} until drawn;
     * the frame drawn least recently first.
     */
    private final @NotNull SequencedMap<SpriteSheet, int @NotNull []> sheetTextures;
    /**
     * The textures of the override anim drawn by {@link #drawImageAnimation} instead of the image's installed anim, and the override sheet that
     * places its frames without a canvas placement; {@code null} outside that draw.
     */
    private @Nullable HdImageTextures animationTextures;
    private @Nullable ScrImageSheet animationPlacement;
    /**
     * The RGBA textures of replaced megatile pictures ({@link #drawTerrain(IntUnaryOperator, IntFunction)}) by megatile index, and the picture
     * each one holds.
     */
    private final @NotNull Map<Integer, Integer> megatileTextures;
    private final @NotNull Map<Integer, byte[]> megatilePictures;
    /**
     * The megatile pictures of the last terrain draw, which the foliage of the frame leaves out, or {@code null} for none.
     */
    private @Nullable IntFunction<byte @Nullable []> terrainMegatiles;
    private boolean fogRequested;
    private int fogVisionMask;
    private int fogVisibleLevel;
    private boolean fogObserver;

    private boolean sceneActive;
    private float cameraX;
    private float cameraY;
    private float scale;
    private int batchMode;
    private int batchTexture;
    private int batchSecondTexture;
    private int @Nullable [] batchLayers;
    private float vertexFlip;
    private float vertexLighting;

    private long vertexRingOffset;
    private long drawCalls;
    private final long @NotNull [] modeDrawCalls;
    private long frameCounter;

    private final @NotNull ShaderProgram program;
    private final int modeUniform;
    private final int deferredUniform;
    private final int framebufferUniform;

    private final @NotNull ShaderProgram compositeProgram;
    private final int compositeFramebufferUniform;
    private final int spriteWidthUniform;

    private final int vertexArray;
    private final int vertexBuffer;
    private final @Nullable FloatBuffer vertices;

    private final int blackTexture;
    /**
     * The {@link #DEFAULT_LIGHTING_COLORS} textures, in {@link #LIGHTING_LAYERS} order.
     */
    private final int @Nullable [] defaultLightingTextures;

    private @Nullable HdLighting lighting;

    private final int @NotNull [] megatileSlots;
    /**
     * The megatile texture arrays, {@link #megatileArrayCount} of them created so far.
     */
    private final int @Nullable [] megatileArrays;
    private int megatileArrayCount;
    private final @Nullable OverlayPass overlay;
    private final @Nullable HdTerrainEffect water;
    private final @NotNull HealthBarLayout barLayout;
    /**
     * Receives the rectangles of {@link #barLayout} for the bar placed at {@link #barLeft} and {@link #barTop} by {@link #drawHealthBar}.
     */
    private final HealthBarLayout.@NotNull RectangleSink barSink;
    private int barLeft;
    private int barTop;

    private final int @Nullable [] creepEdgeTextures;

    private int sceneFramebuffer;
    private int sceneColorTexture;
    private int sceneEffectTexture;
    private int sceneWidth;
    private int sceneHeight;
    private boolean closed;

    /**
     * Creates an {@link HdSceneRenderer} instance on the current OpenGL 3.3 context.
     *
     * @param world  the world renderer whose frame receives the resolved scene
     * @param assets the loaded HD assets of the map's tileset
     * @throws IOException if the creep-edge textures cannot be decoded
     */
    public HdSceneRenderer(@NotNull WorldRenderer world, @NotNull HdRenderAssets assets) throws IOException {
        this(world, assets, new HdImageSet(assets.getAnimations(), false), true);
    }

    /**
     * Creates an {@link HdSceneRenderer} instance on the current OpenGL 3.3 context that draws the images of a shared image set; closing the renderer
     * leaves the set open.
     *
     * @param world    the world renderer whose frame receives the resolved scene
     * @param assets   the loaded HD assets of the map's tileset
     * @param imageSet the image set of the same quality and skin
     * @throws IOException              if the creep-edge textures cannot be decoded
     * @throws IllegalArgumentException if the image set quality differs from the assets
     */
    public HdSceneRenderer(@NotNull WorldRenderer world, @NotNull HdRenderAssets assets, @NotNull HdImageSet imageSet) throws IOException {
        this(world, assets, imageSet, false);
    }

    private HdSceneRenderer(@NotNull WorldRenderer world, @NotNull HdRenderAssets assets, @NotNull HdImageSet imageSet, boolean ownsImageSet) throws IOException {
        if (imageSet.getQuality() != assets.getQuality()) {
            throw Exceptions.newIllegalArgumentException("The image set quality {0} differs from the assets {1}",
                                                         imageSet.getQuality(), assets.getQuality());
        }

        this.world              = world;
        this.assets             = assets;
        this.imageSet           = imageSet;
        this.ownsImageSet       = ownsImageSet;
        this.compressedTextures = GL.getCapabilities().GL_EXT_texture_compression_s3tc;

        this.imageUploadPreparation = this::beforeImageUpload;

        this.lightingEnabled     = imageSet.isLighting();
        this.lightingDebugView   = 0;
        this.smoothTurning       = true;
        this.environmentEffects  = true;
        this.streaming           = false;
        this.imageOverlayOffsets = null;
        this.preloading          = false;
        this.preloadMegatiles    = new int[0];
        this.preloadMegatile     = 0;

        this.megatileCount        = 0;
        this.tileSlots            = new int[0];
        this.tileWaterFrames      = new int[0];
        this.tileCreep            = new boolean[0];
        this.maskedTileRectangles = new float[0];
        this.teamColor            = new float[HdRenderAssets.COLOR_COMPONENTS];
        this.playerTeamColors     = new float[PLAYER_COLOR_ROWS * HdRenderAssets.COLOR_COMPONENTS];

        this.foliageLoaded       = false;
        this.foliage             = null;
        this.foliageMap          = null;
        this.foliageSeed         = 0;
        this.foliageTextures     = null;
        this.foliageTextureSizes = null;

        this.fogTileFlags       = null;
        this.postFogImages      = new ArrayList<>();
        this.sheetTextures      = new LinkedHashMap<>();
        this.animationTextures  = null;
        this.animationPlacement = null;
        this.megatileTextures   = new HashMap<>();
        this.megatilePictures   = new HashMap<>();
        this.terrainMegatiles   = null;
        this.fogRequested       = false;
        this.fogVisionMask      = 0;
        this.fogVisibleLevel    = OverlayPass.DEFAULT_VISIBLE_LEVEL;
        this.fogObserver        = false;

        this.sceneActive        = false;
        this.cameraX            = 0;
        this.cameraY            = 0;
        this.scale              = 0;
        this.batchMode          = 0;
        this.batchTexture       = 0;
        this.batchSecondTexture = 0;
        this.batchLayers        = null;
        this.vertexFlip         = 0;
        this.vertexLighting     = 0;

        this.vertexRingOffset = 0L;
        this.drawCalls        = 0L;
        this.modeDrawCalls    = new long[MODE_COUNT];
        this.frameCounter     = 0L;

        // A failing step closes the renderer, which releases what the steps before it created.
        try {
            this.program            = compileProgram(FRAGMENT_SHADER);
            this.modeUniform        = this.program.getUniformLocation("mode");
            this.deferredUniform    = this.program.getUniformLocation("deferred");
            this.framebufferUniform = this.program.getUniformLocation("framebufferSize");

            this.compositeProgram            = compileProgram(COMPOSITE_FRAGMENT_SHADER);
            this.compositeFramebufferUniform = this.compositeProgram.getUniformLocation("framebufferSize");
            this.spriteWidthUniform          = this.compositeProgram.getUniformLocation("spriteWidth");

            this.vertexArray  = glGenVertexArrays();
            this.vertexBuffer = glGenBuffers();
            this.vertices     = MemoryUtil.memAllocFloat(MAXIMUM_VERTICES * VERTEX_FLOATS);
            glBindVertexArray(this.vertexArray);
            glBindBuffer(GL_ARRAY_BUFFER, this.vertexBuffer);
            glBufferData(GL_ARRAY_BUFFER, (long) this.vertices.capacity() * Float.BYTES * VERTEX_RING_BATCHES, GL_STREAM_DRAW);

            long offset = 0;

            for (int attribute = 0; attribute < ATTRIBUTE_SIZES.length; attribute++) {
                glEnableVertexAttribArray(attribute);
                glVertexAttribPointer(attribute, ATTRIBUTE_SIZES[attribute], GL_FLOAT, false, VERTEX_FLOATS * Float.BYTES, offset * Float.BYTES);
                offset += ATTRIBUTE_SIZES[attribute];
            }

            this.blackTexture = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, this.blackTexture);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 1, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, new int[]{0});
            this.defaultLightingTextures = new int[DEFAULT_LIGHTING_COLORS.length];

            for (int layer = 0; layer < DEFAULT_LIGHTING_COLORS.length; layer++) {
                this.defaultLightingTextures[layer] = glGenTextures();
                glBindTexture(GL_TEXTURE_2D, this.defaultLightingTextures[layer]);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
                glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 1, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, new int[]{DEFAULT_LIGHTING_COLORS[layer]});
            }

            this.lighting = null;

            if (this.lightingEnabled) {
                this.lighting = new HdLighting();
                this.lighting.setDebugView(this.lightingDebugView);
            }

            this.megatileSlots = new int[assets.getMegatiles().getFrameCount()];
            Arrays.fill(this.megatileSlots, -1);
            this.megatileArrays     = new int[Math.ceilDiv(this.megatileSlots.length, ARRAY_LAYERS)];
            this.megatileArrayCount = 0;
            this.overlay            = new OverlayPass(world);
            this.water              = assets.hasTerrainEffect() ? assets.createTerrainEffect(this.compressedTextures) : null;
            this.barLayout          = new HealthBarLayout();
            this.barSink            = (left, top, width, height, colorIndex) ->
                    this.fillBarRectangle(this.barLeft + left,
                                          this.barTop + top,
                                          width,
                                          height,
                                          this.assets.getOverlayAssets().getBarColor(colorIndex));
            this.barLeft            = 0;
            this.barTop             = 0;

            DdsGrpFile creepEdges = assets.getCreepEdges();

            this.creepEdgeTextures = new int[creepEdges.getFrameCount()];

            this.sceneFramebuffer   = 0;
            this.sceneColorTexture  = 0;
            this.sceneEffectTexture = 0;
            this.sceneWidth         = 0;
            this.sceneHeight        = 0;
            this.closed             = false;

            for (int frame = 0; frame < this.creepEdgeTextures.length; frame++) {
                this.creepEdgeTextures[frame] = this.uploadTexture(creepEdges.getFrame(frame));
            }
        } catch (IOException | RuntimeException exception) {
            this.close();

            throw exception;
        }
    }

    /**
     * @return the loaded asset set
     */
    public @NotNull HdRenderAssets getAssets() {
        return this.assets;
    }

    /**
     * @return the anim texture bytes currently resident
     */
    public long getTextureBytes() {
        return this.imageSet.getTextureBytes();
    }

    /**
     * @return {@code true} if the backend draws the remastered (HD) graphics, whose draw builder places images by their HD rules, {@code false}
     * otherwise
     */
    @Override
    public boolean isRemastered() {
        return true;
    }

    /**
     * @return the asset scale of the anims the backend draws ({@link #drawImageAnimation}): {@code 4} for HD, {@code 2} for HD2
     */
    @Override
    public int getAssetScale() {
        return this.imageSet.getQuality().getScale();
    }

    /**
     * @return {@code true} if the deferred lighting path is active, {@code false} otherwise
     */
    public boolean isLightingEnabled() {
        return this.lightingEnabled;
    }

    /**
     * @return {@code true} if a preload started by {@link #startPreload(IntUnaryOperator)} is still incomplete, {@code false} otherwise
     */
    public boolean isPreloading() {
        return this.preloading;
    }

    private float getPreloadProgress() {
        return (this.imageSet.getLoadProgress() * this.imageSet.getLoadImageCount() + this.preloadMegatile) /
               (this.imageSet.getLoadImageCount() + this.preloadMegatiles.length);
    }


    /**
     * Sets the game's live image overlay offsets for the images whose placement reads them at draw time (the HD Bunker fire).
     * <p>
     * This backend places the Bunker fire with them.
     *
     * @param overlays the game's overlay offsets, or {@code null} for the installed resources
     */
    @Override
    public void setImageOverlayOffsets(@Nullable ImageOverlayOffsets overlays) {
        this.imageOverlayOffsets = overlays;
    }

    /**
     * Sets the game context's player color rows (eight {@code tunit.pcx} palette indices per player), read at every draw: the classic remapping of
     * palette indices {@code 8..15} and the HD team color (palette entry of byte {@code 0}). Player colors {@link #PLAYER_COLOR}{@code + player} then
     * draw with them; an EUD write to the rows recolors the player's units.
     * <p>
     * This backend installs the HD team colors from byte {@code 0} of each row.
     *
     * @param rows the twelve rows of eight palette indices
     */
    @Override
    public void setPlayerColorRows(byte @NotNull [] rows) {
        for (int player = 0; player < PLAYER_COLOR_ROWS; player++) {
            this.assets.copyPaletteColor(rows[player * RenderAssets.REMAP_INDEX_COUNT] & BYTE_MASK,
                                         this.playerTeamColors,
                                         player * HdRenderAssets.COLOR_COMPONENTS);
        }
    }

    @Override
    public void setCustomTeamColor(int player, int color) {
        if (player < 0 || player >= PLAYER_COLOR_ROWS) {
            return;
        }

        int offset = player * HdRenderAssets.COLOR_COMPONENTS;

        this.playerTeamColors[offset]                          = (color >>> 3 * Byte.SIZE) / OverlayPass.CHANNEL_MAXIMUM;
        this.playerTeamColors[offset + OverlayPass.RGBA_GREEN] = (color >>> Short.SIZE & BYTE_MASK) / OverlayPass.CHANNEL_MAXIMUM;
        this.playerTeamColors[offset + OverlayPass.RGBA_BLUE]  = (color >>> Byte.SIZE & BYTE_MASK) / OverlayPass.CHANNEL_MAXIMUM;
    }

    /**
     * Sets whether foliage is animated and drawn without changing its placement.
     *
     * @param enabled {@code true} to draw the HD foliage
     */
    public void setEnvironmentEffects(boolean enabled) {
        this.environmentEffects = enabled;
    }

    /**
     * Sets whether every HD direction frame is drawn. When disabled, a frame whose classic GRP repeats the previous frame draws that previous frame
     * instead. The simulation image frame is unchanged.
     *
     * @param enabled {@code true} to draw every HD direction frame
     */
    public void setSmoothTurning(boolean enabled) {
        this.smoothTurning = enabled;
    }

    /**
     * Sets whether the deferred lighting path with bloom is active. Switching releases the uploaded anim textures because the deferred path also
     * uploads the emissive, normal, specular and AO/depth layers.
     *
     * @param enabled {@code true} for the deferred lighting path
     * @throws IllegalStateException if a scene is being drawn
     */
    public void setLightingEnabled(boolean enabled) {
        if (enabled == this.lightingEnabled) {
            return;
        }

        if (this.sceneActive) {
            throw Exceptions.newIllegalStateException("The lighting path cannot change during a scene.");
        }

        // The deferred path needs the lighting layers of every image: the set releases its images, and a loaded set loads
        // them again (isPreloading() until continuePreload completes it).
        this.imageSet.setLighting(enabled);
        this.lightingEnabled = enabled;

        if (this.imageSet.isLoading()) {
            this.preloading = true;
        }

        if (enabled && this.lighting == null) {
            this.lighting = new HdLighting();
            this.lighting.setDebugView(this.lightingDebugView);
        }
    }

    /**
     * Sets the lighting debug view of the deferred path (no effect on the forward path).
     *
     * @param view an index into {@link #LIGHTING_DEBUG_VIEWS}: {@code off}, {@code lights} (the scene with each point light's radius ring and
     *             center), {@code lighting} (the light a white surface receives: directional, shadows and point lights), {@code albedo},
     *             {@code normals}, {@code ao}, {@code emissive} and {@code amount} (the per-pixel lighting amount)
     * @throws IllegalArgumentException if the view index is out of range
     */
    public void setLightingDebugView(int view) {
        if (view < 0 || view >= LIGHTING_DEBUG_VIEWS.length) {
            throw Exceptions.newIllegalArgumentException("Unknown lighting debug view: {0}", view);
        }

        this.lightingDebugView = view;

        if (this.lighting != null) {
            this.lighting.setDebugView(view);
        }
    }

    /**
     * Sets whether new HD images use background preparation and a bounded upload time per frame. An image is skipped until ready; disabling streaming
     * makes its first draw wait for preparation.
     *
     * @param streaming {@code true} to stream, {@code false} to wait
     */
    public void setStreaming(boolean streaming) {
        this.streaming = streaming;
    }


    /**
     * Starts loading the image set (nothing when the shared set is already loaded) and the megatiles of the map's current tiles. The load is advanced
     * by {@link #continuePreload(long)}; afterwards the images stay resident, so no image appears late. Changing the lighting path
     * ({@link #setLightingEnabled(boolean)}) starts the image load again.
     *
     * @param tileIdentifiers the row-major tile identifier for each tile index of the loaded map extent
     */
    public void startPreload(@NotNull IntUnaryOperator tileIdentifiers) {
        boolean[] used = new boolean[this.megatileSlots.length];
        int usedCount = 0;
        int tileCount = this.world.getMapWidth() * this.world.getMapHeight();

        for (int tile = 0; tile < tileCount; tile++) {
            int megatile;

            try {
                megatile = this.assets.getMegatile(tileIdentifiers.applyAsInt(tile));
            } catch (IOException ignored) {
                // An invalid tile is reported when the terrain is drawn.
                continue;
            }

            if (megatile >= 0 && megatile < used.length && !used[megatile]) {
                used[megatile] = true;
                usedCount++;
            }
        }

        this.preloadMegatiles = new int[usedCount];
        usedCount             = 0;

        for (int megatile = 0; megatile < used.length; megatile++) {
            if (used[megatile]) {
                this.preloadMegatiles[usedCount++] = megatile;
            }
        }

        this.imageSet.startLoad();
        this.preloading      = true;
        this.preloadMegatile = 0;
    }

    /**
     * Advances the preload started by {@link #startPreload(IntUnaryOperator)}: images are prepared on the loader threads and uploaded here, in image
     * order, until the time budget is spent. Call it outside a scene.
     *
     * @param budgetNanoseconds the upload time after which the call returns
     * @return the preload progress from {@code 0} to {@code 1} ({@code 1} when complete or when no preload was started)
     * @throws IllegalStateException if a scene is being drawn
     */
    public float continuePreload(long budgetNanoseconds) {
        if (!this.preloading) {
            return 1;
        }

        if (this.sceneActive) {
            throw Exceptions.newIllegalStateException("The HD preload cannot run during a scene.");
        }

        long deadline = System.nanoTime() + budgetNanoseconds;

        this.imageSet.continueLoad(budgetNanoseconds);

        if (this.imageSet.isLoading()) {
            return this.getPreloadProgress();
        }

        int activeTexture = glGetInteger(GL_ACTIVE_TEXTURE);

        glActiveTexture(GL_TEXTURE0);

        int previousTexture2d = glGetInteger(GL_TEXTURE_BINDING_2D);
        int previousTextureArray = glGetInteger(GL_TEXTURE_BINDING_2D_ARRAY);

        try {
            while (this.preloadMegatile < this.preloadMegatiles.length) {
                try {
                    this.getMegatileSlot(this.preloadMegatiles[this.preloadMegatile]);
                } catch (IOException exception) {
                    LOGGER.warning("HD megatile {0} is unavailable: {1}", this.preloadMegatiles[this.preloadMegatile], exception.getMessage());
                }

                this.preloadMegatile++;

                if (System.nanoTime() >= deadline) {
                    return this.getPreloadProgress();
                }
            }

            this.preloading = false;

            return 1;
        } finally {
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, previousTexture2d);
            glBindTexture(GL_TEXTURE_2D_ARRAY, previousTextureArray);
            glActiveTexture(activeTexture);
        }
    }


    /**
     * Draws the visible terrain from the current game tile identifiers, including creep edges.
     * <p>
     * A tile whose CV5 group is {@code 1} is creep; each other visible tile receives the HD creep-edge frame selected by its eight neighbors exactly
     * as the classic renderer selects the GRP frame ({@link WorldRenderer#getCreepEdgeFrame}).
     *
     * @param tileIdentifiers the current row-major tile identifier for each tile index of the loaded map extent
     * @throws IOException if a tile identifier is outside the installed assets
     */
    @Override
    public void drawTerrain(@NotNull IntUnaryOperator tileIdentifiers) throws IOException {
        this.drawTerrainTiles(tileIdentifiers, null);
    }

    /**
     * Draws the visible terrain from the current game tile identifiers, including creep edges, with megatile pictures that replace installed ones:
     * a replaced tile draws its classic picture in the classic palette at world size, opaque, with nearest filtering, over the HD tile.
     *
     * @param tileIdentifiers the current row-major tile identifier for each tile index of the loaded map extent
     * @param megatiles       the picture of a megatile index, the same array while it is unchanged, or {@code null} for the installed picture
     * @throws IOException if a tile identifier is outside the installed assets
     */
    @Override
    public void drawTerrain(@NotNull IntUnaryOperator tileIdentifiers, @NotNull IntFunction<byte @Nullable []> megatiles) throws IOException {
        this.drawTerrainTiles(tileIdentifiers, megatiles);
    }

    private void drawTerrainTiles(@NotNull IntUnaryOperator tileIdentifiers, @Nullable IntFunction<byte @Nullable []> megatiles) throws IOException {
        this.beginScene();

        this.terrainMegatiles = megatiles;

        boolean visibleWater = false;
        int mapWidth = this.world.getMapWidth();
        int mapHeight = this.world.getMapHeight();
        int firstColumn = Math.max(0, (int) Math.floor(this.cameraX / TerrainTileset.TILE_SIZE));
        int firstRow = Math.max(0, (int) Math.floor(this.cameraY / TerrainTileset.TILE_SIZE));
        int lastColumn = Math.min(mapWidth, (int) Math.ceil((this.cameraX + this.world.getViewWidth() / this.scale) / TerrainTileset.TILE_SIZE));
        int lastRow = Math.min(mapHeight, (int) Math.ceil((this.cameraY + this.world.getViewHeight() / this.scale) / TerrainTileset.TILE_SIZE));
        // Tiles do not overlap, so they are drawn grouped by megatile texture array: one draw call per array instead of a
        // flush whenever neighboring tiles live in different arrays (about one draw call per tile).
        int visibleColumns = Math.max(0, lastColumn - firstColumn);
        int visibleTiles = visibleColumns * Math.max(0, lastRow - firstRow);

        if (this.tileSlots.length < visibleTiles) {
            this.tileSlots       = new int[visibleTiles];
            this.tileWaterFrames = new int[visibleTiles];
            this.tileCreep       = new boolean[visibleTiles];
        }

        for (int row = firstRow; row < lastRow; row++) {
            for (int column = firstColumn; column < lastColumn; column++) {
                int tileIdentifier = tileIdentifiers.applyAsInt(row * mapWidth + column);
                int megatile = this.assets.getMegatile(tileIdentifier);
                int visibleIndex = (row - firstRow) * visibleColumns + column - firstColumn;

                this.tileCreep[visibleIndex] = TerrainTileset.isCreepTile(tileIdentifier);
                this.tileSlots[visibleIndex] = this.getMegatileSlot(megatile);
                // A replaced megatile draws as land: its picture is drawn over the tile, without the water effect.
                boolean replaced = megatiles != null && megatiles.apply(megatile) != null;

                this.tileWaterFrames[visibleIndex] = this.water != null && !replaced ? this.assets.getWaterMaskFrame(megatile) + 1 : 0;
                visibleWater |= this.tileWaterFrames[visibleIndex] > 0;
            }
        }

        // Draws unmasked terrain, creep edges, the masked terrain effect, then creep tiles.
        boolean effectTerrain = visibleWater && this.water != null;

        if (!effectTerrain) {
            this.drawTiles(firstColumn, firstRow, visibleColumns, visibleTiles, TILES_ALL);
            this.drawMegatilePictures(tileIdentifiers, megatiles, firstColumn, firstRow, lastColumn, lastRow);
            this.drawCreepEdges(tileIdentifiers, mapWidth, mapHeight, firstColumn, firstRow, lastColumn, lastRow, MODE_EDGE);

            return;
        }

        this.drawTiles(firstColumn, firstRow, visibleColumns, visibleTiles, TILES_UNMASKED);

        int maskedTiles = this.drawTiles(firstColumn, firstRow, visibleColumns, visibleTiles, TILES_MASKED);

        this.drawMegatilePictures(tileIdentifiers, megatiles, firstColumn, firstRow, lastColumn, lastRow);

        this.drawCreepEdges(tileIdentifiers, mapWidth, mapHeight, firstColumn, firstRow, lastColumn, lastRow, MODE_EDGE_EFFECT);
        this.flush();
        this.water.update(System.nanoTime() / NANOSECONDS_PER_MILLISECOND);
        this.water.draw(this.sceneWidth, this.sceneHeight,
                        this.world.getViewWidth(), this.world.getViewHeight(),
                        this.cameraX, this.cameraY, this.scale, mapWidth * (float) TerrainTileset.TILE_SIZE,
                        GL_COLOR_ATTACHMENT1, this.lightingEnabled && this.lighting != null,
                        this.maskedTileRectangles, maskedTiles);

        glClearBufferfv(GL_COLOR, WorldRenderer.EFFECT_TARGET, CLEAR_EFFECT);
        this.program.use();
        glBindVertexArray(this.vertexArray);
        this.batchMode = -1;

        this.drawTiles(firstColumn, firstRow, visibleColumns, visibleTiles, TILES_CREEP);
    }

    /**
     * Draws the visible tiles of one group, grouped by megatile texture array; the masked group also records its tile rectangles for the terrain
     * effect.
     *
     * @param firstColumn    the first visible tile column
     * @param firstRow       the first visible tile row
     * @param visibleColumns the number of visible tile columns
     * @param visibleTiles   the number of visible tiles
     * @param group          {@link #TILES_ALL}, {@link #TILES_UNMASKED}, {@link #TILES_MASKED} or {@link #TILES_CREEP}
     * @return the number of tiles drawn
     */
    private int drawTiles(int firstColumn, int firstRow, int visibleColumns, int visibleTiles, int group) {
        int drawn = 0;
        float size = TerrainTileset.TILE_SIZE * this.scale;

        if (group == TILES_MASKED && this.maskedTileRectangles.length < visibleTiles * HdTerrainEffect.TILE_VALUES) {
            this.maskedTileRectangles = new float[visibleTiles * HdTerrainEffect.TILE_VALUES];
        }

        for (int arrayIndex = 0; arrayIndex < this.megatileArrayCount; arrayIndex++) {
            for (int visibleIndex = 0; visibleIndex < visibleTiles; visibleIndex++) {
                int slot = this.tileSlots[visibleIndex];

                if (slot >>> SLOT_ARRAY_SHIFT != arrayIndex) {
                    continue;
                }

                int waterFrame = this.tileWaterFrames[visibleIndex];
                boolean creep = this.tileCreep[visibleIndex];
                int mode = MODE_TILE;

                if (group == TILES_UNMASKED) {
                    if (creep || waterFrame > 0) {
                        continue;
                    }

                    mode = MODE_TILE_LAND;
                } else if (group == TILES_MASKED) {
                    if (creep || waterFrame == 0) {
                        continue;
                    }

                    mode = MODE_TILE_EFFECT;
                } else if (group == TILES_CREEP && !creep) {
                    continue;
                }

                int column = firstColumn + visibleIndex % visibleColumns;
                int row = firstRow + visibleIndex / visibleColumns;
                float left = (column * TerrainTileset.TILE_SIZE - this.cameraX) * this.scale;
                float top = (row * TerrainTileset.TILE_SIZE - this.cameraY) * this.scale;

                if (group == TILES_MASKED) {
                    this.maskedTileRectangles[drawn * HdTerrainEffect.TILE_VALUES + HdTerrainEffect.TILE_LEFT]   = left;
                    this.maskedTileRectangles[drawn * HdTerrainEffect.TILE_VALUES + HdTerrainEffect.TILE_TOP]    = top;
                    this.maskedTileRectangles[drawn * HdTerrainEffect.TILE_VALUES + HdTerrainEffect.TILE_EXTENT] = size;
                }

                this.selectBatch(mode, this.megatileArrays[arrayIndex], 0, null);
                this.appendQuad(left, top, left + size, top + size, 0, 0, 1, 1, waterFrame, 0, waterFrame, 0, 0, slot & SLOT_LAYER_MASK);
                drawn++;
            }
        }

        return drawn;
    }

    /**
     * Draws the replacing pictures of the visible tiles whose megatile has one.
     *
     * @param tileIdentifiers the current row-major tile identifier for each tile index of the loaded map extent
     * @param megatiles       the picture of a megatile index, or {@code null} for the installed picture; {@code null} for no replacements
     * @param firstColumn     the first visible tile column
     * @param firstRow        the first visible tile row
     * @param lastColumn      the column after the last visible one
     * @param lastRow         the row after the last visible one
     * @throws IOException if no map is loaded or a picture is not {@code 32x32} palette indices
     */
    private void drawMegatilePictures(@NotNull IntUnaryOperator tileIdentifiers, @Nullable IntFunction<byte @Nullable []> megatiles,
                                      int firstColumn, int firstRow, int lastColumn, int lastRow) throws IOException {
        if (megatiles == null) {
            return;
        }

        int mapWidth = this.world.getMapWidth();
        float size = TerrainTileset.TILE_SIZE * this.scale;

        for (int row = firstRow; row < lastRow; row++) {
            for (int column = firstColumn; column < lastColumn; column++) {
                int megatile = this.assets.getMegatile(tileIdentifiers.applyAsInt(row * mapWidth + column));
                byte[] picture = megatiles.apply(megatile);

                if (picture == null) {
                    continue;
                }

                int texture = this.acquireMegatileTexture(megatile, picture);
                float left = (column * TerrainTileset.TILE_SIZE - this.cameraX) * this.scale;
                float top = (row * TerrainTileset.TILE_SIZE - this.cameraY) * this.scale;

                this.copyTeamColor(0);
                this.vertexFlip     = 1;
                this.vertexLighting = 0;

                this.selectBatch(MODE_SPRITE, texture, this.blackTexture, null);
                this.appendQuad(left, top, left + size, top + size, 0, 0, 1, 1, 0, 0, 0, 0, 0, 0);
            }
        }
    }

    /**
     * Returns the RGBA texture of a megatile's replacing picture, made on first use and again when the picture array changes: every palette index
     * in its classic color, opaque, with nearest filtering.
     *
     * @param megatile the megatile index
     * @param picture  the {@code 32x32} palette indices
     * @return the texture
     * @throws IOException if no map is loaded or the picture is not {@code 32x32} palette indices
     */
    private int acquireMegatileTexture(int megatile, byte @NotNull [] picture) throws IOException {
        Integer existing = this.megatileTextures.get(megatile);

        if (existing != null && this.megatilePictures.get(megatile) == picture) {
            return existing;
        }

        if (picture.length != TerrainTileset.TILE_SIZE * TerrainTileset.TILE_SIZE) {
            throw Exceptions.newIOException("A megatile picture has {0} pixels.", picture.length);
        }

        byte[] palette = this.world.copyPlayerColorPalette(0);

        if (palette == null) {
            throw Exceptions.newIOException("The megatile picture needs a loaded map palette.");
        }

        ByteBuffer upload = MemoryUtil.memAlloc(picture.length * OverlayPass.RGBA_BYTES);

        try {
            for (byte value : picture) {
                upload.put(palette, (value & BYTE_MASK) * OverlayPass.RGBA_BYTES, OverlayPass.RGBA_BYTES - 1).put(OverlayPass.OPAQUE_ALPHA);
            }

            upload.flip();
            this.flush();

            int name = existing != null ? existing : glGenTextures();

            glBindTexture(GL_TEXTURE_2D, name);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAX_LEVEL, 0);
            glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, TerrainTileset.TILE_SIZE, TerrainTileset.TILE_SIZE, 0, GL_RGBA, GL_UNSIGNED_BYTE, upload);

            this.megatileTextures.put(megatile, name);
            this.megatilePictures.put(megatile, picture);
            this.batchTexture = 0;

            return name;
        } finally {
            MemoryUtil.memFree(upload);
        }
    }

    private void deleteMegatileTextures() {
        for (int texture : this.megatileTextures.values()) {
            IgnoreException.execute(
                    () -> glDeleteTextures(texture)
            );
        }

        this.megatileTextures.clear();
        this.megatilePictures.clear();
        this.batchTexture = 0;
    }

    private void drawCreepEdges(@NotNull IntUnaryOperator tileIdentifiers,
                                int mapWidth, int mapHeight,
                                int firstColumn, int firstRow, int lastColumn, int lastRow,
                                int mode) throws IOException {
        float unit = this.scale / this.assets.getQuality().getScale();
        int directions = WorldRenderer.getCreepNeighborCount();

        for (int row = firstRow; row < lastRow; row++) {
            for (int column = firstColumn; column < lastColumn; column++) {
                if (TerrainTileset.isCreepTile(tileIdentifiers.applyAsInt(row * mapWidth + column))) {
                    continue;
                }

                int neighborMask = 0;

                for (int direction = 0; direction < directions; direction++) {
                    int neighborColumn = column + WorldRenderer.getCreepNeighborHorizontal(direction);
                    int neighborRow = row + WorldRenderer.getCreepNeighborVertical(direction);

                    if (neighborColumn < 0 || neighborColumn >= mapWidth || neighborRow < 0 || neighborRow >= mapHeight) {
                        continue;
                    }

                    if (TerrainTileset.isCreepTile(tileIdentifiers.applyAsInt(neighborRow * mapWidth + neighborColumn))) {
                        neighborMask |= 1 << direction;
                    }
                }

                int creepFrame = WorldRenderer.getCreepEdgeFrame(neighborMask);

                if (creepFrame == 0) {
                    continue;
                }

                if (creepFrame > this.creepEdgeTextures.length) {
                    throw Exceptions.newIOException("The HD creep-edge container has no frame {0}", creepFrame - 1);
                }

                int frame = creepFrame - 1;
                float left = (column * TerrainTileset.TILE_SIZE - this.cameraX) * this.scale + this.assets.getCreepEdgeXOffset(frame) * unit;
                float top = (row * TerrainTileset.TILE_SIZE - this.cameraY) * this.scale + this.assets.getCreepEdgeYOffset(frame) * unit;
                DdsGrpFile edges = this.assets.getCreepEdges();

                this.selectBatch(mode, this.creepEdgeTextures[frame], this.blackTexture, null);
                this.appendQuad(left, top, left + edges.getFrameWidth(frame) * unit, top + edges.getFrameHeight(frame) * unit,
                                0, 0, 1, 1,
                                0, 0, 0, 0,
                                0, 0);
            }
        }
    }

    /**
     * Draws HD foliage, placed once per map from the game seed with its own generator and animated by the game frame; presentation only.
     * <p>
     * This backend draws the foliage ({@link HdFoliage}) over the terrain. The per-tile draw draws a visible anchor's frame (the type's anim entry,
     * its first frame plus the variant) with the type's canvas (scale-{@code 4} pixels) at the anchor tile plus the placement offset, scaled by the
     * placement scale about the canvas center: an edge at canvas coordinate {@code c} lies at {@code (2 * scale * (c - canvas / 2) + canvas) / 8}
     * game pixels from that corner. HD2 frames are doubled to scale-{@code 4} pixels first, and flipped foliage mirrors the frame inside the canvas
     * ({@code canvas - x}, doubled with the frame coordinates in HD2). The divisor {@code 8} is the quad builder's rectangle divisor, the same at
     * render scales {@code 1}, {@code 2} and {@code 4}.
     *
     * @param map             the loaded map; another object places the foliage again
     * @param gameSeed        the game seed
     * @param gameFrame       the current game frame
     * @param tileIdentifiers the current row-major tile identifier for each tile index of the loaded map extent
     * @param resources       the tile footprints of the resource units, four values each (column, row, columns, rows)
     * @param resourceCount   the number of resources in {@code resources}
     * @throws IOException if the foliage assets are malformed
     */
    @Override
    public void drawFoliage(@NotNull Object map,
                            int gameSeed, int gameFrame,
                            @NotNull IntUnaryOperator tileIdentifiers,
                            int @NotNull [] resources, int resourceCount) throws IOException {
        this.beginScene();

        if (!this.foliageLoaded) {
            this.foliageLoaded = true;
            this.foliage       = this.assets.loadFoliage();
        }

        HdFoliage foliage = this.foliage;

        if (foliage == null) {
            return;
        }

        int mapWidth = this.world.getMapWidth();
        int mapHeight = this.world.getMapHeight();

        if (!foliage.isPlaced() || this.foliageMap != map || this.foliageSeed != gameSeed ||
            foliage.getWidth() != mapWidth || foliage.getHeight() != mapHeight) {
            foliage.place(gameSeed, mapWidth, mapHeight, tile -> this.assets.getCv5Megatile(tileIdentifiers.applyAsInt(tile)));
            this.foliageMap  = map;
            this.foliageSeed = gameSeed;
        }

        for (int resource = 0; resource < resourceCount; resource++) {
            foliage.block(resources[resource * RESOURCE_VALUES + RESOURCE_COLUMN],
                          resources[resource * RESOURCE_VALUES + RESOURCE_ROW],
                          resources[resource * RESOURCE_VALUES + RESOURCE_COLUMNS],
                          resources[resource * RESOURCE_VALUES + RESOURCE_ROWS]);
        }

        if (!this.environmentEffects) {
            return;
        }

        int firstColumn = (int) Math.floor(this.cameraX / TerrainTileset.TILE_SIZE);
        int firstRow = (int) Math.floor(this.cameraY / TerrainTileset.TILE_SIZE);
        int columns = ((int) (this.world.getViewWidth() / this.scale) + TerrainTileset.TILE_SIZE - 1) / TerrainTileset.TILE_SIZE;
        int rows = ((int) (this.world.getViewHeight() / this.scale) + TerrainTileset.TILE_SIZE - 1) / TerrainTileset.TILE_SIZE;

        foliage.update(gameFrame * HdFoliage.FRAME_MILLISECONDS, firstColumn, firstRow, columns, rows);

        AnimFile animations = foliage.getAnimations();

        if (this.foliageTextures == null) {
            this.foliageTextures     = new int[animations.getEntryCount()];
            this.foliageTextureSizes = new float[animations.getEntryCount() * FOLIAGE_SIZE_VALUES];
        }

        float[] sizes = this.foliageTextureSizes;
        int coordinateScale = animations.getScale() == HALF_SCALE_ANIMATION ? HALF_SCALE_COORDINATE_FACTOR : 1;
        int marginColumns = Math.max(0, (int) foliage.getMaximumWidth() - 1);
        int marginRows = Math.max(0, (int) foliage.getMaximumHeight() - 1);

        this.vertexFlip     = 1;
        this.vertexLighting = 0;

        for (int row = firstRow - marginRows; row < firstRow + rows + 1 + marginRows; row++) {
            if (row < 0 || row >= mapHeight) {
                continue;
            }

            for (int column = firstColumn - marginColumns; column < firstColumn + columns + 1 + marginColumns; column++) {
                if (column < 0 || column >= mapWidth) {
                    continue;
                }

                int tile = row * mapWidth + column;

                if ((foliage.getFlags(tile) & HdFoliage.FLAG_VISIBLE) == 0) {
                    continue;
                }

                // A tile whose megatile picture is replaced has no foliage.
                if (this.terrainMegatiles != null && this.terrainMegatiles.apply(this.assets.getMegatile(tileIdentifiers.applyAsInt(tile))) != null) {
                    continue;
                }

                int type = foliage.getType(tile);
                int entryIndex = foliage.getAnimationEntry(type);
                AnimFile.Entry entry = animations.getEntry(entryIndex);
                int frame = foliage.getFirstFrame(type) + foliage.getVariant(tile);

                if (frame >= entry.getFrameCount()) {
                    throw Exceptions.newIOException("The foliage anim entry {0} has no frame {1}", entryIndex, frame);
                }

                int texture = this.getFoliageTexture(entry, entryIndex);
                float canvasWidth = foliage.getCanvasWidth(type);
                float canvasHeight = foliage.getCanvasHeight(type);
                float scale = foliage.getScale(tile);
                int frameX = entry.getFrameXOffset(frame);
                int frameWidth = entry.getFrameWidth(frame);
                float edge0;
                float edge1;

                if ((foliage.getFlags(tile) & HdFoliage.FLAG_FLIPPED) != 0) {
                    edge0 = (canvasWidth - frameX) * coordinateScale;
                    edge1 = (canvasWidth - frameWidth - frameX) * coordinateScale;
                } else {
                    edge0 = frameX * coordinateScale;
                    edge1 = (frameX + frameWidth) * coordinateScale;
                }

                float top0 = entry.getFrameYOffset(frame) * coordinateScale;
                float top1 = (entry.getFrameYOffset(frame) + entry.getFrameHeight(frame)) * coordinateScale;
                float cornerX = column * TerrainTileset.TILE_SIZE + foliage.getOffsetX(tile);
                float cornerY = row * TerrainTileset.TILE_SIZE + foliage.getOffsetY(tile);
                float canvasCenterX = canvasWidth / RenderAssets.HALF_DIVISOR;
                float canvasCenterY = canvasHeight / RenderAssets.HALF_DIVISOR;
                float left = (cornerX + (FOLIAGE_SCALE_MULTIPLIER * scale * (edge0 - canvasCenterX) + canvasWidth) / FOLIAGE_RECTANGLE_DIVISOR -
                              this.cameraX) * this.scale;
                float right = (cornerX + (FOLIAGE_SCALE_MULTIPLIER * scale * (edge1 - canvasCenterX) + canvasWidth) / FOLIAGE_RECTANGLE_DIVISOR -
                               this.cameraX) * this.scale;
                float top = (cornerY + (FOLIAGE_SCALE_MULTIPLIER * scale * (top0 - canvasCenterY) + canvasHeight) / FOLIAGE_RECTANGLE_DIVISOR -
                             this.cameraY) * this.scale;
                float bottom = (cornerY + (FOLIAGE_SCALE_MULTIPLIER * scale * (top1 - canvasCenterY) + canvasHeight) / FOLIAGE_RECTANGLE_DIVISOR -
                                this.cameraY) * this.scale;
                float textureLeft = entry.getFrameX(frame) / sizes[entryIndex * FOLIAGE_SIZE_VALUES];
                float textureRight = (entry.getFrameX(frame) + frameWidth) / sizes[entryIndex * FOLIAGE_SIZE_VALUES];
                float textureTop = entry.getFrameY(frame) / sizes[entryIndex * FOLIAGE_SIZE_VALUES + 1];
                float textureBottom = (entry.getFrameY(frame) + entry.getFrameHeight(frame)) / sizes[entryIndex * FOLIAGE_SIZE_VALUES + 1];

                if (right < left) {
                    float swap = left;

                    left         = right;
                    right        = swap;
                    swap         = textureLeft;
                    textureLeft  = textureRight;
                    textureRight = swap;
                }

                if (left >= this.world.getViewWidth() || top >= this.world.getViewHeight() || right <= 0 || bottom <= 0) {
                    continue;
                }

                this.selectBatch(MODE_EDGE, texture, this.blackTexture, null);
                this.appendQuad(left, top, right, bottom, textureLeft, textureTop, textureRight, textureBottom, 0, 0, 0, 0, 0, 0);
            }
        }
    }

    private int getFoliageTexture(AnimFile.@NotNull Entry entry, int entryIndex) throws IOException {
        int[] textures = this.foliageTextures;
        float[] sizes = this.foliageTextureSizes;

        if (textures == null || sizes == null) {
            throw Exceptions.newIllegalStateException("The foliage textures are not prepared.");
        }

        if (textures[entryIndex] == 0) {
            DdsFile diffuse = entry.getLayerTexture(FOLIAGE_DIFFUSE_LAYER);

            if (diffuse == null) {
                throw Exceptions.newIOException("The foliage anim entry {0} has no diffuse layer.", entryIndex);
            }

            // The upload rebinds textures and resets the batch texture: draw the pending terrain batch first.
            this.flush();
            textures[entryIndex]                        = this.uploadTexture(diffuse);
            sizes[entryIndex * FOLIAGE_SIZE_VALUES]     = diffuse.getWidth();
            sizes[entryIndex * FOLIAGE_SIZE_VALUES + 1] = diffuse.getHeight();
        }

        return textures[entryIndex];
    }

    /**
     * Draws one image of a sprite.
     *
     * @param imageIndex   the image identifier
     * @param frameIndex   the image frame
     * @param modifier     the image draw modifier
     * @param modifierData the image modifier data
     * @param playerColor  the owner's player color index {@code 0..15}, or {@link #PLAYER_COLOR}{@code + owner}
     * @param worldX       the canvas center world coordinate
     * @param worldY       the canvas center world coordinate
     * @param flipped      whether the image is horizontally flipped
     * @throws IOException if the image cannot be loaded or its modifier is not drawn by this backend
     */
    @Override
    public void drawImage(int imageIndex, int frameIndex, int modifier, int modifierData, int playerColor, int worldX, int worldY, boolean flipped) throws IOException {
        this.beginScene();

        if (this.fogRequested) {
            // Drawn after the fog (the classic backend draws into the fogged world directly): kept for the pass after the
            // fog overlay so that click markers stay bright over fogged ground.
            this.postFogImages.add(new int[]{imageIndex, frameIndex, modifier, modifierData, playerColor, worldX, worldY, flipped ? 1 : 0});

            return;
        }

        this.drawSceneImage(imageIndex, frameIndex, modifier, modifierData, playerColor, worldX, worldY, flipped);
    }

    /**
     * Draws one image of a sprite with the frames of an override sheet instead of the image's installed graphics: the SD frame through the classic
     * palette of the player color, scaled to the world size with nearest filtering and unlit. Update rectangles and images drawn after the fog draw
     * the installed graphics.
     *
     * @param sheet        the indexed frames the image draws, their canvas centered on the image position
     * @param imageIndex   the image identifier
     * @param frameIndex   the image frame, a frame of the sheet
     * @param modifier     the image draw modifier
     * @param modifierData the image modifier data
     * @param playerColor  the owner's player color index {@code 0..15}, or {@link #PLAYER_COLOR}{@code + owner}
     * @param worldX       the canvas center world coordinate
     * @param worldY       the canvas center world coordinate
     * @param flipped      whether the image is horizontally flipped
     * @throws IOException if the frame cannot be drawn or the modifier is not drawn by this backend
     */
    @Override
    public void drawImageSheet(@NotNull ScrImageSheet sheet,
                               int imageIndex, int frameIndex,
                               int modifier, int modifierData, int playerColor,
                               int worldX, int worldY, boolean flipped) throws IOException {
        this.beginScene();

        if (this.fogRequested || modifier == DRAW_FUNCTION_UPDATE_RECTANGLE) {
            this.drawImage(imageIndex, frameIndex, modifier, modifierData, playerColor, worldX, worldY, flipped);

            return;
        }

        SpriteSheet frame = sheet.getFrame(frameIndex);

        switch (modifier) {
            case DRAW_FUNCTION_UNSEEN_CLOAKING,
                 DRAW_FUNCTION_UNSEEN_CLOAKED,
                 DRAW_FUNCTION_UNSEEN_DECLOAKING -> this.drawSheetFrame(frame, MODE_CLOAKED, 0, playerColor, worldX, worldY, flipped);
            case DRAW_FUNCTION_CLOAKING,
                 DRAW_FUNCTION_CLOAKED,
                 DRAW_FUNCTION_DECLOAKING -> this.drawSheetFrame(frame, MODE_TRANSLUCENT, VISIBLE_CLOAK_ALPHA, playerColor, worldX, worldY, flipped);
            case DRAW_FUNCTION_DISTORTION -> this.drawSheetFrame(frame, MODE_WARPED, 0, playerColor, worldX, worldY, flipped);
            case DRAW_FUNCTION_SHADOW -> {
                if (this.world.isShadowStacking()) {
                    this.drawSheetFrame(frame, MODE_STACKED_SHADOW, STACKED_SHADOW_ALPHA, playerColor, worldX, worldY, flipped);
                } else {
                    this.drawSheetFrame(frame, MODE_SHADOW, 1, playerColor, worldX, worldY, flipped);
                }
            }
            case DRAW_FUNCTION_HALLUCINATION -> this.drawSheetFrame(frame, MODE_SPRITE, 1, playerColor, worldX, worldY, flipped);
            case DRAW_FUNCTION_WARP_FLASH -> {
                float brighten = SceneRenderer.getBrightenAlpha(modifierData);

                if (brighten > 0) {
                    this.drawSheetFrame(frame, MODE_BRIGHTEN, brighten, playerColor, worldX, worldY, flipped);
                }
            }
            default -> this.drawSheetFrame(frame, MODE_SPRITE, 0, playerColor, worldX, worldY, flipped);
        }
    }

    /**
     * Draws one image of a sprite with the frames of an override anim instead of the image's installed anim, with the image's draw function. A
     * frame the anim does not place on its canvas is placed by the override sheet's frame, else on the canvas. The anim's textures keep the team
     * color in every frame and get no frame lights. Update rectangles and images drawn after the fog draw the override sheet or the installed
     * graphics, and so does the image while the anim's textures are not uploaded yet.
     *
     * @param animation    the texture sheet of the anim, prepared at the backend's asset scale or the other one ({@link #getAssetScale()})
     * @param sheet        the indexed frames the image draws in the classic graphics, or {@code null} for its installed graphics
     * @param imageIndex   the image identifier
     * @param frameIndex   the image frame, a frame of the anim
     * @param modifier     the image draw modifier
     * @param modifierData the image modifier data
     * @param playerColor  the owner's player color index {@code 0..15}, or {@link #PLAYER_COLOR}{@code + owner}
     * @param worldX       the canvas center world coordinate
     * @param worldY       the canvas center world coordinate
     * @param flipped      whether the image is horizontally flipped
     * @throws IOException if the frame cannot be drawn or the modifier is not drawn by this backend
     */
    @Override
    public void drawImageAnimation(@NotNull TextureSheet animation, @Nullable ScrImageSheet sheet,
                                   int imageIndex, int frameIndex,
                                   int modifier, int modifierData, int playerColor,
                                   int worldX, int worldY, boolean flipped) throws IOException {
        this.beginScene();

        HdImageTextures textures = null;

        if (!this.fogRequested && modifier != DRAW_FUNCTION_UPDATE_RECTANGLE) {
            textures = this.imageSet.acquireSheet(animation,
                                                  this.streaming, UPLOAD_BUDGET_NANOSECONDS, this.frameCounter, this.imageUploadPreparation);
        }

        if (textures == null) {
            if (sheet != null && frameIndex < sheet.getFrameCount()) {
                this.drawImageSheet(sheet, imageIndex, frameIndex, modifier, modifierData, playerColor, worldX, worldY, flipped);
            } else if (sheet == null && this.world.isDrawnFrame(imageIndex, frameIndex)) {
                this.drawImage(imageIndex, frameIndex, modifier, modifierData, playerColor, worldX, worldY, flipped);
            }

            return;
        }

        this.animationTextures  = textures;
        this.animationPlacement = sheet;

        try {
            this.drawSceneImage(imageIndex, frameIndex, modifier, modifierData, playerColor, worldX, worldY, flipped);
        } finally {
            this.animationTextures  = null;
            this.animationPlacement = null;
        }
    }

    /**
     * Draws the frame of a one-frame override sheet at the world size: the frame rectangle placed on the sheet's canvas, which is centered on the
     * image position and mirrored when flipped.
     *
     * @param sheet       the one-frame indexed sheet
     * @param mode        the draw mode
     * @param parameter   the mode parameter
     * @param playerColor the owner's player color index
     * @param worldX      the canvas center world coordinate
     * @param worldY      the canvas center world coordinate
     * @param flipped     whether the image is horizontally flipped
     * @throws IOException if the sheet has no frame or no map is loaded
     */
    private void drawSheetFrame(@NotNull SpriteSheet sheet, int mode, float parameter, int playerColor, int worldX, int worldY, boolean flipped) throws IOException {
        if (sheet.getFrameCount() == 0) {
            throw Exceptions.newIOException("The image sheet has no frame.");
        }

        SpriteFrame frame = sheet.getFrame(0);

        if (frame.getWidth() <= 0 || frame.getHeight() <= 0) {
            return;
        }

        int canvasLeft = worldX - (flipped ? sheet.getWidth() - sheet.getAnchorX() : sheet.getAnchorX());
        int rectangleLeft = flipped ? canvasLeft + sheet.getWidth() - frame.getX() - frame.getWidth() : canvasLeft + frame.getX();
        int rectangleTop = worldY - sheet.getAnchorY() + frame.getY();
        float left = (rectangleLeft - this.cameraX) * this.scale;
        float top = (rectangleTop - this.cameraY) * this.scale;
        float right = left + frame.getWidth() * this.scale;
        float bottom = top + frame.getHeight() * this.scale;

        if (left >= this.world.getViewWidth() || top >= this.world.getViewHeight() || right <= 0 || bottom <= 0) {
            return;
        }

        int texture = this.acquireSheetTexture(sheet, playerColor);
        boolean litSprite = this.lightingEnabled && (mode == MODE_SPRITE || mode == MODE_TRANSLUCENT);

        this.copyTeamColor(playerColor);
        this.vertexFlip     = flipped ? -1 : 1;
        this.vertexLighting = litSprite ? 1 : 0;

        this.selectBatch(mode, texture, this.blackTexture, litSprite ? this.defaultLightingTextures : null);
        this.appendQuad(left, top, right, bottom,
                        flipped ? 1 : 0, 0, flipped ? 0 : 1, 1,
                        0, 0, 0, 0,
                        parameter, 0);
    }

    /**
     * Returns the RGBA texture of the frame of a one-frame override sheet in a palette selection, creating it on first use: each nonzero palette
     * index in its classic color, opaque, index {@code 0} transparent, with nearest filtering. Past {@value #MAXIMUM_SHEET_FRAMES} sheets, the one
     * drawn least recently releases its textures.
     *
     * @param sheet       the one-frame indexed sheet
     * @param playerColor the owner's player color index
     * @return the texture
     * @throws IOException if no map is loaded
     */
    private int acquireSheetTexture(@NotNull SpriteSheet sheet, int playerColor) throws IOException {
        int[] textures = this.sheetTextures.get(sheet);

        if (textures == null) {
            if (this.sheetTextures.size() >= MAXIMUM_SHEET_FRAMES) {
                this.flush();
                deleteTextures(this.sheetTextures.pollFirstEntry().getValue());

                this.batchTexture = 0;
            }

            textures = new int[SHEET_PALETTE_SELECTIONS];
            this.sheetTextures.put(sheet, textures);
        } else {
            this.sheetTextures.putLast(sheet, textures);
        }

        int slot = Math.clamp(playerColor, 0, SHEET_PALETTE_SELECTIONS - 1);

        if (textures[slot] != 0) {
            return textures[slot];
        }

        byte[] palette = this.world.copyPlayerColorPalette(playerColor);

        if (palette == null) {
            throw Exceptions.newIOException("The image sheet needs a loaded map palette.");
        }

        SpriteFrame frame = sheet.getFrame(0);
        int pixelCount = frame.getWidth() * frame.getHeight();
        byte[] indices = new byte[pixelCount];
        ByteBuffer upload = MemoryUtil.memAlloc(pixelCount * OverlayPass.RGBA_BYTES);

        sheet.getColor().copyBytes(frame.getPixelOffset(), indices, 0, pixelCount);

        try {
            for (byte value : indices) {
                int index = value & BYTE_MASK;

                if (index == 0) {
                    upload.putInt(0);
                } else {
                    upload.put(palette, index * OverlayPass.RGBA_BYTES, OverlayPass.RGBA_BYTES - 1).put(OverlayPass.OPAQUE_ALPHA);
                }
            }

            upload.flip();
            this.flush();

            int name = glGenTextures();

            glBindTexture(GL_TEXTURE_2D, name);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAX_LEVEL, 0);
            glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, frame.getWidth(), frame.getHeight(), 0, GL_RGBA, GL_UNSIGNED_BYTE, upload);

            textures[slot] = name;

            this.batchTexture = 0;

            return name;
        } finally {
            MemoryUtil.memFree(upload);
        }
    }

    private void deleteSheetTextures() {
        for (int[] textures : this.sheetTextures.values()) {
            deleteTextures(textures);
        }

        this.sheetTextures.clear();
        this.batchTexture = 0;
    }

    private void drawSceneImage(int imageIndex, int frameIndex,
                                int modifier, int modifierData, int playerColor,
                                int worldX, int worldY, boolean flipped) throws IOException {
        this.copyTeamColor(playerColor);

        boolean installed = this.animationTextures == null;

        if (installed && !this.smoothTurning && frameIndex > 0 && this.world.isPreviousFrameRepeated(imageIndex, frameIndex)) {
            frameIndex--;
        }

        int anchor = installed ? this.assets.getAnchoredOverlayOffset(imageIndex, frameIndex, flipped, this.imageOverlayOffsets) : 0;

        if (anchor != 0) {
            // The offsets are subtracted unmirrored, flipped or not.
            worldX -= (short) anchor;
            worldY -= (short) (anchor >>> PACKED_OFFSET_Y_SHIFT);
        }

        switch (modifier) {
            // Draw functions 0 and 1 (normal) and 14 (original player color: the flag) take the builder's default path.
            // Draw functions 11 (health bar) and 13 (selection circle) are drawn by the scene extraction's bar and circle calls.
            case DRAW_FUNCTION_NORMAL,
                 DRAW_FUNCTION_NORMAL_UNCHECKED,
                 DRAW_FUNCTION_PLAYER_COLOR -> this.drawFrame(imageIndex, frameIndex, MODE_SPRITE, 0, worldX, worldY, flipped, -1);
            // Draw function 15 (update rectangle) draws the frame's rectangle outline, not the frame.
            case DRAW_FUNCTION_UPDATE_RECTANGLE -> this.drawUpdateRectangle(imageIndex, frameIndex, worldX, worldY, flipped);
            case DRAW_FUNCTION_HALLUCINATION -> this.drawFrame(imageIndex, frameIndex, MODE_SPRITE, 1, worldX, worldY, flipped, -1);
            // Draws unseen cloak distortion separately from the owner-visible half-alpha sprite and fade threshold.
            case DRAW_FUNCTION_UNSEEN_CLOAKING, DRAW_FUNCTION_UNSEEN_DECLOAKING -> {
                this.drawFrame(imageIndex, frameIndex, MODE_CLOAKED, 0, worldX, worldY, flipped, -1);
                this.drawFrame(imageIndex,
                               frameIndex,
                               MODE_PART_SOLID,
                               (modifierData & BYTE_MASK) / CLOAK_THRESHOLD_DIVISOR,
                               worldX,
                               worldY,
                               flipped,
                               -1);
            }
            case DRAW_FUNCTION_UNSEEN_CLOAKED -> this.drawFrame(imageIndex, frameIndex, MODE_CLOAKED, 0, worldX, worldY, flipped, -1);
            case DRAW_FUNCTION_CLOAKING, DRAW_FUNCTION_DECLOAKING -> {
                this.drawFrame(imageIndex, frameIndex, MODE_TRANSLUCENT, VISIBLE_CLOAK_ALPHA, worldX, worldY, flipped, -1);
                this.drawFrame(imageIndex,
                               frameIndex,
                               MODE_PART_SOLID,
                               (modifierData & BYTE_MASK) / CLOAK_THRESHOLD_DIVISOR,
                               worldX,
                               worldY,
                               flipped,
                               -1);
            }
            case DRAW_FUNCTION_CLOAKED -> this.drawFrame(imageIndex, frameIndex, MODE_TRANSLUCENT, VISIBLE_CLOAK_ALPHA, worldX, worldY, flipped, -1);
            case DRAW_FUNCTION_DISTORTION -> this.drawFrame(imageIndex, frameIndex, MODE_WARPED, 0, worldX, worldY, flipped, -1);
            // Draws remapped glow as a lit alpha-blended sprite using its own emissive and lighting layers.
            case DRAW_FUNCTION_REMAPPING -> this.drawFrame(imageIndex, frameIndex, MODE_SPRITE, 0, worldX, worldY, flipped, -1);
            // Selects stacked solid-black shadows or the effect-target shadow.
            case DRAW_FUNCTION_SHADOW -> this.drawShadowImage(imageIndex, frameIndex, -1, worldX, worldY, flipped);
            case DRAW_FUNCTION_WARP_TEXTURE -> this.drawFrame(imageIndex,
                                                              frameIndex,
                                                              MODE_MAPPED,
                                                              0,
                                                              worldX,
                                                              worldY,
                                                              flipped,
                                                              modifierData & WORD_MASK);
            // Draws the warp flash as a white brighten pass; the building below remains a separate image.
            case DRAW_FUNCTION_WARP_FLASH -> {
                float brighten = SceneRenderer.getBrightenAlpha(modifierData);

                if (brighten > 0) {
                    this.drawFrame(imageIndex, frameIndex, MODE_BRIGHTEN, brighten, worldX, worldY, flipped, -1);
                }
            }
            default -> throw Exceptions.newIOException("Runtime image modifier {0} is not rendered.", modifier);
        }
    }

    /**
     * Draws an image of draw function {@code 10} (a shadow). Without Shadow Stacking the draw builder dims the shadow of a warping-in sprite: when
     * the image's {@code images.rel} type is {@link #SHADOW_RELATION} and the sprite's primary image has draw function {@code 12}, the shadow's
     * multiply alpha is that image's data (the warp texture frame) over the frame count of the Warp Texture's anim
     * ({@link SceneRenderer#getShadowWarpAlpha}).
     * <p>
     * This backend draws stacked shadows as solid black at alpha {@code 0.5} per shadow. Otherwise it writes the effect-target shadow with the
     * warp-frame multiply alpha.
     *
     * @param imageIndex       the image identifier
     * @param frameIndex       the image frame
     * @param primaryWarpFrame the primary image's modifier data when its draw function is {@code 12}, else {@code -1}
     * @param worldX           the canvas center world coordinate
     * @param worldY           the canvas center world coordinate
     * @param flipped          whether the image is horizontally flipped
     * @throws IOException if the image cannot be loaded
     */
    @Override
    public void drawShadowImage(int imageIndex, int frameIndex, int primaryWarpFrame, int worldX, int worldY, boolean flipped) throws IOException {
        this.beginScene();

        if (this.world.isShadowStacking()) {
            this.drawFrame(imageIndex, frameIndex, MODE_STACKED_SHADOW, STACKED_SHADOW_ALPHA, worldX, worldY, flipped, -1);

            return;
        }

        float multiplyAlpha = 1;

        if (primaryWarpFrame >= 0) {
            // The builder loads the Warp Texture anim for its frame count; a streamed set may still be reading it, and the
            // shadow then draws undimmed for that frame.
            HdImageTextures warp = this.acquireImage(WARP_TEXTURE_IMAGE);

            multiplyAlpha = SceneRenderer.getShadowWarpAlpha(this.imageSet.getAnimations().getRelations().getType(imageIndex),
                                                             primaryWarpFrame,
                                                             warp == null ? 0 : warp.geometry.getFrameCount());
        }

        this.drawFrame(imageIndex, frameIndex, MODE_SHADOW, Math.clamp(multiplyAlpha, 0, 1), worldX, worldY, flipped, -1);
    }

    /**
     * Draws a building placement ghost: one image frame with RGB {@code (1, 1, 1)} at alpha {@code 0.5}, colored with the local player's team color,
     * without a shadow.
     * <p>
     * The ghost is draw function {@code 14}; draw functions above {@code 10} are drawn in pass {@code 0}, unlit, which samples the anim's
     * {@code diffuse} layer even when the lit pass is on (where sprites sample {@code bright}). The placement cells are drawn over it.
     *
     * @param imageIndex  the unit's image identifier
     * @param frameIndex  the image frame
     * @param playerColor the local player's color index
     * @param worldX      the canvas center world coordinate
     * @param worldY      the canvas center world coordinate
     * @throws IOException if the image cannot be loaded
     */
    @Override
    public void drawPlacementGhost(int imageIndex, int frameIndex, int playerColor, int worldX, int worldY) throws IOException {
        this.beginScene();
        this.copyTeamColor(playerColor);
        this.drawFrame(imageIndex, frameIndex, MODE_GHOST, 0, worldX, worldY, false, -1);
    }

    /**
     * Draws a selection circle centered at a sprite's circle position; the caller draws it at the place the circle keeps among the images of the
     * selected sprite.
     * <p>
     * The ring uses the HD frame alpha with one solid relation color.
     *
     * @param circleImage the circle image: {@code 561 + sprites.dat selection circle} (dashed circles follow at {@code 571})
     * @param relation    {@link #RELATION_OWN}, {@link #RELATION_NEUTRAL} or {@link #RELATION_ENEMY}
     * @param worldX      the sprite x coordinate
     * @param worldY      the sprite y coordinate plus the {@code sprites.dat} selection-circle vertical offset
     * @throws IOException if the circle image is not a selection circle or cannot be loaded
     */
    @Override
    public void drawSelectionCircle(int circleImage, int relation, int worldX, int worldY) throws IOException {
        if (circleImage < OverlayAssets.FIRST_CIRCLE_IMAGE || circleImage >= OverlayAssets.FIRST_CIRCLE_IMAGE + OverlayAssets.CIRCLE_IMAGE_COUNT) {
            throw Exceptions.newIOException("Invalid selection circle {0}", circleImage);
        }

        this.drawRelationImage(circleImage, 0, relation, worldX, worldY, false);
    }

    /**
     * Draws an image with draw function {@code 13}: the frame's shape in one solid color, the relation color of the player in the image's modifier
     * data. Selection circles are such images, and EUD maps can give any image this draw function.
     * <p>
     * This backend draws the HD frame alpha in the relation color.
     *
     * @param imageIndex the image identifier
     * @param frameIndex the image frame
     * @param relation   {@link #RELATION_OWN}, {@link #RELATION_NEUTRAL} or {@link #RELATION_ENEMY}
     * @param worldX     the canvas center world coordinate
     * @param worldY     the canvas center world coordinate
     * @param flipped    whether the image is horizontally flipped
     * @throws IOException if the image cannot be loaded or the relation is invalid
     */
    @Override
    public void drawRelationImage(int imageIndex, int frameIndex, int relation, int worldX, int worldY, boolean flipped) throws IOException {
        if (relation < 0 || relation > RELATION_ENEMY) {
            throw Exceptions.newIOException("Invalid relation {0}", relation);
        }

        this.beginScene();
        this.setTeamColor(this.assets.getOverlayAssets().getRelationColor(relation));
        this.drawFrame(imageIndex, frameIndex, MODE_TINT, 0, worldX, worldY, flipped, -1);
    }

    /**
     * Draws the frame rectangle of draw function {@code 15} as a one-pixel outline in {@link #UPDATE_RECTANGLE_COLOR}. The frame image itself is not
     * drawn.
     *
     * @param imageIndex the image identifier
     * @param frameIndex the image frame
     * @param worldX     the canvas center world coordinate
     * @param worldY     the canvas center world coordinate
     * @param flipped    whether the image is horizontally flipped
     * @throws IOException if the image cannot be loaded
     */
    @Override
    public void drawUpdateRectangle(int imageIndex, int frameIndex, int worldX, int worldY, boolean flipped) throws IOException {
        ClassicFrame frame = this.world.decodeImageFrame(imageIndex, frameIndex);
        float[] rectangle = frame == null ? null : SceneRenderer.getFrameRectangle(frame, worldX, worldY, flipped);

        if (rectangle == null) {
            return;
        }

        this.beginScene();

        float left = rectangle[RECTANGLE_LEFT];
        float top = rectangle[RECTANGLE_TOP];
        float width = rectangle[RECTANGLE_WIDTH];
        float height = rectangle[RECTANGLE_HEIGHT];
        float sideHeight = height - OUTLINE_ROWS * OUTLINE_WIDTH;

        this.fillBarRectangle(left, top, width, OUTLINE_WIDTH, UPDATE_RECTANGLE_COLOR);
        this.fillBarRectangle(left, top + height - OUTLINE_WIDTH, width, OUTLINE_WIDTH, UPDATE_RECTANGLE_COLOR);
        this.fillBarRectangle(left, top + OUTLINE_WIDTH, OUTLINE_WIDTH, sideHeight, UPDATE_RECTANGLE_COLOR);
        this.fillBarRectangle(left + width - OUTLINE_WIDTH, top + OUTLINE_WIDTH, OUTLINE_WIDTH, sideHeight, UPDATE_RECTANGLE_COLOR);
    }

    /**
     * Draws a health bar centered at the place of its health bar image; the caller draws it at the place the image keeps among the images of the
     * selected sprite.
     * <p>
     * The bar uses classic pixel rows and {@code thpbar.pcx} colors at the view scale.
     *
     * @param worldX           the bar center world coordinate: the sprite x coordinate plus the image's horizontal offset
     * @param worldY           the bar center world coordinate: the sprite y coordinate plus the image's vertical offset
     * @param barWidth         the {@code sprites.dat} health bar width
     * @param hitPointsPercent the hit point percentage
     * @param shieldPercent    the shield percentage, or a negative value when the unit type has no shields
     * @param energyPercent    the energy or removal-timer percentage, or a negative value without an energy row
     * @throws IOException if the color tables cannot be loaded
     */
    @Override
    public void drawHealthBar(int worldX, int worldY, int barWidth, int hitPointsPercent, int shieldPercent, int energyPercent) throws IOException {
        this.beginScene();
        this.barLayout.configure(barWidth, hitPointsPercent, shieldPercent, energyPercent);

        this.barLeft = worldX - this.barLayout.getWidth() / RenderAssets.HALF_DIVISOR;
        this.barTop  = worldY - this.barLayout.getHeight() / RenderAssets.HALF_DIVISOR;

        // Draws classic pixel bar rows and palette colors at the view scale.
        this.barLayout.emit(this.barSink);
    }

    private void fillBarRectangle(float left, float top, float width, float height, int color) {
        this.setTeamColor(color);
        this.selectBatch(MODE_SOLID, this.blackTexture, this.blackTexture, null);

        float screenLeft = (left - this.cameraX) * this.scale;
        float screenTop = (top - this.cameraY) * this.scale;

        this.appendQuad(screenLeft, screenTop, screenLeft + width * this.scale, screenTop + height * this.scale, 0, 0, 1, 1, 0, 0, 0, 0, 0, 0);
    }

    private void setTeamColor(int color) {
        this.teamColor[0]                      = (color >>> OverlayPass.RGB_RED_SHIFT & BYTE_MASK) / OverlayPass.CHANNEL_MAXIMUM;
        this.teamColor[OverlayPass.RGBA_GREEN] = (color >>> OverlayPass.RGB_GREEN_SHIFT & BYTE_MASK) / OverlayPass.CHANNEL_MAXIMUM;
        this.teamColor[OverlayPass.RGBA_BLUE]  = (color & BYTE_MASK) / OverlayPass.CHANNEL_MAXIMUM;
    }

    /**
     * Sets the team color of a player color: a color index's, or a player's live row ({@link #setPlayerColorRows}).
     *
     * @param playerColor the player color index, or {@link #PLAYER_COLOR}{@code + player}
     */
    private void copyTeamColor(int playerColor) {
        if (playerColor >= PLAYER_COLOR) {
            System.arraycopy(this.playerTeamColors,
                             Math.min(playerColor - PLAYER_COLOR, PLAYER_COLOR_ROWS - 1) * HdRenderAssets.COLOR_COMPONENTS,
                             this.teamColor,
                             0,
                             HdRenderAssets.COLOR_COMPONENTS);
        } else {
            this.assets.copyTeamColor(playerColor, this.teamColor, 0);
        }
    }

    /**
     * Darkens the world by the fog of war with the game's level of visible tiles; this is the last world drawing of a scene.
     * <p>
     * An observer slot darkens through the observer table, whose alpha stops at {@code 218}, so unexplored ground stays faintly visible. The replay
     * viewer is not an observer (its local human is a player slot).
     * <p>
     * This backend applies the fog to the resolved scene in {@link #finishScene()}, with the calculation of {@link OverlayPass}.
     *
     * @param tileFlags    the current tile flag DWORD for each row-major tile index (low byte: not visible per player, second byte: not explored per
     *                     player)
     * @param visionMask   the eight-bit mask of players whose vision is shown
     * @param visibleLevel the fog level of visible tiles, the game's visible level BYTE (default {@code 31}); explored tiles get half of it
     * @param observer     whether the local human is an observer slot ({@code 0x80..0x83})
     */
    @Override
    public void drawFog(@NotNull IntUnaryOperator tileFlags, int visionMask, int visibleLevel, boolean observer) {
        this.beginScene();
        this.fogTileFlags    = tileFlags;
        this.fogVisionMask   = visionMask;
        this.fogVisibleLevel = visibleLevel;
        this.fogObserver     = observer;
        this.fogRequested    = true;
    }

    private void drawFrame(int imageIndex, int frameIndex, int mode, float parameter, int worldX, int worldY, boolean flipped, int warpFrame) throws IOException {
        HdImageTextures animation = this.animationTextures;
        // Draw function 14 (the placement ghost) is a pass 0 draw that samples the diffuse layer (HdImageSet.acquireDiffuse).
        HdImageTextures textures = animation != null ? animation
                                                     : mode == MODE_GHOST
                                                       ? this.imageSet.acquireDiffuse(imageIndex,
                                                                                      this.streaming,
                                                                                      UPLOAD_BUDGET_NANOSECONDS,
                                                                                      this.frameCounter,
                                                                                      this.imageUploadPreparation)
                                                       : this.acquireImage(imageIndex);

        if (textures == null) {
            // Not streamed in yet (no preload): its point light still shines once the image has been read.
            HdFrameGeometry geometry = this.imageSet.getGeometry(imageIndex);

            if (geometry != null && this.lightingEnabled && this.lighting != null && frameIndex >= 0 && frameIndex < geometry.getFrameCount() &&
                (mode == MODE_SPRITE || mode == MODE_PART_SOLID || mode == MODE_TRANSLUCENT)) {
                long origin = this.getRectangleOrigin(geometry, imageIndex, frameIndex, worldX, worldY, flipped);

                this.addFrameLight(imageIndex,
                                   frameIndex,
                                   (int) (origin >> ORIGIN_LEFT_SHIFT),
                                   (int) origin,
                                   geometry.getFrameWidth(frameIndex),
                                   flipped);
            }

            return;
        }

        HdFrameGeometry geometry = textures.geometry;

        if (frameIndex < 0 || frameIndex >= geometry.getFrameCount()) {
            throw Exceptions.newIOException("Image {0} has no HD frame {1}", imageIndex, frameIndex);
        }

        int frameWidth = geometry.getFrameWidth(frameIndex);
        int frameHeight = geometry.getFrameHeight(frameIndex);
        long origin = this.getRectangleOrigin(geometry, imageIndex, frameIndex, worldX, worldY, flipped);
        int rectangleLeft = (int) (origin >> ORIGIN_LEFT_SHIFT);
        int rectangleTop = (int) origin;
        float left = (rectangleLeft / FRAME_UNIT_SCALE - this.cameraX) * this.scale;
        float top = (rectangleTop / FRAME_UNIT_SCALE - this.cameraY) * this.scale;
        float right = left + frameWidth / FRAME_UNIT_SCALE * this.scale;
        float bottom = top + frameHeight / FRAME_UNIT_SCALE * this.scale;

        if (left >= this.world.getViewWidth() || top >= this.world.getViewHeight() || right <= 0 || bottom <= 0) {
            return;
        }

        float textureLeft = geometry.getFrameX(frameIndex) / textures.textureWidth;
        float textureRight = (geometry.getFrameX(frameIndex) + frameWidth) / textures.textureWidth;
        float textureTop = geometry.getFrameY(frameIndex) / textures.textureHeight;
        float textureBottom = (geometry.getFrameY(frameIndex) + frameHeight) / textures.textureHeight;
        int secondTexture = textures.teamColorTexture != 0 ? textures.teamColorTexture : this.blackTexture;
        float mapLeft = 0;
        float mapTop = 0;
        float mapRight = 0;
        float mapBottom = 0;

        if (mode == MODE_MAPPED) {
            HdImageTextures warp = this.acquireImage(WARP_TEXTURE_IMAGE);

            if (warp == null) {
                return;
            }

            if (warpFrame >= warp.geometry.getFrameCount()) {
                throw Exceptions.newIOException("The HD warp texture has no frame {0}", warpFrame);
            }

            secondTexture = warp.diffuseTexture;
            mapLeft       = warp.geometry.getFrameX(warpFrame) / warp.textureWidth;
            mapTop        = warp.geometry.getFrameY(warpFrame) / warp.textureHeight;
            mapRight      = (warp.geometry.getFrameX(warpFrame) + frameWidth) / warp.textureWidth;
            mapBottom     = (warp.geometry.getFrameY(warpFrame) + frameHeight) / warp.textureHeight;
        }

        boolean litSprite = this.lightingEnabled && (mode == MODE_SPRITE || mode == MODE_PART_SOLID || mode == MODE_TRANSLUCENT);
        int spriteTexture = textures.diffuseTexture;
        int[] lightingLayers = litSprite ? textures.lightingTextures : null;

        if (litSprite && (!textures.lightingData || (geometry.getFrameFlags(frameIndex) & HdImageLoader.FRAME_UNLIT) != 0)) {
            // Uses the diffuse texture and default lighting layers when a frame has no complete lighting data. A native frame drops its team
            // color mask there; an image a mod adds and an override anim keep it, so a mod unit without lighting layers keeps its team color.
            spriteTexture  = textures.unlitTexture;
            secondTexture  = textures.keepsTeamColor ? secondTexture : this.blackTexture;
            lightingLayers = this.defaultLightingTextures;
        }

        this.vertexFlip     = flipped ? -1 : 1;
        this.vertexLighting = litSprite ? 1 : 0;

        // Main.lit lights belong to the image frame of every lit-pass draw (fire, muzzle flashes and trails included).
        if (this.lighting != null && litSprite && animation == null) {
            this.addFrameLight(imageIndex, frameIndex, rectangleLeft, rectangleTop, frameWidth, flipped);
        }

        this.selectBatch(mode, spriteTexture, secondTexture, lightingLayers);
        this.appendQuad(left, top, right, bottom,
                        flipped ? textureRight : textureLeft, textureTop, flipped ? textureLeft : textureRight, textureBottom,
                        mapLeft, mapTop, mapRight, mapBottom,
                        parameter, 0);
    }

    /**
     * Returns the top-left corner of an HD frame rectangle in HD (scale-{@code 4}) world pixels. A frame with {@link #FRAME_CANVAS_PLACED} (not of
     * draw function {@code 15}) is placed by its anim canvas: the canvas is centered on the image position with the classic integer halving and
     * mirrored when flipped (offset {@code x - width / 2}, flipped {@code width / 2 - x}). Any other frame is an SD frame scaled by {@code 4}, drawn
     * from the image's map position, the top-left corner of its classic GRP frame rectangle.
     *
     * @param geometry   the image's HD frame geometry
     * @param imageIndex the image identifier
     * @param frameIndex the frame
     * @param worldX     the image position (canvas center) in world pixels
     * @param worldY     the image position (canvas center) in world pixels
     * @param flipped    whether the image is horizontally flipped
     * @return the packed corner (left in the high {@code 32} bits, top in the low {@code 32} bits)
     * @throws IOException if the classic frame of a map-placed frame is unavailable
     */
    private long getRectangleOrigin(@NotNull HdFrameGeometry geometry, int imageIndex, int frameIndex, int worldX, int worldY, boolean flipped) throws IOException {
        int left;
        int top;

        long sheetOffset = this.animationTextures != null && (geometry.getFrameFlags(frameIndex) & FRAME_CANVAS_PLACED) == 0
                           ? this.findPlacementOffset(frameIndex, flipped)
                           : NO_PLACEMENT;

        if ((geometry.getFrameFlags(frameIndex) & FRAME_CANVAS_PLACED) != 0 || this.animationTextures != null && sheetOffset == NO_PLACEMENT) {
            int canvasWidth = geometry.getCanvasWidth();
            int canvasLeft = worldX * HdFrameGeometry.UNIT_SCALE - (flipped ? canvasWidth - canvasWidth / RenderAssets.HALF_DIVISOR
                                                                            : canvasWidth / RenderAssets.HALF_DIVISOR);

            left = flipped ? canvasLeft + canvasWidth - geometry.getFrameXOffset(frameIndex) - geometry.getFrameWidth(frameIndex)
                           : canvasLeft + geometry.getFrameXOffset(frameIndex);
            top  = worldY * HdFrameGeometry.UNIT_SCALE - geometry.getCanvasHeight() / RenderAssets.HALF_DIVISOR +
                   geometry.getFrameYOffset(frameIndex);
        } else {
            int offset = sheetOffset != NO_PLACEMENT ? (int) sheetOffset : this.world.getFrameMapOffset(imageIndex, frameIndex, flipped);

            left = (worldX + (short) offset) * HdFrameGeometry.UNIT_SCALE;
            top  = (worldY + (short) (offset >>> PACKED_OFFSET_Y_SHIFT)) * HdFrameGeometry.UNIT_SCALE;
        }

        return (long) left << ORIGIN_LEFT_SHIFT | top & DWORD_MASK;
    }

    /**
     * Finds the offset of an override sheet frame's rectangle from the image position, which places an override anim frame without a canvas
     * placement, as {@link WorldRenderer#getFrameMapOffset} gives it for installed frames.
     *
     * @param frameIndex the frame
     * @param flipped    whether the image is horizontally flipped
     * @return the packed offset (x in the low WORD, y in the high WORD), or {@link #NO_PLACEMENT} without an override sheet frame
     */
    private long findPlacementOffset(int frameIndex, boolean flipped) {
        ScrImageSheet placement = this.animationPlacement;

        if (placement == null || frameIndex >= placement.getFrameCount()) {
            return NO_PLACEMENT;
        }

        try {
            SpriteSheet sheet = placement.getFrame(frameIndex);
            SpriteFrame frame = sheet.getFrame(0);
            int x = flipped ? sheet.getAnchorX() - frame.getX() - frame.getWidth() : frame.getX() - sheet.getAnchorX();
            int y = frame.getY() - sheet.getAnchorY();

            return x & WORD_MASK | (y & WORD_MASK) << PACKED_OFFSET_Y_SHIFT;
        } catch (IOException exception) {
            return NO_PLACEMENT;
        }
    }

    /**
     * Adds the image frame light at coordinates relative to the frame rectangle, with its radius and intensity.
     *
     * @param imageIndex    the image identifier
     * @param frameIndex    the image frame
     * @param rectangleLeft the left edge of the frame rectangle in scale-{@code 4} world pixels
     * @param rectangleTop  the top edge of the frame rectangle in scale-{@code 4} world pixels
     * @param frameWidth    the frame width in scale-{@code 4} pixels
     * @param flipped       whether the image is horizontally flipped
     */
    private void addFrameLight(int imageIndex, int frameIndex, int rectangleLeft, int rectangleTop, int frameWidth, boolean flipped) {
        AnimLights lights = this.assets.getLights(imageIndex);

        if (!lights.hasLight(imageIndex, frameIndex)) {
            return;
        }

        int lightX = lights.getX(imageIndex, frameIndex);
        int worldLightX = rectangleLeft + (flipped ? frameWidth - lightX : lightX);
        int worldLightY = rectangleTop + lights.getY(imageIndex, frameIndex);
        float screenX = (worldLightX / FRAME_UNIT_SCALE - this.cameraX) * this.scale;
        float screenY = (worldLightY / FRAME_UNIT_SCALE - this.cameraY) * this.scale;
        // Converts frame coordinates to screen pixels and scales the radius by the view scale.
        float radius = lights.getRadius(imageIndex, frameIndex) * this.scale;

        if (screenX + radius < 0 ||
            screenY + radius < 0 ||
            screenX - radius > this.world.getViewWidth() ||
            screenY - radius > this.world.getViewHeight()) {
            return;
        }

        this.lighting.addLight(screenX,
                               screenY,
                               radius,
                               lights.getColor(imageIndex, frameIndex),
                               lights.getIntensity(imageIndex, frameIndex) / LIGHT_INTENSITY_DIVISOR);
    }

    /**
     * Completes the scene so that the {@link WorldRenderer} can resolve the world and draw overlays.
     * <p>
     * Resolves the HD color and effect targets into the world frame and restores the world renderer's state. Does nothing when nothing was drawn.
     */
    @Override
    public void finishScene() {
        if (!this.sceneActive) {
            return;
        }

        this.flush();

        if (STATISTICS && this.frameCounter % STATISTICS_INTERVAL == 0) {
            LOGGER.debug("HD STATS: frame {0}, {1} draw calls per frame, {2} MB textures, by mode {3}",
                         this.frameCounter,
                         this.drawCalls / this.frameCounter,
                         this.imageSet.getTextureBytes() >> HdImageSet.MEBIBYTE_SHIFT,
                         Arrays.toString(Arrays.stream(this.modeDrawCalls).map(count -> count / this.frameCounter).toArray()));
        }

        this.sceneActive = false;

        for (int attachment = 0; attachment < HdLighting.ATTACHMENT_COUNT; attachment++) {
            glColorMaski(attachment, true, true, true, true);
        }

        if (this.lightingEnabled && this.lighting != null) {
            this.lighting.resolve(this.sceneColorTexture,
                                  this.sceneEffectTexture,
                                  this.world.getWorldFramebuffer(),
                                  this.world.getViewWidth(),
                                  this.world.getViewHeight(),
                                  this.scale);
            glBindVertexArray(this.vertexArray);
            this.finishFog();

            return;
        }

        glBindFramebuffer(GL_FRAMEBUFFER, this.world.getWorldFramebuffer());
        glViewport(0, 0, this.sceneWidth, this.sceneHeight);
        glColorMaski(WorldRenderer.INDEX_TARGET, false, false, false, false);
        glColorMaski(WorldRenderer.COLOR_TARGET, true, true, true, true);
        glColorMaski(WorldRenderer.EFFECT_TARGET, true, true, true, true);
        glDisable(GL_BLEND);
        glEnable(GL_SCISSOR_TEST);
        glScissor(0, this.sceneHeight - this.world.getViewHeight(), this.world.getViewWidth(), this.world.getViewHeight());
        this.compositeProgram.use();
        glUniform2f(this.compositeFramebufferUniform, this.sceneWidth, this.sceneHeight);
        glUniform1f(this.spriteWidthUniform, 1.0f / this.sceneWidth);
        glActiveTexture(GL_TEXTURE0 + TEAM_COLOR_UNIT);
        glBindTexture(GL_TEXTURE_2D, this.sceneEffectTexture);
        glActiveTexture(GL_TEXTURE0 + SPRITE_UNIT);
        glBindTexture(GL_TEXTURE_2D, this.sceneColorTexture);
        this.vertices.clear();
        this.appendQuad(0, 0, this.sceneWidth, this.sceneHeight, 0, 1, 1, 0, 0, 0, 0, 0, 0, 0);
        this.vertices.flip();
        glBindVertexArray(this.vertexArray);
        glBindBuffer(GL_ARRAY_BUFFER, this.vertexBuffer);
        glDrawArrays(GL_TRIANGLES, this.writeVertexRing(), GraphicsDevice.QUAD_VERTICES);
        this.vertices.clear();
        glColorMaski(WorldRenderer.INDEX_TARGET, true, true, true, true);
        this.finishFog();
    }

    private void finishFog() {
        if (this.fogTileFlags != null) {
            this.overlay.begin(true);
            this.overlay.drawFog(this.fogTileFlags,
                                 this.world.getMapWidth(),
                                 this.world.getMapHeight(),
                                 this.fogVisionMask,
                                 this.fogVisibleLevel,
                                 this.fogObserver);
            this.overlay.end(true);
            this.fogTileFlags = null;
        }

        this.drawPostFogImages();
        this.world.endExternalWorldPass();
    }

    /**
     * Draws the images submitted after the fog straight into the world frame with the forward sprite path; only the color target (and the effect
     * target, cleared below the image) are written.
     */
    private void drawPostFogImages() {
        if (this.postFogImages.isEmpty()) {
            return;
        }

        glBindFramebuffer(GL_FRAMEBUFFER, this.world.getWorldFramebuffer());
        glViewport(0, 0, this.sceneWidth, this.sceneHeight);
        glEnable(GL_SCISSOR_TEST);
        glScissor(0, this.sceneHeight - this.world.getViewHeight(), this.world.getViewWidth(), this.world.getViewHeight());

        for (int attachment = WorldRenderer.INDEX_TARGET; attachment < GraphicsDevice.MAXIMUM_COLOR_TARGETS; attachment++) {
            glColorMaski(attachment, false, false, false, false);
        }

        this.program.use();
        glUniform2f(this.framebufferUniform, this.sceneWidth, this.sceneHeight);
        glUniform1i(this.deferredUniform, 0);
        glBindVertexArray(this.vertexArray);
        this.vertices.clear();
        this.batchMode    = -1;
        this.batchTexture = 0;

        boolean lit = this.lightingEnabled;

        this.lightingEnabled = false;

        try {
            for (int[] image : this.postFogImages) {
                try {
                    this.drawSceneImage(image[POST_FOG_IMAGE], image[POST_FOG_FRAME],
                                        image[POST_FOG_MODIFIER], image[POST_FOG_DATA], image[POST_FOG_COLOR],
                                        image[POST_FOG_X], image[POST_FOG_Y], image[POST_FOG_FLIPPED] != 0);
                } catch (IOException ignored) {
                    // Missing marker art only skips the marker.
                }
            }

            this.flush();
        } finally {
            this.lightingEnabled = lit;
            this.postFogImages.clear();

            for (int attachment = 0; attachment < GraphicsDevice.MAXIMUM_COLOR_TARGETS; attachment++) {
                glColorMaski(attachment, true, true, true, true);
            }
        }
    }

    private void beginScene() {
        if (this.sceneActive) {
            return;
        }

        if (this.closed) {
            throw Exceptions.newIllegalStateException("The HD renderer is closed.");
        }

        this.world.beginExternalWorldPass();
        this.sceneActive = true;
        this.frameCounter++;
        this.imageSet.beginFrame();
        this.fogRequested = false;
        this.postFogImages.clear();

        this.cameraX = this.world.getCameraX();
        this.cameraY = this.world.getCameraY();
        this.scale   = this.world.getScale();

        int width = this.world.getFramebufferWidth();
        int height = this.world.getFramebufferHeight();

        if (this.sceneFramebuffer == 0) {
            this.sceneFramebuffer   = glGenFramebuffers();
            this.sceneColorTexture  = glGenTextures();
            this.sceneEffectTexture = glGenTextures();
        }

        glBindFramebuffer(GL_FRAMEBUFFER, this.sceneFramebuffer);

        if (width != this.sceneWidth || height != this.sceneHeight) {
            for (int texture : new int[]{this.sceneColorTexture, this.sceneEffectTexture}) {
                glBindTexture(GL_TEXTURE_2D, texture);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
                glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
            }

            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, this.sceneColorTexture, 0);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT1, GL_TEXTURE_2D, this.sceneEffectTexture, 0);

            if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
                throw Exceptions.newIllegalStateException("The HD scene framebuffer is incomplete.");
            }

            this.sceneWidth  = width;
            this.sceneHeight = height;
        }

        glViewport(0, 0, width, height);
        glDisable(GL_SCISSOR_TEST);
        glColorMask(true, true, true, true);

        if (this.lightingEnabled && this.lighting != null) {
            this.lighting.attachTargets(width, height);
        } else {
            for (int attachment = HdLighting.EMISSIVE_ATTACHMENT; attachment < HdLighting.ATTACHMENT_COUNT; attachment++) {
                glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0 + attachment, GL_TEXTURE_2D, 0, 0);
            }

            GraphicsDevice.getCurrent().selectColorTargets(SCENE_TARGET_COUNT);
        }

        glClearBufferfv(GL_COLOR, WorldRenderer.COLOR_TARGET, CLEAR_COLOR);
        glClearBufferfv(GL_COLOR, WorldRenderer.EFFECT_TARGET, CLEAR_EFFECT);
        glEnable(GL_SCISSOR_TEST);
        glScissor(0, height - this.world.getViewHeight(), this.world.getViewWidth(), this.world.getViewHeight());
        this.program.use();
        glUniform2f(this.framebufferUniform, width, height);
        glUniform1i(this.deferredUniform, this.lightingEnabled ? 1 : 0);
        glBindVertexArray(this.vertexArray);
        this.vertices.clear();
        this.batchMode    = -1;
        this.batchTexture = 0;
    }

    /**
     * Acquires an image with the scene's streaming budget and flushes pending vertices before an upload.
     *
     * @param imageIndex the image identifier
     * @return the prepared texture set, or {@code null} while streaming or when the image is absent
     * @throws IOException if image preparation or upload fails
     */
    private @Nullable HdImageTextures acquireImage(int imageIndex) throws IOException {
        return this.imageSet.acquire(imageIndex, this.streaming, UPLOAD_BUDGET_NANOSECONDS, this.frameCounter, this.imageUploadPreparation);
    }

    private void beforeImageUpload() {
        this.flush();
        this.batchTexture = 0;
    }

    private int getMegatileSlot(int megatile) throws IOException {
        int slot = this.megatileSlots[megatile];

        if (slot >= 0) {
            return slot;
        }

        this.flush();

        int arrayIndex = this.megatileCount / ARRAY_LAYERS;
        int layer = this.megatileCount % ARRAY_LAYERS;
        DdsFile texture = this.assets.getMegatiles().getFrame(megatile);
        int format = HdImageLoader.getInternalFormat(texture, this.compressedTextures);

        if (arrayIndex == this.megatileArrayCount) {
            int arrayTexture = glGenTextures();

            glBindTexture(GL_TEXTURE_2D_ARRAY, arrayTexture);
            glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAX_LEVEL, 0);

            if (format != GL_RGBA8) {
                int layerSize = texture.getLevelSize(0);

                glCompressedTexImage3D(GL_TEXTURE_2D_ARRAY,
                                       0,
                                       format,
                                       texture.getWidth(),
                                       texture.getHeight(),
                                       ARRAY_LAYERS,
                                       0,
                                       layerSize * ARRAY_LAYERS,
                                       0L);
            } else {
                glTexImage3D(GL_TEXTURE_2D_ARRAY,
                             0,
                             GL_RGBA8,
                             texture.getWidth(),
                             texture.getHeight(),
                             ARRAY_LAYERS,
                             0,
                             GL_RGBA,
                             GL_UNSIGNED_BYTE,
                             (ByteBuffer) null);
            }

            this.megatileArrays[arrayIndex] = arrayTexture;
            this.megatileArrayCount++;
        }

        glBindTexture(GL_TEXTURE_2D_ARRAY, this.megatileArrays[arrayIndex]);

        if (format != GL_RGBA8) {
            ByteBuffer upload = MemoryUtil.memAlloc(texture.getLevelSize(0));

            try {
                texture.copyLevel(0, upload);
                upload.flip();
                glCompressedTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0, 0, 0, layer, texture.getWidth(), texture.getHeight(), 1, format, upload);
            } finally {
                MemoryUtil.memFree(upload);
            }
        } else {
            byte[] pixels = texture.decodeRgba();
            ByteBuffer upload = MemoryUtil.memAlloc(pixels.length);

            try {
                upload.put(pixels).flip();
                glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
                glTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0, 0, 0, layer, texture.getWidth(), texture.getHeight(), 1, GL_RGBA, GL_UNSIGNED_BYTE, upload);
            } finally {
                MemoryUtil.memFree(upload);
            }
        }

        slot                         = arrayIndex << SLOT_ARRAY_SHIFT | layer;
        this.megatileSlots[megatile] = slot;
        this.megatileCount++;
        this.batchTexture = 0;

        return slot;
    }

    private int uploadTexture(@NotNull DdsFile texture) {
        int format = HdImageLoader.getInternalFormat(texture, this.compressedTextures);
        int name = glGenTextures();

        glBindTexture(GL_TEXTURE_2D, name);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAX_LEVEL, 0);

        if (format != GL_RGBA8) {
            ByteBuffer upload = MemoryUtil.memAlloc(texture.getLevelSize(0));

            try {
                texture.copyLevel(0, upload);
                upload.flip();
                glCompressedTexImage2D(GL_TEXTURE_2D, 0, format, texture.getWidth(), texture.getHeight(), 0, upload);
            } finally {
                MemoryUtil.memFree(upload);
            }
        } else {
            byte[] pixels = texture.decodeRgba();
            ByteBuffer upload = MemoryUtil.memAlloc(pixels.length);

            try {
                upload.put(pixels).flip();
                glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
                glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, texture.getWidth(), texture.getHeight(), 0, GL_RGBA, GL_UNSIGNED_BYTE, upload);
            } finally {
                MemoryUtil.memFree(upload);
            }
        }

        this.batchTexture = 0;

        return name;
    }

    private void selectBatch(int mode, int texture, int secondTexture, int @Nullable [] layers) {
        if (this.batchMode == mode && this.batchTexture == texture && this.batchSecondTexture == secondTexture && this.batchLayers == layers) {
            return;
        }

        this.flush();
        this.batchMode          = mode;
        this.batchTexture       = texture;
        this.batchSecondTexture = secondTexture;
        this.batchLayers        = layers;
    }

    private void appendQuad(float left, float top, float right, float bottom,
                            float textureLeft, float textureTop, float textureRight, float textureBottom,
                            float mapLeft, float mapTop, float mapRight, float mapBottom,
                            float parameter, int layer) {
        if (this.vertices.remaining() < GraphicsDevice.QUAD_VERTICES * VERTEX_FLOATS) {
            this.flush();
        }

        this.appendVertex(left, top, textureLeft, textureTop, mapLeft, mapTop, parameter, layer);
        this.appendVertex(right, top, textureRight, textureTop, mapRight, mapTop, parameter, layer);
        this.appendVertex(right, bottom, textureRight, textureBottom, mapRight, mapBottom, parameter, layer);
        this.appendVertex(left, top, textureLeft, textureTop, mapLeft, mapTop, parameter, layer);
        this.appendVertex(right, bottom, textureRight, textureBottom, mapRight, mapBottom, parameter, layer);
        this.appendVertex(left, bottom, textureLeft, textureBottom, mapLeft, mapBottom, parameter, layer);
    }

    private void appendVertex(float horizontal, float vertical,
                              float textureHorizontal, float textureVertical,
                              float mapHorizontal, float mapVertical,
                              float parameter, int layer) {
        this.vertices.put(horizontal).put(vertical).put(textureHorizontal).put(textureVertical).put(mapHorizontal).put(mapVertical)
                     .put(this.teamColor[0]).put(this.teamColor[OverlayPass.RGBA_GREEN]).put(this.teamColor[OverlayPass.RGBA_BLUE]).put(parameter)
                     .put(layer).put(this.vertexFlip).put(this.vertexLighting);
    }

    private void flush() {
        if (this.vertices.position() == 0) {
            return;
        }

        this.vertices.flip();

        int mode = this.batchMode;
        boolean effectOnly = mode == MODE_SHADOW || mode == MODE_CLOAKED || mode == MODE_WARPED;
        boolean colorOnly = mode == MODE_BRIGHTEN;
        // The deferred brighten writes only the emissive target.
        boolean emissiveOnly = colorOnly && this.lightingEnabled;

        glColorMaski(WorldRenderer.COLOR_TARGET,
                     !effectOnly && !emissiveOnly,
                     !effectOnly && !emissiveOnly,
                     !effectOnly && !emissiveOnly,
                     !effectOnly && !emissiveOnly);

        // Writes each distortion or shadow mode only to its own effect channel and alpha.
        if (mode == MODE_SHADOW) {
            glColorMaski(WorldRenderer.EFFECT_TARGET, false, true, false, true);
        } else if (mode == MODE_CLOAKED) {
            glColorMaski(WorldRenderer.EFFECT_TARGET, false, false, true, true);
        } else if (mode == MODE_WARPED) {
            glColorMaski(WorldRenderer.EFFECT_TARGET, true, false, false, true);
        } else {
            glColorMaski(WorldRenderer.EFFECT_TARGET, !colorOnly, !colorOnly, !colorOnly, !colorOnly);
        }

        glEnable(GL_BLEND);

        if (mode == MODE_TILE || mode == MODE_TILE_EFFECT || mode == MODE_TILE_LAND) {
            glDisable(GL_BLEND);
        } else if (mode == MODE_EDGE_EFFECT) {
            // Scales the destination alpha by one minus the edge alpha: the water mask of the effect target (the color target alpha is not read).
            glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ZERO, GL_ONE_MINUS_SRC_ALPHA);
        } else {
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        }

        glUniform1i(this.modeUniform, mode);

        if (mode == MODE_TILE || mode == MODE_TILE_EFFECT || mode == MODE_TILE_LAND) {
            glActiveTexture(GL_TEXTURE0 + TILE_UNIT);
            glBindTexture(GL_TEXTURE_2D_ARRAY, this.batchTexture);

            if (this.water != null) {
                glActiveTexture(GL_TEXTURE0 + MASK_UNIT);
                glBindTexture(GL_TEXTURE_2D_ARRAY, this.water.getMaskTexture());
            }
        } else {
            glActiveTexture(GL_TEXTURE0 + TEAM_COLOR_UNIT);
            glBindTexture(GL_TEXTURE_2D, this.batchSecondTexture != 0 ? this.batchSecondTexture : this.blackTexture);
            glActiveTexture(GL_TEXTURE0 + SPRITE_UNIT);
            glBindTexture(GL_TEXTURE_2D, this.batchTexture);
        }

        if (this.lightingEnabled) {
            for (int layer = 0; layer < LIGHTING_LAYERS.length; layer++) {
                int texture = this.batchLayers != null && layer < this.batchLayers.length ? this.batchLayers[layer] : 0;

                glActiveTexture(GL_TEXTURE0 + FIRST_LIGHTING_UNIT + layer);
                glBindTexture(GL_TEXTURE_2D, texture != 0 ? texture : this.blackTexture);
            }
        }

        glActiveTexture(GL_TEXTURE0);
        glBindBuffer(GL_ARRAY_BUFFER, this.vertexBuffer);

        int vertexCount = this.vertices.remaining() / VERTEX_FLOATS;
        int firstVertex = this.writeVertexRing();

        this.drawCalls++;
        this.modeDrawCalls[mode]++;
        glDrawArrays(GL_TRIANGLES, firstVertex, vertexCount);
        this.vertices.clear();
    }

    /**
     * Appends the pending vertices to the vertex ring of the bound array buffer, orphaning the buffer when it wraps.
     *
     * @return the first vertex of the written range
     */
    private int writeVertexRing() {
        long bytes = (long) this.vertices.remaining() * Float.BYTES;
        long ringBytes = (long) this.vertices.capacity() * Float.BYTES * VERTEX_RING_BATCHES;

        if (this.vertexRingOffset + bytes > ringBytes) {
            glBufferData(GL_ARRAY_BUFFER, ringBytes, GL_STREAM_DRAW);
            this.vertexRingOffset = 0;
        }

        long offset = this.vertexRingOffset;

        glBufferSubData(GL_ARRAY_BUFFER, offset, this.vertices);
        this.vertexRingOffset += bytes;

        return (int) (offset / ((long) VERTEX_FLOATS * Float.BYTES));
    }


    /**
     * Releases the GPU textures, framebuffer, buffers and programs, in the reverse order of their creation. The borrowed world renderer and storage
     * stay open.
     * <p>
     * A failing constructor closes the renderer too; the objects of the steps after the failed one are not created then.
     */
    public void close() {
        if (this.closed) {
            return;
        }

        this.closed = true;

        IgnoreException.execute(
                this::deleteSceneTarget,
                this::deleteSheetTextures,
                this.imageSet::releaseSheets,
                this::deleteMegatileTextures,
                this::deleteTileTextures,
                this::closePasses,
                this::deleteDrawObjects,
                this::closeImageSet
        );
    }

    private void deleteSceneTarget() {
        IgnoreException.execute(
                () -> glDeleteTextures(this.sceneEffectTexture),
                () -> glDeleteTextures(this.sceneColorTexture),
                () -> glDeleteFramebuffers(this.sceneFramebuffer)
        );
    }

    private void deleteTileTextures() {
        if (this.foliageTextures != null) {
            for (int index = this.foliageTextures.length - 1; index >= 0; index--) {
                int texture = this.foliageTextures[index];

                IgnoreException.execute(
                        () -> glDeleteTextures(texture)
                );
            }
        }

        if (this.megatileArrays != null) {
            for (int index = this.megatileArrayCount - 1; index >= 0; index--) {
                int texture = this.megatileArrays[index];

                IgnoreException.execute(
                        () -> glDeleteTextures(texture)
                );
            }

            this.megatileArrayCount = 0;
        }

        if (this.creepEdgeTextures != null) {
            for (int index = this.creepEdgeTextures.length - 1; index >= 0; index--) {
                int texture = this.creepEdgeTextures[index];

                IgnoreException.execute(
                        () -> glDeleteTextures(texture)
                );
            }
        }
    }

    private void closePasses() {
        if (this.water != null) {
            IgnoreException.execute(
                    this.water::close
            );
        }

        if (this.overlay != null) {
            IgnoreException.execute(
                    this.overlay::close
            );
        }

        if (this.lighting != null) {
            IgnoreException.execute(
                    this.lighting::close
            );
        }
    }

    private void deleteDrawObjects() {
        if (this.defaultLightingTextures != null) {
            for (int index = this.defaultLightingTextures.length - 1; index >= 0; index--) {
                int texture = this.defaultLightingTextures[index];

                IgnoreException.execute(
                        () -> glDeleteTextures(texture)
                );
            }
        }

        IgnoreException.execute(
                () -> glDeleteTextures(this.blackTexture),
                () -> MemoryUtil.memFree(this.vertices),
                () -> glDeleteBuffers(this.vertexBuffer),
                () -> glDeleteVertexArrays(this.vertexArray),
                () -> this.compositeProgram.close(),
                () -> this.program.close()
        );
    }

    private void closeImageSet() {
        if (this.ownsImageSet) {
            this.imageSet.close();
        }
    }
}
