Configuration
=============

## The **`submit_rule`** and **`submit_filter`**

To enforce the *owner-approval-before-submit* rule, this plugin provides
**`find_owners:submit_rule/1`** and **`find_owners:submit_filter/2`**
predicates for Gerrit projects.

If a Gerrit project wants to enforce this *owner-approval* policy,
it can add a `submit_rule` to the [`rules.pl`
file](https://gerrit-review.googlesource.com/Documentation/config-project-config.html#file-rules_pl)
in `refs/meta/config` like this:

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
  `label('Owner-Review-Vote', may(_))` is added.
  This will show the label but not disable the Submit button.
  When a user clicks on the Submit button,
  a window will pop up and ask the user to
  (1) add missing *owners* to the reviewers list and/or
  ask for owner's +1 Code-Review votes, or
  (2) add `Exempt-From-Owner-Approval:` to the commit message.
  The **`[[FIND OWNERS]]`** button is useful in this situation to find
  the missing *owners* or +1 votes of any changed files.

When `label('Owner-Approved', may(_))` is added to the submit rule output,
Gerrit displays a grey 'Owner-Approved' label. To avoid confusion,
this `may(_)` state label could be removed by the `submit_filter` of
the root level `All-Projects`. Special automerge processes could
create changes that do not need either Code-Review vote or owner approval.
Such special conditions can also be handled in the `submit_filter`
of `All-Projects`.

A change can be declared as exempt from owner approval in the submit message,
with a special keyword `Exempt-From-Owner-Approval:` followed by some
explanation.

## Default minimal owner vote level

When `find_owners:submit_rule(S)` or `find_owners:submit_filter(In,Out)`
are applied, the default requirement is **+1** Code-Review
vote from at least one owner of every changed file.

## Default OWNERS file name

This plugin finds owners in default OWNERS files.
If a project has already used OWNERS files for other purpose,
the "ownersFileName" parameter can be used to change the default.

## Validate OWNERS files before upload

To check syntax of OWNERS files before they are uploaded,
set the following variable in project.config files.

```bash
[plugin "find-owners"]
    rejectErrorInOwners = true
```

## Example 0, call `submit_filter/2`

The simplest configuration adds to `rules.pl` of the root
`All-Projects` project.

```prolog
submit_filter(In, Out) :- find_owners:submit_filter(In, Out).
```

All projects will need at least +1 vote from an owner of every changed files.

## Example 1, call `submit_rule/2`

Add the following to `rules.pl` of project P,
to change the default and require **+2** owner review votes.

```prolog
submit_rule(S) :- find_owners:submit_rule(S, 2).
```

All patches to project P will need at least +2 vote from
an owner of every changed files.


## Example 2, call `submit_filter/3`

Add the following to `rules.pl` of project P,
to change the default and require **+2** owner review votes.

```prolog
submit_filter(In, Out) :- find_owners:submit_filter(In, Out, 2).
```

All child projects of project P will need at least +2 vote from
an owner of every changed files.

## Example 3, define `minOwnerVoteLevel`

Add the following to global `gerrit.config`
or a project's `project.config` file,
to change the default and require **+2** owner review votes.

```bash
[plugin "find-owners"]
    minOwnerVoteLevel = 2
```

## Example 4, call `remove_may_label/2`

If a change does not need *owner approval*, the *optional* greyed out
`Owner-Approved` label might cause some confusion.
To remove that label, we can add the following to `rules.pl` of
the root `All-Projects` project.

```prolog
submit_filter(In, Out) :-
  find_owners:submit_filter(In, Temp),
  find_owners:remove_may_label(Temp, Out).
```

## Example 5, call `remove_need_label/2`

To make all changes with some special property,
e.g., from the auto merger, exempt from owner approval,
we can add a special filter to `rules.pl` of the root `All-Projects` project.

```prolog
submit_filter(In, Out) :-
  is_exempt_from_owner_approval(), % define-your-own-rule
  find_owners:remove_need_label(In, Out).
```

## Example 6, define `addDebugMsg`

Add the following to global `gerrit.config`,
to debug the find-owners plugin.

```bash
[plugin "find-owners"]
    addDebugMsg = true
```

## Example 7, change default OWNERS file name

Add the following to global `gerrit.config`
or a project's `project.config` file,
to change the default "OWNERS" file name,
e.g., to "OWNERS.android".

```bash
[plugin "find-owners"]
    ownersFileName = OWNERS.android
```

If ownersFileName is defined the project should have such a specified file
at the root directory.
Otherwise it would be considered a configuration or Gerrit server error.
