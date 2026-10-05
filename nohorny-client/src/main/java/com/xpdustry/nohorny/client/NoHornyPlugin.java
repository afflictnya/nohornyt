// SPDX-License-Identifier: MIT
package com.xpdustry.nohorny.client;

import arc.ApplicationListener;
import arc.Core;
import arc.util.CommandHandler;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import mindustry.Vars;
import mindustry.mod.Plugin;

public final class NoHornyPlugin extends Plugin {

    static final String MESSAGE_PREFIX = "[pink][[NoHorny]: [white]";
    static String VERSION = "unknown";
    static String USER_AGENT = "NoHorny (unknown, unknown)";

    private final MiniLogger log = MiniLogger.forClass(NoHornyPlugin.class);
    private final List<LifecycleListener> listeners = new ArrayList<>();
    private final NoHornyEventBus events = new NoHornyEventBus();

    private final PerceptualHashes phashes = new PerceptualHashes();

    @Override
    public void registerServerCommands(final CommandHandler handler) {
        handler.register("add-phash", "<comment-and-value...>", "Add a pHash: add-phash [comment] <value>", args -> {
            final var input = args[0].strip();
            final int separator = input.lastIndexOf(' ');
            final var value = input.substring(separator + 1);
            final var comment =
                    separator < 0 ? "" : input.substring(0, separator).strip();
            try {
                this.phashes.add(
                        Vars.mods.getConfigFolder(this).file().toPath().resolve("phashes.json"), value, comment);
                log.info("Added pHash={}", value);
            } catch (final IOException | IllegalArgumentException e) {
                log.error("Failed to add pHash: {}", e.getMessage());
            }
        });
    }

    @Override
    public void init() {
        final var metadata = Vars.mods.getMod(NoHornyPlugin.class).meta;
        VERSION = metadata.version;
        USER_AGENT = "NoHorny (https://github/" + metadata.repo + ", v" + metadata.version + ")";

        final var directory = Vars.mods.getConfigFolder(this).file().toPath();

        try {
            this.phashes.load(directory.resolve("phashes.json"));
        } catch (final Exception e) {
            throw new IllegalStateException("Failed to load pHash blacklist", e);
        }

        final var client = new NoHornyClient(this.events, this.phashes);
        this.addListener(client);

        final var displays = new DisplayTracker(this.events, client);
        this.addListener(displays);

        final var canvases = new CanvasTracker(this.events, client);
        this.addListener(canvases);

        final var sorters = PixelTracker.sorters(this.events, client);
        this.addListener(sorters);

        final var illuminators = PixelTracker.illuminators(this.events, client);
        this.addListener(illuminators);

        final var debug = new DebugHelper(
                this.events,
                directory.resolve("debug"),
                displays,
                List.of(canvases, sorters, illuminators),
                this.phashes);
        this.addListener(debug);

        this.addListener(new DiscordWebhook(this.events, this.phashes));

        this.addListener(new AutoModerator(this.events));

        this.init0();
        // Added after the game listeners, so the trackers tick after the game update
        Core.app.addListener(new ApplicationListener() {

            @Override
            public void update() {
                NoHornyPlugin.this.tick0();
            }

            @Override
            public void dispose() {
                NoHornyPlugin.this.exit0();
            }
        });
    }

    private void addListener(final LifecycleListener listener) {
        this.listeners.add(listener);
    }

    private void init0() {
        for (int i = 0; i < this.listeners.size(); i++) {
            try {
                this.listeners.get(i).onInit();
            } catch (final Exception e1) {
                for (; i >= 0; --i) {
                    try {
                        this.listeners.get(i).onExit();
                    } catch (final Exception e2) {
                        e1.addSuppressed(e2);
                    }
                }
                this.events.close();
                throw new RuntimeException("Failed to initialize NoHorny", e1);
            }
        }
        log.info("NoHorny successfully initialized");
    }

    private void tick0() {
        if (!Vars.state.isGame()) {
            return;
        }
        for (final var listener : this.listeners) {
            try {
                listener.onTick();
            } catch (final Throwable e) {
                log.error("NoHorny failed to tick {}", listener.getClass().getSimpleName(), e);
            }
        }
    }

    private void exit0() {
        for (final var listener : this.listeners.reversed()) {
            try {
                listener.onExit();
            } catch (final Throwable e) {
                log.error(
                        "NoHorny failed to exit {} gracefully",
                        listener.getClass().getSimpleName(),
                        e);
            }
        }
        this.events.close();
    }
}
