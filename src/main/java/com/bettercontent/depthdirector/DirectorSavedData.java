package com.bettercontent.depthdirector;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class DirectorSavedData extends SavedData {
    private static final String NAME = "depth_director_tracks";
    private final Map<UUID, Track> tracks = new HashMap<>();

    public static DirectorSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(DirectorSavedData::load, DirectorSavedData::new, NAME);
    }

    public Track track(UUID player) {
        Track track = tracks.computeIfAbsent(player, ignored -> new Track());
        setDirty();
        return track;
    }

    public void reset(UUID player) {
        tracks.remove(player);
        setDirty();
    }

    public static DirectorSavedData load(CompoundTag root) {
        DirectorSavedData data = new DirectorSavedData();
        ListTag list = root.getList("Tracks", Tag.TAG_COMPOUND);
        for (int index = 0; index < list.size(); index++) {
            CompoundTag entry = list.getCompound(index);
            if (!entry.hasUUID("Player")) continue;
            Track track = new Track();
            track.pressure = DepthMath.clamp(entry.getDouble("Pressure"), 0.0, 1.0);
            track.recoveryUntil = entry.getLong("RecoveryUntil");
            track.probeFailures = entry.getInt("ProbeFailures");
            track.jitter = entry.contains("Jitter") ? DepthMath.clamp(entry.getDouble("Jitter"), 0.0, 1.0) : 0.5;
            data.tracks.put(entry.getUUID("Player"), track);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag root) {
        ListTag list = new ListTag();
        tracks.forEach((player, track) -> {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("Player", player);
            entry.putDouble("Pressure", track.pressure);
            entry.putLong("RecoveryUntil", track.recoveryUntil);
            entry.putInt("ProbeFailures", track.probeFailures);
            entry.putDouble("Jitter", track.jitter);
            list.add(entry);
        });
        root.put("Tracks", list);
        return root;
    }

    public static final class Track {
        private double pressure;
        private long recoveryUntil;
        private int probeFailures;
        private double jitter = 0.5;

        public double pressure() { return pressure; }
        public long recoveryUntil() { return recoveryUntil; }
        public int probeFailures() { return probeFailures; }
        public double jitter() { return jitter; }
        public void pressure(double value) { pressure = DepthMath.clamp(value, 0.0, 1.0); }
        public void recoveryUntil(long value) { recoveryUntil = value; }
        public void probeFailures(int value) { probeFailures = Math.max(0, value); }
        public void rerollJitter(net.minecraft.util.RandomSource random) { jitter = random.nextDouble(); }
    }
}
