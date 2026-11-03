package com.malaron.loadsoflods.atlas;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AtlasBuilderTest {
	@TempDir
	Path temporaryDirectory;

	@Test
	void buildsMatchedAtlasesAndCachesResults() throws IOException {
		Path pack = temporaryDirectory.resolve("pack");
		writeTexture(pack.resolve("assets/example/textures/block/stone.png"), Color.RED, 8, 8);
		writeTexture(pack.resolve("assets/example/textures/block/dirt.png"), Color.BLUE, 8, 8);
		Path output = temporaryDirectory.resolve("generated");

		AtlasBuildResult first = AtlasBuilder.build(pack, output, 4);
		AtlasBuildResult second = AtlasBuilder.build(pack, output, 4);

		assertFalse(first.cacheHit());
		assertTrue(second.cacheHit());
		assertEquals(2, first.spriteCount());
		assertEquals(1, first.pageCount());
		assertTrue(Files.isRegularFile(first.highResolutionAtlas()));
		assertTrue(Files.isRegularFile(first.lowResolutionAtlas()));

		BufferedImage high = ImageIO.read(first.highResolutionAtlas().toFile());
		BufferedImage low = ImageIO.read(first.lowResolutionAtlas().toFile());
		assertEquals(high.getWidth(), low.getWidth() * 4);
		assertEquals(high.getHeight(), low.getHeight() * 4);

		JsonObject manifest = readManifest(first.layoutManifest());
		JsonObject lowBounds = manifest.getAsJsonObject("sprites")
			.getAsJsonObject("example:stone").getAsJsonObject("low");
		JsonObject highBounds = manifest.getAsJsonObject("sprites")
			.getAsJsonObject("example:stone").getAsJsonObject("high");
		assertEquals(lowBounds.get("x").getAsInt() * 4, highBounds.get("x").getAsInt());
		assertEquals(lowBounds.get("y").getAsInt() * 4, highBounds.get("y").getAsInt());
		assertEquals(lowBounds.get("width").getAsInt() * 4, highBounds.get("width").getAsInt());
		assertEquals(8, highBounds.get("width").getAsInt());
	}

	@Test
	void extractsAnimationFramesFromZipResourcePacks() throws IOException {
		Path zipPack = temporaryDirectory.resolve("animated-pack.zip");
		Path generated = temporaryDirectory.resolve("animated-output");
		try (OutputStream fileOutput = Files.newOutputStream(zipPack);
			 ZipOutputStream zip = new ZipOutputStream(fileOutput)) {
			BufferedImage sheet = new BufferedImage(8, 16, BufferedImage.TYPE_INT_ARGB);
			Graphics2D graphics = sheet.createGraphics();
			graphics.setColor(Color.GREEN);
			graphics.fillRect(0, 0, 8, 8);
			graphics.setColor(Color.YELLOW);
			graphics.fillRect(0, 8, 8, 8);
			graphics.dispose();
			zip.putNextEntry(new ZipEntry("assets/example/textures/block/water.png"));
			ImageIO.write(sheet, "PNG", zip);
			zip.closeEntry();
			zip.putNextEntry(new ZipEntry("assets/example/textures/block/water.png.mcmeta"));
			zip.write("{\"animation\":{\"frametime\":3,\"frames\":[0,{\"index\":1,\"time\":5}]}}"
				.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			zip.closeEntry();
			sheet.flush();
		}

		AtlasBuildResult result = AtlasBuilder.build(zipPack, generated, 2);

		assertEquals(1, result.spriteCount());
		JsonObject water = readManifest(result.layoutManifest()).getAsJsonObject("sprites")
			.getAsJsonObject("example:water");
		assertTrue(water.get("animated").getAsBoolean());
		assertEquals(2, water.getAsJsonArray("frames").size());
		assertEquals(
			5,
			water.getAsJsonArray("frames").get(1).getAsJsonObject().get("duration").getAsInt()
		);
		String framePath = water.getAsJsonArray("frames").get(1).getAsJsonObject().get("lowFile").getAsString();
		assertTrue(Files.isRegularFile(generated.resolve(framePath)));
		BufferedImage lowAtlas = ImageIO.read(result.lowResolutionAtlas().toFile());
		assertEquals(8, lowAtlas.getWidth());
		assertEquals(8, lowAtlas.getHeight());
	}

	@Test
	void skipsTexturesWithInvalidIdentifiersAndContinuesBuilding() throws IOException {
		Path pack = temporaryDirectory.resolve("pack-with-invalid-texture");
		writeTexture(pack.resolve("assets/minecraft/textures/block/stone.png"), Color.GRAY, 8, 8);
		writeTexture(
			pack.resolve("assets/minecraft/textures/block/Cardboard/lodestone.png"),
			Color.ORANGE,
			8,
			8
		);
		Path output = temporaryDirectory.resolve("generated-after-skip");

		AtlasBuildResult result = AtlasBuilder.build(pack, output, 4);

		assertEquals(1, result.spriteCount());
		JsonObject sprites = readManifest(result.layoutManifest()).getAsJsonObject("sprites");
		assertTrue(sprites.has("minecraft:stone"));
		assertFalse(sprites.has("minecraft:Cardboard/lodestone"));
	}

	@Test
	void splitsSpritesAcrossPagesInsteadOfExceedingAtlasTextureLimit() throws IOException {
		Path pack = temporaryDirectory.resolve("large-pack");
		writeTexture(
			pack.resolve("assets/example/textures/block/large-a.png"),
			Color.RED,
			24,
			24
		);
		writeTexture(
			pack.resolve("assets/example/textures/block/large-b.png"),
			Color.BLUE,
			24,
			24
		);
		Path output = temporaryDirectory.resolve("large-generated");

		AtlasBuildResult result = AtlasBuilder.build(pack, output, 2, 32);
		JsonObject manifest = readManifest(result.layoutManifest());
		JsonObject sprites = manifest.getAsJsonObject("sprites");

		assertEquals(2, result.pageCount());
		assertEquals(0, sprites.getAsJsonObject("example:large-a").get("page").getAsInt());
		assertEquals(1, sprites.getAsJsonObject("example:large-b").get("page").getAsInt());
		assertTrue(Files.isRegularFile(output.resolve("atlas-high-001.png")));
		assertTrue(Files.isRegularFile(output.resolve("atlas-low-001.png")));
	}

	private void writeTexture(Path path, Color color, int width, int height) throws IOException {
		Files.createDirectories(path.getParent());
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = image.createGraphics();
		graphics.setColor(color);
		graphics.fillRect(0, 0, width, height);
		graphics.dispose();
		ImageIO.write(image, "PNG", path.toFile());
		image.flush();
	}

	private JsonObject readManifest(Path path) throws IOException {
		try (var reader = Files.newBufferedReader(path)) {
			return JsonParser.parseReader(reader).getAsJsonObject();
		}
	}
}
