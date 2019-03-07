package com.googlesource.gerrit.plugins.findowners;

import static com.google.common.truth.Truth.assertThat;

import com.google.gerrit.acceptance.LightweightPluginDaemonTest;
import com.google.gerrit.acceptance.PushOneCommit;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.extensions.api.changes.SubmitInput;
import com.google.gerrit.extensions.common.ChangeInfo;
import com.google.gerrit.server.config.PluginConfig;
import org.eclipse.jgit.junit.TestRepository;
import org.eclipse.jgit.lib.Repository;
import org.junit.Test;

@TestPlugin(name = "find-owners", sysModule = "com.googlesource.gerrit.plugins.findowners.Module")
public class OwnersFileSubmitRuleIT extends LightweightPluginDaemonTest {

  @Test
  public void changeWithoutPermissions() throws Exception {
    createTestRepositoryContent();
    configurePlugin("enforceLevel", "ENFORCE");
    PushOneCommit.Result r = createChange("test message", "A/1/foo.c", "void main()\n");
    approve(r.getChangeId());
    ChangeInfo result = gApi.changes().id(r.getChangeId()).get();
    assertThat(result.submittable).isFalse();
  }

  @Test
  public void setProjectConfig() throws Exception {
    configurePlugin("enforceLevel", "ENFORCE");
    PluginConfig localPluginConfig =
        pluginConfig.getFromProjectConfigWithInheritance(project, "find-owners");
    EnforcementLevel e =
        localPluginConfig.getEnum(EnforcementLevel.CONFIG_NAME, EnforcementLevel.DISABLED);
    assertThat(e).isEqualTo(EnforcementLevel.ENFORCE);
  }

  private void createTestRepositoryContent() throws Exception {
    try (Repository repo = repoManager.openRepository(project)) {
      TestRepository<?>.CommitBuilder cb = new TestRepository<>(repo).branch("master").commit();

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
    testRepo.git().fetch().call();
  }

  private void configurePlugin(String configKey, String configValue) throws Exception {
    try (ProjectConfigUpdate u = updateProject(project)) {
      u.getConfig().getPluginConfig("find-owners").setString(configKey, configValue);
      u.save();
    }
  }
}
