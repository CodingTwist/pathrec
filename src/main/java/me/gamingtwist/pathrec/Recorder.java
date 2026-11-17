package me.gamingtwist.pathrec;

import static me.gamingtwist.pathrec.PathRec.say;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * One recording session: tracks the player and writes everything to one .json file.
 *
 * <p>Fields read by {@link Overlay} are package-private; only this class writes them.
 */
class Recorder {
  /** Minimum horizontal move between rows, in blocks. */
  private static final double STEP = 1.0;
  /** JSON layout version; bump when the shape changes so readers can branch. */
  private static final int FORMAT = 2;
  /** How often the file is rewritten while walking, in ms; caps what a crash can lose. */
  private static final long SAVE_EVERY = 5000;
  /** Position points removed per undo press (about this many metres). */
  private static final int UNDO = 10;
  /** How close, in blocks, you must get to the undo marker for recording to carry on. */
  private static final double RESUME_RADIUS = 2.0;

  /**
   * One recorded point; {@code json} is what goes in the file.
   * {@code y} is the top of the ground block so jumps don't lift the drawn path.
   */
  record Row(double x, double y, double z, double dist, boolean mark, JsonObject json) {}

  private boolean recording;
  private File file;
  private long lastSave;
  private String name, started;
  private double lastX = Double.NaN, lastZ = Double.NaN;
  double distance;
  int rows, marks;
  long startMs;
  boolean paused;
  final List<Row> history = new ArrayList<>();
  /** Where to walk back to after an undo; null when recording normally. */
  Row resume;
  /** Last block the player stood on; reused mid-jump so any block type works as a path. */
  private BlockPos groundPos;
  private BlockState groundState;

  boolean isRecording() {
    return recording;
  }

  /** Folder recordings are saved in. */
  static File dir() {
    return new File(Minecraft.getInstance().gameDirectory, "pathrec");
  }

  String start(String name) {
    if (recording) return "already recording " + file.getName();
    File dir = dir();
    dir.mkdirs();
    file = new File(dir, LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".json");
    history.clear();
    resume = null;
    groundPos = null;
    lastX = lastZ = Double.NaN;
    rows = marks = 0;
    distance = 0;
    paused = false;
    startMs = System.currentTimeMillis();
    this.name = name;
    started = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString();
    // Written now as well as at stop so a crash still leaves who/where/when.
    if (save(null) != null) return "not recording";
    recording = true;
    return "recording to " + file.getName();
  }

  /**
   * Reopens a saved recording and keeps appending to it.
   *
   * <p>Holds recording until the player walks back to the last point, like after an undo.
   */
  String load(String fileName) {
    if (recording) return "already recording " + file.getName();
    if (fileName.contains("/") || fileName.contains("\\") || fileName.contains("..")) return "bad file name";
    File f = new File(dir(), fileName);
    if (!f.isFile()) return "no such file " + fileName;
    List<Row> rowsIn = new ArrayList<>();
    int r = 0, mk = 0;
    double dist = 0, px = Double.NaN, pz = Double.NaN;
    JsonObject m;
    try {
      m = JsonParser.parseString(Files.readString(f.toPath())).getAsJsonObject();
      if (m.get("format").getAsInt() != FORMAT) return fileName + " is from another version, can't resume";
      for (JsonElement e : m.getAsJsonArray("path")) {
        JsonObject j = e.getAsJsonObject();
        double x = j.get("x").getAsDouble(), z = j.get("z").getAsDouble();
        boolean isMark = j.has("mark");
        // Same distance rule as sample(): only path points count, marks don't move you.
        if (isMark) mk++;
        else {
          if (!Double.isNaN(px)) dist += Math.hypot(x - px, z - pz);
          px = x;
          pz = z;
          r++;
        }
        rowsIn.add(new Row(x, j.get("ground_y").getAsInt() + 1, z, dist, isMark, j));
      }
    } catch (IOException | RuntimeException e) {
      return "can't read " + fileName + ": " + e.getMessage();
    }
    file = f;
    name = m.get("name").isJsonNull() ? null : m.get("name").getAsString();
    started = m.get("started").getAsString();
    history.clear();
    history.addAll(rowsIn);
    rows = r;
    marks = mk;
    distance = dist;
    lastX = px;
    lastZ = pz;
    resume = history.isEmpty() ? null : history.getLast();
    groundPos = null;
    paused = false;
    startMs = System.currentTimeMillis();
    // Saved now so "ended" goes back to null right away.
    if (save(null) != null) return "not recording";
    recording = true;
    return resume == null ? "resumed " + fileName : "resumed " + fileName + ", walk back to the marker";
  }

  Component stop() {
    if (!recording) return Component.literal("not recording");
    recording = false;
    if (save(OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString()) != null) {
      return Component.literal("final save failed, the file has everything up to the last save");
    }
    return Component.literal(String.format(Locale.ROOT, "saved %d points (%.0f m) → ", rows, distance))
        .append(Component.literal(file.getName()).withStyle(s -> s.withUnderlined(true)
            .withClickEvent(new ClickEvent.OpenFile(file.getParentFile()))));
  }

  String pause() {
    if (!recording) return "not recording";
    paused = !paused;
    // Start fresh on resume so the detour isn't counted as one long step.
    lastX = lastZ = Double.NaN;
    return paused ? "paused" : "resumed";
  }

  String mark(String text) {
    LocalPlayer p = Minecraft.getInstance().player;
    if (!recording || p == null) return "not recording";
    write(p, text);
    marks++;
    save(null);
    return "marked: " + text;
  }

  String undo() {
    if (!recording) return "not recording";
    int n = 0;
    while (!history.isEmpty() && n < UNDO) {
      if (history.removeLast().mark) marks--;
      else {
        rows--;
        n++;
      }
    }
    Row last = history.isEmpty() ? null : history.getLast();
    distance = last == null ? 0 : last.dist;
    lastX = last == null ? Double.NaN : last.x;
    lastZ = last == null ? Double.NaN : last.z;
    resume = last;
    save(null);
    return last == null ? "undid everything" : "undid " + n + " points, walk back to the marker";
  }

  /** Adds a point if the player has moved far enough since the last one; called every tick. */
  void sample(LocalPlayer p) {
    if (!recording) return;
    if (p == null) {
      // Logged out or disconnected: close the file rather than leave it dangling.
      say(stop());
      return;
    }
    if (paused) return;
    // Every tick, not just every step, so a step taken mid-jump still knows the last ground.
    if (p.onGround()) {
      groundPos = p.getOnPos();
      groundState = p.level().getBlockState(groundPos);
    }
    if (resume != null) {
      double rx = p.getX() - resume.x, rz = p.getZ() - resume.z;
      // Don't record the walk back, or the undone detour comes right back.
      if (rx * rx + rz * rz > RESUME_RADIUS * RESUME_RADIUS) return;
      resume = null;
      say("back on track");
    }
    double dx = p.getX() - lastX, dz = p.getZ() - lastZ;
    if (Double.isNaN(lastX) || dx * dx + dz * dz >= STEP * STEP) {
      if (!Double.isNaN(lastX)) distance += Math.sqrt(dx * dx + dz * dz);
      write(p, null);
      lastX = p.getX();
      lastZ = p.getZ();
      rows++;
    }
    if (System.currentTimeMillis() - lastSave >= SAVE_EVERY) save(null);
  }

  private void write(LocalPlayer p, String mark) {
    Level level = p.level();
    boolean flying = p.getAbilities().flying || p.isFallFlying();
    BlockPos.MutableBlockPos pos;
    BlockState state;
    if (p.onGround()) {
      pos = p.getOnPos().mutable();
      state = level.getBlockState(pos);
    } else if (!flying && groundPos != null
        && p.getY() >= groundPos.getY() + 0.9 && p.getY() < groundPos.getY() + 3) {
      // Jumping: still level with or above the last ground, so it's the same path.
      pos = groundPos.mutable();
      state = groundState;
    } else {
      // Fell off or flying: scan down. Flying skips leaves so treetops don't count as ground.
      pos = p.blockPosition().mutable();
      state = level.getBlockState(pos);
      while (pos.getY() > level.getMinY() && !isGround(level, pos, state, flying)) {
        state = level.getBlockState(pos.move(0, -1, 0));
      }
    }
    JsonObject j = new JsonObject();
    j.addProperty("ms", System.currentTimeMillis());
    j.addProperty("x", round(p.getX()));
    j.addProperty("y", round(p.getY()));
    j.addProperty("z", round(p.getZ()));
    j.addProperty("dim", level.dimension().identifier().toString());
    j.addProperty("block", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
    j.addProperty("ground_y", pos.getY());
    if (mark != null) j.addProperty("mark", mark);
    history.add(new Row(p.getX(), pos.getY() + 1, p.getZ(), distance, mark != null, j));
  }

  private static double round(double v) {
    return Math.round(v * 100) / 100.0;
  }

  /**
   * Rewrites the whole .json file; {@code ended} is null while recording.
   *
   * <p>Writes to a temp file then renames it, so a crash mid-write never leaves a broken file.
   *
   * @return an error message, or null on success
   */
  private String save(String ended) {
    lastSave = System.currentTimeMillis();
    Minecraft mc = Minecraft.getInstance();
    JsonObject m = new JsonObject();
    m.addProperty("format", FORMAT);
    m.addProperty("name", name);
    m.addProperty("player", mc.getUser().getName());
    m.addProperty("uuid", String.valueOf(mc.getUser().getProfileId()));
    m.addProperty("server", mc.getCurrentServer() == null ? null : mc.getCurrentServer().ip);
    // Multiplayer clients never get the world name, so fall back to the dimension.
    m.addProperty("world", mc.getSingleplayerServer() != null
        ? mc.getSingleplayerServer().getWorldData().getLevelName()
        : mc.level == null ? null : mc.level.dimension().identifier().toString());
    m.addProperty("mc", SharedConstants.getCurrentVersion().name());
    m.addProperty("mod", FabricLoader.getInstance().getModContainer("pathrec")
        .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse(null));
    m.addProperty("started", started);
    m.addProperty("ended", ended);
    m.addProperty("points", rows);
    m.addProperty("distance_m", Math.round(distance));
    m.addProperty("marks", marks);
    m.addProperty("step_m", STEP);
    JsonArray path = new JsonArray();
    for (Row r : history) path.add(r.json);
    m.add("path", path);
    Path tmp = file.toPath().resolveSibling(file.getName() + ".tmp");
    try {
      Files.writeString(tmp, new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(m));
      Files.move(tmp, file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException e) {
      String msg = "can't write " + file.getName() + ": " + e.getMessage();
      say(msg);
      return msg;
    }
    return null;
  }

  private static boolean isGround(Level level, BlockPos pos, BlockState state, boolean skipLeaves) {
    if (!state.getFluidState().isEmpty()) return true;
    if (skipLeaves && state.is(BlockTags.LEAVES)) return false;
    return !state.getCollisionShape(level, pos).isEmpty();
  }
}
