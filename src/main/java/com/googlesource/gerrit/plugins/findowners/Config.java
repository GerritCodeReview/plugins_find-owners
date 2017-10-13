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
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.server.config.PluginConfig;
import com.google.gerrit.server.config.PluginConfigFactory;
import com.google.gerrit.server.project.NoSuchProjectException;
import com.google.gerrit.server.query.change.ChangeData;
import com.google.gwtorm.server.OrmException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

  // Global/plugin config parameters.
  private static PluginConfigFactory config = null;
  private static boolean addDebugMsg = false;
  private static int minOwnerVoteLevel = 1;
  private static int maxCacheAge = 0;
  private static int maxCacheSize = 1000;
  private static boolean reportSyntaxError = false;
  private static boolean alwaysShowButton = false;

  private static final Logger log = LoggerFactory.getLogger(Config.class);

  static void setVariables(String pluginName, PluginConfigFactory conf) {
    if (conf == null) { // When called from integration tests.
      return;
    }
    config = conf;
    PluginConfig gc = config.getFromGerritConfig(pluginName, true);
    // Get config variables from the plugin section of gerrit.config
    addDebugMsg = gc.getBoolean(ADD_DEBUG_MSG, false);
    reportSyntaxError = gc.getBoolean(REPORT_SYNTAX_ERROR, false);
    alwaysShowButton = gc.getBoolean(ALWAYS_SHOW_BUTTON, false);
    minOwnerVoteLevel = gc.getInt(MIN_OWNER_VOTE_LEVEL, 1);
    maxCacheAge = gc.getInt(MAX_CACHE_AGE, 0);
    maxCacheSize = gc.getInt(MAX_CACHE_SIZE, 1000);
  }

  static boolean getAddDebugMsg() {
    return addDebugMsg; // defined globally, not per-project
  }

  static int getMaxCacheAge() {
    return maxCacheAge;
  }

  static int getMaxCacheSize() {
    return maxCacheSize;
  }

  static boolean getReportSyntaxError() {
    return reportSyntaxError;
  }

  static boolean getAlwaysShowButton() {
    return alwaysShowButton;
  }

  static String getOwnersFileName(Project.NameKey project, Change.Id id) {
    if (config != null && project != null) {
      try {
        String name =
            config
                .getFromProjectConfigWithInheritance(project, PLUGIN_NAME)
                .getString(OWNERS_FILE_NAME, OWNERS);
        if (name.trim().equals("")) {
          log.error(
              "Project " + project.get() + " has wrong " + OWNERS_FILE_NAME + ": \"" + name + "\"");
          return OWNERS;
        }
        return name;
      } catch (NoSuchProjectException e) {
        log.error(String.format("Cannot find project %s for change %s", project, id.get()), e);
      }
    }
    return OWNERS;
  }

  @VisibleForTesting
  static void setReportSyntaxError(boolean value) {
    reportSyntaxError = value;
  }

  static int getMinOwnerVoteLevel(ChangeData changeData) throws OrmException {
    Project.NameKey project = changeData.change().getProject();
    try {
      return (config == null || project == null)
          ? minOwnerVoteLevel
          : config
              .getFromProjectConfigWithInheritance(project, PLUGIN_NAME)
              .getInt(MIN_OWNER_VOTE_LEVEL, minOwnerVoteLevel);
    } catch (NoSuchProjectException e) {
      log.error("Cannot find project: " + project, e);
      return minOwnerVoteLevel;
    }
  }
}
