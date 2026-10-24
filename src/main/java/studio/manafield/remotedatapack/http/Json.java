package studio.manafield.remotedatapack.http;

public final class Json {
    private Json() { }
    public static String quote(String text) {
        StringBuilder output = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            if (c == '"' || c == '\\') output.append('\\').append(c);
            else if (c < 32) output.append(String.format("\\u%04x", (int) c));
            else output.append(c);
        }
        return output.append('"').toString();
    }
}
