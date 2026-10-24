package studio.manafield.remotedatapack.file;

import studio.manafield.remotedatapack.auth.AuthFailure;
import studio.manafield.remotedatapack.datapack.PackFormat;
import studio.manafield.remotedatapack.http.Json;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.zip.*;

/** A bounded, in-memory starter ZIP. Never writes to the server or registers automatic functions. */
public final class TemplateArchive {
    public record Generated(String filename, byte[] bytes) { }
    private final PackFormat format;
    public TemplateArchive(PackFormat format) { this.format = Objects.requireNonNull(format); }
    public Generated generate(byte[] request) {
        try {
            if (request.length > 8192) throw new IllegalArgumentException();
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(request)).toString();
            Object parsed = MetadataJson.parse(text);
            if (!(parsed instanceof Map<?, ?> fields) || !fields.keySet().equals(Set.of("namespace", "author", "description")))
                throw new IllegalArgumentException();
            String namespace = field(fields, "namespace", 64), author = field(fields, "author", 80), description = field(fields, "description", 512);
            if (!namespace.matches("[a-z0-9][a-z0-9_.-]{0,63}") || namespace.equals("minecraft")) throw new IllegalArgumentException();
            var bytes = new ByteArrayOutputStream();
            try (var zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
                entry(zip, "pack.mcmeta", "{\n  \"pack\": {\n    \"min_format\": " + format.array()
                        + ",\n    \"max_format\": " + format.array() + ",\n    \"description\": " + Json.quote(description) + "\n  }\n}\n");
                entry(zip, "README.txt", "Namespace: " + namespace + "\nAuthor: " + author + "\nMinecraft: " + format.minecraft()
                        + "\nData pack format: " + format.major() + "." + format.minor()
                        + "\n\nEdit data/" + namespace + "/function/main.mcfunction to start.\n"
                        + "No load/tick functions are registered.\nAfter editing, upload the ZIP and explicitly apply changes.\n"
                        + "Author is recorded here; it is not a standard Minecraft pack metadata field.\n");
                entry(zip, "data/" + namespace + "/function/main.mcfunction", "# Add commands here. This file does not run automatically.\n");
            }
            return new Generated(namespace + ".zip", bytes.toByteArray());
        } catch (IllegalArgumentException | IOException failure) { throw new AuthFailure(400, "invalid_template_fields"); }
    }
    private static String field(Map<?, ?> fields, String key, int max) {
        if (!(fields.get(key) instanceof String value) || value.isBlank() || value.length() > max
                || value.codePoints().anyMatch(c -> Character.isISOControl(c) || Character.getType(c) == Character.FORMAT
                || c >= 0xd800 && c <= 0xdfff)) throw new IllegalArgumentException();
        return value.strip();
    }
    private static void entry(ZipOutputStream zip, String name, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        var entry = new ZipEntry(name);
        entry.setTimeLocal(LocalDateTime.of(1980, 1, 1, 0, 0));
        entry.setMethod(ZipEntry.STORED); entry.setSize(bytes.length);
        var crc = new CRC32(); crc.update(bytes); entry.setCrc(crc.getValue());
        zip.putNextEntry(entry); zip.write(bytes); zip.closeEntry();
    }
}
