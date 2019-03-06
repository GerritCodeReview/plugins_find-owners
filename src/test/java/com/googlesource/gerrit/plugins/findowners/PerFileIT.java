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
import com.google.gerrit.acceptance.PushOneCommit;
import org.junit.Test;

/** Test find-owners plugin features related to per-file statements. */
public class PerFileIT extends FindOwnersIT {

  @Test
  public void ownersPerFileTest() throws Exception {
    addFile("1", "OWNERS", "per-file *.c=x@x\na@a\nc@c\nb@b\n");
    // Add "t.c" file, which has per-file owner x@x, and a@a, b@b, c@c.
    PushOneCommit.Result c2 = createChange("2", "t.c", "Hello!");
    String ownerA = ownerJson("a@a");
    String ownerB = ownerJson("b@b");
    String ownerC = ownerJson("c@c");
    String ownerABC = "owners:[ " +ownerA + ", " + ownerB + ", " + ownerC;
    String ownerX = ownerJson("x@x");
    assertThat(getOwnersResponse(c2)).contains(ownerABC + ", " + ownerX + " ], files:[ t.c ]");
    // Add "t.txt" file, which has only global default owners.
    PushOneCommit.Result c3 = createChange("3", "t.txt", "Test!");
    assertThat(getOwnersResponse(c3)).contains(ownerABC + " ], files:[ t.txt ]");
  }

  @Test
  public void includePerFileTest() throws Exception {
    // Test included file with per-file, which affects the including file.
    PushOneCommit.Result c1 = addFile("1", "d1/d1/OWNERS", "d1d1@g\nper-file OWNERS=d1d1o@g\n");
    PushOneCommit.Result c2 = addFile("2", "d1/OWNERS", "d1@g\nper-file OWNERS=d1o@g\n");
    PushOneCommit.Result c3 = addFile("3", "d2/d1/OWNERS", "d2d1@g\ninclude ../../d1/d1/OWNERS\n");
    PushOneCommit.Result c4 = addFile("4", "d2/OWNERS", "d2@g\nper-file OWNERS=d2o@g");
    // Files that match per-file globs now inherit global default owners.
    assertThat(getOwnersResponse(c1)).contains(
        "{ ./d1/d1/OWNERS:[ d1@g, d1d1@g, d1d1o@g, d1o@g ] }");
    assertThat(getOwnersResponse(c2)).contains("{ ./d1/OWNERS:[ d1@g, d1o@g ] }");
    assertThat(getOwnersResponse(c3)).contains(
        "{ ./d2/d1/OWNERS:[ d1d1@g, d1d1o@g, d2@g, d2d1@g, d2o@g ] }");
    assertThat(getOwnersResponse(c4)).contains("{ ./d2/OWNERS:[ d2@g, d2o@g ] }");
  }
}
