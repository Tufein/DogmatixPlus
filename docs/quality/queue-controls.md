# Downloads management and transfer safeguards

Release 2.3.0 adds search and status filters to the Downloads screen. Search matches the displayed title or file name, independently of letter case. Waiting status uses queue and scheduling context because downloads awaiting a slot can carry the `DOWNLOADING` status. Queue-wide totals and buttons retain their whole-queue meaning; selected actions apply only to matching rows. Changing the query or filter clears selection, and rows leaving the filtered results are removed from selection.

Batch priority partitions the actual waiting tickets once under the queue lock. It preserves the relative order of selected and unselected rows, publishes one snapshot, and retains slot and host limits. It never interrupts a running transfer.

HTTP resume validation happens before opening an output stream or truncating a saved partial. A malformed or mismatched partial response must be replaced with a full response, rather than writing the response tail at the beginning. Exact lengths announced by Content-Length or Content-Range are counted, with premature EOF and excess data treated as transfer failure before post-processing. Full responses without an exact length support rounded catalog sizes; a rounded HTML listing size cannot prove a body is incomplete.

Automatic retry belongs to the failed attempt that scheduled it. Restarting, stopping or deleting a row invalidates its pending retry. The scheduled retry rechecks ownership and the enabled preference before starting.

## Device acceptance

- Queue several hundred files on a physical handheld, search and switch filters while transfers finish, and check that selected actions never touch hidden rows.
- Prioritize multiple waiting downloads with a server limit enabled; verify their order and that running transfers continue.
- Pause and resume downloads against real sources and a slow or disconnected network; compare completed files with published hashes when available.
- Manually retry or stop a failed download while automatic retry is pending, and disable automatic retry before its deadline. A superseded timer must never revive the row.

Android build 35 keeps the release signing identity and database version 13. Automated tests do not establish the cause of the original photographed device crash without device logs.
