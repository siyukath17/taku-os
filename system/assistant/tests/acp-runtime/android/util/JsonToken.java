package android.util;

/** Host-test stand-in for Android's JsonToken (SystemUI's IslandPayload parser runs on the JVM). */
public enum JsonToken { BEGIN_ARRAY, END_ARRAY, BEGIN_OBJECT, END_OBJECT, NAME, STRING, NUMBER, BOOLEAN, NULL, END_DOCUMENT }
