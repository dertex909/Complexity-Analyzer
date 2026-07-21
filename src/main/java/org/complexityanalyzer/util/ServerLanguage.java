/*
 * Complexity Analyzer
 * Copyright (C) 2025-2026 dertex909
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package org.complexityanalyzer.util;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMaps;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerPlayer;
import org.complexityanalyzer.ComplexityAnalyzer;

import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

import static java.util.Locale.ROOT;

public class ServerLanguage {
    private static final ConcurrentHashMap<String, Object2ObjectMap<String, String>> LANGUAGES = new ConcurrentHashMap<>(16);
    private static final String DEFAULT_LOCALE = "en_us";
    private static final Gson GSON = new Gson();

    private static final Type MAP_TYPE = new TypeToken<Object2ObjectOpenHashMap<String, String>>() {
    }.getType();

    static {
        LANGUAGES.put(DEFAULT_LOCALE, loadLanguageInternal(DEFAULT_LOCALE));
    }

    public static void init() {
        ComplexityAnalyzer.LOGGER.info("Initializing Server-Side Language Manager...");
    }

    private static Object2ObjectMap<String, String> loadLanguageInternal(String locale) {
        String path = "/assets/complexityanalyzer/lang/" + locale + ".json";
        try (var is = ComplexityAnalyzer.class.getResourceAsStream(path)) {
            if (is != null) {
                Object2ObjectOpenHashMap<String, String> map = GSON.fromJson(new InputStreamReader(is, StandardCharsets.UTF_8), MAP_TYPE);
                return map != null ? Object2ObjectMaps.unmodifiable(map) : Object2ObjectMaps.emptyMap();
            } else {
                ComplexityAnalyzer.LOGGER.warn("[Language] Lang file not found in resources: {}.json", locale);
                return Object2ObjectMaps.emptyMap();
            }
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.error("[Language] Failed to load server-side language: {}", locale, e);
            return Object2ObjectMaps.emptyMap();
        }
    }

    public static String get(String key, String locale) {
        String cleanLocale = locale != null ? locale.toLowerCase(ROOT) : DEFAULT_LOCALE;
        var map = LANGUAGES.computeIfAbsent(cleanLocale, ServerLanguage::loadLanguageInternal);
        String val = map.get(key);

        if (val == null && !cleanLocale.equals(DEFAULT_LOCALE)) {
            var defaultMap = LANGUAGES.computeIfAbsent(DEFAULT_LOCALE, ServerLanguage::loadLanguageInternal);
            val = defaultMap.get(key);
        }
        return val;
    }

    private static boolean needsTranslation(Component component) {
        if (component.getContents() instanceof TranslatableContents) return true;
        var style = component.getStyle();
        if (style.getHoverEvent() != null && style.getHoverEvent().getAction() == HoverEvent.Action.SHOW_TEXT) {
            var hoverContent = style.getHoverEvent().getValue(HoverEvent.Action.SHOW_TEXT);
            if (hoverContent != null && needsTranslation(hoverContent)) return true;
        }
        for (var sibling : component.getSiblings()) if (needsTranslation(sibling)) return true;
        return false;
    }

    public static Component translate(Component component, String locale) {
        if (!needsTranslation(component)) return component;

        MutableComponent result;

        if (component.getContents() instanceof TranslatableContents translatable) {
            String key = translatable.getKey();
            Object[] args = translatable.getArgs();
            Object[] translatedArgs = new Object[args.length];

            for (int i = 0; i < args.length; i++) {
                if (args[i] instanceof Component c) {
                    translatedArgs[i] = translate(c, locale).getString();
                } else {
                    translatedArgs[i] = args[i];
                }
            }

            String pattern = get(key, locale);
            if (pattern != null) {
                try {
                    String formatted = translatedArgs.length > 0 ? String.format(pattern, translatedArgs) : pattern;
                    result = Component.literal(formatted);
                } catch (Exception e) {
                    ComplexityAnalyzer.LOGGER.error("[Language] Error formatting key {}: {} (Args: {})", key, e.getMessage(), Arrays.toString(translatedArgs));
                    result = Component.translatable(key, translatedArgs);
                }
            } else {
                result = Component.translatable(key, translatedArgs);
            }
        } else {
            result = component.copy();
            result.getSiblings().clear();
        }

        var style = component.getStyle();
        if (style.getHoverEvent() != null && style.getHoverEvent().getAction() == HoverEvent.Action.SHOW_TEXT) {
            var hoverContent = style.getHoverEvent().getValue(HoverEvent.Action.SHOW_TEXT);
            if (hoverContent != null) {
                var translatedHover = translate(hoverContent, locale);
                style = style.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, translatedHover));
            }
        }
        result.withStyle(style);

        for (var sibling : component.getSiblings()) result.append(translate(sibling, locale));

        return result;
    }

    public static Component translateForPlayer(Component component, ServerPlayer player) {
        if (player == null) return translate(component, DEFAULT_LOCALE);
        return translate(component, player.clientInformation().language().toLowerCase(ROOT));
    }
}