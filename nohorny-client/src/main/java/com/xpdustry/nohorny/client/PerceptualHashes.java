// SPDX-License-Identifier: MIT
package com.xpdustry.nohorny.client;

import arc.Core;
import arc.util.serialization.Jval;
import com.xpdustry.nohorny.common.ClassificationResponse;
import com.xpdustry.nohorny.common.MindustryAuthor;
import com.xpdustry.nohorny.common.MindustryImage;
import com.xpdustry.nohorny.common.VirtualBuilding;
import dev.brachtendorf.graphics.FastPixel;
import dev.brachtendorf.jimagehash.hashAlgorithms.PerceptiveHash;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import mindustry.Vars;
import mindustry.gen.Groups;
import org.jspecify.annotations.Nullable;

final class PerceptualHashes {
    private static final MiniLogger log = MiniLogger.forClass(PerceptualHashes.class);
    private static final String PREFIX = "phash-v1:";
    private final PerceptiveHash algorithm = new HeadlessPerceptiveHash();
    private final Map<Long, String> blacklist = new LinkedHashMap<>();
    private final Map<Long, Cached> cache = new LinkedHashMap<>();

    synchronized String hash(final BufferedImage image) {
        final var hash = this.algorithm.hash(image);
        if (hash.getBitResolution() != 64) {
            throw new IllegalStateException("Unexpected pHash resolution");
        }
        return format(hash.getHashValue().longValue());
    }

    synchronized void load(final Path file) throws IOException {
        if (!Files.exists(file)) {
            return;
        }
        final var entries = Jval.read(Files.readString(file)).asArray();
        for (final var entry : entries) {
            this.blacklist.put(parse(entry.getString("hash")), entry.getString("comment", ""));
        }
    }

    synchronized void add(final Path file, final String value, final String comment) throws IOException {
        final long hash = parse(value);
        final var updated = new LinkedHashMap<>(this.blacklist);
        updated.put(hash, comment);
        final var json = Jval.newArray();
        updated.forEach((key, text) ->
                json.add(Jval.newObject().put("hash", format(key)).put("comment", text)));
        Files.createDirectories(file.getParent());
        final var temporary = Files.createTempFile(file.getParent(), "phashes-", ".tmp");
        try {
            Files.writeString(temporary, json.toString());
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            this.blacklist.clear();
            this.blacklist.putAll(updated);
            this.cache.clear();
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    synchronized boolean blocked(final String value) {
        final long hash = parse(value);
        final int threshold = setting(NoHornySetting.PHASH_THRESHOLD, 90);
        for (final var entry : this.blacklist.entrySet()) {
            if (similarity(hash, entry.getKey()) >= threshold) {
                log.info("pHash={} threshold={} comment={}", value, threshold, entry.getValue());
                return true;
            }
        }
        return false;
    }

    boolean reject(
            final String hash,
            final VirtualBuilding.Group<? extends MindustryImage> group,
            final @Nullable MindustryAuthor author) {
        if (!this.blocked(hash)) {
            return false;
        }
        Core.app.post(() -> this.remove(group, author));
        return true;
    }

    void remove(final VirtualBuilding.Group<? extends MindustryImage> group, final @Nullable MindustryAuthor author) {
        AutoModerator.delete(group);
        if (author != null && Boolean.TRUE.equals(NoHornySetting.PHASH_DOS_BLACKLIST.get())) {
            Vars.netServer.admins.blacklistDos(author.ip());
            for (final var player : Groups.player) {
                if (player.ip().equals(author.ip())) {
                    player.kick("Blocked image.");
                }
            }
        }
    }

    synchronized @Nullable ClassificationResponse cached(final String value) {
        final long now = System.nanoTime();
        final long ttl = setting(NoHornySetting.PHASH_CACHE_TTL, 3600) * 1_000_000_000L;
        this.cache.values().removeIf(entry -> ttl == 0 || now - entry.created() >= ttl);
        final long hash = parse(value);
        final int threshold = setting(NoHornySetting.PHASH_CACHE_THRESHOLD, 98);
        Cached best = null;
        double closest = -1;
        for (final var entry : this.cache.entrySet()) {
            final double similarity = similarity(hash, entry.getKey());
            if (similarity >= threshold && similarity > closest) {
                closest = similarity;
                best = entry.getValue();
            }
        }
        return best == null ? null : best.response();
    }

    synchronized void remember(final String value, final ClassificationResponse response) {
        if (setting(NoHornySetting.PHASH_CACHE_TTL, 3600) == 0) {
            this.cache.clear();
            return;
        }
        final long hash = parse(value);
        this.cache.remove(hash);
        this.cache.put(hash, new Cached(response, System.nanoTime()));
        while (this.cache.size() > 4096) {
            this.cache.remove(this.cache.keySet().iterator().next());
        }
    }

    static long parse(final String value) {
        if (!value.matches("phash-v1:[0-9a-fA-F]{16}")) {
            throw new IllegalArgumentException("Expected phash-v1: followed by 16 hexadecimal digits");
        }
        return new BigInteger(value.substring(PREFIX.length()), 16).longValue();
    }

    private static String format(final long value) {
        return PREFIX + "%016x".formatted(value);
    }

    private static double similarity(final long a, final long b) {
        return 100.0 * (64 - Long.bitCount(a ^ b)) / 64;
    }

    private static int setting(final NoHornySetting<Integer> setting, final int fallback) {
        final var value = setting.get();
        return value == null ? fallback : value;
    }

    // JImageHash's default scaler references JavaFX. Keep scaling in AWT on headless servers.
    private static final class HeadlessPerceptiveHash extends PerceptiveHash {
        private static final long serialVersionUID = 1L;

        HeadlessPerceptiveHash() {
            super(64);
        }

        @Override
        protected FastPixel createPixelAccessor(final BufferedImage image, final int width, final int height) {
            final var scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            final var graphics = scaled.createGraphics();
            try {
                graphics.drawImage(image, 0, 0, width, height, null);
            } finally {
                graphics.dispose();
            }
            return FastPixel.create(scaled);
        }
    }

    private record Cached(ClassificationResponse response, long created) {}
}
