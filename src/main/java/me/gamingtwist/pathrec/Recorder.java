package me.gamingtwist.pathrec;

import static me.gamingtwist.pathrec.PathRec.say;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
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
 * One recording session: tracks the player and writes the CSV and its .json sidecar.
 *
 * <p>Fields read by {@link Overlay} are package-private; only this class writes them.
 */
class Recorder {
  /** Minimum horizontal move between rows, in blocks. */
  private static final double STEP = 1.0;
  /** Sidecar JSON layout version; bump when the CSV or JSON shape changes so readers can branch. */
  private static final int FORMAT = 1;
  /** Position rows removed per undo press (about this many metres). */
  private static final int UNDO = 10;
  /** How close, in blocks, you must get to the undo marker for recording to carry on. */
  private static final double RESUME_RADIUS = 2.0;

  /**
   * One written CSV row; {@code end} is the file length after it, so undo can truncate to it.
   * {@code y} is the top of the ground block so jumps don't lift the drawn path.
   */
  record Row(long end, double x, double y, double z, double dist, boolean mark) {}

  private PrintWriter out;
  private File file;
  private String name, started;
  private double lastX = Double.NaN, lastZ = Double.NaN;
  double distance;
  int rows, marks;
  long startMs;
  boolean paused;
  final List<Row> history = new ArrayList<>();
  private long headerEnd;
  /** Where to walk back to after an undo; null when recording normally. */
  Row resume;

  boolean isRecording() {
    return out != null;
  }

  String start(String name) {
    if (out != null) return "already recording " + file.getName();
    File dir = new File(Minecraft.getInstance().gameDirectory, "pathrec");
    dir.mkdirs();
    file = new File(dir, LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".csv");
    try {
      out = new PrintWriter(new FileWriter(file));
    } catch (IOException e) {
      return "can't open " + file + ": " + e.getMessage();
    }
    out.println("ms,x,y,z,dim,block,ground_y");
    out.flush();
    headerEnd = file.length();
    history.clear();
    resume = null;
    lastX = lastZ = Double.NaN;
    rows = marks = 0;
    distance = 0;
    paused = false;
    startMs = System.currentTimeMillis();
    this.name = name;
    started = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString();
    // Written now as well as at stop so a crash still leaves who/where/when.
    writeMeta(null);
    return "recording to " + file.getName();
  }

  Component stop() {
    if (out == null) return Component.literal("not recording");
    out.close();
    out = null;
    writeMeta(OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString());
    return Component.literal(String.format(Locale.ROOT, "saved %d points (%.0f m) → ", rows, distance))
        .append(Component.literal(file.getName()).withStyle(s -> s.withUnderlined(true)
            .withClickEvent(new ClickEvent.OpenFile(file.getParentFile()))));
  }

  String pause() {
    if (out == null) return "not recording";
    paused = !paused;
    // Start fresh on resume so the detour isn't counted as one long step.
    lastX = lastZ = Double.NaN;
    return paused ? "paused" : "resumed";
  }

  String mark(String text) {
    LocalPlayer p = Minecraft.getInstance().player;
    if (out == null || p == null) return "not recording";
    write(p, text.replace(",", " "));
    marks++;
    return "marked: " + text;
  }

  String undo() {
    if (out == null) return "not recording";
    int n = 0;
    while (!history.isEmpty() && n < UNDO) {
      if (history.removeLast().mark) marks--;
      else {
        rows--;
        n++;
      }
    }
    Row last = history.isEmpty() ? null : history.getLast();
    out.close();
    try {
      try (FileChannel ch = FileChannel.open(file.toPath(), StandardOpenOption.WRITE)) {
        ch.truncate(last == null ? headerEnd : last.end);
      }
      out = new PrintWriter(new FileWriter(file, true));
    } catch (IOException e) {
      out = null;
      return "undo failed, recording stopped: " + e.getMessage();
    }
    distance = last == null ? 0 : last.dist;
    lastX = last == null ? Double.NaN : last.x;
    lastZ = last == null ? Double.NaN : last.z;
    resume = last;
    return last == null ? "undid everything" : "undid " + n + " points, walk back to the marker";
  }

  /** Writes a row if the player has moved far enough since the last one; called every tick. */
  void sample(LocalPlayer p) {
    if (out == null) return;
    if (p == null) {
      // Logged out or disconnected: close the file rather than leave it dangling.
      say(stop());
      return;
    }
    if (paused) return;
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
  }

  private void write(LocalPlayer p, String mark) {
    Level level = p.level();
    BlockPos.MutableBlockPos pos = BlockPos.containing(p.getX(), p.getY(), p.getZ()).mutable();
    // Scan down from the feet so this works while flying; skip leaves and plants.
    BlockState state = level.getBlockState(pos);
    while (pos.getY() > level.getMinY() && !isGround(level, pos, state)) {
      state = level.getBlockState(pos.move(0, -1, 0));
    }
    out.printf(Locale.ROOT, "%d,%.2f,%.2f,%.2f,%s,%s,%d%s%n", System.currentTimeMillis(),
        p.getX(), p.getY(), p.getZ(), level.dimension().identifier(),
        BuiltInRegistries.BLOCK.getKey(state.getBlock()), pos.getY(),
        mark == null ? "" : "," + mark);
    // Flush every row so a crash keeps everything walked so far.
    out.flush();
    history.add(new Row(file.length(), p.getX(), pos.getY() + 1, p.getZ(), distance, mark != null));
  }

  /** Writes the .json sidecar next to the CSV; {@code ended} is null while recording. */
  private void writeMeta(String ended) {
    Minecraft mc = Minecraft.getInstance();
    JsonObject m = new JsonObject();
    m.addProperty("format", FORMAT);
    m.addProperty("name", name);
    m.addProperty("player", mc.getUser().getName());
    m.addProperty("uuid", String.valueOf(mc.getUser().getProfileId()));
    m.addProperty("server", mc.getCurrentServer() == null ? null : mc.getCurrentServer().ip);
    m.addProperty("world", mc.getSingleplayerServer() == null ? null
        : mc.getSingleplayerServer().getWorldData().getLevelName());
    m.addProperty("mc", SharedConstants.getCurrentVersion().name());
    m.addProperty("mod", FabricLoader.getInstance().getModContainer("pathrec")
        .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse(null));
    m.addProperty("started", started);
    m.addProperty("ended", ended);
    m.addProperty("points", rows);
    m.addProperty("distance_m", Math.round(distance));
    m.addProperty("marks", marks);
    m.addProperty("step_m", STEP);
    JsonArray cols = new JsonArray();
    for (String c : "ms,x,y,z,dim,block,ground_y,mark".split(",")) cols.add(c);
    m.add("columns", cols);
    File json = new File(file.getParentFile(), file.getName().replaceFirst("\\.csv$", ".json"));
    try (FileWriter w = new FileWriter(json)) {
      new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(m, w);
    } catch (IOException e) {
      say("can't write " + json.getName() + ": " + e.getMessage());
    }
  }

  private static boolean isGround(Level level, BlockPos pos, BlockState state) {
    if (!state.getFluidState().isEmpty()) return true;
    return !state.is(BlockTags.LEAVES) && !state.getCollisionShape(level, pos).isEmpty();
  }
}
