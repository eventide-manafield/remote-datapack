package studio.manafield.remotedatapack.file;

import java.util.Map;

public record FileConfig(long maxBytes, int backupsPerPack, long backupBytes) {
    public static FileConfig from(Map<String, Object> values) {
        int upload = integer(values, "max-upload-mib", 20, 1, 20);
        integer(values, "max-parallel-uploads", 1, 1, 1);
        return new FileConfig(upload * 1048576L, integer(values, "backup-count-per-pack", 3, 1, 10),
                integer(values, "backup-max-total-mib", 200, upload, 1024) * 1048576L);
    }
    private static int integer(Map<String, Object> values, String key, int fallback, int min, int max) {
        Object value = values.getOrDefault("packs." + key, fallback);
        if (!(value instanceof Integer number) || number < min || number > max)
            throw new IllegalArgumentException("packs." + key + " must be an integer in " + min + ".." + max);
        return number;
    }
}
