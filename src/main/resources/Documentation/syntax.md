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
  matching any of the `globs`. Number of globs does not need to be equal
  to the number of directives.

* A 'glob' does not contain directory path.

* The email addresses should belong to registered Gerrit users.
  A group mailing address can be used as long as it is associated to
  a Gerrit user account.

* The `*` directive means that no owner is specified for the directory
  or file. Any user can approve that directory or files. All other specified
  owner email addresses for the same directory or files are ignored.

### Examples

```bash
  # A comment starts with # to EOL; leading spaces are ignored.
  # Empty lines are ignored.

set noparent  # Do not inherit owners defined in parent directories.
# By default, parent directories are searched upwardly, and all
# found OWNERS files are included until a "set noparent" is found.

include P1/P2:/core/OWNERS  # include file core/OWNERS of project P1/P2
include ../base/OWNERS  # include <this_owner_file_dir>/../base/OWNERS

per-file *.c,*.cpp = x@g.com,y@g.com,z@g.com
# x@, y@ and z@ are owners of all *.c or *.cpp files
per-file *.c = c@g.com
# c@, x@, y@ and z@ are owners of all *.c files
per-file *.xml,README:*
# no owner for *.xml and README files

abc@g.com  # one default owner
xyz@g.com  # another default owner
# abc@ and xyz@ are owners for all files in this directory,
# except *.c, *.cpp, *.xml, and README files
```
