package com.googlesource.gerrit.plugins.findowners;

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.gerrit.acceptance.LightweightPluginDaemonTest;
import com.google.gerrit.acceptance.PushOneCommit;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.extensions.api.changes.SubmitInput;
import com.google.gerrit.extensions.common.ChangeInfo;
import com.google.gerrit.server.config.PluginConfig;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.junit.Test;

@TestPlugin(name = "find-owners", sysModule = "com.googlesource.gerrit.plugins.findowners.Module")
public class OwnersFileSubmitRuleIT extends LightweightPluginDaemonTest {

  @Test
  public void TestChangeWithoutPermissions() throws Exception {
    createTestRepositoryContent();
    configurePlugin("enforceLevel", "ENFORCE");
    PushOneCommit.Result r = createChange("test message", "A/1/foo.c", "void main()\n");
    approve(r.getChangeId());
    ChangeInfo result = gApi.changes().id(r.getChangeId()).get();
    assertThat(result.submittable).isFalse();
  }

  private void createTestRepositoryContent() throws Exception {
    addFile("init", "OWNERS", "per-file *.c = alice@example.com, bob@example.com\n");

    addFile("init", "A/1/foo.c", "int main()\n");
    addFile("init", "A/1/bar.c", "int func()\n");
    addFile("init", "A/1/info.txt", "information\n");
    addFile("init", "A/1/OWNERS", "xyz@example.com\n");

    addFile("init", "A/no_inherit/spam.py", "def main()\n");
    addFile("init", "A/no_inherit/ham.py", "def func()\n");
    addFile("init", "A/no_inherit/info.txt", "python information\n");
    addFile("init", "A/no_inherit/OWNERS", "set noparent\nabc@example.com\n");
  }

  @Test
  public void TestDummyRepoSetup() throws Exception {
    createTestRepositoryContent();
    RevWalk rw = testRepo.getRevWalk();
    RevTree tree = rw.parseTree(testRepo.getRepository().resolve("HEAD"));
    RevObject obj = rw.parseAny(testRepo.get(tree, "A/1/foo.c"));
    ObjectLoader loader = rw.getObjectReader().open(obj);
    String contents = new String(loader.getBytes(), UTF_8);
    assertThat(contents).isEqualTo("int main()\n");
  }

  @Test
  public void TestSettingProjectConfig() throws Exception {
    configurePlugin("enforceLevel", "ENFORCE");
    PluginConfig localPluginConfig = pluginConfig
        .getFromProjectConfigWithInheritance(project, "find-owners");
    EnforcementLevel e = localPluginConfig
        .getEnum(EnforcementLevel.CONFIG_NAME, EnforcementLevel.DISABLED);
    assertThat(e).isEqualTo(EnforcementLevel.ENFORCE);
  }

  private void configurePlugin(String configKey, String configValue) throws Exception {
    try (ProjectConfigUpdate u = updateProject(project)) {
      u.getConfig().getPluginConfig("find-owners").setString(configKey, configValue);
      u.save();
    }
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
}
