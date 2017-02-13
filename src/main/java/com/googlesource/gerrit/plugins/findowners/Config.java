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

import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.server.config.PluginConfigFactory;
import com.google.gerrit.server.project.NoSuchProjectException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** find-owners configuration parameters */
class Config {
  // Name of config parameters and plugin.
  static final String ADD_DEBUG_MSG = "addDebugMsg";
  static final String MIN_OWNER_VOTE_LEVEL = "minOwnerVoteLevel";
  static final String REPORT_SYNTAX_ERROR = "reportSyntaxError";
  static final String PLUGIN_NAME = "find-owners";
  static final String PROLOG_NAMESPACE = "find_owners";

  // Enable TRACE_SERVER_MSG only for dev/test builds.
  private static final boolean TRACE_SERVER_MSG = false;

  // Global/plugin config parameters.
  private static PluginConfigFactory config = null;
  private static boolean addDebugMsg = false;
  private static int minOwnerVoteLevel = 1;
  private static boolean reportSyntaxError = false;

  private static final Logger log = LoggerFactory.getLogger(Config.class);

  static void setVariables(PluginConfigFactory conf,
      boolean dbgMsg, int voteLevel, boolean reportError) {
    if (TRACE_SERVER_MSG) {
      log.info("Set config parameters "
               + ADD_DEBUG_MSG + "=" + dbgMsg
               + ", " + MIN_OWNER_VOTE_LEVEL + "=" + voteLevel
               + ", " + REPORT_SYNTAX_ERROR + "=" + reportError);
    }
    config = conf;
    addDebugMsg = dbgMsg;
    minOwnerVoteLevel = voteLevel;
    reportSyntaxError = reportError;
  }

  static boolean traceServerMsg() {
    return TRACE_SERVER_MSG && addDebugMsg;
  }

  static boolean getAddDebugMsg() {
    return addDebugMsg; // defined globally, not per-project
  }

  static boolean getReportSyntaxError() {
    return reportSyntaxError;
  }

  static int getMinOwnerVoteLevel(Project.NameKey project) {
    try {
      return (null == config || null == project) ? minOwnerVoteLevel
          : config.getFromProjectConfigWithInheritance(
              project, PLUGIN_NAME).getInt(MIN_OWNER_VOTE_LEVEL,
                                           minOwnerVoteLevel);
    } catch (NoSuchProjectException e) {
      log.error("Cannot find project: " + project);
      return minOwnerVoteLevel;
    }
  }
}
