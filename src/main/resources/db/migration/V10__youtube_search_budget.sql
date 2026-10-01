-- V10 — the daily count of YouTube Data API searches (YouTubeSearchBudget), one row per Google day (midnight Pacific time).
--
-- The count used to live in memory, so every restart (a deploy, a devtools restart) gave the day's budget back and the fuse
-- could let the project's search quota run out. A row holds how many searches the day took and whether Google answered
-- quotaExceeded; a search is taken with one INSERT ... ON CONFLICT DO UPDATE ... RETURNING, so concurrent requests (and
-- instances) never take more than the budget. Rows older than 30 days are deleted by the application. No entity maps the
-- table (JdbcTemplate), so Hibernate does not validate it.
CREATE TABLE public.youtube_search_budget (
    day       date    NOT NULL,
    used      integer NOT NULL,
    exhausted boolean NOT NULL DEFAULT false,
    CONSTRAINT youtube_search_budget_pkey PRIMARY KEY (day)
);
