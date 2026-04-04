package at.andreasrohner.spartantimelapserec.rest;

import java.text.SimpleDateFormat;
import java.util.Locale;

public class LogBuffer {
    private static final int MAX_ENTRIES = 500;
    private static final LogEntry[] buffer = new LogEntry[MAX_ENTRIES];
    private static int head = 0;
    private static int count = 0;
    private static final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss.SSS", Locale.ENGLISH);

    private static class LogEntry {
        String timestamp;
        String level;
        String tag;
        String message;

        LogEntry(String level, String tag, String message) {
            this.timestamp = timeFormat.format(System.currentTimeMillis());
            this.level = level;
            this.tag = tag;
            this.message = message;
        }
    }

    public static void init() {
        head = 0;
        count = 0;
    }

    public static void add(String level, String tag, String message) {
        buffer[head] = new LogEntry(level, tag, message);
        head = (head + 1) % MAX_ENTRIES;
        if (count < MAX_ENTRIES) {
            count++;
        }
    }

    public static String getAll() {
        if (count == 0) {
            return "No log entries";
        }

        StringBuilder sb = new StringBuilder();
        int start = (count < MAX_ENTRIES) ? 0 : head;
        
        for (int i = 0; i < count; i++) {
            int index = (start + i) % MAX_ENTRIES;
            LogEntry entry = buffer[index];
            if (entry != null) {
                sb.append(entry.timestamp)
                  .append(" ")
                  .append(entry.level)
                  .append("/")
                  .append(entry.tag)
                  .append(": ")
                  .append(entry.message)
                  .append("\n");
            }
        }
        return sb.toString();
    }

    public static void clear() {
        head = 0;
        count = 0;
    }
}
