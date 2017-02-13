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

import java.util.Collection;
import java.util.Date;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedList;
import org.eclipse.jgit.lib.Repository;

/** Save OwnersDb in a cache for multiple calls to submit_filter. */
class Cache {
  // The OwnersDb is created from OWNERS files in directories that
  // contain changed files of a patch set, which belongs to a project
  // and branch. OwnersDb can be cached if the head of a project branch
  // and the patch set are not changed.

  // Although the head of a project branch could be changed by other users,
  // it is better to assume the same for a patch set during a short period
  // of time. So multiple checks would have the same result. For example,
  // one client UI action can trigger multiple HTTP requests.
  // Each HTTP request has one StoredValues,
  // and can trigger multiple Prolog submit_filter.
  // Each submit_filter has one Prolog engine.
  // It would not be enough to keep the cache in a Prolog engine environment
  // or a StoredValues.
  // We keep the cache in a Java class static object for all HTTP requests.

  // OwnersDb is cached for up to 10 seconds.
  private static final int CACHE_LIFE_MSEC = 10000;

  // When branch is "refs/heads/xyz" use only "xyz",
  // to share cached OwnersDb between these two branch names.
  private static final String REFS_HEADS = "refs/heads/";

  static class CachedObj {
    long time;   // system time in milliseconds, when db is created
    String key;  // (changeId, patchSetId, branchName)
    OwnersDb db;
    CachedObj(String k, OwnersDb x) {
      time = new Date().getTime();
      key = k;
      db = x;
    }
  }

  // Before a new CachedObj is added to the tail of dbQueue,
  // old and obsolete CachedObj are removed from the head.
  private static final Deque<CachedObj> dbQueue = new LinkedList<CachedObj>();

  // A HashMap provides quick lookup with a key.
  private static final HashMap<String, CachedObj> dbCache =
      new HashMap<String, CachedObj>();

  private static long minCachedObjectTime() {
    // Cached objects must be used within CACHE_LIFE_MSEC.
    return new Date().getTime() - CACHE_LIFE_MSEC;
  }

  static String makeKey(int change, int patch, String branch) {
    if (branch.indexOf(REFS_HEADS) == 0) {
      branch = branch.substring(REFS_HEADS.length());
    }
    return change + ":" + patch + ":" + branch;
  }

  private static void saveCachedDb(String key, OwnersDb db) {
    CachedObj obj = new CachedObj(key, db);
    long minTime = minCachedObjectTime();
    synchronized (dbCache) {
      // Remove cached objects older than minTime.
      while (dbQueue.size() > 0 && dbQueue.peek().time < minTime) {
        dbCache.remove(dbQueue.peek().key);
        dbQueue.removeFirst();
      }
      // Add the new one to the tail.
      dbCache.put(key, obj);
      dbQueue.addLast(obj);
    }
  }

  static OwnersDb get(Server server, String key, String url, String project,
                      String branch, Collection<String> files) {
    return get(server, key, null, url, project, branch, files);
  }

  static OwnersDb get(Server server, String key, Repository repository,
                      String branch, Collection<String> files) {
    return get(server, key, repository, null, null, branch, files);
  }

  private static OwnersDb get(
      Server server, String key, Repository repository, String url,
      String project, String branch, Collection<String> files) {
    OwnersDb db = null;
    long minTime = minCachedObjectTime();
    synchronized (dbCache) {
      if (dbCache.containsKey(key)) {
        CachedObj obj = dbCache.get(key);
        if (obj.time >= minTime) {
          db = obj.db;
        }
      }
    }
    if (null == db) {
      db = (null != repository)
          ? new OwnersDb(server, key, repository, branch, files)
          : new OwnersDb(server, key, url, project, branch, files);
      saveCachedDb(key, db);
    }
    return db;
  }
}
