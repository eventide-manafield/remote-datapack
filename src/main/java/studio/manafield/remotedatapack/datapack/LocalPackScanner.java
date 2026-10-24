package studio.manafield.remotedatapack.datapack;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

/** Bounded, non-recursive metadata listing. Never reads a pack's contents or follows a symlink. */
public final class LocalPackScanner {
    public record Entry(String name, String type, boolean safe) { }
    public record Scan(String status, List<Entry> entries, boolean truncated) { }
    private final Path root;

    public LocalPackScanner(Path levelDirectory, Object configured) {
        if (levelDirectory == null) { root = null; return; }
        Path expected = levelDirectory.toAbsolutePath().normalize().resolve("datapacks");
        // Explicit configuration can confirm the main-world directory, never select another tree.
        Path selected = null;
        if (configured instanceof String value && !value.isBlank()) {
            try {
                if (value.equals("auto") || Path.of(value).toAbsolutePath().normalize().equals(expected)) selected = expected;
            } catch (InvalidPathException ignored) { }
        }
        root = selected;
    }
    public Path root() { return root; }
    public Scan scan() {
        if (root == null) return new Scan("unavailable", List.of(), false);
        List<SecureDirectoryStream<Path>> handles = new ArrayList<>();
        try {
            var initial = Files.newDirectoryStream(root.getRoot());
            if (!(initial instanceof SecureDirectoryStream<Path> current)) {
                initial.close(); return new Scan("unsupported_filesystem", List.of(), false);
            }
            handles.add(current);
            for (Path component : root) {
                current = current.newDirectoryStream(component, NOFOLLOW_LINKS);
                handles.add(current);
            }
            var entries = new ArrayList<Entry>();
            int visited = 0;
            boolean truncated = false;
            for (Path item : current) {
                if (++visited > 1024) { truncated = true; break; }
                Path name = item.getFileName();
                if (!safeLeaf(name.toString())) continue;
                BasicFileAttributes attributes = attributes(current, name);
                if (attributes.isSymbolicLink()) {
                    entries.add(new Entry(name.toString(), "unmapped", false));
                } else if (attributes.isRegularFile() && name.toString().endsWith(".zip")) {
                    entries.add(new Entry(name.toString(), "zip", singleLink(name, attributes)));
                } else if (attributes.isDirectory()) {
                    try (var directory = current.newDirectoryStream(name, NOFOLLOW_LINKS)) {
                        var meta = attributes(directory, Path.of("pack.mcmeta"));
                        if (meta.isRegularFile() && !meta.isSymbolicLink()) entries.add(new Entry(name.toString(), "directory", true));
                        else if (meta.isSymbolicLink()) entries.add(new Entry(name.toString(), "directory", false));
                    } catch (NoSuchFileException ignored) {
                        // An ordinary directory without root pack.mcmeta is not a datapack candidate.
                    } catch (IOException ignored) { entries.add(new Entry(name.toString(), "directory", false)); }
                }
            }
            entries.sort(Comparator.comparing(Entry::name));
            return new Scan("ok", List.copyOf(entries), truncated);
        } catch (NoSuchFileException e) { return new Scan("missing", List.of(), false); }
        catch (IOException | SecurityException | UnsupportedOperationException e) { return new Scan("unavailable", List.of(), false); }
        finally {
            Collections.reverse(handles);
            for (var handle : handles) { try { handle.close(); } catch (IOException ignored) { } }
        }
    }
    private static BasicFileAttributes attributes(SecureDirectoryStream<Path> directory, Path name) throws IOException {
        return directory.getFileAttributeView(name, BasicFileAttributeView.class, NOFOLLOW_LINKS).readAttributes();
    }
    private boolean singleLink(Path name, BasicFileAttributes anchored) {
        try {
            // Read only link-count metadata. Compare inode identity with the anchored directory entry
            // so a concurrent path replacement cannot establish a safe mapping for another file.
            var unix = Files.readAttributes(root.resolve(name), "unix:nlink,fileKey", NOFOLLOW_LINKS);
            return anchored.fileKey() != null && anchored.fileKey().equals(unix.get("fileKey"))
                    && unix.get("nlink") instanceof Number count && count.longValue() == 1;
        } catch (IOException | UnsupportedOperationException | IllegalArgumentException | SecurityException e) { return false; }
    }
    public static boolean safeLeaf(String name) {
        return !name.isBlank() && name.length() <= 255 && !name.equals(".") && !name.equals("..")
                && name.chars().noneMatch(c -> c == '/' || c == '\\' || c == ':' || Character.isISOControl(c)
                || Character.getType(c) == Character.FORMAT);
    }
}
