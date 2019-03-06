// Copyright (C) 2019 The Android Open Source Project
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
import com.google.gerrit.acceptance.PushOneCommit;
import org.junit.Test;

/** Test find-owners plugin features related to inheritance and set noparent statements. */
public class InheritanceIT extends FindOwnersIT {

  @Test
  public void includePerFileNoParentTest() throws Exception {
    // Test included file with per-file and set noparent, which affects the including file.
    PushOneCommit.Result c1 = addFile("1", "d1/d1/OWNERS",
        "d1d1@g\nper-file OW* = set noparent\nper-file OWNERS=d1d1o@g\n");
    PushOneCommit.Result c2 = addFile("2", "d1/OWNERS",
        "d1@g\nper-file OWNERS=d1o@g\nper-file * = set noparent\n");
    PushOneCommit.Result c3 = addFile( "3", "d2/d1/OWNERS",
        "per-file O*S=d2d1o@g\nd2d1@g\ninclude ../../d1/d1/OWNERS\n");
    PushOneCommit.Result c4 = addFile("4",
        "d2/OWNERS", "d2@g\nper-file OWNERS=d2o@g\nper-file *S=set  noparent \n");
    // Files that match per-file globs with set noparent do not inherit global default owners.
    // But include directive can include more per-file owners as in c3.
    assertThat(getOwnersResponse(c1)).contains("{ ./d1/d1/OWNERS:[ d1d1o@g ] }");
    assertThat(getOwnersResponse(c2)).contains("{ ./d1/OWNERS:[ d1o@g ] }");
    assertThat(getOwnersResponse(c3)).contains("{ ./d2/d1/OWNERS:[ d1d1o@g, d2d1o@g ] }");
    assertThat(getOwnersResponse(c4)).contains("{ ./d2/OWNERS:[ d2o@g ] }");
  }

  @Test
  public void includeNoParentTest() throws Exception {
    // Test included file with noparent, which affects the inheritance of including file.
    PushOneCommit.Result c1 = addFile("1", "d1/d1/OWNERS", "d1d1@g\nset noparent\n");
    PushOneCommit.Result c2 = addFile("2", "d1/d2/OWNERS", "d1d2@g\n");
    PushOneCommit.Result c3 = addFile("3", "d1/OWNERS", "d1@g\n");
    PushOneCommit.Result c4 = addFile("4", "d2/d1/OWNERS", "d2d1@g\ninclude ../../d1/d1/OWNERS\n");
    PushOneCommit.Result c5 = addFile("5", "d2/d2/OWNERS", "d2d2@g\ninclude ../../d1/d2/OWNERS");
    PushOneCommit.Result c6 = addFile("6", "d2/OWNERS", "d2@g\n");
    // d1/d1/OWNERS sets noparent, does not inherit d1/OWNERS
    assertThat(getOwnersResponse(c1)).contains("{ ./d1/d1/OWNERS:[ d1d1@g ] }");
    // d1/d2/OWNERS inherits d1/OWNERS
    assertThat(getOwnersResponse(c2)).contains("{ ./d1/d2/OWNERS:[ d1@g, d1d2@g ] }");
    assertThat(getOwnersResponse(c3)).contains("{ ./d1/OWNERS:[ d1@g ] }");
    // d2/d1/OWNERS includes d1/d1/OWNERS, does not inherit d1/OWNERS or d2/OWNERS
    assertThat(getOwnersResponse(c4)).contains("{ ./d2/d1/OWNERS:[ d1d1@g, d2d1@g ] }");
    // d2/d2/OWNERS includes d1/d1/OWNERS, inherit d2/OWNERS but not d1/OWNERS
    assertThat(getOwnersResponse(c5)).contains("{ ./d2/d2/OWNERS:[ d1d2@g, d2@g, d2d2@g ] }");
    assertThat(getOwnersResponse(c6)).contains("{ ./d2/OWNERS:[ d2@g ] }");
  }

  @Test
  public void subOwnersFileTest() throws Exception {
    // Add OWNERS file in root and subdirectories.
    addFile("1", "OWNERS", "x@x\n");
    addFile("2", "d1/OWNERS", "a@a\n");
    addFile("3", "d2/OWNERS", "y@y\n");
    addFile("4", "d3/OWNERS", "b@b\nset noparent\n");
    addFile("5", "d4/OWNERS", "z@z\ninclude ../d2/OWNERS");
    // Add "t.c" file, which is not owned by subdirectory owners.
    PushOneCommit.Result c2 = createChange("c2", "t.c", "Hello!");
    String ownerA = ownerJson("a@a");
    String ownerX = ownerJson("x@x");
    assertThat(getOwnersResponse(c2)).contains("owners:[ " + ownerX + " ], files:[ t.c ]");
    // Add "d1/t.c" file, which is owned by ./d1 and root owners.
    PushOneCommit.Result c3 = createChange("c3", "d1/t.c", "Hello!");
    String ownerX010 = ownerJson("x@x", 0, 1, 0);
    assertThat(getOwnersResponse(c3))
        .contains("owners:[ " + ownerA + ", " + ownerX010 + " ], files:[ d1/t.c ]");
    // Add "d2/t.c" file, which is owned by ./d2 and root owners.
    PushOneCommit.Result c4 = createChange("c4", "d2/t.c", "Hello!");
    String ownerY = ownerJson("y@y");
    assertThat(getOwnersResponse(c4))
        .contains("owners:[ " + ownerY + ", " + ownerX010 + " ], files:[ d2/t.c ]");
    // Add "d2/d1/t.c" file, which is owned by ./d2 and root owners.
    PushOneCommit.Result c5 = createChange("c5", "d2/d1/t.c", "Hello!");
    assertThat(getOwnersResponse(c5)).contains(
        "owners:[ " + ownerY + ", " + ownerX010 + " ], files:[ d2/d1/t.c ]");
    // Add "d3/t.c" file, which is owned only by ./d3 owners due to "set noparent".
    PushOneCommit.Result c6 = createChange("c6", "d3/t.c", "Hello!");
    String ownerB = ownerJson("b@b");
    assertThat(getOwnersResponse(c6)).contains("owners:[ " + ownerB + " ], files:[ d3/t.c ]");
    // Add "d3/d1/t.c" file, which is owned only by ./d3 owners due to "set noparent".
    PushOneCommit.Result c7 = createChange("c7", "d3/d1/t.c", "Hello!");
    assertThat(getOwnersResponse(c7)).contains(
        "owners:[ " + ownerB + " ], files:[ d3/d1/t.c ]");
    // Add "d4/t.c" file, which is owned by ./d4 and ./d2 owners, but not root owners.
    PushOneCommit.Result c8 = createChange("c8", "d4/t.c", "Hello!");
    String ownerZ = ownerJson("z@z");
    assertThat(getOwnersResponse(c8)).contains(
        "owners:[ " + ownerY + ", " + ownerZ + ", " + ownerX010 + " ], files:[ d4/t.c ]");
  }
}
