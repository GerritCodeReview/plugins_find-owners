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
import com.google.gerrit.reviewdb.client.Account;
import com.google.gerrit.server.account.Emails;
import com.google.gerrit.server.config.PluginConfig;
import com.google.gerrit.server.git.validators.CommitValidationMessage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
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
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Test OwnersValidator, which checks syntax of changed OWNERS files. */
@RunWith(JUnit4.class)
public class OwnersValidatorTest {

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
        builder.put(s, new Account.Id(++id));
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

  @Test
  public void chekIsActiveAndFileName() throws Exception {
    // This check should be enabled in project.config, default is not active.
    assertThat(OwnersValidator.isActive(EMPTY_CONFIG)).isFalse();
    assertThat(OwnersValidator.isActive(ENABLED_CONFIG)).isTrue();
    assertThat(OwnersValidator.isActive(ANDROID_CONFIG)).isTrue();
    assertThat(OwnersValidator.isActive(DISABLED_CONFIG)).isFalse();
    // Default file name is "OWNERS".
    assertThat(OwnersValidator.getOwnersFileName(EMPTY_CONFIG)).isEqualTo(OWNERS);
    assertThat(OwnersValidator.getOwnersFileName(ENABLED_CONFIG)).isEqualTo(OWNERS);
    assertThat(OwnersValidator.getOwnersFileName(DISABLED_CONFIG)).isEqualTo(OWNERS);
    assertThat(OwnersValidator.getOwnersFileName(ANDROID_CONFIG)).isEqualTo(OWNERS_ANDROID);
  }

  private static final ImmutableMap<String, String> FILES_WITHOUT_OWNERS =
      ImmutableMap.of("README", "any\n", "d1/test.c", "int x;\n");

  @Test
  public void testNoOwners() throws Exception {
    try (RevWalk rw = new RevWalk(repo)) {
      RevCommit c = makeCommit(rw, "Commit no OWNERS.", FILES_WITHOUT_OWNERS);
      assertThat(validate(rw, c, false, ENABLED_CONFIG)).isEmpty();
      assertThat(validate(rw, c, true, ENABLED_CONFIG)).isEmpty();
    }
  }

  private static final String INCLUDE_STMT1 = "  include  p1/p2 : /d1/owners";

  private static final String INCLUDE_STMT2 = "include  ../d2/owners ";

  private static final ImmutableMap<String, String> FILES_WITH_NO_ERROR =
      ImmutableMap.of(
          OWNERS,
          "\n\n#comments ...\n  ###  more comments\n"
              + INCLUDE_STMT1 + "\n"
              + INCLUDE_STMT2 + "\n"
              + "   user1@google.com # comment\n"
              + "u1+review@g.com###\n"
              + " * # everyone can approve\n"
              + "per-file   *.py=u2.m@g.com   \n"
              + "per-file *.c, *.java ,A*bp = u1@g.com, u1+review@g.com  ,u2.m@g.com#comment\n"
              + "  per-file *.txt = * # everyone can approve #  \n"
              + "per-file *.java = set noparent #  \n"
              + "  set   noparent  # comment#\n");

  private static final ImmutableSet<String> EXPECTED_VERBOSE_OUTPUT =
      ImmutableSet.of(
          "MSG: validate: " + OWNERS,
          "MSG: unchecked: OWNERS:5: " + INCLUDE_STMT1,
          "MSG: unchecked: OWNERS:6: " + INCLUDE_STMT2,
          "MSG: owner: u1+review@g.com",
          "MSG: owner: u1@g.com",
          "MSG: owner: u2.m@g.com",
          "MSG: owner: user1@google.com");

  private static final ImmutableSet<String> EXPECTED_NON_VERBOSE_OUTPUT =
      ImmutableSet.of(
          "MSG: unchecked: OWNERS:5: " + INCLUDE_STMT1,
          "MSG: unchecked: OWNERS:6: " + INCLUDE_STMT2);

  @Test
  public void testGoodInput() throws Exception {
    try (RevWalk rw = new RevWalk(repo)) {
      RevCommit c = makeCommit(rw, "Commit good files", FILES_WITH_NO_ERROR);
      assertThat(validate(rw, c, false, ENABLED_CONFIG))
          .containsExactlyElementsIn(EXPECTED_NON_VERBOSE_OUTPUT);
      assertThat(validate(rw, c, true, ENABLED_CONFIG))
          .containsExactlyElementsIn(EXPECTED_VERBOSE_OUTPUT);
    }
  }

  private static final ImmutableMap<String, String> FILES_WITH_WRONG_SYNTAX =
      ImmutableMap.of(
          "README",
          "# some content\nu2@g.com\n",
          OWNERS,
          "\n\n\nwrong syntax\n#comment\nuser1@google.com\n",
          "d2/" + OWNERS,
          "u1@g.com\nu3@g.com\n*\n",
          "d3/" + OWNERS,
          "\n\nfile: common/Owners\n");

  private static final ImmutableSet<String> EXPECTED_WRONG_SYNTAX =
      ImmutableSet.of(
          "ERROR: syntax: " + OWNERS + ":4: wrong syntax",
          "ERROR: unknown: u3@g.com at d2/" + OWNERS + ":2",
          "ERROR: ignored: d3/" + OWNERS + ":3: file: common/Owners");

  private static final ImmutableSet<String> EXPECTED_VERBOSE_WRONG_SYNTAX =
      ImmutableSet.of(
          "MSG: validate: d3/" + OWNERS,
          "MSG: validate: d2/" + OWNERS,
          "MSG: validate: " + OWNERS,
          "MSG: owner: user1@google.com",
          "MSG: owner: u1@g.com",
          "MSG: owner: u3@g.com",
          "ERROR: syntax: " + OWNERS + ":4: wrong syntax",
          "ERROR: unknown: u3@g.com at d2/" + OWNERS + ":2",
          "ERROR: ignored: d3/" + OWNERS + ":3: file: common/Owners");

  @Test
  public void testWrongSyntax() throws Exception {
    try (RevWalk rw = new RevWalk(repo)) {
      RevCommit c = makeCommit(rw, "Commit wrong syntax", FILES_WITH_WRONG_SYNTAX);
      assertThat(validate(rw, c, false, ENABLED_CONFIG))
          .containsExactlyElementsIn(EXPECTED_WRONG_SYNTAX);
      assertThat(validate(rw, c, true, ENABLED_CONFIG))
          .containsExactlyElementsIn(EXPECTED_VERBOSE_WRONG_SYNTAX);
    }
  }

  private static final ImmutableMap<String, String> FILES_WITH_WRONG_EMAILS =
      ImmutableMap.of("d1/" + OWNERS, "u1@g.com\n", "d2/" + OWNERS_ANDROID, "u2@g.com\n");

  private static final ImmutableSet<String> EXPECTED_VERBOSE_DEFAULT =
      ImmutableSet.of("MSG: validate: d1/" + OWNERS, "MSG: owner: u1@g.com");

  private static final ImmutableSet<String> EXPECTED_VERBOSE_ANDROID =
      ImmutableSet.of("MSG: validate: d2/" + OWNERS_ANDROID, "MSG: owner: u2@g.com");

  @Test
  public void checkWrongEmails() throws Exception {
    try (RevWalk rw = new RevWalk(repo)) {
      RevCommit c = makeCommit(rw, "Commit Default", FILES_WITH_WRONG_EMAILS);
      assertThat(validate(rw, c, true, ENABLED_CONFIG))
          .containsExactlyElementsIn(EXPECTED_VERBOSE_DEFAULT);
    }
  }

  @Test
  public void checkAndroidOwners() throws Exception {
    try (RevWalk rw = new RevWalk(repo)) {
      RevCommit c = makeCommit(rw, "Commit Android", FILES_WITH_WRONG_EMAILS);
      assertThat(validate(rw, c, true, ANDROID_CONFIG))
          .containsExactlyElementsIn(EXPECTED_VERBOSE_ANDROID);
    }
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

  private List<String> validate(RevWalk rw, RevCommit c, boolean verbose, PluginConfig cfg)
      throws Exception {
    MockedEmails myEmails = new MockedEmails();
    OwnersValidator validator = new OwnersValidator(null, null, myEmails);
    String ownersFileName = OwnersValidator.getOwnersFileName(cfg);
    List<CommitValidationMessage> m = validator.performValidation(c, rw, ownersFileName, verbose);
    return transformMessages(m);
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
