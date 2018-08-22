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
import com.google.gerrit.extensions.api.accounts.EmailInput;
import com.google.gerrit.extensions.api.changes.SubmitInput;
import com.google.gerrit.extensions.client.ChangeStatus;
import com.google.gerrit.extensions.common.ChangeInfo;
import com.google.gerrit.extensions.common.ChangeInput;
import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.reviewdb.client.Account;
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
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.RefSpec;
import org.junit.Test;

/** Test find-owners plugin API. */
@TestPlugin(name = "find-owners", sysModule = "com.googlesource.gerrit.plugins.findowners.Module")
public class FindOwnersIT extends LightweightPluginDaemonTest {

  @Inject private Emails emails;
  @Inject private PluginConfigFactory configFactory;

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
  public void ownersFile1Test() throws Exception {
    // Create 1st OWNERS file, this change does not have owner.
    PushOneCommit.Result c1 = createChange("add OWNERS", "OWNERS", "x@x\na@a\n");
    assertThat(getOwnersResponse(c1)).contains("owners:[], files:[ OWNERS ]");
    // Create another "t.c" file, which has no owner because c1 is not submitted yet.
    PushOneCommit.Result c2 = createChange("add t.c", "t.c", "##");
    assertThat(getOwnersResponse(c2)).contains("owners:[], files:[ t.c ]");
    // Change of "t.c" file has owner after c1 is submitted.
    approveSubmit(c1);
    String ownerA = ownerJson("a@a");
    String ownerX = ownerJson("x@x");
    String ownersAX = "owners:[ " + ownerA + ", " + ownerX + " ]";
    assertThat(getOwnersResponse(c2)).contains(ownersAX + ", files:[ t.c ]");
    // A submitted change gets owners info from current repository.
    assertThat(getOwnersResponse(c1)).contains(ownersAX + ", files:[ OWNERS ]");
    // Check all fields in response.
    String expectedTail =
        "path2owners:{ ./:[ a@a, x@x ] }, owner2paths:{ a@a:[ ./ ], x@x:[ ./ ] }, logs:[ "
            + "key:12:1:master, ownersFileName:OWNERS, getBranchId:refs/heads/master(FOUND), "
            + "findOwnersFileFor:./t.c, findOwnersFileIn:., getRepositoryFile:OWNERS:x@x\\na@a\\n, "
            + "countNumOwners, findOwners, checkFile:./t.c, checkDir:., addOwnerWeightsIn:./ ] "
            + "}, file2owners:{ ./t.c:[ a@a, x@x ] }, reviewers:[], "
            + ownersAX
            + ", files:[ t.c ] }";
    assertThat(getOwnersDebugResponse(c2)).contains(expectedTail);
  }

  @Test
  public void ownersFile2Test() throws Exception {
    // Add OWNERS file, this test does not inherit files created in ownersFile1Test.
    addFile("add OWNERS", "OWNERS", "per-file *.c=x@x\na@a\nc@c\nb@b\n");
    // Add "t.c" file, which has per-file owner x@x, not a@a, b@b, c@c.
    PushOneCommit.Result c2 = createChange("add t.c", "t.c", "Hello!");
    String ownerA = ownerJson("a@a");
    String ownerB = ownerJson("b@b");
    String ownerC = ownerJson("c@c");
    String ownerX = ownerJson("x@x");
    assertThat(getOwnersResponse(c2)).contains("owners:[ " + ownerX + " ], files:[ t.c ]");
    // Add "t.txt" file, which has new owners.
    PushOneCommit.Result c3 = createChange("add t.txt", "t.txt", "Test!");
    assertThat(getOwnersResponse(c3))
        .contains("owners:[ " + ownerA + ", " + ownerB + ", " + ownerC + " ], files:[ t.txt ]");
  }

  @Test
  public void subOwnersFileTest() throws Exception {
    // Add OWNERS file in root and subdirectories.
    addFile("add OWNERS", "OWNERS", "x@x\n");
    addFile("add d1/OWNERS", "d1/OWNERS", "a@a\n");
    addFile("add d2/OWNERS", "d2/OWNERS", "y@y\n");
    addFile("add d3/OWNERS", "d3/OWNERS", "b@b\nset noparent\n");
    // Add "t.c" file, which is not owned by subdirectory owners.
    PushOneCommit.Result c2 = createChange("add t.c", "t.c", "Hello!");
    String ownerA = ownerJson("a@a");
    String ownerX = ownerJson("x@x");
    assertThat(getOwnersResponse(c2)).contains("owners:[ " + ownerX + " ], files:[ t.c ]");
    // Add "d1/t.c" file, which is owned by ./d1 and root owners.
    PushOneCommit.Result c3 = createChange("add d1/t.c", "d1/t.c", "Hello!");
    String ownerX010 = ownerJson("x@x", 0, 1, 0);
    assertThat(getOwnersResponse(c3))
        .contains("owners:[ " + ownerA + ", " + ownerX010 + " ], files:[ d1/t.c ]");
    // Add "d2/t.c" file, which is owned by ./d2 and root owners.
    PushOneCommit.Result c4 = createChange("add d2/t.c", "d2/t.c", "Hello!");
    String ownerY = ownerJson("y@y");
    assertThat(getOwnersResponse(c4))
        .contains("owners:[ " + ownerY + ", " + ownerX010 + " ], files:[ d2/t.c ]");
    // Add "d2/d1/t.c" file, which is owned by ./d2 and root owners.
    PushOneCommit.Result c5 = createChange("add d2/d1/t.c", "d2/d1/t.c", "Hello!");
    assertThat(getOwnersResponse(c5))
        .contains("owners:[ " + ownerY + ", " + ownerX010 + " ], files:[ d2/d1/t.c ]");
    // Add "d3/t.c" file, which is owned only by ./d3 owners due to "set noparent".
    PushOneCommit.Result c6 = createChange("add d3/t.c", "d3/t.c", "Hello!");
    String ownerB = ownerJson("b@b");
    assertThat(getOwnersResponse(c6)).contains("owners:[ " + ownerB + " ], files:[ d3/t.c ]");
    // Add "d3/d1/t.c" file, which is owned only by ./d3 owners due to "set noparent".
    PushOneCommit.Result c7 = createChange("add d3/d1/t.c", "d3/d1/t.c", "Hello!");
    assertThat(getOwnersResponse(c7)).contains("owners:[ " + ownerB + " ], files:[ d3/d1/t.c ]");
  }

  @Test
  public void requestErrorTest() throws Exception {
    PushOneCommit.Result c1 = createChange("add t.c", "t.c", "##");
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
    // Predefined projects: "All-Projects", "All-Users",
    // "com.googlesource.gerrit.plugins.findowners.FindOwnersIT_projectTest_project"
    assertThat(content).contains("\"id\": \"All-Projects\",");
    assertThat(content).contains("\"id\": \"All-Users\",");
    assertThat(content).contains(idProject("projectTest", "project"));
    assertThat(content).doesNotContain(idProject("projectTest", "ProjectA"));
    createProject("ProjectA");
    response = adminRestSession.get("/projects/?d");
    assertThat(response.getEntityContent()).contains(idProject("projectTest", "ProjectA"));
  }

  @Test
  public void ownersFileNameTest() throws Exception {
    Config.setVariables("find-owners", configFactory);

    // Default project is something like ....FindOwnersIT..._project
    Project.NameKey pA = createProject("Project_A");
    Project.NameKey pB = createProject("Project_B");
    switchProject(pA);
    // Now project is ....FindOwnersIT..._Project_A
    switchProject(pB);
    // Now project is ....FindOwnersIT..._Project_B

    // Add OWNERS and OWNERS.alpha file to Project_A.
    switchProject(pA);
    addFile("add OWNERS", "OWNERS", "per-file *.c=x@x\n"); // default owner x@x
    addFile("add OWNERS.alpha", "OWNERS.alpha", "per-file *.c=a@a\n"); // alpha owner a@a
    PushOneCommit.Result cA = createChange("add tA.c", "tA.c", "Hello A!");

    // Add OWNERS and OWNERS.beta file to Project_B.
    switchProject(pB);
    addFile("add OWNERS", "OWNERS", "per-file *.c=y@y\n"); // default owner y@y
    addFile("add OWNERS.beta", "OWNERS.beta", "per-file *.c=b@b\n"); // beta owner b@b
    PushOneCommit.Result cB = createChange("add tB.c", "tB.c", "Hello B!");

    // Default owners file name is "OWNERS".
    assertThat(Config.getDefaultOwnersFileName()).isEqualTo("OWNERS");
    assertThat(Config.getOwnersFileName(projectCache.get(pA), null)).isEqualTo("OWNERS");
    assertThat(Config.getOwnersFileName(projectCache.get(pB), null)).isEqualTo("OWNERS");

    String ownerX = oneOwnerList("x@x");
    String ownerY = oneOwnerList("y@y");
    assertThat(getOwnersResponse(cA)).contains(ownerX + ", files:[ tA.c ]");
    assertThat(getOwnersResponse(cB)).contains(ownerY + ", files:[ tB.c ]");

    // Change owners file name to "OWNERS.alpha" and "OWNERS.beta"
    switchProject(pA);
    setProjectConfig("ownersFileName", "OWNERS.alpha");
    switchProject(pB);
    setProjectConfig("ownersFileName", "OWNERS.beta");

    assertThat(Config.getOwnersFileName(projectCache.get(pA), null)).isEqualTo("OWNERS.alpha");
    assertThat(Config.getOwnersFileName(projectCache.get(pB), null)).isEqualTo("OWNERS.beta");
    String ownerA = oneOwnerList("a@a");
    String ownerB = oneOwnerList("b@b");
    assertThat(getOwnersResponse(cA)).contains(ownerA + ", files:[ tA.c ]");
    assertThat(getOwnersResponse(cB)).contains(ownerB + ", files:[ tB.c ]");

    // Change back to OWNERS in Project_A
    switchProject(pA);
    setProjectConfig("ownersFileName", "OWNERS");
    assertThat(Config.getOwnersFileName(projectCache.get(pA), null)).isEqualTo("OWNERS");
    assertThat(getOwnersResponse(cA)).contains(ownerX + ", files:[ tA.c ]");
    assertThat(getOwnersResponse(cB)).contains(ownerB + ", files:[ tB.c ]");

    // Change back to OWNERS.alpha in Project_B, but there is no OWNERS.alpha
    switchProject(pB);
    setProjectConfig("ownersFileName", "OWNERS.alpha");
    assertThat(Config.getOwnersFileName(projectCache.get(pB), null)).isEqualTo("OWNERS.alpha");
    assertThat(getOwnersResponse(cA)).contains(ownerX + ", files:[ tA.c ]");
    assertThat(getOwnersResponse(cB)).contains("owners:[], files:[ tB.c ]");

    // Do not accept empty string or all-white-spaces for ownersFileName.
    setProjectConfig("ownersFileName", "   ");
    assertThat(Config.getOwnersFileName(projectCache.get(pB), null)).isEqualTo("OWNERS");
    setProjectConfig("ownersFileName", " \t  ");
    assertThat(Config.getOwnersFileName(projectCache.get(pB), null)).isEqualTo("OWNERS");
    setProjectConfig("ownersFileName", "O");
    assertThat(Config.getOwnersFileName(projectCache.get(pB), null)).isEqualTo("O");
  }

  @Test
  public void authorDefaultVoteTest() throws Exception {
    // CL author has default +1 owner vote.
    addFile("add d1/OWNERS", "d1/OWNERS", user.email + "\n"); // d1 owned by user
    addFile("add d2/OWNERS", "d2/OWNERS", admin.email + "\n"); // d2 owned by admin
    // admin is the author of CLs created by createChange.
    PushOneCommit.Result r1 = createChange("add d1/t.c", "d1/t.c", "Hello1");
    PushOneCommit.Result r2 = createChange("add d2/t.c", "d2/t.c", "Hello2");
    PushOneCommit.Result r3 = createChange("add d3/t.c", "d3/t.c", "Hello3");
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
            null,
            changeDataFactory,
            accountCache,
            emails,
            repoManager,
            projectCache);
    Response<RestResult> response = action.apply(db, cr, param);
    RestResult result = response.value();
    verifyRestResult(result, 1, 1, changeInfo._number, false);
    param.debug = true;
    response = action.apply(db, cr, param);
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

  private void addFile(String subject, String file, String content) throws Exception {
    approveSubmit(createChange(subject, file, content));
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
                db,
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
    Repository repo = repoManager.openRepository(project);
    Cache cache = Cache.getInstance().init(0, 0);
    OwnersDb db =
        cache.get(true, projectCache.get(project), accountCache, emails, repo, r.getChange(), 1);
    Checker c = new Checker(repo, r.getChange(), 1);
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

  private static String idProject(String test, String project) {
    // Expected string of "id": "project_name",
    return String.format(
        "\"id\": \"com.googlesource.gerrit.plugins.findowners.FindOwnersIT_" + "%s_%s\",",
        test, project);
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
}
