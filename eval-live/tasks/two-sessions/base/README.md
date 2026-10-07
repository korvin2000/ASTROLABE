# sched

Reminders for people in many time zones.

| module | what it does |
|---|---|
| `sched.model` | `User` (with a fixed `utc_offset_minutes`) and `Reminder` (`due` is an aware UTC instant) |
| `sched.timeutil` | the time helpers: UTC days, wall-clock time in a user's zone |
| `sched.store` | the in-memory store and its queries (`due_today`, `due_between`, `overdue`) |
| `sched.digest` | the daily digest: the reminders of the next days, grouped by day |
| `sched.rules` | rules about one reminder: due today, overdue |
| `sched.notify` | which reminders are sent now |
| `sched.web` | the JSON views (`today_view`, `digest_view`) |
| `sched.cli` | the `today` text listing |

Offsets are fixed per user (no daylight saving in this version). Run the tests with
`python -m unittest discover -s tests`.
