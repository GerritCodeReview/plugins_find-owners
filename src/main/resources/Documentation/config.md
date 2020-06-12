Configuration
=============

## Table of Contents
* [Example from AOSP](#example-from-aosp)
* [Global vs Project Variables](#global-vs-project-variables)
* [Submit Rules and Filters](#submit-rules-and-filters)
* [Exempt from Owner Approval](#exempt-from-owner-approval)
* [Minimal Owner Code-Review Vote](#minimal-owner-code-review-vote)
* [OWNERS File Name](#owners-file-name)
* [OWNERS Upload Validator](#owners-upload-validator)
* [More Prolog Examples](#more-prolog-examples)

## Example from AOSP

The `find-owners` plugin is used in Android Open Source Project (AOSP).
Here is [one AOSP change](https://android-review.googlesource.com/c/platform/build/+/734949)
that modified multiple OWNERS files.
If you sign in to the AOSP Gerrit server,
you can see and click the `[FIND OWNERS]` button and
it will pop up a window with *owners* of the changed files.

The AOSP Gerrit server and projects are configured with the
following steps:

1. Enable the plugin in Gerrit configuration file `gerrit.config`:
    ```bash
    [plugin "find-owners"]
        enable = true
        maxCacheAge = 30
        maxCacheSize = 2000
    ```
    * **enable = true** enables the plugin for all projects.
    * **maxCacheAge** is the number of seconds owners info will stay in
      a cache before being refreshed. This reduces repeated access to the same
      OWNERS files in a repository. If it is set to 0 (default), there will
      be no cache.
    * **maxCacheSize** limits the number of owners info stored in the
      cache to reduce memory footprint.

1. Enable the upload validator in `project.config` of
   the `All-Projects` project, in the `refs/meta/config` branch:
    ```bash
    [plugin "find-owners"]
        rejectErrorInOwners = true
    ```
    * The upload validator checks basic syntax of uploaded OWNERS files.
      It also checks if all email addresses used in OWNERS files
      belong to active Gerrit accounts.
    * All other Gerrit projects inherit from `All-Projects`,
      so they have the same enabled upload validator.

1. Optionally redefine **OWNERS** file name in `project.config` of
   some projects, in the `refs/meta/config` branch:
    ```bash
    [plugin "find-owners"]
        ownersFileName = OWNERS.android
    ```
    * The AOSP `platform/external/v8` project keeps a copy of upstream
      source from https://github.com/v8/v8.
    * The v8 upstream source already has its `OWNERS` files
      that do not work with AOSP Gerrit, because the Email addresses
      in those files are not all active developers for AOSP.
      So we need to use different *owners* files,
      with the `OWNERS.android` file name.

1. Call the submit filters in `rules.pl` of `All-Projects`,
   in the `refs/meta/config` branch:
    ```prolog
    % Special projects, branches, user accounts can opt out owners review.
    % To disable all find_owners rules, add opt_out_find_owners :- true.
    opt_out_find_owners :-
        gerrit:change_branch('refs/heads/pie-gsi').

    % Special projects, branches, user accounts can opt in owners review.
    % To default to find_owners rules, add opt_in_find_owners :- true.
    opt_in_find_owners :- true.

    % If opt_out_find_owners is true, remove all 'Owner-Review-Vote' label;
    % else if opt_in_find_owners is true, call find_owners:submit_filter;
    % else default to no find_owners filter.
    check_find_owners(In, Out) :-
        ( opt_out_find_owners -> find_owners:remove_need_label(In, Temp)
        ; opt_in_find_owners -> find_owners:submit_filter(In, Temp)
        ; In = Temp
        ),
        Temp =.. [submit | A],
        change_find_owners_labels(A, B),
        Out =.. [submit | B].

    submit_filter(In, Out) :-
      In =.. [submit | A],
      check_drno_review(A, B),
      check_api_review(B, C),
      check_qualcomm_review(C, D),
      Temp =.. [submit | D],
      check_find_owners(Temp, Out).

    % Remove useless label('Owner-Approved',_) after final filter.
    % Change optional label('Owner-Review-Vote', may(_)) to
    % label('Owner-Review-Vote', need(_)) to hide the Submit button.
    change_find_owners_labels([], []).

    change_find_owners_labels([H | T], R) :-
      H = label('Owner-Approved', _), !,
      change_find_owners_labels(T, R).

    change_find_owners_labels([H1 | T], [H2 | R]) :-
      H1 = label('Owner-Review-Vote', may(_)), !,
      H2 = label('Owner-Review-Vote', need(_)),
      change_find_owners_labels(T, R).

    change_find_owners_labels([H | T], [H | R]) :-
      change_find_owners_labels(T, R).

    ```
    * With [predefined Gerrit Prolog Facts](
      https://gerrit-review.googlesource.com/Documentation/prolog-change-facts.html),
      any project, branch, or user can be matched
      and added to the `opt_out_find_owners`
      or `opt_in_find_owners` rules.
    * If the `submit_filter` output contains
      `label('Owner-Review-Vote', need(_))`,
      the Gerrit change cannot be submitted.
    * For a simpler configuration without opt-out projects,
      just call `find_owners:submit_filter`
      and `change_find_owners_labels`.


## Global vs Project Variables

In the AOSP example, we saw that some variables are defined in global
`gerrit.config` and some are defined in project-specific `project.config`
files. The Prolog submit rules are defined in the `rules.pl` files of
each project. The following is a summary of all user-configurable
variables for this plugin.

* Global variables must be defined in `gerrit.config`:

    * `enabled` should be true to enable this plugin.
      Note that this variable enables the UI `[FIND OWNERS]`
      button but not yet the submit rules and upload validator.

    * `maxCacheAge` has default value 0, meaning no cache.
      All CLs for one Gerrit site share the same cache of
      owners info, which will stay in cache for up to `maxCacheAge`
      seconds.

    * `maxCacheSize` has default value 1000. When `maxCacheAge` is non-zero,
      up to `maxCacheSize` owner info objects will be stored in the cache.

    * `minOwnerVoteLevel` has default value 1. It means that when owner
      approval check is enabled, every changed file needs at least one
      owner's `Code-Review` +1 vote. This variable can be defined to 2 to
      require owner's +2 `Code-Review` votes.

    * `addDebugMsg` has default value false. When it is defined to true,
      the find-owners REST API will add extra debug messages by default
      in the returned JSON object.
    * `autoAuthorApproval` has a default value of true.  When it is defined to 
      false, no assumptions will be made about a vote from an author who is
      also an owner.  

* Project variables should be defined in `project.config`
  of the `All-Projects` project and inherited by all other projects,
  or they can be defined in each individual project.

    * `rejectErrorInOwners` has default value false. When it is true,
      an *upload-validator* will check all new and changed OWNERS files
      and reject the upload when any syntax error is found. If any owner
      email address in an OWNERS file is not found on the Gerrit site,
      it will also be considered an error and the upload will be rejected.

    * `ownersFileName` has default value "OWNERS". It can be defined to
       a different name such as "OWNERS.android" if "OWNERS" files already
       exist for another purpose.

## Submit Rules and Filters

To enforce the *owner-approval-before-submit* rule, this plugin provides
**`find_owners:submit_rule/1`** and **`find_owners:submit_filter/2`**
predicates for Gerrit projects.

If a Gerrit project wants to enforce this *owner-approval* policy,
it can add a `submit_rule` to the [`rules.pl` file](
https://gerrit-review.googlesource.com/Documentation/config-project-config.html#file-rules_pl)
in the `refs/meta/config` branch like this:

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

As we saw in the AOSP example, it is much easier to use only the `submit_filter`
in the `All-Projects` project to check owner approval for all projects.
If any project or branch need to opt-out of this check, simple Prolog rules can
be defined to skip them, see the `opt_out_find_owners` rule in the AOSP example.

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
  The **`[FIND OWNERS]`** button is useful in this situation to find
  the missing *owners* or +1 votes of any changed files.

When `label('Owner-Approved', may(_))` is added to the submit rule output,
Gerrit displays a grey 'Owner-Approved' label. To avoid confusion,
this `may(_)` state label could be removed by the `submit_filter` of
the root level `All-Projects`.
See the `change_find_owners_labels` rule in the AOSP example.

## Exempt from Owner Approval

A change can be declared as exempt from owner approval in the submit message,
with a special keyword `Exempt-From-Owner-Approval:` followed by some
explanation.

As shown in the AOSP example, special Prolog rules like `opt_out_find_owners`
can be used to skip `find_owners:submit_filter` for any project, branch, or user.
For example, special automerge processes could create changes
that do not need either Code-Review vote or owner approval.

## Minimal Owner Code-Review Vote

When `find_owners:submit_rule(S)` or `find_owners:submit_filter(In,Out)`
are applied, the default requirement is **+1** Code-Review
vote from at least one owner of every changed file.
To change this default level, define the plugin `minOwnerVoteLevel` parameter.

## OWNERS File Name

This plugin finds owners in default OWNERS files.
If a project has already used OWNERS files for other purpose,
the `ownersFileName` parameter can be used to change the default.

If ownersFileName is defined to something other than `OWNERS`,
the project should have such a specified file at the root directory.
Otherwise it would be considered a configuration or Gerrit server error.

## OWNERS Upload Validator

To check syntax of OWNERS files before they are uploaded,
set the following variable in `project.config` files.

```bash
[plugin "find-owners"]
    rejectErrorInOwners = true
```

## More Prolog Examples

### Call `submit_filter/2`

The simplest configuration adds to `rules.pl` of the root
`All-Projects` project.

```prolog
submit_filter(In, Out) :- find_owners:submit_filter(In, Out).
```

All projects will need at least +1 Code-Review vote from an owner of every changed files.

### Call `submit_rule/2`

To enabled owner approval requirement only for a project,
add the following to its `rules.pl`.
This example also changes the default and require **+2** owner Code-Review votes.

```prolog
submit_rule(S) :- find_owners:submit_rule(S, 2).
```

### Call `submit_filter/3`

To enabled owner approval requirement only for child projects,
add the following to `rules.pl`.
This example explicitly define the required owner Code-Review vote level to 1.

```prolog
submit_filter(In, Out) :- find_owners:submit_filter(In, Out, 1).
```

### Call `remove_may_label/2`

If a change does not need *owner approval*, the *optional* greyed out
`Owner-Approved` label might cause some confusion.
To remove that label, we can call `remove_may_label` at the end of all
`find_owners` submit filters, in `rules.pl` of the `All-Projects` project.

```prolog
submit_filter(In, Out) :-
  find_owners:submit_filter(In, Temp),
  find_owners:remove_may_label(Temp, Out).
```

### Call `remove_need_label/2`

To make all changes with some special property,
e.g., from the auto merger, exempt from owner approval,
we can add a special filter to `rules.pl` of the root `All-Projects` project.

```prolog
submit_filter(In, Out) :-
  opt_out_find_owners, % define-your-own-rule
  find_owners:remove_need_label(In, Out).
```

Please review the AOSP example again for more choices to opt-in or opt-out
of the find-owners submit filter.
