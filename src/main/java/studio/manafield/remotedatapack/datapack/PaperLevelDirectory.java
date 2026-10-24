package studio.manafield.remotedatapack.datapack;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import java.nio.file.Path;

/** Public API compatibility boundary. Never selects the first loaded world or reads server.properties. */
public final class PaperLevelDirectory {
    private PaperLevelDirectory() { }
    public static Path resolve(Server server) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Level lookup must run on the main thread");
        if (server.getMinecraftVersion().startsWith("1.21.")) {
            // Before the unified 26.1 level layout, the canonical overworld owns the datapacks directory.
            World overworld = server.getWorld(NamespacedKey.minecraft("overworld"));
            return overworld != null && overworld.getEnvironment() == World.Environment.NORMAL
                    && overworld.getKey().equals(NamespacedKey.minecraft("overworld")) ? overworld.getWorldPath() : null;
        }
        try {
            // Lookup on the PUBLIC Server interface only. Avoid linking a method absent from 1.21.x.
            Object path = Server.class.getMethod("getLevelDirectory").invoke(server);
            return path instanceof Path directory ? directory : null;
        } catch (ReflectiveOperationException | SecurityException unavailable) {
            // Do not fall back to a dimension subdirectory on newer versions.
            return null;
        }
    }
}
