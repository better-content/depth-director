package com.bettercontent.depthdirector;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Optional aquatic threats are supplied by datapacks, never required mod dependencies. */
public final class WaterwayRegistry extends SimpleJsonResourceReloadListener {
    public static final WaterwayRegistry INSTANCE = new WaterwayRegistry();
    private static final ResourceLocation OVERWORLD = new ResourceLocation(DepthDirectorMod.MOD_ID, "overworld");
    private volatile Definition overworld;

    private WaterwayRegistry() { super(new Gson(), "waterway_encounters"); }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> resources, ResourceManager manager, ProfilerFiller profiler) {
        Definition loaded = null;
        JsonElement json = resources.get(OVERWORLD);
        if (json != null) try {
            loaded = parse(json.getAsJsonObject());
        } catch (RuntimeException exception) {
            DepthDirectorMod.LOGGER.error("Ignoring invalid waterway encounter roster", exception);
        }
        overworld = loaded;
    }

    Definition overworld() { return overworld; }

    static Definition parse(JsonObject root) {
        int minimum = root.get("minimum_seconds").getAsInt();
        int maximum = root.get("maximum_seconds").getAsInt();
        if (minimum < 60 || maximum > 1800 || maximum < minimum) throw new IllegalArgumentException("Invalid waterway cadence");
        List<Entry> roster = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray("roster")) {
            JsonObject row = element.getAsJsonObject();
            Entry entry = new Entry(new ResourceLocation(row.get("entity").getAsString()),
                    row.get("weight").getAsInt(), row.get("count").getAsInt());
            if (entry.weight() < 1 || entry.weight() > 100 || entry.count() < 1 || entry.count() > 4)
                throw new IllegalArgumentException("Invalid waterway roster entry " + entry.entity());
            roster.add(entry);
        }
        if (roster.isEmpty()) throw new IllegalArgumentException("Empty waterway roster");
        return new Definition(minimum, maximum, List.copyOf(roster));
    }

    record Definition(int minimumSeconds, int maximumSeconds, List<Entry> roster) {}
    record Entry(ResourceLocation entity, int weight, int count) {}
}
