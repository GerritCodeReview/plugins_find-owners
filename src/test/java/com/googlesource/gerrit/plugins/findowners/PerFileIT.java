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

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.acceptance.PushOneCommit;
import com.google.gerrit.acceptance.TestPlugin;
import org.junit.Rule;
import org.junit.Test;

/** Test find-owners plugin features related to per-file statements. */
@TestPlugin(name = "find-owners", sysModule = "com.googlesource.gerrit.plugins.findowners.Module")
public class PerFileIT extends FindOwners {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  @Rule public Watcher watcher = new Watcher(logger);

  @Test
  public void ownersPerFileTest() throws Exception {
    addFile("1", "OWNERS", "per-file *.c=x@x\na@a\nc@c\nb@b\n");
    // Add "t.c" file, which has per-file owner x@x, and a@a, b@b, c@c.
    PushOneCommit.Result c2 = createChange("2", "t.c", "Hello!");
    String ownerA = ownerJson("a@a");
    String ownerB = ownerJson("b@b");
    String ownerC = ownerJson("c@c");
    String ownerABC = "owners:[" + ownerA + "," + ownerB + "," + ownerC;
    String ownerX = ownerJson("x@x");
    assertThat(getOwnersResponse(c2)).contains(ownerABC + "," + ownerX + "],files:[t.c]");
    // Add "t.txt" file, which has only global default owners.
    PushOneCommit.Result c3 = createChange("3", "t.txt", "Test!");
    assertThat(getOwnersResponse(c3)).contains(ownerABC + "],files:[t.txt]");
  }

  @Test
  public void perFileIncludeTest() throws Exception {
    // A per-file with file: directive to include more owners.
    addFile("1", "OWNERS", "per-file *.c=x@x\na@a\nper-file t.c=file: t_owner\n");
    addFile("2", "t_owner", "t1@g\n*\nper-file *.c=y@y\ninclude more_owner\n");
    addFile("3", "more_owner", "m@g\nm2@g\nper-file *.c=z@z\n");
    PushOneCommit.Result c1 = createChange("c1", "x.c", "test");
    PushOneCommit.Result c2 = createChange("c2", "t.c", "test");
    String c1Response = getOwnersDebugResponse(c1);
    String c2Response = getOwnersDebugResponse(c2);
    assertThat(c1Response).contains("file2owners:{./x.c:[a@a,x@x]}");
    assertThat(c2Response).contains("file2owners:{./t.c:[*,a@a,m2@g,m@g,t1@g,x@x]}");
  }

  @Test
  public void includePerFileTest() throws Exception {
    // Test included file with per-file, which affects the including file.
    PushOneCommit.Result c1 = addFile("1", "d1/d1/OWNERS", "d1d1@g\nper-file OWNERS=d1d1o@g\n");
    PushOneCommit.Result c2 = addFile("2", "d1/OWNERS", "d1@g\nper-file OWNERS=d1o@g\n");
    PushOneCommit.Result c3 = addFile("3", "d2/d1/OWNERS", "d2d1@g\ninclude ../../d1/d1/OWNERS\n");
    PushOneCommit.Result c4 = addFile("4", "d2/OWNERS", "d2@g\nper-file OWNERS=d2o@g");
    // Files that match per-file globs now inherit global default owners.
    assertThat(getOwnersResponse(c1)).contains("{./d1/d1/OWNERS:[d1@g,d1d1@g,d1d1o@g,d1o@g]}");
    assertThat(getOwnersResponse(c2)).contains("{./d1/OWNERS:[d1@g,d1o@g]}");
    assertThat(getOwnersResponse(c3))
        .contains("{./d2/d1/OWNERS:[d1d1@g,d1d1o@g,d2@g,d2d1@g,d2o@g]}");
    assertThat(getOwnersResponse(c4)).contains("{./d2/OWNERS:[d2@g,d2o@g]}");
  }
}
