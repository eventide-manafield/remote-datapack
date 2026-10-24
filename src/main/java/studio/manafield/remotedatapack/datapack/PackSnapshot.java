package studio.manafield.remotedatapack.datapack;

/** Only immutable values cross from Paper's main thread to HTTP workers. Never serialized directly. */
public record PackSnapshot(String name, Source source, boolean enabled, boolean required) {
    public enum Source { DEFAULT, BUILT_IN, FEATURE, WORLD, SERVER, PLUGIN, UNKNOWN }
}
