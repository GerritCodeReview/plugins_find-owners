// Copyright (C) 2017 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.googlesource.gerrit.plugins.findowners;

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.server.git.GitRepositoryManager;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parse lines in an OWNERS file and put them into an OwnersDb.
 *
 * <p>OWNERS file syntax:
 *
 * <pre>
 * lines      := (\s* line? \s* "\n")*
 * line       := "set" \s+ "noparent"
 *            | "per-file" \s+ globs \s* "=" \s* directives
 *            | "file:" \s* glob
 *            | "include" SPACE+ (project SPACE* ":" SPACE*)? filePath
 *            | comment
 *            | directive
 * project    := a Gerrit absolute project path name without space or column character
 * filePath   := a file path name without space or column character
 * directives := directive (comma directive)*
 * directive  := email_address
 *            |  "*"
 * globs      := glob (comma glob)*
 * glob       := [a-zA-Z0-9_-*?.]+
 * comma      := \s* "," \s*
 * comment    := "#" [^"\n"]*
 * </pre>
 *
 * <p>The "file:" directive is not implemented yet.
 *
 * <p>"per-file globs = directives" applies each directive to files matching any of the globs.
 * A glob does not contain directory path.
 */
class Parser {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  // Globs and emails are separated by commas with optional spaces around a comma.
  protected static final String COMMA = "[\\s]*,[\\s]*"; // used in unit tests

  // Separator for project and file paths in an include line.
  private static final String  COLUMN = "[\\s]*:[\\s]*"; // project:file

  private static final String  BOL = "^[\\s]*";          // begin-of-line
  private static final String  EOL = "[\\s]*(#.*)?$";    // end-of-line
  private static final String  GLOB = "[^\\s,=]+";       // a file glob

  // TODO: have a more precise email address pattern.
  private static final String  EMAIL_OR_STAR = "([^\\s<>@,]+@[^\\s<>@#,]+|\\*)";

  // A Gerrit project name followed by a column and optional spaces.
  private static final String  PROJECT_NAME = "([^\\s:]+" + COLUMN + ")?";

  // A relative or absolute file path name without any column or space character.
  private static final String  FILE_PATH = "([^\\s:]+)";

  private static final String  PROJECT_AND_FILE = PROJECT_NAME + FILE_PATH;

  // Simple input lines with 0 or 1 sub-pattern.
  private static final Pattern PAT_COMMENT = Pattern.compile(BOL + EOL);
  private static final Pattern PAT_EMAIL = Pattern.compile(BOL + EMAIL_OR_STAR + EOL);
  private static final Pattern PAT_FILE = Pattern.compile(BOL + "file:.*" + EOL);
  private static final Pattern PAT_INCLUDE =
      Pattern.compile(BOL + "include[\\s]+" + PROJECT_AND_FILE + EOL);
  private static final Pattern PAT_NO_PARENT = Pattern.compile(BOL + "set[\\s]+noparent" + EOL);

  // Patterns to match trimmed globs and emails in per-file lines.
  private static final Pattern PAT_EMAIL_LIST =
      Pattern.compile("^(" + EMAIL_OR_STAR + "(" + COMMA + EMAIL_OR_STAR + ")*)$");
  private static final Pattern PAT_GLOBS =
      Pattern.compile("^(" + GLOB + "(" + COMMA + GLOB + ")*)$");
  // PAT_PER_FILE matches a line to two groups: (1) globs, (2) emails
  // Trimmed 1st group should match PAT_GLOBS; trimmed 2nd group should match PAT_EMAIL_LIST.
  private static final Pattern PAT_PER_FILE =
      Pattern.compile(BOL + "per-file[\\s]+([^=#]+)=[\\s]*([^#]+)" + EOL);

  // A parser keeps current repoManager, project, branch, included file path, and debug/trace logs.
  private GitRepositoryManager repoManager;
  private String branch; // All owners files are read from the same branch.
  private Deque<String[]> includeStack; // Keeps include stack of [projectName,filePath].
  private Set<String> included; // Keeps included files of projectName:filePath
  private List<String> logs; // Keeps debug/trace messages.

  Parser(GitRepositoryManager repoManager, String project, String branch, String file) {
    init(repoManager, project, branch, file, new ArrayList<>());
  }

  Parser(GitRepositoryManager repoManager,
      String project, String branch, String file, List<String> logs) {
    init(repoManager, project, branch, file, logs);
  }

  private void init(GitRepositoryManager repoManager,
      String project, String branch, String file, List<String> logs) {
    this.repoManager = repoManager;
    this.branch = branch;
    this.logs = logs;
    includeStack = new ArrayDeque<>();
    included = new HashSet<>();
    pushProjectFilePaths(project, normalizedRepoDirFilePath(".", file));
  }

  static boolean isComment(String line) {
    return PAT_COMMENT.matcher(line).matches();
  }

  static boolean isFile(String line) {
    return PAT_FILE.matcher(line).matches();
  }

  static boolean isInclude(String line) {
    return PAT_INCLUDE.matcher(line).matches();
  }

  static boolean isGlobs(String line) {
    return PAT_GLOBS.matcher(line).matches();
  }

  static boolean isNoParent(String line) {
    return PAT_NO_PARENT.matcher(line).matches();
  }

  static String parseEmail(String line) {
    Matcher m = Parser.PAT_EMAIL.matcher(line);
    return m.matches() ? m.group(1).trim() : null;
  }

  static String[] parseInclude(String project, String line) {
    Matcher m = Parser.PAT_INCLUDE.matcher(line);
    if (!m.matches()) {
      return null;
    }
    String[] parts = new String[]{m.group(1), m.group(2).trim()};
    if (parts[0] != null && parts[0].length() > 0) {
      // parts[0] has project name followed by ':'
      parts[0] = parts[0].split(COLUMN, -1)[0].trim();
    } else {
      parts[0] = project; // default project name
    }
    return parts;
  }

  static String[] parsePerFile(String line) {
    Matcher m = PAT_PER_FILE.matcher(line);
    if (!m.matches() || !isGlobs(m.group(1).trim())
        || !PAT_EMAIL_LIST.matcher(m.group(2).trim()).matches()) {
      return null;
    }
    return new String[]{m.group(1).trim(), m.group(2).trim()};
  }

  static String[] parsePerFileEmails(String line) {
    String[] globsAndEmails = parsePerFile(line);
    return (globsAndEmails != null) ? globsAndEmails[1].split(COMMA, -1) : null;
  }

  static class Result {
    boolean stopLooking; // if this file contains set noparent
    List<String> warnings; // warning messages
    List<String> errors; // error messages
    Map<String, Set<String>> owner2paths; // maps from owner email to pathGlobs

    Result() {
      stopLooking = false;
      warnings = new ArrayList<>();
      errors = new ArrayList<>();
      owner2paths = new HashMap<>();
    }

    void append(Result r) {
      warnings.addAll(r.warnings);
      errors.addAll(r.warnings);
      stopLooking |= r.stopLooking; // included file's "set noparent" applies to the including file
      for (String key : r.owner2paths.keySet()) {
        for (String dir : r.owner2paths.get(key)) {
          Util.addToMap(owner2paths, key, dir);
        }
      }
    }
  }

  // Parse an OWNERS file or included file content in lines.
  // "dir" is the directory that contains "changed files" of a CL,
  // not necessarily the OWNERS or included file directory.
  // "owners" listed in lines control changed files in 'dir' not
  // necessrily the files in the directory containing "lines".
  Result parseFile(String dir, String[] lines) {
    Result result = new Result();
    int n = 0;
    for (String line : lines) {
      parseLine(result, dir, line, ++n);
    }
    return result;
  }

  private String currentProject() {
    return includeStack.peek()[0];
  }

  private String currentFilePath() {
    return includeStack.peek()[1];
  }

  private String combineProjectAndFile(String project, String file) {
    return project + ":" + file;
  }

  private void pushProjectFilePaths(String project, String file) {
    includeStack.push(new String[]{project, file});
    included.add(combineProjectAndFile(project, file));
  }

  private void popProjectFilePaths() {
    includeStack.pop();
  }

  private String normalizedRepoDirFilePath(String dir, String path) {
    try {
      return Util.normalizedRepoDirFilePath(dir, path);
    } catch (IOException e) {
      String msg = "Fail to normalized path " + dir + " / " + path;
      logger.atSevere().withCause(e).log(msg);
      logs.add(msg + ":" + e.getMessage());
      return dir + "/" + path;
    }
  }

  /**
   * Parse a line in OWNERS file and add info to OwnersDb.
   *
   * @param result a Result object to keep parsed info.
   * @param dir the path to OWNERS file directory.
   * @param line the source line.
   * @param num the line number.
   */
  void parseLine(Result result, String dir, String line, int num) {
    String email;
    String[] globsAndEmails;
    String[] projectAndFile;
    if (isNoParent(line)) {
      result.stopLooking = true;
    } else if (isComment(line)) {
      // ignore comment and empty lines.
    } else if ((email = parseEmail(line)) != null) {
      Util.addToMap(result.owner2paths, email, dir);
    } else if ((globsAndEmails = parsePerFile(line)) != null) {
      String[] emails = globsAndEmails[1].split(COMMA, -1);
      for (String glob : globsAndEmails[0].split(COMMA, -1)) {
        for (String e : emails) {
          Util.addToMap(result.owner2paths, e, dir + glob);
        }
      }
    } else if (isFile(line)) {
      // file: directive is parsed but ignored.
      result.warnings.add(warningMsg(currentFilePath(), num, "ignored", line));
      logs.add("parseLine:file");
    } else if ((projectAndFile = parseInclude(currentProject(), line)) != null) {
      String project = projectAndFile[0];
      String file = projectAndFile[1];
      String includePath = combineProjectAndFile(project, file);
      // Like C/C++ #include, when f1 includes f2 with a relative path projectAndFile[1],
      // use f1's directory, not 'dir', as the base for relative path.
      // 'dir' is the directory of OWNERS file, which might include f1 indirectly.
      String repoFile = normalizedRepoDirFilePath(Util.getParentDir(currentFilePath()), file);
      String repoProjectFile = combineProjectAndFile(project, repoFile);
      if (included.contains(repoProjectFile)) {
        logs.add("parseLine:skip:include:" + includePath);
      } else {
        pushProjectFilePaths(project, repoFile);
        logs.add("parseLine:include:" + includePath);
        String content =
            OwnersDb.getRepoFile(repoManager, project, branch, repoFile, logs);
        if (content != null && !content.isEmpty()) {
          result.append(parseFile(dir, content.split("\\R+")));
        } else {
          logs.add("parseLine:include:(empty)");
        }
        popProjectFilePaths();
      }
    } else {
      result.errors.add(errorMsg(currentFilePath(), num, "ignored unknown line", line));
    }
  }

  private static String createMsgLine(String prefix, String file, int n, String msg, String line) {
    return prefix + file + ":" + n + ": " + msg + ": [" + line + "]";
  }

  static String errorMsg(String file, int n, String msg, String line) {
    return createMsgLine("Error: ", file, n, msg, line);
  }

  static String warningMsg(String file, int n, String msg, String line) {
    return createMsgLine("Warning: ", file, n, msg, line);
  }
}
