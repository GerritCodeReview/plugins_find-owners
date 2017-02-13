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
import com.google.gerrit.reviewdb.client.Change.Status;
import com.google.gerrit.server.change.RevisionResource;
import com.google.gson.JsonArray;
import com.googlesource.gerrit.plugins.findowners.Util.String2Integer;
import com.googlesource.gerrit.plugins.findowners.Util.String2String;
import com.googlesource.gerrit.plugins.findowners.Util.StringSet;
import java.util.Collection;
import org.eclipse.jgit.lib.Repository;
import org.junit.Test;

/** Mocked server to test Action, Checker, OwnersDb classes. */
public class MockedServer extends Server {

  int minOwnerVoteLevel;
  boolean addDebugMsg;
  boolean traceServer;
  boolean reportSyntaxError;
  boolean exemptFromOwnerApproval;
  StringSet changedFiles;
  String2Integer votes;
  JsonArray reviewers;
  MockedOwnersDb ownersDb;
  Status status;
  String branchRevision;
  String2String dir2owners; // map from a directory path to OWNERS content

  public MockedServer() {
    change = 12;
    patchset = 3;
    project = "projectA";
    branch = "master";
    error = null;
    minOwnerVoteLevel = 1;
    addDebugMsg = true;
    traceServer = true;
    reportSyntaxError = true;
    exemptFromOwnerApproval = false;
    changedFiles = new StringSet();
    String[] sampleFiles = {"./README", "./d1/test.c", "./d2/t.txt"};
    for (String file : sampleFiles) {
      changedFiles.add(file);
    }
    votes = new String2Integer();
    reviewers = new JsonArray();
    ownersDb = new MockedOwnersDb();
    status = Status.NEW;
    branchRevision = "13579abcdef";
    dir2owners = new String2String();
  }

  @Override
  int getMinOwnerVoteLevel() {
    return minOwnerVoteLevel;
  }

  @Override
  boolean getAddDebugMsg() {
    return addDebugMsg;
  }

  @Override
  boolean traceServerMsg() {
    return traceServer;
  }

  @Override
  boolean getReportSyntaxError() {
    return reportSyntaxError;
  }

  @Override
  boolean isExemptFromOwnerApproval() {
    return exemptFromOwnerApproval;
  }

  @Override
  void setChangeId(String url, int change) {}

  @Override
  String setPatchId(String patchsetNum) {
    return null;
  }

  @Override
  Collection<String> getChangedFiles() {
    return changedFiles;
  }

  @Override
  String2Integer getVotes() {
    return votes;
  }

  @Override
  JsonArray getReviewers() {
    return reviewers;
  }

  @Override
  Status getStatus(RevisionResource resource) {
    return status;
  }

  @Override
  String getOWNERS(String dir, Repository repository, String url,
                   String project, String branch) {
    String content = dir2owners.get(dir);
    return (null == content ? "" : content);
  }

  @Override
  String getBranchRevision(Repository repository, String url,
                            String project, String branch) {
    return branchRevision;
  }

  @Override
  OwnersDb getCachedOwnersDb() {
    return ownersDb;
  }

  @Test
  public void genDebugMsgTest() {
    // Important because real server.traceServerMsg() is usually false.
    String expected =
        "\n## change=12, patchset=3, project=projectA, branch=master"
        + "\n## changedFiles=" + changedFiles
        + "\nnumOwners=0, minVoteLevel=1"
        + ", approvals=" + getVotes();
    assertThat(genDebugMsg(ownersDb)).isEqualTo(expected);
    url = "http://localhost:8081/";
    String expected2 = "\n## url=" + url + expected;
    assertThat(genDebugMsg(ownersDb)).isEqualTo(expected2);
  }

  // TODO: use a mocked Repository to test getRepositoryFile
}
