# Find-Owners Plugin

## The **OWNERS** files

This plugin is designed for Gerrit projects that contain **OWNERS** files.
The OWNERS files specify *owners*, or active maintainers, of projects,
directories, or files. Changes to those files or directories  should have
an *owner's* review and approval.

The **OWNERS** files are used in Android and Chromium open source projects.
This plugin is easy to apply to any Gerrit review project with compatible
OWNERS files.

### **OWNERS** file syntax

Owner approval is based on OWNERS files located in the same
repository and top of the _merge-to_ branch of a patch set.

An **OWNERS** file has the following syntax:

```java
lines     := (SPACE* line? SPACE* EOL)*
line      := "set noparent"
          |  "per-file" SPACE+ glob SPACE* "=" SPACE* directive
          |  comment
          |  directive
directive := email
          |  "*"
glob      := [a-zA-Z0-9_-*?]+
comment   := "#" ANYCHAR*
email     := [^ @]+@[^ #]+
ANYCHAR   := any character but EOL
EOL       := end of line characters
SPACE     := any white space character
```

* `per-file glob = directive` applies `directive` only to files
  matching `glob`, which does not contain directory path.
* The email addresses should belong to registered Gerrit users.
* The `*` directive means that no owner is specified for the directory
  or file. Any user can approve that directory or file.

## The **`Find Owners`** button

This plugin adds a **`Find Owners`** button to Gerrit revision screen.
The button pops up a window that contains:

* current reviewers and owners of changed files for users to select, and
* optionally a list of changed files that have not any required *owner*
  code review vote.

This button is useful for change authors to find *owners* of all changed
files and add some of them into the reviewer list.
Note that a file usually has multiple *owners* and many files
share the same *owners*. A change author should select at least one *owner*
of each changed file. It is common to select a small number of *owners*
that can review collectively all the changed files.

## The **`submit_rule`** and **`submit_filter`**

To enforce the *owner-approval-before-submit* rule, this plugin provides
**`find_owners:submit_rule/1`** and **`find_owners:submit_filter/2`**
predicates for Gerrit projects.

If a Gerrit project wants to enforce this *owner-approval* policy,
it can add a `submit_rule` to its `rules.pl` file like this:

```prolog
submit_rule(S) :- find_owners:submit_rule(S).
```

If many projects need this *owner-approval* policy,
each of them can have a `submit_rule` defined, or we can simply
define a `submit_filter` in their common parent project's
`rules.pl` file like this:

```prolog
submit_filter(In, Out) :- find_owners:submit_filter(In, Out).
```

By default the `find_owners:submit_rule` calls `gerrit:default_submit`,
and the `find_owners:submit_filter` passes `In` to `Out`.
They add special labels to the output to indicate if *owner* approval
is needed or missing.

* If a change does not need owner approval, `label('Owner-Approved', may(_))`
  is added. This is an *optional* requirement that does not affect
  a change's submittability.
* If a change needs owner approval, and all changed files have at least one
  *owner* voted +1 and no negative vote,
  `label('Owner-Approved', ok(user(1)))` is added.
* If a change needs owner approval, but some changed file has no *owner*
  +1 vote or has negative *owner* vote,
  `label('Owner-Review-Vote', need(_))` is added.
  This will make a change *not-submittable*.
  The change author should add missing *owners* to the
  reviewers list and/or ask for those owner's +1 Code-Review votes.
  The **`Find Owners`** button is useful in this situation to find
  the missing *owners* or +1 votes of any changed files.

When `label('Owner-Approved', may(_))` is added to the submit rule output,
Gerrit displays a grey 'Owner-Approved' label. To avoid confusion,
this 'may(_)' state label could be removed by the `submit_filter` of
the root level `All-Projects`. Special automerge processes could
create changes that do not need either Code-Review vote or owner approval.
Such special conditions can also be handled in the `submit_filter`
of `All-Projects`. See examples in [config.md](config.md).

### Default minimal owner vote level

When `find_owners:submit_rule(S)` or `find_owners:submit_filter(In,Out)`
are applied, the default requirement is **+1** Code-Review
vote from at least one owner of every changed file.

See examples in [config.md](config.md) to change the defaults.

## The **`find-owners`** REST API

Other Gerrit UI clients or tools can use the
`plugins/find-owners/change/id` API to get
OWNERS information of a change. The HTTP GET syntax is:

```
http://<gerrit-server-address>/plugins/find-owners/change/<id>
```

### Input Parameters

The `<id>` is a Gerrit change ID. This API can have two parameters:

* **patch**: is the patch set number of the change to look for changed files.
  By default the current (latest) patch set of given change is used.
* **debug**: can be set to true or false to override the configuration variable
  **addDebugMsg**.

For example,

```
http://.../plugins/find-owners/change/29?debug=true&patch=3
```

### JSON Output Attributes

This API returns a JSON object with the following attributes:

* **minOwnerVoteLevel**: is 1 by default, but could be set to 2.
   It is the minimal Code-Review vote value all changed files must get
   from at least one owner to make a change submittable.
* **addDebugMsg**: is false by default. In a development/test configuration,
   this attribute could be true, to tell a client to display extra debug
   information.
* **revision**: is the revision where OWNERS files were searched.
   It is the top revision of the given change's project branch.
* **dbgmsgs**: returned only when addDebugMsg is true,
   a set of debugging messages including change id, patch set number,
   project name, branch name, server address, etc.
* **path2owners**: returned only when addDebugMsg is true,
   a map from directory path or file glob to a string of owner emails
   separated by space. Note that `*` is a special owner email address.
   It means that there is no owner or anyone can be the owner.
* **owner2paths**: returned only when addDebugMsg is true,
   a map from owner email to directory path or file glob.
   This is opposite to the path2owners map.
* **file2owners**: a map from each file in the change patch set to
   the file owner emails, separated by space.
* **reviewers**: an array of current reviewer emails followed by
   optional extra information that should be ignored for now.
* **owners**: an array of owner emails followed by the owner weights,
   `[n1+n2+n3]`, which are the number of level 1, 2, 3+ controlled files.
   This list of owners are the keys in the owner2paths map.
   The array is sorted by owner weights.
   Users should try to pick owners with more weights to review a change.
* **files**: an alphabetically sorted files changed in the given change patch set.
