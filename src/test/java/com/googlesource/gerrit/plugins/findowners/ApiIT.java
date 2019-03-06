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
import com.google.gerrit.acceptance.RestResponse;
import com.google.gerrit.extensions.common.ChangeInfo;
import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.server.change.ChangeResource;
import org.junit.Test;

/** Test find-owners plugin top level APIs. */
public class ApiIT extends FindOwnersIT {

  @Test
  public void getOwnersTest() throws Exception {
    ChangeInfo info1 = newChangeInfo("test1 GetOwners");
    ChangeInfo info2 = newChangeInfo("test2 GetOwners");
    assertThat(info2._number).isEqualTo(info1._number + 1);
    String expected =
        ")]}' { minOwnerVoteLevel:1, addDebugMsg:false, change:"
            + info1._number
            + ", patchset:1, file2owners:{}, reviewers:[], owners:[], files:[] }";
    Cache cache = getCache().init(0, 10); // reset, no Cache
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
    Cache cache = getCache().init(0, 10);
    assertThat(cache.size()).isEqualTo(0);
    // TODO: create ChangeInput in a new project.
    ChangeInfo changeInfo = newChangeInfo("test Action.apply");
    ChangeResource cr = parseChangeResource(changeInfo.changeId);
    Action.Parameters param = new Action.Parameters();
    Action action =
        new Action(
            pluginConfig,
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
}
