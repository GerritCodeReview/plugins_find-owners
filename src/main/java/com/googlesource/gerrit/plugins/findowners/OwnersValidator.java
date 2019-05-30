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

import static com.google.common.base.Strings.isNullOrEmpty;
import static com.googlesource.gerrit.plugins.findowners.Config.OWNERS;
import static com.googlesource.gerrit.plugins.findowners.Config.OWNERS_FILE_NAME;
import static com.googlesource.gerrit.plugins.findowners.Config.REJECT_ERROR_IN_OWNERS;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Multimap;
import com.google.gerrit.entities.Account;
import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.annotations.Exports;
import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.extensions.api.projects.ProjectConfigEntryType;
import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.gerrit.server.account.Emails;
import com.google.gerrit.server.config.PluginConfig;
import com.google.gerrit.server.config.PluginConfigFactory;
import com.google.gerrit.server.config.ProjectConfigEntry;
import com.google.gerrit.server.events.CommitReceivedEvent;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.git.validators.CommitValidationException;
import com.google.gerrit.server.git.validators.CommitValidationListener;
import com.google.gerrit.server.git.validators.CommitValidationMessage;
import com.google.gerrit.server.permissions.PermissionBackend;
import com.google.gerrit.server.project.NoSuchProjectException;
import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import java.io.BufferedReader;
import java.io.File;
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
import java.util.stream.Collectors;
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
  private final PermissionBackend permissionBackend;
  private final PluginConfigFactory cfgFactory;
  private final GitRepositoryManager repoManager;
  private final Emails emails;

  @Inject
  OwnersValidator(
      @PluginName String pluginName,
      PermissionBackend permissionBackend,
      PluginConfigFactory cfgFactory,
      GitRepositoryManager repoManager,
      Emails emails) {
    this.pluginName = pluginName;
    this.permissionBackend = permissionBackend;
    this.cfgFactory = cfgFactory;
    this.repoManager = repoManager;
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
  public List<CommitValidationMessage> onCommitReceived(CommitReceivedEvent event)
      throws CommitValidationException {
    Checker checker = new Checker(event, false, permissionBackend);
    try {
      Project.NameKey project = event.project.getNameKey();
      PluginConfig cfg = cfgFactory.getFromProjectConfigWithInheritance(project, pluginName);
      if (isActive(cfg)) {
        checker.check(getOwnersFileName(project));
      }
    } catch (NoSuchProjectException | IOException e) {
      throw new CommitValidationException("failed to check owners files", e);
    }
    if (checker.hasError()) {
      checker.addError(
          "See OWNERS file syntax document at "
              + "https://gerrit.googlesource.com/plugins/find-owners/+/"
              + "master/src/main/resources/Documentation/syntax.md");
      throw new CommitValidationException("found invalid owners file", checker.messages);
    }
    return checker.messages;
  }

  class Checker {
    // An inner class to keep needed data specific to one commit event.
    CommitReceivedEvent event;
    boolean verbose;
    PermissionBackend permissionBackend;
    List<CommitValidationMessage> messages;
    Map<String, ObjectId> allFiles; // changedFilePath => ObjectId
    Map<String, String> readFiles; // project:file => content
    Set<String> checkedFiles; // project:file
    // Collect all email addresses from all files and check each address only once.
    Map<String, Set<String>> email2lines;

    Checker(CommitReceivedEvent event, boolean verbose, PermissionBackend permissionBackend) {
      this.event = event;
      this.verbose = verbose;
      this.permissionBackend = permissionBackend;
      messages = new ArrayList<>();
      readFiles = new HashMap<>();
      checkedFiles = new HashSet<>();
      email2lines = new HashMap<>();
      try {
        allFiles = getChangedFiles(event.commit, event.revWalk);
      } catch (Exception e) {
        allFiles = new HashMap<>();
        addError("getChangedFiles failed.");
      }
    }

    @VisibleForTesting
    void check(String ownersFileName) throws IOException {
      Map<String, ObjectId> ownerFiles =
          allFiles.entrySet().stream()
              .filter(e -> ownersFileName.equals(new File(e.getKey()).getName()))
              .collect(Collectors.toMap(e -> e.getKey(), e -> e.getValue()));
      String projectName = event.project.getName();
      for (String path : ownerFiles.keySet()) {
        String key = projectName + ":" + path;
        ObjectLoader ol = event.revWalk.getObjectReader().open(ownerFiles.get(path));
        try (InputStream in = ol.openStream()) {
          if (RawText.isBinary(in)) {
            addError(path + " is a binary file"); // OWNERS files cannot be binary
            continue;
          }
        }
        checkedFiles.add(key);
        checkFile(projectName, path, ol);
      }
      checkEmails(emails);
    }

    void checkEmails(Emails emails) {
      List<String> owners = new ArrayList<>(email2lines.keySet());
      if (owners.isEmpty()) {
        return;
      }
      if (verbose) {
        for (String owner : owners) {
          addMsg("owner: " + owner);
        }
      }
      if (emails == null) {
        addError("cannot check owner emails with null Emails cache.");
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
            addError("unknown: " + owner + " at " + locations);
          }
        }
      } catch (Exception e) {
        addError("checkEmails failed.");
      }
    }

    void checkFile(String project, String path, String[] lines) {
      addVerboseMsg("checking " + path);
      int num = 0;
      for (String line : lines) {
        checkLine(project, path, ++num, line);
      }
    }

    void checkFile(String project, String path, String content) {
      checkFile(project, path, content.split("\\R"));
    }

    void checkFile(String project, String path, ObjectLoader ol) {
      try {
        BufferedReader reader =
            new BufferedReader(new InputStreamReader(ol.openStream(), StandardCharsets.UTF_8));
        checkFile(project, path, reader.lines().toArray(String[]::new));
      } catch (Exception e) {
        addError("cannot open file: " + path);
      }
    }

    private void collectEmail(String email, String project, String file, int lineNumber) {
      if (!email.equals("*")) {
        email2lines.computeIfAbsent(email, (String k) -> new HashSet<>());
        email2lines.get(email).add(qualifiedPath(project, file) + ":" + lineNumber);
      }
    }

    private boolean hasError() {
      for (CommitValidationMessage m : messages) {
        if (m.isError()) {
          return true;
        }
      }
      return false;
    }

    void addError(String msg) {
      messages.add(new CommitValidationMessage(msg, true));
    }

    String qualifiedPath(String project, String path) {
      return event.project.getName().equals(project) ? path : (project + ":" + path);
    }

    void addSyntaxError(String path, int lineNumber, String line) {
      addError("syntax: " + path + ":" + lineNumber + ": " + line);
    }

    void addMsg(String msg) {
      messages.add(new CommitValidationMessage(msg, false));
    }

    void addVerboseMsg(String msg) {
      if (verbose) {
        addMsg(msg);
      }
    }

    String normalizeChangedFilePath(String dir, String file) {
      try {
        if (file.startsWith("/")) {
          file = new File(file).getCanonicalPath();
        } else {
          file = new File("/" + dir + "/" + file).getCanonicalPath();
        }
      } catch (IOException e) {
        addError("cannot build file path " + dir + ":" + file);
      }
      return file.startsWith("/") ? file.substring(1) : file;
    }

    /**
     * Check if an included file exists and with valid syntax. An included file could be (1) in the
     * current CL, (2) in the same repository, (3) in a different repository, (4) in another CL.
     * Case (4) is not checked yet.
     */
    void checkIncludeOrFile(String project, String path, int num, String line) {
      // project is the including file's project, not necessarily the same as CL event's.
      String directive = Parser.getIncludeOrFile(line);
      String[] KPF = Parser.parseInclude(project, directive);
      if (KPF == null || KPF[1] == null || KPF[2] == null) {
        addSyntaxError(qualifiedPath(project, path), num, line);
      }
      String file = KPF[2];
      String curDir = Util.getParentDir(path);
      String repoFile = normalizeChangedFilePath(curDir, file);
      // Check each file only once.
      String key = KPF[1] + ":" + repoFile;
      if (checkedFiles.contains(key)) {
        addVerboseMsg("skip repeated include of " + key);
        return;
      }
      checkedFiles.add(key);
      if (KPF[1].equals(event.project.getName())) {
        if (allFiles.get(repoFile) != null) {
          // Case (1): included file is in current CL.
          addVerboseMsg("check changed file " + key);
          try {
            ObjectLoader ol = event.revWalk.getObjectReader().open(allFiles.get(repoFile));
            try (InputStream in = ol.openStream()) {
              if (RawText.isBinary(in)) {
                addError(path + " is a binary file"); // OWNERS files cannot be binary
                return;
              }
            }
            checkFile(KPF[1], repoFile, ol);
          } catch (Exception e) {
            addError("cannot open changed file: " + path);
          }
          return;
        }
      }
      // Included file is in repository or other CL.
      addVerboseMsg("check repo file " + key);
      String content =
          OwnersDb.getRepoFile(
              permissionBackend,
              readFiles,
              repoManager,
              null,
              null,
              KPF[1],
              event.refName,
              repoFile,
              new ArrayList<>());
      if (isNullOrEmpty(content)) { // file not found or not readable.
        addVerboseMsg("cannot find file: " + key);
        // unchecked: including-file-path : line number : source line
        addMsg("unchecked: " + qualifiedPath(project, path) + ":" + num + ": " + directive);
      } else {
        checkFile(KPF[1], repoFile, content);
      }
    }

    void checkLine(String project, String path, int lineNumber, String line) {
      String email;
      String[] owners;
      if (Parser.isComment(line) || Parser.isNoParent(line)) {
        // no email address to check
      } else if ((email = Parser.parseEmail(line)) != null) {
        collectEmail(email, project, path, lineNumber);
      } else if ((owners = Parser.parsePerFileOwners(line)) != null) {
        for (String owner : owners) {
          if (owner.startsWith("file:")) {
            // Pass the whole line, not just owner, to report any syntax error.,
            checkIncludeOrFile(project, path, lineNumber, line);
          } else if (!owner.equals(Parser.TOK_SET_NOPARENT)) {
            collectEmail(owner, project, path, lineNumber);
          }
        }
      } else if (Parser.isInclude(line)) {
        checkIncludeOrFile(project, path, lineNumber, line);
      } else {
        addSyntaxError(qualifiedPath(project, path), lineNumber, line);
      }
    }
  } // end of inner class Checker

  /** Return a map from "Path to changed file" to "ObjectId of the file". */
  private static Map<String, ObjectId> getChangedFiles(RevCommit c, RevWalk revWalk)
      throws IOException {
    final Map<String, ObjectId> content = new HashMap<>();
    visitChangedEntries(
        c,
        revWalk,
        new TreeWalkVisitor() {
          @Override
          public void onVisit(TreeWalk tw) {
            // getPathString() returns path names without leading "/"
            if (isFile(tw)) {
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
