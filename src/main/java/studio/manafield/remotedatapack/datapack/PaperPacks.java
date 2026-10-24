package studio.manafield.remotedatapack.datapack;

import io.papermc.paper.datapack.DatapackSource;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import java.util.ArrayList;
import java.util.List;

/** Main-thread Paper adapter. Discovery refresh never calls resource reload or commands. */
public final class PaperPacks {
    private PaperPacks() { }
    public static List<PackSnapshot> snapshot(Server server) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Datapack API must run on the main thread");
        var result = new ArrayList<PackSnapshot>();
        for (var pack : server.getDatapackManager().getPacks()) {
            // One extra entry lets the HTTP service report truncation without an unbounded copy.
            if (result.size() > DatapackService.MAX_PACKS) break;
            result.add(new PackSnapshot(pack.getName(), source(pack.getSource()), pack.isEnabled(), pack.isRequired()));
        }
        return List.copyOf(result);
    }
    public static void refresh(Server server, String filename, boolean deleted) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Datapack API must run on the main thread");
        var manager=server.getDatapackManager(); manager.refreshPacks();
        var pack=manager.getPack("file/"+filename);
        if (deleted ? pack!=null : pack==null || pack.getSource()!=DatapackSource.WORLD) {
            throw new IllegalStateException("Datapack discovery did not reflect file operation");
        }
    }
    private static PackSnapshot.Source source(DatapackSource source) {
        if (source == DatapackSource.WORLD) return PackSnapshot.Source.WORLD;
        if (source == DatapackSource.BUILT_IN) return PackSnapshot.Source.BUILT_IN;
        if (source == DatapackSource.FEATURE) return PackSnapshot.Source.FEATURE;
        if (source == DatapackSource.PLUGIN) return PackSnapshot.Source.PLUGIN;
        if (source == DatapackSource.SERVER) return PackSnapshot.Source.SERVER;
        if (source == DatapackSource.DEFAULT) return PackSnapshot.Source.DEFAULT;
        return PackSnapshot.Source.UNKNOWN;
    }
}
