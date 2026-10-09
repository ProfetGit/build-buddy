package io.github.profetgit.buildbuddy.ponder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The Help list's search: every word typed must be in a lesson's title, summary or tags (any part of a word counts, capitals do
 * not), and the lessons come best first: a word in the title counts most, then the tags, then the summary. Without words, the list
 * as it is. Pure, so it is tested without the game.
 */
public final class LessonSearch {
    private LessonSearch() {
    }

    public static List<Scene> filter(List<Scene> all, String query) {
        String[] words = query.toLowerCase(Locale.ROOT).trim().split("\\s+");
        if (query.isBlank()) return all;
        record Hit(Scene scene, int score, int order) {
        }
        List<Hit> hits = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            Scene s = all.get(i);
            String title = s.title.toLowerCase(Locale.ROOT), summary = s.summary.toLowerCase(Locale.ROOT), tags = String.join(" ", s.tags).toLowerCase(Locale.ROOT);
            int score = 0;
            boolean every = true;
            for (String w : words) {
                int here = (title.contains(w) ? 3 : 0) + (tags.contains(w) ? 2 : 0) + (summary.contains(w) ? 1 : 0);
                if (here == 0) {
                    every = false;
                    break;
                }
                score += here;
            }
            if (every) hits.add(new Hit(s, score, i));
        }
        hits.sort(Comparator.comparingInt((Hit h) -> -h.score).thenComparingInt(Hit::order));
        List<Scene> out = new ArrayList<>(hits.size());
        for (Hit h : hits) out.add(h.scene);
        return out;
    }
}
