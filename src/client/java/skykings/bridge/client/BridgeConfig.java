package skykings.bridge.client;

import dev.isxander.yacl3.api.ConfigCategory;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.OptionDescription;
import dev.isxander.yacl3.api.YetAnotherConfigLib;
import dev.isxander.yacl3.api.controller.StringControllerBuilder;
import dev.isxander.yacl3.api.controller.TickBoxControllerBuilder;
import dev.isxander.yacl3.config.v2.api.ConfigClassHandler;
import dev.isxander.yacl3.config.v2.api.SerialEntry;
import dev.isxander.yacl3.config.v2.api.serializer.GsonConfigSerializerBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Bridge settings, saved to .minecraft/config/skykings-bridge.json5.
 *
 * YACL does two jobs here:
 *   1) HANDLER reads and writes the file. Only fields marked @SerialEntry are saved.
 *   2) createScreen() builds the in-game settings screen (opened from Mod Menu).
 *
 * The server address and key can also be built into the jar (see bridge-defaults.properties),
 * so people you send the mod to don't have to set anything. Those are never written to the config file.
 */
public class BridgeConfig {
    /** Built into the jar from bridge-defaults.properties when it was built. Empty if that file didn't exist. */
    private static final Properties BUILT_IN = loadBuiltIn();
    private static final String OLD_PLACEHOLDER = "wss://bridge.example.com/ws";

    public static final ConfigClassHandler<BridgeConfig> HANDLER =
        ConfigClassHandler.createBuilder(BridgeConfig.class)
            .id(Identifier.fromNamespaceAndPath("skykings-bridge", "config"))
            .serializer(config -> GsonConfigSerializerBuilder.create(config)
                .setPath(FabricLoader.getInstance().getConfigDir().resolve("skykings-bridge.json5"))
                .setJson5(true) // JSON5 allows the comments below to be written into the file
                .build())
            .build();

    @SerialEntry(comment = "Leave empty to use the server built into the mod. Only fill in to use a different one.")
    public String serverUrl = "";

    @SerialEntry(comment = "Leave empty to use the key built into the mod. Only fill in to use your own key.")
    public String secret = "";

    @SerialEntry(comment = "Set to false to turn the bridge off without removing the mod")
    public boolean enabled = true;

    /** Always read settings through this. load() swaps in a brand-new object, so never keep a copy. */
    public static BridgeConfig get() {
        return HANDLER.instance();
    }

    /** The address to connect to: the one in the config file if filled in, otherwise the built-in one. */
    public String effectiveServerUrl() {
        boolean unset = serverUrl.isBlank() || serverUrl.trim().equals(OLD_PLACEHOLDER);
        return unset ? BUILT_IN.getProperty("serverUrl", "").trim() : serverUrl.trim();
    }

    /** The key to log in with: the one in the config file if filled in, otherwise the built-in one. */
    public String effectiveSecret() {
        return secret.isBlank() ? BUILT_IN.getProperty("secret", "").trim() : secret.trim();
    }

    private static Properties loadBuiltIn() {
        Properties properties = new Properties();
        try (InputStream in = BridgeConfig.class.getResourceAsStream("/skykings-bridge-defaults.properties")) {
            if (in != null) properties.load(in);
        } catch (IOException ignored) {
            // no built-in settings; the config file has to provide them
        }
        return properties;
    }

    /** Builds the settings screen. onSave runs after the file has been written. */
    public static Screen createScreen(Screen parent, Runnable onSave) {
        BridgeConfig defaults = HANDLER.defaults();
        return YetAnotherConfigLib.createBuilder()
            .title(Component.literal("skykings bridge"))
            .category(ConfigCategory.createBuilder()
                .name(Component.literal("Connection"))
                .option(Option.<Boolean>createBuilder()
                    .name(Component.literal("Enabled"))
                    .description(OptionDescription.of(Component.literal(
                        "Connect to the relay server when you join a server.")))
                    .binding(defaults.enabled, () -> get().enabled, value -> get().enabled = value)
                    .controller(TickBoxControllerBuilder::create)
                    .build())
                .option(Option.<String>createBuilder()
                    .name(Component.literal("Server URL"))
                    .description(OptionDescription.of(Component.literal(
                        "Leave empty to use the server built into the mod.")))
                    .binding(defaults.serverUrl, () -> get().serverUrl, value -> get().serverUrl = value)
                    .controller(StringControllerBuilder::create)
                    .build())
                .option(Option.<String>createBuilder()
                    .name(Component.literal("Secret"))
                    .description(OptionDescription.of(Component.literal(
                        "Leave empty to use the key built into the mod. If you fill it in, it is shown "
                            + "in plain text, so don't open this screen while streaming.")))
                    .binding(defaults.secret, () -> get().secret, value -> get().secret = value)
                    .controller(StringControllerBuilder::create)
                    .build())
                .build())
            .save(() -> {
                HANDLER.save();
                onSave.run();
            })
            .build()
            .generateScreen(parent);
    }
}