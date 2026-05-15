package org.complexityanalyzer.util;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMaps;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerPlayer;
import org.complexityanalyzer.ComplexityAnalyzer;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class ServerLanguage {
    private static final Object2ObjectMap<String, Object2ObjectMap<String, String>> LANGUAGES = new Object2ObjectOpenHashMap<>();
    private static final String DEFAULT_LOCALE = "en_us";
    private static final Gson GSON = new Gson();

    static {
        loadLanguage(DEFAULT_LOCALE);
    }

    public static void init() {
        ComplexityAnalyzer.LOGGER.info("Initializing Server-Side Language Manager...");
    }

    private static void loadLanguage(String locale) {
        if (LANGUAGES.containsKey(locale)) return;

        String path = "/assets/complexityanalyzer/lang/" + locale + ".json";
        try (InputStream is = ComplexityAnalyzer.class.getResourceAsStream(path)) {
            if (is != null) {
                Object2ObjectOpenHashMap<String, String> map = GSON.fromJson(
                        new InputStreamReader(is, StandardCharsets.UTF_8),
                        new TypeToken<Object2ObjectOpenHashMap<String, String>>() {
                        }.getType()
                );
                LANGUAGES.put(locale, map != null ? map : Object2ObjectMaps.emptyMap());
            } else {
                ComplexityAnalyzer.LOGGER.warn("[Language] Lang file not found in resources: {}.json", locale);
            }
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.error("[Language] Failed to load server-side language: {}", locale, e);
        }
    }

    public static String get(String key, String locale) {
        String cleanLocale = locale != null ? locale.toLowerCase() : DEFAULT_LOCALE;
        loadLanguage(cleanLocale);
        String val = LANGUAGES.get(cleanLocale).get(key);
        if (val == null && !cleanLocale.equals(DEFAULT_LOCALE)) {
            loadLanguage(DEFAULT_LOCALE);
            val = LANGUAGES.get(DEFAULT_LOCALE).get(key);
        }
        return val;
    }


    public static Component translate(Component component, String locale) {
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

        Style style = component.getStyle();
        if (style.getHoverEvent() != null && style.getHoverEvent().getAction() == HoverEvent.Action.SHOW_TEXT) {
            Component hoverContent = style.getHoverEvent().getValue(HoverEvent.Action.SHOW_TEXT);
            if (hoverContent != null) {
                Component translatedHover = translate(hoverContent, locale);
                style = style.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, translatedHover));
            }
        }
        result.withStyle(style);

        for (Component sibling : component.getSiblings()) result.append(translate(sibling, locale));

        return result;
    }

    public static Component translateForPlayer(Component component, ServerPlayer player) {
        if (player == null) return translate(component, DEFAULT_LOCALE);
        return translate(component, player.clientInformation().language().toLowerCase());
    }
}