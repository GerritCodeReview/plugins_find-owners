**NOTE:** The find-owners plugin has been deprecated in favour of the
[code-owners](https://gerrit.googlesource.com/plugins/code-owners/+/HEAD/resources/Documentation/about.md)
plugin.

This plugin works with Gerrit projects that
use Android or Chromium compatible **OWNERS** files.
The **OWNERS** files specify *owners*, or active maintainers,
of a project and its directories or files.
Changes to those files or directories will
need *owner's* review and approval before submission,
with some exceptions.

* Syntax and some examples of **OWNERS** files are described in [syntax.md](syntax.md).

* Configuration examples of this plugin are included in [config.md](config.md).
  Prolog `submit_rule` or `submit_filter` are used to enforce
  *owner's* review and approval for projects with **OWNERS** files.

* A **[FIND OWNERS]** action button is added to the Gerrit revision screen.
  The button pops up a window that contains
    * owners of changed files for users to select to add to the reviewers list, and
    * changed files without required *owner* code review vote.

* A REST API is added to get owners information of a change.
  The API is described in [rest-api.md](rest-api.md).
