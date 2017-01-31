# Configuration Examples

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

```
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

Add the following to global `gerrit.config`
or a project's `project.config` file,
to debug the find-owners plugin.

```
[plugin "find-owners"]
    addDebugMsg = true
```
