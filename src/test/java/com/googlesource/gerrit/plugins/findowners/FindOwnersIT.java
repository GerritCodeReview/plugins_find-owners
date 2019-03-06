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
import com.google.gerrit.reviewdb.client.Account;
import com.google.gerrit.reviewdb.client.Branch;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.reviewdb.client.RefNames;
import com.google.gerrit.server.account.AccountState;
import com.google.gerrit.server.account.Emails;
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
import org.junit.Before;
import org.junit.Test;

/** Test find-owners plugin API core features. */
@TestPlugin(name = "find-owners", sysModule = "com.googlesource.gerrit.plugins.findowners.Module")
public class FindOwnersIT extends LightweightPluginDaemonTest {

  @Inject protected Emails emails;
  @Inject protected ProjectOperations projectOperations;
  protected Config config;

  @Before
  public void setConfig() {
    config = new Config(pluginConfig);
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
      assertThat(email2ids).doesNotContainKey(email);
    }
  }

  @Test
  public void projectTest() throws Exception {
    RestResponse response = adminRestSession.get("/projects/?d");
    String content = response.getEntityContent();
    // Predefined projects: "All-Projects", "All-Users", project
    assertThat(content).contains("\"id\": \"All-Projects\",");
    assertThat(content).contains("\"id\": \"All-Users\",");
    assertThat(content).contains(idProject("projectTest", "project"));
    assertThat(content).doesNotContain(idProject("projectTest", "ProjectA"));
    newProject("ProjectA");
    response = adminRestSession.get("/projects/?d");
    assertThat(response.getEntityContent()).contains(idProject("projectTest", "ProjectA"));
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
    // should be called with different subject
    ChangeInput in = new ChangeInput();
    in.project = project.get();
    in.branch = "master";
    in.subject = subject;
    in.topic = "test empty change";
    in.status = ChangeStatus.NEW;
    return gApi.changes().create(in).get();
  }

  protected String getFindOwnersResponse(ChangeInfo info) throws Exception {
    return filteredJson(
        userRestSession.get("/changes/" + info._number + "/revisions/1/find-owners"));
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

  protected PushOneCommit.Result addFile(
      String subject, String file, String content) throws Exception {
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

  protected int checkApproval(PushOneCommit.Result r) throws Exception {
    Project.NameKey project = r.getChange().project();
    Cache cache = getCache().init(0, 0);
    OwnersDb db = cache.get(true, projectCache.get(project), accountCache, emails,
                            repoManager, pluginConfig, r.getChange(), 1);
    Checker c = new Checker(repoManager, pluginConfig, null, r.getChange(), 1);
    return c.findApproval(accountCache, db);
  }

  // Remove '"' and space; replace '\n' with ' '; ignore "owner_revision" and "HostName:*".
  protected static String filteredJson(String json) {
    return json.replaceAll("[\" ]*", "").replace('\n', ' ').replaceAll("owner_revision:[^ ]* ", "")
        .replaceAll("HostName:[^ ]*, ", "");
  }

  protected static String filteredJson(RestResponse response) throws Exception {
    return filteredJson(response.getEntityContent());
  }

  protected String myProjectName(String test, String project) {
    return this.getClass().getName() + "_" + test + "_" + project;
  }

  protected String idProject(String test, String project) {
    // Expected string of "id": "project_name",
    return "\"id\": \"" + myProjectName(test, project) + "\",";
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
    return createBranch(new Branch.NameKey(project, branch));
  }

  protected PushOneCommit.Result createChangeInBranch(
      String branch, String subject, String fileName, String content) throws Exception {
    PushOneCommit push = pushFactory.create(admin.getIdent(), testRepo, subject, fileName, content);
    return push.to("refs/for/" + branch);
  }

  protected Project.NameKey newProject(String name) {
    return newProject(name, project);
  }

  protected Project.NameKey newProject(String myName, Project.NameKey parent) {
    return projectOperations.newProject().parent(parent).name(name(myName)).create();
  }

  protected String projectOwnersFileName(Project.NameKey name) {
    return config.getOwnersFileName(projectCache.get(name), null);
  }

  protected Cache getCache() {
    return Cache.getInstance(pluginConfig, repoManager);
  }
}
