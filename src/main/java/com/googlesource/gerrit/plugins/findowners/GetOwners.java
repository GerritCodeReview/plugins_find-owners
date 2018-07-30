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

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.extensions.restapi.BadRequestException;
import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestReadView;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.account.AccountCache;
import com.google.gerrit.server.account.Emails;
import com.google.gerrit.server.change.ChangeResource;
import com.google.gerrit.server.config.PluginConfigFactory;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.project.ProjectCache;
import com.google.gerrit.server.query.change.ChangeData;
import com.google.gwtorm.server.OrmException;
import com.google.gwtorm.server.SchemaFactory;
import com.google.inject.Inject;
import com.google.inject.Provider;
import java.io.IOException;
import org.kohsuke.args4j.Option;

/** REST API to get owners of a change. */
public class GetOwners implements RestReadView<ChangeResource> {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final Action action;

  // "debug" could be true/yes/1 or false/no/0,
  // when not specified configuration variable "addDebugMsg" is used.
  @Option(name = "--debug", usage = "get extra debug info")
  private String debug;

  @Option(name = "--patchset", usage = "select change patchset number")
  private Integer patchset;

  @Inject
  GetOwners(
      @PluginName String pluginName,
      PluginConfigFactory configFactory,
      Provider<CurrentUser> userProvider,
      SchemaFactory<ReviewDb> reviewDbProvider,
      ChangeData.Factory dataFactory,
      AccountCache accountCache,
      Emails emails,
      GitRepositoryManager repoManager,
      ProjectCache projectCache) {
    this.action =
        new Action(
            pluginName,
            configFactory,
            userProvider,
            reviewDbProvider,
            dataFactory,
            accountCache,
            emails,
            repoManager,
            projectCache);
  }

  @Override
  public Response<RestResult> apply(ChangeResource rsrc) throws IOException, OrmException {
    Action.Parameters params = new Action.Parameters();
    params.patchset = patchset;
    params.debug = (debug != null) ? Util.parseBoolean(debug) : null;
    try {
      return this.action.apply(rsrc, params);
    } catch (BadRequestException e) {
      // Catch this exception to avoid too many call stack dumps
      // from bad wrong client requests.
      logger.atSevere().log("Exception: " + e);
      return Response.none();
    }
  }
}
