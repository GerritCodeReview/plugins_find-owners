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
 * lines     := (\s* line? \s* "\n")*
 * line      := "set noparent"
 *           | "per-file" \s+ glob \s* "=" \s* directive
 *           | "file:" glob
 *           | comment
 *           | directive
 * directive := email_address
 *           |  "*"
 * glob      := [a-zA-Z0-9_-*?]+
 * comment   := "#" [^"\n"]*
 * </pre>
 *
 * <p>The "file:" directive is not implemented yet.
 *
 * <p>"per-file glob = directive" applies directive only to files matching glob. glob does not
 * contain directory path.
 */
class Parser {
  static final Pattern patComment = Pattern.compile("^ *(#.*)?$");
  // TODO: have a more precise email address pattern.
  static final Pattern patEmail = // email address or a "*"
      Pattern.compile("^ *([^ <>@]+@[^ <>@#]+|\\*) *(#.*)?$");
  static final Pattern patFile = Pattern.compile("^ *file:.*$");
  static final Pattern patNoParent = Pattern.compile("^ *set +noparent(( |#).*)?$");
  static final Pattern patPerFile = Pattern.compile("^ *per-file +([^= ]+) *= *([^#]+).*$");

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
    if (patNoParent.matcher(line).find()) {
      result.stopLooking = true;
    } else if (patPerFile.matcher(line).find()) {
      Matcher m = patPerFile.matcher(line);
      m.find();
      parseDirective(result, dir + m.group(1), file, m.group(2).trim(), num);
    } else if (patFile.matcher(line).find()) {
      result.warnings.add(warningMsg(file, num, "ignored", line));
    } else if (patComment.matcher(line).find()) {
      // ignore comment and empty lines.
    } else {
      parseDirective(result, dir, file, line, num);
    }
  }

  private static void parseDirective(
      Result result, String pathGlob, String file, String line, int num) {
    // A directive is an email address or "*".
    if (patEmail.matcher(line).find()) {
      Matcher m = patEmail.matcher(line);
      m.find();
      Util.addToMap(result.owner2paths, m.group(1), pathGlob);
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
