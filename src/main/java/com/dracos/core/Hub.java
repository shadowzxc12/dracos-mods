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
 * Spawn hub: "/buildspawn" (owner/OP) builds it where you stand - fountain plaza, crates, AFK zone,
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
        public int arenaR;                           // arena radius (0 = old 11-block square arena)
        public Map<String, double[]> boards = new HashMap<>();
        public int[] portal;                         // RTP portal x1,y1,z1,x2,y2,z2 (walk in -> /rtp)
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

    public static boolean inHubPublic(ServerWorld w, BlockPos p) { return inHub(w, p); }

    static boolean inAfk(ServerPlayerEntity p) {
        int[] a = CFG.afk;
        if (a == null || !inHub(p)) return false;
        double x = p.getX(), y = p.getY(), z = p.getZ();
        return x >= Math.min(a[0], a[3]) && x <= Math.max(a[0], a[3]) + 1 && z >= Math.min(a[2], a[5]) && z <= Math.max(a[2], a[5]) + 1
                && y >= Math.min(a[1], a[4]) - 1 && y <= Math.max(a[1], a[4]) + 3;
    }

    static boolean bypass(PlayerEntity p) {
        return p.isCreative();   // even the owner can only change spawn in creative mode
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
            if (!inHub(p)) return true;
            if (source.getAttacker() instanceof ServerPlayerEntity att && att != p)
                att.sendMessage(Text.literal("PvP is off at spawn - use /duel or go out with /rtp").formatted(Formatting.RED), true);
            return false;
        });
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, be) -> {
            if (!(world instanceof ServerWorld sw) || bypass(player) || !inHub(sw, pos)) return true;
            if (player instanceof ServerPlayerEntity sp && duelBuildable(sp, pos) && DUEL_PLACED.contains(pos.asLong())) {
                sw.breakBlock(pos, false, player);           // only blocks placed in this duel; no drops
                DUEL_PLACED.remove(pos.asLong());
                return false;
            }
            return false;
        });
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (!(world instanceof ServerWorld sw) || bypass(player)) return ActionResult.PASS;
            if (!inHub(sw, hit.getBlockPos()) && !inHub(sw, hit.getBlockPos().offset(hit.getSide()))) return ActionResult.PASS;
            if (player instanceof ServerPlayerEntity sp && duelBuildable(sp, hit.getBlockPos().offset(hit.getSide()))
                    && player.getStackInHand(hand).getItem() instanceof net.minecraft.item.BlockItem) {
                DUEL_PLACED.add(hit.getBlockPos().offset(hit.getSide()).asLong());   // these (and only these) may be broken again
                return ActionResult.PASS;
            }
            return Crates.at(hit.getBlockPos()) != null ? ActionResult.PASS : ActionResult.FAIL;
        });
        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (!(world instanceof ServerWorld) || bypass(player) || !inHub(player)) return ActionResult.PASS;
            var it = player.getStackInHand(hand).getItem();
            if (it == Items.WATER_BUCKET || it == Items.LAVA_BUCKET || it == Items.FLINT_AND_STEEL || it == Items.FIRE_CHARGE
                    || it == Items.END_CRYSTAL || it == Items.TNT_MINECART) return ActionResult.FAIL;
            return ActionResult.PASS;
        });
        // menu NPCs: right-click runs their command
        net.fabricmc.fabric.api.event.player.UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (!(player instanceof ServerPlayerEntity sp) || !(world instanceof ServerWorld)) return ActionResult.PASS;
            for (String tag : entity.getCommandTags()) {
                if (!tag.startsWith("npc_")) continue;
                if (hand == net.minecraft.util.Hand.MAIN_HAND) {
                    String c = tag.substring(4);
                    if (c.equals("shop")) Shop.open(sp);      // our new shop (the /shop command is swapped in at packet level)
                    else sp.getEntityWorld().getServer().getCommandManager().parseAndExecute(sp.getCommandSource(), c);
                }
                return ActionResult.FAIL;
            }
            return ActionResult.PASS;
        });
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (entity instanceof net.minecraft.entity.mob.Monster && CFG.radius > 0 && inHub(entity)) TO_REMOVE.add(entity);
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (!TO_REMOVE.isEmpty()) { for (Entity e : TO_REMOVE) if (!e.isRemoved()) e.discard(); TO_REMOVE.clear(); }
            if (!JOBS.isEmpty()) runJobs();
            if (CFG.portal != null && server.getTicks() % 5 == 0) portalTick(server);
            // no monsters at spawn or in the arena: also catch ones that walk / fly / get pushed in
            if (CFG.radius > 0 && server.getTicks() % 40 == 13) {
                ServerWorld hw = world(server);
                net.minecraft.util.math.Box area = new net.minecraft.util.math.Box(CFG.cx - CFG.radius, hw.getBottomY(), CFG.cz - CFG.radius,
                        CFG.cx + CFG.radius + 1, hw.getTopYInclusive() + 1, CFG.cz + CFG.radius + 1);
                for (var m : hw.getEntitiesByClass(net.minecraft.entity.mob.MobEntity.class, area,
                        e -> e instanceof net.minecraft.entity.mob.Monster && !e.getCommandTags().contains("dracos_hub"))) m.discard();
            }
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
        // brand-new players start at the spawn hub
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register((h, sender, server) -> {
            if (CFG.radius <= 0) return;
            ServerPlayerEntity p = h.player;
            int left = p.getStatHandler().getStat(net.minecraft.stat.Stats.CUSTOM.getOrCreateStat(net.minecraft.stat.Stats.LEAVE_GAME));
            if (left > 0) return;
            server.execute(() -> p.teleport(world(server), CFG.cx + 0.5, CFG.cy, CFG.cz + 6.5, java.util.Set.of(), 180f, 0f, true));
        });
    }

    private static final Map<UUID, Long> PORTAL_CD = new HashMap<>();

    static void portalTick(MinecraftServer server) {
        int[] a = CFG.portal;
        ServerWorld w = world(server);
        double mx = (a[0] + a[3]) / 2.0 + 0.5, my = (a[1] + a[4]) / 2.0 + 0.5, mz = (a[2] + a[5]) / 2.0 + 0.5;
        if (server.getTicks() % 10 == 0) Effects.p(w, net.minecraft.particle.ParticleTypes.PORTAL, mx, my, mz, 25, 1.2, 0.3);
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            if (p.getEntityWorld() != w) continue;
            double x = p.getX(), y = p.getY(), z = p.getZ();
            if (x < Math.min(a[0], a[3]) || x > Math.max(a[0], a[3]) + 1 || z < Math.min(a[2], a[5]) || z > Math.max(a[2], a[5]) + 1
                    || y < a[1] - 0.5 || y > a[4] + 1) continue;
            long now = System.currentTimeMillis();
            if (now - PORTAL_CD.getOrDefault(p.getUuid(), 0L) < 5000) continue;
            PORTAL_CD.put(p.getUuid(), now);
            p.teleport(w, x, y, z + 3, java.util.Set.of(), p.getYaw(), p.getPitch(), true);   // step back out of the frame
            RandomRespawn.rtp(p);
        }
    }

    static void afkReward(ServerPlayerEntity p) {
        long money = AFK_MONEY;
        Ranks.Rank r = Ranks.of(p);
        money += money * r.sellBonus / 100;
        Money.add(p, money);
        String key = null;
        double roll = Math.random();
        if (roll < 0.03) key = "legendary";
        else if (roll < 0.08) key = "spawner";
        else if (roll < 0.18) key = "rare";
        else if (roll < 0.50) key = "common";
        if (key != null) Crates.giveKey(p, key, 1);
        p.sendMessage(Text.literal("AFK reward: ").formatted(Formatting.LIGHT_PURPLE)
                .append(Text.literal("+" + Money.fmt(money)).formatted(Formatting.GOLD, Formatting.BOLD))
                .append(Text.literal(key != null ? "  + 1 " + Crates.title(key) + " key!" : "").formatted(Formatting.AQUA)), false);
        p.getEntityWorld().playSound(null, p.getBlockPos(), SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.PLAYERS, 0.8f, 1.2f);
    }

    // ------------------------------------------------------------------ commands
    public static void commands(CommandDispatcher<ServerCommandSource> d) {
        // NOTE: Minehut's proxy owns /hub and /lobby, so this is /buildspawn
        d.register(CommandManager.literal("buildspawn")
                .requires(CommandManager.requirePermissionLevel(CommandManager.GAMEMASTERS_CHECK))
                .executes(ctx -> { build(ctx.getSource().getPlayerOrThrow()); return 1; })
                .then(CommandManager.literal("border").executes(ctx -> { border(world(ctx.getSource().getServer())); ctx.getSource().sendFeedback(() -> Text.literal("Invisible border placed around spawn."), false); return 1; }))
                .then(CommandManager.literal("arena").executes(ctx -> {
                    if (CFG.radius <= 0) { ctx.getSource().sendError(Text.literal("No spawn yet - use /buildspawn")); return 0; }
                    if (Duels.CURRENT != null) { ctx.getSource().sendError(Text.literal("A duel is running, try again after it.")); return 0; }
                    ServerWorld w = world(ctx.getSource().getServer());
                    arena(w); save(); border(w); labels(ctx.getSource().getServer());
                    resetArena(w);
                    ctx.getSource().sendFeedback(() -> Text.literal("New duel arena built."), false);
                    return 1;
                }))
                .then(CommandManager.literal("rebuild").executes(ctx -> {
                    ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
                    if (CFG.radius <= 0) { p.sendMessage(Text.literal("No spawn yet - use /buildspawn").formatted(Formatting.RED), false); return 0; }
                    p.teleport(world(ctx.getSource().getServer()), CFG.cx + 0.5, CFG.cy, CFG.cz + 0.5, java.util.Set.of(), p.getYaw(), p.getPitch(), true);
                    build(p);
                    return 1;
                }))
                .then(CommandManager.literal("clear").executes(ctx -> { clear(ctx.getSource().getServer()); ctx.getSource().sendFeedback(() -> Text.literal("Removing the old spawn (takes a few seconds). Then stand somewhere else and use /buildspawn."), false); return 1; }))
                .then(CommandManager.literal("refresh").executes(ctx -> { Leaderboards.refresh(ctx.getSource().getServer()); labels(ctx.getSource().getServer()); return 1; }))
                .then(CommandManager.literal("info").executes(ctx -> {
                    ctx.getSource().sendFeedback(() -> Text.literal(CFG.radius <= 0 ? "No spawn yet - stand where you want it and use /buildspawn"
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
        if (CFG.portal != null) {
            int[] a = CFG.portal;
            MutableText t = Text.empty().append(Text.literal("✦ RANDOM TELEPORT ✦\n").formatted(Formatting.DARK_PURPLE, Formatting.BOLD))
                    .append(Text.literal("Walk into the portal").formatted(Formatting.GRAY));
            hologram(server, (a[0] + a[3]) / 2.0 + 0.5, a[4] + 2.6, a[2] + 0.5, t, "dracos_label");
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
        int AR = ARENA_R;                // arena radius
        int ax = cx + ARENA_OFF;         // arena centre (east of the plaza)
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
        String[] types = {"common", "rare", "spawner", "legendary"};
        BlockState[] crateBlock = {Blocks.CHEST.getDefaultState(), Blocks.VAULT.getDefaultState(), Blocks.SPAWNER.getDefaultState(), Blocks.ENDER_CHEST.getDefaultState()};
        BlockState[] pedestal = {Blocks.POLISHED_ANDESITE.getDefaultState(), Blocks.PURPUR_BLOCK.getDefaultState(),
                Blocks.CRYING_OBSIDIAN.getDefaultState(), Blocks.GOLD_BLOCK.getDefaultState()};
        for (int i = 0; i < 4; i++) {
            int x = cx - 12 + i * 8, z = cz - 18;
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) set(w, x + dx, cy - 1, z + dz, pedestal[i]);
            set(w, x, cy, z, pedestal[i]);                              // crate sits on a raised block
            set(w, x, cy + 1, z, crateBlock[i]);
            for (int dx : new int[]{-2, 2}) {
                set(w, x + dx, cy, z, Blocks.POLISHED_BLACKSTONE_WALL.getDefaultState());
                set(w, x + dx, cy + 1, z, Blocks.LANTERN.getDefaultState());
            }
            CFG.crates.put(types[i], new int[]{x, cy + 1, z});
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
        CFG.cx = cx; CFG.cy = cy; CFG.cz = cz;
        CFG.arena = null;          // the plaza loop above already cleared this area
        arena(w);

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

        // ---- RTP portal (north edge, behind the crates)
        int pz = cz - 26;
        for (int x = -3; x <= 3; x++) for (int y = 0; y <= 5; y++) {
            boolean frame = Math.abs(x) == 3 || y == 0 || y == 5;
            set(w, cx + x, cy + y - 1, pz, frame ? Blocks.CRYING_OBSIDIAN.getDefaultState() : Blocks.AIR.getDefaultState());
        }
        for (int x = -3; x <= 3; x += 6) set(w, cx + x, cy + 5, pz, Blocks.SOUL_LANTERN.getDefaultState());
        CFG.portal = new int[]{cx - 2, cy, pz, cx + 2, cy + 3, pz};

        // ---- menu NPCs (both sides of the fountain)
        cmd(server, "execute in " + w.getRegistryKey().getValue() + " run kill @e[type=minecraft:villager,tag=dracos_npc]");
        String[][] npcs = {
                {"shop", "Shop", "minecraft:armorer"}, {"sell", "Sell", "minecraft:farmer"}, {"ah", "Auction House", "minecraft:librarian"},
                {"orders", "Orders", "minecraft:cartographer"}, {"spawners", "Spawners", "minecraft:cleric"},
                {"quests", "Daily Quests", "minecraft:fletcher"}, {"rankshop", "Rank Shop", "minecraft:weaponsmith"},
                {"legendary", "Legendary Shop", "minecraft:toolsmith"}, {"skills", "Skills", "minecraft:mason"}, {"pwarp", "Player Warps", "minecraft:shepherd"}};
        for (int i = 0; i < npcs.length; i++) {
            boolean east = i < 5;
            int nx = cx + (east ? 11 : -11), nz = cz - 8 + (i % 5) * 4;
            set(w, nx, cy - 1, nz, Blocks.GOLD_BLOCK.getDefaultState());
            String name = snbt(Text.literal(npcs[i][1]).formatted(Formatting.GOLD, Formatting.BOLD));
            cmd(server, "execute in " + w.getRegistryKey().getValue() + " run summon minecraft:villager " + (nx + 0.5) + " " + cy + " " + (nz + 0.5)
                    + " {NoAI:1b,Invulnerable:1b,PersistenceRequired:1b,Silent:1b,CustomNameVisible:1b,Rotation:[" + (east ? 90 : -90) + "f,0f],"
                    + "Tags:[\"dracos_hub\",\"dracos_npc\",\"npc_" + npcs[i][0] + "\"],CustomName:" + name
                    + ",VillagerData:{profession:\"" + npcs[i][2] + "\",level:5,type:\"minecraft:plains\"}}");
        }

        // ---- decorations: cherry trees, flower ring around the fountain, arches over the 4 entrances
        for (int[] t : new int[][]{{-20, -22}, {20, -22}, {-20, 21}, {20, 21}, {-26, -26}, {26, -26}, {-26, 26}, {26, 26}})
            tree(w, cx + t[0], cy, cz + t[1]);
        BlockState[] flowers = {Blocks.POPPY.getDefaultState(), Blocks.CORNFLOWER.getDefaultState(), Blocks.ALLIUM.getDefaultState(),
                Blocks.OXEYE_DAISY.getDefaultState(), Blocks.AZURE_BLUET.getDefaultState(), Blocks.LILY_OF_THE_VALLEY.getDefaultState()};
        for (int x = -5; x <= 5; x++) for (int z = -5; z <= 5; z++) {
            if (Math.max(Math.abs(x), Math.abs(z)) != 5 || Math.abs(x) <= 2 || Math.abs(z) <= 2) continue;
            set(w, cx + x, cy - 1, cz + z, Blocks.GRASS_BLOCK.getDefaultState());
            set(w, cx + x, cy, cz + z, flowers[Math.floorMod(x * 7 + z * 3, flowers.length)]);
        }
        for (int[] d : new int[][]{{0, -1}, {0, 1}, {-1, 0}, {1, 0}}) {
            int ex = cx + d[0] * R, ez = cz + d[1] * R;
            for (int k = -3; k <= 3; k++) {
                int x = ex + (d[1] != 0 ? k : 0), z = ez + (d[0] != 0 ? k : 0);
                if (Math.abs(k) == 3) for (int y = cy; y <= cy + 4; y++) set(w, x, y, z, Blocks.POLISHED_BLACKSTONE_BRICKS.getDefaultState());
                set(w, x, cy + 5, z, Math.abs(k) == 3 ? Blocks.CHISELED_POLISHED_BLACKSTONE.getDefaultState() : Blocks.POLISHED_BLACKSTONE_BRICKS.getDefaultState());
            }
            set(w, ex, cy + 4, ez, Blocks.LANTERN.getDefaultState().with(net.minecraft.block.LanternBlock.HANGING, true));
        }

        CFG.world = w.getRegistryKey().getValue().toString();
        CFG.cx = cx; CFG.cy = cy; CFG.cz = cz;
        CFG.radius = ax + AR + 3 - cx;
        save();
        labels(server);
        border(w);
        Leaderboards.refresh(server);
        // world spawn + Essential Commands /spawn
        cmd(server, "execute in " + CFG.world + " run setworldspawn " + cx + " " + cy + " " + (cz + 6));
        p.teleport(w, cx + 0.5, cy, cz + 6.5, java.util.Set.of(), 180f, 0f, true);
        try { server.getCommandManager().parseAndExecute(p.getCommandSource(), "setspawn"); } catch (Throwable ignored) {}
        p.sendMessage(Text.literal("Hub built! Crates north, AFK zone south, leaderboards west, duel arena east.").formatted(Formatting.GREEN), false);
    }

    /** Invisible barrier walls around the plaza, the path and the arena so nobody falls off spawn. */
    static void border(ServerWorld w) {
        if (CFG.radius <= 0) return;
        int cx = CFG.cx, cy = CFG.cy, cz = CFG.cz, R = 30, AR = arenaR(), ax = CFG.arena != null ? CFG.arena[0] : cx + 48;
        BlockState b = Blocks.BARRIER.getDefaultState();
        for (int i = -R - 1; i <= R + 1; i++) {
            for (int y = cy; y <= cy + 4; y++) {
                set(w, cx + i, y, cz - R - 1, b);
                set(w, cx + i, y, cz + R + 1, b);
                set(w, cx - R - 1, y, cz + i, b);
                if (Math.abs(i) > 2) set(w, cx + R + 1, y, cz + i, b);        // east side stays open to the arena path
            }
        }
        for (int x = cx + R + 1; x <= ax - AR - 1; x++)
            for (int y = cy + 1; y <= cy + 4; y++) { set(w, x, y, cz - 3, b); set(w, x, y, cz + 3, b); }
        // arena: walls exist; make them high enough that nobody climbs or jumps out
        if (CFG.arenaR > 0) {
            for (int x = -AR - 3; x <= AR + 3; x++) for (int z = -AR - 3; z <= AR + 3; z++) {
                double d = Math.sqrt(x * x + z * z);
                if (d > AR + 0.5 && d <= AR + 2.5) for (int y = cy + 8; y <= cy + 14; y++) set(w, ax + x, y, cz + z, b);
            }
        } else {
            for (int x = ax - AR - 1; x <= ax + AR + 1; x++) for (int z = cz - AR - 1; z <= cz + AR + 1; z++)
                if (Math.abs(x - ax) == AR + 1 || Math.abs(z - cz) == AR + 1) for (int y = cy + 5; y <= cy + 8; y++) set(w, x, y, z, b);
        }
    }

    /** Removes the hub that was built last (blocks, holograms, NPCs) and forgets it. */
    static void clear(MinecraftServer server) {
        if (CFG.radius <= 0) return;
        ServerWorld w = world(server);
        cmd(server, "execute in " + CFG.world + " run kill @e[tag=dracos_hub]");
        cmd(server, "execute in " + CFG.world + " run kill @e[type=minecraft:text_display,tag=dracos_label]");
        final int cx = CFG.cx, cy = CFG.cy, cz = CFG.cz;
        final int ax = CFG.arena != null ? CFG.arena[0] : cx + 48, ar = arenaR() + 3, zr = Math.max(32, ar);
        final boolean sky = cy > 150;                 // built in the air: remove the foundation too, leave nothing behind
        CFG = new Cfg();                              // protection off right away
        save();
        // done a slice at a time (a few ms per tick) - doing it all at once froze the server until it was killed
        for (int x0 = cx - 32; x0 <= ax + ar; x0++) {
            final int x = x0;
            JOBS.add(() -> {
                for (int z = cz - zr; z <= cz + zr; z++) {
                    boolean plaza = Math.abs(x - cx) <= 32 && Math.abs(z - cz) <= 32;
                    boolean arena = Math.abs(x - ax) <= ar && Math.abs(z - cz) <= ar;
                    boolean path = Math.abs(z - cz) <= 3;
                    if (!plaza && !arena && !path) continue;
                    for (int y = cy - (sky ? 4 : 2); y <= cy + 15; y++) fast(w, x, y, z, Blocks.AIR.getDefaultState());
                    if (!sky) {
                        fast(w, x, cy - 2, z, Blocks.DIRT.getDefaultState());
                        fast(w, x, cy - 1, z, Blocks.GRASS_BLOCK.getDefaultState());
                    }
                }
            });
        }
    }

    /** Block change without neighbour updates / drops (big edits). */
    private static void fast(ServerWorld w, int x, int y, int z, BlockState s) {
        w.setBlockState(new BlockPos(x, y, z), s, net.minecraft.block.Block.NOTIFY_LISTENERS | net.minecraft.block.Block.FORCE_STATE
                | net.minecraft.block.Block.SKIP_DROPS);
    }

    /** Big world edits queued and run a little every tick so the server never freezes. */
    static final java.util.ArrayDeque<Runnable> JOBS = new java.util.ArrayDeque<>();

    static void runJobs() {
        long until = System.nanoTime() + 15_000_000L;
        while (!JOBS.isEmpty() && System.nanoTime() < until) JOBS.poll().run();
    }

    /** Small decorative cherry tree on a patch of grass (leaves never decay). */
    static void tree(ServerWorld w, int x, int cy, int z) {
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) set(w, x + dx, cy - 1, z + dz, Blocks.MOSS_BLOCK.getDefaultState());
        BlockState leaves = Blocks.CHERRY_LEAVES.getDefaultState().with(net.minecraft.block.LeavesBlock.PERSISTENT, true);
        for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) for (int dy = 3; dy <= 6; dy++) {
            double d = dx * dx + dz * dz + (dy - 4.5) * (dy - 4.5) * 2.2;
            if (d <= 9.5 && Math.floorMod(dx * 5 + dz * 3 + dy, 7) != 0) set(w, x + dx, cy + dy, z + dz, leaves);
        }
        for (int y = cy; y <= cy + 4; y++) set(w, x, y, z, Blocks.CHERRY_LOG.getDefaultState());
        set(w, x + 1, cy, z, Blocks.PINK_PETALS.getDefaultState());
        set(w, x - 1, cy, z + 1, Blocks.PINK_PETALS.getDefaultState());
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

    static int arenaR() { return CFG.arenaR > 0 ? CFG.arenaR : 11; }

    static Box arenaBox() {
        int[] a = CFG.arena;
        int r = arenaR() + 1;
        return new Box(a[0] - r, a[1] - 3, a[2] - r, a[0] + r + 1, a[1] + 14, a[2] + r + 1);
    }

    static final int ARENA_R = 22, ARENA_OFF = 30 + 8 + ARENA_R;

    /** Big round colosseum east of the plaza: patterned floor, tall wall with pillars and lights, cover pillars,
     *  red/blue spawn pads. Replaces whatever arena was there before (old square one included). */
    static void arena(ServerWorld w) {
        int cx = CFG.cx, cy = CFG.cy, cz = CFG.cz, R = 30, AR = ARENA_R, ax = cx + ARENA_OFF;
        BlockState air = Blocks.AIR.getDefaultState(), base = Blocks.STONE_BRICKS.getDefaultState(), b = Blocks.BARRIER.getDefaultState();
        // remove the old arena (square, 11 radius at +48) and its barriers
        if (CFG.arena != null) {
            int ox = CFG.arena[0], or = arenaR() + 2;
            for (int x = ox - or; x <= ox + or; x++) for (int z = cz - or; z <= cz + or; z++) {
                if (Math.abs(x - cx) <= R && Math.abs(z - cz) <= R) continue;
                for (int y = cy; y <= cy + 14; y++) fast(w, x, y, z, air);
                if (!(Math.abs(x - ax) <= AR + 3 && Math.abs(z - cz) <= AR + 3)) {
                    fast(w, x, cy - 1, z, Blocks.GRASS_BLOCK.getDefaultState());
                }
            }
        }
        for (int x = -AR - 3; x <= AR + 3; x++) for (int z = -AR - 3; z <= AR + 3; z++) {
            double d = Math.sqrt(x * x + z * z);
            int X = ax + x, Z = cz + z;
            for (int y = cy; y <= cy + 14; y++) fast(w, X, y, Z, air);
            if (d > AR + 2.5) continue;
            for (int y = cy - 4; y <= cy - 2; y++) fast(w, X, y, Z, base);
            BlockState f = d > AR + 0.5 ? Blocks.POLISHED_DEEPSLATE.getDefaultState() : floorAt(x, z);
            fast(w, X, cy - 1, Z, f);
            // wall: 2 thick, 7 high, pillars every 30 degrees, lights on top, barrier above
            if (d > AR + 0.5) {
                int ang = (int) Math.round(Math.toDegrees(Math.atan2(z, x)) + 360) % 360;
                boolean gate = x < 0 && Math.abs(z) <= 2;
                boolean pillar = ang % 30 <= 3;
                for (int y = cy; y <= cy + 7; y++) {
                    BlockState s;
                    if (gate && y <= cy + 3) s = air;                                   // open entrance from the path
                    else if (pillar) s = y == cy + 7 ? Blocks.CHISELED_POLISHED_BLACKSTONE.getDefaultState() : Blocks.POLISHED_BLACKSTONE_BRICKS.getDefaultState();
                    else if (y == cy + 5) s = Blocks.CHISELED_STONE_BRICKS.getDefaultState();
                    else if (y == cy + 6) s = Blocks.STONE_BRICK_WALL.getDefaultState();
                    else if (y == cy + 7) s = b;
                    else s = ((x * 3 + y + z) % 5 == 0) ? Blocks.CRACKED_STONE_BRICKS.getDefaultState() : Blocks.STONE_BRICKS.getDefaultState();
                    fast(w, X, y, Z, s);
                }
                if (pillar && d <= AR + 1.5) fast(w, X, cy + 8, Z, Blocks.SOUL_LANTERN.getDefaultState());
                for (int y = cy + 8; y <= cy + 14; y++) if (!(pillar && y == cy + 8 && d <= AR + 1.5)) fast(w, X, y, Z, b);
            }
        }
        cover(w, ax, cy, cz);
        path(w, ax, AR);
        // spawn pads
        int pd = 16;
        CFG.arena = new int[]{ax, cy, cz};
        CFG.arenaA = new int[]{ax - pd, cy, cz};
        CFG.arenaB = new int[]{ax + pd, cy, cz};
        CFG.arenaR = AR;
        CFG.radius = Math.max(CFG.radius, ax + AR + 3 - cx);
    }

    /** Arena floor block at (x, z) relative to the centre. */
    static BlockState floorAt(int x, int z) {
        double d = Math.sqrt(x * x + z * z);
        int ring = (int) Math.round(d);
        int pd = 16;
        if (Math.abs(z) <= 1 && (Math.abs(x + pd) <= 1 || Math.abs(x - pd) <= 1)) {         // spawn pads
            boolean red = x < 0, mid = z == 0 && (x == -pd || x == pd);
            return red ? (mid ? Blocks.REDSTONE_BLOCK : Blocks.RED_CONCRETE).getDefaultState()
                    : (mid ? Blocks.LAPIS_BLOCK : Blocks.BLUE_CONCRETE).getDefaultState();
        }
        if (ring % 7 == 0 && Math.floorMod((int) Math.round(Math.toDegrees(Math.atan2(z, x))), 30) < 4 && d > 4)
            return Blocks.SEA_LANTERN.getDefaultState();
        if (d <= 2.5) return (x == 0 && z == 0 ? Blocks.CHISELED_RED_SANDSTONE : Blocks.RED_NETHER_BRICKS).getDefaultState();
        if (d <= 3.5) return Blocks.GILDED_BLACKSTONE.getDefaultState();
        if (ring % 7 == 0) return Blocks.POLISHED_BLACKSTONE_BRICKS.getDefaultState();
        if (x == 0) return Blocks.QUARTZ_BRICKS.getDefaultState();                              // centre line
        return (((x + z) & 1) == 0 ? Blocks.POLISHED_DEEPSLATE : Blocks.DEEPSLATE_TILES).getDefaultState();
    }

    /** Cover inside the arena: 4 broken pillars and 4 low walls. */
    static void cover(ServerWorld w, int ax, int cy, int cz) {
        int[][] cover = {{8, 8}, {-8, 8}, {8, -8}, {-8, -8}};
        for (int[] c : cover) for (int dx = 0; dx <= 1; dx++) for (int dz = 0; dz <= 1; dz++) {
            int h = 3 + ((c[0] + c[1] + dx * 2 + dz) & 1);
            for (int y = cy; y < cy + h; y++) fast(w, ax + c[0] + dx, y, cz + c[1] + dz,
                    y == cy + h - 1 ? Blocks.CHISELED_POLISHED_BLACKSTONE.getDefaultState() : Blocks.POLISHED_BLACKSTONE_BRICKS.getDefaultState());
        }
        int[][] walls = {{0, 13}, {0, -13}, {13, 0}, {-13, 0}};
        for (int[] c : walls) for (int i = -2; i <= 2; i++) {
            int x = c[0] == 0 ? i : c[0], z = c[0] == 0 ? c[1] : i;
            fast(w, ax + x, cy, cz + z, Blocks.DEEPSLATE_BRICK_WALL.getDefaultState());
        }
    }

    /** Solid walkway from the plaza into the arena gate: 5 wide floor, low walls with lanterns on the sides,
     *  and any gaps at the plaza edge filled in. */
    static void path(ServerWorld w, int ax, int AR) {
        int cx = CFG.cx, cy = CFG.cy, cz = CFG.cz, R = 30;
        BlockState air = Blocks.AIR.getDefaultState(), base = Blocks.STONE_BRICKS.getDefaultState();
        int x1 = ax - AR;                                                    // up to the inside of the wall
        for (int x = cx + R - 4; x <= x1; x++) for (int z = -3; z <= 3; z++) {
            boolean inPlaza = x <= cx + R && Math.sqrt((x - cx) * (x - cx) + z * z) <= R;
            double dArena = Math.sqrt((x - ax) * (x - ax) + z * z);
            if (inPlaza && !w.getBlockState(new BlockPos(x, cy - 1, cz + z)).isAir()) continue;   // keep the plaza
            if (dArena <= AR + 0.5) continue;                                                     // arena floor already there
            boolean edge = Math.abs(z) == 3;
            boolean inWall = dArena <= AR + 2.5;
            if (edge && inWall) continue;                                                         // the arena wall itself
            for (int y = cy - 4; y <= cy - 2; y++) fast(w, x, y, cz + z, base);
            fast(w, x, cy - 1, cz + z, edge || Math.abs(z) == 2 ? Blocks.POLISHED_BLACKSTONE_BRICKS.getDefaultState()
                    : z == 0 ? Blocks.QUARTZ_BRICKS.getDefaultState() : Blocks.DEEPSLATE_TILES.getDefaultState());
            for (int y = cy; y <= cy + 4; y++) if (!inWall || Math.abs(z) <= 2) fast(w, x, y, cz + z, air);
            if (edge && x > cx + R) {
                fast(w, x, cy, cz + z, Blocks.POLISHED_BLACKSTONE_BRICK_WALL.getDefaultState());
                if (Math.floorMod(x - cx, 3) == 0) fast(w, x, cy + 1, cz + z, Blocks.LANTERN.getDefaultState());
            }
        }
    }

    /** Blocks placed by duelists in the current duel - the only ones they may break. */
    static final java.util.Set<Long> DUEL_PLACED = new java.util.HashSet<>();

    /** Puts the inside of the arena back the way it was built (blocks players placed or broke in the last duel). */
    static void resetArena(ServerWorld w) {
        DUEL_PLACED.clear();
        if (CFG.arena == null || CFG.arenaR <= 0) return;
        int ax = CFG.arena[0], cy = CFG.arena[1], cz = CFG.arena[2], AR = CFG.arenaR;
        BlockState air = Blocks.AIR.getDefaultState();
        for (int x = -AR; x <= AR; x++) for (int z = -AR; z <= AR; z++) {
            if (Math.sqrt(x * x + z * z) > AR + 0.5) continue;
            fast(w, ax + x, cy - 1, cz + z, floorAt(x, z));
            for (int y = cy; y <= cy + 7; y++) fast(w, ax + x, y, cz + z, air);
        }
        cover(w, ax, cy, cz);
        for (var e : w.getEntitiesByClass(net.minecraft.entity.ItemEntity.class,
                new net.minecraft.util.math.Box(ax - AR, cy - 2, cz - AR, ax + AR + 1, cy + 10, cz + AR + 1), e -> true)) e.discard();
    }

    /** Duelists may build/break inside the arena, below the top of the wall. */
    static boolean duelBuildable(ServerPlayerEntity p, BlockPos pos) {
        if (CFG.arena == null || CFG.arenaR <= 0 || !Duels.inDuel(p)) return false;
        int dx = pos.getX() - CFG.arena[0], dz = pos.getZ() - CFG.arena[2], dy = pos.getY() - CFG.arena[1];
        return dy >= 0 && dy <= 5 && Math.sqrt(dx * dx + dz * dz) <= CFG.arenaR - 0.5;
    }
}
