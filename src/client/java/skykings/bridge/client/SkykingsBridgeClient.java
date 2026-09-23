package skykings.bridge.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

public class SkykingsBridgeClient implements ClientModInitializer {

	// Built into the jar so other players need zero setup. Edit these two, then rebuild.
	private static final String DEFAULT_URL = "wss://l3nnyxserver.taile3f3bc.ts.net/ws";
	private static final String DEFAULT_SECRET = "176a5776329a3d65f11496977fc6218b"; // your WS_SECRET from the server

	/** Written to .minecraft/config/dcbridge.json. Leave url/secret empty to use the built-in defaults. */
	public static class Config {
		public String url = "";
		public String secret = "";
		/** Regexes matched against Hypixel's chat lines when you toggle guild chat. Empty = unused. */
		public String guildToggleOffRegex = "";
		public String guildToggleOnRegex = "";
		public boolean onlyOnHypixel = true;
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final ScheduledExecutorService POOL = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "dcbridge");
		t.setDaemon(true);
		return t;
	});

	private Config cfg;
	private Pattern offPattern;
	private Pattern onPattern;

	private volatile WebSocket socket;
	private volatile boolean ready = false;
	/** false when the player has Hypixel's guild chat toggled off -> hide Discord messages, relay nothing. */
	private volatile boolean guildChatOn = true;
	private final AtomicBoolean reconnecting = new AtomicBoolean(false);
	private volatile int backoffSeconds = 2;
	/** Set by the bot: only one connected player relays guild chat. */
	private volatile boolean isRelayer = false;

	@Override
	public void onInitializeClient() {
		loadConfig();

		// Lines coming from the server (Hypixel sends chat as system/game messages).
		INSTANCE = this;
		// Chat lines are read by ChatComponentMixin (after other mods like SkyHanni are done with them).

		// Commands typed by the player.
		ClientSendMessageEvents.ALLOW_COMMAND.register(command -> {
			String c = command.trim().toLowerCase();
			if (c.startsWith("dcbridge")) {
				handleOwnCommand(c);
				return false; // don't send to the server
			}
			// Fallback only when no confirmation regexes are configured.
			if (offPattern == null && onPattern == null
				&& (c.equals("gtoggle") || c.equals("g toggle") || c.equals("guild toggle"))) {
				guildChatOn = !guildChatOn;
				info("Discord bridge assumes guild chat is now " + (guildChatOn ? "ON" : "OFF")
					+ " (use /dcbridge guild on|off to correct)");
			}
			return true;
		});

		connect();
	}

	private static SkykingsBridgeClient INSTANCE;

	/** Called by ChatComponentMixin for every line shown in chat. */
	public static void onDisplayedLine(String text) {
		if (INSTANCE != null) INSTANCE.onChatLine(text);
	}

	private void onChatLine(String raw) {
		// Strip invisible/format characters and anything other mods put before "Guild >"
		String text = raw.replaceAll("[\\p{Cf}\\p{Co}\\u00A0]", " ").strip();

		if (offPattern != null && offPattern.matcher(text).find()) guildChatOn = false;
		if (onPattern != null && onPattern.matcher(text).find()) guildChatOn = true;

		int i = text.indexOf("Guild > ");
		if (i >= 0 && i <= 6) {
			text = text.substring(i);
			guildChatOn = true; // we can see guild chat, so it's obviously on
			relayGuildLine(text);
		} else if (text.contains("Guild")) {
			log("Saw a Guild line but didn't match: " + raw.chars()
				.limit(20).mapToObj(c -> Integer.toHexString(c)).toList() + " | " + raw);
		}
	}

	// ---------------------------------------------------------------- config

	private void loadConfig() {
		Path p = FabricLoader.getInstance().getConfigDir().resolve("dcbridge.json");
		try {
			if (!Files.exists(p)) Files.writeString(p, GSON.toJson(new Config()));
			cfg = GSON.fromJson(Files.readString(p), Config.class);
		} catch (Exception e) {
			System.err.println("[dcbridge] config error: " + e);
			cfg = new Config();
		}
		if (cfg.url == null || cfg.url.isBlank()) cfg.url = DEFAULT_URL;
		if (cfg.secret == null || cfg.secret.isBlank()) cfg.secret = DEFAULT_SECRET;
		offPattern = cfg.guildToggleOffRegex.isBlank() ? null : Pattern.compile(cfg.guildToggleOffRegex);
		onPattern = cfg.guildToggleOnRegex.isBlank() ? null : Pattern.compile(cfg.guildToggleOnRegex);
	}

	// ------------------------------------------------------------- websocket

	private void connect() {
		if ("PUT_SECRET_HERE".equals(cfg.secret)) {
			log("No secret set: edit DEFAULT_SECRET in the source and rebuild.");
			return;
		}
		log("Connecting to " + cfg.url + " (secret length " + cfg.secret.length() + ")");
		try {
			HttpClient.newHttpClient()
				.newWebSocketBuilder()
				.connectTimeout(Duration.ofSeconds(10))
				.buildAsync(URI.create(cfg.url), new Listener())
				.whenComplete((ws, err) -> {
					if (err != null) {
						log("Connect failed: " + err);
						scheduleReconnect();
					}
				});
		} catch (Exception e) {
			log("Connect error: " + e);
			scheduleReconnect();
		}
	}

	private void scheduleReconnect() {
		ready = false;
		isRelayer = false;
		socket = null;
		if (!reconnecting.compareAndSet(false, true)) return;
		int delay = backoffSeconds;
		backoffSeconds = Math.min(backoffSeconds * 2, 60);
		POOL.schedule(() -> {
			reconnecting.set(false);
			connect();
		}, delay, TimeUnit.SECONDS);
	}

	private class Listener implements WebSocket.Listener {
		private final StringBuilder buf = new StringBuilder();

		@Override
		public void onOpen(WebSocket ws) {
			log("Socket open, sending auth");
			socket = ws;
			JsonObject auth = new JsonObject();
			auth.addProperty("type", "auth");
			auth.addProperty("secret", cfg.secret);
			ws.sendText(GSON.toJson(auth), true);
			ws.request(1);
		}

		@Override
		public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
			buf.append(data);
			if (last) {
				String s = buf.toString();
				buf.setLength(0);
				handleMessage(s);
			}
			ws.request(1);
			return null;
		}

		@Override
		public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
			log("Closed by server: " + code + " " + reason);
			scheduleReconnect();
			return null;
		}

		@Override
		public void onError(WebSocket ws, Throwable error) {
			log("Socket error: " + error);
			scheduleReconnect();
		}
	}

	private void handleMessage(String json) {
		try {
			JsonObject o = JsonParser.parseString(json).getAsJsonObject();
			String type = o.get("type").getAsString();
			if (type.equals("ready")) {
				ready = true;
				log("Authenticated, bridge ready");
				backoffSeconds = 2;
				info("Discord bridge connected");
			} else if (type.equals("role")) {
				isRelayer = o.get("relay").getAsBoolean();
				log("Relayer: " + isRelayer);
			} else if (type.equals("discord")) {
				if (!guildChatOn) return; // guild chat toggled off -> no messages
				String author = o.get("author").getAsString().replace('§', ' ');
				String text = o.get("text").getAsString().replace('§', ' ');
				Component line = Component.literal("[Discord] ").withStyle(ChatFormatting.BLUE)
					.append(Component.literal(author + ": ").withStyle(ChatFormatting.AQUA))
					.append(Component.literal(text).withStyle(ChatFormatting.WHITE));
				show(line);
			}
		} catch (Exception ignored) {
			// malformed frame
		}
	}

	private void relayGuildLine(String text) {
		WebSocket ws = socket;
		if (ws == null || !ready) { log("Not relaying (not connected): " + text); return; }
		if (!guildChatOn) { log("Not relaying (guild chat marked off)"); return; }
		if (!isRelayer) return; // another player is the relayer
		if (cfg.onlyOnHypixel && !onHypixel()) { log("Not relaying (server is not hypixel.net)"); return; }
		log("Relaying: " + text);
		JsonObject o = new JsonObject();
		o.addProperty("type", "guild");
		o.addProperty("text", text);
		String payload = GSON.toJson(o);
		POOL.execute(() -> {
			try {
				synchronized (this) {
					ws.sendText(payload, true).join();
				}
			} catch (Exception e) {
				log("Send failed: " + e);
			}
		});
	}

	// --------------------------------------------------------------- helpers

	private boolean onHypixel() {
		var server = Minecraft.getInstance().getCurrentServer();
		return server != null && server.ip.toLowerCase().contains("hypixel.net");
	}

	private void show(Component c) {
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> {
			if (mc.level != null) mc.gui.getChat().addClientSystemMessage(c);
		});
	}

	private static void log(String s) {
		System.out.println("[dcbridge] " + s);
	}

	private void info(String s) {
		show(Component.literal("[Discord bridge] " + s).withStyle(ChatFormatting.GRAY));
	}

	private void handleOwnCommand(String c) {
		String[] a = c.split("\\s+");
		if (a.length >= 3 && a[1].equals("guild")) {
			guildChatOn = a[2].equals("on");
			info("Guild chat marked " + (guildChatOn ? "ON" : "OFF"));
		} else {
			info((ready ? "connected" : "NOT connected") + (isRelayer ? " (relayer)" : "")
				+ ", guild chat " + (guildChatOn ? "ON" : "OFF")
				+ ". Commands: /dcbridge status | /dcbridge guild on|off");
		}
	}
}