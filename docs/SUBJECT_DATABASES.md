# SUBJECT database management

ANTARES keeps an active SUBJECT selector and its management menu above all four processing buttons. The main screen has one preview and one status area. Import, Save As, reopening, and export grouping are available from Photos and files. Every workflow displays the active subject name before selection or processing.

| Menu action | Behavior |
| --- | --- |
| Create SUBJECT Database | Create and select a subject under a unique name. |
| Rename SUBJECT Database | Change the active subject's display name while retaining its internal record ID. |
| Backup SUBJECT Database | Choose a ZIP name and destination in Android Files. The archive includes the subject's records, original byte copies, result images and landmark documents. Each archived payload has a SHA-256, and the written ZIP is read back before success. |
| Restore SUBJECT Database | Pick an ANTARES ZIP in Android Files. The restore verifies staged files, assigns new local IDs, remaps references and landmarks, and commits the new subject atomically to SQLite. An existing subject of the same name remains intact. |
| Delete SUBJECT Database | Review the exact subject name and record scope. The dialog offers a backup first. Deletion is blocked while the subject has active jobs and never deletes a source photograph outside ANTARES. |

Reference IDs, jobs, attachments, analysis, and folder preferences are subject-scoped. Byte-identical immutable originals may share private backing storage. Detached private originals are retained in that backing storage pending a separately qualified cleanup mechanism. Archive restore creates a separate subject. Choose a local export folder again after restoring because Android file-provider permissions are specific to the installation. A backup interrupted during the external write may leave an incomplete ZIP, which a restore validates before creating any records.

The Android test suite checks subject creation, name changes, selecting and displaying the active subject in all four workflows, rejection of cross-subject links, verified backup, rollback after a truncated file, remapped restore, deletion of a single subject, and rejection of deletion with active work. Physical Galaxy A14 picker, confirmation, and interruption tests remain release gates.
