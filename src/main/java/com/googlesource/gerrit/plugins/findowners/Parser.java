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

import java.util.ArrayList;
import java.util.HashMap;
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
 *            | comment
 *            | directive
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
  // Globs and emails are separated by commas with optional spaces around a comma.
  protected static final String COMMA = "[\\s]*,[\\s]*"; // used in unit tests

  private static final String  BOL = "^[\\s]*";          // begin-of-line
  private static final String  EOL = "[\\s]*(#.*)?$";    // end-of-line
  private static final String  GLOB = "[^\\s,=]+";       // a file glob

  // TODO: have a more precise email address pattern.
  private static final String  EMAIL_OR_STAR = "([^\\s<>@,]+@[^\\s<>@#,]+|\\*)";

  // Simple input lines with 0 or 1 sub-pattern.
  private static final Pattern patComment = Pattern.compile(BOL + EOL);
  private static final Pattern patEmail = Pattern.compile(BOL + EMAIL_OR_STAR + EOL);
  private static final Pattern patFile = Pattern.compile(BOL + "file:.*" + EOL);
  private static final Pattern patNoParent = Pattern.compile(BOL + "set[\\s]+noparent" + EOL);

  // Patterns to mach trimmed globs and emails in per-file lines.
  private static final Pattern patEmailList =
      Pattern.compile("^(" + EMAIL_OR_STAR + "(" + COMMA + EMAIL_OR_STAR + ")*)$");
  private static final Pattern patGlobs =
      Pattern.compile("^(" + GLOB + "(" + COMMA + GLOB + ")*)$");
  // patPerFile matches a line to two groups: (1) globs, (2) emails
  // Trimmed 1st group should match patGlobs; trimmed 2nd group should match patEmailList.
  private static final Pattern patPerFile =
      Pattern.compile(BOL + "per-file[\\s]+([^=#]+)=[\\s]*([^#]+)" + EOL);

  static boolean isComment(String line) {
    return patComment.matcher(line).matches();
  }

  static boolean isFile(String line) {
    return patFile.matcher(line).matches();
  }

  static boolean isGlobs(String line) {
    return patGlobs.matcher(line).matches();
  }

  static boolean isNoParent(String line) {
    return patNoParent.matcher(line).matches();
  }

  static String parseEmail(String line) {
    Matcher m = Parser.patEmail.matcher(line);
    return m.matches() ? m.group(1).trim() : null;
  }

  static String[] parsePerFile(String line) {
    Matcher m = patPerFile.matcher(line);
    if (!m.matches() || !isGlobs(m.group(1).trim())
        || !patEmailList.matcher(m.group(2).trim()).matches()) {
      return null;
    }
    String[] parts = new String[2];
    parts[0] = m.group(1).trim();
    parts[1] = m.group(2).trim();
    return parts;
  }

  static String[] parsePerFileEmails(String line) {
    String[] globsAndEmails = parsePerFile(line);
    return (globsAndEmails != null) ? globsAndEmails[1].split(COMMA) : null;
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
  }

  static Result parseFile(String dir, String file, String[] lines) {
    Result result = new Result();
    int n = 0;
    for (String line : lines) {
      Parser.parseLine(result, dir, file, line, ++n);
    }
    return result;
  }

  /**
   * Parse a line in OWNERS file and add info to OwnersDb.
   *
   * @param result a Result object to keep parsed info.
   * @param dir the path to OWNERS file directory.
   * @param file the OWNERS file path.
   * @param line the source line.
   * @param num the line number.
   */
  static void parseLine(Result result, String dir, String file, String line, int num) {
    // comment and file: directive are parsed but ignored.
    String email;
    String[] globsAndEmails;
    if (isNoParent(line)) {
      result.stopLooking = true;
    } else if (isComment(line)) {
      // ignore comment and empty lines.
    } else if ((email = parseEmail(line)) != null) {
      Util.addToMap(result.owner2paths, email, dir);
    } else if ((globsAndEmails = parsePerFile(line)) != null) {
      String[] emails = globsAndEmails[1].split(COMMA);
      for (String glob : globsAndEmails[0].split(COMMA)) {
        for (String e : emails) {
          Util.addToMap(result.owner2paths, e, dir + glob);
        }
      }
    } else if (isFile(line)) {
      result.warnings.add(warningMsg(file, num, "ignored", line));
    } else {
      result.errors.add(errorMsg(file, num, "ignored unknown line", line));
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
