package gg.sxull.coinflip;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.*;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public final class SXULLCoinflip extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private Economy economy;
    private final Map<UUID, Challenge> challenges = new LinkedHashMap<>();
    private final Map<UUID, List<String>> history = new HashMap<>();
    private final Set<UUID> busy = new HashSet<>();
    private String title;

    private static final class Challenge {
        final UUID owner;
        final String name;
        final double stake;
        final long created;
        Challenge(Player p, double amount) {
            owner = p.getUniqueId(); name = p.getName(); stake = amount; created = System.currentTimeMillis();
        }
    }

    @Override public void onEnable() {
        saveDefaultConfig();
        if (Bukkit.getPluginManager().getPlugin("Vault") == null || !Bukkit.getPluginManager().isPluginEnabled("Vault")) {
            getLogger().severe("Vault is missing or disabled. Install Vault and a compatible economy plugin; disabling SXULLCoinflip.");
            Bukkit.getPluginManager().disablePlugin(this); return;
        }
        RegisteredServiceProvider<Economy> registration = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (registration == null || registration.getProvider() == null) {
            getLogger().severe("No Vault economy provider is registered. Install and enable an economy plugin (Vault alone is not an economy). Disabling SXULLCoinflip.");
            Bukkit.getPluginManager().disablePlugin(this); return;
        }
        economy = registration.getProvider();
        getLogger().info("Vault economy hooked successfully: " + economy.getName());
        title = color(getConfig().getString("gui.title", "&8SXULL &7» &6Coinflip"));
        Objects.requireNonNull(getCommand("coinflip")).setExecutor(this);
        Objects.requireNonNull(getCommand("coinflip")).setTabCompleter(this);
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, this::expireChallenges, 20L, 20L);
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player p)) { sender.sendMessage("Only players can open the Coinflip GUI."); return true; }
            openMenu(p, 0); return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("reload")) {
            if (!sender.hasPermission("sxull.coinflip.reload")) { msg(sender, "&cNo permission."); return true; }
            reloadConfig(); title = color(getConfig().getString("gui.title", "&8SXULL &7» &6Coinflip"));
            msg(sender, "&aConfiguration reloaded."); return true;
        }
        if (!(sender instanceof Player p)) { sender.sendMessage("This command requires a player."); return true; }
        switch (sub) {
            case "create" -> {
                if (args.length != 2) { usage(p); return true; }
                double amount;
                try { amount = Double.parseDouble(args[1]); } catch (NumberFormatException ex) { msg(p, "&cEnter a valid amount."); return true; }
                double min = getConfig().getDouble("minimum-bet", 10), max = getConfig().getDouble("maximum-bet", 1000000);
                if (!Double.isFinite(amount) || amount < min || amount > max) { msg(p, "&cBet must be between " + money(min) + " and " + money(max) + "."); return true; }
                synchronized (challenges) {
                    if (challenges.containsKey(p.getUniqueId()) || busy.contains(p.getUniqueId())) { msg(p, "&cYou already have a waiting coinflip."); return true; }
                    if (economy.getBalance(p) < amount) { msg(p, "&cYou do not have enough money."); return true; }
                    EconomyResponse response = economy.withdrawPlayer(p, amount);
                    if (!response.transactionSuccess()) { msg(p, "&cPayment failed: " + response.errorMessage); return true; }
                    challenges.put(p.getUniqueId(), new Challenge(p, amount));
                }
                msg(p, "&aChallenge created for &e" + money(amount) + "&a. Open &e/coinflip &ato find an opponent.");
                openMenu(p, 0); return true;
            }
            case "join" -> {
                if (args.length != 2) { usage(p); return true; }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) { msg(p, "&cThat player is not online."); return true; }
                join(p, target.getUniqueId()); return true;
            }
            case "cancel" -> {
                synchronized (challenges) {
                    Challenge c = challenges.get(p.getUniqueId());
                    if (c == null) { msg(p, "&cYou have no waiting challenge."); return true; }
                    EconomyResponse refund = economy.depositPlayer(p, c.stake);
                    if (!refund.transactionSuccess()) { msg(p, "&cRefund failed. Your challenge is kept active; contact staff."); return true; }
                    challenges.remove(p.getUniqueId());
                    msg(p, "&aYour stake of &e" + money(c.stake) + " &awas refunded.");
                } return true;
            }
            case "history" -> {
                List<String> entries = history.getOrDefault(p.getUniqueId(), List.of());
                msg(p, "&6Recent results:");
                if (entries.isEmpty()) msg(p, "&7No results recorded this session.");
                else entries.forEach(s -> p.sendMessage(color("&8 • &7" + s)));
                return true;
            }
            default -> { usage(p); return true; }
        }
    }

    private void join(Player joiner, UUID ownerId) {
        if (joiner.getUniqueId().equals(ownerId)) { msg(joiner, "&cYou cannot join your own challenge."); return; }
        synchronized (challenges) {
            Challenge c = challenges.get(ownerId);
            if (c == null) { msg(joiner, "&cThat challenge is no longer available."); return; }
            if (busy.contains(ownerId) || busy.contains(joiner.getUniqueId())) { msg(joiner, "&cA coinflip involving you is already processing."); return; }
            if (challenges.containsKey(joiner.getUniqueId())) { msg(joiner, "&cCancel your own waiting challenge first."); return; }
            if (economy.getBalance(joiner) < c.stake) { msg(joiner, "&cYou do not have enough money."); return; }
            busy.add(ownerId); busy.add(joiner.getUniqueId());
            EconomyResponse withdrawal = economy.withdrawPlayer(joiner, c.stake);
            if (!withdrawal.transactionSuccess()) { busy.remove(ownerId); busy.remove(joiner.getUniqueId()); msg(joiner, "&cPayment failed: " + withdrawal.errorMessage); return; }
            challenges.remove(ownerId);
            Player owner = Bukkit.getPlayer(ownerId);
            double pot = c.stake * 2.0;
            double feePercent = Math.max(0, Math.min(100, getConfig().getDouble("house-fee-percent", 0)));
            double fee = pot * feePercent / 100.0;
            Player winner = ThreadLocalRandom.current().nextBoolean() ? joiner : owner;
            double payout = pot - fee;
            if (winner == null || !winner.isOnline()) {
                // Winner disconnected during resolution: refund both collected stakes rather than lose funds.
                EconomyResponse r1 = economy.depositPlayer(joiner, c.stake);
                OfflinePlayer original = Bukkit.getOfflinePlayer(ownerId);
                EconomyResponse r2 = economy.depositPlayer(original, c.stake);
                if (!r1.transactionSuccess() || !r2.transactionSuccess())
                    getLogger().severe("Coinflip refund failure for challenge owner " + ownerId + "; manual reconciliation required.");
                busy.remove(ownerId); busy.remove(joiner.getUniqueId()); return;
            }
            EconomyResponse pay = economy.depositPlayer(winner, payout);
            if (!pay.transactionSuccess()) {
                // Try to reverse the second stake and restore the original challenge stake.
                EconomyResponse r1 = economy.depositPlayer(joiner, c.stake);
                EconomyResponse r2 = economy.depositPlayer(Bukkit.getOfflinePlayer(ownerId), c.stake);
                getLogger().severe("Coinflip payout failed for " + winner.getName() + "; automatic refund responses: " + r1.transactionSuccess() + ", " + r2.transactionSuccess() + ". Manual reconciliation may be required.");
                msg(joiner, "&cPayout failed. Staff have been alerted.");
                if (owner != null) msg(owner, "&cPayout failed. Staff have been alerted.");
                busy.remove(ownerId); busy.remove(joiner.getUniqueId()); return;
            }
            String result = winner.getName() + " won " + money(payout) + " (pot " + money(pot) + ")";
            remember(joiner.getUniqueId(), result); remember(ownerId, result);
            msg(joiner, "&6" + winner.getName() + " &awon &e" + money(payout) + "&a!");
            if (owner != null) msg(owner, "&6" + winner.getName() + " &awon &e" + money(payout) + "&a!");
            if (getConfig().getBoolean("sounds.enabled", true)) {
                joiner.playSound(joiner.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.1f);
                if (owner != null) owner.playSound(owner.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.1f);
            }
            busy.remove(ownerId); busy.remove(joiner.getUniqueId());
        }
    }

    private void openMenu(Player p, int page) {
        List<Challenge> active;
        synchronized (challenges) { active = new ArrayList<>(challenges.values()); }
        int pageSize = 45, maxPage = Math.max(0, (active.size() - 1) / pageSize);
        int current = Math.min(Math.max(0, page), maxPage);
        Inventory inv = Bukkit.createInventory(null, 54, title);
        int start = current * pageSize;
        for (int i = start; i < Math.min(active.size(), start + pageSize); i++) {
            Challenge c = active.get(i);
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setOwningPlayer(Bukkit.getOfflinePlayer(c.owner));
            meta.setDisplayName(color("&6" + c.name + "'s Coinflip"));
            meta.setLore(List.of(color("&7Bet: &e" + money(c.stake)), color("&7Pot: &a" + money(c.stake * 2)), color("&eClick to join")));
            head.setItemMeta(meta); inv.setItem(i - start, head);
        }
        inv.setItem(49, item(Material.GOLD_INGOT, "&6Create a Coinflip", "&7Use &f/coinflip create <amount>"));
        inv.setItem(45, item(Material.ARROW, "&ePrevious page"));
        inv.setItem(53, item(Material.ARROW, "&eNext page"));
        inv.setItem(48, item(Material.SUNFLOWER, "&eRefresh list"));
        inv.setItem(50, item(Material.BARRIER, "&cCancel your challenge", "&7Use &f/coinflip cancel"));
        p.openInventory(inv);
        p.setMetadata("sxull-cf-page", new org.bukkit.metadata.FixedMetadataValue(this, current));
    }

    @EventHandler public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p) || !e.getView().getTitle().equals(title)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() == null || e.getClickedInventory() != e.getView().getTopInventory()) return;
        int slot = e.getRawSlot();
        if (slot == 45) { openMenu(p, p.getMetadata("sxull-cf-page").isEmpty() ? 0 : p.getMetadata("sxull-cf-page").get(0).asInt() - 1); return; }
        if (slot == 53) { openMenu(p, p.getMetadata("sxull-cf-page").isEmpty() ? 1 : p.getMetadata("sxull-cf-page").get(0).asInt() + 1); return; }
        if (slot == 48) { openMenu(p, 0); return; }
        if (slot == 49) { msg(p, "&eCreate a challenge with &f/coinflip create <amount>&e."); return; }
        if (slot == 50) { p.closeInventory(); onCommand(p, Objects.requireNonNull(getCommand("coinflip")), "coinflip", new String[]{"cancel"}); return; }
        ItemStack clicked = e.getCurrentItem();
        if (clicked == null || clicked.getType() != Material.PLAYER_HEAD || !(clicked.getItemMeta() instanceof SkullMeta meta)) return;
        String name = ChatColor.stripColor(meta.getDisplayName()).replace("'s Coinflip", "");
        Player target = Bukkit.getPlayerExact(name);
        if (target == null) { msg(p, "&cThat player is no longer online."); openMenu(p, 0); return; }
        p.closeInventory(); join(p, target.getUniqueId());
    }

    @EventHandler public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTitle().equals(title) && e.getRawSlots().stream().anyMatch(s -> s < e.getView().getTopInventory().getSize())) e.setCancelled(true);
    }
    private void expireChallenges() {
        long expiry = getConfig().getLong("challenge-expiration-seconds", 300) * 1000L;
        synchronized (challenges) {
            Iterator<Challenge> it = challenges.values().iterator();
            while (it.hasNext()) {
                Challenge c = it.next();
                if (System.currentTimeMillis() - c.created < expiry) continue;
                EconomyResponse r = economy.depositPlayer(Bukkit.getOfflinePlayer(c.owner), c.stake);
                if (!r.transactionSuccess()) { getLogger().severe("Failed to refund expired challenge for " + c.name + ": " + r.errorMessage); continue; }
                Player owner = Bukkit.getPlayer(c.owner);
                if (owner != null) msg(owner, "&eYour expired coinflip was refunded: &6" + money(c.stake));
                it.remove();
            }
        }
    }
    private void remember(UUID id, String result) {
        List<String> list = history.computeIfAbsent(id, k -> new ArrayList<>());
        list.add(0, result);
        int limit = Math.max(1, getConfig().getInt("history-limit", 10));
        while (list.size() > limit) list.remove(list.size() - 1);
    }
    private ItemStack item(Material material, String name, String... lore) {
        ItemStack stack = new ItemStack(material); var meta = stack.getItemMeta();
        meta.setDisplayName(color(name));
        if (lore.length > 0) meta.setLore(Arrays.stream(lore).map(SXULLCoinflip::color).toList());
        stack.setItemMeta(meta); return stack;
    }
    private String money(double amount) { return economy.format(amount); }
    private static String color(String s) { return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s); }
    private void msg(CommandSender s, String message) { s.sendMessage(color(getConfig().getString("messages.prefix", "&8[&6SXULL&8] &7") + message)); }
    private void usage(Player p) { msg(p, "&eUsage: /coinflip, /coinflip create <amount>, /coinflip join <player>, /coinflip cancel, /coinflip history"); }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return List.of("create", "join", "cancel", "history", "reload").stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        if (args.length == 2 && args[0].equalsIgnoreCase("join")) return Bukkit.getOnlinePlayers().stream().map(Player::getName).filter(n -> n.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        return Collections.emptyList();
    }
}