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

import static com.googlesource.gerrit.plugins.findowners.Config.OWNERS;
import static com.googlesource.gerrit.plugins.findowners.Config.OWNERS_FILE_NAME;
import static com.googlesource.gerrit.plugins.findowners.Config.REJECT_ERROR_IN_OWNERS;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Multimap;
import com.google.gerrit.extensions.annotations.Exports;
import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.extensions.api.projects.ProjectConfigEntryType;
import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.gerrit.reviewdb.client.Account;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.server.account.Emails;
import com.google.gerrit.server.config.PluginConfig;
import com.google.gerrit.server.config.PluginConfigFactory;
import com.google.gerrit.server.config.ProjectConfigEntry;
import com.google.gerrit.server.events.CommitReceivedEvent;
import com.google.gerrit.server.git.validators.CommitValidationException;
import com.google.gerrit.server.git.validators.CommitValidationListener;
import com.google.gerrit.server.git.validators.CommitValidationMessage;
import com.google.gerrit.server.project.NoSuchProjectException;
import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.TreeFilter;

/** Check syntax of changed OWNERS files. */
public class OwnersValidator implements CommitValidationListener {
  private interface TreeWalkVisitor {
    void onVisit(TreeWalk tw);
  }

  public static AbstractModule module() {
    return new AbstractModule() {
      @Override
      protected void configure() {
        DynamicSet.bind(binder(), CommitValidationListener.class).to(OwnersValidator.class);
        bind(ProjectConfigEntry.class)
            .annotatedWith(Exports.named(REJECT_ERROR_IN_OWNERS))
            .toInstance(
                new ProjectConfigEntry(
                    "Reject OWNERS Files With Errors",
                    null,
                    ProjectConfigEntryType.BOOLEAN,
                    null,
                    false,
                    "Pushes of commits with errors in OWNERS files will be rejected."));
      }
    };
  }

  private final String pluginName;
  private final PluginConfigFactory cfgFactory;
  private final Emails emails;

  @Inject
  OwnersValidator(
      @PluginName String pluginName,
      PluginConfigFactory cfgFactory,
      Emails emails) {
    this.pluginName = pluginName;
    this.cfgFactory = cfgFactory;
    this.emails = emails;
  }

  public static String getOwnersFileName(PluginConfig cfg) {
    return getOwnersFileName(cfg, OWNERS);
  }

  public static String getOwnersFileName(PluginConfig cfg, String defaultName) {
    return cfg.getString(OWNERS_FILE_NAME, defaultName);
  }

  public String getOwnersFileName(Project.NameKey project) {
    String name = getOwnersFileName(cfgFactory.getFromGerritConfig(pluginName, true));
    try {
      return getOwnersFileName(
          cfgFactory.getFromProjectConfigWithInheritance(project, pluginName), name);
    } catch (NoSuchProjectException e) {
      return name;
    }
  }

  @VisibleForTesting
  static boolean isActive(PluginConfig cfg) {
    return cfg.getBoolean(REJECT_ERROR_IN_OWNERS, false);
  }

  @Override
  public List<CommitValidationMessage> onCommitReceived(CommitReceivedEvent receiveEvent)
      throws CommitValidationException {
    List<CommitValidationMessage> messages = new ArrayList<>();
    try {
      Project.NameKey project = receiveEvent.project.getNameKey();
      PluginConfig cfg = cfgFactory.getFromProjectConfigWithInheritance(project, pluginName);
      if (isActive(cfg)) {
        String name = getOwnersFileName(project);
        messages = performValidation(receiveEvent.commit, receiveEvent.revWalk, name, false);
      }
    } catch (NoSuchProjectException | IOException e) {
      throw new CommitValidationException("failed to check owners files", e);
    }
    if (hasError(messages)) {
      throw new CommitValidationException("found invalid owners file", messages);
    }
    return messages;
  }

  @VisibleForTesting
  List<CommitValidationMessage> performValidation(
      RevCommit c, RevWalk revWalk, String ownersFileName, boolean verbose) throws IOException {
    // Collect all messages from all files.
    List<CommitValidationMessage> messages = new ArrayList<>();
    // Collect all email addresses from all files and check each address only once.
    Map<String, Set<String>> email2lines = new HashMap<>();
    Map<String, ObjectId> content = getChangedOwners(c, revWalk, ownersFileName);
    for (String path : content.keySet()) {
      ObjectLoader ol = revWalk.getObjectReader().open(content.get(path));
      try (InputStream in = ol.openStream()) {
        if (RawText.isBinary(in)) {
          add(messages, path + " is a binary file", true); // OWNERS files cannot be binary
          continue;
        }
      }
      checkFile(messages, email2lines, path, ol, verbose);
    }
    checkEmails(messages, emails, email2lines, verbose);
    return messages;
  }

  private static void checkEmails(
      List<CommitValidationMessage> messages,
      Emails emails,
      Map<String, Set<String>> email2lines,
      boolean verbose) {
    List<String> owners = new ArrayList<>(email2lines.keySet());
    if (verbose) {
      for (String owner : owners) {
        add(messages, "owner: " + owner, false);
      }
    }
    if (emails == null || owners.isEmpty()) {
      return;
    }
    String[] ownerEmailsAsArray = new String[owners.size()];
    owners.toArray(ownerEmailsAsArray);
    try {
      Multimap<String, Account.Id> email2ids = emails.getAccountsFor(ownerEmailsAsArray);
      for (String owner : ownerEmailsAsArray) {
        boolean wrongEmail = (email2ids == null);
        if (!wrongEmail) {
          try {
            Collection<Account.Id> ids = email2ids.get(owner);
            wrongEmail = (ids == null || ids.isEmpty());
          } catch (Exception e) {
            wrongEmail = true;
          }
        }
        if (wrongEmail) {
          String locations = String.join(" ", email2lines.get(owner));
          add(messages, "unknown: " + owner + " at " + locations, true);
        }
      }
    } catch (Exception e) {
      add(messages, "checkEmails failed.", true);
    }
  }

  private static void checkFile(
      List<CommitValidationMessage> messages,
      Map<String, Set<String>> email2lines,
      String path,
      ObjectLoader ol,
      boolean verbose)
      throws IOException {
    if (verbose) {
      add(messages, "validate: " + path, false);
    }
    try (BufferedReader br =
        new BufferedReader(new InputStreamReader(ol.openStream(), StandardCharsets.UTF_8))) {
      int line = 0;
      for (String l = br.readLine(); l != null; l = br.readLine()) {
        line++;
        checkLine(messages, email2lines, path, line, l);
      }
    }
  }

  private static void collectEmail(
      Map<String, Set<String>> map, String email, String file, int lineNumber) {
    if (!email.equals("*")) {
      map.computeIfAbsent(email, (String k) -> new HashSet<>());
      map.get(email).add(file + ":" + lineNumber);
    }
  }

  private static boolean hasError(List<CommitValidationMessage> messages) {
    for (CommitValidationMessage m : messages) {
      if (m.isError()) {
        return true;
      }
    }
    return false;
  }

  private static void add(List<CommitValidationMessage> messages, String msg, boolean error) {
    messages.add(new CommitValidationMessage(msg, error));
  }

  private static void checkLine(
      List<CommitValidationMessage> messages,
      Map<String, Set<String>> email2lines,
      String path,
      int lineNumber,
      String line) {
    String email;
    String[] emails;
    if (Parser.isComment(line) || Parser.isNoParent(line)) {
      // no email address to check
    } else if ((email = Parser.parseEmail(line)) != null) {
      collectEmail(email2lines, email, path, lineNumber);
    } else if ((emails = Parser.parsePerFileOwners(line)) != null) {
      for (String e : emails) {
        if (!e.equals(Parser.TOK_SET_NOPARENT)) {
          collectEmail(email2lines, e, path, lineNumber);
        }
      }
    } else if (Parser.isInclude(line)) {
      // Included "OWNERS" files will be checked by themselves.
      // TODO: Check if the include file path is valid and existence of the included file.
      // TODO: Check an included file syntax if it is not named as the project ownersFileName.
      add(messages, "unchecked: " + path + ":" + lineNumber + ": " + line, false);
    } else if (Parser.isFile(line)) {
      add(messages, "ignored: " + path + ":" + lineNumber + ": " + line, true);
    } else {
      add(messages, "syntax: " + path + ":" + lineNumber + ": " + line, true);
    }
  }

  /**
   * Find all changed OWNERS files which differ between the commit and its parents. Return a map
   * from "Path to the changed file" to "ObjectId of the file".
   */
  private static Map<String, ObjectId> getChangedOwners(
      RevCommit c, RevWalk revWalk, String ownersFileName) throws IOException {
    final Map<String, ObjectId> content = new HashMap<>();
    visitChangedEntries(
        c,
        revWalk,
        new TreeWalkVisitor() {
          @Override
          public void onVisit(TreeWalk tw) {
            if (isFile(tw) && ownersFileName.equals(tw.getNameString())) {
              content.put(tw.getPathString(), tw.getObjectId(0));
            }
          }
        });
    return content;
  }

  private static boolean isFile(TreeWalk tw) {
    return FileMode.EXECUTABLE_FILE.equals(tw.getRawMode(0))
        || FileMode.REGULAR_FILE.equals(tw.getRawMode(0));
  }

  /**
   * Find all TreeWalk entries which differ between the commit and its parents. If a TreeWalk entry
   * is found this method calls the onVisit() method of the class TreeWalkVisitor.
   */
  private static void visitChangedEntries(RevCommit c, RevWalk revWalk, TreeWalkVisitor visitor)
      throws IOException {
    try (TreeWalk tw = new TreeWalk(revWalk.getObjectReader())) {
      tw.setRecursive(true);
      tw.setFilter(TreeFilter.ANY_DIFF);
      tw.addTree(c.getTree());
      if (c.getParentCount() > 0) {
        for (RevCommit p : c.getParents()) {
          if (p.getTree() == null) {
            revWalk.parseHeaders(p);
          }
          tw.addTree(p.getTree());
        }
        while (tw.next()) {
          if (isDifferentToAllParents(c, tw)) {
            visitor.onVisit(tw);
          }
        }
      } else {
        while (tw.next()) {
          visitor.onVisit(tw);
        }
      }
    }
  }

  private static boolean isDifferentToAllParents(RevCommit c, TreeWalk tw) {
    if (c.getParentCount() > 1) {
      for (int p = 1; p <= c.getParentCount(); p++) {
        if (tw.getObjectId(0).equals(tw.getObjectId(p))) {
          return false;
        }
      }
    }
    return true;
  }
}
