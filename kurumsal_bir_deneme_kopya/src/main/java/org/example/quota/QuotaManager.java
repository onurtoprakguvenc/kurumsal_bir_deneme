package org.example.quota;

import org.example.core.QuotaGate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;

/**
 * Thread-safe ceiling for outbound AI calls (the "Jevons paradox" shield).
 *
 * <p>Three independent checks, cheapest first:</p>
 * <ol>
 *   <li><b>Business hours</b> – optional weekday/time window; outside it no call is made at all.</li>
 *   <li><b>Burst throttle</b> – at most {@code perMinuteLimit} calls in any 60-second period.</li>
 *   <li><b>Daily ceiling</b> – at most {@code dailyLimit} calls inside a sliding {@code window} (24 h by default).
 *       Because the window slides, the budget refills itself: a call falls out exactly {@code window} after it was
 *       made, which is the automatic daily reset.</li>
 * </ol>
 *
 * <p>Only the timestamps of the calls inside the window are kept, so memory is O(dailyLimit). Token counters are
 * atomic. Local ingestion, indexing and search never call this class.</p>
 */
public final class QuotaManager implements QuotaGate {

    public static final int DEFAULT_DAILY_QUOTA = 200;
    public static final int DEFAULT_PER_MINUTE = 10;

    /**
     * @param dailyLimit     calls allowed inside {@code window}
     * @param perMinuteLimit calls allowed in any 60-second period
     * @param window         sliding window for the daily ceiling
     * @param businessStart  first minute calls are allowed (inclusive), or {@code null} for 24/7
     * @param businessEnd    first minute calls are refused again (exclusive), or {@code null} for 24/7
     * @param businessDays   weekdays on which calls are allowed
     * @param zone           time zone the business window is expressed in
     */
    public record Config(int dailyLimit, int perMinuteLimit, Duration window, LocalTime businessStart,
                         LocalTime businessEnd, Set<DayOfWeek> businessDays, ZoneId zone) {

        public Config {
            if (dailyLimit < 1 || perMinuteLimit < 1) {
                throw new IllegalArgumentException("Quota limits must be positive");
            }
            if (window == null || window.isZero() || window.isNegative()) {
                throw new IllegalArgumentException("Quota window must be positive");
            }
            businessDays = Set.copyOf(businessDays);
            if (businessDays.isEmpty()) {
                throw new IllegalArgumentException("At least one business day is required");
            }
            Objects.requireNonNull(zone, "zone must not be null");
        }

        public static Config defaults() {
            return new Config(DEFAULT_DAILY_QUOTA, DEFAULT_PER_MINUTE, Duration.ofDays(1), null, null,
                    EnumSet.allOf(DayOfWeek.class), ZoneId.systemDefault());
        }

        /**
         * Reads {@code DWB_AI_QUOTA} (calls per window), {@code DWB_AI_RATE} (calls per minute),
         * {@code DWB_AI_HOURS} (e.g. {@code 09:00-18:00}) and {@code DWB_AI_DAYS} ({@code MON-FRI} or
         * {@code MON,TUE,WED}). Invalid values fall back to the defaults.
         */
        public static Config fromEnvironment(UnaryOperator<String> environment) {
            Config defaults = defaults();
            int daily = positiveInt(environment.apply("DWB_AI_QUOTA"), defaults.dailyLimit());
            int perMinute = positiveInt(environment.apply("DWB_AI_RATE"), defaults.perMinuteLimit());
            LocalTime start = null;
            LocalTime end = null;
            String hours = environment.apply("DWB_AI_HOURS");
            if (hours != null && hours.contains("-")) {
                String[] parts = hours.split("-", 2);
                start = parseTime(parts[0]);
                end = parseTime(parts[1]);
                if (start == null || end == null || start.equals(end)) {
                    start = null;
                    end = null;
                }
            }
            return new Config(daily, perMinute, defaults.window(), start, end,
                    parseDays(environment.apply("DWB_AI_DAYS")), ZoneId.systemDefault());
        }

        private static int positiveInt(String value, int fallback) {
            if (value == null || value.isBlank()) {
                return fallback;
            }
            try {
                int parsed = Integer.parseInt(value.strip());
                return parsed > 0 ? parsed : fallback;
            } catch (NumberFormatException e) {
                return fallback;
            }
        }

        private static LocalTime parseTime(String value) {
            try {
                return LocalTime.parse(value.strip());
            } catch (RuntimeException e) {
                return null;
            }
        }

        private static Set<DayOfWeek> parseDays(String value) {
            if (value == null || value.isBlank()) {
                return EnumSet.allOf(DayOfWeek.class);
            }
            String v = value.strip().toUpperCase(Locale.ROOT);
            EnumSet<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
            try {
                if (v.contains("-")) {
                    String[] range = v.split("-", 2);
                    DayOfWeek from = day(range[0]);
                    DayOfWeek to = day(range[1]);
                    for (DayOfWeek d : DayOfWeek.values()) {
                        boolean inRange = from.getValue() <= to.getValue()
                                ? d.getValue() >= from.getValue() && d.getValue() <= to.getValue()
                                : d.getValue() >= from.getValue() || d.getValue() <= to.getValue();
                        if (inRange) {
                            days.add(d);
                        }
                    }
                } else {
                    for (String token : v.split(",")) {
                        days.add(day(token));
                    }
                }
            } catch (RuntimeException e) {
                return EnumSet.allOf(DayOfWeek.class);
            }
            return days.isEmpty() ? EnumSet.allOf(DayOfWeek.class) : days;
        }

        private static DayOfWeek day(String token) {
            String t = token.strip();
            for (DayOfWeek d : DayOfWeek.values()) {
                if (!t.isEmpty() && d.name().startsWith(t)) {
                    return d;
                }
            }
            throw new IllegalArgumentException("Unknown day " + token);
        }

        boolean alwaysOpen() {
            return businessStart == null || businessEnd == null;
        }

        /** One-line description for {@code status}. */
        public String describe() {
            String hours = alwaysOpen() ? "24/7" : businessStart + "-" + businessEnd;
            String days = businessDays.size() == 7 ? "all days"
                    : businessDays.stream().sorted().map(d -> d.name().substring(0, 3))
                    .reduce((a, b) -> a + "," + b).orElse("");
            return dailyLimit + " calls / " + window.toHours() + " h · " + perMinuteLimit + " per minute · "
                    + hours + " " + days + " (" + zone.getId() + ")";
        }
    }

    private final Config config;
    private final Clock clock;
    private final Deque<Long> calls = new ArrayDeque<>();
    private final Path stateFile;
    private final AtomicLong promptTokens = new AtomicLong();
    private final AtomicLong outputTokens = new AtomicLong();
    private final AtomicLong grantedCalls = new AtomicLong();

    public QuotaManager(Config config) {
        this(config, Clock.systemDefaultZone());
    }

    public QuotaManager(Config config, Clock clock) {
        this(config, clock, null);
    }

    /**
     * @param stateFile where granted calls and token totals are kept across restarts, so closing and reopening the
     *                  application does not reset the daily budget; {@code null} keeps everything in memory
     */
    public QuotaManager(Config config, Clock clock, Path stateFile) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.stateFile = stateFile == null ? null : stateFile.toAbsolutePath().normalize();
        restore();
    }

    /** Persistent variant with the system clock. */
    public QuotaManager(Config config, Path stateFile) {
        this(config, Clock.systemDefaultZone(), stateFile);
    }

    private void restore() {
        if (stateFile == null || !Files.isRegularFile(stateFile)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(stateFile, StandardCharsets.UTF_8)) {
                String[] parts = line.strip().split("\\s+");
                if (parts.length != 2) {
                    continue;
                }
                long value = Long.parseLong(parts[1]);
                switch (parts[0]) {
                    case "call" -> calls.addLast(value);
                    case "prompt" -> promptTokens.set(Math.max(0, value));
                    case "output" -> outputTokens.set(Math.max(0, value));
                    case "granted" -> grantedCalls.set(Math.max(0, value));
                    default -> {
                        // unknown key from a newer version
                    }
                }
            }
            List<Long> sorted = new ArrayList<>(calls);
            Collections.sort(sorted);
            calls.clear();
            calls.addAll(sorted);
            prune(clock.instant());
        } catch (IOException | RuntimeException e) {
            System.getLogger(QuotaManager.class.getName()).log(System.Logger.Level.WARNING,
                    "AI quota state " + stateFile + " unreadable; starting with an empty window: " + e.getMessage());
            calls.clear();
        }
    }

    /** Writes the window atomically; caller holds the monitor. Failures only cost persistence, never the call. */
    private void persist() {
        if (stateFile == null) {
            return;
        }
        StringBuilder sb = new StringBuilder(32 + calls.size() * 20);
        sb.append("granted ").append(grantedCalls.get()).append('\n')
                .append("prompt ").append(promptTokens.get()).append('\n')
                .append("output ").append(outputTokens.get()).append('\n');
        for (Long call : calls) {
            sb.append("call ").append(call).append('\n');
        }
        try {
            Files.createDirectories(stateFile.getParent());
            Path temp = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
            Files.writeString(temp, sb, StandardCharsets.UTF_8);
            try {
                Files.move(temp, stateFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, stateFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            System.getLogger(QuotaManager.class.getName()).log(System.Logger.Level.WARNING,
                    "AI quota state not saved: " + e.getMessage());
        }
    }

    public Config config() {
        return config;
    }

    @Override
    public synchronized Lease acquire() {
        Instant now = clock.instant();
        prune(now);
        if (!withinBusinessHours(now)) {
            return Lease.denied(Denial.OUTSIDE_BUSINESS_HOURS, nextOpening(now), remaining());
        }
        long minuteAgo = now.toEpochMilli() - 60_000;
        int lastMinute = 0;
        long oldestInMinute = now.toEpochMilli();
        for (Long timestamp : calls) {
            if (timestamp > minuteAgo) {
                lastMinute++;
                oldestInMinute = Math.min(oldestInMinute, timestamp);
            }
        }
        if (lastMinute >= config.perMinuteLimit()) {
            return Lease.denied(Denial.RATE_LIMITED, Instant.ofEpochMilli(oldestInMinute + 60_000), remaining());
        }
        if (calls.size() >= config.dailyLimit()) {
            return Lease.denied(Denial.DAILY_QUOTA_EXHAUSTED,
                    Instant.ofEpochMilli(calls.peekFirst()).plus(config.window()), 0);
        }
        calls.addLast(now.toEpochMilli());
        grantedCalls.incrementAndGet();
        persist();
        return Lease.granted(remaining());
    }

    @Override
    public synchronized void recordUsage(long prompt, long output) {
        if (prompt > 0) {
            promptTokens.addAndGet(prompt);
        }
        if (output > 0) {
            outputTokens.addAndGet(output);
        }
        persist();
    }

    @Override
    public synchronized Snapshot snapshot() {
        Instant now = clock.instant();
        prune(now);
        Instant resetAt = calls.isEmpty() ? null : Instant.ofEpochMilli(calls.peekFirst()).plus(config.window());
        return new Snapshot(calls.size(), config.dailyLimit(), remaining(), promptTokens.get(), outputTokens.get(),
                (int) Math.min(Integer.MAX_VALUE, grantedCalls.get()), resetAt, withinBusinessHours(now),
                config.describe());
    }

    /** Forgets the recorded calls; token totals are kept. */
    public synchronized void reset() {
        calls.clear();
        persist();
    }

    private int remaining() {
        return Math.max(0, config.dailyLimit() - calls.size());
    }

    private void prune(Instant now) {
        long cutoff = now.minus(config.window()).toEpochMilli();
        while (!calls.isEmpty() && calls.peekFirst() <= cutoff) {
            calls.removeFirst();
        }
    }

    private boolean withinBusinessHours(Instant now) {
        ZonedDateTime local = now.atZone(config.zone());
        if (!config.businessDays().contains(local.getDayOfWeek())) {
            return false;
        }
        if (config.alwaysOpen()) {
            return true;
        }
        LocalTime time = local.toLocalTime();
        LocalTime start = config.businessStart();
        LocalTime end = config.businessEnd();
        return start.isBefore(end)
                ? !time.isBefore(start) && time.isBefore(end)
                : !time.isBefore(start) || time.isBefore(end);
    }

    private Instant nextOpening(Instant now) {
        ZonedDateTime local = now.atZone(config.zone());
        for (int i = 0; i < 8; i++) {
            ZonedDateTime day = local.plusDays(i);
            if (!config.businessDays().contains(day.getDayOfWeek())) {
                continue;
            }
            ZonedDateTime opening = config.alwaysOpen()
                    ? day.toLocalDate().atStartOfDay(config.zone())
                    : day.with(config.businessStart());
            if (!opening.toInstant().isBefore(now)) {
                return opening.toInstant();
            }
        }
        return now.plus(Duration.ofDays(1));
    }
}
