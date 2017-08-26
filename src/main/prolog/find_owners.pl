:- package find_owners.
'$init'.

%:- public add_may_label/2.
%:- public remove_may_label/2.
%:- public remove_need_label/2.
%:- public submit_filter/2.
%:- public submit_filter/3.
%:- public submit_rule/1.
%:- public submit_rule/2.

% Default required vote value is set in Checker,
% by reading the gerrit.config and project config files.
submit_rule(S) :- submit_rule(S, 0).
submit_filter(In, Out) :- submit_filter(In, Out, 0).

submit_rule(S, N) :-
  gerrit:default_submit(D),
  submit_filter(D, S, N).

submit_filter(In, Out, N) :-
  add_owner_approval_label(In, Out, N), !.

% Do nothing if add_owner_approval_label fails.
submit_filter(X, X, _).

owner_approved('Owner-Approved').

owner_approval_missing('Owner-Review-Vote').

owner_approval_label(X) :- owner_approved(X).
owner_approval_label(X) :- owner_approval_missing(X).

% Do nothing if X contains owner_approval_label.
add_owner_approval_label(In, Out, _) :-
  In =.. [submit|L],
  has_owner_approval_label(L), !,
  Out =.. [submit|L],
  !.

add_owner_approval_label(In, Out, N) :-
  In =.. [submit|L],
  % check_owner_approval(n, R) checks Code-Review votes with value >= n,
  % then set R to -1/0/1 to mean owner approval is missing/unneeded/complete.
  check_owner_approval(N, R), !,
  create_owner_approval_label(R, Label),
  Out =.. [submit|[Label|L]].

create_owner_approval_label(0, label(X, may(_))) :-
  owner_approved(X), !.
create_owner_approval_label(N, label(X, ok(user(1)))) :-
  N > 0, owner_approved(X), !.
% If owner approval is required and missing,
% use the owner_approval_missing(X) label and may(_) state to
% enable the Submit button. Front-end JavaScript should check
% the label and then block the submit or suggest users to
% add "Exempt-From-Owner-Approval:" to the commit message.
create_owner_approval_label(_, label(X, may(_))) :-
  owner_approval_missing(X).

has_owner_approval_label([label(X, _)|_]) :- owner_approval_label(X).
has_owner_approval_label([_|L]) :- has_owner_approval_label(L).

% Remove the grey label('Owner-Approval',may(_)) to avoid confusion.
remove_may_label(In, Out) :-
  In =.. [submit|L1],
  owner_approved(N),
  cleanup_label(label(N, may(_)), L1, L2),
  Out =.. [submit|L2],
  !.
remove_may_label(X, X).

% Remove label('Owner-Review-Vote',need(_)) for special changes.
remove_need_label(In, Out) :-
  In =.. [submit|L1],
  owner_approval_missing(N),
  cleanup_label(label(N, need(_)), L1, L2),
  Out =.. [submit|L2],
  !.
remove_need_label(X, X).

cleanup_label(_, [], []).
cleanup_label(N, [X|L1], L2) :- N = X, !, cleanup_label(N, L1, L2).
cleanup_label(N, [X|L1], [X|L2]) :- cleanup_label(N, L1, L2).

% Add label('Owner-Approval',may(_)) to skip owner approval check.
add_may_label(In, Out) :-
  remove_may_label(In, Tmp1),
  remove_need_label(Tmp1, Tmp2),
  Tmp2 =.. [submit|L],
  owner_approved(N),
  Out =.. [submit|[label(N, may(_))|L]],
  !.
add_may_label(X, X).
