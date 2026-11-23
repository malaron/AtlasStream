package com.malaron.atlasstream.client.atlas;

import net.minecraft.client.renderer.texture.Stitcher;
import net.minecraft.resources.Identifier;

public record SpriteFootprint(Identifier name, int width, int height) implements Stitcher.Entry {
}
