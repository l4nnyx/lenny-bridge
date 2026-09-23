package skykings.bridge.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import skykings.bridge.client.SkykingsBridgeClient;

/** Reads chat straight from the server's packets, before any other mod touches it. */
@Mixin(ClientPacketListener.class)
public class ChatPacketMixin {
    @Inject(method = "handleSystemChat", at = @At("HEAD"))
    private void dcbridge$onSystemChat(ClientboundSystemChatPacket packet, CallbackInfo ci) {
        // This method runs twice: once on the network thread, then again on the main thread. Only use the second.
        if (!Minecraft.getInstance().isSameThread()) return;
        if (packet.overlay()) return; // action bar, not chat
        SkykingsBridgeClient.onDisplayedLine(packet.content().getString());
    }
}