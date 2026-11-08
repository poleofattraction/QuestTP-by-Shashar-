package ua.questtp;

import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Keyed;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class QuestTP extends JavaPlugin implements Listener, TabExecutor {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final int MENU_SIZE = 54;

    enum Type { DEPARTURE, INTEREST }

    /** Поставлена плита. loc - центр верхньої грані опорного блока (там стоїть гравець). */
    record Point(String id, Type type, UUID owner, String name, Location loc, UUID display, UUID interaction) {}

    /** Власник інвентарю-меню: слот -> id точки. */
    static final class Menu implements InventoryHolder {
        final Map<Integer, String> slots = new HashMap<>();
        Inventory inv;

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private NamespacedKey typeKey;
    private NamespacedKey nameKey;
    private NamespacedKey pointKey;
    private NamespacedKey recipeKey;

    private File pointsFile;
    private File craftedFile;

    private final Map<String, Point> points = new LinkedHashMap<>();
    private final Set<UUID> crafted = new HashSet<>();
    private final Set<UUID> pending = new HashSet<>();
    private final Map<UUID, Long> cooldowns = new HashMap<>();

    // ================================================================== lifecycle

    @Override
    public void onEnable() {
        saveDefaultConfig();
        typeKey = new NamespacedKey(this, "item_type");
        nameKey = new NamespacedKey(this, "item_name");
        pointKey = new NamespacedKey(this, "point_id");
        recipeKey = new NamespacedKey(this, "departure_recipe");
        pointsFile = new File(getDataFolder(), "points.yml");
        craftedFile = new File(getDataFolder(), "crafted.yml");

        loadPoints();
        loadCrafted();
        registerRecipe();
        getServer().getPluginManager().registerEvents(this, this);

        // Раз на секунду нагадуємо Bedrock-гравцям про "підставні" блоки (сервер сам їх стирає після кліків)
        getServer().getScheduler().runTaskTimer(this, () -> {
            for (Player pl : Bukkit.getOnlinePlayers()) refreshFake(pl);
        }, 40L, 20L);

        PluginCommand cmd = getCommand("questtp");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
    }

    @Override
    public void onDisable() {
        Bukkit.removeRecipe(recipeKey);
        savePoints();
        saveCrafted();
    }

    // ================================================================== items

    private static String path(Type t) {
        return t == Type.DEPARTURE ? "departure" : "interest";
    }

    private static String pretty(String name) {
        return name == null ? "" : name.replace('_', ' ');
    }

    private Component text(String legacy) {
        return LEGACY.deserialize(legacy).decoration(TextDecoration.ITALIC, false);
    }

    private List<Component> loreOf(List<String> lines) {
        List<Component> out = new ArrayList<>();
        for (String s : lines) out.add(text(s));
        return out;
    }

    /** Предмет, у якого є тільки вигляд (item_model). Для меню й ItemDisplay. */
    private ItemStack visual(Type type) {
        Material base = Material.matchMaterial(getConfig().getString("item.base-material", "KNOWLEDGE_BOOK"));
        if (base == null) base = Material.KNOWLEDGE_BOOK;
        ItemStack item = new ItemStack(base);
        ItemMeta meta = item.getItemMeta();
        String model = getConfig().getString("items." + path(type) + ".item-model", "");
        if (model != null && !model.isBlank()) {
            NamespacedKey k = NamespacedKey.fromString(model);
            if (k != null) meta.setItemModel(k);
        }
        item.setItemMeta(meta);
        return item;
    }

    /** Справжній предмет, який гравець тримає в руці. */
    private ItemStack createItem(Type type, String name) {
        ItemStack item = visual(type);
        ItemMeta meta = item.getItemMeta();
        String p = "items." + path(type);
        meta.displayName(text(getConfig().getString(p + ".name", p).replace("{name}", pretty(name))));
        meta.lore(loreOf(getConfig().getStringList(p + ".lore")));
        meta.getPersistentDataContainer().set(typeKey, PersistentDataType.STRING, type.name());
        if (name != null) meta.getPersistentDataContainer().set(nameKey, PersistentDataType.STRING, name);
        item.setItemMeta(meta);
        return item;
    }

    private Type itemType(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        String s = item.getItemMeta().getPersistentDataContainer().get(typeKey, PersistentDataType.STRING);
        if (s == null) return null;
        try {
            return Type.valueOf(s);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private String itemName(ItemStack item) {
        return item.getItemMeta().getPersistentDataContainer().get(nameKey, PersistentDataType.STRING);
    }

    private void registerRecipe() {
        ShapedRecipe recipe = new ShapedRecipe(recipeKey, createItem(Type.DEPARTURE, null));
        recipe.shape("OEO", "ECE", "OEO");
        recipe.setIngredient('O', Material.OBSIDIAN);
        recipe.setIngredient('E', Material.ENDER_PEARL);
        recipe.setIngredient('C', Material.COMPASS);
        Bukkit.addRecipe(recipe);
    }

    // ================================================================== storage

    private void loadPoints() {
        points.clear();
        if (!pointsFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(pointsFile);
        ConfigurationSection sec = y.getConfigurationSection("points");
        if (sec == null) return;
        for (String id : sec.getKeys(false)) {
            ConfigurationSection s = sec.getConfigurationSection(id);
            if (s == null) continue;
            World w = Bukkit.getWorld(s.getString("world", ""));
            if (w == null) continue;
            try {
                Type t = Type.valueOf(s.getString("type", ""));
                String owner = s.getString("owner");
                Location loc = new Location(w, s.getDouble("x"), s.getDouble("y"), s.getDouble("z"));
                points.put(id, new Point(id, t, owner == null ? null : UUID.fromString(owner), s.getString("name"),
                        loc, UUID.fromString(s.getString("display", "")), UUID.fromString(s.getString("interaction", ""))));
            } catch (IllegalArgumentException ex) {
                getLogger().warning("Пропускаю пошкоджений запис точки " + id);
            }
        }
    }

    private void savePoints() {
        YamlConfiguration y = new YamlConfiguration();
        for (Point p : points.values()) {
            String b = "points." + p.id() + ".";
            y.set(b + "type", p.type().name());
            if (p.owner() != null) y.set(b + "owner", p.owner().toString());
            if (p.name() != null) y.set(b + "name", p.name());
            y.set(b + "world", p.loc().getWorld().getName());
            y.set(b + "x", p.loc().getX());
            y.set(b + "y", p.loc().getY());
            y.set(b + "z", p.loc().getZ());
            y.set(b + "display", p.display().toString());
            y.set(b + "interaction", p.interaction().toString());
        }
        try {
            y.save(pointsFile);
        } catch (IOException ex) {
            getLogger().severe("Не вдалось зберегти points.yml: " + ex.getMessage());
        }
    }

    private void loadCrafted() {
        crafted.clear();
        if (!craftedFile.exists()) return;
        for (String s : YamlConfiguration.loadConfiguration(craftedFile).getStringList("players")) {
            try {
                crafted.add(UUID.fromString(s));
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private void saveCrafted() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("players", crafted.stream().map(UUID::toString).toList());
        try {
            y.save(craftedFile);
        } catch (IOException ex) {
            getLogger().severe("Не вдалось зберегти crafted.yml: " + ex.getMessage());
        }
    }

    // ================================================================== messages

    private void msg(Player p, String key) {
        msg(p, key, "", 0);
    }

    private void msg(Player p, String key, String name, long seconds) {
        String t = getConfig().getString("messages." + key, key);
        p.sendMessage(LEGACY.deserialize(t.replace("{name}", pretty(name)).replace("{s}", String.valueOf(seconds))));
    }

    // ================================================================== lookup

    private Point departureOf(UUID owner) {
        for (Point p : points.values()) {
            if (p.type() == Type.DEPARTURE && owner.equals(p.owner())) return p;
        }
        return null;
    }

    private Point interestNamed(String name) {
        for (Point p : points.values()) {
            if (p.type() == Type.INTEREST && p.name().equalsIgnoreCase(name)) return p;
        }
        return null;
    }

    private boolean isSupport(Block b) {
        for (Point p : points.values()) {
            if (!p.loc().getWorld().equals(b.getWorld())) continue;
            if (p.loc().clone().subtract(0, 0.01, 0).getBlock().equals(b)) return true;
        }
        return false;
    }

    // ================================================================== placing

    @EventHandler
    public void onUseItem(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
        ItemStack item = e.getItem();
        Type type = itemType(item);
        if (type == null) return;

        Block clicked = e.getClickedBlock();
        if (clicked == null || clicked.isEmpty()) return; // клік по "підставному" блоку Bedrock обробляє onFakeClick
        e.setCancelled(true);
        Player p = e.getPlayer();
        if (clicked == null || e.getBlockFace() != BlockFace.UP) {
            msg(p, "place-top");
            return;
        }
        if (!clicked.getRelative(BlockFace.UP).isEmpty()) {
            msg(p, "no-space");
            return;
        }

        UUID owner = null;
        String name = null;
        if (type == Type.DEPARTURE) {
            if (departureOf(p.getUniqueId()) != null) {
                msg(p, "already-departure");
                return;
            }
            owner = p.getUniqueId();
        } else {
            name = itemName(item);
            if (name == null) return;
            if (interestNamed(name) != null) {
                msg(p, "name-taken");
                return;
            }
        }

        Location loc = new Location(clicked.getWorld(),
                clicked.getX() + 0.5, clicked.getBoundingBox().getMaxY(), clicked.getZ() + 0.5);
        spawnPoint(type, owner, name, loc);

        if (p.getGameMode() != GameMode.CREATIVE) item.setAmount(item.getAmount() - 1);
        if (type == Type.DEPARTURE) msg(p, "placed-departure");
        else msg(p, "placed-interest", name, 0);
    }

    private void spawnPoint(Type type, UUID owner, String name, Location loc) {
        World w = loc.getWorld();
        String id = UUID.randomUUID().toString();
        double yOffset = getConfig().getDouble("display-y-offset", 0.5);
        ItemStack shown = visual(type);

        ItemDisplay display = w.spawn(loc.clone().add(0, yOffset, 0), ItemDisplay.class, d -> {
            d.setItemStack(shown);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            d.setPersistent(true);
            d.setInvulnerable(true);
        });
        Interaction hitbox = w.spawn(loc, Interaction.class, i -> {
            i.setInteractionWidth(1.0f);
            i.setInteractionHeight(0.25f);
            i.setResponsive(true);
            i.setPersistent(true);
            i.setInvulnerable(true);
            i.getPersistentDataContainer().set(pointKey, PersistentDataType.STRING, id);
        });

        Point created = new Point(id, type, owner, name, loc.clone(), display.getUniqueId(), hitbox.getUniqueId());
        points.put(id, created);
        savePoints();
        for (Player pl : Bukkit.getOnlinePlayers()) {
            if (isBedrock(pl)) sendFake(pl, created);
        }
    }

    private void removePoint(Point p) {
        points.remove(p.id());
        for (Player pl : Bukkit.getOnlinePlayers()) {
            if (isBedrock(pl)) clearFake(pl, p);
        }
        World w = p.loc().getWorld();
        w.getChunkAt(p.loc()).load();
        for (UUID u : new UUID[]{p.display(), p.interaction()}) {
            Entity en = w.getEntity(u);
            if (en != null) en.remove();
        }
        savePoints();
    }

    // ================================================================== using / breaking

    private String pointIdOf(Entity entity) {
        if (!(entity instanceof Interaction)) return null;
        return entity.getPersistentDataContainer().get(pointKey, PersistentDataType.STRING);
    }

    @EventHandler
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        String id = pointIdOf(e.getRightClicked());
        if (id == null) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;

        Point point = points.get(id);
        if (point == null) {
            e.getRightClicked().remove(); // сирота без запису
            return;
        }
        openMenu(e.getPlayer(), point);
    }

    @EventHandler
    public void onAttack(PrePlayerAttackEntityEvent e) {
        String id = pointIdOf(e.getAttacked());
        if (id == null) return;
        e.setCancelled(true);

        Point point = points.get(id);
        if (point != null) tryBreak(e.getPlayer(), point);
    }

    private void tryBreak(Player p, Point point) {
        boolean allowed = p.hasPermission("questtp.eventmaker")
                || (point.type() == Type.DEPARTURE && p.getUniqueId().equals(point.owner()));
        if (!allowed) {
            msg(p, "no-break");
            return;
        }
        removePoint(point);
        if (p.getGameMode() != GameMode.CREATIVE) {
            point.loc().getWorld().dropItemNaturally(point.loc().clone().add(0, 0.3, 0),
                    createItem(point.type(), point.name()));
        }
    }

    // ================================================================== Bedrock (Geyser/Floodgate)
    //
    // У Bedrock немає ItemDisplay, тому плиту там не видно. Замість неї гравцям Bedrock (і тільки їм)
    // надсилається "підставний" блок, наприклад респавн-якір. На сервері цього блока не існує:
    // ванільні якорі не зачіпаються, а підставний активувати неможливо.

    private boolean isBedrock(Player p) {
        if (!getConfig().getBoolean("bedrock.enabled", true)) return false;
        // Floodgate видає Bedrock-гравцям UUID, що починається з 00000000-0000-0000
        if (p.getUniqueId().getMostSignificantBits() == 0L) return true;
        String prefix = getConfig().getString("bedrock.name-prefix", ".");
        return prefix != null && !prefix.isEmpty() && p.getName().startsWith(prefix);
    }

    /** Блок прямо над опорним (там, де візуально стоїть плита). */
    private Block fakeBlockOf(Point p) {
        return p.loc().clone().subtract(0, 0.01, 0).getBlock().getRelative(BlockFace.UP);
    }

    private BlockData fakeDataOf(Point p) {
        String def = p.type() == Type.INTEREST
                ? "minecraft:respawn_anchor[charges=4]"
                : "minecraft:respawn_anchor[charges=0]";
        try {
            return Bukkit.createBlockData(getConfig().getString("bedrock.fake-blocks." + path(p.type()), def));
        } catch (IllegalArgumentException ex) {
            return Bukkit.createBlockData(def);
        }
    }

    private void sendFake(Player pl, Point p) {
        if (!pl.getWorld().equals(p.loc().getWorld())) return;
        Block b = fakeBlockOf(p);
        if (!b.isEmpty()) return; // там уже стоїть справжній блок
        pl.sendBlockChange(b.getLocation(), fakeDataOf(p));
    }

    private void clearFake(Player pl, Point p) {
        if (!pl.getWorld().equals(p.loc().getWorld())) return;
        Block b = fakeBlockOf(p);
        pl.sendBlockChange(b.getLocation(), b.getBlockData());
    }

    private void refreshFake(Player pl) {
        if (!pl.isOnline() || !isBedrock(pl)) return;
        Location at = pl.getLocation();
        for (Point p : points.values()) {
            if (!p.loc().getWorld().equals(at.getWorld())) continue;
            if (p.loc().distanceSquared(at) > 96 * 96) continue;
            sendFake(pl, p);
        }
    }

    private Point pointAtFake(Block b) {
        for (Point p : points.values()) {
            if (p.loc().getWorld().equals(b.getWorld()) && fakeBlockOf(p).equals(b)) return p;
        }
        return null;
    }

    /** Куди поставити Bedrock-гравця: поруч із плитою, а не всередині "підставного" блока. */
    private Location bedrockLanding(Point t) {
        Block base = fakeBlockOf(t);
        for (BlockFace f : new BlockFace[]{BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST}) {
            Block side = base.getRelative(f);
            if (side.isPassable() && side.getRelative(BlockFace.UP).isPassable()
                    && !side.getRelative(BlockFace.DOWN).isPassable()) {
                return side.getLocation().add(0.5, 0, 0.5);
            }
        }
        return t.loc().clone();
    }

    /** Клік Bedrock-гравця по "підставному" блоку: на сервері там повітря, тож ловимо подію самі. */
    @EventHandler
    public void onFakeClick(PlayerInteractEvent e) {
        Block b = e.getClickedBlock();
        if (b == null || e.getHand() != EquipmentSlot.HAND) return;
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_BLOCK && a != Action.LEFT_CLICK_BLOCK) return;
        Player p = e.getPlayer();
        if (!isBedrock(p)) return;
        Point pt = pointAtFake(b);
        if (pt == null) return;

        e.setCancelled(true);
        if (a == Action.RIGHT_CLICK_BLOCK) openMenu(p, pt);
        else tryBreak(p, pt);

        // Сервер після кліку шле клієнту справжній (порожній) блок. Повертаємо підставний.
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (p.isOnline() && points.containsKey(pt.id())) sendFake(p, pt);
        }, 1L);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(this, () -> refreshFake(p), 40L);
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(this, () -> refreshFake(p), 10L);
    }

    // Не даємо вибити блок під плитою
    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent e) {
        if (isSupport(e.getBlock())) {
            e.setCancelled(true);
            msg(e.getPlayer(), "support");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(this::isSupport);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(this::isSupport);
    }

    // ================================================================== menu

    private ItemStack button(ItemStack base, String name, List<String> lore) {
        ItemMeta meta = base.getItemMeta();
        meta.displayName(text(name));
        meta.lore(loreOf(lore));
        base.setItemMeta(meta);
        return base;
    }

    private void openMenu(Player p, Point point) {
        Menu holder = new Menu();
        FileLists cfg = new FileLists();

        if (point.type() == Type.DEPARTURE) {
            Inventory inv = Bukkit.createInventory(holder, MENU_SIZE, text(cfg.s("menu.departure-title")));
            holder.inv = inv;

            List<Point> list = points.values().stream()
                    .filter(x -> x.type() == Type.INTEREST)
                    .sorted(Comparator.comparing(x -> x.name().toLowerCase()))
                    .limit(MENU_SIZE)
                    .toList();

            if (list.isEmpty()) {
                inv.setItem(22, button(new ItemStack(Material.BARRIER), cfg.s("menu.empty"), List.of()));
            }
            int slot = 0;
            for (Point x : list) {
                inv.setItem(slot, button(visual(Type.INTEREST), "&d" + pretty(x.name()), cfg.l("menu.go-lore")));
                holder.slots.put(slot, x.id());
                slot++;
            }
            p.openInventory(inv);
        } else {
            Inventory inv = Bukkit.createInventory(holder, 27, text(cfg.s("menu.interest-title")));
            holder.inv = inv;

            Point home = departureOf(p.getUniqueId());
            if (home == null) {
                inv.setItem(13, button(new ItemStack(Material.BARRIER),
                        cfg.s("menu.no-home-name"), cfg.l("menu.no-home-lore")));
            } else {
                inv.setItem(13, button(visual(Type.DEPARTURE), cfg.s("menu.home-name"), cfg.l("menu.home-lore")));
                holder.slots.put(13, home.id());
            }
            p.openInventory(inv);
        }
    }

    /** Дрібний помічник, щоб не писати getConfig() усюди. */
    private final class FileLists {
        String s(String key) {
            return getConfig().getString(key, key);
        }

        List<String> l(String key) {
            return getConfig().getStringList(key);
        }
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof Menu menu)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() != top) return;

        String id = menu.slots.get(e.getSlot());
        if (id == null || !(e.getWhoClicked() instanceof Player p)) return;

        Point target = points.get(id);
        p.closeInventory();
        if (target == null) {
            msg(p, "point-gone");
            return;
        }
        startTeleport(p, target);
    }

    @EventHandler
    public void onMenuDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Menu) e.setCancelled(true);
    }

    // ================================================================== teleport

    private void startTeleport(Player p, Point target) {
        UUID id = p.getUniqueId();
        if (pending.contains(id)) return;

        long cooldownMs = getConfig().getLong("cooldown-seconds", 60) * 1000L;
        Long last = cooldowns.get(id);
        if (last != null && System.currentTimeMillis() - last < cooldownMs) {
            long left = (cooldownMs - (System.currentTimeMillis() - last) + 999) / 1000;
            msg(p, "cooldown", "", left);
            return;
        }

        Location dest = isBedrock(p) ? bedrockLanding(target) : target.loc().clone();
        dest.setYaw(p.getLocation().getYaw());
        dest.setPitch(p.getLocation().getPitch());

        int warmup = getConfig().getInt("warmup-seconds", 3);
        Location start = p.getLocation();
        pending.add(id);
        msg(p, "warmup", "", warmup);

        new BukkitRunnable() {
            int left = warmup;

            @Override
            public void run() {
                if (!p.isOnline() || !pending.contains(id)) {
                    pending.remove(id);
                    cancel();
                    return;
                }
                Location now = p.getLocation();
                if (now.getWorld() != start.getWorld() || now.distanceSquared(start) > 1.5) {
                    pending.remove(id);
                    msg(p, "moved");
                    cancel();
                    return;
                }
                p.getWorld().spawnParticle(Particle.PORTAL, now.add(0, 1, 0), 30, 0.4, 0.8, 0.4, 0.5);

                if (--left <= 0) {
                    pending.remove(id);
                    cancel();
                    cooldowns.put(id, System.currentTimeMillis());
                    p.teleportAsync(dest).thenAccept(ok -> {
                        if (ok) {
                            p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
                            msg(p, "teleported");
                        }
                    });
                }
            }
        }.runTaskTimer(this, 20L, 20L);
    }

    @EventHandler
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && pending.remove(p.getUniqueId())) {
            msg(p, "damaged");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        pending.remove(e.getPlayer().getUniqueId());
    }

    // ================================================================== crafting limit

    private boolean isOurRecipe(org.bukkit.inventory.Recipe r) {
        return r instanceof Keyed k && k.getKey().equals(recipeKey);
    }

    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent e) {
        if (!isOurRecipe(e.getRecipe())) return;
        if (crafted.contains(e.getView().getPlayer().getUniqueId())) {
            e.getInventory().setResult(null);
        }
    }

    @EventHandler
    public void onCraft(CraftItemEvent e) {
        if (!isOurRecipe(e.getRecipe()) || !(e.getWhoClicked() instanceof Player p)) return;
        if (crafted.contains(p.getUniqueId())) {
            e.setCancelled(true);
            return;
        }
        if (e.isShiftClick()) {
            e.setCancelled(true);
            msg(p, "craft-shift");
            return;
        }
        crafted.add(p.getUniqueId());
        saveCrafted();
    }

    // ================================================================== commands

    private boolean need(CommandSender s, String perm) {
        if (s.hasPermission(perm)) return true;
        s.sendMessage("§cНемає прав.");
        return false;
    }

    private void give(Player target, ItemStack item) {
        target.getInventory().addItem(item)
                .values().forEach(rest -> target.getWorld().dropItemNaturally(target.getLocation(), rest));
    }

    @Override
    public boolean onCommand(CommandSender s, Command command, String label, String[] a) {
        if (a.length == 0) {
            s.sendMessage("§e/questtp give interest <назва> [гравець] §7- видати точку інтересу");
            s.sendMessage("§e/questtp give departure [гравець] §7- видати точку відправки (admin)");
            s.sendMessage("§e/questtp list §7- список точок інтересу");
            s.sendMessage("§e/questtp remove <назва> §7- прибрати точку інтересу");
            s.sendMessage("§e/questtp reset <гравець> §7- дозволити гравцю скрафтити знову (admin)");
            s.sendMessage("§e/questtp reload §7- перезавантажити конфіг (admin)");
            return true;
        }

        switch (a[0].toLowerCase()) {
            case "give" -> {
                if (a.length >= 2 && a[1].equalsIgnoreCase("interest")) {
                    if (!need(s, "questtp.eventmaker")) return true;
                    if (a.length < 3) {
                        s.sendMessage("§e/questtp give interest <назва> [гравець]");
                        return true;
                    }
                    String name = a[2];
                    if (!name.matches("[A-Za-z0-9_\\-]{1,32}")) {
                        s.sendMessage("§cНазва: лише a-z, 0-9, _ та -, до 32 символів. Підкреслення стануть пробілами.");
                        return true;
                    }
                    if (interestNamed(name) != null) {
                        s.sendMessage("§cТочка з такою назвою вже стоїть у світі.");
                        return true;
                    }
                    Player target = a.length > 3 ? Bukkit.getPlayerExact(a[3]) : (s instanceof Player pl ? pl : null);
                    if (target == null) {
                        s.sendMessage("§cГравця не знайдено.");
                        return true;
                    }
                    give(target, createItem(Type.INTEREST, name));
                    s.sendMessage("§aВидано точку інтересу «" + pretty(name) + "» гравцю " + target.getName() + ".");
                } else if (a.length >= 2 && a[1].equalsIgnoreCase("departure")) {
                    if (!need(s, "questtp.admin")) return true;
                    Player target = a.length > 2 ? Bukkit.getPlayerExact(a[2]) : (s instanceof Player pl ? pl : null);
                    if (target == null) {
                        s.sendMessage("§cГравця не знайдено.");
                        return true;
                    }
                    give(target, createItem(Type.DEPARTURE, null));
                    s.sendMessage("§aВидано точку відправки гравцю " + target.getName() + ".");
                } else {
                    s.sendMessage("§e/questtp give <interest|departure> ...");
                }
            }
            case "list" -> {
                if (!need(s, "questtp.eventmaker")) return true;
                List<Point> list = points.values().stream().filter(x -> x.type() == Type.INTEREST).toList();
                s.sendMessage("§aТочок інтересу: §f" + list.size());
                for (Point x : list) {
                    s.sendMessage("§7- §f" + x.name() + " §8(" + x.loc().getWorld().getName() + " "
                            + x.loc().getBlockX() + " " + x.loc().getBlockY() + " " + x.loc().getBlockZ() + ")");
                }
                long dep = points.values().stream().filter(x -> x.type() == Type.DEPARTURE).count();
                s.sendMessage("§7Точок відправки у світі: §f" + dep);
            }
            case "remove" -> {
                if (!need(s, "questtp.eventmaker")) return true;
                if (a.length < 2) {
                    s.sendMessage("§e/questtp remove <назва>");
                    return true;
                }
                Point p = interestNamed(a[1]);
                if (p == null) {
                    s.sendMessage("§cТакої точки інтересу немає.");
                    return true;
                }
                removePoint(p);
                s.sendMessage("§aТочку «" + pretty(p.name()) + "» прибрано.");
            }
            case "reset" -> {
                if (!need(s, "questtp.admin")) return true;
                if (a.length < 2) {
                    s.sendMessage("§e/questtp reset <гравець>");
                    return true;
                }
                OfflinePlayer op = Bukkit.getOfflinePlayerIfCached(a[1]);
                if (op == null) {
                    s.sendMessage("§cТакого гравця сервер не пам'ятає.");
                    return true;
                }
                crafted.remove(op.getUniqueId());
                saveCrafted();
                s.sendMessage("§aГравець " + a[1] + " може скрафтити точку відправки ще раз.");
            }
            case "reload" -> {
                if (!need(s, "questtp.admin")) return true;
                reloadConfig();
                Bukkit.removeRecipe(recipeKey);
                registerRecipe();
                s.sendMessage("§aКонфіг перезавантажено.");
            }
            default -> s.sendMessage("§cНевідома підкоманда.");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command command, String alias, String[] a) {
        if (a.length == 1) {
            return List.of("give", "list", "remove", "reset", "reload").stream()
                    .filter(x -> x.startsWith(a[0].toLowerCase())).toList();
        }
        if (a.length == 2 && a[0].equalsIgnoreCase("give")) {
            return List.of("interest", "departure").stream()
                    .filter(x -> x.startsWith(a[1].toLowerCase())).toList();
        }
        if (a.length == 2 && a[0].equalsIgnoreCase("remove")) {
            return points.values().stream().filter(x -> x.type() == Type.INTEREST)
                    .map(Point::name).filter(x -> x.toLowerCase().startsWith(a[1].toLowerCase())).toList();
        }
        return List.of();
    }
}
