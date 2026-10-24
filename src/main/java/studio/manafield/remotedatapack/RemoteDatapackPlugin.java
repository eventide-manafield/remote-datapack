package studio.manafield.remotedatapack;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import studio.manafield.remotedatapack.auth.*;
import studio.manafield.remotedatapack.datapack.*;
import studio.manafield.remotedatapack.file.*;
import studio.manafield.remotedatapack.apply.*;
import studio.manafield.remotedatapack.config.WebConfig;
import studio.manafield.remotedatapack.http.HttpServerService;
import studio.manafield.remotedatapack.http.WebAssets;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;

public final class RemoteDatapackPlugin extends JavaPlugin implements Listener {
    public static final String PERMISSION = "remotedatapack.manage";
    private final AtomicLong generation = new AtomicLong();
    private final AtomicBoolean active = new AtomicBoolean();
    private final AtomicReference<HttpServerService> web = new AtomicReference<>();
    private final AtomicReference<AccessManager> access = new AtomicReference<>();
    // Main thread only. Object identity distinguishes delayed quits from new connections.
    private final Map<Player, String> connections = new IdentityHashMap<>();
    private MainThreadGate gate;
    private ExecutorService startup;
    private volatile String status = "시작 전";

    @Override public void onEnable() {
        long epoch = generation.incrementAndGet();
        active.set(true);
        status = "시작 중";
        Path directory = getDataFolder().toPath();
        Path levelDirectory = getServer().getLevelDirectory();
        ClassLoader resources = getClass().getClassLoader();
        var logger = getLogger();
        boolean onlineMode = getServer().getOnlineMode();
        MainThreadGate runGate = new MainThreadGate(getServer().getScheduler().getMainThreadExecutor(this), Duration.ofSeconds(2));
        gate = runGate;
        getServer().getPluginManager().registerEvents(this, this);
        PaperReload reload=new PaperReload(getServer());
        getServer().getPluginManager().registerEvents(reload,this);
        boolean applySupported=PaperReload.supported();
        startup = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "remote-datapack-startup"); thread.setDaemon(true); return thread;
        });
        startup.submit(() -> {
            HttpServerService candidate = null;
            AccessManager manager = null;
            FileService files = null;
            ApplyCoordinator apply = null;
            try {
                Map<String, Object> values = loadConfig(directory, resources);
                WebConfig config = WebConfig.from(values);
                AuthConfig auth = AuthConfig.from(values, config);
                if (!config.enabled()) {
                    if (generation.get() == epoch) status = "설정으로 비활성화됨";
                    logger.info("Web listener disabled by configuration."); return;
                }
                WebAssets assets = WebAssets.load(resources).withServerName(values.getOrDefault("ui.server-name", "Minecraft Server"));
                if (!active.get() || generation.get() != epoch) return;
                boolean trustedIdentity = onlineMode || auth.trustProxyIdentity();
                if (!onlineMode) logger.warning(trustedIdentity
                        ? "Offline-mode identity explicitly trusted. The operator must enforce authenticated proxy forwarding and block direct game access."
                        : "Offline-mode identity is not trusted. Web authentication is disabled.");
                manager = new AccessManager(auth, this::currentlyAllowed);
                AccessManager ready = manager;
                runGate.call(() -> {
                    if (!active.get() || generation.get() != epoch) throw new AuthFailure(503, "auth_unavailable");
                    access.set(ready);
                    for (Player player : getServer().getOnlinePlayers()) register(player, ready);
                    return true;
                });
                var localPacks = new LocalPackScanner(levelDirectory, values.getOrDefault("packs.directory", "auto"));
                var datapacks = new DatapackService(() -> PaperPacks.snapshot(getServer()), localPacks::scan);
                files = new FileService(localPacks.root(), FileConfig.from(values), manager, runGate, datapacks,
                        (name, deleted) -> PaperPacks.refresh(getServer(), name, deleted), line -> logger.info("Audit " + line));
                apply=new ApplyCoordinator(ApplyCoordinator.cooldown(values),System::nanoTime,
                        task->getServer().getScheduler().runTask(this,task),reload::reload,manager,line->logger.info("Audit "+line),applySupported);
                files.coordinate(apply);datapacks.pendingSource(apply::pending);
                candidate = HttpServerService.start(config, assets, trustedIdentity ? auth : null, manager, runGate, datapacks, files,apply);
                HttpServerService readyWeb = candidate;
                runGate.call(() -> {
                    if (!active.get() || generation.get() != epoch || !web.compareAndSet(null, readyWeb)) {
                        throw new AuthFailure(503, "auth_unavailable");
                    }
                    status = readyWeb.authOrigin().isEmpty() ? "웹 실행 중 / 인증 설정 필요" : "웹 인증·ZIP 관리·명시적 적용 가능";
                    return true;
                });
                logger.info("Web listener ready on " + config.bind().getHostAddress() + ":" + candidate.port() + ". Datapack ZIP management enabled; explicit apply enabled on supported build; activation changes disabled.");
                if (candidate.authOrigin().isEmpty()) logger.info("Authentication unavailable: configure web.public-url for the selected web.mode and trusted game identity.");
                if (config.directHttp()) logger.warning("Direct HTTP enabled: session credentials and uploads are not encrypted. Public URL and TCP port forwarding must match your configuration.");
                if (auth.localDevelopment()) logger.warning("Local HTTP development cookies enabled. This mode must not be published through a proxy.");
            } catch (Exception e) {
                if(apply!=null)apply.close();
                if (files != null) files.close();
                if (candidate != null) { web.compareAndSet(candidate, null); candidate.close(); }
                if (manager != null) { access.compareAndSet(manager, null); manager.close(); }
                if (!active.get() || generation.get() != epoch) return;
                status = "시작 실패 — 설정과 포트 충돌을 확인하세요";
                logger.severe("Web listener not started (" + e.getClass().getSimpleName() + "). Check config.yml, bundled resources and port availability.");
                if (e instanceof IllegalArgumentException) logger.severe(e.getMessage());
            }
        });
        startup.shutdown();
    }
    private boolean currentlyAllowed(UUID id) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Player authority must run on the main thread");
        Player player = getServer().getPlayer(id);
        return active.get() && player != null && player.isOnline() && player.hasPermission(PERMISSION);
    }
    private void register(Player player, AccessManager manager) {
        // The online profile is already loaded. Never update/complete it over the network on the tick thread.
        String skin = null;
        try {
            var url = player.getPlayerProfile().getTextures().getSkin();
            if (url != null) skin = url.toExternalForm();
        } catch (RuntimeException ignored) { /* Missing cosmetic data must not break authentication. */ }
        connections.put(player, manager.join(player.getUniqueId(), player.getName(), skin));
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void joined(PlayerJoinEvent event) {
        AccessManager manager = access.get();
        if (manager != null) {
            try { register(event.getPlayer(), manager); }
            catch (AuthFailure ignored) { getLogger().warning("Web identity capacity unavailable; link issuance denied."); }
        }
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void quit(PlayerQuitEvent event) {
        String connectionId = connections.remove(event.getPlayer());
        AccessManager manager = access.get();
        if (manager != null && connectionId != null) manager.quit(event.getPlayer().getUniqueId(), connectionId);
    }
    private static Map<String, Object> loadConfig(Path directory, ClassLoader resources) throws Exception {
        Files.createDirectories(directory);
        Path path = directory.resolve("config.yml");
        if (Files.isSymbolicLink(path)) throw new IOException("Symlink configuration is not supported");
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            try (InputStream defaults = resources.getResourceAsStream("config.yml")) {
                if (defaults == null) throw new IOException("Missing default configuration");
                Files.copy(defaults, path);
            }
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 65536) throw new IOException("Invalid configuration file");
        YamlConfiguration yaml = new YamlConfiguration();
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) { yaml.load(reader); }
        for (String section : new String[]{"web", "auth", "ui", "packs", "apply"}) {
            if (yaml.contains(section) && !yaml.isConfigurationSection(section)) throw new IllegalArgumentException(section + " must be a YAML mapping");
        }
        return yaml.getValues(true);
    }
    @Override public void onDisable() {
        active.set(false); generation.incrementAndGet(); status = "종료됨";
        AccessManager manager = access.getAndSet(null);
        if (manager != null) manager.close();
        if (gate != null) gate.close();
        connections.clear();
        HttpServerService running = web.getAndSet(null);
        if (running != null) running.close();
        if (startup != null) startup.shutdownNow();
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            if (sender instanceof Player player && access.get() != null) access.get().revoke(player.getUniqueId());
            sender.sendMessage("Remote Datapack 관리 권한이 없습니다."); return true;
        }
        AccessManager manager = access.get();
        if (args.length == 1 && args[0].equalsIgnoreCase("status")) {
            sender.sendMessage("Remote Datapack: " + status);
            if (sender instanceof Player player && manager != null) sender.sendMessage(manager.status(player.getUniqueId()));
            return true;
        }
        if (!(sender instanceof Player player) || !player.isOnline()) {
            sender.sendMessage("게임에 접속한 플레이어만 인증 링크를 발급하거나 회수할 수 있습니다."); return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("revoke")) {
            if (manager != null) manager.revoke(player.getUniqueId());
            player.sendMessage("모든 로그인 링크와 웹 세션을 회수했습니다."); return true;
        }
        if (args.length != 0) return false;
        HttpServerService listener = web.get();
        if (manager == null || listener == null || listener.authOrigin().isEmpty()) {
            player.sendMessage("인증을 사용할 수 없습니다. web.mode, web.public-url과 서버 인증 설정을 확인하세요."); return true;
        }
        try {
            String token = manager.issue(player.getUniqueId());
            player.sendMessage(Component.text("[Remote Datapack 열기 · 60초 이내 1회 사용]")
                    .clickEvent(ClickEvent.openUrl(listener.authOrigin() + "/#/auth/" + token)));
            player.sendMessage("링크를 공유하지 마세요. 게임에서 나가면 웹 접속권도 회수됩니다.");
        } catch (AuthFailure ignored) { player.sendMessage("인증 링크를 발급할 수 없습니다. 접속 상태와 권한을 확인하세요."); }
        return true;
    }
}
