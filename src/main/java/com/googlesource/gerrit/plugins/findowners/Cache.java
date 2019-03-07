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

import static java.util.concurrent.TimeUnit.SECONDS;

import com.google.common.cache.CacheBuilder;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.server.account.AccountCache;
import com.google.gerrit.server.account.Emails;
import com.google.gerrit.server.config.PluginConfigFactory;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.project.ProjectState;
import com.google.gerrit.server.query.change.ChangeData;
import com.google.gwtorm.server.OrmException;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

/** Save OwnersDb in a cache for multiple calls to submit_filter. */
public class Cache {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

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

  // Since a Java runtime can host multiple Gerrit sites, each site should have
  // its own cache. We assume that each site has its own GitRepositoryManager.
  // We use a WeakHashMap to avoid leaking objects in the map.
  private static final Map<GitRepositoryManager, Cache> cacheMap =
      Collections.synchronizedMap(new WeakHashMap<GitRepositoryManager, Cache>());

  // dbCache key is generated by makeKey.
  private com.google.common.cache.Cache<String, OwnersDb> dbCache;

  private Cache(int maxSeconds, int maxSize) {
    init(maxSeconds, maxSize);
  }

  long size() {
    return (dbCache == null) ? 0 : dbCache.size();
  }

  Cache init(int maxSeconds, int maxSize) {
    // This should be called once in normal configuration,
    // but could be called multiple times in unit or integration tests.
    if (dbCache != null) {
      dbCache.invalidateAll(); // release all cached objects
    }
    if (maxSeconds > 0) {
      logger.atInfo().log("Initialize Cache with maxSeconds=%d maxSize=%d", maxSeconds, maxSize);
      dbCache =
          CacheBuilder.newBuilder()
              .maximumSize(maxSize)
              .expireAfterWrite(maxSeconds, SECONDS)
              .build();
    } else {
      logger.atInfo().log("Cache disabled.");
      dbCache = null;
    }
    return this;
  }

  /** Returns a cached or new OwnersDb, for the current patchset. */
  public OwnersDb get(
      Boolean useCache,
      ProjectState projectState,
      AccountCache accountCache,
      Emails emails,
      GitRepositoryManager repoManager,
      PluginConfigFactory configFactory,
      ChangeData changeData)
      throws OrmException, IOException {
    return get(
        useCache,
        projectState,
        accountCache,
        emails,
        repoManager,
        configFactory,
        changeData,
        changeData.currentPatchSet().getId().get());
  }

  /** Returns a cached or new OwnersDb, for the specified patchset. */
  OwnersDb get(
      Boolean useCache,
      ProjectState projectState,
      AccountCache accountCache,
      Emails emails,
      GitRepositoryManager repoManager,
      PluginConfigFactory configFactory,
      ChangeData changeData,
      int patchset)
      throws OrmException, IOException {
    String branch = changeData.change().getDest().get();
    String dbKey = Cache.makeKey(changeData.getId().get(), patchset, repoManager);
    // TODO: get changed files of the given patchset?
    return get(
        useCache,
        projectState,
        accountCache,
        emails,
        dbKey,
        repoManager,
        configFactory,
        changeData,
        branch,
        changeData.currentFilePaths());
  }

  /** Returns a cached or new OwnersDb, for the specified branch and changed files. */
  OwnersDb get(
      Boolean useCache,
      ProjectState projectState,
      AccountCache accountCache,
      Emails emails,
      String key,
      GitRepositoryManager repoManager,
      PluginConfigFactory configFactory,
      ChangeData changeData,
      String branch,
      Collection<String> files) {
    if (dbCache == null || !useCache) { // Do not cache OwnersDb
      logger.atFiner().log("Create new OwnersDb, key=%s", key);
      return new OwnersDb(
          projectState,
          accountCache,
          emails,
          key,
          repoManager,
          configFactory,
          changeData,
          branch,
          files);
    }
    try {
      logger.atFiner().log(
          "Get from cache %s, key=%s, cache size=%d", dbCache, key, dbCache.size());
      return dbCache.get(
          key,
          new Callable<OwnersDb>() {
            @Override
            public OwnersDb call() {
              logger.atFiner().log("Create new OwnersDb, key=%s", key);
              return new OwnersDb(
                  projectState,
                  accountCache,
                  emails,
                  key,
                  repoManager,
                  configFactory,
                  changeData,
                  branch,
                  files);
            }
          });
    } catch (ExecutionException e) {
      logger.atSevere().withCause(e).log(
          "Cache.get has exception for %s", Config.getChangeId(changeData));
      return new OwnersDb(
          projectState,
          accountCache,
          emails,
          key,
          repoManager,
          configFactory,
          changeData,
          branch,
          files);
    }
  }

  public static String makeKey(int change, int patch, GitRepositoryManager repoManager) {
    return String.format("%d:%d:%H", change, patch, repoManager);
  }

  public static Cache getInstance(
      PluginConfigFactory configFactory, GitRepositoryManager repoManager) {
    Cache cache = cacheMap.get(repoManager);
    if (cache == null) {
      Config config = new Config(configFactory);
      cache = new Cache(config.getMaxCacheAge(), config.getMaxCacheSize());
      cacheMap.put(repoManager, cache);
    }
    return cache;
  }
}
