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
import java.util.Arrays;
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
 * One Parser object should be created to parse only one OWNERS file.
 * It keeps repoManager, project, branch, and filePath of the OWNERS
 * file so it can find files that are included by OWNERS.
 *
 * The usage pattern is:
 *   Parser parser = new Parser(repoManager, project, branch, repoFilePath);
 *   String content = OwnersDb.getRepoFile(readFiles, repoManager, project,
 *                                         branch, repoFilePath, logs);
 *   Parser.Result result = parser.parseFile(dirPath, content);
 *
 * OWNERS file syntax, semantics, and examples are included in syntax.md.
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
  private static final String  FILE_PATH = "([^\\s:#]+)";

  private static final String  PROJECT_AND_FILE = PROJECT_NAME + FILE_PATH;

  private static final String  SET_NOPARENT = "set[\\s]+noparent";

  private static final String  FILE_DIRECTIVE = "file:[\\s]*" + PROJECT_AND_FILE;
  private static final String  INCLUDE_OR_FILE = "(file:[\\s]*|include[\\s]+)";

  // Simple input lines with 0 or 1 sub-pattern.
  private static final Pattern PAT_COMMENT = Pattern.compile(BOL + EOL);
  private static final Pattern PAT_EMAIL = Pattern.compile(BOL + EMAIL_OR_STAR + EOL);
  private static final Pattern PAT_INCLUDE =
      Pattern.compile(BOL + INCLUDE_OR_FILE + PROJECT_AND_FILE + EOL);
  private static final Pattern PAT_NO_PARENT = Pattern.compile(BOL + SET_NOPARENT + EOL);

  // Patterns to match trimmed globs, emails, and set noparent in per-file lines.
  private static final Pattern PAT_PER_FILE_OWNERS =
      Pattern.compile("^(" + EMAIL_LIST + "|" + SET_NOPARENT + "|" + FILE_DIRECTIVE + ")$");
  private static final Pattern PAT_GLOBS =
      Pattern.compile("^(" + GLOB + "(" + COMMA + GLOB + ")*)$");
  // PAT_PER_FILE matches a line to two groups: (1) globs, (2) emails
  // Trimmed 1st group should match PAT_GLOBS;
  // trimmed 2nd group should match PAT_PER_FILE_OWNERS.
  private static final Pattern PAT_PER_FILE =
      Pattern.compile(BOL + "per-file[\\s]+([^=#]+)=[\\s]*([^#]+)" + EOL);
  // Fetch the include/file part of a line with correct syntax.
  private static final Pattern PAT_INCLUDE_OR_FILE =
      Pattern.compile("^.*(" + INCLUDE_OR_FILE + PROJECT_AND_FILE + ")" + EOL);

  // A parser keeps current readFiles, repoManager, project, branch,
  // included file path, and debug/trace logs.
  private Map<String, String> readFiles;
  private GitRepositoryManager repoManager;
  private String branch; // All owners files are read from the same branch.
  private IncludeStack stack; // a stack of including files.
  private List<String> logs; // Keeps debug/trace messages.
  private Map<String, Result> savedResults; // projectName:filePath => Parser.Result

  static class IncludeStack {
    Deque<String> projectName; // project/repository name of included file
    Deque<String> filePath; // absolute or relative path of included file
    Set<String> allFiles; // to detect recursive inclusion quickly

    IncludeStack(String project, String file) {
      projectName = new ArrayDeque<>();
      filePath = new ArrayDeque<>();
      allFiles = new HashSet<>();
      push(project, file);
    }

    void push(String project, String file) {
      projectName.push(project);
      filePath.push(file);
      allFiles.add(getFileKey(project, file));
    }

    void pop() {
      allFiles.remove(getFileKey(currentProject(), currentFile()));
      projectName.pop();
      filePath.pop();
    }

    String currentProject() {
      return projectName.peek();
    }

    String currentFile() {
      return filePath.peek();
    }

    boolean contains(String project, String file) {
      return allFiles.contains(getFileKey(project, file));
    }
  }

  Parser(Map<String, String> readFiles, GitRepositoryManager repoManager,
      String project, String branch, String file) {
    init(readFiles, repoManager, project, branch, file, new ArrayList<>());
  }

  Parser(Map<String, String> readFiles, GitRepositoryManager repoManager,
      String project, String branch, String file, List<String> logs) {
    init(readFiles, repoManager, project, branch, file, logs);
  }

  private void init(Map<String, String> readFiles, GitRepositoryManager repoManager,
      String project, String branch, String file, List<String> logs) {
    this.readFiles = readFiles;
    this.repoManager = repoManager;
    this.branch = branch;
    this.logs = logs;
    stack = new IncludeStack(project, normalizedRepoDirFilePath(".", file));
    savedResults = new HashMap<>();
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
    return s.trim().replaceAll("[\\s]+", " ").replaceAll("[\\s]*:[\\s]*", ":");
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

  static String getIncludeOrFile(String line) {
    Matcher m = PAT_INCLUDE_OR_FILE.matcher(line);
    return m.matches() ? removeExtraSpaces(m.group(1)) : "";
  }

  static class Result {
    boolean stopLooking; // if this file contains set noparent
    Set<String> warnings; // unique warning messages
    Set<String> errors; // unique error messages
    Map<String, Set<String>> owner2paths; // maps from owner email to pathGlobs
    Set<String> noParentGlobs; // per-file dirpath+glob with "set noparent"

    Result() {
      stopLooking = false;
      warnings = new HashSet<>();
      errors = new HashSet<>();
      owner2paths = new HashMap<>();
      noParentGlobs = new HashSet<>();
    }

    void append(Result r, String dir, boolean addAll) {
      // addAll is true when the Result is from an include statements.
      // It is false for the included result of "file:" directive, which
      // only collects owner emails, not per-file or set noparent statement.
      warnings.addAll(r.warnings);
      errors.addAll(r.errors);
      if (addAll) {
        stopLooking = stopLooking || r.stopLooking;
        for (String glob : r.noParentGlobs) {
          noParentGlobs.add(dir + glob);
        }
      }
      for (String key : r.owner2paths.keySet()) {
        for (String path : r.owner2paths.get(key)) {
          // In an included file, top-level owener emails have empty dir path.
          if (path.isEmpty() || addAll) {
            Util.addToMap(owner2paths, key, dir + path);
          }
        }
      }
    }
  }

  /**
   * Parse given lines of an OWNERS files; return parsed Result.
   * It can recursively call itself to parse included files.
   *
   * @param dir is the directory that contains "changed files" of a CL,
   *        not necessarily the OWNERS or included file directory.
   *        "owners" found in lines control changed files in 'dir'.
   *        'dir' ends with '/' or is empty when parsing an included file.
   * @param  lines are the source lines of the file to be parsed.
   * @return the parsed data
   */
  Result parseFile(String dir, String[] lines) {
    Result result = new Result();
    int n = 0;
    for (String line : lines) {
      parseLine(result, dir, line, ++n);
    }
    return result;
  }

  Result parseFile(String dir, String content) {
    return parseFile(dir, content.split("\\R"));
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
   * Parse a line in OWNERS file and add parsed info into result.
   * This function should be called only by parseFile and Parser unit tests.
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
    if (isNoParent(line)) {
      result.stopLooking = true;
    } else if (isComment(line)) {
      // ignore comment and empty lines.
    } else if ((email = parseEmail(line)) != null) {
      Util.addToMap(result.owner2paths, email, dir); // here dir is not a glob
    } else if ((globsAndOwners = parsePerFile(line)) != null) {
      String[] dirGlobs = globsAndOwners[0].split(COMMA, -1);
      String directive = globsAndOwners[1];
      if (directive.equals(Parser.TOK_SET_NOPARENT)) {
        // per-file globs = set noparent
        for (String glob : dirGlobs) {
          result.noParentGlobs.add(dir + glob);
        }
      } else {
        List<String> ownerEmails;
        if ((parsedKPF = parseInclude(stack.currentProject(), directive)) == null) {
          // per-file globs = ownerEmails
          ownerEmails = Arrays.asList(directive.split(COMMA, -1));
        } else {
          // per-file globs = file: projectFile
          ownerEmails = new ArrayList<>();
          Result r = new Result();
          includeFile(r, "", num, parsedKPF, false);
          for (String key : r.owner2paths.keySet()) {
            for (String path : r.owner2paths.get(key)) {
              if (path.isEmpty()) {
                ownerEmails.add(key);
                break;
              }
            }
          }
        }
        for (String glob : dirGlobs) {
          for (String owner: ownerEmails) {
            Util.addToMap(result.owner2paths, owner, dir + glob);
          }
        }
      }
    } else if ((parsedKPF = parseInclude(stack.currentProject(), line)) != null) {
      includeFile(result, dir, num, parsedKPF, parsedKPF[0].equals("include"));
    } else {
      result.errors.add(errorMsg(stack.currentFile(), num, "ignored unknown line", line));
    }
  }

  /**
   * Find and parse an included file and append data to the 'result'.
   * For an 'include' statement, parsed data is all appended to the given result parameter.
   * For a 'file:' statement or directive, only owner emails are appended.
   * If the project+file name is found in the stored result set, the stored result is reused.
   * The inclusion is skipped if the to be included file is already on the including file stack.
   *
   * @param result to where the included file data should be added.
   * @param dir the including file's directory or glob.
   * @param num source code line number
   * @param parsedKPF the parsed line of include or file directive.
   * @param addAll to add all parsed data into result or not.
   */
  private void includeFile(Result result, String dir, int num, String[] parsedKPF, boolean addAll) {
    String keyword = parsedKPF[0];
    String project = parsedKPF[1];
    String file = parsedKPF[2];
    String includeKPF = keyword + ":" + getFileKey(project, file);
    // Like C/C++ #include, when f1 includes f2 with a relative file path,
    // use f1's directory, not 'dir', as the base for relative path.
    // 'dir' is the directory of OWNERS file, which might include f1 indirectly.
    String repoFile = normalizedRepoDirFilePath(Util.getParentDir(stack.currentFile()), file);
    if (stack.contains(project, repoFile)) {
      logs.add("parseLine:errorRecursion:" + includeKPF);
      result.errors.add(errorMsg(stack.currentFile(), num, "recursive include", includeKPF));
      return;
    }
    String savedResultKey = getFileKey(project, repoFile);
    Result includedFileResult = savedResults.get(savedResultKey);
    if (null != includedFileResult) {
      logs.add("parseLine:useSaved:" + includeKPF);
    } else {
      stack.push(project, repoFile);
      logs.add("parseLine:" + includeKPF);
      String content =
          OwnersDb.getRepoFile(readFiles, repoManager, project, branch, repoFile, logs);
      if (content != null && !content.isEmpty()) {
        includedFileResult = parseFile("", content);
      } else {
        logs.add("parseLine:" + keyword + ":()");
        includedFileResult = new Result();
      }
      stack.pop();
      savedResults.put(savedResultKey, includedFileResult);
    }
    result.append(includedFileResult, dir, addAll);
  }

  // Build a readable key or output string for a (project, file) pair.
  static String getFileKey(String project, String file) {
    return project + ":" + file;
  }

  // Build a readable key or output string for a (project, branch, file) tuple.
  static String getFileKey(String project, String branch, String file) {
    return project + ":" + branch + ":" + file;
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
