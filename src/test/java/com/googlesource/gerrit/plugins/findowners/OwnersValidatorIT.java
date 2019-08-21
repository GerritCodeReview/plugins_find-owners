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
import static com.googlesource.gerrit.plugins.findowners.Config.OWNERS;
import static com.googlesource.gerrit.plugins.findowners.Config.OWNERS_FILE_NAME;
import static com.googlesource.gerrit.plugins.findowners.Config.REJECT_ERROR_IN_OWNERS;

import com.google.common.base.Function;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Lists;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.acceptance.PushOneCommit;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.extensions.api.changes.SubmitInput;
import com.google.gerrit.reviewdb.client.Account;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.server.account.Emails;
import com.google.gerrit.server.config.PluginConfig;
import com.google.gerrit.server.events.CommitReceivedEvent;
import com.google.gerrit.server.git.validators.CommitValidationMessage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.AddCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/** Test OwnersValidator, which checks syntax of changed OWNERS files. */
@TestPlugin(name = "find-owners", sysModule = "com.googlesource.gerrit.plugins.findowners.Module")
public class OwnersValidatorIT extends FindOwners {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  @Rule public Watcher watcher = new Watcher(logger);

  private void addProjectFile(String project, String file, String content) throws Exception {
    switchProject(newProject(project));
    PushOneCommit.Result c = createChange("c", file, content);
    approve(c.getChangeId());
    gApi.changes().id(c.getChangeId()).current().submit(new SubmitInput());
  }

  private static class MockedCommitReceivedEvent extends CommitReceivedEvent {
    MockedCommitReceivedEvent(String project, RevWalk revWalk, RevCommit commit) {
      this.project = new Project(Project.nameKey(project));
      this.revWalk = revWalk;
      this.commit = commit;
      this.refName = "master";
    }
  }

  private static class MockedEmails extends Emails {
    Set<String> registered;

    MockedEmails() {
      super(null, null, null);
      registered =
          ImmutableSet.of(
              "u1@g.com", "u2@g.com", "u2.m@g.com", "user1@google.com", "u1+review@g.com");
    }

    @Override
    public ImmutableSetMultimap<String, Account.Id> getAccountsFor(String... emails) {
      // Used by checkEmails; each email should have exactly one Account.Id
      ImmutableSetMultimap.Builder<String, Account.Id> builder = ImmutableSetMultimap.builder();
      int id = 1000000;
      for (String s : registered) {
        builder.put(s, Account.id(++id));
      }
      return builder.build();
    }
  }

  private File repoFolder;
  private Repository repo;

  @Before
  public void init() throws IOException {
    repoFolder = File.createTempFile("Git", "");
    repoFolder.delete();
    repo = FileRepositoryBuilder.create(new File(repoFolder, ".git"));
    repo.create();
  }

  @After
  public void cleanup() throws IOException {
    repo.close();
    if (repoFolder.exists()) {
      FileUtils.deleteDirectory(repoFolder);
    }
  }

  private static final String OWNERS_ANDROID = "OWNERS.android"; // alternative OWNERS file name
  private static final PluginConfig ANDROID_CONFIG = createAndroidConfig(); // use OWNERS_ANDROID
  private static final PluginConfig EMPTY_CONFIG = new PluginConfig("", new Config());
  private static final PluginConfig ENABLED_CONFIG = createEnabledConfig(); // use OWNERS
  private static final PluginConfig DISABLED_CONFIG = createDisabledConfig();

  private OwnersValidator newOwnersValidator(PluginConfig cfg) {
    return new OwnersValidator(cfg, accountCache, patchListCache, repoManager, new MockedEmails());
  }

  @Test
  public void chekIsActiveAndFileName() throws Exception {
    // This check should be enabled in project.config, default is not active.
    assertThat(newOwnersValidator(EMPTY_CONFIG).isActive()).isFalse();
    assertThat(newOwnersValidator(ENABLED_CONFIG).isActive()).isTrue();
    assertThat(newOwnersValidator(ANDROID_CONFIG).isActive()).isTrue();
    assertThat(newOwnersValidator(DISABLED_CONFIG).isActive()).isFalse();
    // Default file name is "OWNERS".
    assertThat(newOwnersValidator(EMPTY_CONFIG).getOwnersFileName()).isEqualTo(OWNERS);
    assertThat(newOwnersValidator(ENABLED_CONFIG).getOwnersFileName()).isEqualTo(OWNERS);
    assertThat(newOwnersValidator(DISABLED_CONFIG).getOwnersFileName()).isEqualTo(OWNERS);
    assertThat(newOwnersValidator(ANDROID_CONFIG).getOwnersFileName()).isEqualTo(OWNERS_ANDROID);
  }

  private static final ImmutableMap<String, String> FILES_WITHOUT_OWNERS =
      ImmutableMap.of("README", "any\n", "d1/test.c", "int x;\n");

  @Test
  public void testNoOwners() throws Exception {
    CommitReceivedEvent event = makeCommitEvent("myTest", "no OWNERS.", FILES_WITHOUT_OWNERS);
    assertThat(validate(event, false, ENABLED_CONFIG)).isEmpty();
    assertThat(validate(event, true, ENABLED_CONFIG)).isEmpty();
  }

  private static final String[] INCLUDE_STMTS =
      new String[] {
        "  include  p1/p2 : /d1/owners",
        "include  p2/p1://d1/owners #comment",
        "include ../d2/owners",
        " per-file *.java = file:  //d2/OWNERS.java #",
        "per-file *.cpp,*cc=file:p1/p2:/c++owners",
        "  file:   p1/p2 : /d1/owners ", // repeated
        "file:  p3/p2://d1/owners #comment",
        "file: ../d2/owners", // repeated
        "file: //OWNERS", // recursive inclusion
        "file:///OWNERS.android"
      };

  private static final ImmutableSet<String> SKIP_INCLUDE_STMTS =
      ImmutableSet.of("  file:   p1/p2 : /d1/owners ", "file: ../d2/owners", "file: //OWNERS");

  private static String allIncludeStatements() {
    String statement = "";
    for (String s : INCLUDE_STMTS) {
      statement += s + "\n";
    }
    return statement;
  }

  private static Set<String> allIncludeMsgs() {
    Set<String> msgs = new HashSet<>();
    for (int i = 0; i < INCLUDE_STMTS.length; i++) {
      if (!SKIP_INCLUDE_STMTS.contains(INCLUDE_STMTS[i])) {
        msgs.add(
            "MSG: unchecked: OWNERS:" + (i + 1) + ": " + Parser.getIncludeOrFile(INCLUDE_STMTS[i]));
      }
    }
    return msgs;
  }

  private static final ImmutableMap<String, String> FILES_WITH_NO_ERROR =
      ImmutableMap.of(
          OWNERS,
          allIncludeStatements()
              + "\n\n#comments ...\n  ###  more comments\n"
              + "   user1@google.com # comment\n"
              + "u1+review@g.com###\n"
              + " * # everyone can approve\n"
              + "per-file   *.py=u2.m@g.com   \n"
              + "per-file *.c, *.java ,A*bp = u1@g.com, u1+review@g.com  ,u2.m@g.com#comment\n"
              + "  per-file *.txt = * # everyone can approve #  \n"
              + "per-file *.java = set noparent #  \n"
              + "  set   noparent  # comment#\n");

  private static Set<String> allVerboseMsgs() {
    Set<String> msgs = allIncludeMsgs();
    msgs.add("MSG: checking " + OWNERS);
    msgs.add("MSG: owner: u1+review@g.com");
    msgs.add("MSG: owner: u1@g.com");
    msgs.add("MSG: owner: u2.m@g.com");
    msgs.add("MSG: owner: user1@google.com");
    String[] missing =
        new String[] {
          "p1/p2:OWNERS.android",
          "p1/p2:c++owners",
          "p1/p2:d1/owners",
          "p1/p2:d2/OWNERS.java",
          "p1/p2:d2/owners",
          "p2/p1:d1/owners",
          "p3/p2:d1/owners",
        };
    for (String s : missing) {
      msgs.add("MSG: cannot find file: " + s);
      msgs.add("MSG: check repo file " + s);
    }
    String[] skips =
        new String[] {
          "p1/p2:OWNERS", "p1/p2:d1/owners", "p1/p2:d2/owners",
        };
    for (String s : skips) {
      msgs.add("MSG: skip repeated include of " + s);
    }
    return msgs;
  }

  private static final ImmutableSet<String> EXPECTED_VERBOSE_OUTPUT =
      ImmutableSet.copyOf(allVerboseMsgs());
  private static final ImmutableSet<String> EXPECTED_NON_VERBOSE_OUTPUT =
      ImmutableSet.copyOf(allIncludeMsgs());

  @Test
  public void testGoodInput() throws Exception {
    CommitReceivedEvent event = makeCommitEvent("p1/p2", "good files", FILES_WITH_NO_ERROR);
    assertThat(validate(event, false, ENABLED_CONFIG))
        .containsExactlyElementsIn(EXPECTED_NON_VERBOSE_OUTPUT);
    assertThat(validate(event, true, ENABLED_CONFIG))
        .containsExactlyElementsIn(EXPECTED_VERBOSE_OUTPUT);
  }

  private static final ImmutableMap<String, String> FILES_WITH_WRONG_SYNTAX =
      ImmutableMap.of(
          "README",
          "# some content\nu2@g.com\n",
          OWNERS,
          "\n\n\nwrong syntax\n#comment\nuser1@google.com\n",
          "d2/" + OWNERS,
          "u1@g.com\n\nu3@g.com\n*\n");

  private static final ImmutableSet<String> EXPECTED_WRONG_SYNTAX =
      ImmutableSet.of(
          "ERROR: syntax: " + OWNERS + ":4: wrong syntax",
          "ERROR: unknown: u3@g.com at d2/" + OWNERS + ":3");

  private static final ImmutableSet<String> EXPECTED_VERBOSE_WRONG_SYNTAX =
      ImmutableSet.of(
          "MSG: checking d2/" + OWNERS,
          "MSG: checking " + OWNERS,
          "MSG: owner: user1@google.com",
          "MSG: owner: u1@g.com",
          "MSG: owner: u3@g.com",
          "ERROR: syntax: " + OWNERS + ":4: wrong syntax",
          "ERROR: unknown: u3@g.com at d2/" + OWNERS + ":3");

  @Test
  public void testWrongSyntax() throws Exception {
    CommitReceivedEvent event = makeCommitEvent("p1/p2", "wrong syntax", FILES_WITH_WRONG_SYNTAX);
    assertThat(validate(event, false, ENABLED_CONFIG))
        .containsExactlyElementsIn(EXPECTED_WRONG_SYNTAX);
    assertThat(validate(event, true, ENABLED_CONFIG))
        .containsExactlyElementsIn(EXPECTED_VERBOSE_WRONG_SYNTAX);
  }

  private static final ImmutableMap<String, String> FILES_WITH_WRONG_EMAILS =
      ImmutableMap.of("d1/" + OWNERS, "u1@g.com\n", "d2/" + OWNERS_ANDROID, "u2@g.com\n");

  private static final ImmutableSet<String> EXPECTED_VERBOSE_DEFAULT =
      ImmutableSet.of("MSG: checking d1/" + OWNERS, "MSG: owner: u1@g.com");

  private static final ImmutableSet<String> EXPECTED_VERBOSE_ANDROID =
      ImmutableSet.of("MSG: checking d2/" + OWNERS_ANDROID, "MSG: owner: u2@g.com");

  @Test
  public void checkWrongEmails() throws Exception {
    CommitReceivedEvent event = makeCommitEvent("test", "default", FILES_WITH_WRONG_EMAILS);
    assertThat(validate(event, true, ENABLED_CONFIG))
        .containsExactlyElementsIn(EXPECTED_VERBOSE_DEFAULT);
  }

  @Test
  public void checkAndroidOwners() throws Exception {
    CommitReceivedEvent event = makeCommitEvent("test", "Android", FILES_WITH_WRONG_EMAILS);
    assertThat(validate(event, true, ANDROID_CONFIG))
        .containsExactlyElementsIn(EXPECTED_VERBOSE_ANDROID);
  }

  @Test
  public void simpleIncludeTest() throws Exception {
    addProjectFile("p1", "d2/owners", "wrong\nxyz\n");
    addProjectFile("p2", "d2/owners", "x@g.com\nerr\ninclude ../d2/owners\n");
    ImmutableMap<String, String> files =
        ImmutableMap.of(
            "d1/" + OWNERS,
            "include ../d2/owners\ninclude /d2/owners\n"
                + "include p1:/d2/owners\ninclude p2:/d2/owners\n");
    ImmutableSet<String> expected =
        ImmutableSet.of(
            "ERROR: unknown: x@g.com at p2:d2/owners:1",
            "ERROR: syntax: p2:d2/owners:2: err",
            "ERROR: syntax: d2/owners:1: wrong",
            "ERROR: syntax: d2/owners:2: xyz");
    CommitReceivedEvent event = makeCommitEvent("p1", "T", files);
    assertThat(validate(event, false, ENABLED_CONFIG)).containsExactlyElementsIn(expected);
  }

  @Test
  public void simpleIncludeACLTest() throws Exception {
    // Even if the user cannot read an included file,
    // the upload validator should still check the included file.
    // Use pA/pB, because addProjectFile cannot create the same project again.
    addProjectFile("pA", "d2/owners", "wrong\nxyz\n");
    addProjectFile("pB", "d2/owners", "x@g.com\nerr\ninclude ../d2/owners\n");
    blockRead(project); // current project is pB; set it to not readable
    ImmutableMap<String, String> files =
        ImmutableMap.of(
            "d1/" + OWNERS,
            "include ../d2/owners\ninclude /d2/owners\n"
                + "include pA:/d2/owners\ninclude pB:/d2/owners\n");
    ImmutableSet<String> expected =
        ImmutableSet.of(
            // "MSG: unchecked: d1/OWNERS:4: include pB:/d2/owners", // cannot read pB
            "ERROR: unknown: x@g.com at pB:d2/owners:1",
            "ERROR: syntax: pB:d2/owners:2: err",
            "ERROR: syntax: d2/owners:1: wrong",
            "ERROR: syntax: d2/owners:2: xyz");
    CommitReceivedEvent event = makeCommitEvent("pA", "T", files);
    assertThat(validate(event, false, ENABLED_CONFIG)).containsExactlyElementsIn(expected);
  }

  private static PluginConfig createEnabledConfig() {
    PluginConfig c = new PluginConfig("", new Config());
    c.setBoolean(REJECT_ERROR_IN_OWNERS, true);
    return c;
  }

  private static PluginConfig createDisabledConfig() {
    PluginConfig c = new PluginConfig("", new Config());
    c.setBoolean(REJECT_ERROR_IN_OWNERS, false);
    return c;
  }

  private static PluginConfig createAndroidConfig() {
    PluginConfig c = createEnabledConfig();
    c.setString(OWNERS_FILE_NAME, OWNERS_ANDROID);
    return c;
  }

  private RevCommit makeCommit(RevWalk rw, String message, Map<String, String> fileStrings)
      throws IOException, GitAPIException {
    Map<File, byte[]> fileBytes = new HashMap<>();
    for (String path : fileStrings.keySet()) {
      fileBytes.put(
          new File(repo.getDirectory().getParent(), path),
          fileStrings.get(path).getBytes(StandardCharsets.UTF_8));
    }
    return makeCommit(rw, repo, message, fileBytes);
  }

  private static RevCommit makeCommit(
      RevWalk rw, Repository repo, String message, Map<File, byte[]> files)
      throws IOException, GitAPIException {
    try (Git git = new Git(repo)) {
      if (files != null) {
        addFiles(git, files);
      }
      return rw.parseCommit(git.commit().setMessage(message).call());
    }
  }

  private CommitReceivedEvent makeCommitEvent(
      String project, String message, Map<String, String> fileStrings) throws Exception {
    RevWalk rw = new RevWalk(repo);
    return new MockedCommitReceivedEvent(project, rw, makeCommit(rw, message, fileStrings));
  }

  private List<String> validate(CommitReceivedEvent event, boolean verbose, PluginConfig cfg)
      throws Exception {
    OwnersValidator validator = newOwnersValidator(cfg);
    OwnersValidator.Checker checker = validator.new Checker(event, verbose);
    checker.check(validator.getOwnersFileName());
    return transformMessages(checker.messages);
  }

  private static String generateFilePattern(File f, Git git) {
    return f.getAbsolutePath()
        .replace(git.getRepository().getWorkTree().getAbsolutePath(), "")
        .substring(1);
  }

  private static void addFiles(Git git, Map<File, byte[]> files)
      throws IOException, GitAPIException {
    AddCommand ac = git.add();
    for (File f : files.keySet()) {
      if (!f.exists()) {
        FileUtils.touch(f);
      }
      if (files.get(f) != null) {
        FileUtils.writeByteArrayToFile(f, files.get(f));
      }
      ac = ac.addFilepattern(generateFilePattern(f, git));
    }
    ac.call();
  }

  private static List<String> transformMessages(List<CommitValidationMessage> messages) {
    return Lists.transform(
        messages,
        new Function<CommitValidationMessage, String>() {
          @Override
          public String apply(CommitValidationMessage input) {
            String pre = input.isError() ? "ERROR: " : "MSG: ";
            return pre + input.getMessage();
          }
        });
  }

  @Test
  public void testTransformer() {
    List<CommitValidationMessage> messages = new ArrayList<>();
    messages.add(new CommitValidationMessage("a message", false));
    messages.add(new CommitValidationMessage("an error", true));
    Set<String> expected = ImmutableSet.of("ERROR: an error", "MSG: a message");
    assertThat(transformMessages(messages)).containsExactlyElementsIn(expected);
  }
}
