package me.gamingtwist.pathrec;

import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.Vec3;
import me.gamingtwist.pathrec.Recorder.Row;

/** Draws a {@link Recorder}'s status HUD and its path in the world. */
class Overlay {
  /** Only path segments this close to the camera are drawn, to keep long walks cheap. */
  private static final double DRAW_RADIUS = 96;
  /** Segments longer than this are gaps (pause, teleport), not walked path, so aren't drawn. */
  private static final double MAX_SEGMENT = 10;

  private final Recorder rec;

  Overlay(Recorder rec) {
    this.rec = rec;
  }

  void hud(GuiGraphicsExtractor g) {
    Minecraft mc = Minecraft.getInstance();
    if (!rec.isRecording() || mc.gui.hud.isHidden()) return;
    // ponytail: elapsed includes paused time; track paused duration if players want it exact
    long s = (System.currentTimeMillis() - rec.startMs) / 1000;
    String status = rec.paused ? "❚❚ PAUSED" : (s % 2 == 0 ? "● REC" : "○ REC");
    String stats = String.format(Locale.ROOT, "%02d:%02d · %.0f m · %d pts", s / 60, s % 60, rec.distance, rec.rows);
    String back = rec.resume == null ? null : back(mc.player);
    int w = Math.max(mc.font.width(status), mc.font.width(stats));
    if (back != null) w = Math.max(w, mc.font.width(back));
    g.fill(2, 2, 2 + w + 8, back == null ? 26 : 36, 0x80000000);
    g.text(mc.font, status, 6, 5, rec.paused ? 0xFFFFFF55 : 0xFFFF5555, true);
    g.text(mc.font, stats, 6, 15, 0xFFFFFFFF, true);
    if (back != null) g.text(mc.font, back, 6, 25, 0xFFFFAA00, true);
  }

  /** Draws the recorded path as lines near the camera, plus a beam at the undo marker. */
  void drawPath(Vec3 cam) {
    if (!rec.isRecording() || rec.history.isEmpty()) return;
    double r2 = DRAW_RADIUS * DRAW_RADIUS;
    Row prev = null;
    for (Row row : rec.history) {
      if (prev != null) {
        double dx = row.x() - prev.x(), dz = row.z() - prev.z();
        double cx = row.x() - cam.x, cz = row.z() - cam.z;
        if (dx * dx + dz * dz <= MAX_SEGMENT * MAX_SEGMENT && cx * cx + cz * cz <= r2) {
          Gizmos.line(new Vec3(prev.x(), prev.y() + 0.1, prev.z()), new Vec3(row.x(), row.y() + 0.1, row.z()),
              0xFF33DDFF, 3);
        }
      }
      if (row.mark()) Gizmos.point(new Vec3(row.x(), row.y() + 0.5, row.z()), 0xFFFFDD33, 8);
      prev = row;
    }
    Row r = rec.resume;
    if (r != null) {
      Gizmos.line(new Vec3(r.x(), r.y(), r.z()), new Vec3(r.x(), r.y() + 4, r.z()), 0xFFFFAA00, 6)
          .setAlwaysOnTop();
    }
  }

  /** Arrow and distance from the player to the undo marker, relative to where they're facing. */
  private String back(LocalPlayer p) {
    if (p == null) return null;
    double dx = rec.resume.x() - p.getX(), dz = rec.resume.z() - p.getZ();
    // Minecraft yaw: 0 faces +Z and grows clockwise, so atan2(-dx, dz) is the yaw toward the marker.
    double rel = Math.toDegrees(Math.atan2(-dx, dz)) - p.getYRot();
    int i = (int) Math.floorMod(Math.round(rel / 45), 8L);
    return String.format(Locale.ROOT, "↩ go back %s %.0f m", "↑↗→↘↓↙←↖".charAt(i), Math.hypot(dx, dz));
  }
}
