REST API to Get Owners Info
===========================

Any Gerrit UI clients or tools can use the
`/changes/<id>/owners` API to get
OWNERS information of a change.

### Request

```bash
GET /changes/<id>/owners HTTP/1.0
```

The `<id>` is a Gerrit change ID. This API can have two parameters:

* **patchset**: is the patchset number of the change to look for changed files.
  By default the current (latest) patchset of given change is used.

* **debug**: can be set to true or false to override the configuration variable
  **addDebugMsg**.

* **nocache**: can be set to true to collect owerns info without using the cached OwnersDb.

For example,

```bash
http://<gerrit_server>/changes/29/owners?debug=true&patchset=3
```

### Response

This API returns a JSON object with the following attributes:

* **minOwnerVoteLevel**: is 1 by default, but could be set to 2.
   It is the minimal Code-Review vote value all changed files must get
   from at least one owner to make a change submittable.

* **addDebugMsg**: is false by default. In a development/test configuration,
   this attribute could be true, to tell a client to display extra debug
   information.

* **change**: is the change number.

* **patchset**: is the change patchset number.

* **owner\_revision**: is the revision where OWNERS files were searched.
   It is the tip of the given change's destination branch.
   Due to caching this revision might be behind recent branches changes.

* **dbgmsgs**: returned only when addDebugMsg is true,
   a JSON object with the following members:

    * **user**: the change's creator.

    * **project**: the change's project name.

    * **branch**: the change's destination branch name.

    * **errors**: error messages from OWNERS files.

    * **path2owners**:
      a map from directory path or file glob to an array of owner emails.
      Note that `*` is a special owner email address.
      It means that there is no owner and anyone can be the owner.
      Included directories are those affected by the change revision.

    * **owner2paths**:
      a map from owner email to an array of directory path or file glob.
      This is opposite to the path2owners map.

    * **logs**:
      trace messages during the search of OWNERS files.

* **file2owners**: a map from each file path in the change patchset to
   an array of the file's owner emails.

* **reviewers**: an array of current reviewer emails.

* **owners**: an array of owner info objects.
   Each owner info object has "email" and "weights" attributes.
   The weights attribute is an array of integers like [n1, n2, n3],
   which are the number of level 1, 2, 3+ controlled files.
   The email attributes are the keys in the owner2paths map.
   This owners array is sorted by owner weights.
   Users should try to pick owners with more weights to review a change.

* **files**: an alphabetically sorted files changed
   in the given change patchset.
