package com.scan2play.template;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every template — the pages and the fragments, also those no browser test opens (the landing page, the legal pages, the error
 * pages) — works under the Content-Security-Policy of {@code SecurityConfig} (review 5.1): no inline script, no {@code on…=}
 * handler, no inline style ({@code style="…"}, {@code th:style}, a {@code <style>} element). The browser tests catch the same on
 * the pages they open; this catches it on every page, without a browser.
 */
class NoInlineCodeInTemplatesTest {

    private static final Pattern INLINE_SCRIPT = Pattern.compile("<script(?![^>]*\\ssrc=)[^>]*>");
    private static final Pattern HANDLER = Pattern.compile("\\s(?:th:)?on[a-z]+\\s*=", Pattern.CASE_INSENSITIVE);
    private static final Pattern STYLE = Pattern.compile("\\s(?:th:)?style\\s*=|<style[\\s>]", Pattern.CASE_INSENSITIVE);
    // Thymeleaf's comments (<!--/* … */-->) and HTML comments are not rendered: what they say does not count
    private static final Pattern COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);

    @Test
    void noTemplateHasAnInlineScriptAHandlerOrAnInlineStyle() throws IOException {
        Resource[] templates = new PathMatchingResourcePatternResolver().getResources("classpath:templates/**/*.html");
        assertThat(templates).hasSizeGreaterThan(10);

        List<String> found = new ArrayList<>();
        for (Resource template : templates) {
            String html = COMMENT.matcher(template.getContentAsString(StandardCharsets.UTF_8)).replaceAll("");
            for (Pattern pattern : List.of(INLINE_SCRIPT, HANDLER, STYLE)) {
                Matcher m = pattern.matcher(html);
                while (m.find()) {
                    found.add(template.getFilename() + ": " + m.group().trim());
                }
            }
        }
        assertThat(found).as("inline code the CSP would block").isEmpty();
    }
}
