package studio.manafield.remotedatapack.datapack;

import studio.manafield.remotedatapack.http.Json;
import java.util.Set;

/** Release formats verified from Mojang version.json bundled with the official Paper downloads. */
public record PackFormat(String minecraft, int major, int minor, Set<Integer> reloadBuilds) {
    public PackFormat { reloadBuilds = Set.copyOf(reloadBuilds); }
    public static PackFormat forVersion(String version) {
        return switch (version) {
            case "1.21.9" -> new PackFormat(version, 88, 0, Set.of(59));
            case "1.21.10" -> new PackFormat(version, 88, 0, Set.of(130));
            case "1.21.11" -> new PackFormat(version, 94, 1, Set.of(132));
            case "26.1.1" -> new PackFormat(version, 101, 1, Set.of(29));
            case "26.1.2" -> new PackFormat(version, 101, 1, Set.of(74));
            case "26.2" -> new PackFormat(version, 107, 1, Set.of(134));
            case "26.3" -> new PackFormat(version, 121, 0, Set.of(211, 218));
            default -> throw new IllegalArgumentException("Unsupported Minecraft version: " + version + ". Verified versions: 1.21.9, 1.21.10, 1.21.11, 26.1.1, 26.1.2, 26.2, 26.3.");
        };
    }
    public long number() { return major * 1_000_000L + minor; }
    public String array() { return "[" + major + "," + minor + "]"; }
    public String json() { return "{\"minecraft\":" + Json.quote(minecraft) + ",\"major\":" + major + ",\"minor\":" + minor + "}"; }
}
