package com.scan2play.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

/**
 * The pages "Dla kogo" (Next 7, 2026-10-21): one for each kind of party the landing page's cards name — wedding DJs, clubs, pubs — for
 * a search engine (in the sitemap, Polish at the page's own address, English with {@code ?lang=en}, like the other public pages) and
 * for a link sent to a DJ or a pub's owner. One template, {@code for-whom.html}, and the texts {@code forWhom.KEY.*}: each page its own
 * steps, features and questions — not one page with a word swapped, which a search engine counts as a doorway page. No karaoke page
 * (the owner, 2026-10-21: nothing in the app is made for it yet). No form on them: no session, no CSRF token.
 */
@Controller
public class ForWhomController {

    /** A page: its Polish address and the key of its texts. */
    public record Page(String path, String key) {}

    /** The pages, in the landing page's order. */
    public static final List<Page> PAGES = List.of(
            new Page("/dj-weselny", "wedding"),
            new Page("/kluby", "club"),
            new Page("/puby", "pub"));

    @GetMapping({"/dj-weselny", "/kluby", "/puby"})
    public String page(HttpServletRequest request, Model model) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        Page page = PAGES.stream().filter(p -> p.path().equals(path)).findFirst().orElseThrow();
        model.addAttribute("forWhom", page);
        model.addAttribute("forWhomOthers", PAGES.stream().filter(p -> p != page).toList());
        return "for-whom";
    }
}
