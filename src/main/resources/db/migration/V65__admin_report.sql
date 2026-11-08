-- V65 — the owner's daily report (AdminReportService): the day of the last report sent, claimed before the send (one conditional
-- UPDATE: two runs at once, or two servers during a deploy, send it once) and given back when the send fails.
ALTER TABLE public.email_status ADD COLUMN admin_report_day date;
