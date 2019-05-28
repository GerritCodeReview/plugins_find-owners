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
import com.google.gerrit.reviewdb.client.Project;
import org.junit.Rule;
import org.junit.Test;

/** Test find-owners plugin features related to include and file statements. */
@TestPlugin(name = "find-owners", sysModule = "com.googlesource.gerrit.plugins.findowners.Module")
public class IncludeIT extends FindOwners {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  @Rule public Watcher watcher = new Watcher(logger);

  private String getRepoFileLog(String msg1, String msg2) {
    return "getRepoFile:" + msg1 + ", getFile:" + msg2 + ", ";
  }

  private String concat(String s1, String s2) {
    return s1 + s2;
  }

  private String concat(String s1, String s2, String s3) {
    return s1 + s2 + s3;
  }

  @Test
  public void includeNotFoundTest() throws Exception {
    // c2 and c1 are both submitted before existence of OWNERS.
    PushOneCommit.Result c2 = addFile("1", "t.c", "##");
    // Submitted c2 still finds no owners before c1 is submitted.
    assertThat(getOwnersResponse(c2)).contains("owners:[], files:[ t.c ]");
    PushOneCommit.Result c1 =
        addFile("2", "OWNERS", "x@x\na@a\ninclude  P1/P2 : f1\ninclude ./d1/d2/../../f2\n");
    // Now c2 should find owners, but include directives find no repository or file.
    String ownersAX = "owners:[ " + ownerJson("a@a") + ", " + ownerJson("x@x") + " ]";
    String path2owners = "path2owners:{ ./:[ a@a, x@x ] }";
    String owner2paths = "owner2paths:{ a@a:[ ./ ], x@x:[ ./ ] }";
    String projectName = project.get();
    String expectedInLog =
        concat("project:", projectName, ", ")
            + "ownersFileName:OWNERS, "
            + "getBranchId:refs/heads/master(FOUND), "
            + "findOwnersFileFor:./t.c, "
            + "findOwnersFileIn:., "
            + getRepoFileLog(projectName + ":refs/heads/master:./OWNERS", "OWNERS:(...)")
            + "parseLine:include:P1/P2:f1, "
            + "getRepoFile:P1/P2:refs/heads/master:f1, "
            + "hasReadAccessException:project\\u0027P1/P2\\u0027isunavailable, " // cannot read
            + "parseLine:include:(), " // missing file is treated as empty
            + concat("parseLine:include:", projectName, ":./d1/d2/../../f2, ")
            + getRepoFileLog(projectName + ":refs/heads/master:f2", "f2(NOTFOUND)")
            + "parseLine:include:(), " // missing file is treated as empty
            + "countNumOwners, "
            + "findOwners, "
            + "checkFile:./t.c, "
            + "checkDir:., "
            + "addOwnerWeightsIn:./ "
            + "] ";
    String c2Response = getOwnersDebugResponse(c2);
    assertThat(c2Response).contains(path2owners);
    assertThat(c2Response).contains(owner2paths);
    assertThat(c2Response).contains("file2owners:{ ./t.c:[ a@a, x@x ] }");
    assertThat(c2Response).contains(ownersAX);
    assertThat(c2Response).contains(expectedInLog);
    // A submitted change gets owners info from current repository.
    String c1Response = getOwnersDebugResponse(c1);
    assertThat(c1Response).contains(path2owners);
    assertThat(c1Response).contains(owner2paths);
    assertThat(c1Response).contains("file2owners:{ ./OWNERS:[ a@a, x@x ] }");
    assertThat(c1Response).contains(ownersAX);
  }

  @Test
  public void includeFoundTest() throws Exception {
    // Compared with includeNotFoundTest, this one has file "f2" to include.
    addFile("c0", "f2", "g1@g\ng2@g\n");
    // c2 and c1 are both submitted before existence of OWNERS.
    PushOneCommit.Result c2 = addFile("c2", "t.c", "##");
    PushOneCommit.Result c1 =
        addFile("c1", "OWNERS", "x@x\na@a\ninclude  P1/P2 : f1\ninclude ./d1/d2/../../f2\n");
    String ownerA = ownerJson("a@a");
    String ownerX = ownerJson("x@x");
    String ownerG1 = ownerJson("g1@g");
    String ownerG2 = ownerJson("g2@g");
    String ownersAG1G2X =
        "owners:[ " + ownerA + ", " + ownerG1 + ", " + ownerG2 + ", " + ownerX + " ]";
    String path2owners = "path2owners:{ ./:[ a@a, g1@g, g2@g, x@x ] }";
    String owner2paths = "owner2paths:{ a@a:[ ./ ], g1@g:[ ./ ], g2@g:[ ./ ], x@x:[ ./ ] }";
    String projectName = project.get();
    String expectedInLog =
        concat("project:", projectName, ", ")
            + "ownersFileName:OWNERS, "
            + "getBranchId:refs/heads/master(FOUND), "
            + "findOwnersFileFor:./t.c, "
            + "findOwnersFileIn:., "
            + getRepoFileLog(projectName + ":refs/heads/master:./OWNERS", "OWNERS:(...)")
            + "parseLine:include:P1/P2:f1, "
            + "getRepoFile:P1/P2:refs/heads/master:f1, "
            + "hasReadAccessException:project\\u0027P1/P2\\u0027isunavailable, "
            + "parseLine:include:(), " // P1/P2 is still not found
            + concat("parseLine:include:", projectName, ":./d1/d2/../../f2, ")
            + getRepoFileLog(projectName + ":refs/heads/master:f2", "f2:(...)")
            + "countNumOwners, "
            + "findOwners, "
            + "checkFile:./t.c, "
            + "checkDir:., "
            + "addOwnerWeightsIn:./ "
            + "] ";
    String c2Response = getOwnersDebugResponse(c2);
    assertThat(c2Response).contains(path2owners);
    assertThat(c2Response).contains(owner2paths);
    assertThat(c2Response).contains("file2owners:{ ./t.c:[ a@a, g1@g, g2@g, x@x ] }");
    assertThat(c2Response).contains(ownersAG1G2X);
    assertThat(c2Response).contains(expectedInLog);
    // A submitted change gets owners info from current repository.
    String c1Response = getOwnersDebugResponse(c1);
    assertThat(c1Response).contains(path2owners);
    assertThat(c1Response).contains(owner2paths);
    assertThat(c1Response).contains("file2owners:{ ./OWNERS:[ a@a, g1@g, g2@g, x@x ] }");
    assertThat(c1Response).contains(ownersAG1G2X);
  }

  @Test
  public void includeIndirectFileTest() throws Exception {
    // Test indirectly included file and relative file path.
    addFile("1", "d1/f2", "d1f2@g\n");
    addFile("2", "d2/f2", "d2f2@g\n");
    addFile("3", "d3/f2", "d3f2@g\n");
    addFile("4", "d1/d2/owners", "d1d2@g\ninclude ../f2\n");
    addFile("5", "d2/d2/owners", "d2d2@g\ninclude ../f2\n");
    addFile("6", "d3/d2/owners", "d3d2@g\ninclude ../f2\n");
    addFile("7", "d3/OWNERS", "d3@g\ninclude ../d2/d2/owners\n");
    addFile("8", "OWNERS", "x@g\n");
    PushOneCommit.Result c1 = createChange("c1", "d3/t.c", "Hello!");
    // d3's owners are in d3/OWNERS, d2/d2/owners, d2/f2, OWNERS,
    // If the include directories are based on original directory d3,
    // then the included files will be d2/d2/owners and d3/f2.
    String ownerD3 = ownerJson("d3@g");
    String ownerD2 = ownerJson("d2d2@g");
    String ownerF2 = ownerJson("d2f2@g");
    String ownerX = ownerJson("x@g", 0, 1, 0);
    assertThat(getOwnersResponse(c1))
        .contains(
            "owners:[ "
                + concat(ownerD2, ", ")
                + concat(ownerF2, ", ")
                + concat(ownerD3, ", ")
                + concat(ownerX, " ], files:[ d3/t.c ]"));
  }

  @Test
  public void includeVsFileTest() throws Exception {
    // Test difference between include and file statements.
    // The file statement skips "set noparent" and "per-file" statements.
    addFile("d1", "d1/OWNERS", "d1@g\n");
    addFile("d1/d1", "d1/d1/OWNERS", "per-file *.c=d1d1p@g\nd1d1@g\nfile: d1/OWNERS\n");
    addFile("d1/d1/d1", "d1/d1/d1/OWNERS", "set noparent\nper-file *.c=d1d1d1p@g\nd1d1d1@g\n");
    addFile("d1/d2", "d1/d2/OWNERS", "per-file *.c=d1d2p@g\nd1d2@g\ninclude d1/OWNERS\n");
    addFile("d1/d2/d1", "d1/d2/d1/OWNERS", "set noparent\nper-file *.c=d1d2d1p@g\nd1d2d1@g\n");

    addFile("d2", "d2/OWNERS", "d2@g\n");
    addFile("d2/d1", "d2/d1/OWNERS", "per-file *.c=d2d1p@g\nd2d1@g\nfile: ./d1/OWNERS\n");
    addFile("d2/d1/d1", "d2/d1/d1/OWNERS", "set noparent\nper-file *.c=d2d1d1p@g\nd2d1d1@g\n");
    addFile("d2/d2", "d2/d2/OWNERS", "per-file *.c=d2d2p@g\nd2d2@g\ninclude ./d1/OWNERS\n");
    addFile("d2/d2/d1", "d2/d2/d1/OWNERS", "set noparent\nper-file *.c=d2d2d1p@g\nd2d2d1@g\n");

    addFile("d3", "d3/OWNERS", "d3@g\n");
    addFile("d3/d1/d1", "d3/d1/d1/OWNERS", "d3d1d1@g\nfile: ../../../d1/d1/OWNERS\n");
    addFile("d3/d1/d2", "d3/d1/d2/OWNERS", "d3d1d2@g\nfile: //d1/d2/OWNERS\n");
    addFile("d3/d2/d1", "d3/d2/d1/OWNERS", "d3d2d1@g\ninclude /d2/d1/OWNERS\n");
    addFile("d3/d2/d2", "d3/d2/d2/OWNERS", "d3d2d2@g\ninclude //d2/d2/OWNERS\n");
    PushOneCommit.Result c11 = createChange("c11", "d3/d1/d1/t.c", "test");
    PushOneCommit.Result c12 = createChange("c12", "d3/d1/d2/t.c", "test");
    PushOneCommit.Result c21 = createChange("c21", "d3/d2/d1/t.c", "test");
    PushOneCommit.Result c22 = createChange("c22", "d3/d2/d2/t.c", "test");

    // file and file
    String owners11 = "file2owners:{ ./d3/d1/d1/t.c:" + "[ d1d1@g, d1d1d1@g, d3@g, d3d1d1@g ] }";
    // file and include
    String owners12 = "file2owners:{ ./d3/d1/d2/t.c:" + "[ d1d2@g, d1d2d1@g, d3@g, d3d1d2@g ] }";
    // include and file
    String owners21 =
        "file2owners:{ ./d3/d2/d1/t.c:" + "[ d2d1@g, d2d1d1@g, d2d1p@g, d3@g, d3d2d1@g ] }";
    // include and include
    String owners22 =
        "file2owners:{ ./d3/d2/d2/t.c:" + "[ d2d2@g, d2d2d1@g, d2d2d1p@g, d2d2p@g, d3d2d2@g ] }";

    assertThat(getOwnersDebugResponse(c11)).contains(owners11);
    assertThat(getOwnersDebugResponse(c12)).contains(owners12);
    assertThat(getOwnersDebugResponse(c21)).contains(owners21);
    assertThat(getOwnersDebugResponse(c22)).contains(owners22);
  }

  @Test
  public void multipleIncludeTest() throws Exception {
    // Now "include" and "file:" statements can share parsed results.
    addFile("d1", "d1/OWNERS", "d1@g\n");
    addFile("d2/d1", "d2/d1/OWNERS", "include /d1/OWNERS\nfile://d1/OWNERS\n");
    addFile("d2/d2", "d2/d2/OWNERS", "file: //d1/OWNERS\ninclude /d1/OWNERS\n");
    PushOneCommit.Result c1 = createChange("c1", "d2/d1/t.c", "test");
    PushOneCommit.Result c2 = createChange("c2", "d2/d2/t.c", "test");
    String projectName = project.get();
    String log1 = "parseLine:useSaved:file:" + projectName + "://d1/OWNERS, ";
    String log2 = "parseLine:useSaved:include:" + projectName + ":/d1/OWNERS, ";
    String response1 = getOwnersDebugResponse(c1);
    String response2 = getOwnersDebugResponse(c2);
    assertThat(response1).contains(log1);
    assertThat(response1).doesNotContain(log2);
    assertThat(response2).doesNotContain(log1);
    assertThat(response2).contains(log2);
  }

  @Test
  public void includeCycleTest() throws Exception {
    // f1 includes f2, f2 includes f3, f3 includes f4, f4 includes f2, OWNERS includes f1.
    // All files are in the root directory, but could be referred with relative paths.
    addFile("1", "f1", "f1@g\ninclude ./f2\n");
    addFile("2", "f2", "f2@g\ninclude d1/../f3\n");
    addFile("3", "f3", "f3@g\ninclude /f4\n");
    addFile("4", "f4", "f4@g\ninclude d2/../f2\n");
    addFile("5", "OWNERS", "x@g\ninclude ./d1/../f1\n");
    PushOneCommit.Result c = createChange("6", "t.c", "#\n");
    String response = getOwnersDebugResponse(c);
    String projectName = project.get();
    String expectedInLog =
        concat("project:", projectName, ", ")
            + "ownersFileName:OWNERS, "
            + "getBranchId:refs/heads/master(FOUND), "
            + "findOwnersFileFor:./t.c, "
            + "findOwnersFileIn:., "
            + getRepoFileLog(projectName + ":refs/heads/master:./OWNERS", "OWNERS:(...)")
            + concat("parseLine:include:", projectName, ":./d1/../f1, ")
            + getRepoFileLog(projectName + ":refs/heads/master:f1", "f1:(...)")
            + concat("parseLine:include:", projectName, ":./f2, ")
            + getRepoFileLog(projectName + ":refs/heads/master:f2", "f2:(...)")
            + concat("parseLine:include:", projectName, ":d1/../f3, ")
            + getRepoFileLog(projectName + ":refs/heads/master:f3", "f3:(...)")
            + concat("parseLine:include:", projectName, ":/f4, ")
            + getRepoFileLog(projectName + ":refs/heads/master:f4", "f4:(...)")
            + concat("parseLine:errorRecursion:include:", projectName, ":d2/../f2, ")
            + "countNumOwners, "
            + "findOwners, "
            + "checkFile:./t.c, "
            + "checkDir:., "
            + "addOwnerWeightsIn:./ "
            + "] ";
    assertThat(response).contains("path2owners:{ ./:[ f1@g, f2@g, f3@g, f4@g, x@g ] }");
    assertThat(response)
        .contains("owner2paths:{ f1@g:[ ./ ], f2@g:[ ./ ], f3@g:[ ./ ], f4@g:[ ./ ], x@g:[ ./ ] }");
    assertThat(response).contains(expectedInLog);
  }

  @Test
  public void includeDuplicationTest() throws Exception {
    // f0 is included into f1, f2, f3,
    // f2 is included into f4 and f5; f4 is included into f5.
    // f0, f1, f2, f3, f5 are included into d6/OWNERS.
    addFile("0", "d0/f0", "f0@g\n");
    addFile("1", "d1/d2/f1", "f1@g\ninclude ../../d0/f0\n");
    addFile("2", "d2/f2", "f2@g\ninclude ../d0/f0\n");
    addFile("3", "d2/d3/f3", "f3@g\ninclude /d0/f0\n");
    addFile("4", "d4/f4", "f4@g\ninclude ../d2/f2\n");
    addFile("5", "d4/d5/f5", "f5@g\ninclude /d2/f2\ninclude ../f4\n");
    PushOneCommit.Result c =
        addFile(
            "6",
            "d6/OWNERS",
            "f6@g\ninclude /d0/f0\ninclude ../d1/d2/f1\n"
                + "include ../d2/f2\ninclude /d2/d3/f3\ninclude /d2/../d4/d5/f5\ninclude /d4/f4\n");
    String result = getOwnersDebugResponse(c);
    assertThat(result).contains("{ ./d6/OWNERS:[ f0@g, f1@g, f2@g, f3@g, f4@g, f5@g, f6@g ] }");
    String projectName = project.get();
    String skipLog = "parseLine:useSaved:include:" + projectName + ":";
    for (String path : new String[] {"../../d0/f0", "../d0/f0", "../d2/f2", "/d2/f2", "/d4/f4"}) {
      assertThat(result).contains(skipLog + path);
    }
    String expectedInLog =
        concat("project:", projectName, ", ")
            + "ownersFileName:OWNERS, "
            + "getBranchId:refs/heads/master(FOUND), "
            + "findOwnersFileFor:./d6/OWNERS, "
            + "findOwnersFileIn:./d6, "
            + getRepoFileLog(projectName + ":refs/heads/master:./d6/OWNERS", "d6/OWNERS:(...)")
            + concat("parseLine:include:", projectName, ":/d0/f0, ")
            + getRepoFileLog(projectName + ":refs/heads/master:d0/f0", "d0/f0:(...)")
            + concat("parseLine:include:", projectName, ":../d1/d2/f1, ")
            + getRepoFileLog(projectName + ":refs/heads/master:d1/d2/f1", "d1/d2/f1:(...)")
            + concat("parseLine:useSaved:include:", projectName, ":../../d0/f0, ")
            + concat("parseLine:include:", projectName, ":../d2/f2, ")
            + getRepoFileLog(projectName + ":refs/heads/master:d2/f2", "d2/f2:(...)")
            + concat("parseLine:useSaved:include:", projectName, ":../d0/f0, ")
            + concat("parseLine:include:", projectName, ":/d2/d3/f3, ")
            + getRepoFileLog(projectName + ":refs/heads/master:d2/d3/f3", "d2/d3/f3:(...)")
            + concat("parseLine:useSaved:include:", projectName, ":/d0/f0, ")
            + concat("parseLine:include:", projectName, ":/d2/../d4/d5/f5, ")
            + getRepoFileLog(projectName + ":refs/heads/master:d4/d5/f5", "d4/d5/f5:(...)")
            + concat("parseLine:useSaved:include:", projectName, ":/d2/f2, ")
            + concat("parseLine:include:", projectName, ":../f4, ")
            + getRepoFileLog(projectName + ":refs/heads/master:d4/f4", "d4/f4:(...)")
            + concat("parseLine:useSaved:include:", projectName, ":../d2/f2, ")
            + concat("parseLine:useSaved:include:", projectName, ":/d4/f4, ")
            + "findOwnersFileIn:., "
            + getRepoFileLog(projectName + ":refs/heads/master:./OWNERS", "OWNERS(NOTFOUND)")
            + "countNumOwners, "
            + "findOwners, "
            + "checkFile:./d6/OWNERS, "
            + "checkDir:./d6, "
            + "checkDir:., "
            + "addOwnerWeightsIn:./d6/ "
            + "] ";
    assertThat(result).contains(expectedInLog);
  }

  @Test
  public void includeProjectOwnerTest() throws Exception {
    // Test include directive with other project name.
    Project.NameKey pA = newProject("PA");
    Project.NameKey pB = newProject("PB");
    String nameA = pA.get();
    String nameB = pB.get();
    switchProject(pA);
    addFile("1", "f1", "pAf1@g\ninclude ./d1/f1\n");
    addFile("2", "d1/f1", "pAd1f1@g\ninclude " + nameB + ":" + "/d2/f2\n");
    addFile("3", "d2/OWNERS", "pAd2@g\n  include " + nameA + "  : " + "../f1\n");
    addFile("4", "OWNERS", "pA@g\n");
    switchProject(pB);
    addFile("5", "f1", "pBf1@g\ninclude ./d1/f1\n");
    addFile("6", "f2", "pBf2@g\n");
    addFile("7", "d1/f1", "pBd1f1@g\n");
    addFile("8", "d2/f2", "pBd2f2@g\ninclude ../f1\n");
    switchProject(pA);
    PushOneCommit.Result c1 = createChange("c1", "d2/t.c", "Hello!");
    // included: pA:d2/OWNERS, pA:d2/../f1, pA:d1/f1, pB:d2/f2, pB:d2/../f1, pB:./d1/f1
    // inherited: pA:OWNERS
    String owners =
        "owners:[ "
            + concat(ownerJson("pAd1f1@g"), ", ")
            + concat(ownerJson("pAd2@g"), ", ")
            + concat(ownerJson("pAf1@g"), ", ")
            + concat(ownerJson("pBd1f1@g"), ", ")
            + concat(ownerJson("pBd2f2@g"), ", ")
            + concat(ownerJson("pBf1@g"), ", ")
            + concat(ownerJson("pA@g", 0, 1, 0), " ]");
    assertThat(getOwnersResponse(c1)).contains(owners);
  }

  @Test
  public void includeProjectOwnerACLTest() throws Exception {
    // Test include directive with other unreadable project.
    Project.NameKey pA = newProject("PA2");
    Project.NameKey pB = newProject("PB2");
    String nameA = pA.get();
    String nameB = pB.get();
    switchProject(pA);
    addFile("1", "f1", "pAf1@g\ninclude ./d1/f1\n");
    addFile("2", "d1/f1", "pAd1f1@g\ninclude " + nameB + ":" + "/d2/f2\n");
    addFile("3", "d2/OWNERS", "pAd2@g\n  include " + nameA + "  : " + "../f1\n");
    addFile("4", "OWNERS", "pA@g\n");
    switchProject(pB);
    addFile("5", "f1", "pBf1@g\ninclude ./d1/f1\n");
    addFile("6", "f2", "pBf2@g\n");
    addFile("7", "d1/f1", "pBd1f1@g\n");
    addFile("8", "d2/f2", "pBd2f2@g\ninclude ../f1\n");
    blockRead(project); // now cannot read pB
    switchProject(pA);
    PushOneCommit.Result c1 = createChange("c1", "d2/t.c", "Hello!");
    // included: pA:d2/OWNERS, pA:d2/../f1, pA:d1/f1
    // inherited: pA:OWNERS
    // pB's OWNERS files are not readable
    String owners =
        "owners:[ "
            + concat(ownerJson("pAd1f1@g"), ", ")
            + concat(ownerJson("pAd2@g"), ", ")
            + concat(ownerJson("pAf1@g"), ", ")
            + concat(ownerJson("pA@g", 0, 1, 0), " ]");
    // The "owners:[...]" substring contains only owners from pA.
    assertThat(getOwnersResponse(c1)).contains(owners);
  }
}
