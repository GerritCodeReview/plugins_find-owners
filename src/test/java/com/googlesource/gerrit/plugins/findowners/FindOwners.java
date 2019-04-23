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
import static com.google.gerrit.reviewdb.client.RefNames.REFS_CONFIG;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.gerrit.acceptance.LightweightPluginDaemonTest;
import com.google.gerrit.acceptance.PushOneCommit;
import com.google.gerrit.acceptance.RestResponse;
import com.google.gerrit.acceptance.testsuite.project.ProjectOperations;
import com.google.gerrit.extensions.api.changes.SubmitInput;
import com.google.gerrit.extensions.api.projects.BranchApi;
import com.google.gerrit.extensions.client.ChangeStatus;
import com.google.gerrit.extensions.common.ChangeInfo;
import com.google.gerrit.extensions.common.ChangeInput;
import com.google.gerrit.reviewdb.client.BranchNameKey;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.reviewdb.client.RefNames;
import com.google.gerrit.server.account.Emails;
import com.google.inject.Inject;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilterGroup;
import org.junit.Before;

/** Abstract base class for find-owners plugin integration tests. */
public abstract class FindOwners extends LightweightPluginDaemonTest {

  @Inject protected Emails emails;
  @Inject protected ProjectOperations projectOperations;

  protected static final String PLUGIN_NAME = "find-owners";
  protected Config config;

  @Before
  public void setConfig() {
    config = new Config(pluginConfig);
  }

  protected String oneOwnerList(String email) {
    return "owners:[ " + ownerJson(email) + " ]";
  }

  protected String ownerJson(String email) {
    return "{ email:" + email + ", weights:[ 1, 0, 0 ] }";
  }

  protected String ownerJson(String email, int w1, int w2, int w3) {
    return "{ email:" + email + ", weights:[ " + w1 + ", " + w2 + ", " + w3 + " ] }";
  }

  protected ChangeInfo newChangeInfo(String subject) throws Exception {
    ChangeInput in = new ChangeInput();
    in.project = project.get();
    in.branch = "master";
    in.subject = subject;
    in.topic = "";
    in.status = ChangeStatus.NEW;
    return gApi.changes().create(in).get();
  }

  protected String getFindOwnersResponse(ChangeInfo info) throws Exception {
    return filteredJson(
        userRestSession.get("/changes/" + info._number + "/revisions/1/" + PLUGIN_NAME));
  }

  protected String getOwnersResponse(ChangeInfo info) throws Exception {
    return filteredJson(userRestSession.get("/changes/" + info._number + "/owners"));
  }

  protected String getOwnersResponse(PushOneCommit.Result change) throws Exception {
    return filteredJson(userRestSession.get("/changes/" + change.getChangeId() + "/owners"));
  }

  protected String getOwnersDebugResponse(PushOneCommit.Result change) throws Exception {
    return filteredJson(
        userRestSession.get("/changes/" + change.getChangeId() + "/owners?debug=1"));
  }

  protected void approveSubmit(PushOneCommit.Result change) throws Exception {
    approve(change.getChangeId());
    gApi.changes().id(change.getChangeId()).current().submit(new SubmitInput());
  }

  protected PushOneCommit.Result addFile(String subject, String file, String content)
      throws Exception {
    PushOneCommit.Result c = createChange(subject, file, content);
    approveSubmit(c);
    return c;
  }

  protected void switchProject(Project.NameKey p) throws Exception {
    project = p;
    testRepo = cloneProject(project);
  }

  protected org.eclipse.jgit.lib.Config readProjectConfig() throws Exception {
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

  protected void setProjectConfig(String var, String value) throws Exception {
    org.eclipse.jgit.lib.Config cfg = readProjectConfig();
    cfg.setString("plugin", PLUGIN_NAME, var, value);
    assertThat(cfg.getString("plugin", PLUGIN_NAME, var)).isEqualTo(value);
    PushOneCommit.Result commit =
        pushFactory
            .create(
                admin.newIdent(), // normal user cannot change refs/meta/config
                testRepo,
                "Update project config",
                "project.config",
                cfg.toText())
            .to("refs/for/" + REFS_CONFIG);
    commit.assertOkStatus();
    approveSubmit(commit);
  }

  protected int checkApproval(PushOneCommit.Result r) throws Exception {
    Project.NameKey project = r.getChange().project();
    Cache cache = getCache().init(0, 0);
    OwnersDb db =
        cache.get(
            true,
            projectCache.get(project),
            accountCache,
            emails,
            repoManager,
            pluginConfig,
            r.getChange(),
            1);
    Checker c = new Checker(repoManager, pluginConfig, null, r.getChange(), 1);
    return c.findApproval(accountCache, db);
  }

  // Remove '"' and space; replace '\n' with ' '; ignore "owner_revision" and "HostName:*".
  protected static String filteredJson(String json) {
    return json.replaceAll("[\" ]*", "")
        .replace('\n', ' ')
        .replaceAll("owner_revision:[^ ]* ", "")
        .replaceAll("HostName:[^ ]*, ", "");
  }

  protected static String filteredJson(RestResponse response) throws Exception {
    return filteredJson(response.getEntityContent());
  }

  protected String myProjectName(String test, String project) {
    return this.getClass().getName() + "_" + test + "_" + project;
  }

  protected String idProject(String test, String project) {
    return idProject(myProjectName(test, project));
  }

  protected String idProject(String name) {
    // Expected string of "id": "name",,
    return "\"id\": \"" + name + "\",";
  }

  protected static void verifyRestResult(
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

  protected BranchApi createBranch(String branch) throws Exception {
    return createBranch(BranchNameKey.create(project, branch));
  }

  protected PushOneCommit.Result createChangeInBranch(
      String branch, String subject, String fileName, String content) throws Exception {
    PushOneCommit push = pushFactory.create(admin.newIdent(), testRepo, subject, fileName, content);
    return push.to("refs/for/" + branch);
  }

  protected Project.NameKey newProject(String name) {
    return newProject(name, Project.nameKey("All-Projects"));
  }

  protected Project.NameKey newProject(String name, Project.NameKey parent) {
    return projectOperations.newProject().parent(parent).name(name).create();
  }

  protected String projectOwnersFileName(Project.NameKey name) {
    return config.getOwnersFileName(projectCache.get(name), null);
  }

  protected Cache getCache() {
    return Cache.getInstance(pluginConfig, repoManager);
  }
}
