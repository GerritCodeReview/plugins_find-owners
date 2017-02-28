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

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parse lines in an OWNERS file and put them into an OwnersDb.
 *
 * <p>OWNERS file syntax:<pre>
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
 * <p> The "file:" directive is not implemented yet.
 * <p> "per-file glob = directive" applies directive only to files
 *     matching glob. glob does not contain directory path.
 */
class Parser {
  static final Pattern PatComment = Pattern.compile("^ *(#.*)?$");
  // TODO: have a more precise email address pattern.
  static final Pattern PatEmail =  // email address or a "*"
      Pattern.compile("^ *([^ <>@]+@[^ <>@#]+|\\*) *(#.*)?$");
  static final Pattern PatFile = Pattern.compile("^ *file:.*$");
  static final Pattern PatNoParent =
      Pattern.compile("^ *set +noparent(( |#).*)?$");
  static final Pattern PatPerFile =
      Pattern.compile("^ *per-file +([^= ]+) *= *([^ #]+).*$");

  /**
   *  Parse a line in OWNERS file and add info to OwnersDb.
   *
   *  @param db   an OwnersDb to keep parsed info.
   *  @param path the path of OWNERS file.
   *  @param file the OWNERS file path.
   *  @param line the source line.
   *  @param num  the line number.
   *  @return error message string or null.
   */
  static String parseLine(OwnersDb db, String path,
                          String file, String line, int num) {
    // comment and file: directive are parsed but ignored.
    if (PatNoParent.matcher(line).find()) {
      db.stopLooking.add(path);
      return null;
    }
    if (PatPerFile.matcher(line).find()) {
      Matcher m = PatPerFile.matcher(line);
      m.find();
      return parseDirective(db, path + m.group(1), file, m.group(2), num);
    }
    if (PatFile.matcher(line).find()) {
      return warningMsg(file, num, "ignored", line);
    }
    // ignore comment and empty lines.
    return (PatComment.matcher(line).find())
        ? null : parseDirective(db, path, file, line, num);
  }

  private static String parseDirective(OwnersDb db, String pathGlob,
                                       String file, String line, int num) {
    // A directive is an email address or "*".
    if (PatEmail.matcher(line).find()) {
      Matcher m = PatEmail.matcher(line);
      m.find();
      db.addOwnerPathPair(m.group(1), pathGlob);
      return null;
    }
    return errorMsg(file, num, "ignored unknown line", line);
  }

  private static String createMsgLine(
      String prefix, String file, int n, String msg, String line) {
    return prefix + file + ":" + n + ": " + msg + ": [" + line + "]";
  }

  static String errorMsg(String file, int n, String msg, String line) {
    return createMsgLine("Error: ", file, n, msg, line);
  }

  static String warningMsg(String file, int n, String msg, String line) {
    return createMsgLine("Warning: ", file, n, msg, line);
  }
}
