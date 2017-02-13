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

import com.googlesource.gerrit.plugins.findowners.Util.Owner2Weights;
import com.googlesource.gerrit.plugins.findowners.Util.String2StringSet;
import com.googlesource.gerrit.plugins.findowners.Util.StringSet;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import org.eclipse.jgit.lib.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Keep all information about owners and owned files. */
class OwnersDb {
  private static final Logger log = LoggerFactory.getLogger(OwnersDb.class);

  private Server server; // could be set to a mocked server in unit tests

  private int numOwners; // # of owners of all given files.

  String revision; // tip of branch revision, where OWENRS were found.
  String2StringSet dir2Globs; // (directory) => file globs in the directory
  String2StringSet owner2Paths; // (owner email) => owned dirs or file globs
  String2StringSet path2Owners; // (directory or file glob) => owner emails
  StringSet readDirs; // directories in which we have checked OWNERS
  StringSet stopLooking; // directories where OWNERS has "set noparent"

  private void init(Server s) {
    numOwners = -1;
    revision = "";
    dir2Globs = new String2StringSet();
    owner2Paths = new String2StringSet();
    path2Owners = new String2StringSet();
    readDirs = new StringSet();
    stopLooking = new StringSet();
    server = (null != s) ? s : new Server();
  }

  OwnersDb(Server s) {
    init(s);
  }

  OwnersDb(Server s, String key, Repository repository,
           String branch, Collection<String> files) {
    init(s, key, repository, null, null, branch, files);
  }

  OwnersDb(Server s, String key, String url, String project,
           String branch, Collection<String> files) {
    init(s, key, null, url, project, branch, files);
  }

  int getNumOwners() {
    return (numOwners >= 0) ? numOwners : owner2Paths.keySet().size();
  }

  private void countNumOwners(Collection<String> files) {
    String2StringSet file2Owners = findOwners(files, null);
    if (null != file2Owners) {
      StringSet emails = new StringSet();
      for (String key : file2Owners.keySet()) {
        for (String owner : file2Owners.get(key)) {
          emails.add(owner);
        }
      }
      numOwners = emails.size();
    } else {
      numOwners = owner2Paths.keySet().size();
    }
  }

  private static void addToMap(String2StringSet map,
                               String key, String value) {
    if (null == map.get(key)) {
      map.put(key, new StringSet());
    }
    map.get(key).add(value);
  }

  void addOwnerPathPair(String owner, String path) {
    addToMap(owner2Paths, owner, path);
    addToMap(path2Owners, path, owner);
    if (path.length() > 0 && path.charAt(path.length() - 1) != '/') {
      addToMap(dir2Globs, Util.getDirName(path) + "/", path); // A file glob.
    }
  }

  void addFile(String path, String file, String[] lines) {
    int n = 0;
    for (String line : lines) {
      String error = Parser.parseLine(this, path, file, line, ++n);
      if (null != error && server.getReportSyntaxError()) {
        log.warn(error);
      }
    }
  }

  private void addOwnerWeights(
      ArrayList<String> paths, ArrayList<Integer> distances,
      String file, String2StringSet file2Owners, Owner2Weights map) {
    for (int i = 0; i < paths.size(); i++) {
      StringSet owners = path2Owners.get(paths.get(i));
      if (null == owners) {
        continue;
      }
      for (String name : owners) {
        addToMap(file2Owners, file, name);
        if (null == map) {
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
  String2StringSet findOwners(Collection<String> files) {
    return findOwners(files, null);
  }

  /** Returns owner emails of every file and set up ownerWeights. */
  String2StringSet findOwners(Collection<String> files,
                              Owner2Weights ownerWeights) {
    return findOwners(files.toArray(new String[0]), ownerWeights);
  }

  /** Returns true if path has '*' owner. */
  private boolean findStarOwner(String path, int distance,
                                ArrayList<String> paths,
                                ArrayList<Integer> distances) {
    StringSet owners = path2Owners.get(path);
    if (null != owners) {
      paths.add(path);
      distances.add(new Integer(distance));
      if (owners.contains("*")) {
        return true;
      }
    }
    return false;
  }

  /** Returns owner emails of every file and set up ownerWeights. */
  String2StringSet findOwners(String[] files, Owner2Weights ownerWeights) {
    // Returns a map of file to set of owner emails.
    // If ownerWeights is not null, add to it owner to distance-from-dir;
    // a distance of 1 is the lowest/closest possible distance
    // (which makes the subsequent math easier).
    String2StringSet file2Owners = new String2StringSet();
    for (String fileName : files) {
      fileName = Util.normalizedFilePath(fileName);
      String dirPath = Util.normalizedDirPath(fileName);
      String baseName = fileName.substring(dirPath.length() + 1);
      int distance = 1;
      FileSystem fileSystem = FileSystems.getDefault();
      // Collect all matched (path, distance) in all OWNERS files for
      // fileName. Add them only if there is no special "*" owner.
      ArrayList<String> paths = new ArrayList<String>();
      ArrayList<Integer> distances = new ArrayList<Integer>();
      boolean foundStar = false;
      while (true) {
        int savedSizeOfPaths = paths.size();
        if (dir2Globs.containsKey(dirPath + "/")) {
          StringSet patterns = dir2Globs.get(dirPath + "/");
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
            || !dirPath.contains("/") /* root */ ) {
          break;
        }
        if (paths.size() != savedSizeOfPaths) {
          distance++;  // increase distance for each found OWNERS
        }
        dirPath = Util.getDirName(dirPath); // go up one level
      }
      if (!foundStar) {
        addOwnerWeights(paths, distances, fileName,
                        file2Owners, ownerWeights);
      }
    }
    return file2Owners;
  }

  private void init(
      Server s, String key, Repository repository, String url,
      String project, String branch, Collection<String> files) {
    init(s);
    for (String fileName : files) {
      // Find OWNERS in fileName's directory and parent directories.
      // Stop looking parent directory after an OWNERS with "set noparent".
      fileName = Util.normalizedFilePath(fileName);
      String dir = Util.normalizedDirPath(fileName);
      while (!readDirs.contains(dir)) {
        readDirs.add(dir);
        String content = server.getOWNERS(dir, repository, url,
                                          project, branch);
        if (null != content && !content.equals("")) {
          addFile(dir + "/", dir + "/OWNERS", content.split("\\R+"));
        }
        if (stopLooking.contains(dir + "/") || !dir.contains("/")) {
          break; // stop looking parent directory
        }
        dir = Util.getDirName(dir); // go up one level
      }
    }
    countNumOwners(files);
    revision = server.getBranchRevision(repository, url, project, branch);
  }
}
