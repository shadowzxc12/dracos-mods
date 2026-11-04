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

/** /settings - per-player toggles (stored in config/dracos/settings.json, only the OFF ones). */
public final class Settings {
    private Settings() {}

    public enum Opt {
        TRAIL("Rank particle trail", Items.BLAZE_POWDER, "Show your rank trail while walking"),
        AURAS("Legendary item effects", Items.NETHER_STAR, "Particles around your legendary items and armor"),
        JOIN("Join messages", Items.OAK_SIGN, "See when ranked players join (and the Draco roar)"),
        DAILY("Daily reward reminder", Items.CLOCK, "Reminder on join when /daily is ready"),
        SELLSOUND("Sell sounds", Items.GOLD_NUGGET, "Play a sound when you sell items");

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

    private static final int[] SLOTS = {10, 11, 12, 13, 14};

    public static void open(ServerPlayerEntity p) {
        SimpleInventory inv = new SimpleInventory(27);
        fill(p, inv);
        p.openHandledScreen(new SimpleNamedScreenHandlerFactory((id, pi, pl) -> new Handler(id, pi, inv),
                Text.literal("Settings").formatted(Formatting.DARK_AQUA, Formatting.BOLD)));
    }

    private static void fill(ServerPlayerEntity p, SimpleInventory inv) {
        ItemStack pane = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
        pane.set(DataComponentTypes.ITEM_NAME, Text.literal(" "));
        for (int i = 0; i < 27; i++) inv.setStack(i, pane.copy());
        Opt[] opts = Opt.values();
        for (int i = 0; i < opts.length; i++) {
            Opt o = opts[i];
            boolean on = on(p, o);
            ItemStack s = new ItemStack(o.icon);
            s.set(DataComponentTypes.ITEM_NAME, Text.literal(o.title).styled(st -> st.withColor(on ? Formatting.GREEN : Formatting.RED).withBold(true).withItalic(false)));
            s.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                    Text.literal(o.desc).styled(st -> st.withColor(Formatting.GRAY).withItalic(false)),
                    Text.literal(on ? "● ON" : "○ OFF").styled(st -> st.withColor(on ? Formatting.GREEN : Formatting.RED).withItalic(false)),
                    Text.literal("Click to switch").styled(st -> st.withColor(Formatting.DARK_GRAY).withItalic(false)))));
            if (on) s.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
            inv.setStack(SLOTS[i], s);
        }
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        book.set(DataComponentTypes.ITEM_NAME, Text.literal("Get the tutorial book").styled(st -> st.withColor(Formatting.GOLD).withBold(true).withItalic(false)));
        inv.setStack(16, book);
    }

    static class Handler extends GenericContainerScreenHandler {
        private final SimpleInventory inv;
        Handler(int id, PlayerInventory pi, SimpleInventory inv) { super(ScreenHandlerType.GENERIC_9X3, id, pi, inv, 3); this.inv = inv; }

        @Override
        public void onSlotClick(int slot, int button, SlotActionType type, PlayerEntity player) {
            if (player instanceof ServerPlayerEntity sp && type == SlotActionType.PICKUP) {
                for (int i = 0; i < SLOTS.length; i++) if (SLOTS[i] == slot) {
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
