Customers far from UTC complain that their reminders land on the wrong day. The daily digest, the "today" list (the
text listing and the web view) and the due-today rule all use the UTC calendar date instead of the user's own day
(`User.utc_offset_minutes`, a fixed offset: no daylight saving in this version), and the text listing prints UTC times.
Find every place that groups, lists or counts reminders by day, or shows a time of day, and make it use the user's own
day and clock.

Then add quiet hours: a reminder that falls between 22:00 (inclusive) and 07:00 (exclusive) on the user's own clock is
not sent then, but held until the next 07:00 on that clock; an urgent reminder is never held. Implement it as
`next_send_time(reminder, user)` in `sched/rules.py`, which returns an aware instant, and make
`notify.due_notifications` list only what is past its send time.

Add tests for both parts and keep the existing ones passing (`python -m unittest discover -s tests`).
