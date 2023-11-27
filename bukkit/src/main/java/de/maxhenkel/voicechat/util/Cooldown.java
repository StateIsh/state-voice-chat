package de.maxhenkel.voicechat.util;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class Cooldown<K> {
    private final Map<K, Long> lastRun = new ConcurrentHashMap<>();
    private final long cooldownMillis;

    public Cooldown(long cooldownMillis) {
        this.cooldownMillis = cooldownMillis;
    }

    public void run(K key, Runnable runnable) {
        lastRun.compute(key, (k, v) -> {
            if (v == null || System.currentTimeMillis() - v >= cooldownMillis) {
                runnable.run();
                return System.currentTimeMillis();
            } else {
                return v;
            }
        });
    }

}
