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
  static final String  BOL = "^[\\s]*";
  static final String  EOL = "[\\s]*(#.*)?$";
  static final String  COMMA = "[\\s]*,[\\s]*";
  static final String  GLOB = "[^\\s,=]+"; // non-empty and without glob separator/terminator
  static final String  PER_FILE_LHS = BOL + "per-file[\\s]+([^=#]+)";
  // TODO: have a more precise email address pattern.
  static final String  EMAIL_OR_STAR = "([^\\s<>@,]+@[^\\s<>@#,]+|\\*)"; // email address or a "*"
  static final Pattern patComment = Pattern.compile("^" + EOL);
  static final Pattern patEmail = Pattern.compile(BOL + EMAIL_OR_STAR + EOL);
  static final Pattern patEmailList =
      Pattern.compile("^(" + EMAIL_OR_STAR + "(" + COMMA + EMAIL_OR_STAR + ")*)$");
  static final Pattern patGlobs =
      Pattern.compile("^(" + GLOB + "(" + COMMA + GLOB + ")*)$");
  static final Pattern patFile = Pattern.compile(BOL + "file:.*$");
  static final Pattern patNoParent = Pattern.compile(BOL + "set[\\s]+noparent" + EOL);
  static final Pattern patPerFile = Pattern.compile(PER_FILE_LHS + "=[\\s]*([^#]+)" + EOL);

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
    Matcher m;
    if (patNoParent.matcher(line).find()) {
      result.stopLooking = true;
    } else if ((m = patPerFile.matcher(line)).find()) {
      String globs = m.group(1).trim();
      if (!patGlobs.matcher(globs).find()) {
        result.errors.add(errorMsg(file, num, "wrong per-file pattern list", globs));
      } else {
        Matcher emailMatcher = patEmailList.matcher(m.group(2).trim());
        if (!emailMatcher.find()) {
          result.errors.add(errorMsg(
              file, num, "invalid email address in line", m.group(2).trim()));
        } else {
          String[] emails = emailMatcher.group(1).trim().split(COMMA);
          for (String glob : m.group(1).trim().split(COMMA)) {
            for (String email : emails) {
              Util.addToMap(result.owner2paths, email, dir + glob);
            }
          }
        }
      }
    } else if (patFile.matcher(line).find()) {
      result.warnings.add(warningMsg(file, num, "ignored", line));
    } else if (patComment.matcher(line).find()) {
      // ignore comment and empty lines.
    } else if ((m = patEmail.matcher(line)).find()) {
      Util.addToMap(result.owner2paths, m.group(1), dir);
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
