package org.fogofwarlabs.sc4j.scr.client.ui;

import org.fogofwarlabs.sc4j.base.exception.IgnoreException;
import org.fogofwarlabs.sc4j.engine.client.render.Canvas;
import org.fogofwarlabs.sc4j.engine.client.render.Texture;
import org.fogofwarlabs.sc4j.engine.resource.graphics.Picture;
import org.jetbrains.annotations.NotNull;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * The textures of the pictures that the game's plugins draw, one per picture instance, uploaded on first use and kept until the cache is closed.
 */
public final class PictureTextureCache {
    private final @NotNull Map<Picture, Texture> textures;

    /**
     * Creates a {@link PictureTextureCache} instance without textures.
     */
    public PictureTextureCache() {
        this.textures = new IdentityHashMap<>();
    }

    /**
     * Finds the texture of a picture, uploading it through a canvas on first use.
     *
     * @param canvas  the canvas that draws the texture
     * @param picture the picture
     * @return the texture
     */
    public @NotNull Texture findTexture(@NotNull Canvas canvas, @NotNull Picture picture) {
        Texture texture = this.textures.get(picture);

        if (texture != null) {
            return texture;
        }

        int[] colors = new int[picture.getWidth() * picture.getHeight()];

        picture.getColor().copyValues(0, colors, 0, colors.length);

        Texture uploaded = canvas.uploadTexture(picture.getWidth(), picture.getHeight(), colors);

        this.textures.put(picture, uploaded);

        return uploaded;
    }

    /**
     * Disposes resources: closes every uploaded texture.
     */
    public void close() {
        for (Texture texture : this.textures.values()) {
            IgnoreException.execute(
                    texture::close
            );
        }

        this.textures.clear();
    }
}
