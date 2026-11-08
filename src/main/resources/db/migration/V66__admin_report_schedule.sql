-- V66 — when the owner's daily report goes (AdminReportService, set on /dj/stats/report): on or off, the time of day (Polish time)
-- and the days of the week as bits (Monday 1, Tuesday 2, … Sunday 64; 127 = every day). By default every day at 9:10, as before.
ALTER TABLE public.email_status
    ADD COLUMN admin_report_on boolean NOT NULL DEFAULT true,
    ADD COLUMN admin_report_time time NOT NULL DEFAULT '09:10',
    ADD COLUMN admin_report_days smallint NOT NULL DEFAULT 127 CHECK (admin_report_days BETWEEN 0 AND 127);
