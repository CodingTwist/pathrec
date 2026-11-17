package me.gamingtwist.pathrec;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Records the player's position to a CSV while walking, for tracing paths onto the map.
 *
 * <p>Rows are {@code ms,x,y,z,dim,block,ground_y}; mark rows add an 8th column with the label.
 * Each CSV gets a same-named .json with who/where/when and totals.
 * Use the hotkeys (F6 record, F7 pause, F8 undo, F9 mark; rebindable in Controls) or
 * {@code /pathrec start [name]|stop|pause|undo|mark <text>}. Undo drops the last few metres and
 * holds recording until you walk back to the glowing marker.
 */
public class PathRec implements ClientModInitializer {
  private static final KeyMapping.Category CATEGORY =
      KeyMapping.Category.register(Identifier.fromNamespaceAndPath("pathrec", "main"));
  private final KeyMapping toggleKey = key("toggle", InputConstants.KEY_F6);
  private final KeyMapping pauseKey = key("pause", InputConstants.KEY_F7);
  private final KeyMapping undoKey = key("undo", InputConstants.KEY_F8);
  private final KeyMapping markKey = key("mark", InputConstants.KEY_F9);

  private final Recorder rec = new Recorder();
  private final Overlay overlay = new Overlay(rec);

  @Override
  public void onInitializeClient() {
    ClientCommandRegistrationCallback.EVENT.register((dispatcher, ctx) -> dispatcher.register(
        literal("pathrec")
            .then(literal("start").executes(c -> say(rec.start(null)))
                .then(argument("name", StringArgumentType.greedyString())
                    .executes(c -> say(rec.start(StringArgumentType.getString(c, "name"))))))
            .then(literal("stop").executes(c -> say(rec.stop())))
            .then(literal("pause").executes(c -> say(rec.pause())))
            .then(literal("undo").executes(c -> say(rec.undo())))
            .then(literal("mark").then(argument("text", StringArgumentType.greedyString())
                .executes(c -> say(rec.mark(StringArgumentType.getString(c, "text"))))))));
    ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("pathrec", "hud"), (g, dt) -> overlay.hud(g));
    // Gizmos must be added during extraction, when the frame's gizmo collector is open.
    LevelExtractionEvents.END_EXTRACTION.register(ctx -> overlay.drawPath(ctx.camera().position()));
  }

  private static KeyMapping key(String name, int code) {
    return KeyMappingHelper.registerKeyMapping(new KeyMapping("key.pathrec." + name, code, CATEGORY));
  }

  private void tick(Minecraft mc) {
    while (toggleKey.consumeClick()) {
      if (rec.isRecording()) say(rec.stop());
      else say(rec.start(null));
    }
    while (pauseKey.consumeClick()) say(rec.pause());
    while (undoKey.consumeClick()) say(rec.undo());
    while (markKey.consumeClick()) say(rec.mark("mark " + (rec.marks + 1)));
    rec.sample(mc.player);
  }

  static int say(String msg) {
    return say(Component.literal(msg));
  }

  /** Sends a [pathrec]-prefixed chat message; returns 1 so commands can return it directly. */
  static int say(Component msg) {
    LocalPlayer p = Minecraft.getInstance().player;
    if (p != null) p.sendSystemMessage(Component.literal("[pathrec] ").append(msg));
    return 1;
  }
}
