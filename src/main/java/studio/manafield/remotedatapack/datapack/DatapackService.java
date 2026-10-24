package studio.manafield.remotedatapack.datapack;

import studio.manafield.remotedatapack.auth.AuthFailure;
import studio.manafield.remotedatapack.http.Json;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/** On-demand listing only. The main-thread callback returns values; filesystem work stays on HTTP workers. */
public final class DatapackService {
    public static final int MAX_PACKS = 512;
    private final Supplier<List<PackSnapshot>> snapshot;
    private final Supplier<LocalPackScanner.Scan> scan;
    private final Semaphore capacity = new Semaphore(1);
    private volatile boolean filesEnabled;
    private volatile boolean pendingApply;
    public void enableFiles() { filesEnabled = true; }
    private java.util.function.BooleanSupplier pendingSource;
    public void pendingSource(java.util.function.BooleanSupplier source) {pendingSource=source;}
    private boolean pending() {return pendingSource==null?pendingApply:pendingSource.getAsBoolean();}
    public void changed() { pendingApply = true; }
    private final byte[] salt = new byte[32];
    public DatapackService(Supplier<List<PackSnapshot>> snapshot, Supplier<LocalPackScanner.Scan> scan) {
        this.snapshot = snapshot; this.scan = scan; new SecureRandom().nextBytes(salt);
    }
    public List<PackSnapshot> snapshot() { return snapshot.get(); }
    public String list(Supplier<List<PackSnapshot>> authorizedSnapshot, Runnable revalidate) {
        if (!capacity.tryAcquire()) throw new AuthFailure(503, "packs_busy");
        try {
            List<PackSnapshot> paper = authorizedSnapshot.get();
            String json = merge(paper, scan.get());
            revalidate.run(); // Quit, revoke, permission loss or timeout during I/O must suppress the list.
            return json;
        } finally { capacity.release(); }
    }
    private String merge(List<PackSnapshot> paper, LocalPackScanner.Scan scan) {
        Map<String, LocalPackScanner.Entry> local = new TreeMap<>();
        for (var entry : scan.entries()) local.put(entry.name(), entry);
        List<String> rows = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        boolean truncated = scan.truncated() || paper.size() > MAX_PACKS;
        for (var pack : paper) {
            if (rows.size() >= MAX_PACKS) { truncated = true; break; }
            if (!ids.add(pack.name())) continue;
            String leaf = pack.name().startsWith("file/") ? pack.name().substring(5) : null;
            if (leaf != null && !LocalPackScanner.safeLeaf(leaf)) leaf = null;
            var entry = leaf == null ? null : local.remove(leaf);
            boolean world = pack.source() == PackSnapshot.Source.WORLD;
            boolean matched = world && entry != null && entry.safe();
            String type = switch (pack.source()) {
                case BUILT_IN, FEATURE -> "builtin";
                case PLUGIN -> "plugin";
                default -> world && entry != null ? entry.type() : "unmapped";
            };
            String reason = switch (pack.source()) {
                case BUILT_IN, FEATURE -> "built_in";
                case PLUGIN -> "plugin_provided";
                default -> !matched ? "unverified_location" : pack.required() ? "required_pack"
                        : type.equals("directory") ? "directory_pack" : "read_only";
            };
            String name = leaf != null ? leaf : LocalPackScanner.safeLeaf(pack.name()) ? pack.name() : "데이터팩";
            rows.add(row("paper:" + pack.name(), name, type, pack.source().name().toLowerCase(Locale.ROOT),
                    pack.enabled(), pack.required(), true, matched, reason));
        }
        for (var entry : local.values()) {
            if (rows.size() >= MAX_PACKS) { truncated = true; break; }
            rows.add(row("local:" + entry.name(), entry.name(), entry.type(), "local", null, false, false,
                    entry.safe(), entry.safe() ? "not_discovered" : "unverified_location"));
        }
        return "{\"readOnly\":" + !filesEnabled + ",\"pendingApply\":" + pending() + ",\"directoryStatus\":" + Json.quote(scan.status()) + ",\"truncated\":" + truncated
                + ",\"packs\":[" + String.join(",", rows) + "]}";
    }
    private String row(String key, String name, String type, String source, Boolean enabled, boolean required,
                       boolean discovered, boolean localMatch, String reason) {
        return "{\"id\":" + Json.quote(id(key)) + ",\"name\":" + Json.quote(name) + ",\"type\":" + Json.quote(type)
                + ",\"source\":" + Json.quote(source) + ",\"enabled\":" + enabled + ",\"required\":" + required
                + ",\"discovered\":" + discovered + ",\"localMatch\":" + localMatch
                + ",\"modifiable\":" + (filesEnabled && reason.equals("read_only"))
                + ",\"downloadable\":" + (filesEnabled && reason.equals("read_only"))
                + ",\"deletable\":" + (filesEnabled && reason.equals("read_only") && Boolean.FALSE.equals(enabled))
                + ",\"protected\":" + !reason.equals("read_only")
                + ",\"protectionReason\":" + Json.quote(filesEnabled && reason.equals("read_only") ? "zip_management" : reason) + "}";
    }
    public record Target(String name, boolean enabled) { }
    /** Resolve the opaque identifier against a fresh main-thread snapshot and safe local scan. */
    public Target resolve(String requested, List<PackSnapshot> paper) {
        var local = scan.get();
        if (!local.status().equals("ok") || local.truncated() || paper.size() > MAX_PACKS) throw new AuthFailure(409,"unverified_location");
        for (var pack : paper) {
            if (!id("paper:"+pack.name()).equals(requested)) continue;
            if (pack.source()!=PackSnapshot.Source.WORLD || pack.required() || !pack.name().startsWith("file/")) break;
            String name=pack.name().substring(5);
            if (!LocalPackScanner.safeLeaf(name)) break;
            for (var entry:local.entries()) if (entry.name().equals(name) && entry.type().equals("zip") && entry.safe()) return new Target(name,pack.enabled());
            break;
        }
        throw new AuthFailure(409,"protected_or_missing_pack");
    }
    private String id(String key) {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256"); hash.update(salt);
            return HexFormat.of().formatHex(hash.digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
