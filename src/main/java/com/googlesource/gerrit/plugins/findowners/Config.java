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
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.server.config.PluginConfig;
import com.google.gerrit.server.config.PluginConfigFactory;
import com.google.gerrit.server.project.NoSuchProjectException;
import com.google.gerrit.server.project.ProjectState;
import com.google.gerrit.server.query.change.ChangeData;
import java.util.HashMap;
import java.util.Map;

/** find-owners configuration parameters */
class Config {
  // Name of config parameters that should be defined in gerrit.config:
  static final String ADD_DEBUG_MSG = "addDebugMsg"; // include "dbgmsgs" in returned JSON object
  static final String MAX_CACHE_AGE = "maxCacheAge"; // seconds to stay in cache
  static final String MAX_CACHE_SIZE = "maxCacheSize"; // number of OwnersDb in cache
  static final String MIN_OWNER_VOTE_LEVEL = "minOwnerVoteLevel"; // default +1
  static final String REPORT_SYNTAX_ERROR = "reportSyntaxError"; // only for tests
  // "alwaysShowButton" is obsolete, new UI design always shows the [Find Owners] button

  // Name of config parameters that can be defined in project.config or gerrit.confg:
  static final String OWNERS_FILE_NAME = "ownersFileName"; // config key for file name
  static final String REJECT_ERROR_IN_OWNERS = "rejectErrorInOwners"; // enable upload validator

  static final String OWNERS = "OWNERS"; // default OWNERS file name

  // Name of plugin and namespace.
  static final String PLUGIN_NAME = "find-owners";
  static final String PROLOG_NAMESPACE = "find_owners";

  private final PluginConfigFactory configFactory;

  // Each call to API entry point creates one new Config and parses gerrit.config.
  private final PluginConfig gerritConfig;

  // Each Config has a cache of project.config, with projectName:changeId key.
  private final Map<String, PluginConfig> projectConfigMap;

  // Global/plugin config parameters.
  private boolean addDebugMsg = false;
  private int minOwnerVoteLevel = 1;
  private int maxCacheAge = 0;
  private int maxCacheSize = 1000;
  private boolean reportSyntaxError = false;

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  Config(PluginConfigFactory configFactory) {
    // Called by Action() and Checker.findApproval, through Prolog submit filter.
    this(configFactory, null);
  }

  @VisibleForTesting
  Config(PluginConfig rawConfig) {
    this(null, rawConfig);
  }

  Config(PluginConfigFactory configFactory, PluginConfig config) {
    this.configFactory = configFactory;
    this.projectConfigMap = new HashMap<>();
    if (configFactory == null && config == null) { // When called from integration tests.
      this.gerritConfig = config;
      return;
    }
    this.gerritConfig = config == null ? configFactory.getFromGerritConfig(PLUGIN_NAME) : config;
    // Get config variables from the plugin section of gerrit.config.
    addDebugMsg = gerritConfig.getBoolean(ADD_DEBUG_MSG, false);
    minOwnerVoteLevel = gerritConfig.getInt(MIN_OWNER_VOTE_LEVEL, 1);
    maxCacheAge = gerritConfig.getInt(MAX_CACHE_AGE, 0);
    maxCacheSize = gerritConfig.getInt(MAX_CACHE_SIZE, 1000);
    reportSyntaxError = gerritConfig.getBoolean(REPORT_SYNTAX_ERROR, false);
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

  boolean getReportSyntaxError() {
    return reportSyntaxError;
  }

  static String getChangeId(ChangeData data) {
    return data == null ? "(unknown change)" : ("c/" + data.getId().get());
  }

  String getDefaultOwnersFileName() {
    return gerritConfig == null ? OWNERS : gerritConfig.getString(OWNERS_FILE_NAME, OWNERS);
  }

  // This is per ProjectState and ChangeData.
  private PluginConfig getProjectConfig(ProjectState state, ChangeData data) {
    // A new Config object is created for every call to Action or Checker.
    // So it is okay to reuse a PluginConfig per (ProjectState:ChangeData).
    // ProjectState parameter must not be null.
    String key = state.getName() + ":" + getChangeId(data);
    PluginConfig config = projectConfigMap.get(key);
    if (config == null) {
      config = configFactory.getFromProjectConfigWithInheritance(state, PLUGIN_NAME);
      projectConfigMap.put(key, config);
    }
    return config;
  }

  // Used by OwnersValidator and tests, not cached.
  PluginConfig getProjectConfig(Project project) throws NoSuchProjectException {
    return configFactory.getFromProjectConfigWithInheritance(project.getNameKey(), PLUGIN_NAME);
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
        logger.atSevere().log("Project %s has emptry %s", project, OWNERS_FILE_NAME);
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
