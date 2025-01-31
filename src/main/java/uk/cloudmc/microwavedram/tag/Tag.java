package uk.cloudmc.microwavedram.tag;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.types.InheritanceNode;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Particle;
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

import java.sql.*;
import java.util.logging.Logger;

public final class Tag extends JavaPlugin implements CommandExecutor {

    class EventListener implements Listener {
        @EventHandler
        public void onPlayerHit(EntityDamageByEntityEvent event) throws SQLException {
            if (event.getEntity() instanceof Player damaged && event.getDamager() instanceof Player attacker) {
                if (!isTagged(attacker)) return;
                if (isTagged(damaged)) return;

                long refresh_time = 0;

                PreparedStatement query = connection.prepareStatement("SELECT cooldown_end FROM tag_cooldown_expire WHERE uuid = ?");
                query.setString(1, damaged.getUniqueId().toString());

                try (ResultSet rs = query.executeQuery()) {
                    if (rs.next()) {
                        refresh_time = rs.getLong("cooldown_end");
                    }
                }

                if (refresh_time > System.currentTimeMillis()) {
                    attacker.sendMessage(String.format("You cannot tag this player for another %.0f secconds", (float) (refresh_time - System.currentTimeMillis()) / 1000f));
                    return;
                }

                PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO tag_cooldown_expire (uuid, cooldown_end)
                    VALUES (?, ?)
                    ON DUPLICATE KEY UPDATE cooldown_end = VALUES(cooldown_end);
                """);
                statement.setString(1, attacker.getUniqueId().toString());
                statement.setLong(2, System.currentTimeMillis() + resetTime);
                statement.execute();

                Particle.DustTransition transition = new Particle.DustTransition(Color.fromRGB(255, 0, 0), Color.fromRGB(255, 255, 255), 1.0F);

                damaged.getWorld().spawnParticle(Particle.DUST_COLOR_TRANSITION, damaged.getLocation(), 100, .2f, .4f, .2f, transition);
                damaged.sendMessage(String.format("§aYou have been tagged by %s", attacker.getName()));
                attacker.sendMessage(String.format("§aYou have tagged %s", damaged.getName()));

                try {
                    setTagged(attacker, false);
                    setTagged(damaged, true);
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    private static final Logger logger = Logger.getLogger("Tag");
    private static long resetTime;
    private static LuckPerms luckPerms;
    private static Connection connection;

    @Override
    public void onEnable() {

        this.saveDefaultConfig();

        Bukkit.getPluginManager().registerEvents(new EventListener(), this);
        getCommand("tag").setExecutor(this);

        RegisteredServiceProvider<LuckPerms> provider = Bukkit.getServicesManager().getRegistration(LuckPerms.class);
        if (provider != null) {
            luckPerms = provider.getProvider();
        }

        resetTime = getConfig().getInt("reset_time");

        String db_server = getConfig().getString("database.server");
        String db_user = getConfig().getString("database.user");
        String db_password = getConfig().getString("database.password");

        try {
            assert db_server != null;
            connection = DriverManager.getConnection(db_server, db_user, db_password);

            Statement statement = connection.createStatement();
            statement.execute("CREATE TABLE IF NOT EXISTS tag_state_mirror(uuid CHAR(36) PRIMARY KEY, tagged BOOLEAN NOT NULL DEFAULT FALSE) ENGINE=MEMORY;"); // Using memory table as the check for "isTagged" occurs often
            statement.execute("CREATE TABLE IF NOT EXISTS tag_state(uuid CHAR(36) PRIMARY KEY, tagged BOOLEAN NOT NULL DEFAULT FALSE);");
            statement.execute("CREATE TABLE IF NOT EXISTS tag_cooldown_expire(uuid CHAR(36) PRIMARY KEY, cooldown_end  BIGINT NOT NULL);");

            statement.execute("""
                        DELIMITER $$
                        CREATE PROCEDURE persist_tag_state()
                        BEGIN
                            REPLACE INTO tag_state (uuid, tagged)
                            SELECT uuid, tagged FROM tag_state_mirror;
                        END $$
                        DELIMITER ;
                    """);

            statement.execute("""
                        DELIMITER $$
                        CREATE TRIGGER after_tag_state_insert_update
                        AFTER INSERT ON tag_state
                        FOR EACH ROW
                        BEGIN
                            REPLACE INTO tag_state_mirror (uuid, tagged)
                            VALUES (NEW.uuid, NEW.tagged);
                        END $$
                        DELIMITER ;
                    """);

        } catch (SQLException e) {
            logger.warning("Failed to connect to TagPlugin Database");
        } catch (AssertionError e) {
            logger.warning("Database config failure " + e.getMessage());
        }

    }

    public static boolean isTagged(Player player) throws SQLException {
        String query = "SELECT COALESCE((SELECT tagged FROM tag_state_mirror WHERE uuid = ?), FALSE) AS tagged_value";

        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, player.getUniqueId().toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return resultSet.getBoolean("tagged_value");
                }
            }
        }

        return false;
    }

    public void setTagged(Player player, boolean tagged) throws SQLException {
        String query = """
            INSERT INTO tag_state (uuid, tagged)
            VALUES (?, ?)
            ON DUPLICATE KEY UPDATE tagged = VALUES(tagged);
        """;

        InheritanceNode node = InheritanceNode.builder("tagged").build();
        User user = luckPerms.getUserManager().getUser(player.getUniqueId());

        if (user == null) {
            logger.warning("Could not find LP User " + player.getUniqueId());
        }

        if (tagged) {
            user.data().add(node);
        } else {
            user.data().remove(node);
        }
        luckPerms.getUserManager().saveUser(user);


        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, player.getUniqueId().toString());
            statement.setBoolean(2, tagged);
            statement.executeUpdate();
        }

        String updateMirrorQuery = "REPLACE INTO tag_state_mirror (uuid, tagged) VALUES (?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(updateMirrorQuery)) {
            statement.setString(1, player.getUniqueId().toString());
            statement.setBoolean(2, tagged);
            statement.executeUpdate();
        }

    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, Command command, @NotNull String label, String[] args) {
        if (command.getName().equalsIgnoreCase("tag")) {

            if (!sender.hasPermission("tag.tag")) {
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

            try {
                boolean isTagged = isTagged(target);
                setTagged(target, !isTagged);

                if (isTagged) {
                    sender.sendMessage(String.format("%s is now not tagged", target.getName()));
                } else {
                    sender.sendMessage(String.format("%s is now tagged", target.getName()));
                }

                return true;
            } catch (SQLException e) {
                sender.sendMessage("Failed to change tag state. " + e);
            }
        }

        return false;
    }

    @Override
    public void onDisable() {}
}
