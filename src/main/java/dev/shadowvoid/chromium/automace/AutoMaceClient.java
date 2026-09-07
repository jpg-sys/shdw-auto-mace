package dev.shadowvoid.chromium.automace;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

public final class AutoMaceClient implements ClientModInitializer {
    private static final AutoMaceModule AUTO_MACE =
            new AutoMaceModule(AutoMaceConfig.defaults(), false);

    private static KeyBinding toggleKey;

    @Override
    public void onInitializeClient() {
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.chromium.auto_mace",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_M,
                KeyBinding.Category.MISC
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (toggleKey.wasPressed()) {
                AUTO_MACE.toggle();
                showToggleMessage(client);
            }
            AUTO_MACE.tick(client);
        });
    }

    private static void showToggleMessage(MinecraftClient client) {
        if (client.player != null) {
            client.player.sendMessage(
                    Text.literal("Auto Mace: " + (AUTO_MACE.isEnabled() ? "ON" : "OFF")),
                    true
            );
        }
    }
}
