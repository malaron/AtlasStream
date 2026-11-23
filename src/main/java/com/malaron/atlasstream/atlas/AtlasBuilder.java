package com.malaron.atlasstream.atlas;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

import com.malaron.atlasstream.AtlasStream;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

public final class AtlasBuilder {
	private static final String MANIFEST_NAME = "atlas-layout.json";
	private static final String HIGH_ATLAS_BASE_NAME = "atlas-high";
	private static final String LOW_ATLAS_BASE_NAME = "atlas-low";
	private static final int MAX_GUTTER_LOW_RESOLUTION_PIXELS = 1;
	private static final int MAX_ATLAS_DIMENSION = 8192;
	private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("[a-z0-9._/-]+:[a-z0-9._/-]+");

	private AtlasBuilder() {
	}

	public static void main(String[] args) throws IOException {
		if (args.length < 2 || args.length > 3) {
			throw new IllegalArgumentException(
				"Usage: AtlasBuilder <resource-pack-directory-or-zip> <output-directory> [downscale-factor]"
			);
		}
		int scale = args.length == 3 ? Integer.parseInt(args[2]) : 4;
		AtlasBuildResult result = build(Path.of(args[0]), Path.of(args[1]), scale);
		AtlasStream.LOGGER.info(
			"{} atlas pair with {} sprites at {}",
			result.cacheHit() ? "Reused cached" : "Built",
			result.spriteCount(),
			result.layoutManifest()
		);
	}

	public static AtlasBuildResult build(Path resourcePack, Path outputDirectory, int downscaleFactor)
		throws IOException {
		return build(resourcePack, outputDirectory, downscaleFactor, MAX_ATLAS_DIMENSION);
	}

	static AtlasBuildResult build(
		Path resourcePack,
		Path outputDirectory,
		int downscaleFactor,
		int maximumAtlasDimension
	) throws IOException {
		validateScale(downscaleFactor);
		if (maximumAtlasDimension < downscaleFactor
				|| (maximumAtlasDimension & (maximumAtlasDimension - 1)) != 0) {
			throw new IllegalArgumentException("Atlas dimension limit must be a power of two");
		}
		if (!Files.isDirectory(resourcePack) && !Files.isRegularFile(resourcePack)) {
			throw new IOException("Resource pack does not exist: " + resourcePack);
		}
		if (Files.isDirectory(resourcePack)) {
			return buildFromRoot(resourcePack, outputDirectory, downscaleFactor, maximumAtlasDimension);
		}
		if (!resourcePack.getFileName().toString().endsWith(".zip")) {
			throw new IOException("Resource pack file must be a ZIP archive: " + resourcePack);
		}
		try (FileSystem fileSystem = FileSystems.newFileSystem(resourcePack)) {
			return buildFromRoot(fileSystem.getPath("/"), outputDirectory, downscaleFactor, maximumAtlasDimension);
		}
	}

	public static AtlasBuildResult build(
		ResourceManager resourceManager,
		Path outputDirectory,
		int downscaleFactor
	) throws IOException {
		Path absoluteOutput = outputDirectory.toAbsolutePath();
		Path parent = absoluteOutput.getParent();
		if (parent == null) {
			throw new IOException("Atlas output directory must have a parent: " + outputDirectory);
		}
		Files.createDirectories(parent);
		Path inputDirectory = Files.createTempDirectory(parent, "atlasstream-active-pack-");
		try {
			Map<Identifier, Resource> textures = resourceManager.listResources(
				"textures/block",
				identifier -> identifier.getPath().endsWith(".png")
			);
			for (Map.Entry<Identifier, Resource> entry : textures.entrySet()) {
				Identifier identifier = entry.getKey();
				Path texturePath = resourcePath(inputDirectory, identifier);
				try {
					Files.createDirectories(texturePath.getParent());
					try (InputStream input = entry.getValue().open()) {
						Files.copy(input, texturePath);
					}
				} catch (IOException exception) {
					AtlasStream.LOGGER.warn(
						"Skipping block texture {} because it could not be read: {}",
						identifier,
						exception.getMessage()
					);
					continue;
				}

				try {
					Identifier metadataId = Identifier.fromNamespaceAndPath(
						identifier.getNamespace(),
						identifier.getPath() + ".mcmeta"
					);
					var metadataResource = resourceManager.getResource(metadataId);
					if (metadataResource.isPresent()) {
						Path metadataPath = resourcePath(inputDirectory, metadataId);
						Files.createDirectories(metadataPath.getParent());
						try (InputStream input = metadataResource.get().open()) {
							Files.copy(input, metadataPath);
						}
					}
				} catch (IOException | RuntimeException exception) {
					AtlasStream.LOGGER.warn(
						"Ignoring animation metadata for {} because it could not be read: {}",
						identifier,
						exception.getMessage()
					);
				}
			}
			return build(inputDirectory, absoluteOutput, downscaleFactor);
		} finally {
			deleteTemporaryDirectory(inputDirectory);
		}
	}

	private static Path resourcePath(Path root, Identifier identifier) {
		return root.resolve("assets")
			.resolve(identifier.getNamespace())
			.resolve(identifier.getPath());
	}

	private static void deleteTemporaryDirectory(Path directory) throws IOException {
		try (Stream<Path> paths = Files.walk(directory)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(path);
			}
		}
	}

	private static AtlasBuildResult buildFromRoot(
		Path root,
		Path outputDirectory,
		int downscaleFactor,
		int maximumAtlasDimension
	)
		throws IOException {
		List<Sprite> sprites = scanRoot(root, downscaleFactor);
		int maximumLowDimension = maximumAtlasDimension / downscaleFactor;
		sprites.removeIf(sprite -> {
			int gutter = gutterFor(sprite, downscaleFactor);
			int lowWidth = sprite.cellWidth() / downscaleFactor + gutter * 2;
			int lowHeight = sprite.cellHeight() / downscaleFactor + gutter * 2;
			if (lowWidth <= maximumLowDimension && lowHeight <= maximumLowDimension) {
				return false;
			}
			AtlasStream.LOGGER.warn(
				"Skipping block texture {} because its frame exceeds the per-atlas dimension limit of {} pixels",
				sprite.identifier(),
				maximumAtlasDimension
			);
			return true;
		});
		if (sprites.isEmpty()) {
			throw new IOException("No usable block textures found in resource pack: " + root);
		}
		sprites.sort(
			Comparator.comparingInt(Sprite::cellHeight).reversed()
				.thenComparing(Comparator.comparingInt(Sprite::cellWidth).reversed())
				.thenComparing(Sprite::identifier)
		);

		String sourceHash = hashSources(sprites, downscaleFactor);
		Path manifestPath = outputDirectory.resolve(MANIFEST_NAME);
		List<Layout> layouts = pack(sprites, downscaleFactor, maximumAtlasDimension);
		Path highAtlasPath = outputDirectory.resolve(atlasFileName(HIGH_ATLAS_BASE_NAME, 0));
		Path lowAtlasPath = outputDirectory.resolve(atlasFileName(LOW_ATLAS_BASE_NAME, 0));
		if (isCacheHit(manifestPath, outputDirectory, sourceHash)) {
			return new AtlasBuildResult(
				highAtlasPath,
				lowAtlasPath,
				manifestPath,
				sprites.size(),
				layouts.size(),
				true
			);
		}

		Files.createDirectories(outputDirectory);
		Path frameDirectory = outputDirectory.resolve("frames");
		List<SpritePlacement> placements = new ArrayList<>(sprites.size());

		for (int pageIndex = 0; pageIndex < layouts.size(); pageIndex++) {
			Layout layout = layouts.get(pageIndex);
			BufferedImage highAtlas = new BufferedImage(
				layout.width() * downscaleFactor,
				layout.height() * downscaleFactor,
				BufferedImage.TYPE_INT_ARGB
			);
			BufferedImage lowAtlas = new BufferedImage(
				layout.width(),
				layout.height(),
				BufferedImage.TYPE_INT_ARGB
			);
			for (Sprite sprite : sprites) {
				if (sprite.page() == pageIndex) {
					placements.add(
						buildSprite(
							sprite,
							pageIndex,
							highAtlas,
							lowAtlas,
							downscaleFactor,
							frameDirectory,
							outputDirectory
						)
					);
				}
			}
			writePng(highAtlas, outputDirectory.resolve(atlasFileName(HIGH_ATLAS_BASE_NAME, pageIndex)));
			writePng(lowAtlas, outputDirectory.resolve(atlasFileName(LOW_ATLAS_BASE_NAME, pageIndex)));
			highAtlas.flush();
			lowAtlas.flush();
		}
		Files.writeString(
			manifestPath,
			createManifest(sourceHash, downscaleFactor, layouts, placements),
			StandardOpenOption.CREATE,
			StandardOpenOption.TRUNCATE_EXISTING,
			StandardOpenOption.WRITE
		);
		return new AtlasBuildResult(
			highAtlasPath,
			lowAtlasPath,
			manifestPath,
			sprites.size(),
			layouts.size(),
			false
		);
	}

	private static SpritePlacement buildSprite(
		Sprite sprite,
		int pageIndex,
		BufferedImage highAtlas,
		BufferedImage lowAtlas,
		int downscaleFactor,
		Path frameDirectory,
		Path outputDirectory
	) throws IOException {
		int lowX = sprite.x();
		int lowY = sprite.y();
		int lowGutter = gutterFor(sprite, downscaleFactor);
		int highGutter = lowGutter * downscaleFactor;
		List<AnimationFrame> frames = sprite.animation().frames();
		BufferedImage firstFrame = readFrame(sprite, frames.get(0).index());
		BufferedImage normalizedHigh = resize(firstFrame, sprite.cellWidth(), sprite.cellHeight());
		BufferedImage normalizedLow = resize(
			firstFrame,
			sprite.cellWidth() / downscaleFactor,
			sprite.cellHeight() / downscaleFactor
		);
		drawWithGutter(highAtlas, normalizedHigh, lowX * downscaleFactor, lowY * downscaleFactor, highGutter);
		drawWithGutter(lowAtlas, normalizedLow, lowX, lowY, lowGutter);

		List<FrameFile> frameFiles = new ArrayList<>();
		if (frames.size() > 1 || sprite.animation().interpolate()) {
			Path spriteFrameDirectory = frameDirectory.resolve(sprite.identifier().replace(':', '/'));
			Files.createDirectories(spriteFrameDirectory.resolve("high"));
			Files.createDirectories(spriteFrameDirectory.resolve("low"));
			for (int frameIndex = 0; frameIndex < frames.size(); frameIndex++) {
				AnimationFrame frame = frames.get(frameIndex);
				BufferedImage frameImage = readFrame(sprite, frame.index());
				BufferedImage highImage = resize(frameImage, sprite.cellWidth(), sprite.cellHeight());
				BufferedImage lowImage = resize(
					frameImage,
					sprite.cellWidth() / downscaleFactor,
					sprite.cellHeight() / downscaleFactor
				);
				String fileName = String.format("frame-%04d.png", frameIndex);
				Path highFrame = spriteFrameDirectory.resolve("high").resolve(fileName);
				Path lowFrame = spriteFrameDirectory.resolve("low").resolve(fileName);
				writePng(highImage, highFrame);
				writePng(lowImage, lowFrame);
				highImage.flush();
				lowImage.flush();
				frameImage.flush();
				frameFiles.add(
					new FrameFile(
						frame.index(),
						frame.duration(),
						outputDirectory.relativize(highFrame).toString().replace('\\', '/'),
						outputDirectory.relativize(lowFrame).toString().replace('\\', '/')
					)
				);
			}
		}
		normalizedHigh.flush();
		normalizedLow.flush();
		firstFrame.flush();
		return new SpritePlacement(
			sprite,
			pageIndex,
			lowX,
			lowY,
			sprite.cellWidth() / downscaleFactor,
			sprite.cellHeight() / downscaleFactor,
			frameFiles
		);
	}

	private static void validateScale(int scale) {
		if (scale < 2 || scale > 16 || (scale & (scale - 1)) != 0) {
			throw new IllegalArgumentException("Downscale factor must be a power of two between 2 and 16");
		}
	}

	private static List<Sprite> scanRoot(Path root, int scale) throws IOException {
		List<Path> textureFiles;
		try (Stream<Path> paths = Files.walk(root)) {
			textureFiles = paths
				.filter(Files::isRegularFile)
				.filter(path -> path.toString().replace('\\', '/').matches("(?:.*/)?assets/[^/]+/textures/block/.+\\.png"))
				.sorted()
				.toList();
		}
		List<Sprite> sprites = new ArrayList<>(textureFiles.size());
		for (Path texturePath : textureFiles) {
			String normalized = root.relativize(texturePath).toString().replace('\\', '/');
			try {
				Sprite sprite = readSprite(texturePath, normalized, scale);
				if (sprite != null) {
					sprites.add(sprite);
				}
			} catch (IOException | RuntimeException exception) {
				AtlasStream.LOGGER.warn(
					"Skipping block texture {}: {}",
					normalized,
					exception.getMessage()
				);
			}
		}
		return sprites;
	}

	private static Sprite readSprite(Path texturePath, String normalized, int scale) throws IOException {
		String identifier = identifierFromPath(normalized);
		if (!IDENTIFIER_PATTERN.matcher(identifier).matches()) {
			AtlasStream.LOGGER.warn("Skipping block texture with invalid identifier: {}", normalized);
			return null;
		}
		BufferedImage image;
		try (InputStream input = Files.newInputStream(texturePath)) {
			image = ImageIO.read(input);
		}
		if (image == null || image.getWidth() == 0 || image.getHeight() == 0) {
			throw new IOException("PNG could not be decoded");
		}
		try {
			Path metadataPath = texturePath.resolveSibling(texturePath.getFileName() + ".mcmeta");
			Animation animation = readAnimation(metadataPath, image.getWidth(), image.getHeight());
			int highWidth = roundUp(animation.frameWidth(), scale);
			int highHeight = roundUp(animation.frameHeight(), scale);
			if (highWidth < 4 || highHeight < 4) {
				throw new IOException("texture frame is too small for the selected downscale factor");
			}
			return new Sprite(
				identifier,
				texturePath,
				metadataPath,
				image.getWidth(),
				image.getHeight(),
				highWidth,
				highHeight,
				animation
			);
		} finally {
			image.flush();
		}
	}

	private static String identifierFromPath(String path) {
		String[] components = path.split("/");
		String namespace = components[1];
		String texturePath = String.join(
			"/",
			java.util.Arrays.copyOfRange(components, 4, components.length)
		);
		return namespace + ":" + texturePath.substring(0, texturePath.length() - ".png".length());
	}

	private static Animation readAnimation(Path metadataPath, int imageWidth, int imageHeight) throws IOException {
		if (!Files.exists(metadataPath)) {
			return new Animation(imageWidth, imageHeight, List.of(new AnimationFrame(0, 1)), 1, false);
		}

		JsonObject root;
		try (var reader = Files.newBufferedReader(metadataPath)) {
			root = JsonParser.parseReader(reader).getAsJsonObject();
		} catch (RuntimeException exception) {
			throw new IOException("Invalid animation metadata: " + metadataPath, exception);
		}
		JsonObject animationObject = root.has("animation") && root.get("animation").isJsonObject()
			? root.getAsJsonObject("animation")
			: new JsonObject();
		int frameWidth = animationObject.has("width")
			? animationObject.get("width").getAsInt()
			: imageWidth;
		int frameHeight = animationObject.has("height")
			? animationObject.get("height").getAsInt()
			: frameWidth;
		if (frameWidth <= 0 || frameHeight <= 0 || imageWidth % frameWidth != 0 || imageHeight % frameHeight != 0) {
			throw new IOException("Animation frame dimensions do not tile the PNG: " + metadataPath);
		}
		int columns = imageWidth / frameWidth;
		int frameCount = columns * (imageHeight / frameHeight);
		int defaultTime = animationObject.has("frametime")
			? animationObject.get("frametime").getAsInt()
			: 1;
		if (defaultTime <= 0) {
			throw new IOException("Animation frame time must be positive: " + metadataPath);
		}
		List<AnimationFrame> frames = new ArrayList<>();
		if (animationObject.has("frames")) {
			JsonArray declaredFrames = animationObject.getAsJsonArray("frames");
			for (JsonElement element : declaredFrames) {
				int index = element.isJsonObject()
					? element.getAsJsonObject().get("index").getAsInt()
					: element.getAsInt();
				int duration = element.isJsonObject() && element.getAsJsonObject().has("time")
					? element.getAsJsonObject().get("time").getAsInt()
					: defaultTime;
				addFrame(frames, index, duration, frameCount, metadataPath);
			}
		} else {
			for (int index = 0; index < frameCount; index++) {
				frames.add(new AnimationFrame(index, defaultTime));
			}
		}
		if (frames.isEmpty()) {
			throw new IOException("Animation must have at least one frame: " + metadataPath);
		}
		boolean interpolate = animationObject.has("interpolate")
			&& animationObject.get("interpolate").getAsBoolean();
		return new Animation(frameWidth, frameHeight, frames, defaultTime, interpolate, columns);
	}

	private static void addFrame(
		List<AnimationFrame> frames,
		int index,
		int duration,
		int frameCount,
		Path metadataPath
	) throws IOException {
		if (index < 0 || index >= frameCount || duration <= 0) {
			throw new IOException("Invalid animation frame index or duration: " + metadataPath);
		}
		frames.add(new AnimationFrame(index, duration));
	}

	private static BufferedImage readFrame(Sprite sprite, int frameIndex) throws IOException {
		BufferedImage source;
		try (InputStream input = Files.newInputStream(sprite.texturePath())) {
			source = ImageIO.read(input);
		}
		if (source == null) {
			throw new IOException("Could not decode block texture: " + sprite.texturePath());
		}
		int frameWidth = sprite.animation().frameWidth();
		int frameHeight = sprite.animation().frameHeight();
		int columns = sprite.animation().columns();
		int x = (frameIndex % columns) * frameWidth;
		int y = (frameIndex / columns) * frameHeight;
		BufferedImage frame = source.getSubimage(x, y, frameWidth, frameHeight);
		BufferedImage copy = new BufferedImage(frameWidth, frameHeight, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = copy.createGraphics();
		graphics.drawImage(frame, 0, 0, null);
		graphics.dispose();
		source.flush();
		return copy;
	}

	private static BufferedImage resize(BufferedImage source, int width, int height) {
		BufferedImage resized = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = resized.createGraphics();
		graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
		graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		graphics.drawImage(source, 0, 0, width, height, null);
		graphics.dispose();
		return resized;
	}

	private static void drawWithGutter(BufferedImage atlas, BufferedImage sprite, int x, int y, int gutter) {
		for (int row = 0; row < sprite.getHeight(); row++) {
			int left = sprite.getRGB(0, row);
			int right = sprite.getRGB(sprite.getWidth() - 1, row);
			for (int offset = 1; offset <= gutter; offset++) {
				atlas.setRGB(x - offset, y + row, left);
				atlas.setRGB(x + sprite.getWidth() - 1 + offset, y + row, right);
			}
		}
		for (int column = 0; column < sprite.getWidth(); column++) {
			int top = sprite.getRGB(column, 0);
			int bottom = sprite.getRGB(column, sprite.getHeight() - 1);
			for (int offset = 1; offset <= gutter; offset++) {
				atlas.setRGB(x + column, y - offset, top);
				atlas.setRGB(x + column, y + sprite.getHeight() - 1 + offset, bottom);
			}
		}
		for (int gx = 1; gx <= gutter; gx++) {
			for (int gy = 1; gy <= gutter; gy++) {
				atlas.setRGB(x - gx, y - gy, sprite.getRGB(0, 0));
				atlas.setRGB(x + sprite.getWidth() - 1 + gx, y - gy, sprite.getRGB(sprite.getWidth() - 1, 0));
				atlas.setRGB(x - gx, y + sprite.getHeight() - 1 + gy, sprite.getRGB(0, sprite.getHeight() - 1));
				atlas.setRGB(
					x + sprite.getWidth() - 1 + gx,
					y + sprite.getHeight() - 1 + gy,
					sprite.getRGB(sprite.getWidth() - 1, sprite.getHeight() - 1)
				);
			}
		}
		Graphics2D graphics = atlas.createGraphics();
		graphics.drawImage(sprite, x, y, null);
		graphics.dispose();
	}

	private static List<Layout> pack(List<Sprite> sprites, int scale, int maximumAtlasDimension)
		throws IOException {
		int maximumLowDimension = maximumAtlasDimension / scale;
		long totalArea = 0;
		int widest = 0;
		for (Sprite sprite : sprites) {
			int gutter = gutterFor(sprite, scale);
			int width = sprite.cellWidth() / scale + gutter * 2;
			int height = sprite.cellHeight() / scale + gutter * 2;
			totalArea += (long) width * height;
			widest = Math.max(widest, width);
		}
		int desiredWidth = (int) Math.min(maximumLowDimension, Math.max(widest, (long) Math.ceil(Math.sqrt(totalArea))));
		int atlasWidth = nextPowerOfTwo(desiredWidth, maximumLowDimension);
		List<Layout> layouts = new ArrayList<>();
		int page = 0;
		int x = 0;
		int y = 0;
		int rowHeight = 0;
		for (Sprite sprite : sprites) {
			int gutter = gutterFor(sprite, scale);
			int width = sprite.cellWidth() / scale + gutter * 2;
			int height = sprite.cellHeight() / scale + gutter * 2;
			if (x + width > atlasWidth) {
				x = 0;
				y += rowHeight;
				rowHeight = 0;
			}
			if (y + height > maximumLowDimension) {
				if (y == 0) {
					throw new IOException(
						"Block texture " + sprite.identifier() + " cannot fit in a "
							+ maximumAtlasDimension + " pixel atlas"
					);
				}
				layouts.add(new Layout(page, atlasWidth, nextPowerOfTwo(y + rowHeight, maximumLowDimension)));
				page++;
				x = 0;
				y = 0;
				rowHeight = 0;
				if (width > atlasWidth) {
					throw new IOException(
						"Block texture " + sprite.identifier() + " exceeds the per-atlas width limit"
					);
				}
			}
			sprite.place(page, x + gutter, y + gutter);
			x += width;
			rowHeight = Math.max(rowHeight, height);
		}
		layouts.add(new Layout(page, atlasWidth, nextPowerOfTwo(y + rowHeight, maximumLowDimension)));
		return layouts;
	}

	private static int gutterFor(Sprite sprite, int scale) {
		int smallestLowDimension = Math.min(sprite.cellWidth(), sprite.cellHeight()) / scale;
		return Math.min(MAX_GUTTER_LOW_RESOLUTION_PIXELS, Math.max(0, (smallestLowDimension - 1) / 2));
	}

	private static int nextPowerOfTwo(int value, int maximum) throws IOException {
		if (value <= 0 || value > maximum) {
			throw new IOException("Generated atlas dimensions exceed the prototype texture size limit");
		}
		int next = 1;
		while (next < value) {
			next <<= 1;
		}
		return next;
	}

	private static int roundUp(int value, int multiple) {
		return ((value + multiple - 1) / multiple) * multiple;
	}

	private static String hashSources(List<Sprite> sprites, int scale) throws IOException {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			digest.update(Integer.toString(scale).getBytes(java.nio.charset.StandardCharsets.UTF_8));
			for (Sprite sprite : sprites) {
				digest.update(sprite.identifier().getBytes(java.nio.charset.StandardCharsets.UTF_8));
				updateDigest(digest, sprite.texturePath());
				if (Files.exists(sprite.metadataPath())) {
					updateDigest(digest, sprite.metadataPath());
				}
			}
			return HexFormat.of().formatHex(digest.digest());
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private static void updateDigest(MessageDigest digest, Path file) throws IOException {
		try (InputStream input = Files.newInputStream(file)) {
			byte[] buffer = new byte[8192];
			int read;
			while ((read = input.read(buffer)) != -1) {
				digest.update(buffer, 0, read);
			}
		}
	}

	private static boolean isCacheHit(Path manifest, Path outputDirectory, String sourceHash)
		throws IOException {
		if (!Files.isRegularFile(manifest)) {
			return false;
		}
		JsonObject json;
		try (var reader = Files.newBufferedReader(manifest)) {
			json = JsonParser.parseReader(reader).getAsJsonObject();
		} catch (RuntimeException exception) {
			return false;
		}
		if (!json.has("sourceHash") || !sourceHash.equals(json.get("sourceHash").getAsString())
				|| !json.has("atlases") || !json.get("atlases").isJsonArray()) {
			return false;
		}
		JsonArray atlases = json.getAsJsonArray("atlases");
		if (atlases.isEmpty()) {
			return false;
		}
		for (JsonElement element : atlases) {
			JsonObject atlas = element.getAsJsonObject();
			if (!Files.isRegularFile(outputDirectory.resolve(atlas.get("highFile").getAsString()))
					|| !Files.isRegularFile(outputDirectory.resolve(atlas.get("lowFile").getAsString()))) {
				return false;
			}
		}
		return true;
	}

	private static String atlasFileName(String baseName, int page) {
		return page == 0 ? baseName + ".png" : String.format("%s-%03d.png", baseName, page);
	}

	private static void writePng(BufferedImage image, Path path) throws IOException {
		Files.createDirectories(path.getParent());
		if (!ImageIO.write(image, "PNG", path.toFile())) {
			throw new IOException("No PNG writer is available for " + path);
		}
	}

	private static String createManifest(
		String sourceHash,
		int scale,
		List<Layout> layouts,
		List<SpritePlacement> placements
	) {
		StringBuilder json = new StringBuilder(512 + placements.size() * 256);
		json.append("{\n")
			.append("  \"formatVersion\": 1,\n")
			.append("  \"sourceHash\": \"").append(sourceHash).append("\",\n")
			.append("  \"downscaleFactor\": ").append(scale).append(",\n")
			.append("  \"coordinateNote\": \"Sprite bounds are pixel coordinates; normalized UVs match between atlases.\",\n")
			.append("  \"atlases\": [\n");
		for (int index = 0; index < layouts.size(); index++) {
			Layout layout = layouts.get(index);
			json.append("    {\"page\": ").append(layout.page())
				.append(", \"highFile\": \"").append(atlasFileName(HIGH_ATLAS_BASE_NAME, layout.page()))
				.append("\", \"lowFile\": \"").append(atlasFileName(LOW_ATLAS_BASE_NAME, layout.page()))
				.append("\", \"highWidth\": ").append(layout.width() * scale)
				.append(", \"highHeight\": ").append(layout.height() * scale)
				.append(", \"lowWidth\": ").append(layout.width())
				.append(", \"lowHeight\": ").append(layout.height()).append('}');
			if (index + 1 < layouts.size()) {
				json.append(',');
			}
			json.append('\n');
		}
		json.append("  ],\n")
			.append("  \"sprites\": {\n");
		for (int index = 0; index < placements.size(); index++) {
			SpritePlacement placement = placements.get(index);
			Sprite sprite = placement.sprite();
			int highX = placement.x() * scale;
			int highY = placement.y() * scale;
			int highSpriteWidth = placement.width() * scale;
			int highSpriteHeight = placement.height() * scale;
			json.append("    \"").append(sprite.identifier()).append("\": {")
				.append("\"page\": ").append(placement.page()).append(", ")
				.append("\"low\": {\"x\": ").append(placement.x())
				.append(", \"y\": ").append(placement.y())
				.append(", \"width\": ").append(placement.width())
				.append(", \"height\": ").append(placement.height()).append("}, ")
				.append("\"high\": {\"x\": ").append(highX)
				.append(", \"y\": ").append(highY)
				.append(", \"width\": ").append(highSpriteWidth)
				.append(", \"height\": ").append(highSpriteHeight).append("}, ")
				.append("\"animated\": ").append(sprite.animation().frames().size() > 1)
				.append(", \"interpolate\": ").append(sprite.animation().interpolate())
				.append(", \"frames\": [");
			for (int frameIndex = 0; frameIndex < placement.frames().size(); frameIndex++) {
				FrameFile frame = placement.frames().get(frameIndex);
				if (frameIndex > 0) {
					json.append(", ");
				}
				json.append("{\"sourceIndex\": ").append(frame.sourceIndex())
					.append(", \"duration\": ").append(frame.duration())
					.append(", \"highFile\": \"").append(frame.highFile())
					.append("\", \"lowFile\": \"").append(frame.lowFile()).append("\"}");
			}
			json.append("]}");
			if (index + 1 < placements.size()) {
				json.append(',');
			}
			json.append('\n');
		}
		return json.append("  }\n}\n").toString();
	}

	private record AnimationFrame(int index, int duration) {
	}

	private record Animation(
		int frameWidth,
		int frameHeight,
		List<AnimationFrame> frames,
		int defaultFrameTime,
		boolean interpolate,
		int columns
	) {
		private Animation(int frameWidth, int frameHeight, List<AnimationFrame> frames, int defaultFrameTime, boolean interpolate) {
			this(frameWidth, frameHeight, frames, defaultFrameTime, interpolate, 1);
		}
	}

	private static final class Sprite {
		private final String identifier;
		private final Path texturePath;
		private final Path metadataPath;
		private final int sourceWidth;
		private final int sourceHeight;
		private final int cellWidth;
		private final int cellHeight;
		private final Animation animation;
		private int page;
		private int x;
		private int y;

		private Sprite(
			String identifier,
			Path texturePath,
			Path metadataPath,
			int sourceWidth,
			int sourceHeight,
			int cellWidth,
			int cellHeight,
			Animation animation
		) {
			this.identifier = identifier;
			this.texturePath = texturePath;
			this.metadataPath = metadataPath;
			this.sourceWidth = sourceWidth;
			this.sourceHeight = sourceHeight;
			this.cellWidth = cellWidth;
			this.cellHeight = cellHeight;
			this.animation = animation;
		}

		String identifier() { return identifier; }
		Path texturePath() { return texturePath; }
		Path metadataPath() { return metadataPath; }
		int cellWidth() { return cellWidth; }
		int cellHeight() { return cellHeight; }
		Animation animation() { return animation; }
		void place(int page, int x, int y) { this.page = page; this.x = x; this.y = y; }
		int page() { return page; }
		int x() { return x; }
		int y() { return y; }
	}

	private record Layout(int page, int width, int height) {
	}

	private record FrameFile(int sourceIndex, int duration, String highFile, String lowFile) {
	}

	private record SpritePlacement(
		Sprite sprite,
		int page,
		int x,
		int y,
		int width,
		int height,
		List<FrameFile> frames
	) {
	}

}
