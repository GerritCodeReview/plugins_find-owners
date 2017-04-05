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
import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.extensions.restapi.BadRequestException;
import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestReadView;
import com.google.gerrit.extensions.webui.UiAction;
import com.google.gerrit.reviewdb.client.Account;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.Change.Status;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.account.AccountCache;
import com.google.gerrit.server.change.ChangeResource;
import com.google.gerrit.server.change.RevisionResource;
import com.google.gerrit.server.config.PluginConfigFactory;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.query.change.ChangeData;
import com.google.gwtorm.server.OrmException;
import com.google.gwtorm.server.SchemaFactory;
import com.google.inject.Inject;
import com.google.inject.Provider;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.jgit.lib.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Create and return OWNERS info when "Find Owners" button is clicked. */
class Action implements RestReadView<RevisionResource>, UiAction<RevisionResource> {

  private static final Logger log = LoggerFactory.getLogger(Action.class);

  private AccountCache accountCache;
  private ChangeData.Factory changeDataFactory;
  private GitRepositoryManager repoManager;
  private Provider<CurrentUser> userProvider;
  private SchemaFactory<ReviewDb> reviewDbProvider;

  static class Parameters {
    Boolean debug; // REST API "debug" parameter, or null
    Integer patchset; // REST API "patchset" parameter, or null
  }

  @Inject
  Action(
      @PluginName String pluginName,
      PluginConfigFactory configFactory,
      Provider<CurrentUser> userProvider,
      SchemaFactory<ReviewDb> reviewDbProvider,
      ChangeData.Factory changeDataFactory,
      AccountCache accountCache,
      GitRepositoryManager repoManager) {
    this.userProvider = userProvider;
    this.reviewDbProvider = reviewDbProvider;
    this.changeDataFactory = changeDataFactory;
    this.accountCache = accountCache;
    this.repoManager = repoManager;
    Config.setVariables(pluginName, configFactory);
    Cache.getInstance(); // Create a single Cache.
  }

  @VisibleForTesting
  Action() {}

  private String getUserName() {
    if (userProvider != null && userProvider.get().getUserName() != null) {
      return userProvider.get().getUserName();
    }
    return "?";
  }

  private List<String> getOwners(OwnersDb db, Collection<String> files) {
    Map<String, OwnerWeights> weights = new HashMap<>();
    db.findOwners(files, weights);
    List<String> result = new ArrayList<>();
    Set<String> emails = new HashSet<>();
    for (String key : OwnerWeights.sortKeys(weights)) {
      if (!emails.contains(key)) {
        result.add(key + " " + weights.get(key).encodeLevelCounts());
        emails.add(key);
      }
    }
    return result;
  }

  @Override
  public Response<RestResult> apply(RevisionResource rev)
      throws IOException, OrmException, BadRequestException {
    return apply(rev.getChangeResource(), new Parameters());
  }

  // Used by both Action.apply and GetOwners.apply.
  public Response<RestResult> apply(ChangeResource rsrc, Parameters params)
      throws IOException, OrmException, BadRequestException {
    try (ReviewDb reviewDb = reviewDbProvider.open()) {
      return apply(reviewDb, rsrc, params);
    }
  }

  // Used by integration tests, because they do not have ReviewDb Provider.
  public Response<RestResult> apply(ReviewDb reviewDb, ChangeResource rsrc, Parameters params)
      throws IOException, OrmException, BadRequestException {
    Change c = rsrc.getChange();
    try (Repository repo = repoManager.openRepository(c.getProject())) {
      ChangeData changeData = changeDataFactory.create(reviewDb, c);
      return getChangeData(repo, params, changeData);
    }
  }

  /** Returns reviewer emails got from ChangeData. */
  static List<String> getReviewers(ChangeData changeData, AccountCache accountCache) {
    List<String> result = new ArrayList<>();
    try {
      for (Account.Id id : changeData.reviewers().all()) {
        Account account = accountCache.get(id).getAccount();
        result.add(account.getPreferredEmail() + " []");
      }
    } catch (OrmException e) {
      log.error("Exception", e);
      result = new ArrayList<>();
    }
    return result;
  }

  /** Returns the current patchset number or the given patchsetNum if it is valid. */
  static int getValidPatchsetNum(ChangeData changeData, Integer patchsetNum)
      throws OrmException, BadRequestException {
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
  public Response<RestResult> getChangeData(
      Repository repository, Parameters params, ChangeData changeData)
      throws OrmException, BadRequestException {
    int patchset = getValidPatchsetNum(changeData, params.patchset);
    OwnersDb db = Cache.getInstance().get(repository, changeData, patchset);
    Collection<String> changedFiles = changeData.currentFilePaths();
    Map<String, Set<String>> file2Owners = db.findOwners(changedFiles);

    Boolean addDebugMsg = (params.debug != null) ? params.debug : Config.getAddDebugMsg();
    RestResult obj = new RestResult(Config.getMinOwnerVoteLevel(changeData), addDebugMsg);
    obj.change = changeData.getId().get();
    obj.patchset = patchset;
    obj.ownerRevision = db.revision;
    if (addDebugMsg) {
      obj.dbgmsgs.user = getUserName();
      obj.dbgmsgs.project = changeData.change().getProject().get();
      obj.dbgmsgs.branch = changeData.change().getDest().get();
      obj.dbgmsgs.path2owners = Util.makeSortedMap(db.path2Owners);
      obj.dbgmsgs.owner2paths = Util.makeSortedMap(db.owner2Paths);
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
    try (ReviewDb reviewDb = reviewDbProvider.open();
        Repository repo = repoManager.openRepository(change.getProject())) {
      ChangeData changeData = changeDataFactory.create(reviewDb, change);
      if (changeData.change().getDest().get() == null) {
        log.error("Cannot get branch of change: " + changeData.getId().get());
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
      if (needFindOwners && !Config.getAlwaysShowButton()) {
        // Show button only if some owner is found.
        OwnersDb db = Cache.getInstance().get(repo, changeData);
        log.trace("getDescription db key = " + db.key);
        needFindOwners = needFindOwners && (db.getNumOwners() > 0);
      }
      return new Description()
          .setLabel("Find Owners")
          .setTitle("Find owners to add to Reviewers list")
          .setVisible(needFindOwners);
    } catch (IOException | OrmException e) {
      log.error("Exception", e);
      throw new IllegalStateException(e);
    }
  }
}