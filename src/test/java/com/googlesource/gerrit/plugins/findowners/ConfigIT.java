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
import com.google.gerrit.reviewdb.client.Project;
import org.junit.Test;

/** Test find-owners plugin config variables. */
public class ConfigIT extends FindOwnersIT {

  @Test
  public void projectInheritanceTest() throws Exception {
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
  }

  @Test
  public void ownersFileNameTest() throws Exception {
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
    assertThat(Config.OWNERS).isEqualTo("OWNERS");
    assertThat(config.getDefaultOwnersFileName()).isEqualTo("OWNERS");
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
}
