package com.googlesource.gerrit.plugins.findowners;

import static com.google.common.truth.Truth.assertThat;
import static com.google.gerrit.acceptance.GitUtil.assertPushOk;
import static com.google.gerrit.acceptance.GitUtil.pushHead;
import static com.google.gerrit.reviewdb.client.RefNames.REFS_CONFIG;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.gerrit.acceptance.AbstractDaemonTest;
import com.google.gerrit.acceptance.GitUtil;
import com.google.gerrit.acceptance.PushOneCommit;
import com.google.gerrit.acceptance.TestAccount;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.common.data.Permission;
import com.google.gerrit.extensions.api.changes.ReviewInput;
import com.google.gerrit.extensions.api.changes.SubmitInput;
import com.google.gerrit.extensions.api.projects.BranchInput;
import com.google.gerrit.extensions.common.ChangeInfo;
import com.google.gerrit.reviewdb.client.RefNames;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.junit.TestRepository;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilterGroup;
import org.junit.Test;

@TestPlugin(name = "find-owners", sysModule = "com.googlesource.gerrit.plugins.findowners.Module")
public class OwnersFileSubmitRuleIT extends AbstractDaemonTest {

  @Test
  public void TestChangeWithoutPermissions() throws Exception {
    createTestRepositoryContent();
    setProjectConfig("enforceLevel", "ENFORCE");
    PushOneCommit.Result r = createCommitAndPush(testRepo, "refs/for/master", "test message", "A/1/foo.c", "void main()\n");
    approve(r.getChangeId());

    ChangeInfo result = gApi.changes().id(r.getChangeId()).get();
    assertThat(result.submittable).isFalse();
  }

  private void createTestRepositoryContent() throws Exception {
    grant(project, "refs/for/master", Permission.PUSH);
    TestRepository<InMemoryRepository>.CommitBuilder cb = testRepo.branch("master").commit();

    cb.add("OWNERS", "alice@example.com\nbob@example.com\n");

    cb.add("A/1/foo.c", "int main()\n");
    cb.add("A/1/bar.c", "int func()\n");
    cb.add("A/1/info.txt", "information\n");
    cb.add("A/1/OWNERS", "xyz@example.com\n");

    cb.add("A/no_inherit/spam.py", "def main()\n");
    cb.add("A/no_inherit/ham.py", "def func()\n");
    cb.add("A/no_inherit/info.txt", "python information\n");
    cb.add("A/no_inherit/OWNERS", "set noparent\nabc@example.com\n");

    cb.message("initial commit");
    cb.insertChangeId();

    cb.create();
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

  private void approveSubmit(PushOneCommit.Result change) throws Exception {
    approve(change.getChangeId());
    gApi.changes().id(change.getChangeId()).current().submit(new SubmitInput());
  }
}
