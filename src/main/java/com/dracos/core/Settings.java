package com.dracos.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** /menu - per-player toggles (stored in config/dracos/settings.json, only the OFF ones). */
public final class Settings {
    private Settings() {}

    public enum Opt {
        TRAIL("Rank particle trail", Items.BLAZE_POWDER, "Show your rank trail while walking"),
        AURAS("Legendary item effects", Items.NETHER_STAR, "Particles around your legendary items and armor"),
        JOIN("Join messages", Items.OAK_SIGN, "See when ranked players join (and the Draco roar)"),
        DAILY("Daily reward reminder", Items.CLOCK, "Reminder on join when /daily is ready"),
        SELLSOUND("Sell sounds", Items.GOLD_NUGGET, "Play a sound when you sell items"),
        BOUNTY("Bounty alerts", Items.WITHER_SKELETON_SKULL, "Chat messages about new and claimed bounties");

        final String title; final Item icon; final String desc;
        Opt(String title, Item icon, String desc) { this.title = title; this.icon = icon; this.desc = desc; }
    }

    private static final Map<UUID, Set<String>> OFF = new ConcurrentHashMap<>();
    private static boolean loaded;

    private static Path file() { return FabricLoader.getInstance().getConfigDir().resolve("dracos").resolve("settings.json"); }

    private static synchronized void load() {
        if (loaded) return;
        loaded = true;
        try {
            if (!Files.exists(file())) return;
            JsonObject o = JsonParser.parseString(Files.readString(file())).getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : o.entrySet()) {
                Set<String> s = ConcurrentHashMap.newKeySet();
                for (JsonElement x : e.getValue().getAsJsonArray()) s.add(x.getAsString());
                OFF.put(UUID.fromString(e.getKey()), s);
            }
        } catch (Exception ex) { DracosCore.LOGGER.warn("settings.json unreadable", ex); }
    }

    private static synchronized void save() {
        try {
            JsonObject o = new JsonObject();
            OFF.forEach((k, v) -> { JsonArray a = new JsonArray(); v.forEach(a::add); o.add(k.toString(), a); });
            Files.createDirectories(file().getParent());
            Files.writeString(file(), o.toString());
        } catch (Exception ex) { DracosCore.LOGGER.warn("settings.json save failed", ex); }
    }

    public static boolean on(ServerPlayerEntity p, Opt opt) {
        load();
        Set<String> s = OFF.get(p.getUuid());
        return s == null || !s.contains(opt.name());
    }

    private static void toggle(ServerPlayerEntity p, Opt opt) {
        load();
        Set<String> s = OFF.computeIfAbsent(p.getUuid(), u -> ConcurrentHashMap.newKeySet());
        if (!s.remove(opt.name())) s.add(opt.name());
        save();
    }

    /** Sound only this player hears. */
    public static void sound(ServerPlayerEntity p, net.minecraft.sound.SoundEvent ev, float vol, float pitch) {
        p.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket(
                net.minecraft.registry.Registries.SOUND_EVENT.getEntry(ev), SoundCategory.PLAYERS,
                p.getX(), p.getY(), p.getZ(), vol, pitch, p.getRandom().nextLong()));
    }

    // DonutSMP-style: icon on row 2, ON/OFF switch right under it on row 3
    private static final int[] SLOTS = {10, 11, 12, 13, 14, 15};

    public static void open(ServerPlayerEntity p) {
        DracosCore.LOGGER.info("[Settings] opening menu for {}", p.getName().getString());
        SimpleInventory inv = new SimpleInventory(45);
        fill(p, inv);
        p.openHandledScreen(new SimpleNamedScreenHandlerFactory((id, pi, pl) -> new Handler(id, pi, inv),
                Text.literal("⚙ Settings").formatted(Formatting.DARK_GRAY, Formatting.BOLD)));
    }

    private static ItemStack named(Item item, Text name, List<Text> lore) {
        ItemStack s = new ItemStack(item);
        s.set(DataComponentTypes.ITEM_NAME, name);
        if (lore != null) s.set(DataComponentTypes.LORE, new LoreComponent(lore));
        return s;
    }

    private static Text gray(String t) { return Text.literal(t).styled(st -> st.withColor(Formatting.GRAY).withItalic(false)); }

    private static void fill(ServerPlayerEntity p, SimpleInventory inv) {
        ItemStack pane = named(Items.BLACK_STAINED_GLASS_PANE, Text.literal(" "), null);
        for (int i = 0; i < 45; i++) inv.setStack(i, pane.copy());
        Opt[] opts = Opt.values();
        for (int i = 0; i < opts.length; i++) {
            Opt o = opts[i];
            boolean on = on(p, o);
            Text title = Text.literal(o.title).styled(st -> st.withColor(Formatting.WHITE).withBold(true).withItalic(false));
            ItemStack icon = named(o.icon, title, List.of(gray(o.desc), Text.empty(),
                    Text.literal(on ? "Enabled" : "Disabled").styled(st -> st.withColor(on ? Formatting.GREEN : Formatting.RED).withItalic(false)),
                    Text.literal("Click to toggle").styled(st -> st.withColor(Formatting.DARK_GRAY).withItalic(false))));
            inv.setStack(SLOTS[i], icon);
            ItemStack sw = named(on ? Items.LIME_DYE : Items.GRAY_DYE,
                    Text.literal(on ? "ON" : "OFF").styled(st -> st.withColor(on ? Formatting.GREEN : Formatting.RED).withBold(true).withItalic(false)),
                    List.of(gray(o.title), Text.literal("Click to toggle").styled(st -> st.withColor(Formatting.DARK_GRAY).withItalic(false))));
            inv.setStack(SLOTS[i] + 9, sw);
        }
        inv.setStack(16, named(Items.WRITTEN_BOOK, Text.literal("Tutorial book").styled(st -> st.withColor(Formatting.GOLD).withBold(true).withItalic(false)),
                List.of(gray("Get the server guide again"))));
        Ranks.Rank r = Ranks.of(p);
        inv.setStack(25, named(Items.NAME_TAG, Text.literal("Your rank: " + r.title).styled(st -> st.withColor(r.color).withBold(true).withItalic(false)),
                List.of(gray(r.homes + " homes, +" + r.sellBonus + "% /sell"), gray(Ranks.daysLeft(p) >= 0 ? Ranks.daysLeft(p) + " days left" : "No expiry"))));
        inv.setStack(40, named(Items.BARRIER, Text.literal("Close").styled(st -> st.withColor(Formatting.RED).withBold(true).withItalic(false)), null));
    }

    static class Handler extends GenericContainerScreenHandler {
        private final SimpleInventory inv;
        Handler(int id, PlayerInventory pi, SimpleInventory inv) { super(ScreenHandlerType.GENERIC_9X5, id, pi, inv, 5); this.inv = inv; }

        @Override
        public void onSlotClick(int slot, int button, SlotActionType type, PlayerEntity player) {
            if (player instanceof ServerPlayerEntity sp && type == SlotActionType.PICKUP) {
                if (slot == 40) { sp.closeHandledScreen(); return; }
                for (int i = 0; i < SLOTS.length; i++) if (SLOTS[i] == slot || SLOTS[i] + 9 == slot) {
                    toggle(sp, Opt.values()[i]);
                    sound(sp, SoundEvents.UI_BUTTON_CLICK.value(), 0.6f, 1.2f);
                    fill(sp, inv);
                }
                if (slot == 16) { Tutorial.give(sp); sp.closeHandledScreen(); }
            }
            this.syncState();
        }

        @Override public ItemStack quickMove(PlayerEntity player, int slot) { return ItemStack.EMPTY; }
    }
}
