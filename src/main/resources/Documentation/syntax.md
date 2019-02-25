OWNERS File Syntax
==================

Owner approval is based on OWNERS files located in the same
repository and top of the _merge-to_ branch of a patch set.

### Syntax

```java
lines       := (SPACE* line? SPACE* EOL)*
line        := comment
            |  noparent
            |  ownerEmail
            |  "per-file" SPACE+ globs SPACE* "=" SPACE* owners
            |  include projectFile
include     := "include" SPACE+
            |  "file:" SPACE*
projectFile := (project SPACE* ":" SPACE*)? filePath
comment     := "#" ANYCHAR*
noparent    := "set" SPACE+ "noparent"
ownerEmail  := email
            |  "*"
owners      := noparent
            |  "file:" SPACE* projectFile
            |  ownerEmail (SPACE* "," SPACE* ownerEmail)*
globs       := glob (SPACE* "," SPACE* glob)*
glob        := [a-zA-Z0-9_-*?.]+
email       := [^ @]+@[^ #]+
project     := a Gerrit project name without space or column character
filePath    := a file path name without space or column character
ANYCHAR     := any character but EOL
EOL         := end of line characters
SPACE       := any white space character
```

* An OWNERS file can include another file with the `include filePath`
  or `include project:filePath` line.
  When the `project:` is not specified, the OWNERS file's project is used.
  The included file is given with the `filePath`.

* If the filePath starts with "/", it is an absolute path starting from
  the project root directory. Otherwise, the filePath is added a prefix
  of the current including file directory and then searched from the
  (given) project root directory.

* The `file: ...` statement is similar to `include ...`
    * To be compatible to Google's old OWNERS `file:` directive,
      the `file: ...` statement ignores the `per-file ...` and
      `set noparent` lines in the directly or indirectly included files.
      The `include ...` statement takes all statements in the
      directly included file.
    * The `include ...` statement is more general and should be
      used in new OWNERS files.
        * Google's `file: <file>` directive only includes files
          in the same repository. The syntax does not accept a
          "project path" like the `include ...` statement.
        * Google's `file: <file>` directive requires the include file
          to have a name with the 'OWNERS' substring,
          but `include ...` statement can include any file.

* Recursive inclusion (loop) is an error, which is detected
  and logged as run-time error. The inclusion is then ignored.

* Repeated inclusion (non-loop) is allowed but redundant.
  The effect is similar to duplicated statements in an OWNERS file.

* An OWNERS file inherits rules in OWNERS files of parent directories
  by default, unless `set noparent` is specified.
  Note that when a file is included into other files,
  it does not inherit rules in its parent directory's OWNERS files.

* A "glob" is UNIX globbing pathname without the directory path.

* `per-file globs = owners` applies `owners` only to files
  matching any of the `globs`. Number of globs does not need to be equal
  to the number of `ownerEmail` in `owners`.

* `per-file globs = set noparent` is like `set noparent` but applies only to
  files matching any of the `globs`. OWNERS files in parent directories
  are not considered for files matching those globs. Even default ownerEmails
  specified in the current OWNERS file are not included.

* Without the `per-file globs = set noparent`, all non-per-file
  ownerEmails also apply to files matching those globs.

* `per-file globs = file: <projectFile>` is equivalent to
  `per-file globs = emails`, where the emails are collected
  from top-level emails collected from `file: <projectFile>`.

* The email addresses should belong to registered Gerrit users.
  A group mailing address can be used as long as it is associated to
  a Gerrit user account.

* The `*` is a special ownerEmail meaning that
  any user can approve that directory or files.

### Examples

```bash
  # A comment starts with # to EOL; leading spaces are ignored.
  # Empty lines are ignored.

set noparent  # Do not inherit owners defined in parent directories.
# By default, parent directories are searched upwardly, and all
# found OWNERS files are included until a "set noparent" is found.

include P1/P2:/core/OWNERS  # include file core/OWNERS of project P1/P2
include ../base/OWNERS  # include <this_owner_file_dir>/../base/OWNERS
include /OWNERS  # include OWNERS at root directory of this repository

per-file *.c, *.cpp = x@g.com, y@g.com, z@g.com
# x@, y@ and z@ are owners of all *.c or *.cpp files
per-file *.c = c@g.com
# c@, x@, y@ and z@ are owners of all *.c files
per-file *.xml,README=*,x@g.com
# in additional to x@g.com, anyone can be owner for *.xml and README files

abc@g.com  # one default owner
xyz@g.com  # another default owner
# abc@ and xyz@ are owners for all files in this directory,
# including *.c, *.cpp, *.xml, and README files

per-file *.txt,*.java = set noparent
per-file *.txt,*.java = jj@g.com
# Only jj@g.com is the owner of *.txt and *.java files,
# not even abc@g.com or xyz@g.com
```
