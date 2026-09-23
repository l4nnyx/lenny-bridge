package skykings.bridge.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * Adds a config button for skykings bridge in Mod Menu.
 * If Mod Menu isn't installed, Fabric never loads this class, so it's safe to ship.
 */
public class ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> BridgeConfig.createScreen(parent, SkykingsBridgeClient::applyConfig);
    }
}
