/*
 * Mending Forger Fix - configuration
 * SPDX-License-Identifier: MIT
 */

package com.mendingfix;

import java.util.List;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

public final class MendingFixConfig {
    public static final ModConfigSpec SPEC;
    private static ModConfigSpec.ConfigValue<List<? extends String>> enchantmentExtensions;

    static {
        Pair<MendingFixConfig, ModConfigSpec> pair =
                new ModConfigSpec.Builder().configure(MendingFixConfig::new);
        SPEC = pair.getRight();
        enchantmentExtensions = pair.getLeft().extensions;
    }

    private final ModConfigSpec.ConfigValue<List<? extends String>> extensions;

    private MendingFixConfig(ModConfigSpec.Builder builder) {
        extensions = builder
                .comment(
                        "Per-enchantment level extensions for the Blaze Forger, overriding Create:",
                        "Enchantment Industry's hard-coded enchantment_processing/rules data map.",
                        "",
                        "Format: \"<enchantment_id>=<blaze_forger>[,<blaze_enchanter>]\"",
                        "  0 = keep the vanilla max level (CEI's default for Mending and Infinity)",
                        "  1 = allow one level above vanilla (Mending I + Mending I -> Mending II)",
                        "",
                        "The enchanter value defaults to the forger value when omitted.",
                        "CEI caps Mending and Infinity at 0, which is why the Blaze Forger refuses",
                        "to combine two Mending I books even when the config option",
                        "blazeForgerMaxLevelExtension is raised: a per-enchantment rule wins.",
                        "",
                        "Only raise CEI's own blazeForgerMaxLevelExtension if you also want",
                        "extensions for enchantments that have no rule listed here.")
                .defineListAllowEmpty(
                        "enchantmentExtensions",
                        List.of("minecraft:mending=1"),
                        () -> "minecraft:mending=1",
                        MendingFixConfig::isValidEntry);
    }

    /** Malformed entries are logged and dropped by the parser instead of failing the load. */
    private static boolean isValidEntry(Object value) {
        return value instanceof String;
    }

    @SubscribeEvent
    public static void onLoad(ModConfigEvent.Loading event) {
        if (event.getConfig().getSpec() == SPEC) push();
    }

    @SubscribeEvent
    public static void onReload(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() == SPEC) push();
    }

    /** Called once at mod construction, before the first data map load. */
    public static void push() {
        MendingForgerFix.setPendingRules(MendingForgerFix.parseRules(enchantmentExtensions.get()));
    }
}
