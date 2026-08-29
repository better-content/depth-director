package com.bettercontent.depthdirector;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class EcologyRegistry extends SimpleJsonResourceReloadListener {
    public static final EcologyRegistry INSTANCE = new EcologyRegistry();
    private volatile Map<ResourceLocation, EcologyDefinition> definitions = Map.of();

    private EcologyRegistry() { super(new Gson(), "director_ecologies"); }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> resources, ResourceManager manager, ProfilerFiller profiler) {
        Map<ResourceLocation, EcologyDefinition> loaded = new LinkedHashMap<>();
        resources.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            try {
                loaded.put(entry.getKey(), EcologyDefinition.parse(entry.getKey(), entry.getValue().getAsJsonObject()));
            } catch (RuntimeException exception) {
                DepthDirectorMod.LOGGER.error("Ignoring invalid Director ecology {}", entry.getKey(), exception);
            }
        });
        definitions = Map.copyOf(loaded);
        DepthDirectorMod.LOGGER.info("Loaded {} Director ecologies", loaded.size());
    }

    public Map<ResourceLocation, EcologyDefinition> definitions() { return definitions; }

    public Blend blend(long seed, Vec3 position) {
        List<Scored> scored = new ArrayList<>();
        definitions.values().forEach(definition -> scored.add(new Scored(definition,
                EcologyNoise.sample(seed, definition.id(), position.x, position.z, definition.noiseScale()))));
        scored.sort(Comparator.comparingDouble(Scored::score).reversed());
        if (scored.isEmpty()) return null;
        if (scored.size() == 1) return new Blend(scored.get(0).definition, null, 0.0);
        Scored first = scored.get(0);
        Scored second = scored.get(1);
        double difference = first.score - second.score;
        double secondaryWeight = difference >= 0.18 ? 0.0 : (0.18 - difference) / 0.36;
        return new Blend(first.definition, secondaryWeight > 0.0 ? second.definition : null, secondaryWeight);
    }

    private record Scored(EcologyDefinition definition, double score) {}

    public record Blend(EcologyDefinition primary, EcologyDefinition secondary, double secondaryWeight) {
        public double mix(double primaryValue, double secondaryValue) {
            return secondary == null ? primaryValue : DepthMath.lerp(primaryValue, secondaryValue, secondaryWeight);
        }

        public EcologyDefinition choose(net.minecraft.util.RandomSource random) {
            return secondary != null && random.nextDouble() < secondaryWeight ? secondary : primary;
        }

        public String label() {
            return secondary == null ? primary.id().toString()
                    : primary.id() + "+" + secondary.id() + "@" + String.format(java.util.Locale.ROOT, "%.2f", secondaryWeight);
        }
    }
}
