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
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Test Parser class */
@RunWith(JUnit4.class)
public class ParserTest {
  @Before
  public void setUp() {
    Config.setReportSyntaxError(true);
  }

  private static String mockedTestDir() {
    return "./d1/d2/";
  }

  private static Parser.Result testLine(String line) {
    Parser.Result result = new Parser.Result();
    Parser.parseLine(result, mockedTestDir(), "OWNERS", line, 3);
    return result;
  }

  private static String testLineWarningMsg(String line) {
    // expected warning message created by testLine(line)
    return Parser.warningMsg("OWNERS", 3, "ignored", line);
  }

  private static String testLineErrorMsg(String line) {
    // expected error message created by testLine(line)
    return Parser.errorMsg("OWNERS", 3, "ignored unknown line", line);
  }

  @Test
  public void emptyParserResult() {
    Parser.Result result = new Parser.Result();
    assertThat(result.stopLooking).isFalse();
    assertThat(result.warnings).isEmpty();
    assertThat(result.errors).isEmpty();
    assertThat(result.owner2paths).isEmpty();
  }

  @Test
  public void badLineTest() {
    String[] lines = {"actor", "a@b@c", "**", "per-files *.gyp", "a@b.com@c.com #..."};
    for (String s : lines) {
      Parser.Result result = testLine(s);
      assertThat(result.warnings).isEmpty();
      assertThat(result.errors).hasSize(1);
      String expected = testLineErrorMsg(s);
      assertThat(result.errors.get(0)).isEqualTo(expected);
    }
  }

  @Test
  public void commentLineTest() {
    String[] lines = {"", "   ", "# comment #data", "#any", "  # comment"};
    for (String s : lines) {
      Parser.Result result = testLine(s);
      assertThat(result.stopLooking).isFalse();
      assertThat(result.warnings).isEmpty();
      assertThat(result.errors).isEmpty();
      assertThat(result.owner2paths).isEmpty();
    }
  }

  @Test
  public void emailLineTest() {
    String[] lines = {"a_b-c3@google.com", "  x.y.z@gmail.com # comment", "*", "  *  # any user"};
    String[] emails = {"a_b-c3@google.com", "x.y.z@gmail.com", "*", "*"};
    for (int i = 0; i < lines.length; i++) {
      Parser.Result result = testLine(lines[i]);
      assertThat(result.owner2paths).hasSize(1);
      String[] paths = result.owner2paths.get(emails[i]).toArray(new String[1]);
      assertThat(paths.length).isEqualTo(1);
      assertThat(paths[0]).isEqualTo(mockedTestDir());
    }
  }

  @Test
  public void fileLineTest() {
    // file: directive is not implemented yet.
    String[] lines = {"file://owners", " file: //d1/owner", "file:owner #"};
    for (String s : lines) {
      Parser.Result result = testLine(s);
      String expected = testLineWarningMsg(s);
      assertThat(result.warnings).hasSize(1);
      assertThat(result.warnings.get(0)).isEqualTo(expected);
    }
  }

  @Test
  public void noParentLineTest() {
    String[] lines = {"set noparent", "  set  noparent", "set noparent # comment"};
    for (String line : lines) {
      Parser.Result result = testLine(line);
      assertThat(result.stopLooking).isTrue();
      testLine(line);
    }
  }

  @Test
  public void perFileGoodDirectiveTest() {
    String[] directives = {"abc@google.com#comment", "  *# comment", "  xyz@gmail.com # comment"};
    String[] emails = {"abc@google.com", "*", "xyz@gmail.com"};
    for (int i = 0; i < directives.length; i++) {
      String line = "per-file *test*.java=" + directives[i];
      Parser.Result result = testLine(line);
      String[] paths = result.owner2paths.get(emails[i]).toArray(new String[1]);
      assertThat(paths.length).isEqualTo(1);
      assertThat(paths[0]).isEqualTo(mockedTestDir() + "*test*.java");
    }
  }

  @Test
  public void perFileBadDirectiveTest() {
    String[] directives = {
      "file://OWNERS", " ** ", "a b@c .co", "a@b@c  #com", "a.<b>@zc#", " set  noparent "
    };
    String[] errors = {"file://OWNERS", "**", "a b@c .co", "a@b@c", "a.<b>@zc", "set  noparent"};
    for (int i = 0; i < directives.length; i++) {
      String line = "per-file *test*.c=" + directives[i];
      Parser.Result result = testLine(line);
      String expected = testLineErrorMsg(errors[i]);
      assertThat(result.warnings).isEmpty();
      assertThat(result.errors).hasSize(1);
      assertThat(result.errors.get(0)).isEqualTo(expected);
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
