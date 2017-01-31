REST API to Get Owners Info
===========================

Any Gerrit UI clients or tools can use the
`plugins/find-owners/change/<id>` API to get
OWNERS information of a change.

### Request

```bash
GET /plugins/find-owners/change/<id> HTTP/1.0
```

The `<id>` is a Gerrit change ID. This API can have two parameters:

* **patch**: is the patch set number of the change to look for changed files.
  By default the current (latest) patch set of given change is used.

* **debug**: can be set to true or false to override the configuration variable
  **addDebugMsg**.

For example,

```bash
http://.../plugins/find-owners/change/29?debug=true&patch=3
```

### Response

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
