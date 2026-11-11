package io.github.sh4sh.aomcvisited;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

final class WebmapUpload {
	static final String HOST = Target.HOST;
	private static final URI WEBMAP = URI.create("https://" + HOST + "/");
	static final URI TOKEN_PAGE = WEBMAP.resolve("settings");
	/** Xaero's export timestamp, which the webmap dates an upload by. */
	private static final Pattern EXPORT_TIME = Pattern.compile("\\d{4}-\\d{2}-\\d{2}_\\d{2}\\.\\d{2}\\.\\d{2}");
	static final URI ISSUES = URI.create("https://github.com/AOMC-Server/aomc-visited/issues");
	private static final Set<String> KNOWN_SERVERS = Set.of("server/mc.aboutoliver.com", "server/mc.diorite.xyz",
			"server/161.129.180.145_25565", "server/161.129.180.145", "server/create.diorite.xyz",
			"server/gw.algot.net_25037", "server/gw.algot.net_25030");
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
	private static final String USER_AGENT = "AOMC Webmapper/" + Platform.modVersion();

	/** This session's exports, by number, for their [Upload] buttons. */
	private static final Map<Integer, Dimension> EXPORTS = new HashMap<>();
	private static volatile boolean running;

	record Dimension(String server, String key, int export, List<Path> images) {
		String map() {
			return webmapName(key);
		}
	}

	private WebmapUpload() {
	}

	static void remember(Dimension dimension) {
		EXPORTS.put(dimension.export(), dimension);
	}

	static Dimension export(int number) {
		return EXPORTS.get(number);
	}

	static boolean known(String server) {
		return KNOWN_SERVERS.contains(server);
	}

	static boolean forWebmap(String server) {
		return server != null && Target.SERVERS.contains(server);
	}

	static MutableComponent offHere() {
		return Chat.info("Only works on the " + Target.SERVER_NAME + " server, so it's off here.");
	}

	/** Why exports from this server can't be uploaded, or null if they can. */
	static Component refusal(String server) {
		if (forWebmap(server)) {
			return null;
		}
		String name = server == null ? "this world" : server.substring(server.indexOf('/') + 1);
		return Chat.error("Only maps of the " + Target.SERVER_NAME + " server go to " + HOST + ". You're on " + name
				+ ", so nothing is uploaded from here.");
	}

	static boolean running() {
		return running;
	}

	/** Null if the webmap has no map for it. */
	static String webmapName(String key) {
		return switch (VisitedChunks.dimensionOf(key)) {
			case "minecraft:overworld" -> VisitedChunks.isCaveMode(key) ? "overworld_caves" : "overworld";
			case "minecraft:the_nether" -> "nether";
			case "minecraft:the_end" -> "the_end";
			default -> null;
		};
	}

	/**
	 * Uploads each dimension's images, waits for the webmap to process them, then marks each dimension
	 * whose upload was processed as uploaded. Runs in the background.
	 */
	static void start(Minecraft client, List<Dimension> dimensions) {
		for (Dimension dimension : dimensions) {
			Component refusal = refusal(dimension.server());
			if (refusal != null) {
				Chat.send(client, refusal);
				return;
			}
		}
		if (AomcVisitedClient.SETTINGS.webmapToken() == null) {
			Chat.send(client, needsToken());
			return;
		}
		running = true;
		Thread.ofVirtual().name("AOMC Webmapper upload").start(() -> {
			try {
				Map<Dimension, String> problems = upload(client, dimensions);
				client.execute(() -> finish(client, dimensions, problems));
			} catch (RuntimeException e) {
				AomcVisitedClient.LOGGER.error("Upload failed", e);
				client.execute(() -> Chat.send(client, Chat.error("The upload failed: " + e.getMessage()
						+ ". Nothing was marked as uploaded.")));
			} finally {
				running = false;
			}
		});
	}

	/** Returns a problem for each dimension that didn't fully succeed. */
	private static Map<Dimension, String> upload(Minecraft client, List<Dimension> dimensions) {
		Map<Dimension, String> problems = new LinkedHashMap<>();
		String token = AomcVisitedClient.SETTINGS.webmapToken();
		for (Dimension dimension : dimensions) {
			status(client, "uploading " + dimension.images().size() + " images to the " + dimension.map() + " map");
			try {
				String problem = uploadDimension(client, dimension, token);
				if (problem != null) {
					problems.put(dimension, problem);
				}
			} catch (IOException e) {
				AomcVisitedClient.LOGGER.error("Could not upload {}", dimension.key(), e);
				problems.put(dimension, "couldn't reach the webmap (" + e.getMessage() + ")");
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				problems.put(dimension, "the upload was interrupted");
			}
		}
		return problems;
	}

	/**
	 * Sends a dimension's images in one request, then follows each revision the webmap makes of them.
	 * Returns a problem, or null if every revision was processed.
	 */
	private static String uploadDimension(Minecraft client, Dimension dimension, String token)
			throws IOException, InterruptedException {
		List<String> paths = new ArrayList<>();
		for (Path image : dimension.images()) {
			String path = exportPath(image);
			if (path == null) {
				return image.getFileName() + " isn't in a dated export folder, so the webmap would date it now";
			}
			paths.add(path);
		}
		String boundary = "aomcwebmapper" + UUID.randomUUID().toString().replace("-", "");
		List<HttpRequest.BodyPublisher> parts = new ArrayList<>();
		for (Path image : dimension.images()) {
			parts.add(text("--" + boundary + "\r\nContent-Disposition: form-data; name=\"files[]\"; filename=\""
					+ image.getFileName() + "\"\r\nContent-Type: image/png\r\n\r\n"));
			parts.add(HttpRequest.BodyPublishers.ofFile(image));
			parts.add(text("\r\n"));
		}
		JsonArray pathsJson = new JsonArray();
		paths.forEach(pathsJson::add);
		parts.add(field(boundary, "paths", pathsJson.toString()));
		parts.add(field(boundary, "timeZone", ZoneId.systemDefault().getId()));
		parts.add(text("--" + boundary + "--\r\n"));
		HttpRequest request = api("api/dimensions/" + dimension.map() + "/uploads", token)
				.header("Content-Type", "multipart/form-data; boundary=" + boundary)
				.timeout(Duration.ofMinutes(10))
				.POST(HttpRequest.BodyPublishers.concat(parts.toArray(HttpRequest.BodyPublisher[]::new)))
				.build();
		HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
		AomcVisitedClient.LOGGER.info("[AOMC Webmapper] upload: {} images to {} -> HTTP {} {}",
				dimension.images().size(), dimension.map(), response.statusCode(), response.body());
		if (response.statusCode() != 202) {
			return refused(response);
		}
		JsonArray revisions = JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonArray("revisionIds");
		for (JsonElement revision : revisions) {
			String problem = follow(client, revision.getAsLong(), token);
			if (problem != null) {
				return problem;
			}
		}
		return null;
	}

	/**
	 * The image's path from its export folder down, which the webmap reads the export date from: tiles have
	 * none in their own names. Null if no folder above it is dated.
	 */
	private static String exportPath(Path image) {
		Deque<String> names = new ArrayDeque<>();
		names.push(image.getFileName().toString());
		for (Path folder = image.getParent(); folder != null && folder.getFileName() != null;
				folder = folder.getParent()) {
			String name = folder.getFileName().toString();
			names.push(name);
			if (EXPORT_TIME.matcher(name).find()) {
				return String.join("/", names);
			}
		}
		return null;
	}

	/** Shows the webmap's progress on a revision until it ends. Returns a problem, or null if it was processed. */
	private static String follow(Minecraft client, long revision, String token) throws IOException, InterruptedException {
		HttpRequest events = api("api/revisions/" + revision + "/events", token)
				.header("Accept", "text/event-stream").timeout(Duration.ofMinutes(15)).GET().build();
		HttpResponse<Stream<String>> stream = HTTP.send(events, HttpResponse.BodyHandlers.ofLines());
		if (stream.statusCode() == 200) {
			String event = "";
			for (String line : (Iterable<String>) stream.body()::iterator) {
				if (line.startsWith("event:")) {
					event = line.substring("event:".length()).strip();
				} else if (line.startsWith("data:") && event.equals("processing")) {
					status(client, "the webmap is processing: " + progress(line.substring("data:".length())));
				}
			}
		}
		HttpRequest get = api("api/revisions/" + revision, token).timeout(Duration.ofSeconds(30)).GET().build();
		HttpResponse<String> response = HTTP.send(get, HttpResponse.BodyHandlers.ofString());
		AomcVisitedClient.LOGGER.info("[AOMC Webmapper] revision {}: HTTP {} {}", revision, response.statusCode(),
				response.body());
		if (response.statusCode() != 200) {
			return refused(response);
		}
		JsonObject result = JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonObject("revision");
		String state = result.get("status").getAsString();
		if (state.equals("done")) {
			return null;
		}
		JsonElement error = result.get("error");
		return state.equals("failed") && error != null && !error.isJsonNull()
				? "the webmap couldn't process it: " + error.getAsString()
				: "the webmap didn't finish processing it (" + state + ")";
	}

	private static String progress(String data) {
		try {
			JsonObject json = JsonParser.parseString(data).getAsJsonObject();
			if (json.has("total") && json.has("done")) {
				return json.get("done").getAsInt() + " of " + json.get("total").getAsInt();
			}
			if (json.has("message")) {
				return json.get("message").getAsString();
			}
		} catch (RuntimeException e) {
			// Not the JSON we expected: show it as it is.
		}
		return data.strip();
	}

	/** Why the webmap refused a request, from its {@code {"error": ...}} answer. */
	private static String refused(HttpResponse<String> response) {
		if (response.statusCode() == 401) {
			return "the webmap didn't accept your token. Make a new one on " + TOKEN_PAGE
					+ " and set it with /visited token <token>";
		}
		String error = null;
		try {
			error = JsonParser.parseString(response.body()).getAsJsonObject().get("error").getAsString();
		} catch (RuntimeException e) {
			// No JSON error: the status code alone will do.
		}
		return "the webmap answered HTTP " + response.statusCode() + (error == null ? "" : ": " + error);
	}

	/** Checks the token in the background and says whose account it is. */
	static void checkToken(Minecraft client, String token) {
		Thread.ofVirtual().name("AOMC Webmapper token check").start(() -> {
			Component message;
			try {
				HttpResponse<String> response = HTTP.send(api("api/me", token).timeout(Duration.ofSeconds(30))
						.GET().build(), HttpResponse.BodyHandlers.ofString());
				JsonElement user = response.statusCode() == 200
						? JsonParser.parseString(response.body()).getAsJsonObject().get("user") : null;
				message = user != null && user.isJsonObject()
						? Chat.success("Token saved. Uploads to " + HOST + " go in as "
								+ user.getAsJsonObject().get("username").getAsString() + ".")
						: Chat.error("Token saved, but " + HOST + " didn't accept it. Check you copied all of it.");
			} catch (IOException | RuntimeException e) {
				AomcVisitedClient.LOGGER.error("Could not check the webmap token", e);
				message = Chat.warning("Token saved, but " + HOST + " couldn't be reached to check it.");
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
			Component report = message;
			client.execute(() -> Chat.send(client, report));
		});
	}

	/** Tells the player how to get a token. */
	static MutableComponent needsToken() {
		return Chat.error("Uploading needs an API token from " + HOST + ". Log in there, make one under Settings,"
				+ " then type /visited token <token>.")
				.append(Chat.buttons(Chat.linkButton("Open " + HOST + " settings", TOKEN_PAGE)));
	}

	private static HttpRequest.Builder api(String path, String token) {
		return HttpRequest.newBuilder(WEBMAP.resolve(path)).header("User-Agent", USER_AGENT)
				.header("Authorization", "Bearer " + token);
	}

	private static HttpRequest.BodyPublisher field(String boundary, String name, String value) {
		return text("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
				+ value + "\r\n");
	}

	private static HttpRequest.BodyPublisher text(String text) {
		return HttpRequest.BodyPublishers.ofByteArray(text.getBytes(StandardCharsets.UTF_8));
	}

	private static void finish(Minecraft client, List<Dimension> dimensions, Map<Dimension, String> problems) {
		MutableComponent report = problems.isEmpty()
				? Chat.success("Uploaded to " + HOST + " and marked as uploaded.")
				: Chat.warning("The upload didn't fully succeed. Only what's listed as marked was marked as uploaded.");
		for (Dimension dimension : dimensions) {
			String name = dimension.map() != null ? dimension.map() : VisitedChunks.shortName(dimension.key());
			String problem = problems.get(dimension);
			if (problem != null) {
				report.append(Chat.line(Component.literal(name + ": not marked as uploaded, " + problem)
						.withStyle(ChatFormatting.RED)));
				continue;
			}
			int forgotten = AomcVisitedClient.VISITED.markUploaded(dimension.export());
			report.append(Chat.line(forgotten < 0
					? name + ": uploaded, but couldn't be marked (that export was replaced or cleared)"
					: name + ": " + dimension.images().size() + " images uploaded, marked as uploaded"));
		}
		VisitedHighlighter.refreshAll();
		Chat.status(client, problems.isEmpty() ? "upload done, see chat." : "upload had problems, see chat.",
				problems.isEmpty() ? ChatFormatting.GREEN : ChatFormatting.GOLD);
		Chat.send(client, report);
	}

	private static void status(Minecraft client, String text) {
		client.execute(() -> Chat.status(client, text, ChatFormatting.YELLOW));
	}
}
