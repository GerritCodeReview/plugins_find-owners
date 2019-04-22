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

import static java.util.stream.Collectors.toList;

import com.google.common.collect.Streams;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.exceptions.StorageException;
import com.google.gerrit.extensions.restapi.BadRequestException;
import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestReadView;
import com.google.gerrit.extensions.webui.UiAction;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.Change.Status;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.account.AccountCache;
import com.google.gerrit.server.account.Emails;
import com.google.gerrit.server.change.ChangeResource;
import com.google.gerrit.server.change.RevisionResource;
import com.google.gerrit.server.config.PluginConfigFactory;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.project.ProjectCache;
import com.google.gerrit.server.project.ProjectState;
import com.google.gerrit.server.query.change.ChangeData;
import com.google.inject.Inject;
import com.google.inject.Provider;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Create and return OWNERS info when "Find Owners" button is clicked. */
class Action implements RestReadView<RevisionResource>, UiAction<RevisionResource> {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final AccountCache accountCache;
  private final Emails emails;
  private final ChangeData.Factory changeDataFactory;
  private final GitRepositoryManager repoManager;
  private final PluginConfigFactory configFactory;
  private final Provider<CurrentUser> userProvider;
  private final ProjectCache projectCache;
  private final Config config;

  static class Parameters {
    Boolean debug; // REST API "debug" parameter, or null
    Boolean nocache; // REST API "nocache" parameter, or null
    Integer patchset; // REST API "patchset" parameter, or null
  }

  @Inject
  Action(
      PluginConfigFactory configFactory,
      Provider<CurrentUser> userProvider,
      ChangeData.Factory changeDataFactory,
      AccountCache accountCache,
      Emails emails,
      GitRepositoryManager repoManager,
      ProjectCache projectCache) {
    this.userProvider = userProvider;
    this.changeDataFactory = changeDataFactory;
    this.accountCache = accountCache;
    this.emails = emails;
    this.repoManager = repoManager;
    this.projectCache = projectCache;
    this.configFactory = configFactory;
    this.config = new Config(configFactory);
  }

  private String getUserName() {
    if (userProvider != null && userProvider.get().getUserName().isPresent()) {
      return userProvider.get().getUserName().get();
    }
    return "?";
  }

  private List<OwnerInfo> getOwners(OwnersDb db, Collection<String> files) {
    Map<String, OwnerWeights> weights = new HashMap<>();
    db.findOwners(files, weights, new ArrayList<>());
    List<OwnerInfo> result = new ArrayList<>();
    Set<String> emails = new HashSet<>();
    for (String key : OwnerWeights.sortKeys(weights)) {
      if (!emails.contains(key)) {
        result.add(new OwnerInfo(key, weights.get(key).getLevelCounts()));
        emails.add(key);
      }
    }
    return result;
  }

  @Override
  public Response<RestResult> apply(RevisionResource rev)
      throws IOException, StorageException, BadRequestException {
    return apply(rev.getChangeResource(), new Parameters());
  }

  // Used by integration tests, because they do not have ReviewDb Provider.
  public Response<RestResult> apply(ChangeResource rsrc, Parameters params)
      throws StorageException, BadRequestException {
    ChangeData changeData = changeDataFactory.create(rsrc.getChange());
    return getChangeData(params, changeData);
  }

  /** Returns reviewer emails got from ChangeData. */
  static List<String> getReviewers(ChangeData changeData, AccountCache accountCache) {
    try {
      // Reviewers may have no preferred email, skip them if the preferred email is not set.
      return changeData.reviewers().all().stream()
          .map(accountCache::get)
          .flatMap(Streams::stream)
          .map(a -> a.getAccount().getPreferredEmail())
          .filter(Objects::nonNull)
          .collect(toList());
    } catch (StorageException e) {
      logger.atSevere().withCause(e).log("Exception for %s", Config.getChangeId(changeData));
      return new ArrayList<>();
    }
  }

  /** Returns the current patchset number or the given patchsetNum if it is valid. */
  static int getValidPatchsetNum(ChangeData changeData, Integer patchsetNum)
      throws StorageException, BadRequestException {
    int patchset = changeData.currentPatchSet().getId().get();
    if (patchsetNum != null) {
      if (patchsetNum < 1 || patchsetNum > patchset) {
        throw new BadRequestException(
            "Invalid patchset parameter: "
                + patchsetNum
                + "; must be 1"
                + ((1 != patchset) ? (" to " + patchset) : ""));
      }
      return patchsetNum;
    }
    return patchset;
  }

  /** REST API to return owners info of a change. */
  public Response<RestResult> getChangeData(Parameters params, ChangeData changeData)
      throws StorageException, BadRequestException {
    int patchset = getValidPatchsetNum(changeData, params.patchset);
    ProjectState projectState = projectCache.get(changeData.project());
    Boolean useCache = params.nocache == null || !params.nocache;
    OwnersDb db =
        Cache.getInstance(configFactory, repoManager)
            .get(
                useCache,
                projectState,
                accountCache,
                emails,
                repoManager,
                configFactory,
                changeData,
                patchset);
    Collection<String> changedFiles = changeData.currentFilePaths();
    Map<String, Set<String>> file2Owners = db.findOwners(changedFiles);

    Boolean addDebugMsg = (params.debug != null) ? params.debug : config.getAddDebugMsg();
    RestResult obj =
        new RestResult(config.getMinOwnerVoteLevel(projectState, changeData), addDebugMsg);
    obj.change = changeData.getId().get();
    obj.patchset = patchset;
    obj.ownerRevision = db.revision;
    if (addDebugMsg) {
      obj.dbgmsgs.user = getUserName();
      obj.dbgmsgs.project = changeData.change().getProject().get();
      obj.dbgmsgs.branch = changeData.change().getDest().get();
      obj.dbgmsgs.errors = db.errors;
      obj.dbgmsgs.path2owners = Util.makeSortedMap(db.path2Owners);
      obj.dbgmsgs.owner2paths = Util.makeSortedMap(db.owner2Paths);
      obj.dbgmsgs.logs = db.logs;
    }

    obj.file2owners = Util.makeSortedMap(file2Owners);
    obj.reviewers = getReviewers(changeData, accountCache);
    obj.owners = getOwners(db, changedFiles);
    obj.files = new ArrayList<>(changedFiles);
    return Response.ok(obj);
  }

  @Override
  public Description getDescription(RevisionResource resource) {
    Change change = resource.getChangeResource().getChange();
    ChangeData changeData = null;
    try {
      changeData = changeDataFactory.create(change);
      if (changeData.change().getDest().get() == null) {
        if (!Checker.isExemptFromOwnerApproval(changeData)) {
          logger.atSevere().log("Cannot get branch of change: %d", changeData.getId().get());
        }
        return null; // no "Find Owners" button
      }
      Status status = resource.getChange().getStatus();
      // Commit message is not used to enable/disable "Find Owners".
      boolean needFindOwners =
          userProvider != null
              && userProvider.get() instanceof IdentifiedUser
              && status != Status.ABANDONED
              && status != Status.MERGED;
      // If alwaysShowButton is true, skip expensive owner lookup.
      if (needFindOwners && !config.getAlwaysShowButton()) {
        needFindOwners = false; // Show button only if some owner is found.
        OwnersDb db =
            Cache.getInstance(configFactory, repoManager)
                .get(
                    true, // use cached OwnersDb
                    projectCache.get(resource.getProject()),
                    accountCache,
                    emails,
                    repoManager,
                    configFactory,
                    changeData);
        logger.atFiner().log("getDescription db key = %s", db.key);
        needFindOwners = db.getNumOwners() > 0;
      }
      return new Description()
          .setLabel("Find Owners")
          .setTitle("Find owners to add to Reviewers list")
          .setVisible(needFindOwners);
    } catch (StorageException e) {
      logger.atSevere().withCause(e).log("Exception for %s", Config.getChangeId(changeData));
      throw new IllegalStateException(e);
    }
  }
}
