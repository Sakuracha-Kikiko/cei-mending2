/*
 * Mending Forger Fix
 * SPDX-License-Identifier: MIT
 *
 * Create: Enchantment Industry hard-caps a few enchantments at level 1 for its Blaze
 * Forger / Blaze Enchanter through the `enchantment_processing/rules` registry data map.
 * CEIDataMaps#generate registers `enchantmentAndForgerExtension(0, 0)` for
 * `minecraft:mending` and `minecraft:infinity`.
 *
 * Because that is a *per-enchantment* rule, it also shadows the config options
 * `blazeForgerMaxLevelExtension` / `blazeEnchanterMaxLevelExtension`: those are only read
 * "when no per-enchantment processing rule exists". A modpack therefore cannot re-enable
 * Mending II by editing the config, and overriding it with a datapack depends on
 * NeoForge's cross-pack data map merge order, which is not reliable in practice.
 *
 * NeoForge loads data maps into a plain mutable map on the registry
 * (DataMapLoader#apply -> BaseMappedRegistry#dataMaps, an IdentityHashMap) and then fires
 * DataMapsUpdatedEvent. This mod listens for that event and replaces the rule in place,
 * which is deterministic and needs no datapack.
 */

package com.mendingfix;

import com.mojang.logging.LogUtils;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.enchantment.Enchantment;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.registries.datamaps.DataMapType;
import net.neoforged.neoforge.registries.datamaps.DataMapsUpdatedEvent;
import org.slf4j.Logger;

@Mod(MendingForgerFix.MOD_ID)
public class MendingForgerFix {
    public static final String MOD_ID = "mending_forger_fix";
    private static final Logger LOGGER = LogUtils.getLogger();

    /** Lower-cased: the loader normalises mod ids. */
    private static final ResourceLocation RULES_ID =
            ResourceLocation.fromNamespaceAndPath("create_enchantment_industry", "enchantment_processing/rules");

    private static final String CEI_RULE_CLASS =
            "plus.dragons.createenchantmentindustry.common.processing.EnchantmentProcessingRule";
    private static final String CEI_LEVEL_RULE_CLASS = CEI_RULE_CLASS + "$LevelExtensionRule";
    private static final String CEI_COST_RULE_CLASS = CEI_RULE_CLASS + "$CostMultiplierRule";

    /** Internal field on NeoForge's BaseMappedRegistry. */
    private static final String DATA_MAPS_FIELD = "dataMaps";

    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    /**
     * Mirrors the config default. Used for the very first application during mod
     * construction, when {@code ConfigValue#get()} is not safe to call yet.
     */
    private static final Map<String, int[]> DEFAULT_RULES = Map.of("minecraft:mending", new int[] {1, 0});

    private static volatile Map<String, int[]> pendingRules = Map.of();
    private static volatile boolean warnedMissingRuleType;

    /** False until the first DataMapsUpdatedEvent, i.e. until the loader has run once. */
    private static volatile boolean dataMapsReady;

    /**
     * Kept from the last DataMapsUpdatedEvent so later config reloads (which carry no
     * registry access of their own) can still reach the registries.
     */
    private static volatile Object currentRegistryAccess;

    public MendingForgerFix(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, MendingFixConfig.SPEC);
        modBus.addListener(MendingForgerFix::onConfigLoad);

        NeoForge.EVENT_BUS.addListener(MendingForgerFix::onDataMapsUpdated);

        // Seed from the built-in default rather than calling ConfigValue#get() here: at mod
        // construction the config has not been loaded yet and reading it throws. The real
        // values arrive with ModConfigEvent.Loading, and ModConfigEvent.Reloading covers
        // later edits.
        pendingRules = DEFAULT_RULES;
        applyRules();
    }

    private static void onConfigLoad(ModConfigEvent event) {
        if (event.getConfig().getSpec() == MendingFixConfig.SPEC) {
            applyRules();
        }
    }

    private static void onDataMapsUpdated(DataMapsUpdatedEvent event) {
        // NeoForge rebuilds the data maps from scratch on every (re)load, so the change has
        // to be re-applied each time or it is silently reverted.
        dataMapsReady = true;
        currentRegistryAccess = event.getRegistries();
        applyRules(currentRegistryAccess);
    }

    // ---------------------------------------------------------------------------------
    // Rule application
    // ---------------------------------------------------------------------------------

    private static void applyRules() {
        applyRules(null);
    }

    private static void applyRules(Object registryAccess) {
        try {
            Map<String, int[]> rules = pendingRules;
            if (rules.isEmpty()) return;

            Registry<Enchantment> registry = enchantmentRegistry(registryAccess);
            if (registry == null) return;

            DataMapType<Enchantment, ?> ruleType = findRulesDataMapType(registry);
            if (ruleType == null) {
                // Before the first data map load the enchantment table is simply empty, which
                // is normal rather than a problem, so it is only reported once the loader has
                // actually populated the maps.
                if (dataMapsReady && !warnedMissingRuleType) {
                    warnedMissingRuleType = true;
                    LOGGER.warn("[{}] Data map '{}' not found; nothing changed. "
                            + "The Blaze Forger will keep CEI's vanilla level caps.", MOD_ID, RULES_ID);
                    logAvailableDataMaps(registry);
                }
                return;
            }
            warnedMissingRuleType = false;

            Map<Object, Object> inner = innerDataMap(registry, ruleType);
            if (inner == null) {
                LOGGER.warn("[{}] Data map '{}' has no mutable table; nothing changed", MOD_ID, RULES_ID);
                return;
            }

            for (var entry : rules.entrySet()) {
                applyTo(registry, ruleType, inner, entry.getKey(), entry.getValue());
            }
        } catch (Throwable t) {
            LOGGER.error("[{}] Failed to apply enchantment processing rules", MOD_ID, t);
        }
    }

    private static Map<Object, Object> innerDataMap(Registry<Enchantment> registry, DataMapType<Enchantment, ?> ruleType) {
        try {
            Map<Object, Object> table = dataMapsTable(registry);
            if (table == null) return null;
            Object inner = table.get(ruleType);
            @SuppressWarnings("unchecked")
            Map<Object, Object> cast = inner instanceof Map<?, ?> map ? (Map<Object, Object>) map : null;
            return cast;
        } catch (Throwable t) {
            LOGGER.error("[{}] Could not reach the mutable data map table", MOD_ID, t);
            return null;
        }
    }

    private static void applyTo(Registry<Enchantment> registry, DataMapType<Enchantment, ?> ruleType,
                                Map<Object, Object> inner, String enchantmentId, int[] extension) {
        ResourceLocation id = ResourceLocation.tryParse(enchantmentId);
        if (id == null) {
            LOGGER.warn("[{}] Skipping malformed enchantment id '{}'", MOD_ID, enchantmentId);
            return;
        }
        ResourceKey<Enchantment> key = ResourceKey.create(Registries.ENCHANTMENT, id);

        // The raw-typed registry makes get() return the element itself (null when absent),
        // which keeps this independent of the Optional-vs-raw signature differences.
        if (registry.get(key) == null) {
            LOGGER.warn("[{}] Unknown enchantment '{}', skipped", MOD_ID, enchantmentId);
            return;
        }

        RuleAdapter rule = RuleAdapter.of(registry.getData(ruleType, key));

        boolean changed = false;
        if (extension.length > 0 && extension[0] >= 0) {
            rule = rule.withForger(extension[0]);
            changed = true;
        }
        if (extension.length > 1 && extension[1] >= 0) {
            rule = rule.withEnchanter(extension[1]);
            changed = true;
        }
        if (!changed) return;

        Object replacement = rule.build();
        if (replacement == null) return;

        inner.put(key, replacement);

        if (REPORTED.add(enchantmentId)) {
            LOGGER.info("[{}] {} -> blaze_forger={}, blaze_enchanter={} (Blaze Forger may now exceed the vanilla cap)",
                    MOD_ID, enchantmentId, rule.forger(), rule.enchanter());
        }
    }

    // ---------------------------------------------------------------------------------
    // Registry / data map discovery
    // ---------------------------------------------------------------------------------

    /**
     * {@code Registry#getDataMap} is a NeoForge addition living on BaseMappedRegistry, not on
     * the {@code Registry} interface, so it is reached reflectively.
     *
     * <p>{@code getDeclaredField} only sees fields declared on the exact class, so the whole
     * superclass chain has to be walked: the loader writes into the map declared on
     * BaseMappedRegistry, while the instance handed to us is a MappedRegistry.
     */
    private static java.lang.reflect.Field dataMapsField(Class<?> type) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                var field = c.getDeclaredField(DATA_MAPS_FIELD);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                // keep walking up
            }
        }
        return null;
    }

    /** The mutable {@code Map<DataMapType<?,?>, Map<ResourceKey<?>, ?>>} backing one registry. */
    @SuppressWarnings("unchecked")
    private static Map<Object, Object> dataMapsTable(Object registry) throws Exception {
        var field = dataMapsField(registry.getClass());
        if (field == null) {
            LOGGER.warn("[{}] Field '{}' not found on {} or its superclasses",
                    MOD_ID, DATA_MAPS_FIELD, registry.getClass().getName());
            return null;
        }
        Object table = field.get(registry);
        return table instanceof Map<?, ?> map ? (Map<Object, Object>) map : null;
    }

    /**
     * Locates CEI's ENCHANTMENT_PROCESSING_RULES instance by id.
     *
     * <p>Data map types are matched by identity in the loader, so a locally constructed
     * instance is useless; the exact object has to be taken from the registry table, whose
     * key set is the authoritative list of registered data map types.
     */
    private static DataMapType<Enchantment, ?> findRulesDataMapType(Registry<Enchantment> registry) {
        if (registry == null) return null;
        try {
            Map<Object, Object> table = dataMapsTable(registry);
            if (table == null) return null;
            return findRulesIn(table);
        } catch (Throwable t) {
            LOGGER.error("[{}] Could not inspect the enchantment data map table", MOD_ID, t);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static DataMapType<Enchantment, ?> findRulesIn(Map<Object, Object> table) {
        for (Object typeKey : table.keySet()) {
            if (typeKey instanceof DataMapType<?, ?> type && RULES_ID.equals(type.id())) {
                return (DataMapType<Enchantment, ?>) type;
            }
        }
        return null;
    }

    /** Logged once so a future CEI/NeoForge change is diagnosable from a single line. */
    private static void logAvailableDataMaps(Registry<Enchantment> registry) {
        try {
            Map<Object, Object> table = dataMapsTable(registry);
            if (table == null) return;
            StringBuilder sb = new StringBuilder();
            for (Object typeKey : table.keySet()) {
                if (typeKey instanceof DataMapType<?, ?> type) {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(type.id());
                }
            }
            LOGGER.warn("[{}] Registered enchantment data maps: [{}]", MOD_ID, sb);
        } catch (Throwable ignored) {
            // Diagnostics only.
        }
    }

    private static Registry<Enchantment> enchantmentRegistry(Object registryAccess) {
        Object access = registryAccess != null ? registryAccess : currentRegistryAccess;
        if (access == null) access = provider();
        if (access == null) return null;
        try {
            Method m = access.getClass().getMethod("registryOrThrow", ResourceKey.class);
            @SuppressWarnings("unchecked")
            Registry<Enchantment> registry = (Registry<Enchantment>) m.invoke(access, Registries.ENCHANTMENT);
            return registry;
        } catch (Throwable t) {
            LOGGER.error("[{}] Could not look up the enchantment registry", MOD_ID, t);
            return null;
        }
    }

    /** NeoForge's current RegistryAccess, avoiding a hard link to a version-specific holder. */
    private static Object provider() {
        try {
            Class<?> serverHooks = Class.forName("net.neoforged.neoforge.server.ServerLifecycleHooks");
            Method get = serverHooks.getMethod("getCurrentServer");
            Object server = get.invoke(null);
            if (server != null) {
                return server.getClass().getMethod("registryAccess").invoke(server);
            }
        } catch (Throwable ignored) {
            // No server yet (main menu / config load).
        }
        return null;
    }

    // ---------------------------------------------------------------------------------
    // Reflective adapter for CEI's rule record
    // ---------------------------------------------------------------------------------

    /**
     * Mirrors CEI's
     * {@code EnchantmentProcessingRule(LevelExtensionRule, CostMultiplierRule)} and
     * {@code LevelExtensionRule(Optional<Integer> blazeEnchanter, Optional<Integer> blazeForger)},
     * so no compile-time dependency on CEI is required and a CEI API change degrades to a
     * logged error instead of a hard crash.
     */
    private static final class RuleAdapter {
        private final Object original;
        private final int forger;
        private final int enchanter;

        private RuleAdapter(Object original, int forger, int enchanter) {
            this.original = original;
            this.forger = forger;
            this.enchanter = enchanter;
        }

        static RuleAdapter of(Object rule) {
            if (rule == null) return new RuleAdapter(null, -1, -1);
            try {
                Object levelExtension = invoke(rule, "levelExtension");
                return new RuleAdapter(rule, optionalInt(levelExtension, "blazeForger"),
                        optionalInt(levelExtension, "blazeEnchanter"));
            } catch (Throwable t) {
                LOGGER.warn("[{}] Could not read the existing rule; replacing it wholesale", MOD_ID, t);
                return new RuleAdapter(null, -1, -1);
            }
        }

        int forger() {
            return forger;
        }

        int enchanter() {
            return enchanter;
        }

        RuleAdapter withForger(int value) {
            return new RuleAdapter(original, value, enchanter);
        }

        RuleAdapter withEnchanter(int value) {
            return new RuleAdapter(original, forger, value);
        }

        Object build() {
            try {
                Class<?> levelRuleClass = Class.forName(CEI_LEVEL_RULE_CLASS);
                Class<?> ruleClass = Class.forName(CEI_RULE_CLASS);
                Class<?> costRuleClass = Class.forName(CEI_COST_RULE_CLASS);

                var levelCtor = levelRuleClass.getDeclaredConstructor(Optional.class, Optional.class);
                levelCtor.setAccessible(true);
                Object levelRule = levelCtor.newInstance(optional(enchanter), optional(forger));

                Object costRule = costMultiplier(ruleClass);
                if (costRule == null) return null;

                var ctor = ruleClass.getDeclaredConstructor(levelRuleClass, costRuleClass);
                ctor.setAccessible(true);
                return ctor.newInstance(levelRule, costRule);
            } catch (Throwable t) {
                LOGGER.error("[{}] Could not construct a replacement rule; CEI may have changed its API",
                        MOD_ID, t);
                return null;
            }
        }

        /** Reuses the existing cost multipliers so modpack cost tuning is preserved. */
        private Object costMultiplier(Class<?> ruleClass) {
            if (original != null) {
                try {
                    Object existing = invoke(original, "costMultiplier");
                    if (existing != null) return existing;
                } catch (Throwable ignored) {
                    // fall through to the record default
                }
            }
            try {
                Object defaultRule = ruleClass.getField("DEFAULT").get(null);
                return invoke(defaultRule, "costMultiplier");
            } catch (Throwable t) {
                LOGGER.error("[{}] Could not resolve a CostMultiplierRule default", MOD_ID, t);
                return null;
            }
        }

        private static Optional<Integer> optional(int value) {
            return value < 0 ? Optional.empty() : Optional.of(value);
        }

        private static Object invoke(Object target, String method) throws Exception {
            Method m = target.getClass().getMethod(method);
            m.setAccessible(true);
            return m.invoke(target);
        }

        @SuppressWarnings("unchecked")
        private static int optionalInt(Object record, String accessor) {
            try {
                Optional<Integer> value = (Optional<Integer>) invoke(record, accessor);
                return value != null && value.isPresent() ? value.get() : -1;
            } catch (Throwable t) {
                return -1;
            }
        }
    }

    // ---------------------------------------------------------------------------------
    // Config plumbing
    // ---------------------------------------------------------------------------------

    /** Parses {@code mending=1} / {@code mending=1,0} into enchantment id -> [forger, enchanter]. */
    static Map<String, int[]> parseRules(List<? extends String> entries) {
        Map<String, int[]> parsed = new LinkedHashMap<>();
        for (String raw : entries) {
            if (raw == null) continue;
            String line = raw.trim();
            if (line.isEmpty()) continue;

            int eq = line.indexOf('=');
            if (eq <= 0) {
                LOGGER.warn("[{}] Ignoring malformed rule '{}' (expected 'enchantment=forger' or "
                        + "'enchantment=forger,enchanter')", MOD_ID, line);
                continue;
            }

            String id = line.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            String[] values = line.substring(eq + 1).split(",");

            int forger = parseInt(values, 0);
            int enchanter = values.length > 1 ? parseInt(values, 1) : forger;
            if (forger < 0 && enchanter < 0) {
                LOGGER.warn("[{}] Ignoring rule '{}': no valid level extension", MOD_ID, line);
                continue;
            }
            parsed.put(id, new int[] {forger, enchanter});
        }
        return Map.copyOf(parsed);
    }

    private static int parseInt(String[] values, int index) {
        if (index >= values.length) return -1;
        try {
            int value = Integer.parseInt(values[index].trim());
            if (value < 0 || value > 255) {
                LOGGER.warn("[{}] Level extension {} is outside 0..255, clamped", MOD_ID, value);
                return Math.max(0, Math.min(255, value));
            }
            return value;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    static void setPendingRules(Map<String, int[]> rules) {
        pendingRules = rules;
        REPORTED.clear();
    }
}
