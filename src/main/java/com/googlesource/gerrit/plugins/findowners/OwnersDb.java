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

import com.google.common.collect.Multimap;
import com.google.gerrit.reviewdb.client.Account;
import com.google.gerrit.server.account.AccountCache;
import com.google.gerrit.server.account.Emails;
import com.google.gerrit.server.project.ProjectState;
import com.google.gerrit.server.query.change.ChangeData;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.ArrayList;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Keep all information about owners and owned files. */
class OwnersDb {
  private static final Logger log = LoggerFactory.getLogger(OwnersDb.class);

  private AccountCache accountCache;
  private Emails emails;
  private int numOwners = -1; // # of owners of all given files.

  String key = ""; // key to find this OwnersDb in a cache.
  String revision = ""; // tip of branch revision, where OWENRS were found.
  Map<String, Set<String>> dir2Globs = new HashMap<>(); // directory to file globs in the directory
  Map<String, Set<String>> owner2Paths = new HashMap<>(); // owner email to owned dirs or file globs
  Map<String, Set<String>> path2Owners = new HashMap<>(); // dir or file glob to owner emails
  Set<String> readDirs = new HashSet<>(); // directories in which we have checked OWNERS
  Set<String> stopLooking = new HashSet<>(); // directories where OWNERS has "set noparent"
  Map<String, String> preferredEmails = new HashMap<>(); // owner email to preferred email
  List<String> errors = new ArrayList<>(); // error messages

  OwnersDb() {}

  OwnersDb(
      ProjectState projectState,
      AccountCache accountCache,
      Emails emails,
      String key,
      Repository repository,
      ChangeData changeData,
      String branch,
      Collection<String> files) {
    this.accountCache = accountCache;
    this.emails = emails;
    this.key = key;
    preferredEmails.put("*", "*");
    String ownersFileName = Config.getOwnersFileName(projectState, changeData);
    // Some hacked CL could have a target branch that is not created yet.
    ObjectId id = getBranchId(repository, branch, changeData);
    revision = "";
    if (id != null) {
      for (String fileName : files) {
        // Find OWNERS in fileName's directory and parent directories.
        // Stop looking for a parent directory if OWNERS has "set noparent".
        fileName = Util.normalizedFilePath(fileName);
        String dir = Util.normalizedDirPath(fileName); // e.g. dir = ./d1/d2
        while (!readDirs.contains(dir)) {
          readDirs.add(dir);
          String filePath = (dir + "/" + ownersFileName).substring(2); // remove "./"
          String content = getRepositoryFile(repository, id, filePath);
          if (content != null && !content.equals("")) {
            addFile(dir + "/", dir + "/" + ownersFileName, content.split("\\R+"));
          }
          if (stopLooking.contains(dir + "/") || !dir.contains("/")) {
            break; // stop looking through parent directory
          }
          dir = Util.getDirName(dir); // go up one level
        }
      }
      try {
        revision = repository.getRef(branch).getObjectId().getName();
      } catch (Exception e) {
        log.error("Fail to get branch revision for " + Config.getChangeId(changeData), e);
      }
    }
    countNumOwners(files);
  }

  int getNumOwners() {
    return (numOwners >= 0) ? numOwners : owner2Paths.keySet().size();
  }

  private void countNumOwners(Collection<String> files) {
    Map<String, Set<String>> file2Owners = findOwners(files, null);
    if (file2Owners != null) {
      Set<String> emails = new HashSet<>();
      file2Owners.values().forEach(emails::addAll);
      numOwners = emails.size();
    } else {
      numOwners = owner2Paths.keySet().size();
    }
  }

  void addOwnerPathPair(String owner, String path) {
    Util.addToMap(owner2Paths, owner, path);
    Util.addToMap(path2Owners, path, owner);
    if (path.length() > 0 && path.charAt(path.length() - 1) != '/') {
      Util.addToMap(dir2Globs, Util.getDirName(path) + "/", path); // A file glob.
    }
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
        log.error("accounts.byEmails failed with exception: ", e);
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
              email = accountCache.get(ids.iterator().next()).getAccount().getPreferredEmail();
            }
          }
        } catch (Exception e) {
          log.error("Fail to find preferred email of " + owner, e);
          errors.add(owner);
        }
        preferredEmails.put(owner, email);
      }
    }
  }

  void addFile(String dirPath, String filePath, String[] lines) {
    Parser.Result result = Parser.parseFile(dirPath, filePath, lines);
    if (result.stopLooking) {
      stopLooking.add(dirPath);
    }
    addPreferredEmails(result.owner2paths.keySet());
    for (String owner : result.owner2paths.keySet()) {
      String email = preferredEmails.get(owner);
      for (String path : result.owner2paths.get(owner)) {
        addOwnerPathPair(email, path);
      }
    }
    if (Config.getReportSyntaxError()) {
      result.warnings.forEach(w -> log.warn(w));
      result.errors.forEach(w -> log.error(w));
    }
  }

  private void addOwnerWeights(
      ArrayList<String> paths,
      ArrayList<Integer> distances,
      String file,
      Map<String, Set<String>> file2Owners,
      Map<String, OwnerWeights> map) {
    for (int i = 0; i < paths.size(); i++) {
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
    return findOwners(files, null);
  }

  /** Returns owner emails of every file and set up ownerWeights. */
  Map<String, Set<String>> findOwners(
      Collection<String> files, Map<String, OwnerWeights> ownerWeights) {
    return findOwners(files.toArray(new String[0]), ownerWeights);
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

  /** Returns owner emails of every file and set up ownerWeights. */
  Map<String, Set<String>> findOwners(String[] files, Map<String, OwnerWeights> ownerWeights) {
    // Returns a map of file to set of owner emails.
    // If ownerWeights is not null, add to it owner to distance-from-dir;
    // a distance of 1 is the lowest/closest possible distance
    // (which makes the subsequent math easier).
    Map<String, Set<String>> file2Owners = new HashMap<>();
    for (String fileName : files) {
      fileName = Util.normalizedFilePath(fileName);
      String dirPath = Util.normalizedDirPath(fileName);
      String baseName = fileName.substring(dirPath.length() + 1);
      int distance = 1;
      FileSystem fileSystem = FileSystems.getDefault();
      // Collect all matched (path, distance) in all OWNERS files for
      // fileName. Add them only if there is no special "*" owner.
      ArrayList<String> paths = new ArrayList<>();
      ArrayList<Integer> distances = new ArrayList<>();
      boolean foundStar = false;
      while (true) {
        int savedSizeOfPaths = paths.size();
        if (dir2Globs.containsKey(dirPath + "/")) {
          Set<String> patterns = dir2Globs.get(dirPath + "/");
          for (String pat : patterns) {
            PathMatcher matcher = fileSystem.getPathMatcher("glob:" + pat);
            if (matcher.matches(Paths.get(dirPath + "/" + baseName))) {
              foundStar |= findStarOwner(pat, distance, paths, distances);
              // Do not break here, a file could match multiple globs
              // with different owners.
              // OwnerWeights.add won't add duplicated files.
            }
          }
          // NOTE: A per-file directive can only specify owner emails,
          // not "set noparent".
        }
        // If baseName does not match per-file glob, paths is not changed.
        // Then we should check the general non-per-file owners.
        if (paths.size() == savedSizeOfPaths) {
          foundStar |= findStarOwner(dirPath + "/", distance, paths, distances);
        }
        if (foundStar // This file can be approved by anyone, no owner.
            || stopLooking.contains(dirPath + "/") // stop looking parent
            || !dirPath.contains("/") /* root */) {
          break;
        }
        if (paths.size() != savedSizeOfPaths) {
          distance++; // increase distance for each found OWNERS
        }
        dirPath = Util.getDirName(dirPath); // go up one level
      }
      if (!foundStar) {
        addOwnerWeights(paths, distances, fileName, file2Owners, ownerWeights);
      }
    }
    return file2Owners;
  }

  /** Returns ObjectId of the given branch, or null. */
  private static ObjectId getBranchId(Repository repo, String branch, ChangeData changeData) {
    try {
      ObjectId id = repo.resolve(branch);
      if (id == null && changeData != null && !Checker.isExemptFromOwnerApproval(changeData)) {
        log.error("cannot find branch " + branch + " for " + Config.getChangeId(changeData));
      }
      return id;
    } catch (Exception e) {
      log.error("cannot find branch " + branch + " for " + Config.getChangeId(changeData), e);
    }
    return null;
  }

  /** Returns file content or empty string; uses Repository. */
  private static String getRepositoryFile(Repository repo, ObjectId id, String file) {
    try (RevWalk revWalk = new RevWalk(repo)) {
      RevTree tree = revWalk.parseCommit(id).getTree();
      ObjectReader reader = revWalk.getObjectReader();
      TreeWalk treeWalk = TreeWalk.forPath(reader, file, tree);
      if (treeWalk != null) {
        return new String(reader.open(treeWalk.getObjectId(0)).getBytes(), UTF_8);
      }
    } catch (Exception e) {
      log.error("get file " + file, e);
    }
    return "";
  }
}
