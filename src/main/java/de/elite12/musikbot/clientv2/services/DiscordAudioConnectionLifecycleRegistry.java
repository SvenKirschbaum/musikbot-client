package de.elite12.musikbot.clientv2.services;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

final class DiscordAudioConnectionLifecycleRegistry<M> {

    private final Map<Long, Binding> bindings = new HashMap<>();
    private final Function<M, DiscordAudioConnectionLifecycle> factory;
    private final Detacher<M> detacher;

    DiscordAudioConnectionLifecycleRegistry(
            Function<M, DiscordAudioConnectionLifecycle> factory,
            Detacher<M> detacher
    ) {
        this.factory = factory;
        this.detacher = detacher;
    }

    synchronized DiscordAudioConnectionLifecycle lifecycleFor(long guildId, M manager) {
        Binding binding = bindings.get(guildId);
        if (binding != null && binding.manager == manager) {
            return binding.lifecycle;
        }
        if (binding != null) {
            bindings.remove(guildId);
            detacher.detach(binding.manager, binding.lifecycle, false);
        }

        DiscordAudioConnectionLifecycle lifecycle = factory.apply(manager);
        bindings.put(guildId, new Binding(manager, lifecycle));
        return lifecycle;
    }

    synchronized void remove(long guildId) {
        Binding binding = bindings.remove(guildId);
        if (binding == null) {
            return;
        }
        detacher.detach(binding.manager, binding.lifecycle, true);
    }

    @FunctionalInterface
    interface Detacher<M> {
        void detach(M manager, DiscordAudioConnectionLifecycle lifecycle, boolean completeDisconnect);
    }

    private final class Binding {
        private final M manager;
        private final DiscordAudioConnectionLifecycle lifecycle;

        private Binding(M manager, DiscordAudioConnectionLifecycle lifecycle) {
            this.manager = manager;
            this.lifecycle = lifecycle;
        }
    }
}
