package com.xbow;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

public class XbowClient implements ClientModInitializer {
    private static KeyBinding toggleKey;
    private final XbowController controller = new XbowController();

    @Override
    public void onInitializeClient() {
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.xbow.toggle",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_X,
                KeyBinding.Category.MISC
        ));

        // Клавиши ходьбы сбрасываем в начале тика, до обработки ввода игрока
        ClientTickEvents.START_CLIENT_TICK.register(client -> controller.lockKeys(client));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (toggleKey.wasPressed()) {
                controller.toggle(client);
            }
            controller.tick(client);
        });
    }
}
