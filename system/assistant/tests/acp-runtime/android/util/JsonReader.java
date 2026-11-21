package android.util;

import java.io.Closeable;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Host-test stand-in for the subset of Android's JsonReader that IslandPayload uses. It tokenises
 * well-formed JSON (as produced by org.json) up front; strictness is not under test here.
 */
public final class JsonReader implements Closeable {
    private final List<JsonToken> types = new ArrayList<>();
    private final List<String> values = new ArrayList<>();
    private int index;

    public JsonReader(Reader in) throws IOException {
        StringBuilder text = new StringBuilder();
        char[] buffer = new char[4096];
        int n;
        while ((n = in.read(buffer)) != -1) text.append(buffer, 0, n);
        tokenize(text.toString());
    }

    public void setLenient(boolean lenient) {}

    private void add(JsonToken type, String value) {
        types.add(type);
        values.add(value);
    }

    private void tokenize(String s) throws IOException {
        Deque<Character> stack = new ArrayDeque<>();
        boolean expectName = false;
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            switch (c) {
                case '{': stack.push('{'); add(JsonToken.BEGIN_OBJECT, null); expectName = true; i++; break;
                case '}': stack.pop(); add(JsonToken.END_OBJECT, null); expectName = false; i++; break;
                case '[': stack.push('['); add(JsonToken.BEGIN_ARRAY, null); expectName = false; i++; break;
                case ']': stack.pop(); add(JsonToken.END_ARRAY, null); i++; break;
                case ',': expectName = !stack.isEmpty() && stack.peek() == '{'; i++; break;
                case ':': expectName = false; i++; break;
                case '"': {
                    StringBuilder out = new StringBuilder();
                    i++;
                    while (s.charAt(i) != '"') {
                        char ch = s.charAt(i++);
                        if (ch != '\\') { out.append(ch); continue; }
                        char e = s.charAt(i++);
                        switch (e) {
                            case 'n': out.append('\n'); break;
                            case 't': out.append('\t'); break;
                            case 'r': out.append('\r'); break;
                            case 'b': out.append('\b'); break;
                            case 'f': out.append('\f'); break;
                            case 'u': out.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                            default: out.append(e);
                        }
                    }
                    i++;
                    boolean name = expectName && !stack.isEmpty() && stack.peek() == '{';
                    add(name ? JsonToken.NAME : JsonToken.STRING, out.toString());
                    expectName = false;
                    break;
                }
                default: {
                    int start = i;
                    while (i < s.length() && "{}[],: \t\r\n".indexOf(s.charAt(i)) < 0) i++;
                    String literal = s.substring(start, i);
                    if (literal.equals("true") || literal.equals("false")) add(JsonToken.BOOLEAN, literal);
                    else if (literal.equals("null")) add(JsonToken.NULL, literal);
                    else add(JsonToken.NUMBER, literal);
                }
            }
        }
        add(JsonToken.END_DOCUMENT, null);
    }

    private void expect(JsonToken type) throws IOException {
        if (types.get(index) != type) throw new IllegalStateException("expected " + type + " but was " + types.get(index));
        index++;
    }

    public JsonToken peek() { return types.get(index); }
    public void beginObject() throws IOException { expect(JsonToken.BEGIN_OBJECT); }
    public void endObject() throws IOException { expect(JsonToken.END_OBJECT); }
    public void beginArray() throws IOException { expect(JsonToken.BEGIN_ARRAY); }
    public void endArray() throws IOException { expect(JsonToken.END_ARRAY); }
    public boolean hasNext() { return types.get(index) != JsonToken.END_OBJECT && types.get(index) != JsonToken.END_ARRAY; }
    public String nextName() throws IOException { String v = values.get(index); expect(JsonToken.NAME); return v; }

    public String nextString() throws IOException {
        JsonToken t = types.get(index);
        if (t != JsonToken.STRING && t != JsonToken.NUMBER) throw new IllegalStateException("expected string");
        return values.get(index++);
    }

    public boolean nextBoolean() throws IOException { String v = values.get(index); expect(JsonToken.BOOLEAN); return Boolean.parseBoolean(v); }
    public void nextNull() throws IOException { expect(JsonToken.NULL); }
    @Override public void close() {}
}
