OWNERS File Syntax
==================

Owner approval is based on OWNERS files located in the same
repository and top of the _merge-to_ branch of a patch set.

The syntax is:

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
