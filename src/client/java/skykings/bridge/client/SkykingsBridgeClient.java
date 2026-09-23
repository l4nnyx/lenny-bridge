package skykings.bridge.client;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Client-side Fabric mod that bridges Hypixel guild chat and a Discord channel through bot.py.
 *
 *   Guild chat -> Discord: every player with the mod sends the guild lines they see, and bot.py
 *   posts each line once. As long as one of them can see guild chat, nothing is missed.
 *
 *   Discord -> game: bot.py sends each Discord message to every player with the mod, and the mod
 *   shows it in that player's own chat. Nothing is typed into guild chat, so only mod users see it.
 */
public class SkykingsBridgeClient implements ClientModInitializer {
	private static final Logger LOG = LoggerFactory.getLogger("SkykingsBridge");
	private static final Gson GSON = new Gson();

	// Hypixel puts color codes like §2 inside the text itself, so they have to be removed before reading it.
	private static final Pattern COLOR_CODES = Pattern.compile("\u00A7.?");
	// Hypixel adds this as an extra line to any chat message that mentions Discord.
	private static final String DISCORD_WARNING = "Please be mindful of Discord links in chat";
	// Removes whatever is left at the end after cutting the warning off: spaces, line breaks, color codes.
	private static final Pattern TRAILING_JUNK = Pattern.compile("(\\s|\u00A7.)+$");

	private static SkykingsBridgeClient instance;

	private final HttpClient http = HttpClient.newHttpClient();
	private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(daemon("SkykingsBridge-timer"));
	private final ExecutorService sendThread = Executors.newSingleThreadExecutor(daemon("SkykingsBridge-send"));
	// Goes up on every (re)connect. Callbacks from an older connection see an old number and do nothing.
	private final AtomicInteger generation = new AtomicInteger();

	private volatile WebSocket socket;
	private volatile boolean inServer;
	private volatile String playerName = "";
	private volatile int retryDelaySec = 2;

	@Override
	public void onInitializeClient() {
		instance = this;
		BridgeConfig.HANDLER.load(); // reads config/skykings-bridge.json5, creating it on first launch

		// Tie the connection to the game session: connect on join, disconnect on leave.
		ClientPlayConnectionEvents.JOIN.register((handler, packetSender, client) -> {
			inServer = true;
			if (client.player != null) playerName = client.player.getGameProfile().name();
			if (socket == null) restart(); // already connected (e.g. switching lobbies)? keep it
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			inServer = false;
			restart();
		});

		// Hypixel's Discord warning is added to the same message as the chat line that triggered it,
		// so only the warning is cut off and the message itself still shows. This only affects your own screen.
		ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
			String text = message.getString();
			// Hide a message only if the warning is all there is in it.
			return !hasDiscordWarning(text) || !stripColors(withoutDiscordWarning(text)).isEmpty();
		});
		ClientReceiveMessageEvents.MODIFY_GAME.register((message, overlay) -> {
			String text = message.getString();
			if (!hasDiscordWarning(text)) return message; // every other message stays exactly as it was
			// Hypixel's colors are § codes inside the text, so the rest of the message keeps its colors.
			return Component.literal(withoutDiscordWarning(text));
		});
	}

	/** Called after the config screen saves, so new settings take effect right away. */
	public static void applyConfig() {
		if (instance != null) instance.restart();
	}

	// ---------- Guild chat -> Discord ----------

	/**
	 * Called by ChatPacketMixin for every chat line Hypixel sends (action-bar text is skipped there).
	 * Reading the packet directly means other chat mods can't hide or reformat guild chat before we see it.
	 */
	public static void onDisplayedLine(String text) {
		if (instance != null) instance.onChatLine(text);
	}

	private void onChatLine(String rawText) {
		// e.g. "§2Guild > §6[MVP§9++§6] Name §e[E]§f: hi" becomes "Guild > [MVP++] Name [E]: hi".
		// The Discord warning is removed, and any line breaks become spaces.
		String text = stripColors(withoutDiscordWarning(rawText)).replace('\n', ' ');
		if (!text.startsWith("Guild > ")) return;
		// Every guild line (messages, joins, leaves) goes to bot.py, which formats it for Discord.
		// Everyone with the mod sends the same lines; bot.py posts each one only once.
		JsonObject o = new JsonObject();
		o.addProperty("type", "guild");
		o.addProperty("text", text);
		send(o);
	}

	// ---------- Discord -> game ----------

	/** Shows a Discord message in this player's own chat, e.g. "Discord > Dana: hello". */
	private void showDiscordMessage(String author, String text) {
		Component line = Component.literal("Discord > ").withStyle(ChatFormatting.BLUE)
			.append(Component.literal(author).withStyle(ChatFormatting.AQUA))
			.append(Component.literal(": " + text).withStyle(ChatFormatting.WHITE));
		Minecraft mc = Minecraft.getInstance();
		// Network threads must never touch the game directly, so hop onto the game thread.
		// sendSystemMessage only adds the line to your own chat; nothing is sent to the server.
		mc.execute(() -> {
			if (mc.player != null) mc.player.sendSystemMessage(line);
		});
	}

	// ---------- Connection ----------

	/** Drop the current connection (if any), then connect again if we should be connected. */
	private synchronized void restart() {
		int gen = generation.incrementAndGet();
		WebSocket old = socket;
		socket = null;
		// Close through the send thread, so anything already queued goes out first.
		if (old != null) sendThread.execute(() -> {
			try {
				old.sendClose(WebSocket.NORMAL_CLOSURE, "").get(5, TimeUnit.SECONDS);
			} catch (Exception ignored) {
				// the connection was already broken, abort() cleans up
			}
			old.abort();
		});
		retryDelaySec = 2;
		if (inServer) connect(gen);
	}

	private void connect(int gen) {
		BridgeConfig cfg = BridgeConfig.get();
		if (!cfg.enabled) return;
		// Uses the config file's values if filled in, otherwise the ones built into the jar.
		// The values themselves are never logged, so they don't end up in anyone's latest.log.
		String url = cfg.effectiveServerUrl();
		String secret = cfg.effectiveSecret();
		if (url.isEmpty() || secret.isEmpty()) {
			LOG.warn("No server URL or key set (none built into the mod, none in config/skykings-bridge.json5), not connecting");
			return;
		}
		URI uri;
		try {
			uri = URI.create(url);
		} catch (IllegalArgumentException e) {
			LOG.warn("The server URL is not a valid address");
			return;
		}
		if (!"ws".equalsIgnoreCase(uri.getScheme()) && !"wss".equalsIgnoreCase(uri.getScheme())) {
			LOG.warn("The server URL must start with ws:// or wss://");
			return;
		}
		http.newWebSocketBuilder()
			.buildAsync(uri, new Listener(gen, secret))
			.exceptionally(error -> {
				retryLater(gen);
				return null;
			});
	}

	private void retryLater(int gen) {
		if (gen != generation.get()) return; // this connection was already replaced
		int delay = retryDelaySec;
		retryDelaySec = Math.min(delay * 2, 60); // exponential backoff, max 60s
		LOG.info("Bridge disconnected, retrying in {}s", delay);
		timer.schedule(() -> {
			if (gen == generation.get()) connect(gen);
		}, delay, TimeUnit.SECONDS);
	}

	private class Listener implements WebSocket.Listener {
		private final int gen;
		private final String secret;
		private final StringBuilder buffer = new StringBuilder();

		Listener(int gen, String secret) {
			this.gen = gen;
			this.secret = secret;
		}

		@Override
		public void onOpen(WebSocket ws) {
			synchronized (SkykingsBridgeClient.this) {
				if (gen != generation.get()) { // settings changed while we were connecting
					ws.abort();
					return;
				}
				JsonObject auth = new JsonObject();
				auth.addProperty("type", "auth");
				auth.addProperty("secret", secret);
				auth.addProperty("name", playerName);
				queueSend(ws, GSON.toJson(auth)); // queued before anything else, so it's always first
				socket = ws;
			}
			retryDelaySec = 2;
			LOG.info("Connected to relay server, sending secret");
			ws.request(1);
		}

		@Override
		public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
			buffer.append(data); // big messages can arrive in pieces
			if (last) {
				handle(buffer.toString());
				buffer.setLength(0);
			}
			ws.request(1); // ask for the next piece
			return null;
		}

		@Override
		public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
			closed(ws, code, reason);
			return null;
		}

		@Override
		public void onError(WebSocket ws, Throwable error) {
			closed(ws, -1, String.valueOf(error));
		}

		private void closed(WebSocket ws, int code, String reason) {
			if (socket == ws) socket = null;
			if (code == 4001) { // wrong secret, retrying wouldn't help
				LOG.warn("Relay server rejected the secret, not retrying");
				return;
			}
			if (code == 4002) { // this account connected again, and the newer connection took over
				LOG.warn("Replaced by a newer connection from this account, not retrying");
				return;
			}
			retryLater(gen);
		}
	}

	// ---------- Sending and receiving ----------

	private void send(JsonObject o) {
		WebSocket ws = socket;
		if (ws != null) queueSend(ws, GSON.toJson(o));
	}

	/** Java's WebSocket allows only one send at a time, so every send goes through one thread, in order. */
	private void queueSend(WebSocket ws, String json) {
		sendThread.execute(() -> {
			try {
				ws.sendText(json, true).get(10, TimeUnit.SECONDS);
			} catch (Exception e) {
				LOG.warn("Send failed: {}", e.toString());
			}
		});
	}

	private void handle(String json) {
		try {
			JsonObject o = GSON.fromJson(json, JsonObject.class);
			if (o == null || !o.has("type")) return;
			switch (o.get("type").getAsString()) {
				case "ready" -> LOG.info("Relay server accepted the secret");
				case "discord_display" -> {
					String author = clean(o.get("author").getAsString(), 32);
					String text = clean(o.get("text").getAsString(), 500);
					if (!author.isEmpty() && !text.isEmpty()) showDiscordMessage(author, text);
				}
				default -> { }
			}
		} catch (RuntimeException e) {
			LOG.warn("Ignoring bad message from relay server: {}", json);
		}
	}

	private static String stripColors(String text) {
		return COLOR_CODES.matcher(text).replaceAll("").trim();
	}

	private static boolean hasDiscordWarning(String text) {
		return stripColors(text).contains(DISCORD_WARNING);
	}

	/** Cuts Hypixel's Discord warning off the end of a message, keeping everything before it. */
	private static String withoutDiscordWarning(String text) {
		if (!hasDiscordWarning(text)) return text;
		int start = text.lastIndexOf("Please be mindful of");
		if (start < 0) return text;
		return TRAILING_JUNK.matcher(text.substring(0, start)).replaceAll("");
	}

	/**
	 * Removes line breaks, other control characters and the section sign from Discord messages.
	 * The section sign would let Discord users add Minecraft colors or scrambled text to what you see.
	 */
	private static String clean(String s, int max) {
		String cleaned = s.replaceAll("[\\p{Cntrl}\u00A7]", " ").replaceAll("\\s+", " ").trim();
		return cleaned.length() > max ? cleaned.substring(0, max) : cleaned;
	}

	/** Background threads marked "daemon" so they never keep the game running after you quit. */
	private static ThreadFactory daemon(String name) {
		return runnable -> {
			Thread thread = new Thread(runnable, name);
			thread.setDaemon(true);
			return thread;
		};
	}
}