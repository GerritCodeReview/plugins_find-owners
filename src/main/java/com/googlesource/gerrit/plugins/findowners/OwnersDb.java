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

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.Ordering;
import com.google.common.collect.Multimap;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.reviewdb.client.Account;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.server.account.AccountCache;
import com.google.gerrit.server.account.Emails;
import com.google.gerrit.server.config.PluginConfigFactory;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.project.ProjectState;
import com.google.gerrit.server.query.change.ChangeData;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;

/** Keep all information about owners and owned files. */
class OwnersDb {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final AccountCache accountCache;
  private final GitRepositoryManager repoManager;
  private final Emails emails;
  private final Config config;
  private int numOwners = -1; // # of owners of all given files.

  String key = ""; // key to find this OwnersDb in a cache.
  String revision = ""; // tip of branch revision, where OWENRS were found.
  Map<String, Set<String>> dir2Globs = new HashMap<>(); // directory to file globs in the directory
  Map<String, Set<String>> owner2Paths = new HashMap<>(); // owner email to owned dirs or file globs
  Map<String, Set<String>> path2Owners = new HashMap<>(); // dir or file glob to owner emails
  Set<String> readDirs = new HashSet<>(); // directories in which we have checked OWNERS
  Set<String> stopLooking = new HashSet<>(); // directories where OWNERS has "set noparent"
  Set<String> noParentGlobs = new HashSet<>(); // per-file globs with "set noparent"
  Map<String, String> preferredEmails = new HashMap<>(); // owner email to preferred email
  List<String> errors = new ArrayList<>(); // error messages
  List<String> logs = new ArrayList<>(); // trace/debug messages

  OwnersDb(
      ProjectState projectState,
      AccountCache accountCache,
      Emails emails,
      String key,
      GitRepositoryManager repoManager,
      PluginConfigFactory configFactory,
      ChangeData changeData,
      String branch,
      Collection<String> files) {
    this.accountCache = accountCache;
    this.repoManager = repoManager;
    this.emails = emails;
    this.key = key;
    this.config = new Config(configFactory);
    try {
      InetAddress inetAddress = InetAddress.getLocalHost();
      logs.add("HostName:" + inetAddress.getHostName());
    } catch (UnknownHostException e) {
      logException(logs, "HostName:", e);
    }
    logs.add("key:" + key);
    preferredEmails.put("*", "*"); // '*' maps to itself, has no user account
    String projectName = projectState.getName();
    logs.add("project:" + projectName);
    String ownersFileName = config.getOwnersFileName(projectState, changeData);
    logs.add("ownersFileName:" + ownersFileName);
    try (Repository repo = repoManager.openRepository(projectState.getNameKey())) {
      // Some hacked CL could have a target branch that is not created yet.
      ObjectId id = getBranchId(repo, branch, changeData, logs);
      revision = "";
      // For the same repo and branch id, keep content of all read files to avoid
      // repeated read. This cache of files should be passed down to the Parser to
      // avoid reading the same file through "include" or "file:" statements.
      Map<String, String> readFiles = new HashMap<>();
      if (id != null) {
        if (!ownersFileName.equals(Config.OWNERS) && branch.equals("refs/heads/master")) {
          // If ownersFileName is not the default "OWNERS", and current branch is master,
          // this project should have a non-empty root file of that name.
          // We added this requirement to detect errors in project config files
          // and Gerrit server bugs that return wrong value of "ownersFileName".
          String content = getFile(readFiles, repo, projectName, id, "/" + ownersFileName, logs);
          String found = "Found";
          if (content.isEmpty()) {
            String changeId = Config.getChangeId(changeData);
            logger.atSevere().log("Missing root %s for %s of %s",
                ownersFileName, changeId, projectName);
            found = "Missing";
          }
          logs.add(found + " root " + ownersFileName);
        }
        for (String fileName : files) {
          // Find OWNERS in fileName's directory and parent directories.
          // Stop looking for a parent directory if OWNERS has "set noparent".
          fileName = Util.addDotPrefix(fileName); // e.g.   "./" "./d1/f1" "./d2/d3/"
          String dir = Util.getParentDir(fileName); // e.g. "."  "./d1"    "./d2"
          logs.add("findOwnersFileFor:" + fileName);
          // Multiple changed files can be in one directory, but each directory
          // is only searched once for an OWNERS file.
          // However one OWNERS or any file can be included by OWNERS files in
          // different directories. In that case, the included file could be parsed
          // multiple times for different "dir".
          // Since open/read a Gerrit repositoy file could be slow, getFile should keep
          // a copy of all read files to avoid repeated read of the same file.
          while (!readDirs.contains(dir)) {
            readDirs.add(dir);
            logs.add("findOwnersFileIn:" + dir);
            String filePath = dir + "/" + ownersFileName;
            String content = getFile(readFiles, repo, projectName, id, filePath, logs);
            if (content != null && !content.isEmpty()) {
              addFile(readFiles, projectName, branch, dir + "/", dir + "/" + ownersFileName,
                      content.split("\\R"));
            }
            if (stopLooking.contains(dir + "/") || !dir.contains("/")) {
              break; // stop looking through parent directory
            }
            dir = Util.getDirName(dir); // go up one level
          }
        }
        try {
          revision = repo.exactRef(branch).getObjectId().getName();
        } catch (Exception e) {
          logger.atSevere().withCause(e).log(
              "Fail to get branch revision for %s", Config.getChangeId(changeData));
          logException(logs, "OwnersDb get revision", e);
        }
      }
    } catch (Exception e) {
      logger.atSevere().log("OwnersDb failed to find repository of project %s", projectName);
      logException(logs, "OwnersDb get repository", e);
    }
    countNumOwners(files);
  }

  int getNumOwners() {
    return (numOwners >= 0) ? numOwners : owner2Paths.size();
  }

  private void countNumOwners(Collection<String> files) {
    logs.add("countNumOwners");
    Map<String, Set<String>> file2Owners = findOwners(files, null, logs);
    if (file2Owners != null) {
      Set<String> emails = new HashSet<>();
      file2Owners.values().forEach(emails::addAll);
      numOwners = emails.size();
    } else {
      numOwners = owner2Paths.size();
    }
  }

  void addOwnerPathPair(String owner, String path) {
    Util.addToMap(owner2Paths, owner, path);
    Util.addToMap(path2Owners, path, owner);
    if (path.length() > 0 && path.charAt(path.length() - 1) != '/') {
      add2dir2Globs(Util.getDirName(path) + "/", path); // A file glob.
    }
  }

  void add2dir2Globs(String dir, String glob) {
    Util.addToMap(dir2Globs, dir, glob);
  }

  void addPreferredEmails(Set<String> ownerEmails) {
    List<String> owners = new ArrayList<>(ownerEmails);
    owners.removeIf(o -> preferredEmails.get(o) != null);
    if (!owners.isEmpty()) {
      String[] ownerEmailsAsArray = new String[owners.size()];
      owners.toArray(ownerEmailsAsArray);
      Multimap<String, Account.Id> email2ids = null;
      try {
        email2ids = emails.getAccountsFor(ownerEmailsAsArray);
      } catch (Exception e) {
        logger.atSevere().withCause(e).log("accounts.byEmails failed");
        logException(logs, "getAccountsFor:" + ownerEmailsAsArray[0], e);
      }
      for (String owner : ownerEmailsAsArray) {
        String email = owner;
        try {
          if (email2ids == null) {
            errors.add(owner);
          } else {
            Collection<Account.Id> ids = email2ids.get(owner);
            if (ids == null || ids.size() != 1) {
              errors.add(owner);
            } else {
              // Accounts may have no preferred email.
              email =
                  accountCache
                      .get(ids.iterator().next())
                      .map(a -> a.getAccount().getPreferredEmail())
                      .orElse(null);
            }
          }
        } catch (Exception e) {
          logger.atSevere().withCause(e).log("Fail to find preferred email of %s", owner);
          errors.add(owner);
        }
        preferredEmails.put(owner, email);
      }
    }
  }

  void addFile(Map<String, String> readFiles, String project, String branch,
      String dirPath, String filePath, String[] lines) {
    Parser parser = new Parser(readFiles, repoManager, project, branch, filePath, logs);
    Parser.Result result = parser.parseFile(dirPath, lines);
    if (result.stopLooking) {
      stopLooking.add(dirPath);
    }
    noParentGlobs.addAll(result.noParentGlobs);
    addPreferredEmails(result.owner2paths.keySet());
    for (String owner : result.owner2paths.keySet()) {
      String email = preferredEmails.get(owner);
      for (String path : result.owner2paths.get(owner)) {
        addOwnerPathPair(email, path);
      }
    }
    for (String glob : result.noParentGlobs) {
      add2dir2Globs(Util.getDirName(glob) + "/", glob);
    }
    if (config.getReportSyntaxError()) {
      Ordering.natural().sortedCopy(result.warnings).forEach(w -> logger.atWarning().log(w));
      Ordering.natural().sortedCopy(result.errors).forEach(e -> logger.atSevere().log(e));
    }
  }

  private void addOwnerWeights(
      ArrayList<String> paths,
      ArrayList<Integer> distances,
      String file,
      Map<String, Set<String>> file2Owners,
      Map<String, OwnerWeights> map,
      List<String> logs) {
    for (int i = 0; i < paths.size(); i++) {
      logs.add("addOwnerWeightsIn:" + paths.get(i));
      Set<String> owners = path2Owners.get(paths.get(i));
      if (owners == null) {
        continue;
      }
      for (String name : owners) {
        Util.addToMap(file2Owners, file, name);
        if (map == null) {
          continue;
        }
        if (map.containsKey(name)) {
          map.get(name).addFile(file, distances.get(i));
        } else {
          map.put(name, new OwnerWeights(file, distances.get(i)));
        }
      }
    }
  }

  /** Quick method to find owner emails of every file. */
  Map<String, Set<String>> findOwners(Collection<String> files) {
    return findOwners(files, null, new ArrayList<>());
  }

  /** Returns owner emails of every file and set up ownerWeights. */
  Map<String, Set<String>> findOwners(
      Collection<String> files, Map<String, OwnerWeights> ownerWeights, List<String> logs) {
    return findOwners(files.toArray(new String[0]), ownerWeights, logs);
  }

  /** Returns owner emails of every file and set up ownerWeights. */
  Map<String, Set<String>> findOwners(
      String[] files, Map<String, OwnerWeights> ownerWeights, List<String> logs) {
    // Returns a map of file to set of owner emails.
    // If ownerWeights is not null, add to it owner to distance-from-dir;
    // a distance of 1 is the lowest/closest possible distance
    // (which makes the subsequent math easier).
    logs.add("findOwners");
    Arrays.sort(files); // Force an ordered search sequence.
    Map<String, Set<String>> file2Owners = new HashMap<>();
    for (String fileName : files) {
      fileName = Util.addDotPrefix(fileName);
      logs.add("checkFile:" + fileName);
      String dirPath = Util.getParentDir(fileName); // ".", "./d1", "./d1/d2", etc.
      String baseName = fileName.substring(dirPath.length() + 1);
      int distance = 1;
      FileSystem fileSystem = FileSystems.getDefault();
      // Collect all matched (path, distance) in all OWNERS files for
      // fileName. Add all of them, even with the special "*" owner.
      ArrayList<String> paths = new ArrayList<>();
      ArrayList<Integer> distances = new ArrayList<>();
      boolean foundStar = false;
      while (true) {
        int savedSizeOfPaths = paths.size();
        logs.add("checkDir:" + dirPath);
        boolean foundNoParentGlob = false;
        if (dir2Globs.containsKey(dirPath + "/")) {
          Set<String> patterns = dir2Globs.get(dirPath + "/");
          for (String pat : patterns) {
            PathMatcher matcher = fileSystem.getPathMatcher("glob:" + pat);
            if (matcher.matches(Paths.get(dirPath + "/" + baseName))) {
              foundStar |= findStarOwner(pat, distance, paths, distances);
              foundNoParentGlob |= noParentGlobs.contains(pat);
              // Do not break here, a file could match multiple globs
              // with different owners.
              // OwnerWeights.add won't add duplicated files.
            }
          }
        }
        // Unless foundNoParentGlob, we should check the general non-per-file owners.
        if (!foundNoParentGlob) {
          foundStar |= findStarOwner(dirPath + "/", distance, paths, distances);
        }
        if (stopLooking.contains(dirPath + "/") // stop looking parent
            || foundNoParentGlob                // per-file "set noparent"
            || !dirPath.contains("/") /* root */) {
          break;
        }
        if (paths.size() != savedSizeOfPaths) {
          distance++; // increase distance for each found OWNERS
        }
        dirPath = Util.getDirName(dirPath); // go up one level
      }
      if (foundStar) {
        logs.add("found * in:" + fileName);
      }
      addOwnerWeights(paths, distances, fileName, file2Owners, ownerWeights, logs);
    }
    return file2Owners;
  }

  /** Returns true if path has '*' owner. */
  private boolean findStarOwner(
      String path, int distance, ArrayList<String> paths, ArrayList<Integer> distances) {
    Set<String> owners = path2Owners.get(path);
    if (owners != null) {
      paths.add(path);
      distances.add(distance);
      if (owners.contains("*")) {
        return true;
      }
    }
    return false;
  }

  /** Returns ObjectId of the given branch, or null. */
  private static ObjectId getBranchId(
      Repository repo, String branch, ChangeData changeData, List<String> logs) {
    String header = "getBranchId:" + branch;
    try {
      ObjectId id = repo.resolve(branch);
      if (id == null && changeData != null && !Checker.isExemptFromOwnerApproval(changeData)) {
        logger.atSevere().log(
            "cannot find branch %s for %s", branch, Config.getChangeId(changeData));
        logs.add(header + " (NOT FOUND)");
      } else {
        logs.add(header + " (FOUND)");
      }
      return id;
    } catch (Exception e) {
      logger.atSevere().withCause(e).log(
          "cannot find branch %s for %s", branch, Config.getChangeId(changeData));
      logException(logs, header, e);
    }
    return null;
  }

  private static String findReadFile(Map<String, String> readFiles, String project, String file) {
    String key = project + ":" + file;
    if (readFiles != null && readFiles.get(key) != null) {
      return readFiles.get(key);
    }
    return null;
  }

  private static void saveReadFile(
      Map<String, String> readFiles, String project, String file, String content) {
    if (readFiles != null) {
      readFiles.put(project + ":" + file, content);
    }
  }

  /** Returns file content or empty string; uses project+branch+file names. */
  public static String getRepoFile(Map<String, String> readFiles, GitRepositoryManager repoManager,
      String project, String branch, String file, List<String> logs) {
    // 'file' must be an absolute path from the root of 'project'.
    logs.add("getRepoFile:" + project + ":" + branch + ":" + file);
    file = Util.gitRepoFilePath(file);
    String content = findReadFile(readFiles, project, file);
    if (content == null) {
      content = "";
      if (repoManager != null) { // ParserTest can call with null repoManager
        try (Repository repo = repoManager.openRepository(new Project.NameKey(project))) {
          ObjectId id = repo.resolve(branch);
          if (id != null) {
            return getFile(readFiles, repo, project, id, file, logs);
          }
          logs.add("getRepoFile not found branch " + branch);
        } catch (Exception e) {
          logger.atSevere().log("getRepoFile failed to find repository of project %s", project);
          logException(logs, "getRepoFile", e);
        }
      }
    }
    return content;
  }

  /** Returns file content or empty string; uses Repository. */
  private static String getFile(Map<String, String> readFiles,
      Repository repo, String project, ObjectId id, String file, List<String> logs) {
    file = Util.gitRepoFilePath(file);
    String content = findReadFile(readFiles, project, file);
    if (content == null) {
      content = "";
      try (RevWalk revWalk = new RevWalk(repo)) {
        String header = "getFile:" + file;
        RevTree tree = revWalk.parseCommit(id).getTree();
        ObjectReader reader = revWalk.getObjectReader();
        TreeWalk treeWalk = TreeWalk.forPath(reader, file, tree);
        if (treeWalk != null) {
          content = new String(reader.open(treeWalk.getObjectId(0)).getBytes(), UTF_8);
          logs.add(header + ":(...)");
        } else {
          logs.add(header + " (NOT FOUND)");
        }
      } catch (Exception e) {
        logger.atSevere().withCause(e).log("get file %s", file);
        logException(logs, "getFile", e);
      }
      saveReadFile(readFiles, project, file, content);
    }
    return content;
  }

  /** Adds a header + exception message to the logs. */
  private static void logException(List<String> logs, String header, Exception e) {
    logs.add(header + " Exception:" + e.getMessage());
  }
}
