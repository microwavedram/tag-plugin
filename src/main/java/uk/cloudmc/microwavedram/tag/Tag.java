package uk.cloudmc.microwavedram.tag;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.types.InheritanceNode;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.logging.Logger;

public final class Tag extends JavaPlugin implements CommandExecutor {

    class EventListener implements Listener {
        @EventHandler
        public void onPlayerHit(EntityDamageByEntityEvent event) {
            if (event.getEntity() instanceof Player damaged && event.getDamager() instanceof Player attacker) {
                if (!isTagged(attacker)) return;
                if (isTagged(damaged)) return;

                setTagged(attacker, false);
                setTagged(damaged, true);
            }
        }
    }

    private static final Logger logger = Logger.getLogger("Tag");
    private static LuckPerms luckPerms;

    @Override
    public void onEnable() {

        logger.info("Hello!");

        this.saveDefaultConfig();

        Bukkit.getPluginManager().registerEvents(new EventListener(), this);
        getCommand("tag").setExecutor(this);

        RegisteredServiceProvider<LuckPerms> provider = Bukkit.getServicesManager().getRegistration(LuckPerms.class);
        if (provider != null) {
            luckPerms = provider.getProvider();
        }

        if (getConfig().contains("tagged_players")) {
            for (String id : getConfig().getConfigurationSection("tagged_players").getKeys(false)) {
                Player player = Bukkit.getPlayer(UUID.fromString(id));
                if (player != null && getConfig().getBoolean("tagged_players." + id)) {
                    setTagged(player, true); // Reapply tag to the player
                }
            }
        }
    }

    public boolean isTagged(Player player) {
        return getConfig().getBoolean("tagged_players." + player.getUniqueId(), false);
    }

    // Helper method to set the tagged status of a player
    public void setTagged(Player player, boolean tagged) {
        String id = player.getUniqueId().toString();

        InheritanceNode node = InheritanceNode.builder("tagged").build();
        User user = luckPerms.getUserManager().getUser(player.getUniqueId());

        if (user == null) {
            logger.warning("Could not find LP User " + player.getUniqueId());
        }

        if (tagged) {
            getConfig().set("tagged_players." + id, true);
            user.data().add(node);
        } else {
            getConfig().set("tagged_players." + id, null);
            user.data().remove(node);
        }

        luckPerms.getUserManager().saveUser(user);
        saveConfig();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, Command command, @NotNull String label, String[] args) {
        if (command.getName().equalsIgnoreCase("tag")) {

            if (!sender.hasPermission("tagplugin.tag")) {
                sender.sendMessage("You do not have permission to use this command.");
                return true;
            }

            if (args.length == 0) {
                sender.sendMessage("Please specify a player to tag.");
                return false;
            }

            Player target = Bukkit.getPlayer(args[0]);
            if (target == null) {
                sender.sendMessage("That player is not online.");
                return true;
            }

            boolean isTagged = isTagged(target);
            setTagged(target, !isTagged);

            if (isTagged) {
                sender.sendMessage(String.format("%s is now not tagged", target.getName()));
            } else {
                sender.sendMessage(String.format("%s is now tagged", target.getName()));
            }

            return true;
        }

        return false;
    }

    @Override
    public void onDisable() {

    }
}
