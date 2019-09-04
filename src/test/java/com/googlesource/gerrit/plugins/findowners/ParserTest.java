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

import com.google.common.flogger.FluentLogger;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Test Parser class */
@RunWith(JUnit4.class)
public class ParserTest {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  @Rule public Watcher watcher = new Watcher(logger);

  private static String mockedTestDir() {
    return "./d1/d2/";
  }

  private static String mockedProject() {
    return "myTestProject";
  }

  private static Parser.Result testLine(String line) {
    Parser.Result result = new Parser.Result();
    // Single line parser tests do not need a repository.
    Parser parser = new Parser(mockedProject(), "master", "OWNERS");
    parser.parseLine(result, mockedTestDir(), line, 3);
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
      String expected = testLineErrorMsg(s);
      assertThat(result.errors).containsExactly(expected);
    }
  }

  @Test
  public void appendResultTest() {
    String s1 = "a@b@c";
    String s2 = "**";
    String e1 = testLineErrorMsg(s1);
    String e2 = testLineErrorMsg(s2);
    String w1 = testLineWarningMsg("w1");
    String w2 = testLineWarningMsg("w2");
    String b1 = "d1/*.c";
    String b2 = "d2/*.java";
    Parser.Result r1 = testLine(s1);
    Parser.Result r2 = testLine(s2);
    assertThat(r1.warnings).isEmpty();
    assertThat(r2.warnings).isEmpty();
    assertThat(r1.noParentGlobs).isEmpty();
    assertThat(r2.noParentGlobs).isEmpty();
    assertThat(r1.errors).containsExactly(e1);
    assertThat(r2.errors).containsExactly(e2);
    r1.warnings.add(w1);
    r2.warnings.add(w2);
    r1.noParentGlobs.add(b1);
    r2.noParentGlobs.add(b2);
    assertThat(r1.owner2paths).isEmpty();
    assertThat(r2.owner2paths).isEmpty();
    r2.append(r1, "", true);
    assertThat(r1.owner2paths).isEmpty();
    assertThat(r2.owner2paths).isEmpty();
    assertThat(r2.warnings).containsExactly(w2, w1);
    assertThat(r2.noParentGlobs).containsExactly(b2, b1);
    assertThat(r1.noParentGlobs).containsExactly(b1);
    assertThat(r2.errors).containsExactly(e2, e1);
    r1.append(r2, "", true);
    assertThat(r1.owner2paths).isEmpty();
    assertThat(r2.owner2paths).isEmpty();
    // warnings, errors, and noParentGlobs are sets of strings.
    // containsExactly does not check order of elements.
    assertThat(r1.warnings).containsExactly(w1, w2);
    assertThat(r1.warnings).containsExactly(w2, w1);
    assertThat(r1.noParentGlobs).containsExactly(b2, b1);
    assertThat(r1.errors).containsExactly(e1, e2);
    assertThat(r1.errors).containsExactly(e2, e1);
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
      assertThat(paths).hasLength(1);
      assertThat(paths[0]).isEqualTo(mockedTestDir());
    }
  }

  @Test
  public void fileLineTest() {
    // file: statement should work like include.
    String[] lines = {"file://owners", " file: //d1/owner", "file:owner #"};
    for (String s : lines) {
      Parser.Result result = testLine(s);
      assertThat(result.warnings).isEmpty();
      assertThat(result.errors).isEmpty();
    }
    testOneFileLine("P0", "  file: //common/OWNERS #comment", "P0", "//common/OWNERS");
    testOneFileLine("P1", "file: Other :  /Group ", "Other", "/Group");
    testOneFileLine("P2", "file:  /Common/Project: OWNER", "/Common/Project", "OWNER");
    testOneFileLine("P3", "  file: \tP2/D2:/D3/F.txt", "P2/D2", "/D3/F.txt");
    testOneFileLine("P4", "  file: \tP2/D2://D3/F.txt", "P2/D2", "//D3/F.txt");
    testOneFileLine("P5", "\t file: \t P2/D2:\t/D3/F2.txt\n", "P2/D2", "/D3/F2.txt");
    testOneFileLine("P6", "file:  ../d1/d2/F   \n", "P6", "../d1/d2/F");
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
    String[] directives = {
      "abc@google.com#comment",
      "  *# comment",
      "  xyz@gmail.com # comment",
      "a@g.com  ,  xyz@gmail.com , *  # comment",
      "*,*#comment",
      "  a@b,c@d  ",
      "  set   noparent  ",
      "\tset\t\tnoparent\t",
      "file://java.owners",
      "  file:  p1/p2  :  /OWNERS  "
    };
    String[] globsList = {"*", "*,*.c", "  *test*.java , *.cc, *.cpp  ", "*.bp,*.mk ,A*  "};
    for (String directive : directives) {
      for (String globs : globsList) {
        String line = "per-file " + globs + "=" + directive;
        Parser.Result result = testLine(line);
        String[] directiveList = directive.replaceAll("#.*$", "").trim().split(Parser.COMMA, -1);
        String[] globList = globs.trim().split(Parser.COMMA);
        Arrays.sort(globList);
        String[] owners = Parser.parsePerFileOwners(line);
        assertThat(owners).hasLength(directiveList.length);
        for (String email : owners) {
          String e = email.trim();
          assertThat(result.stopLooking).isFalse();
          if (e.equals(Parser.TOK_SET_NOPARENT)) {
            assertThat(result.owner2paths).isEmpty(); // no other owners in this per-file
            assertThat(result.noParentGlobs).hasSize(globList.length);
            for (String glob : globList) {
              assertThat(result.noParentGlobs).contains(mockedTestDir() + glob);
            }
          } else if (e.startsWith("file:")) {
            // If per-file has file: directive, it cannot have any other directive.
            assertThat(e).isEqualTo(Parser.removeExtraSpaces(directive));
            assertThat(owners).hasLength(1);
          } else {
            String[] paths = result.owner2paths.get(e).toArray(new String[1]);
            assertThat(paths).hasLength(globList.length); // should not work for "set noparent"
            Arrays.sort(paths);
            for (int g = 0; g < globList.length; g++) {
              assertThat(paths[g]).isEqualTo(mockedTestDir() + globList[g]);
            }
          }
        }
      }
    }
  }

  @Test
  public void perFileBadDirectiveTest() {
    String[] directives = {
      " ** ",
      "a b@c .co",
      "a@b@c  #com",
      "a.<b>@zc#",
      " , a@b  ",
      "a@b, , c@d  #",
      "a@b, set noparent",
      "a@b, file://java.owners",
      "*,file:OWNERS"
    };
    for (String directive : directives) {
      String line = "per-file *test*.c=" + directive;
      Parser.Result result = testLine(line);
      String expected = testLineErrorMsg(line);
      assertThat(result.warnings).isEmpty();
      assertThat(result.errors).containsExactly(expected);
    }
  }

  private static void testOneIncludeOrFileLine(
      String project, String line, String keyword, String projectName, String filePath) {
    String[] results = Parser.parseInclude(project, line);
    assertThat(results).hasLength(3);
    assertThat(results[0]).isEqualTo(keyword);
    assertThat(results[1]).isEqualTo(projectName);
    assertThat(results[2]).isEqualTo(filePath);
  }

  private static void testOneFileLine(
      String project, String line, String projectName, String filePath) {
    testOneIncludeOrFileLine(project, line, "file", projectName, filePath);
  }

  private static void testOneIncludeLine(
      String project, String line, String projectName, String filePath) {
    testOneIncludeOrFileLine(project, line, "include", projectName, filePath);
  }

  @Test
  public void includeLineTest() {
    testOneIncludeLine("P0", "  include /common/OWNERS #comment", "P0", "/common/OWNERS");
    testOneIncludeLine("P1", "include Other :  /Group ", "Other", "/Group");
    testOneIncludeLine("P2", "include  /Common/Project: OWNER", "/Common/Project", "OWNER");
    testOneIncludeLine("P3", "  include \tP2/D2:/D3/F.txt", "P2/D2", "/D3/F.txt");
    testOneIncludeLine("P4", "\t include \t P2/D2:\t/D3/F2.txt\n", "P2/D2", "/D3/F2.txt");
    testOneIncludeLine("P5", "include  ../d1/d2/F   \n", "P5", "../d1/d2/F");
  }

  @Test
  public void getIncludeOrFileTest() {
    Map<String, String> tests = new HashMap<>(); // map from input to expected result
    tests.put("", "");
    tests.put("wrong input", "");
    tests.put("INCLUDE X", "");
    tests.put("include //f2.txt # ", "include //f2.txt");
    tests.put("  include  P1/P2:  ../f1 # ", "include P1/P2:../f1");
    tests.put("  file://f3 # ", "file://f3");
    tests.put("file:  P1:f3", "file:P1:f3");
    tests.put("  per-file *.c,file.c = file:  /OWNERS  # ", "file:/OWNERS");
    tests.put("per-file  *=file:P1/P2:  /O# ", "file:P1/P2:/O");
    for (String line : tests.keySet()) {
      assertThat(Parser.getIncludeOrFile(line)).isEqualTo(tests.get(line));
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
