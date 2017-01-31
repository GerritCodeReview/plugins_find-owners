This plugin works with Gerrit projects that
use Android or Chromium compatible **OWNERS** files.
The **OWNERS** files specify *owners*, or active maintainers,
of a project and its directories or files.
Changes to those files or directories will
need *owner's* review and approval before submitted,
with some exceptions.

**OWNERS** files syntax is describe in [syntax.md](syntax.md).
Projects with **OWNERS** file should be configured with
Prolog `submit_rule` or `submit_filter`, see [config.md](config.md).

A **`Find Owners`** button is added to the Gerrit revision screen
if a change needs owner approval.  The button pops up a window that contains
(1) current reviewers and owners of changed files for users to select, and
(2) optionally changed files without required *owner* code review vote.

A REST API is added to get owners information of a change.
The API is described in [rest-api.md](rest-api.md).
