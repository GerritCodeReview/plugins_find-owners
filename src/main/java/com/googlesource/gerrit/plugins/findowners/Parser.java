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
 * The syntax, semantics, and some examples are included in syntax.md.
 */
class Parser {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  // Artifical owner token for "set noparent" when used in per-file.
  protected static final String TOK_SET_NOPARENT = "set noparent";

  // Globs and emails are separated by commas with optional spaces around a comma.
  protected static final String COMMA = "[\\s]*,[\\s]*"; // used in unit tests

  // Separator for project and file paths in an include line.
  private static final String  COLUMN = "[\\s]*:[\\s]*"; // project:file

  private static final String  BOL = "^[\\s]*";          // begin-of-line
  private static final String  EOL = "[\\s]*(#.*)?$";    // end-of-line
  private static final String  GLOB = "[^\\s,=]+";       // a file glob

  // TODO: have a more precise email address pattern.
  private static final String  EMAIL_OR_STAR = "([^\\s<>@,]+@[^\\s<>@#,]+|\\*)";
  private static final String  EMAIL_LIST =
      "(" + EMAIL_OR_STAR + "(" + COMMA + EMAIL_OR_STAR + ")*)";

  // A Gerrit project name followed by a column and optional spaces.
  private static final String  PROJECT_NAME = "([^\\s:]+" + COLUMN + ")?";

  // A relative or absolute file path name without any column or space character.
  private static final String  FILE_PATH = "([^\\s:]+)";

  private static final String  PROJECT_AND_FILE = PROJECT_NAME + FILE_PATH;

  private static final String  SET_NOPARENT = "set[\\s]+noparent";

  // Simple input lines with 0 or 1 sub-pattern.
  private static final Pattern PAT_COMMENT = Pattern.compile(BOL + EOL);
  private static final Pattern PAT_EMAIL = Pattern.compile(BOL + EMAIL_OR_STAR + EOL);
  private static final Pattern PAT_INCLUDE =
      Pattern.compile(BOL + "(file:[\\s]*|include[\\s]+)" + PROJECT_AND_FILE + EOL);
  private static final Pattern PAT_NO_PARENT = Pattern.compile(BOL + SET_NOPARENT + EOL);

  // Patterns to match trimmed globs, emails, and set noparent in per-file lines.
  private static final Pattern PAT_PER_FILE_OWNERS =
      Pattern.compile("^(" + EMAIL_LIST + "|(" + SET_NOPARENT + "))$");
  private static final Pattern PAT_GLOBS =
      Pattern.compile("^(" + GLOB + "(" + COMMA + GLOB + ")*)$");
  // PAT_PER_FILE matches a line to two groups: (1) globs, (2) emails
  // Trimmed 1st group should match PAT_GLOBS;
  // trimmed 2nd group should match PAT_PER_FILE_OWNERS.
  private static final Pattern PAT_PER_FILE =
      Pattern.compile(BOL + "per-file[\\s]+([^=#]+)=[\\s]*([^#]+)" + EOL);

  // A parser keeps current repoManager, project, branch, included file path, and debug/trace logs.
  private GitRepositoryManager repoManager;
  private String branch; // All owners files are read from the same branch.
  private Deque<IncludeInfo> includeStack; // Keeps info of nested included files.
  private Set<String> includedFiles; // Keeps keyword:projectName:filePath
  private List<String> logs; // Keeps debug/trace messages.

  static class IncludeInfo {
    String projectName; // project/repository name of included file
    String filePath; // absolute or relative path of included file
    boolean includeAll; // include also "set noparent" and "per-file"

    IncludeInfo(String projectName, String filePath, boolean includeAll) {
      this.projectName = projectName;
      this.filePath = filePath;
      this.includeAll = includeAll;
    }
  }

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
    includedFiles = new HashSet<>();
    // The first parsed OWNERS file is treated as an "include" file.
    pushIncludeInfo(project, normalizedRepoDirFilePath(".", file), true);
  }

  static boolean isComment(String line) {
    return PAT_COMMENT.matcher(line).matches();
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
    String keyword = m.group(1).trim();
    if (keyword.equals("file:")) {
      keyword = "file";
    }
    String projectName = m.group(2);
    if (projectName != null && projectName.length() > 1) {
      // PROJECT_NAME ends with ':'
      projectName = projectName.split(COLUMN, -1)[0].trim();
    } else {
      projectName = project; // default project name
    }
    return new String[]{keyword, projectName, m.group(3).trim()};
  }

  static String removeExtraSpaces(String s) {
    return s.trim().replaceAll("[\\s]+", " ");
  }

  static String[] parsePerFile(String line) {
    Matcher m = PAT_PER_FILE.matcher(line);
    if (!m.matches() || !isGlobs(m.group(1).trim())
        || !PAT_PER_FILE_OWNERS.matcher(m.group(2).trim()).matches()) {
      return null;
    }
    return new String[]{removeExtraSpaces(m.group(1)), removeExtraSpaces(m.group(2))};
  }

  static String[] parsePerFileOwners(String line) {
    String[] globsAndOwners = parsePerFile(line);
    return (globsAndOwners != null) ? globsAndOwners[1].split(COMMA, -1) : null;
  }

  static class Result {
    boolean stopLooking; // if this file contains set noparent
    List<String> warnings; // warning messages
    List<String> errors; // error messages
    Map<String, Set<String>> owner2paths; // maps from owner email to pathGlobs
    Set<String> noParentGlobs; // per-file dirpath+glob with "set noparent"

    Result() {
      stopLooking = false;
      warnings = new ArrayList<>();
      errors = new ArrayList<>();
      owner2paths = new HashMap<>();
      noParentGlobs = new HashSet<>();
    }

    void append(Result r) {
      warnings.addAll(r.warnings);
      errors.addAll(r.errors);
      stopLooking |= r.stopLooking; // included file's "set noparent" applies to the including file
      for (String key : r.owner2paths.keySet()) {
        Util.addAllToMap(owner2paths, key, r.owner2paths.get(key));
      }
      noParentGlobs.addAll(r.noParentGlobs);
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

  private boolean currentIncludeAll() {
    return includeStack.peek().includeAll;
  }

  private String currentProjectName() {
    return includeStack.peek().projectName;
  }

  private String currentFilePath() {
    return includeStack.peek().filePath;
  }

  private String makeIncludedKey(String projectName, String filePath, boolean includeAll) {
    return (includeAll ? "include:" : "file:") + projectName + ":" + filePath;
  }

  private void pushIncludeInfo(String projectName, String filePath, boolean includeAll) {
    includeStack.push(new IncludeInfo(projectName, filePath, includeAll));
    includedFiles.add(makeIncludedKey(projectName, filePath, includeAll));
  }

  private boolean hasBeenIncluded(String projectName, String filePath, boolean includeAll) {
    if (includedFiles.contains(makeIncludedKey(projectName, filePath, true))) {
      return true;
    }
    return (!includeAll && includedFiles.contains(makeIncludedKey(projectName, filePath, false)));
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
    String[] globsAndOwners;
    String[] parsedKPF; // parsed keyword, projectName, filePath
    boolean includeAll = currentIncludeAll();
    if (isNoParent(line)) {
      if (!includeAll) { // ignored if included by the "file:" statement
        logs.add("parseLine:ignore:" + line);
      } else {
        result.stopLooking = true;
      }
    } else if (isComment(line)) {
      // ignore comment and empty lines.
    } else if ((email = parseEmail(line)) != null) {
      Util.addToMap(result.owner2paths, email, dir);
    } else if ((globsAndOwners = parsePerFile(line)) != null) {
      if (!includeAll) { // ignored if included by the "file:" statement
        logs.add("parseLine:ignore:" + line);
      } else {
        String[] owners = globsAndOwners[1].split(COMMA, -1);
        for (String glob : globsAndOwners[0].split(COMMA, -1)) {
          for (String e : owners) {
            if (e.equals(Parser.TOK_SET_NOPARENT)) {
              result.noParentGlobs.add(dir + glob);
            } else {
              Util.addToMap(result.owner2paths, e, dir + glob);
            }
          }
        }
      }
    } else if ((parsedKPF = parseInclude(currentProjectName(), line)) != null) {
      String keyword = parsedKPF[0];
      String project = parsedKPF[1];
      String file = parsedKPF[2];
      String includeKPF = keyword + ":" + project + ":" + file;
      boolean nestedIncludeAll = includeAll && !keyword.equals("file");
      // Like C/C++ #include, when f1 includes f2 with a relative file path,
      // use f1's directory, not 'dir', as the base for relative path.
      // 'dir' is the directory of OWNERS file, which might include f1 indirectly.
      String repoFile = normalizedRepoDirFilePath(Util.getParentDir(currentFilePath()), file);
      if (hasBeenIncluded(project, repoFile, nestedIncludeAll)) {
        logs.add("parseLine:skip:" + includeKPF);
      } else {
        pushIncludeInfo(project, repoFile, nestedIncludeAll);
        logs.add("parseLine:" + includeKPF);
        String content =
            OwnersDb.getRepoFile(repoManager, project, branch, repoFile, logs);
        if (content != null && !content.isEmpty()) {
          result.append(parseFile(dir, content.split("\\R+")));
        } else {
          logs.add("parseLine:" + keyword + ":(empty)");
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
