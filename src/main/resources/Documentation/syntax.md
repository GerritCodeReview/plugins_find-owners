OWNERS File Syntax
==================

Owner approval is based on OWNERS files located in the same
repository and top of the _merge-to_ branch of a patch set.

### Syntax

```java
lines      := (SPACE* line? SPACE* EOL)*
line       := "set" SPACE+ "noparent"
           |  "per-file" SPACE+ globs SPACE* "=" SPACE* directives
           |  "include" SPACE+ (project SPACE* ":" SPACE*)? filePath
           |  comment
           |  directive
directives := directive (SPACE* "," SPACE* directive)*
directive  := email
           |  "*"
globs      := glob (SPACE* "," SPACE* glob)*
glob       := [a-zA-Z0-9_-*?.]+
comment    := "#" ANYCHAR*
email      := [^ @]+@[^ #]+
project    := a Gerrit project name without space or column character
filePath   := a file path name without space or column character
ANYCHAR    := any character but EOL
EOL        := end of line characters
SPACE      := any white space character
```

* An OWNERS file can include another file with the "include filePath"
  or "include project:filePath" line.
  When the "project:" is not specified, the OWNERS file's project is used.
  The included file is given with the "filePath".

* If the filePath starts with "/", it is an absolute path starting from
  the project root directory. Otherwise, the filePath is added a prefix
  of the current including file directory and then searched from the
  (given) project root directory.

* `per-file globs = directives` applies each `directive` only to files
  matching any of the `globs`.

* A 'glob' does not contain directory path.

* The email addresses should belong to registered Gerrit users.

* The `*` directive means that no owner is specified for the directory
  or file. Any user can approve that directory or file.

### Examples

```bash
  # a comment starts with # to EOL; leading spaces are ignored
set noparent  # do not inherit owners defined in parent directories
include P1/P2:/core/OWNERS  # include file core/OWNERS of project P1/P2
include ../base/OWNERS  # include with relative path to the current directory
per-file *.c,*.cpp = c@g.com,cpp@g.com  # special owners of .c and .cpp files
per-file *.c = x@g.com  # .c file owners are c@g.com and x@g.com
abc@g.com  # default owner, not for *.c and *.cpp files
xyz@g.com  # another default owner, not for *.c and *.cpp files
per-file README:*  # no specific owner for the README file

```
