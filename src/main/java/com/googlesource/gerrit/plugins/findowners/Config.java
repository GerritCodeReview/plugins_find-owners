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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.entities.Project;
import com.google.gerrit.server.account.AccountCache;
import com.google.gerrit.server.account.Emails;
import com.google.gerrit.server.config.PluginConfig;
import com.google.gerrit.server.config.PluginConfigFactory;
import com.google.gerrit.server.patch.PatchListCache;
import com.google.gerrit.server.project.NoSuchProjectException;
import com.google.gerrit.server.project.ProjectState;
import com.google.gerrit.server.query.change.ChangeData;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** find-owners configuration parameters */
class Config {
  // Name of config parameters that should be defined in gerrit.config:
  static final String ADD_DEBUG_MSG = "addDebugMsg"; // include "dbgmsgs" in returned JSON object
  static final String MAX_CACHE_AGE = "maxCacheAge"; // seconds to stay in cache
  static final String MAX_CACHE_SIZE = "maxCacheSize"; // number of OwnersDb in cache
  static final String MIN_OWNER_VOTE_LEVEL = "minOwnerVoteLevel"; // default +1
  static final String REPORT_SYNTAX_ERROR = "reportSyntaxError"; // only for tests
  // "alwaysShowButton" is obsolete, new UI design always shows the [Find Owners] button

  // Name of config parameters that can be defined in project.config or gerrit.config:
  static final String OWNERS_FILE_NAME = "ownersFileName"; // config key for file name
  static final String REJECT_ERROR_IN_OWNERS = "rejectErrorInOwners"; // enable upload validator

  static final String OWNERS = "OWNERS"; // default OWNERS file name

  // Name of plugin and namespace.
  static final String PLUGIN_NAME = "find-owners";
  static final String PROLOG_NAMESPACE = "find_owners";

  private final PluginConfigFactory configFactory;

  // Each call to API entry point creates one new Config and parses gerrit.config.
  private final BaseConfig gerritConfig;

  // Each Config has a cache of project.config, with projectName:changeId key.
  private final Map<String, BaseConfig> projectConfigMap;

  // Global/plugin config parameters.
  private boolean addDebugMsg = false;
  private int minOwnerVoteLevel = 1;
  private int maxCacheAge = 0;
  private int maxCacheSize = 1000;
  private boolean reportSyntaxError = false;

  // Gerrit server objects to set up JS initcode for JSEConfig.
  private final AccountCache accountCache;
  private final PatchListCache patchListCache;
  private final Emails emails;

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  Config(
      PluginConfigFactory configFactory, // null when called from unit tests
      PluginConfig config, // null when called by Action and Checker
      AccountCache accountCache,
      PatchListCache patchListCache,
      Emails emails) {
    this.configFactory = configFactory;
    this.accountCache = accountCache;
    this.patchListCache = patchListCache;
    this.emails = emails;
    projectConfigMap = new ConcurrentHashMap<>();
    if (configFactory == null && config == null) { // When called from integration tests.
      gerritConfig = null;
      return;
    }
    if (config == null) {
      config = configFactory.getFromGerritConfig(PLUGIN_NAME);
    }
    // Get config variables from the plugin section of gerrit.config
    // It could use JS in value expressions, if useJSE key value is true
    // and JSEPluginConfig is available.
    gerritConfig = newConfig(PLUGIN_NAME, config, null, null, null);
    addDebugMsg = gerritConfig.getBoolean(ADD_DEBUG_MSG, false);
    minOwnerVoteLevel = gerritConfig.getInt(MIN_OWNER_VOTE_LEVEL, 1);
    maxCacheAge = gerritConfig.getInt(MAX_CACHE_AGE, 0);
    maxCacheSize = gerritConfig.getInt(MAX_CACHE_SIZE, 1000);
    reportSyntaxError = gerritConfig.getBoolean(REPORT_SYNTAX_ERROR, false);
  }

  AccountCache accountCache() {
    return accountCache;
  }

  Emails emails() {
    return emails;
  }

  PatchListCache patchListCache() {
    return patchListCache;
  }

  private static BaseConfig newConfig(
      String name, PluginConfig cfg, Project project, ProjectState state, ChangeData changeData) {
    // This function is called
    // (1) per Config (global gerrit.config), when Action or Checker API is called,
    //     with null project, state, and changeData.
    // (2) per ProjectState and ChangeData, for project.config, when getProjectConfig is called.
    //     with null project and non-null state and changeData.
    // (3) per Project, for project.config, by OwnersValidator,
    //     with non-null project and null state and changeData.
    // Now only BaseConfig is returned.
    // In the future, other child class of BaseConfig could be returned,
    // depending on project, state, and changeData..
    if (changeData != null && state == null && project == null) {
      logger.atSevere().log("Unexpected null pointer for change %s", getChangeId(changeData));
    }
    return new BaseConfig(name, cfg);
  }

  boolean getAddDebugMsg() {
    return addDebugMsg; // defined globally, not per-project
  }

  int getMaxCacheAge() {
    return maxCacheAge;
  }

  int getMaxCacheSize() {
    return maxCacheSize;
  }

  boolean getGlobalBooleanValue(String key) {
    return gerritConfig != null && gerritConfig.getBoolean(key, false);
  }

  boolean getRejectErrorInOwners() {
    return getGlobalBooleanValue(REJECT_ERROR_IN_OWNERS);
  }

  boolean getRejectErrorInOwners(Project project) {
    return getBooleanValue(project, REJECT_ERROR_IN_OWNERS);
  }

  boolean getRejectErrorInOwners(ProjectState projectState, ChangeData changeData) {
    return getBooleanValue(projectState, changeData, REJECT_ERROR_IN_OWNERS);
  }

  boolean getBooleanValue(Project project, String key) {
    return getBooleanValue(project, key, getGlobalBooleanValue(key));
  }

  boolean getBooleanValue(Project project, String key, boolean defaultValue) {
    try {
      return getProjectConfig(project).getBoolean(key, defaultValue);
    } catch (NoSuchProjectException e) {
      logger.atSevere().withCause(e).log(
          "Exception in getBooleanValue for %s:%s", project.getName(), key);
      return defaultValue;
    }
  }

  boolean getBooleanValue(ProjectState projectState, ChangeData changeData, String key) {
    return getBooleanValue(projectState, changeData, key, getGlobalBooleanValue(key));
  }

  boolean getBooleanValue(
      ProjectState projectState, ChangeData changeData, String key, boolean defaultValue) {
    return getProjectConfig(projectState, changeData).getBoolean(key, defaultValue);
  }

  boolean getReportSyntaxError() {
    return reportSyntaxError;
  }

  static String getProjectName(ProjectState state, Project project) {
    return state != null
        ? state.getProject().getName()
        : (project != null ? project.getName() : "(unknown project)");
  }

  static String getChangeId(ChangeData data) {
    return data == null ? "(unknown change)" : ("c/" + data.getId().get());
  }

  String getDefaultOwnersFileName() {
    return gerritConfig == null ? OWNERS : gerritConfig.getString(OWNERS_FILE_NAME, OWNERS);
  }

  // This is per ProjectState and ChangeData.
  BaseConfig getProjectConfig(ProjectState state, ChangeData data) {
    // A new Config object is created for every call to Action or Checker.
    // So it is okay to reuse a BaseConfig per (ProjectState:ChangeData).
    // ProjectState parameter must not be null.
    // When the ChangeData parameter is null, the BaseConfig is created
    // with a dummy CL info for the JS expression evaluator.
    String key = state.getName() + ":" + getChangeId(data);
    return projectConfigMap.computeIfAbsent(
        key,
        (String k) ->
            newConfig(
                PLUGIN_NAME,
                configFactory.getFromProjectConfigWithInheritance(state, PLUGIN_NAME),
                null,
                state,
                data));
  }

  // Used by OwnersValidator and tests, not cached.
  BaseConfig getProjectConfig(Project project) throws NoSuchProjectException {
    return newConfig(
        PLUGIN_NAME,
        configFactory.getFromProjectConfigWithInheritance(project.getNameKey(), PLUGIN_NAME),
        project,
        null,
        null);
  }

  String getOwnersFileName() {
    return getOwnersFileName(null, null);
  }

  String getOwnersFileName(ProjectState projectState) {
    return getOwnersFileName(projectState, null);
  }

  String getOwnersFileName(ProjectState projectState, ChangeData c) {
    String defaultName = getDefaultOwnersFileName();
    if (projectState == null) {
      if (c != null) {
        logger.atSevere().log("Null projectState for change %s", getChangeId(c));
      }
      return defaultName;
    }
    String name = getProjectConfig(projectState, c).getString(OWNERS_FILE_NAME, defaultName);
    if (name.trim().isEmpty()) {
      logger.atSevere().log(
          "Project %s has wrong %s: \"%s\" for %s",
          projectState.getProject(), OWNERS_FILE_NAME, name, getChangeId(c));
      return defaultName;
    }
    return name;
  }

  String getOwnersFileName(Project project) {
    String defaultName = getDefaultOwnersFileName();
    try {
      String name = getProjectConfig(project).getString(OWNERS_FILE_NAME, defaultName);
      if (name.trim().isEmpty()) {
        logger.atSevere().log("Project %s has empty %s", project, OWNERS_FILE_NAME);
        return defaultName;
      }
      return name;
    } catch (NoSuchProjectException e) {
      logger.atSevere().withCause(e).log(
          "Exception in getOwnersFileName for %s", project.getName());
      return defaultName;
    }
  }

  @VisibleForTesting
  void setReportSyntaxError(boolean value) {
    reportSyntaxError = value;
  }

  int getMinOwnerVoteLevel(ProjectState projectState, ChangeData c) {
    if (projectState == null) {
      logger.atSevere().log("Null projectState for change %s", getChangeId(c));
      return minOwnerVoteLevel;
    }
    return getProjectConfig(projectState, c).getInt(MIN_OWNER_VOTE_LEVEL, minOwnerVoteLevel);
  }
}
