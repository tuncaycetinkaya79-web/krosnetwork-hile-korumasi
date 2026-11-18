package net.krosnetwork.hile;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class Main extends JavaPlugin implements Listener {

    private static class Data {
        int airTicks, flyBuf, speedBuf, flags;
        int aimBuf, elytraBuf, multiBuf, angleBuf;
        int glideBuf, jesusTicks, stepBuf, reachBuf, timerCount, timerBuf;
        long timerStart;
        long exemptUntil, lastAlert, lastFlagTime, lastRotMs, lastHitMs;
        float lastYaw, lastYawDelta;
        UUID lastHitTarget;
        boolean punishing;
        Location lastSafe;
        final ArrayDeque<Long> clicks = new ArrayDeque<>();
    }

    private final Map<UUID, Data> data = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("Krosnetwork Hile Koruması aktif (1.21.4)");
    }

    private Data d(Player p) {
        return data.computeIfAbsent(p.getUniqueId(), k -> new Data());
    }

    private boolean exempt(Player p) {
        if (p.hasPermission("krosnetwork.hile.bypass")) return true;
        if (d(p).punishing) return true;
        GameMode gm = p.getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) return true;
        if (p.getAllowFlight() || p.isFlying() || p.isGliding() || p.isRiptiding()
                || p.isInsideVehicle() || p.isDead()) return true;
        return System.currentTimeMillis() < d(p).exemptUntil;
    }

    private void exemptFor(Player p, long ms) {
        Data x = d(p);
        x.exemptUntil = Math.max(x.exemptUntil, System.currentTimeMillis() + ms);
        x.airTicks = 0;
        x.flyBuf = 0;
        x.speedBuf = 0;
    }

    /* ---- muafiyet olayları (yanlış pozitifi önler) ---- */
    @EventHandler public void onJoin(PlayerJoinEvent e) { exemptFor(e.getPlayer(), 3000); }
    @EventHandler public void onQuit(PlayerQuitEvent e) { data.remove(e.getPlayer().getUniqueId()); }
    @EventHandler public void onTp(PlayerTeleportEvent e) { exemptFor(e.getPlayer(), 2000); }
    @EventHandler public void onRespawn(PlayerRespawnEvent e) { exemptFor(e.getPlayer(), 3000); }
    @EventHandler public void onVel(PlayerVelocityEvent e) { exemptFor(e.getPlayer(), 2500); }
    @EventHandler public void onGm(PlayerGameModeChangeEvent e) { exemptFor(e.getPlayer(), 3000); }
    @EventHandler public void onToggleFlight(PlayerToggleFlightEvent e) { exemptFor(e.getPlayer(), 3000); }
    @EventHandler public void onDmg(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p) exemptFor(p, 2000); // knockback
    }

    /* ---- yardımcılar ---- */
    private boolean nearSpecialBlock(Location l) {
        for (int x = -1; x <= 1; x++)
            for (int y = -1; y <= 2; y++)
                for (int z = -1; z <= 1; z++) {
                    Block b = l.clone().add(x, y, z).getBlock();
                    if (b.isLiquid()) return true;
                    String n = b.getType().name();
                    if (n.contains("LADDER") || n.contains("VINE") || n.contains("SCAFFOLDING")
                            || n.contains("WATER") || n.contains("LAVA") || n.contains("BUBBLE")
                            || n.contains("COBWEB") || n.contains("SLIME") || n.contains("HONEY")
                            || n.contains("POWDER_SNOW") || n.contains("TWISTING") || n.contains("WEEPING")
                            || n.contains("CAVE_VINES") || n.contains("ICE") || n.endsWith("_BED")
                            || n.contains("DRIPLEAF") || n.contains("POINTED_DRIPSTONE") || n.contains("BERRY")
                            || n.contains("TRAPDOOR") || n.contains("DOOR") || n.contains("FENCE")
                            || n.contains("WALL") || n.contains("STAIRS") || n.contains("SLAB")
                            || n.contains("PISTON") || n.contains("SEA_PICKLE") || n.contains("CHORUS")) return true;
                }
        return false;
    }

    private boolean groundBelow(Location l) {
        for (double dx = -0.4; dx <= 0.4; dx += 0.4)
            for (double dz = -0.4; dz <= 0.4; dz += 0.4)
                if (!l.clone().add(dx, -0.6, dz).getBlock().isPassable()) return true;
        return false;
    }

    private boolean hasEffect(Player p, PotionEffectType t) { return p.hasPotionEffect(t); }

    /* ---- hareket + dönüş kontrolleri ---- */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        Location from = e.getFrom(), to = e.getTo();
        if (to == null || from.getWorld() != to.getWorld()) return;
        Data x = d(p);

        // Dönüş (aim) takibi: muafiyetten bağımsız tutulur
        float dyaw = Math.abs(wrap(to.getYaw() - from.getYaw()));
        if (dyaw > 0.01f) {
            x.lastYawDelta = dyaw;
            x.lastRotMs = System.currentTimeMillis();
        }

        if (exempt(p)) {
            x.airTicks = 0; x.flyBuf = 0; x.speedBuf = 0;
            if (groundBelow(to)) x.lastSafe = to.clone();
            return;
        }

        /* TIMER: saniyede çok fazla hareket paketi */
        if (getConfig().getBoolean("checks.timer.enabled")) {
            long nowT = System.currentTimeMillis();
            if (nowT - x.timerStart >= 1000) {
                if (x.timerStart != 0 && x.timerCount > getConfig().getInt("checks.timer.max-packets")) {
                    x.timerBuf++;
                    if (x.timerBuf >= 3) {
                        flag(p, "Timer (" + x.timerCount + "/sn)");
                        setback(p);
                        x.timerBuf = 0;
                    }
                } else {
                    x.timerBuf = Math.max(0, x.timerBuf - 1);
                }
                x.timerStart = nowT;
                x.timerCount = 0;
            }
            x.timerCount++;
        }

        /* JESUS: suyun üstünde yürüme */
        if (getConfig().getBoolean("checks.jesus.enabled")) {
            double dyJ = to.getY() - from.getY();
            Block cur = to.getBlock();
            Block below = to.clone().subtract(0, 0.2, 0).getBlock();
            if (below.getType() == Material.WATER && !p.isInWater() && !p.isSwimming()
                    && !cur.isLiquid() && cur.getType() != Material.LILY_PAD && Math.abs(dyJ) < 0.02) {
                x.jesusTicks++;
                if (x.jesusTicks >= 12) {
                    flag(p, "Jesus");
                    setback(p);
                    x.jesusTicks = 0;
                }
            } else {
                x.jesusTicks = 0;
            }
        }

        boolean special = nearSpecialBlock(to)
                || hasEffect(p, PotionEffectType.LEVITATION)
                || hasEffect(p, PotionEffectType.SLOW_FALLING)
                || hasEffect(p, PotionEffectType.JUMP_BOOST);

        boolean ground = groundBelow(to);
        if (ground && !special) x.lastSafe = to.clone();

        /* STEP: yerdeyken tek harekette yüksek çıkma */
        if (getConfig().getBoolean("checks.step.enabled") && x.airTicks == 0 && !special) {
            double dyS = to.getY() - from.getY();
            if (dyS > 0.8) {
                x.stepBuf++;
                if (x.stepBuf >= 2) {
                    flag(p, "Step");
                    setback(p);
                    x.stepBuf = 0;
                }
            } else {
                x.stepBuf = Math.max(0, x.stepBuf - 1);
            }
        }

        /* FLY */
        if (getConfig().getBoolean("checks.fly.enabled")) {
            double dy = to.getY() - from.getY();
            if (ground || special) {
                x.airTicks = 0;
                x.flyBuf = Math.max(0, x.flyBuf - 1);
            } else {
                x.airTicks++;
                // Glide / yavaş düşme bypass'ı: normal düşüş hızlanır, hile yavaş kalır
                if (x.airTicks > 18 && dy > -0.25 && dy < -0.01) {
                    x.glideBuf++;
                    if (x.glideBuf >= 5) {
                        flag(p, "Fly (Glide)");
                        setback(p);
                        x.glideBuf = 0;
                    }
                } else if (dy < -0.4) {
                    x.glideBuf = 0;
                }
                if (x.airTicks > 14 && dy >= -0.01) {
                    x.flyBuf++;
                    if (x.flyBuf >= getConfig().getInt("checks.fly.buffer")) {
                        flag(p, "Fly");
                        setback(p);
                        x.flyBuf = 0;
                    }
                } else if (dy < -0.05) {
                    x.flyBuf = Math.max(0, x.flyBuf - 1);
                }
            }
        }

        /* SPEED */
        if (getConfig().getBoolean("checks.speed.enabled")) {
            double dx = to.getX() - from.getX(), dz = to.getZ() - from.getZ();
            double h = Math.sqrt(dx * dx + dz * dz);
            double max = getConfig().getDouble("checks.speed.max-horizontal");
            var eff = p.getPotionEffect(PotionEffectType.SPEED);
            if (eff != null) max += 0.2 * (eff.getAmplifier() + 1);
            max += p.getWalkSpeed() > 0.2f ? (p.getWalkSpeed() - 0.2f) * 3 : 0;
            if (special) max += 0.5;
            if (h > max) {
                x.speedBuf++;
                if (x.speedBuf >= getConfig().getInt("checks.speed.buffer")) {
                    flag(p, "Speed");
                    setback(p);
                    x.speedBuf = 0;
                }
            } else {
                x.speedBuf = Math.max(0, x.speedBuf - 1);
            }
        }
    }

    private float wrap(float a) {
        a %= 360f;
        if (a >= 180f) a -= 360f;
        if (a < -180f) a += 360f;
        return a;
    }

    /* ---- saldırı kontrolleri: Reach, KillAura, Aim, ElytraTarget, AutoClicker ---- */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p)) return;
        if (p.hasPermission("krosnetwork.hile.bypass")) return;
        if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) return;
        if (e.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        Data x = d(p);
        if (x.punishing) { e.setCancelled(true); return; }

        Entity target = e.getEntity();
        long now = System.currentTimeMillis();

        Location eye = p.getEyeLocation();
        Vector center = target.getBoundingBox().getCenter();
        Vector toTarget = center.clone().subtract(eye.toVector());
        double dist = toTarget.length();

        /* REACH */
        var bb = target.getBoundingBox();
        Vector ev = eye.toVector();
        Vector near = new Vector(
                Math.max(bb.getMinX(), Math.min(ev.getX(), bb.getMaxX())),
                Math.max(bb.getMinY(), Math.min(ev.getY(), bb.getMaxY())),
                Math.max(bb.getMinZ(), Math.min(ev.getZ(), bb.getMaxZ())));
        double reach = ev.distance(near);
        if (getConfig().getBoolean("checks.reach.enabled")) {
            if (reach > getConfig().getDouble("checks.reach.max-distance")) {
                x.reachBuf++;
                if (x.reachBuf >= 2) {
                    flag(p, "Reach (" + String.format("%.1f", reach) + ")");
                    x.reachBuf = 0;
                }
                e.setCancelled(true);
                return;
            } else {
                x.reachBuf = Math.max(0, x.reachBuf - 1);
            }
        }

        double angle = dist < 1.5 ? 0 : Math.toDegrees(eye.getDirection().angle(toTarget));

        /* KILLAURA: arkandan / çok yanlış açıdan vurma */
        if (getConfig().getBoolean("checks.killaura.enabled") && dist >= 1.5) {
            if (angle > getConfig().getDouble("checks.killaura.max-angle")) {
                x.angleBuf++;
                if (x.angleBuf >= 2) {
                    flag(p, "KillAura (açı " + (int) angle + "°)");
                    x.angleBuf = 0;
                }
                e.setCancelled(true);
                return;
            } else {
                x.angleBuf = Math.max(0, x.angleBuf - 1);
            }

            /* KILLAURA: Multi-Aura (aynı anda 2 farklı hedefe vurma) */
            if (x.lastHitTarget != null && !x.lastHitTarget.equals(target.getUniqueId())
                    && now - x.lastHitMs < 40) {
                x.multiBuf++;
                if (x.multiBuf >= 3) {
                    flag(p, "KillAura (Multi)");
                    x.multiBuf = 0;
                }
                e.setCancelled(true);
                x.lastHitTarget = target.getUniqueId();
                x.lastHitMs = now;
                return;
            } else {
                x.multiBuf = Math.max(0, x.multiBuf - 1);
            }
        }
        x.lastHitTarget = target.getUniqueId();
        x.lastHitMs = now;

        /* AIM + ELYTRATARGET: ani büyük dönüş sonrası kusursuz nişan */
        boolean recentSnap = now - x.lastRotMs < 120 && x.lastYawDelta > 30f;
        if (dist >= 2.0 && recentSnap) {
            if (p.isGliding()) {
                if (getConfig().getBoolean("checks.elytratarget.enabled")) {
                    if (angle < 2.5) {
                        x.elytraBuf++;
                        if (x.elytraBuf >= getConfig().getInt("checks.elytratarget.buffer")) {
                            flag(p, "ElytraTarget");
                            x.elytraBuf = 0;
                        }
                    } else {
                        x.elytraBuf = Math.max(0, x.elytraBuf - 1);
                    }
                }
            } else if (getConfig().getBoolean("checks.aim.enabled")) {
                if (angle < 1.0) {
                    x.aimBuf++;
                    if (x.aimBuf >= getConfig().getInt("checks.aim.buffer")) {
                        flag(p, "Aim");
                        x.aimBuf = 0;
                    }
                } else {
                    x.aimBuf = Math.max(0, x.aimBuf - 1);
                }
            }
        }

        /* AUTOCLICKER */
        if (getConfig().getBoolean("checks.autoclicker.enabled")) {
            x.clicks.addLast(now);
            while (!x.clicks.isEmpty() && now - x.clicks.peekFirst() > 1000) x.clicks.pollFirst();
            if (x.clicks.size() > getConfig().getInt("checks.autoclicker.max-cps")) {
                flag(p, "AutoClicker (" + x.clicks.size() + " CPS)");
                e.setCancelled(true);
            }
        }
    }

    /* ---- ceza / uyarı ---- */
    private void setback(Player p) {
        Data x = d(p);
        if (x.lastSafe != null && x.lastSafe.getWorld() == p.getWorld()) {
            Location t = x.lastSafe.clone();
            t.setYaw(p.getLocation().getYaw());
            t.setPitch(p.getLocation().getPitch());
            p.teleport(t);
        }
    }

    private void flag(Player p, String check) {
        Data x = d(p);
        if (x.punishing) return;
        long now = System.currentTimeMillis();

        long resetMs = getConfig().getLong("punish.flag-reset-minutes", 5) * 60_000L;
        if (now - x.lastFlagTime > resetMs) x.flags = 0; // eski şüpheler silinir
        x.lastFlagTime = now;
        x.flags++;

        int limit = getConfig().getInt("punish.flags-to-punish", 5);
        String msg = color(getConfig().getString("prefix", "&c[Krosnetwork] &7"))
                + p.getName() + " &f-> &e" + check + " &7(" + x.flags + "/" + limit + ")";
        if (now - x.lastAlert > 1000) {
            x.lastAlert = now;
            for (Player s : getServer().getOnlinePlayers())
                if (s.hasPermission("krosnetwork.hile.alert")) s.sendMessage(msg);
            getLogger().info(ChatColor.stripColor(msg));
        }

        if (getConfig().getBoolean("punish.enabled", true) && x.flags >= limit) {
            punish(p);
        }
    }

    private void punish(Player p) {
        Data x = d(p);
        x.punishing = true;
        x.flags = 0;

        String kickText = getConfig().getString("punish.kick-message", "&cYakalandın hileci!");
        int liftTicks = getConfig().getInt("punish.lift-ticks", 50);

        // Etraf kararır
        p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 200, 1, false, false, false));
        p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 200, 1, false, false, false));

        // "Yakalandın hileci" mesajı
        p.showTitle(Title.title(
                Component.text("YAKALANDIN HİLECİ!", NamedTextColor.RED, TextDecoration.BOLD),
                Component.text("Krosnetwork Hile Koruması", NamedTextColor.GRAY),
                Title.Times.times(Duration.ZERO, Duration.ofSeconds(3), Duration.ZERO)));
        p.sendMessage(color("&c&lYakalandın hileci!"));
        p.playSound(p.getLocation(), Sound.ENTITY_WITHER_SPAWN, 1f, 1f);

        String staffMsg = color(getConfig().getString("prefix", "&c[Krosnetwork] &7"))
                + "&c" + p.getName() + " &7hile nedeniyle oyundan atılıyor.";
        for (Player s : getServer().getOnlinePlayers())
            if (s.hasPermission("krosnetwork.hile.alert")) s.sendMessage(staffMsg);
        getLogger().info(ChatColor.stripColor(staffMsg));

        // Birden yukarı taşı, sonra at
        new BukkitRunnable() {
            int t = 0;
            @Override
            public void run() {
                if (!p.isOnline()) { cancel(); return; }
                if (t++ < liftTicks) {
                    p.setVelocity(new Vector(0, 1.0, 0));
                } else {
                    cancel();
                    p.kick(Component.text(ChatColor.stripColor(color(kickText))));
                }
            }
        }.runTaskTimer(this, 0L, 1L);
    }

    private String color(String s) { return ChatColor.translateAlternateColorCodes('&', s); }

    @Override
    public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        if (!s.hasPermission("krosnetwork.hile.admin")) return true;
        if (a.length > 0 && a[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            s.sendMessage(color("&aKrosnetwork Hile Koruması config yenilendi."));
        } else {
            s.sendMessage(color("&e/hile reload"));
        }
        return true;
    }
          }
