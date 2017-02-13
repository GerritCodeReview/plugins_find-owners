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

import static com.google.common.truth.Truth.assertThat;
import org.junit.Before;
import org.junit.Test;

/** Test Parser class */
public class ParserTest {
  private MockedOwnersDb db;

  @Before
  public void setUp() {
    db = new MockedOwnersDb();
  }

  private String mockedTestDir() {
    return "./d1/d2/";
  }

  private void testLine(String line) {
    db.resetData();
    String result = Parser.parseLine(db, mockedTestDir(), "OWNERS", line, 3);
    db.appendSavedData((result != null) ? (result + line) : line);
  }

  private String testLineOwnerPath(String line, String s1) {
    return testLineOwnerPath(line, s1, mockedTestDir());
  }

  private String testLineOwnerPath(String line, String s1, String s2) {
    // expected db.savedData created by testLine(line)
    // followed by call to addOwnerPathPair(s1, s2)
    return "s1:" + s1 + "\ns2:" + s2 + "\n" + line;
  }

  private String testLineWarningMsg(String line) {
    // expected warning message created by testLine(line)
    return Parser.warningMsg("OWNERS", 3, "ignored", line);
  }

  private String testLineErrorMsg(String line) {
    // expected error message created by testLine(line)
    return Parser.errorMsg("OWNERS", 3, "ignored unknown line", line);
  }

  @Test
  public void badLineTest() {
    String[] lines = {"actor", "a@b@c", "**", "per-files *.gyp",
                      "a@b.com@c.com #..."};
    for (String s : lines) {
      testLine(s);
      String expected = testLineErrorMsg(s) + s;
      assertThat(db.getSavedData()).isEqualTo(expected);
    }
  }

  @Test
  public void commentLineTest() {
    String[] lines = {"", "   ", "# comment #data", "#any", "  # comment"};
    for (String s : lines) {
      testLine(s);
      assertThat(db.getSavedData()).isEqualTo(s);
    }
  }

  @Test
  public void emailLineTest() {
    String[] lines = {"a_b-c3@google.com", "  x.y.z@gmail.com # comment",
                      "*", "  *  # any user"};
    String[] emails = {"a_b-c3@google.com", "x.y.z@gmail.com", "*", "*"};
    for (int i = 0; i < lines.length; i++) {
      testLine(lines[i]);
      String expected = testLineOwnerPath(lines[i], emails[i]);
      assertThat(db.getSavedData()).isEqualTo(expected);
    }
  }

  @Test
  public void fileLineTest() {
    // file: directive is not implemented yet.
    String[] lines = {"file://owners", " file: //d1/owner", "file:owner #"};
    for (String s : lines) {
      testLine(s);
      String expected = testLineWarningMsg(s) + s;
      assertThat(db.getSavedData()).isEqualTo(expected);
    }
  }

  @Test
  public void noParentLineTest() {
    String[] lines = {"set noparent", "  set  noparent",
                      "set noparent # comment"};
    for (String line : lines) {
      db.resetData();
      assertThat(db.stopLooking.size()).isEqualTo(0);
      testLine(line);
      assertThat(db.stopLooking.size()).isEqualTo(1);
      assertThat(db.stopLooking.contains(mockedTestDir())).isEqualTo(true);
      assertThat(db.getSavedData()).isEqualTo(line);
    }
  }

  @Test
  public void perFileGoodDirectiveTest() {
    String[] directives = {"abc@google.com#comment",
                           "  *# comment",
                           "  xyz@gmail.com # comment"};
    String[] emails = {"abc@google.com", "*", "xyz@gmail.com"};
    for (int i = 0; i < directives.length; i++) {
      String line = "per-file *test*.java=" + directives[i];
      testLine(line);
      String expected =
          testLineOwnerPath(line, emails[i], mockedTestDir() + "*test*.java");
      assertThat(db.getSavedData()).isEqualTo(expected);
    }
  }

  @Test
  public void perFileBadDirectiveTest() {
    // TODO: test "set noparent" after perf-file.
    String[] directives =
        {"file://OWNERS", " ** ", "a b@c .co", "a@b@c  #com", "a.<b>@zc#"};
    String[] errors =
        {"file://OWNERS", "**", "a", "a@b@c", "a.<b>@zc"};
    for (int i = 0; i < directives.length; i++) {
      String line = "per-file *test*.c=" + directives[i];
      testLine(line);
      String expected = testLineErrorMsg(errors[i]) + line;
      assertThat(db.getSavedData()).isEqualTo(expected);
    }
  }

  @Test
  public void errorMsgTest() {
    String file = "./OWNERS";
    int n = 5;
    String msg = "error X";
    String line = "a@@a";
    String location = file + ":" + n + ": " + msg + ": [" + line + "]";
    String error = "Error: " + location;
    String warning = "Warning: " + location;
    assertThat(Parser.errorMsg(file, n, msg, line)).isEqualTo(error);
    assertThat(Parser.warningMsg(file, n, msg, line)).isEqualTo(warning);
  }
}
