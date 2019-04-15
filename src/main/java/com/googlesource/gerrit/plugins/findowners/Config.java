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
import com.google.gerrit.server.config.PluginConfig;
import com.google.gerrit.server.config.PluginConfigFactory;
import com.google.gerrit.server.project.ProjectState;
import com.google.gerrit.server.query.change.ChangeData;

/** find-owners configuration parameters */
class Config {
  // Name of config parameters.
  static final String ADD_DEBUG_MSG = "addDebugMsg";
  static final String ALWAYS_SHOW_BUTTON = "alwaysShowButton"; // always show "Find Owners" button
  static final String MAX_CACHE_AGE = "maxCacheAge"; // seconds to stay in cache
  static final String MAX_CACHE_SIZE = "maxCacheSize"; // number of OwnersDb in cache
  static final String MIN_OWNER_VOTE_LEVEL = "minOwnerVoteLevel"; // default +1
  static final String OWNERS = "OWNERS"; // Default file name
  static final String OWNERS_FILE_NAME = "ownersFileName"; // config key for file name
  static final String REJECT_ERROR_IN_OWNERS = "rejectErrorInOwners"; // config key for validator
  static final String REPORT_SYNTAX_ERROR = "reportSyntaxError";

  // Name of plugin and namespace.
  static final String PLUGIN_NAME = "find-owners";
  static final String PROLOG_NAMESPACE = "find_owners";

  private final PluginConfigFactory configFactory;

  // Global/plugin config parameters.
  private boolean addDebugMsg = false;
  private int minOwnerVoteLevel = 1;
  private int maxCacheAge = 0;
  private int maxCacheSize = 1000;
  private boolean reportSyntaxError = false;
  private boolean alwaysShowButton = false;
  private String ownersFileName = OWNERS;

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  Config(PluginConfigFactory configFactory) {
    this.configFactory = configFactory;
    if (configFactory == null) { // When called from integration tests.
      return;
    }
    PluginConfig gc = configFactory.getFromGerritConfig(PLUGIN_NAME);
    // Get config variables from the plugin section of gerrit.config
    addDebugMsg = gc.getBoolean(ADD_DEBUG_MSG, false);
    reportSyntaxError = gc.getBoolean(REPORT_SYNTAX_ERROR, false);
    alwaysShowButton = gc.getBoolean(ALWAYS_SHOW_BUTTON, false);
    minOwnerVoteLevel = gc.getInt(MIN_OWNER_VOTE_LEVEL, 1);
    maxCacheAge = gc.getInt(MAX_CACHE_AGE, 0);
    maxCacheSize = gc.getInt(MAX_CACHE_SIZE, 1000);
    ownersFileName = gc.getString(OWNERS_FILE_NAME, OWNERS);
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

  boolean getReportSyntaxError() {
    return reportSyntaxError;
  }

  boolean getAlwaysShowButton() {
    return alwaysShowButton;
  }

  static String getChangeId(ChangeData data) {
    return data == null ? "(unknown change)" : ("change c/" + data.getId().get());
  }

  String getDefaultOwnersFileName() {
    return ownersFileName; // could be defined in gerrit.config
  }

  private PluginConfig getPluginConfig(ProjectState state) {
    return configFactory.getFromProjectConfigWithInheritance(state, PLUGIN_NAME);
  }

  String getOwnersFileName(ProjectState projectState, ChangeData c) {
    String defaultName = getDefaultOwnersFileName();
    if (projectState == null) {
      logger.atSevere().log("Null projectState for change %s", getChangeId(c));
      return defaultName;
    }
    String name = getPluginConfig(projectState).getString(OWNERS_FILE_NAME, defaultName);
    if (name.trim().isEmpty()) {
      logger.atSevere().log(
          "Project %s has wrong %s: \"%s\" for %s",
          projectState.getProject(), OWNERS_FILE_NAME, name, getChangeId(c));
      return defaultName;
    }
    return name;
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
    return getPluginConfig(projectState).getInt(MIN_OWNER_VOTE_LEVEL, minOwnerVoteLevel);
  }
}
