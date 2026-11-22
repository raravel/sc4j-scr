package org.fogofwarlabs.sc4j.scr.render.hd;

import org.jetbrains.annotations.NotNull;

import static org.lwjgl.opengl.GL33C.glDeleteTextures;

/**
 * The uploaded textures of one HD image: diffuse (bright in the lit path), team color and (for the lit path) emissive, normal, specular and AO/depth,
 * with the frame placement of its anim.
 */
final class HdImageTextures {
    /**
     * The frame placement of the image's anim.
     */
    final @NotNull HdFrameGeometry geometry;

    /**
     * The sprite texture: the diffuse layer, or in the lit path the bright layer where the anim has one.
     */
    final int diffuseTexture;
    /**
     * The team color mask texture, or {@code 0} when the anim has none.
     */
    final int teamColorTexture;
    /**
     * The emissive, normal, specular and AO/depth textures of the lit path ({@code 0} for an absent layer).
     */
    final int @NotNull [] lightingTextures;
    /**
     * The texture of lit-path frames without lighting data: the diffuse layer (the {@link #diffuseTexture} itself when it is not the bright layer),
     * or {@code 0} when every frame has lighting data.
     */
    final int unlitTexture;
    /**
     * Whether the bright, normal, specular and AO/depth layers are all present.
     */
    final boolean lightingData;
    /**
     * Whether a frame drawn from the diffuse layer in the lit path (without lighting data) keeps the team color mask.
     */
    final boolean keepsTeamColor;

    /**
     * The diffuse texture width in scale-{@code 4} frame units.
     */
    final float textureWidth;
    /**
     * The diffuse texture height in scale-{@code 4} frame units.
     */
    final float textureHeight;

    /**
     * The texture memory the uploaded layers use.
     */
    final long byteSize;

    /**
     * The scene frame of the last use, for least-recently-used eviction.
     */
    long lastUsedFrame;

    /**
     * Creates an {@link HdImageTextures} instance from uploaded textures.
     *
     * @param geometry         the frame placement of the image's anim
     * @param diffuseTexture   the sprite texture
     * @param teamColorTexture the team color mask texture, or {@code 0}
     * @param lightingTextures the emissive, normal, specular and AO/depth textures
     * @param unlitTexture     the texture of lit-path frames without lighting data, or {@code 0}
     * @param lightingData     whether the bright, normal, specular and AO/depth layers are all present
     * @param keepsTeamColor   whether a frame drawn from the diffuse layer in the lit path keeps the team color mask
     * @param textureWidth     the diffuse texture width in scale-{@code 4} frame units
     * @param textureHeight    the diffuse texture height in scale-{@code 4} frame units
     * @param byteSize         the texture memory the uploaded layers use
     */
    HdImageTextures(@NotNull HdFrameGeometry geometry,
                    int diffuseTexture, int teamColorTexture, int @NotNull [] lightingTextures, int unlitTexture, boolean lightingData,
                    boolean keepsTeamColor,
                    float textureWidth, float textureHeight,
                    long byteSize) {
        this.geometry = geometry;

        this.diffuseTexture   = diffuseTexture;
        this.teamColorTexture = teamColorTexture;
        this.lightingTextures = lightingTextures;
        this.unlitTexture     = unlitTexture;
        this.lightingData     = lightingData;
        this.keepsTeamColor   = keepsTeamColor;

        this.textureWidth  = textureWidth;
        this.textureHeight = textureHeight;

        this.byteSize = byteSize;

        this.lastUsedFrame = 0L;
    }

    /**
     * Deletes the textures.
     */
    void delete() {
        glDeleteTextures(this.diffuseTexture);

        if (this.teamColorTexture != 0) {
            glDeleteTextures(this.teamColorTexture);
        }

        if (this.unlitTexture != 0 && this.unlitTexture != this.diffuseTexture) {
            glDeleteTextures(this.unlitTexture);
        }

        for (int texture : this.lightingTextures) {
            if (texture != 0) {
                glDeleteTextures(texture);
            }
        }
    }
}
