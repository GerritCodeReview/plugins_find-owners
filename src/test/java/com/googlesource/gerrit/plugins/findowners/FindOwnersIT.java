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
import static com.google.common.truth.Truth8.assertThat;
import static com.google.gerrit.reviewdb.client.RefNames.REFS_CONFIG;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.Multimap;
import com.google.gerrit.acceptance.LightweightPluginDaemonTest;
import com.google.gerrit.acceptance.PushOneCommit;
import com.google.gerrit.acceptance.RestResponse;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.acceptance.testsuite.project.ProjectOperations;
import com.google.gerrit.extensions.api.accounts.EmailInput;
import com.google.gerrit.extensions.api.changes.SubmitInput;
import com.google.gerrit.extensions.api.projects.BranchApi;
import com.google.gerrit.extensions.client.ChangeStatus;
import com.google.gerrit.extensions.common.ChangeInfo;
import com.google.gerrit.extensions.common.ChangeInput;
import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.reviewdb.client.Account;
import com.google.gerrit.reviewdb.client.Branch;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.reviewdb.client.RefNames;
import com.google.gerrit.server.account.AccountState;
import com.google.gerrit.server.account.Emails;
import com.google.gerrit.server.change.ChangeResource;
import com.google.gerrit.server.config.PluginConfigFactory;
import com.google.inject.Inject;
import java.util.Collection;
import java.util.Optional;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilterGroup;
import org.junit.Test;

/** Test find-owners plugin API. */
@TestPlugin(name = "find-owners", sysModule = "com.googlesource.gerrit.plugins.findowners.Module")
public class FindOwnersIT extends LightweightPluginDaemonTest {

  @Inject private Emails emails;
  @Inject private PluginConfigFactory configFactory;
  @Inject private ProjectOperations projectOperations;

  @Test
  public void getOwnersTest() throws Exception {
    ChangeInfo info1 = newChangeInfo("test1 GetOwners");
    ChangeInfo info2 = newChangeInfo("test2 GetOwners");
    assertThat(info2._number).isEqualTo(info1._number + 1);
    String expected =
        ")]}' { minOwnerVoteLevel:1, addDebugMsg:false, change:"
            + info1._number
            + ", patchset:1, file2owners:{}, reviewers:[], owners:[], files:[] }";
    Cache cache = Cache.getInstance().init(0, 10); // reset, no Cache
    assertThat(cache.size()).isEqualTo(0L);
    // GetOwners GET API
    assertThat(getOwnersResponse(info1)).isEqualTo(expected);
    assertThat(cache.size()).isEqualTo(0L);
    // find-owners GET API
    assertThat(getFindOwnersResponse(info1)).isEqualTo(expected);
    cache.init(10, 5); // create the Cache
    assertThat(cache.size()).isEqualTo(0L);
    assertThat(getOwnersResponse(info1)).isEqualTo(expected);
    assertThat(getFindOwnersResponse(info1)).isEqualTo(expected);
    assertThat(cache.size()).isEqualTo(1L);
  }

  @Test
  public void includeNotFoundTest() throws Exception {
    // c2 and c1 are both submitted before existence of OWNERS.
    PushOneCommit.Result c2 = addFile("1", "t.c", "##");
    // Submitted c2 still finds no owners before c1 is submitted.
    assertThat(getOwnersResponse(c2)).contains("owners:[], files:[ t.c ]");
    PushOneCommit.Result c1 = addFile("2", "OWNERS",
        "x@x\na@a\nfile:f1.txt\ninclude  P1/P2 : f1\ninclude ./d1/d2/../../f2\n");
    // Now c2 should find owners, but include directives find no repository or file.
    String ownersAX = "owners:[ " + ownerJson("a@a") + ", " + ownerJson("x@x") + " ]";
    String path2owners = "path2owners:{ ./:[ a@a, x@x ] }";
    String owner2paths = "owner2paths:{ a@a:[ ./ ], x@x:[ ./ ] }";
    String projectName = myProjectName("includeNotFoundTest");
    String expectedInLog = "project:" + projectName + ", "
            + "ownersFileName:OWNERS, "
            + "getBranchId:refs/heads/master(FOUND), "
            + "findOwnersFileFor:./t.c, "
            + "findOwnersFileIn:., "
            + "getFile:OWNERS:x@x\\na@a\\nfile:f1.txt\\n"
            + "includeP1/P2:f1\\ninclude./d1/d2/../../f2\\n, "
            + "parseLine:file, "
            + "parseLine:include:P1/P2:f1, "
            + "getRepoFile:P1/P2:refs/heads/master:f1, "
            + "getRepoFileException:repositorynotfound:P1/P2, " // repository not found
            + "parseLine:include:(empty), " // missing file is treated as empty
            + "parseLine:include:" + projectName + ":./d1/d2/../../f2, "
            + "getRepoFile:" + projectName + ":refs/heads/master:f2, "
            + "getFile:f2(NOTFOUND), " // same repository but f2 is missing
            + "parseLine:include:(empty), " // missing file is treated as empty
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
    PushOneCommit.Result c1 = addFile("c1", "OWNERS",
        "x@x\na@a\nfile:f1.txt\ninclude  P1/P2 : f1\ninclude ./d1/d2/../../f2\n");
    String ownerA = ownerJson("a@a");
    String ownerX = ownerJson("x@x");
    String ownerG1 = ownerJson("g1@g");
    String ownerG2 = ownerJson("g2@g");
    String ownersAG1G2X =
        "owners:[ " + ownerA + ", " + ownerG1 + ", " + ownerG2 + ", " + ownerX + " ]";
    String path2owners = "path2owners:{ ./:[ a@a, g1@g, g2@g, x@x ] }";
    String owner2paths = "owner2paths:{ a@a:[ ./ ], g1@g:[ ./ ], g2@g:[ ./ ], x@x:[ ./ ] }";
    String projectName = myProjectName("includeFoundTest");
    String expectedInLog = "project:" + projectName + ", "
            + "ownersFileName:OWNERS, "
            + "getBranchId:refs/heads/master(FOUND), "
            + "findOwnersFileFor:./t.c, "
            + "findOwnersFileIn:., "
            + "getFile:OWNERS:x@x\\na@a\\nfile:f1.txt\\n"
            + "includeP1/P2:f1\\ninclude./d1/d2/../../f2\\n, "
            + "parseLine:file, "
            + "parseLine:include:P1/P2:f1, "
            + "getRepoFile:P1/P2:refs/heads/master:f1, "
            + "getRepoFileException:repositorynotfound:P1/P2, "
            + "parseLine:include:(empty), " // P1/P2 is still not found
            + "parseLine:include:" + projectName + ":./d1/d2/../../f2, "
            + "getRepoFile:" + projectName + ":refs/heads/master:f2, "
            + "getFile:f2:g1@g\\ng2@g\\n, " // f2 is included
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
    assertThat(getOwnersResponse(c1)).contains("owners:[ " + ownerD2 + ", "
        + ownerF2 + ", " + ownerD3 + ", " + ownerX + " ], files:[ d3/t.c ]");
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
    String projectName = myProjectName("includeCycleTest");
    String expectedInLog = "project:" + projectName + ", "
            + "ownersFileName:OWNERS, "
            + "getBranchId:refs/heads/master(FOUND), "
            + "findOwnersFileFor:./t.c, "
            + "findOwnersFileIn:., "
            + "getFile:OWNERS:x@g\\ninclude./d1/../f1\\n, "
            + "parseLine:include:" + projectName + ":./d1/../f1, "
            + "getRepoFile:" + projectName + ":refs/heads/master:f1, "
            + "getFile:f1:f1@g\\ninclude./f2\\n, "
            + "parseLine:include:" + projectName + ":./f2, "
            + "getRepoFile:" + projectName + ":refs/heads/master:f2, "
            + "getFile:f2:f2@g\\nincluded1/../f3\\n, "
            + "parseLine:include:" + projectName + ":d1/../f3, "
            + "getRepoFile:" + projectName + ":refs/heads/master:f3, "
            + "getFile:f3:f3@g\\ninclude/f4\\n, "
            + "parseLine:include:" + projectName + ":/f4, "
            + "getRepoFile:" + projectName + ":refs/heads/master:f4, "
            + "getFile:f4:f4@g\\nincluded2/../f2\\n, "
            + "parseLine:skip:include:" + projectName + ":d2/../f2, "
            + "countNumOwners, "
            + "findOwners, "
            + "checkFile:./t.c, "
            + "checkDir:., "
            + "addOwnerWeightsIn:./ "
            + "] ";
    assertThat(response).contains("path2owners:{ ./:[ f1@g, f2@g, f3@g, f4@g, x@g ] }");
    assertThat(response).contains(
        "owner2paths:{ f1@g:[ ./ ], f2@g:[ ./ ], f3@g:[ ./ ], f4@g:[ ./ ], x@g:[ ./ ] }");
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
    PushOneCommit.Result c = addFile("6", "d6/OWNERS",
        "f6@g\ninclude /d0/f0\ninclude ../d1/d2/f1\n"
        + "include ../d2/f2\ninclude /d2/d3/f3\ninclude /d2/../d4/d5/f5\ninclude /d4/f4\n");
    String result = getOwnersDebugResponse(c);
    assertThat(result).contains("{ ./d6/OWNERS:[ f0@g, f1@g, f2@g, f3@g, f4@g, f5@g, f6@g ] }");
    String skipLog = "parseLine:skip:include:" + myProjectName("includeDuplicationTest") + ":";
    for (String path : new String[]{"../../d0/f0", "../d0/f0", "../d2/f2", "/d2/f2", "/d4/f4"}) {
      assertThat(result).contains(skipLog + path);
    }
    String projectName = myProjectName("includeDuplicationTest");
    String expectedInLog = "project:" + projectName + ", "
           + "ownersFileName:OWNERS, "
           + "getBranchId:refs/heads/master(FOUND), "
           + "findOwnersFileFor:./d6/OWNERS, "
           + "findOwnersFileIn:./d6, "
           + "getFile:d6/OWNERS:f6@g\\ninclude/d0/f0\\ninclude../d1/d2/f1\\ninclude../d2/f2\\n"
           + "include/d2/d3/f3\\ninclude/d2/../d4/d5/f5\\ninclude/d4/f4\\n, "
           + "parseLine:include:" + projectName + ":/d0/f0, "
           + "getRepoFile:" + projectName + ":refs/heads/master:d0/f0, "
           + "getFile:d0/f0:f0@g\\n, "
           + "parseLine:include:" + projectName + ":../d1/d2/f1, "
           + "getRepoFile:" + projectName + ":refs/heads/master:d1/d2/f1, "
           + "getFile:d1/d2/f1:f1@g\\ninclude../../d0/f0\\n, "
           + "parseLine:skip:include:" + projectName + ":../../d0/f0, "
           + "parseLine:include:" + projectName + ":../d2/f2, "
           + "getRepoFile:" + projectName + ":refs/heads/master:d2/f2, "
           + "getFile:d2/f2:f2@g\\ninclude../d0/f0\\n, "
           + "parseLine:skip:include:" + projectName + ":../d0/f0, "
           + "parseLine:include:" + projectName + ":/d2/d3/f3, "
           + "getRepoFile:" + projectName + ":refs/heads/master:d2/d3/f3, "
           + "getFile:d2/d3/f3:f3@g\\ninclude/d0/f0\\n, "
           + "parseLine:skip:include:" + projectName + ":/d0/f0, "
           + "parseLine:include:" + projectName + ":/d2/../d4/d5/f5, "
           + "getRepoFile:" + projectName + ":refs/heads/master:d4/d5/f5, "
           + "getFile:d4/d5/f5:f5@g\\ninclude/d2/f2\\ninclude../f4\\n, "
           + "parseLine:skip:include:" + projectName + ":/d2/f2, "
           + "parseLine:include:" + projectName + ":../f4, "
           + "getRepoFile:" + projectName + ":refs/heads/master:d4/f4, "
           + "getFile:d4/f4:f4@g\\ninclude../d2/f2\\n, "
           + "parseLine:skip:include:" + projectName + ":../d2/f2, "
           + "parseLine:skip:include:" + projectName + ":/d4/f4, "
           + "findOwnersFileIn:., "
           + "getFile:OWNERS(NOTFOUND), "
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
  public void ownersPerFileTest() throws Exception {
    addFile("1", "OWNERS", "per-file *.c=x@x\na@a\nc@c\nb@b\n");
    // Add "t.c" file, which has per-file owner x@x, not a@a, b@b, c@c.
    PushOneCommit.Result c2 = createChange("2", "t.c", "Hello!");
    String ownerA = ownerJson("a@a");
    String ownerB = ownerJson("b@b");
    String ownerC = ownerJson("c@c");
    String ownerX = ownerJson("x@x");
    assertThat(getOwnersResponse(c2)).contains("owners:[ " + ownerX + " ], files:[ t.c ]");
    // Add "t.txt" file, which has new owners.
    PushOneCommit.Result c3 = createChange("3", "t.txt", "Test!");
    assertThat(getOwnersResponse(c3))
        .contains("owners:[ " + ownerA + ", " + ownerB + ", " + ownerC + " ], files:[ t.txt ]");
  }

  @Test
  public void includePerFileTest() throws Exception {
    // Test included file with per-file, which affects the including file.
    PushOneCommit.Result c1 = addFile("1", "d1/d1/OWNERS", "d1d1@g\nper-file OWNERS=d1d1o@g\n");
    PushOneCommit.Result c2 = addFile("2", "d1/OWNERS", "d1@g\nper-file OWNERS=d1o@g\n");
    PushOneCommit.Result c3 = addFile("3", "d2/d1/OWNERS", "d2d1@g\ninclude ../../d1/d1/OWNERS\n");
    PushOneCommit.Result c4 = addFile("4", "d2/OWNERS", "d2@g\nper-file OWNERS=d2o@g");
    assertThat(getOwnersResponse(c1)).contains("{ ./d1/d1/OWNERS:[ d1d1o@g, d1o@g ] }");
    assertThat(getOwnersResponse(c2)).contains("{ ./d1/OWNERS:[ d1o@g ] }");
    assertThat(getOwnersResponse(c3)).contains("{ ./d2/d1/OWNERS:[ d1d1o@g, d2o@g ] }");
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
    String owners = "owners:[ " + ownerJson("pAd1f1@g") + ", " + ownerJson("pAd2@g") + ", "
        + ownerJson("pAf1@g") + ", " + ownerJson("pBd1f1@g") + ", " + ownerJson("pBd2f2@g")
        + ", " + ownerJson("pBf1@g") + ", " + ownerJson("pA@g", 0, 1, 0) + " ]";
    assertThat(getOwnersResponse(c1)).contains(owners);
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

  @Test
  public void requestErrorTest() throws Exception {
    PushOneCommit.Result c1 = createChange("1", "t.c", "##");
    assertThat(getOwnersResponse(c1)).contains("owners:[], files:[ t.c ]");
    int id = c1.getChange().getId().get();
    // Correct change id.
    String result = userRestSession.get("/changes/" + id + "/owners").getEntityContent();
    assertThat(filteredJson(result)).contains("owners:[], files:[ t.c ]");
    // Wrong change number, 404 not found.
    RestResponse response = userRestSession.get("/changes/" + (id + 1) + "/owners");
    assertThat(response.getStatusCode()).isEqualTo(404);
    assertThat(response.getEntityContent()).isEqualTo("Not found: " + (id + 1));
    // Wrong request parameter, 400 not a valid option
    response = userRestSession.get("/changes/" + id + "/owners?xyz=3");
    assertThat(response.getStatusCode()).isEqualTo(400);
    assertThat(response.getEntityContent()).isEqualTo("\"--xyz\" is not a valid option");
    // Wrong patchset parameter, no content
    response = userRestSession.get("/changes/" + id + "/owners?patchset=2");
    assertThat(response.getStatusCode()).isEqualTo(204);
    assertThat(response.hasContent()).isFalse();
  }

  @Test
  public void accountTest() throws Exception {
    String[] users = {"user1", "user2", "user3"};
    String[] emails1 = {"abc@g.com", "abc+xyz@g.com", "xyz-team+review@g.com"};
    String[] emails2 = {"abc@goog.com", "abc+xyz2@g.com", "xyz-team@goog.com"};
    // Create accounts with given user name, first and second email addresses.
    for (int i = 0; i < users.length; i++) {
      accountCreator.create(users[i], emails1[i], "FullName " + users[i]).getId();
      EmailInput input = new EmailInput();
      input.email = emails2[i];
      input.noConfirmation = true;
      gApi.accounts().id(users[i]).addEmail(input);
    }
    // Find accounts with given first and second email addresses.
    // OwnersDb uses either emails.getAccountFor or getAccountsFor to get preferred email addresses.
    Multimap<String, Account.Id> map1 = emails.getAccountsFor(emails1);
    Multimap<String, Account.Id> map2 = emails.getAccountsFor(emails2);
    for (int i = 0; i < users.length; i++) {
      Collection<Account.Id> ids1 = emails.getAccountFor(emails1[i]);
      Collection<Account.Id> ids2 = emails.getAccountFor(emails2[i]);
      Collection<Account.Id> ids3 = map1.get(emails1[i]);
      Collection<Account.Id> ids4 = map2.get(emails2[i]);
      assertThat(ids1).hasSize(1);
      assertThat(ids2).hasSize(1);
      assertThat(ids3).hasSize(1);
      assertThat(ids4).hasSize(1);
      Account.Id id1 = ids1.iterator().next();
      Account.Id id2 = ids2.iterator().next();
      Account.Id id3 = ids3.iterator().next();
      Account.Id id4 = ids4.iterator().next();
      assertThat(id1).isEqualTo(id2); // Both emails should find the same account.
      assertThat(id1).isEqualTo(id3);
      assertThat(id1).isEqualTo(id4);
      // Action.getReviewers and Checker.getVotes use accountCache to get email address.
      Optional<Account> account = accountCache.get(id1).map(AccountState::getAccount);
      assertThat(account).named("account %s", id1).isPresent();
      assertThat(account.get().getPreferredEmail()).isEqualTo(emails1[i]);
    }
    // Wrong or non-existing email address.
    String[] wrongEmails = {"nobody", "@g.com", "nobody@g.com", "*"};
    Multimap<String, Account.Id> email2ids = emails.getAccountsFor(wrongEmails);
    for (String email : wrongEmails) {
      assertThat(emails.getAccountFor(email)).isEmpty();
      assertThat(email2ids.get(email)).isEmpty();
    }
  }

  @Test
  public void projectTest() throws Exception {
    RestResponse response = adminRestSession.get("/projects/?d");
    String content = response.getEntityContent();
    // Predefined projects: "All-Projects", "All-Users", myProjectName("projectTest")
    assertThat(content).contains("\"id\": \"All-Projects\",");
    assertThat(content).contains("\"id\": \"All-Users\",");
    assertThat(content).contains(idProject("projectTest", "project"));
    assertThat(content).doesNotContain(idProject("projectTest", "ProjectA"));
    newProject("ProjectA");
    response = adminRestSession.get("/projects/?d");
    assertThat(response.getEntityContent()).contains(idProject("projectTest", "ProjectA"));
  }

  @Test
  public void projectInheritanceTest() throws Exception {
    Config.setVariables("find-owners", configFactory);
    Project.NameKey pA = newProject("Project_A");
    Project.NameKey pB = newProject("Project_B", pA);
    Project.NameKey pC = newProject("Project_C", pB);
    assertThat(projectOwnersFileName(pA)).isEqualTo("OWNERS");
    assertThat(projectOwnersFileName(pB)).isEqualTo("OWNERS");
    assertThat(projectOwnersFileName(pC)).isEqualTo("OWNERS");
    switchProject(pA);
    setProjectConfig("ownersFileName", "OWNERS_A");
    assertThat(projectOwnersFileName(pA)).isEqualTo("OWNERS_A");
    assertThat(projectOwnersFileName(pB)).isEqualTo("OWNERS_A");
    assertThat(projectOwnersFileName(pC)).isEqualTo("OWNERS_A");
    switchProject(pC);
    setProjectConfig("ownersFileName", "OWNERS_C");
    assertThat(projectOwnersFileName(pA)).isEqualTo("OWNERS_A");
    assertThat(projectOwnersFileName(pB)).isEqualTo("OWNERS_A");
    assertThat(projectOwnersFileName(pC)).isEqualTo("OWNERS_C");
    switchProject(pB);
    setProjectConfig("ownersFileName", "OWNERS_B");
    assertThat(projectOwnersFileName(pA)).isEqualTo("OWNERS_A");
    assertThat(projectOwnersFileName(pB)).isEqualTo("OWNERS_B");
    assertThat(projectOwnersFileName(pC)).isEqualTo("OWNERS_C");
    switchProject(pC);
    setProjectConfig("ownersFileName", "");
    assertThat(projectOwnersFileName(pC)).isEqualTo("OWNERS_B");
  }

  @Test
  public void ownersFileNameTest() throws Exception {
    Config.setVariables("find-owners", configFactory);
    // Default project is something like ....FindOwnersIT..._project
    Project.NameKey pA = newProject("Project_A");
    Project.NameKey pB = newProject("Project_B");
    // Add OWNERS and OWNERS.alpha file to Project_A.
    switchProject(pA);
    createBranch("BranchX");
    addFile("1", "OWNERS", "per-file *.c=x@x\n"); // default owner x@x
    addFile("2", "OWNERS.alpha", "per-file *.c=a@a\n"); // alpha owner a@a
    PushOneCommit.Result cA = createChange("cA", "tA.c", "Hello A!");
    PushOneCommit.Result cX = createChangeInBranch("BranchX", "cX", "tX.c", "Hello X!");
    // Add OWNERS and OWNERS.beta file to Project_B.
    switchProject(pB);
    createBranch("BranchY");
    addFile("3", "OWNERS", "per-file *.c=y@y\n"); // default owner y@y
    addFile("4", "OWNERS.beta", "per-file *.c=b@b\n"); // beta owner b@b
    PushOneCommit.Result cB = createChange("cB", "tB.c", "Hello B!");
    PushOneCommit.Result cY = createChangeInBranch("BranchY", "cY", "tY.c", "Hello Y!");

    // Default owners file name is "OWNERS".
    assertThat(Config.getDefaultOwnersFileName()).isEqualTo("OWNERS");
    assertThat(projectOwnersFileName(pA)).isEqualTo("OWNERS");
    assertThat(projectOwnersFileName(pB)).isEqualTo("OWNERS");

    String ownerX = oneOwnerList("x@x");
    String ownerY = oneOwnerList("y@y");
    String cAResponse = getOwnersDebugResponse(cA);
    String cXResponse = getOwnersDebugResponse(cX);
    String cBResponse = getOwnersDebugResponse(cB);
    String cYResponse = getOwnersDebugResponse(cY);
    assertThat(cAResponse).contains(ownerX + ", files:[ tA.c ]");
    assertThat(cBResponse).contains(ownerY + ", files:[ tB.c ]");
    assertThat(cXResponse).contains(", files:[ tX.c ]");
    assertThat(cYResponse).contains(", files:[ tY.c ]");
    assertThat(cXResponse).doesNotContain(ownerX);
    assertThat(cYResponse).doesNotContain(ownerY);
    assertThat(cAResponse).contains("branch:refs/heads/master");
    assertThat(cBResponse).contains("branch:refs/heads/master");
    assertThat(cXResponse).contains("branch:refs/heads/BranchX");
    assertThat(cYResponse).contains("branch:refs/heads/BranchY");
    assertThat(cAResponse).contains("ownersFileName:OWNERS, ");
    assertThat(cBResponse).contains("ownersFileName:OWNERS, ");
    assertThat(cXResponse).contains("ownersFileName:OWNERS, ");
    assertThat(cYResponse).contains("ownersFileName:OWNERS, ");

    // pA and pB use default OWNERS file name.
    // cA and cB logs should not contain anything about Missing/Found root.
    assertThat(cAResponse).doesNotContain("root");
    assertThat(cBResponse).doesNotContain("root");
    // cX and cY are not for the master branch.
    // They should not contain anything about Missing/Found root.
    assertThat(cXResponse).doesNotContain("root");
    assertThat(cYResponse).doesNotContain("root");

    // Change owners file name to "OWNERS.alpha" and "OWNERS.beta"
    switchProject(pA);
    setProjectConfig("ownersFileName", "OWNERS.alpha");
    switchProject(pB);
    setProjectConfig("ownersFileName", "OWNERS.beta");

    assertThat(projectOwnersFileName(pA)).isEqualTo("OWNERS.alpha");
    assertThat(projectOwnersFileName(pB)).isEqualTo("OWNERS.beta");
    String ownerA = oneOwnerList("a@a");
    String ownerB = oneOwnerList("b@b");
    cAResponse = getOwnersDebugResponse(cA);
    cBResponse = getOwnersDebugResponse(cB);
    cXResponse = getOwnersDebugResponse(cX);
    cYResponse = getOwnersDebugResponse(cY);
    assertThat(cAResponse).contains("ownersFileName:OWNERS.alpha, ");
    assertThat(cBResponse).contains("ownersFileName:OWNERS.beta, ");
    assertThat(cXResponse).contains("ownersFileName:OWNERS.alpha, ");
    assertThat(cYResponse).contains("ownersFileName:OWNERS.beta, ");
    assertThat(cAResponse).contains(ownerA + ", files:[ tA.c ]");
    assertThat(cBResponse).contains(ownerB + ", files:[ tB.c ]");
    // pA and pB now use non-default OWNERS file name.
    // cA and cB logs should contain "Found root ..."
    assertThat(cAResponse).contains("FoundrootOWNERS.alpha");
    assertThat(cBResponse).contains("FoundrootOWNERS.beta");
    assertThat(cXResponse).doesNotContain("root");
    assertThat(cYResponse).doesNotContain("root");

    // Now change owners file name to "MAINTAINERS"
    // logs should contain "Missing root ..."
    switchProject(pA);
    setProjectConfig("ownersFileName", "MAINTAINERS");
    cAResponse = getOwnersDebugResponse(cA);
    cXResponse = getOwnersDebugResponse(cX);
    assertThat(cAResponse).contains("ownersFileName:MAINTAINERS, ");
    assertThat(cXResponse).contains("ownersFileName:MAINTAINERS, ");
    assertThat(cAResponse).contains("owners:[], ");
    assertThat(cXResponse).contains("owners:[], ");
    assertThat(cAResponse).contains("MissingrootMAINTAINERS");
    // Gerrit server log file should contain: "Missing root MAINTAINERS for change "
    // cX is not on the master branch, so we do not check for the root owners file.
    assertThat(cXResponse).doesNotContain("root");

    // Change back to OWNERS in Project_A
    switchProject(pA);
    setProjectConfig("ownersFileName", "OWNERS");
    assertThat(projectOwnersFileName(pA)).isEqualTo("OWNERS");
    cAResponse = getOwnersDebugResponse(cA);
    cBResponse = getOwnersDebugResponse(cB);
    assertThat(cAResponse).contains(ownerX + ", files:[ tA.c ]");
    assertThat(cBResponse).contains(ownerB + ", files:[ tB.c ]");

    // Change back to OWNERS.alpha in Project_B, but there is no OWNERS.alpha
    switchProject(pB);
    setProjectConfig("ownersFileName", "OWNERS.alpha");
    assertThat(projectOwnersFileName(pB)).isEqualTo("OWNERS.alpha");
    cAResponse = getOwnersDebugResponse(cA);
    cBResponse = getOwnersDebugResponse(cB);
    cYResponse = getOwnersDebugResponse(cY);
    assertThat(cAResponse).contains("ownersFileName:OWNERS, ");
    assertThat(cBResponse).contains("ownersFileName:OWNERS.alpha, ");
    assertThat(cAResponse).contains(ownerX + ", files:[ tA.c ]");
    assertThat(cBResponse).contains("owners:[], files:[ tB.c ]");
    assertThat(cBResponse).contains("MissingrootOWNERS.alpha");
    // Gerrit server log file should contain: "Missing root OWNERS.alpha for change "
    assertThat(cYResponse).doesNotContain("root");

    // Do not accept empty string or all-white-spaces for ownersFileName.
    setProjectConfig("ownersFileName", "   ");
    assertThat(projectOwnersFileName(pB)).isEqualTo("OWNERS");
    setProjectConfig("ownersFileName", " \t  ");
    assertThat(projectOwnersFileName(pB)).isEqualTo("OWNERS");
    setProjectConfig("ownersFileName", "O");
    assertThat(projectOwnersFileName(pB)).isEqualTo("O");
  }

  @Test
  public void authorDefaultVoteTest() throws Exception {
    // CL author has default +1 owner vote.
    addFile("1", "d1/OWNERS", user.email + "\n"); // d1 owned by user
    addFile("2", "d2/OWNERS", admin.email + "\n"); // d2 owned by admin
    // admin is the author of CLs created by createChange.
    PushOneCommit.Result r1 = createChange("r1", "d1/t.c", "Hello1");
    PushOneCommit.Result r2 = createChange("r2", "d2/t.c", "Hello2");
    PushOneCommit.Result r3 = createChange("r3", "d3/t.c", "Hello3");
    assertThat(checkApproval(r1)).isEqualTo(-1); // owner is not change author
    assertThat(checkApproval(r2)).isEqualTo(1); // owner is change author, default +1
    assertThat(checkApproval(r3)).isEqualTo(0); // no owner is found in d3
  }

  @Test
  public void actionApplyTest() throws Exception {
    Cache cache = Cache.getInstance().init(0, 10);
    assertThat(cache.size()).isEqualTo(0);
    // TODO: create ChangeInput in a new project.
    ChangeInfo changeInfo = newChangeInfo("test Action.apply");
    ChangeResource cr = parseChangeResource(changeInfo.changeId);
    Action.Parameters param = new Action.Parameters();
    Action action =
        new Action(
            "find-owners",
            null,
            null,
            changeDataFactory,
            accountCache,
            emails,
            repoManager,
            projectCache);
    Response<RestResult> response = action.apply(cr, param);
    RestResult result = response.value();
    verifyRestResult(result, 1, 1, changeInfo._number, false);
    param.debug = true;
    response = action.apply(cr, param);
    result = response.value();
    verifyRestResult(result, 1, 1, changeInfo._number, true);
    assertThat(result.dbgmsgs.user).isEqualTo("?");
    assertThat(result.dbgmsgs.project).isEqualTo(changeInfo.project);
    // changeInfo.branch is "master" but result.dbgmsgs.branch is "refs/heads/master".
    assertThat(result.dbgmsgs.branch).contains(changeInfo.branch);
    assertThat(result.dbgmsgs.path2owners).isEmpty();
    assertThat(result.dbgmsgs.owner2paths).isEmpty();
    assertThat(result.file2owners).isEmpty();
    assertThat(result.reviewers).isEmpty();
    assertThat(result.owners).isEmpty();
    assertThat(result.files).isEmpty();
    // TODO: find expected value of ownerRevision.
    assertThat(cache.size()).isEqualTo(0);
  }

  private String oneOwnerList(String email) {
    return "owners:[ " + ownerJson(email) + " ]";
  }

  private String ownerJson(String email) {
    return "{ email:" + email + ", weights:[ 1, 0, 0 ] }";
  }

  private String ownerJson(String email, int w1, int w2, int w3) {
    return "{ email:" + email + ", weights:[ " + w1 + ", " + w2 + ", " + w3 + " ] }";
  }

  private ChangeInfo newChangeInfo(String subject) throws Exception {
    // should be called with different subject
    ChangeInput in = new ChangeInput();
    in.project = project.get();
    in.branch = "master";
    in.subject = subject;
    in.topic = "test empty change";
    in.status = ChangeStatus.NEW;
    return gApi.changes().create(in).get();
  }

  private String getFindOwnersResponse(ChangeInfo info) throws Exception {
    return filteredJson(
        userRestSession.get("/changes/" + info._number + "/revisions/1/find-owners"));
  }

  private String getOwnersResponse(ChangeInfo info) throws Exception {
    return filteredJson(userRestSession.get("/changes/" + info._number + "/owners"));
  }

  private String getOwnersResponse(PushOneCommit.Result change) throws Exception {
    return filteredJson(userRestSession.get("/changes/" + change.getChangeId() + "/owners"));
  }

  private String getOwnersDebugResponse(PushOneCommit.Result change) throws Exception {
    return filteredJson(
        userRestSession.get("/changes/" + change.getChangeId() + "/owners?debug=1"));
  }

  private void approveSubmit(PushOneCommit.Result change) throws Exception {
    approve(change.getChangeId());
    gApi.changes().id(change.getChangeId()).current().submit(new SubmitInput());
  }

  private PushOneCommit.Result addFile(
      String subject, String file, String content) throws Exception {
    PushOneCommit.Result c = createChange(subject, file, content);
    approveSubmit(c);
    return c;
  }

  private void switchProject(Project.NameKey p) throws Exception {
    project = p;
    testRepo = cloneProject(project);
  }

  private org.eclipse.jgit.lib.Config readProjectConfig() throws Exception {
    git().fetch().setRefSpecs(new RefSpec(REFS_CONFIG + ":" + REFS_CONFIG)).call();
    testRepo.reset(RefNames.REFS_CONFIG);
    RevWalk rw = testRepo.getRevWalk();
    RevTree tree = rw.parseTree(testRepo.getRepository().resolve("HEAD"));

    try (TreeWalk treeWalk = new TreeWalk(rw.getObjectReader())) {
      treeWalk.setFilter(PathFilterGroup.createFromStrings("project.config"));
      treeWalk.reset(tree);
      boolean hasProjectConfig = treeWalk.next();
      if (!hasProjectConfig) {
        return new org.eclipse.jgit.lib.Config();
      }
    }

    RevObject obj = rw.parseAny(testRepo.get(tree, "project.config"));
    ObjectLoader loader = rw.getObjectReader().open(obj);
    String text = new String(loader.getCachedBytes(), UTF_8);
    org.eclipse.jgit.lib.Config cfg = new org.eclipse.jgit.lib.Config();
    cfg.fromText(text);
    return cfg;
  }

  private void setProjectConfig(String var, String value) throws Exception {
    org.eclipse.jgit.lib.Config cfg = readProjectConfig();
    cfg.setString("plugin", "find-owners", var, value);
    assertThat(cfg.getString("plugin", "find-owners", var)).isEqualTo(value);
    PushOneCommit.Result commit =
        pushFactory
            .create(
                admin.getIdent(), // normal user cannot change refs/meta/config
                testRepo,
                "Update project config",
                "project.config",
                cfg.toText())
            .to("refs/for/" + REFS_CONFIG);
    commit.assertOkStatus();
    approveSubmit(commit);
  }

  private int checkApproval(PushOneCommit.Result r) throws Exception {
    Project.NameKey project = r.getChange().project();
    Cache cache = Cache.getInstance().init(0, 0);
    OwnersDb db = cache.get(true, projectCache.get(project), accountCache, emails,
                            repoManager, r.getChange(), 1);
    Checker c = new Checker(repoManager, r.getChange(), 1);
    return c.findApproval(accountCache, db);
  }

  // Remove '"' and space; replace '\n' with ' '; ignore "owner_revision" and "HostName:*".
  private static String filteredJson(String json) {
    return json.replaceAll("[\" ]*", "").replace('\n', ' ').replaceAll("owner_revision:[^ ]* ", "")
        .replaceAll("HostName:[^ ]*, ", "");
  }

  private static String filteredJson(RestResponse response) throws Exception {
    return filteredJson(response.getEntityContent());
  }

  private String myProjectName(String test) {
    return myProjectName(test, "project");
  }

  private String myProjectName(String test, String project) {
    return this.getClass().getName() + "_" + test + "_" + project;
  }

  private String idProject(String test, String project) {
    // Expected string of "id": "project_name",
    return "\"id\": \"" + myProjectName(test, project) + "\",";
  }

  private static void verifyRestResult(
      RestResult result, int voteLevel, int patchset, int changeNumber, boolean addDebugMsg)
      throws Exception {
    assertThat(result.minOwnerVoteLevel).isEqualTo(voteLevel);
    assertThat(result.patchset).isEqualTo(patchset);
    assertThat(result.change).isEqualTo(changeNumber);
    assertThat(result.addDebugMsg).isEqualTo(addDebugMsg);
    if (addDebugMsg) {
      assertThat(result.dbgmsgs).isNotNull();
    } else {
      assertThat(result.dbgmsgs).isNull();
    }
  }

  private BranchApi createBranch(String branch) throws Exception {
    return createBranch(new Branch.NameKey(project, branch));
  }

  private PushOneCommit.Result createChangeInBranch(
      String branch, String subject, String fileName, String content) throws Exception {
    PushOneCommit push = pushFactory.create(admin.getIdent(), testRepo, subject, fileName, content);
    return push.to("refs/for/" + branch);
  }

  private Project.NameKey newProject(String name) {
    return newProject(name, project);
  }

  private Project.NameKey newProject(String myName, Project.NameKey parent) {
    return projectOperations.newProject().parent(parent).name(name(myName)).create();
  }

  private String projectOwnersFileName(Project.NameKey name) {
    return Config.getOwnersFileName(projectCache.get(name), null);
  }
}
