package com.scan2play.deck;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** XML's entities in a value: the five named ones and the numbered. */
final class Xml {

    private static final Pattern ENTITY = Pattern.compile("&(#x[0-9a-fA-F]+|#\\d+|amp|lt|gt|quot|apos);");
    private static final Map<String, String> NAMED = Map.of("amp", "&", "lt", "<", "gt", ">", "quot", "\"", "apos", "'");

    private Xml() {
    }

    static String unescape(String text) {
        Matcher entity = ENTITY.matcher(text);
        StringBuilder out = new StringBuilder();
        while (entity.find()) {
            String name = entity.group(1);
            String value;
            try {
                value = name.startsWith("#x") ? Character.toString(Integer.parseInt(name.substring(2), 16))
                        : name.startsWith("#") ? Character.toString(Integer.parseInt(name.substring(1)))
                        : NAMED.get(name);
            } catch (IllegalArgumentException e) {
                value = entity.group();
            }
            entity.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        entity.appendTail(out);
        return out.toString();
    }
}
