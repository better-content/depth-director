package com.bettercontent.depthdirector;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

public record EcologyDefinition(
        ResourceLocation id,
        double noiseScale,
        int cadenceMinimumSeconds,
        int cadenceMaximumSeconds,
        int warningMinimumSeconds,
        int warningMaximumSeconds,
        int surgeSeconds,
        int recoverySeconds,
        int deepBudgetPerPlayer,
        int deepActiveTargetPerPlayer,
        int packetIntervalTicks,
        boolean maximizeDirections,
        List<ResourceLocation> warningSounds,
        List<Entry> roster) {

    public EcologyDefinition {
        warningSounds = List.copyOf(warningSounds);
        roster = List.copyOf(roster);
    }

    public static EcologyDefinition parse(ResourceLocation id, JsonObject root) {
        JsonObject cadence = requiredObject(root, "cadence_seconds");
        JsonObject warning = requiredObject(root, "warning_seconds");
        List<ResourceLocation> sounds = resources(root.getAsJsonArray("warning_sounds"));
        List<Entry> entries = new ArrayList<>();
        for (JsonElement element : requiredArray(root, "roster")) {
            JsonObject entry = element.getAsJsonObject();
            entries.add(new Entry(
                    new ResourceLocation(entry.get("entity").getAsString()),
                    Role.valueOf(entry.get("role").getAsString().toUpperCase()),
                    entry.has("minimum_depth") ? entry.get("minimum_depth").getAsDouble() : 0.0,
                    entry.has("weight") ? entry.get("weight").getAsInt() : 0,
                    entry.has("cost") ? entry.get("cost").getAsInt() : 0,
                    entry.has("maximum_per_packet") ? entry.get("maximum_per_packet").getAsInt() : 0,
                    entry.has("maximum_per_encounter") ? entry.get("maximum_per_encounter").getAsInt() : 0));
        }
        if (entries.isEmpty()) throw new IllegalArgumentException("Ecology roster is empty: " + id);
        return new EcologyDefinition(
                id,
                number(root, "noise_scale", 768.0),
                integer(cadence, "minimum"), integer(cadence, "maximum"),
                integer(warning, "minimum"), integer(warning, "maximum"),
                integer(root, "surge_seconds"), integer(root, "recovery_seconds"),
                integer(root, "deep_budget_per_player"), integer(root, "deep_active_target_per_player"),
                integer(root, "packet_interval_ticks"),
                root.has("maximize_directions") && root.get("maximize_directions").getAsBoolean(),
                sounds, entries);
    }

    public Entry pick(RandomSource random, double depth) {
        return pick(random, depth, true, ignored -> true);
    }

    Entry pick(RandomSource random, double depth, boolean allowHeavy, Predicate<ResourceLocation> eligible) {
        return pick(random, depth, allowHeavy, eligible, ignored -> true);
    }

    Entry pick(RandomSource random, double depth, boolean allowHeavy, Predicate<ResourceLocation> eligible,
               Predicate<Entry> selectable) {
        List<Entry> available = roster.stream()
                .filter(entry -> entry.minimumDepth <= depth)
                .filter(entry -> allowHeavy || entry.role != Role.HEAVY)
                .filter(entry -> eligible.test(entry.entity))
                .filter(selectable)
                .toList();
        if (available.isEmpty()) return null;
        int total = available.stream().mapToInt(Entry::selectionWeight).sum();
        int roll = random.nextInt(Math.max(1, total));
        for (Entry entry : available) {
            roll -= entry.selectionWeight();
            if (roll < 0) return entry;
        }
        return available.get(available.size() - 1);
    }

    private static JsonObject requiredObject(JsonObject root, String name) {
        if (!root.has(name) || !root.get(name).isJsonObject()) throw new IllegalArgumentException("Missing object " + name);
        return root.getAsJsonObject(name);
    }

    private static JsonArray requiredArray(JsonObject root, String name) {
        if (!root.has(name) || !root.get(name).isJsonArray()) throw new IllegalArgumentException("Missing array " + name);
        return root.getAsJsonArray(name);
    }

    private static int integer(JsonObject root, String name) {
        if (!root.has(name)) throw new IllegalArgumentException("Missing integer " + name);
        return root.get(name).getAsInt();
    }

    private static double number(JsonObject root, String name, double fallback) {
        return root.has(name) ? root.get(name).getAsDouble() : fallback;
    }

    private static List<ResourceLocation> resources(JsonArray array) {
        if (array == null) return Collections.emptyList();
        List<ResourceLocation> result = new ArrayList<>();
        array.forEach(element -> result.add(new ResourceLocation(element.getAsString())));
        return result;
    }

    public record Entry(ResourceLocation entity, Role role, double minimumDepth, int explicitWeight,
                        int explicitCost, int maximumPerPacket, int maximumPerEncounter) {
        public Entry {
            if (minimumDepth < 0.0 || minimumDepth > 1.0) {
                throw new IllegalArgumentException("minimum_depth must be between 0 and 1 for " + entity);
            }
            if (explicitWeight < 0) throw new IllegalArgumentException("weight cannot be negative for " + entity);
            if (explicitCost < 0 || explicitCost > 64) {
                throw new IllegalArgumentException("cost must be between 0 and 64 for " + entity);
            }
            if (maximumPerPacket < 0 || maximumPerPacket > 160
                    || maximumPerEncounter < 0 || maximumPerEncounter > 160) {
                throw new IllegalArgumentException("roster limits must be between 0 and 160 for " + entity);
            }
        }

        public Entry(ResourceLocation entity, Role role, double minimumDepth, int explicitWeight) {
            this(entity, role, minimumDepth, explicitWeight, 0, 0, 0);
        }

        public int selectionWeight() { return explicitWeight > 0 ? explicitWeight : role.weight; }
        public int cost() { return explicitCost > 0 ? explicitCost : role.cost; }
        public boolean allowsPacketCount(int successfulSpawns) {
            return maximumPerPacket == 0 || successfulSpawns < maximumPerPacket;
        }
        public boolean allowsEncounterCount(int successfulSpawns, int players) {
            return maximumPerEncounter == 0
                    || successfulSpawns < maximumPerEncounter * Math.max(1, players);
        }
    }

    public enum Role {
        SWARM(1, 6), COMMON(1, 6), LINE(2, 3), HEAVY(6, 1);
        private final int cost;
        private final int weight;
        Role(int cost, int weight) { this.cost = cost; this.weight = weight; }
        public int cost() { return cost; }
        public int weight() { return weight; }
    }
}
