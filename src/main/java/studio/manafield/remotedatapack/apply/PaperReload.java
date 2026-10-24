package studio.manafield.remotedatapack.apply;

import io.papermc.paper.ServerBuildInfo;
import io.papermc.paper.event.server.ServerResourcesReloadedEvent;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.event.*;
import studio.manafield.remotedatapack.auth.AuthFailure;

/** Uses only public APIs. Completion contract verified specifically against Paper 26.3 build 211. */
public final class PaperReload implements Listener {
    private final Server server;
    private boolean invoking;
    private int completions;
    public PaperReload(Server server) {this.server=server;}
    public static boolean supported() {
        var build=ServerBuildInfo.buildInfo();
        return build.brandId().equals(ServerBuildInfo.BRAND_PAPER_ID) && build.minecraftVersionId().equals("26.3")
                && build.buildNumber().orElse(-1)==211;
    }
    public void reload() {
        if(!Bukkit.isPrimaryThread())throw new IllegalStateException("Reload must run on main thread");
        if(!supported() || invoking)throw new AuthFailure(503,"apply_unsupported_build");
        invoking=true;completions=0;
        try {
            // Build 211 pumps its main executor until its internal reload future finishes, then returns.
            // A failed future is NOT thrown by this void API. Require its official success event too.
            server.reloadData();
            if(completions!=1)throw new AuthFailure(503,"reload_completion_unconfirmed");
        } finally {invoking=false;}
    }
    @EventHandler(priority=EventPriority.MONITOR)
    public void completed(ServerResourcesReloadedEvent event) {
        if(Bukkit.isPrimaryThread() && invoking && event.getCause()==ServerResourcesReloadedEvent.Cause.PLUGIN)completions++;
    }
}
