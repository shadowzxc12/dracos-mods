package com.dracos.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.text.TextCodecs;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Spawn hub: "/hub build" (owner/OP) builds it where you stand - fountain plaza, crates, AFK zone,
 * duel arena and leaderboards - and remembers all positions. Inside the hub nobody (except the owner or
 * creative players) can break/place blocks, players take no damage (except in a duel) and hostile mobs
 * don't stay. Standing in the AFK zone pays money and sometimes crate keys.
 */
public final class Hub {
    private Hub() {}

    public static final class Cfg {
        public String world = "minecraft:overworld";
        public int cx, cy, cz;                       // centre (floor y = cy - 1)
        public int radius = 0;                       // 0 = no hub yet
        public int[] afk;                            // x1,y1,z1,x2,y2,z2
        public Map<String, int[]> crates = new HashMap<>();
        public int[] arena;                          // centre x,y,z
        public int[] arenaA, arenaB;                 // duel spawn points
        public Map<String, double[]> boards = new HashMap<>();
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static Cfg CFG = new Cfg();
    static final int AFK_SECONDS = 300;
    static final long AFK_MONEY = 7_500;
    private static final Map<UUID, Integer> AFK_TIME = new HashMap<>();
    private static final List<Entity> TO_REMOVE = new ArrayList<>();

    private static Path file() { return FabricLoader.getInstance().getConfigDir().resolve("dracos").resolve("hub.json"); }

    public static void load() {
        try {
            if (Files.exists(file())) {
                Cfg c = GSON.fromJson(Files.readString(file()), Cfg.class);
                if (c != null) CFG = c;
                if (CFG.crates == null) CFG.crates = new HashMap<>();
                if (CFG.boards == null) CFG.boards = new HashMap<>();
            }
        } catch (Exception e) { DracosCore.LOGGER.warn("hub.json unreadable", e); }
    }

    static void save() {
        try { Files.createDirectories(file().getParent()); Files.writeString(file(), GSON.toJson(CFG)); }
        catch (Exception e) { DracosCore.LOGGER.warn("hub.json save failed", e); }
    }

    // ------------------------------------------------------------------ areas
    public static boolean inHub(Entity e) {
        if (CFG.radius <= 0 || !(e.getEntityWorld() instanceof ServerWorld w)) return false;
        if (!w.getRegistryKey().getValue().toString().equals(CFG.world)) return false;
        return Math.abs(e.getX() - CFG.cx) <= CFG.radius && Math.abs(e.getZ() - CFG.cz) <= CFG.radius;
    }

    static boolean inHub(ServerWorld w, BlockPos p) {
        if (CFG.radius <= 0 || !w.getRegistryKey().getValue().toString().equals(CFG.world)) return false;
        return Math.abs(p.getX() - CFG.cx) <= CFG.radius && Math.abs(p.getZ() - CFG.cz) <= CFG.radius;
    }

    static boolean inAfk(ServerPlayerEntity p) {
        int[] a = CFG.afk;
        if (a == null || !inHub(p)) return false;
        double x = p.getX(), y = p.getY(), z = p.getZ();
        return x >= Math.min(a[0], a[3]) && x <= Math.max(a[0], a[3]) + 1 && z >= Math.min(a[2], a[5]) && z <= Math.max(a[2], a[5]) + 1
                && y >= Math.min(a[1], a[4]) - 1 && y <= Math.max(a[1], a[4]) + 3;
    }

    static boolean bypass(PlayerEntity p) {
        return p.isCreative() || (p instanceof ServerPlayerEntity sp && Ranks.of(sp) == Ranks.Rank.OWNER);
    }

    public static ServerWorld world(MinecraftServer server) {
        for (ServerWorld w : server.getWorlds()) if (w.getRegistryKey().getValue().toString().equals(CFG.world)) return w;
        return server.getOverworld();
    }

    // ------------------------------------------------------------------ events
    public static void register() {
        // no damage in the hub (except duels)
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (!(entity instanceof ServerPlayerEntity p)) return true;
            if (Duels.inDuel(p)) return true;
            return !inHub(p);
        });
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, be) ->
                !(world instanceof ServerWorld sw) || bypass(player) || !inHub(sw, pos));
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (!(world instanceof ServerWorld sw) || bypass(player)) return ActionResult.PASS;
            if (!inHub(sw, hit.getBlockPos()) && !inHub(sw, hit.getBlockPos().offset(hit.getSide()))) return ActionResult.PASS;
            return Crates.at(hit.getBlockPos()) != null ? ActionResult.PASS : ActionResult.FAIL;
        });
        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (!(world instanceof ServerWorld) || bypass(player) || !inHub(player)) return ActionResult.PASS;
            var it = player.getStackInHand(hand).getItem();
            if (it == Items.WATER_BUCKET || it == Items.LAVA_BUCKET || it == Items.FLINT_AND_STEEL || it == Items.FIRE_CHARGE
                    || it == Items.END_CRYSTAL || it == Items.TNT_MINECART) return ActionResult.FAIL;
            return ActionResult.PASS;
        });
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (entity instanceof HostileEntity && CFG.radius > 0 && inHub(entity)) TO_REMOVE.add(entity);
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (!TO_REMOVE.isEmpty()) { for (Entity e : TO_REMOVE) if (!e.isRemoved()) e.discard(); TO_REMOVE.clear(); }
            if (server.getTicks() % 20 != 0) return;
            for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                if (!inAfk(p)) { AFK_TIME.remove(p.getUuid()); continue; }
                int t = AFK_TIME.merge(p.getUuid(), 1, Integer::sum);
                int left = AFK_SECONDS - (t % AFK_SECONDS);
                p.sendMessage(Text.literal("AFK Zone » next reward in " + left / 60 + ":" + String.format("%02d", left % 60))
                        .formatted(Formatting.LIGHT_PURPLE, Formatting.BOLD), true);
                if (t % AFK_SECONDS == 0) afkReward(p);
            }
        });
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register((h, s) -> AFK_TIME.remove(h.player.getUuid()));
    }

    static void afkReward(ServerPlayerEntity p) {
        long money = AFK_MONEY;
        Ranks.Rank r = Ranks.of(p);
        money += money * r.sellBonus / 100;
        Money.add(p, money);
        String key = null;
        double roll = Math.random();
        if (roll < 0.03) key = "legendary";
        else if (roll < 0.15) key = "rare";
        else if (roll < 0.50) key = "common";
        if (key != null) Crates.giveKey(p, key, 1);
        p.sendMessage(Text.literal("AFK reward: ").formatted(Formatting.LIGHT_PURPLE)
                .append(Text.literal("+" + Money.fmt(money)).formatted(Formatting.GOLD, Formatting.BOLD))
                .append(Text.literal(key != null ? "  + 1 " + Crates.title(key) + " key!" : "").formatted(Formatting.AQUA)), false);
        p.getEntityWorld().playSound(null, p.getBlockPos(), SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.PLAYERS, 0.8f, 1.2f);
    }

    // ------------------------------------------------------------------ commands
    public static void commands(CommandDispatcher<ServerCommandSource> d) {
        d.register(CommandManager.literal("hub")
                .requires(CommandManager.requirePermissionLevel(CommandManager.GAMEMASTERS_CHECK))
                .then(CommandManager.literal("build").executes(ctx -> { build(ctx.getSource().getPlayerOrThrow()); return 1; }))
                .then(CommandManager.literal("refresh").executes(ctx -> { Leaderboards.refresh(ctx.getSource().getServer()); labels(ctx.getSource().getServer()); return 1; }))
                .then(CommandManager.literal("info").executes(ctx -> {
                    ctx.getSource().sendFeedback(() -> Text.literal(CFG.radius <= 0 ? "No hub yet - stand where you want it and use /hub build"
                            : "Hub at " + CFG.cx + " " + CFG.cy + " " + CFG.cz + " radius " + CFG.radius), false);
                    return 1;
                })));
    }

    // ------------------------------------------------------------------ text displays (crate names, AFK zone, welcome)
    static void cmd(MinecraftServer server, String c) {
        try { server.getCommandManager().parseAndExecute(server.getCommandSource().withSilent(), c); }
        catch (Throwable t) { DracosCore.LOGGER.warn("hub command failed: {}", c, t); }
    }

    static String snbt(Text t) {
        return TextCodecs.CODEC.encodeStart(NbtOps.INSTANCE, t).result().map(Object::toString).orElse("\"\"");
    }

    /** Spawns a floating text with tag dracos_hub (+ extra tag). */
    static void hologram(MinecraftServer server, double x, double y, double z, Text text, String tag) {
        cmd(server, "execute in " + CFG.world + " run summon minecraft:text_display " + x + " " + y + " " + z
                + " {billboard:\"center\",see_through:0b,shadow:1b,background:1073741824,line_width:220,Tags:[\"dracos_hub\",\"" + tag + "\"],text:"
                + snbt(text) + "}");
    }

    static void labels(MinecraftServer server) {
        cmd(server, "execute in " + CFG.world + " run kill @e[type=minecraft:text_display,tag=dracos_label]");
        MutableText welcome = Text.empty()
                .append(Text.literal("✦ DRACOS SMP ✦\n").formatted(Formatting.GOLD, Formatting.BOLD))
                .append(Text.literal("/rtp  /shop  /ah  /orders\n").formatted(Formatting.YELLOW))
                .append(Text.literal("/quests  /clan  /duel  /trade").formatted(Formatting.AQUA));
        hologram(server, CFG.cx + 0.5, CFG.cy + 4.2, CFG.cz + 0.5, welcome, "dracos_label");
        for (var e : CFG.crates.entrySet()) {
            int[] c = e.getValue();
            MutableText t = Text.empty().append(Text.literal(Crates.title(e.getKey()) + " Crate\n").formatted(Crates.color(e.getKey()), Formatting.BOLD))
                    .append(Text.literal("Right-click with a key\nLeft-click to preview").formatted(Formatting.GRAY));
            hologram(server, c[0] + 0.5, c[1] + 1.6, c[2] + 0.5, t, "dracos_label");
        }
        if (CFG.afk != null) {
            int[] a = CFG.afk;
            MutableText t = Text.empty().append(Text.literal("☾ AFK ZONE ☽\n").formatted(Formatting.LIGHT_PURPLE, Formatting.BOLD))
                    .append(Text.literal("Stand here: " + Money.fmt(AFK_MONEY) + " every 5 min\n+ chance for crate keys").formatted(Formatting.GRAY));
            hologram(server, (a[0] + a[3]) / 2.0 + 0.5, a[1] + 3.2, (a[2] + a[5]) / 2.0 + 0.5, t, "dracos_label");
        }
        if (CFG.arena != null) {
            MutableText t = Text.empty().append(Text.literal("⚔ DUEL ARENA ⚔\n").formatted(Formatting.RED, Formatting.BOLD))
                    .append(Text.literal("/duel <player> [bet]\nNo items are lost").formatted(Formatting.GRAY));
            hologram(server, CFG.cx + 24.5, CFG.cy + 3.0, CFG.cz + 0.5, t, "dracos_label");
        }
    }

    // ------------------------------------------------------------------ build
    private static void set(ServerWorld w, int x, int y, int z, BlockState s) {
        w.setBlockState(new BlockPos(x, y, z), s, 2);
    }

    static void build(ServerPlayerEntity p) {
        ServerWorld w = (ServerWorld) p.getEntityWorld();
        MinecraftServer server = w.getServer();
        int cx = p.getBlockX(), cy = p.getBlockY(), cz = p.getBlockZ();
        int R = 30;                      // plaza half-size
        int ax = cx + 48;                // arena centre (east of the plaza)
        int AR = 11;                     // arena half-size
        cmd(server, "execute in " + w.getRegistryKey().getValue() + " run kill @e[type=minecraft:text_display,tag=dracos_hub]");

        BlockState air = Blocks.AIR.getDefaultState();
        BlockState base = Blocks.STONE_BRICKS.getDefaultState();
        // clear + floor + foundation for the plaza and the path to the arena
        for (int x = cx - R - 1; x <= ax + AR + 1; x++) {
            for (int z = cz - R - 1; z <= cz + R + 1; z++) {
                boolean plaza = Math.abs(x - cx) <= R && Math.abs(z - cz) <= R;
                boolean arena = Math.abs(x - ax) <= AR + 1 && Math.abs(z - cz) <= AR + 1;
                boolean path = x > cx + R && x < ax - AR && Math.abs(z - cz) <= 2;
                if (!plaza && !arena && !path) continue;
                for (int y = cy; y <= cy + 14; y++) set(w, x, y, z, air);
                for (int y = cy - 4; y <= cy - 2; y++) if (w.getBlockState(new BlockPos(x, y, z)).isAir() || !w.getFluidState(new BlockPos(x, y, z)).isEmpty())
                    set(w, x, y, z, base);
                set(w, x, cy - 1, z, floor(x - cx, z - cz, plaza, arena, path));
            }
        }
        // plaza rim: low wall with lantern posts, gaps for the 4 paths
        for (int i = -R; i <= R; i++) {
            for (int[] e : new int[][]{{cx + i, cz - R}, {cx + i, cz + R}, {cx - R, cz + i}, {cx + R, cz + i}}) {
                boolean gap = Math.abs(i) <= 2;
                if (gap) continue;
                boolean post = Math.abs(i) % 6 == 0;
                set(w, e[0], cy, e[1], post ? Blocks.CHISELED_STONE_BRICKS.getDefaultState() : Blocks.STONE_BRICK_WALL.getDefaultState());
                if (post) { set(w, e[0], cy + 1, e[1], Blocks.STONE_BRICK_WALL.getDefaultState()); set(w, e[0], cy + 2, e[1], Blocks.LANTERN.getDefaultState()); }
            }
        }
        // fountain
        for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) {
            int d = Math.max(Math.abs(x), Math.abs(z));
            if (d == 3) set(w, cx + x, cy, cz + z, Blocks.QUARTZ_BRICKS.getDefaultState());
            else { set(w, cx + x, cy - 1, cz + z, Blocks.PRISMARINE_BRICKS.getDefaultState()); set(w, cx + x, cy, cz + z, Blocks.WATER.getDefaultState()); }
        }
        set(w, cx, cy - 1, cz, Blocks.SEA_LANTERN.getDefaultState());
        for (int y = cy; y <= cy + 2; y++) set(w, cx, y, cz, Blocks.QUARTZ_PILLAR.getDefaultState());
        set(w, cx, cy + 3, cz, Blocks.SEA_LANTERN.getDefaultState());
        // lamp posts around the fountain
        for (int[] o : new int[][]{{6, 6}, {-6, 6}, {6, -6}, {-6, -6}, {14, 14}, {-14, 14}, {14, -14}, {-14, -14}}) {
            for (int y = cy; y <= cy + 2; y++) set(w, cx + o[0], y, cz + o[1], Blocks.POLISHED_BLACKSTONE_WALL.getDefaultState());
            set(w, cx + o[0], cy + 3, cz + o[1], Blocks.SEA_LANTERN.getDefaultState());
        }

        // ---- crates (north): pedestals + crate blocks
        CFG.crates.clear();
        String[] types = {"common", "rare", "legendary"};
        BlockState[] crateBlock = {Blocks.CHEST.getDefaultState(), Blocks.VAULT.getDefaultState(), Blocks.ENDER_CHEST.getDefaultState()};
        BlockState[] pedestal = {Blocks.POLISHED_ANDESITE.getDefaultState(), Blocks.PURPUR_BLOCK.getDefaultState(), Blocks.GOLD_BLOCK.getDefaultState()};
        for (int i = 0; i < 3; i++) {
            int x = cx - 8 + i * 8, z = cz - 18;
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) set(w, x + dx, cy - 1, z + dz, pedestal[i]);
            set(w, x, cy, z, crateBlock[i]);
            CFG.crates.put(types[i], new int[]{x, cy, z});
        }
        // ---- AFK zone (south): glowing pad with glass edge
        int az = cz + 20;
        for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) {
            boolean edge = Math.abs(x) == 4 || Math.abs(z) == 4;
            set(w, cx + x, cy - 2, az + z, Blocks.SEA_LANTERN.getDefaultState());
            set(w, cx + x, cy - 1, az + z, edge ? Blocks.AMETHYST_BLOCK.getDefaultState() : Blocks.PURPLE_STAINED_GLASS.getDefaultState());
            if (edge && (Math.abs(x) == 4 && Math.abs(z) == 4)) {
                for (int y = cy; y <= cy + 3; y++) set(w, cx + x, y, az + z, Blocks.AMETHYST_BLOCK.getDefaultState());
                set(w, cx + x, cy + 4, az + z, Blocks.AMETHYST_CLUSTER.getDefaultState());
            }
        }
        CFG.afk = new int[]{cx - 3, cy, az - 3, cx + 3, cy, az + 3};

        // ---- duel arena (east, at the end of the path)
        for (int x = -AR - 1; x <= AR + 1; x++) for (int z = -AR - 1; z <= AR + 1; z++) {
            boolean wall = Math.abs(x) == AR + 1 || Math.abs(z) == AR + 1;
            if (!wall) continue;
            boolean door = x == -AR - 1 && Math.abs(z) <= 1;
            for (int y = cy; y <= cy + 6; y++) {
                BlockState s = y <= cy + 3 ? Blocks.STONE_BRICKS.getDefaultState()
                        : y == cy + 4 ? Blocks.CHISELED_STONE_BRICKS.getDefaultState() : Blocks.BARRIER.getDefaultState();
                if (door && y <= cy + 2) s = Blocks.IRON_BARS.getDefaultState();
                set(w, ax + x, y, cz + z, s);
            }
            if (!door && (Math.abs(x) + Math.abs(z)) % 6 == 0) set(w, ax + x, cy + 5, cz + z, Blocks.SOUL_LANTERN.getDefaultState());
        }
        CFG.arena = new int[]{ax, cy, cz};
        CFG.arenaA = new int[]{ax - 8, cy, cz};
        CFG.arenaB = new int[]{ax + 8, cy, cz};
        for (int[] s : new int[][]{CFG.arenaA, CFG.arenaB}) set(w, s[0], cy - 1, s[2], Blocks.REDSTONE_BLOCK.getDefaultState());

        // ---- leaderboards (west wall)
        CFG.boards.clear();
        String[] boards = {"money", "kills", "playtime"};
        for (int i = 0; i < 3; i++) {
            int z = cz - 10 + i * 10, x = cx - 24;
            for (int dz = -2; dz <= 2; dz++) for (int y = cy; y <= cy + 4; y++)
                set(w, x - 1, y, z + dz, Blocks.POLISHED_BLACKSTONE_BRICKS.getDefaultState());
            set(w, x - 1, cy + 5, z - 2, Blocks.LANTERN.getDefaultState());
            set(w, x - 1, cy + 5, z + 2, Blocks.LANTERN.getDefaultState());
            CFG.boards.put(boards[i], new double[]{x + 0.3, cy + 1.3, z + 0.5});
        }

        CFG.world = w.getRegistryKey().getValue().toString();
        CFG.cx = cx; CFG.cy = cy; CFG.cz = cz;
        CFG.radius = ax + AR + 3 - cx;
        save();
        labels(server);
        Leaderboards.refresh(server);
        // world spawn + Essential Commands /spawn
        cmd(server, "execute in " + CFG.world + " run setworldspawn " + cx + " " + cy + " " + (cz + 6));
        p.teleport(w, cx + 0.5, cy, cz + 6.5, java.util.Set.of(), 180f, 0f, true);
        try { server.getCommandManager().parseAndExecute(p.getCommandSource(), "setspawn"); } catch (Throwable ignored) {}
        p.sendMessage(Text.literal("Hub built! Crates north, AFK zone south, leaderboards west, duel arena east.").formatted(Formatting.GREEN), false);
    }

    private static BlockState floor(int x, int z, boolean plaza, boolean arena, boolean path) {
        if (arena && !plaza) {
            int ax = x - 48;
            return (Math.abs(ax) + Math.abs(z)) % 2 == 0 ? Blocks.SMOOTH_STONE.getDefaultState() : Blocks.POLISHED_ANDESITE.getDefaultState();
        }
        if (path && !plaza) return Math.abs(z) == 2 ? Blocks.POLISHED_BLACKSTONE_BRICKS.getDefaultState() : Blocks.DEEPSLATE_TILES.getDefaultState();
        int d = Math.max(Math.abs(x), Math.abs(z));
        if (Math.abs(x) <= 2 || Math.abs(z) <= 2) return Blocks.POLISHED_DEEPSLATE.getDefaultState();           // cross paths
        if (d % 8 == 0) return Blocks.POLISHED_BLACKSTONE_BRICKS.getDefaultState();                             // rings
        return ((x + z) & 1) == 0 ? Blocks.DEEPSLATE_TILES.getDefaultState() : Blocks.DEEPSLATE_BRICKS.getDefaultState();
    }

    static Box arenaBox() {
        int[] a = CFG.arena;
        return new Box(a[0] - 12, a[1] - 3, a[2] - 12, a[0] + 13, a[1] + 8, a[2] + 13);
    }
}
