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
import com.google.gerrit.entities.Change.Status;
import com.google.gerrit.entities.PatchSetApproval;
import com.google.gerrit.exceptions.StorageException;
import com.google.gerrit.server.account.AccountCache;
import com.google.gerrit.server.account.Emails;
import com.google.gerrit.server.config.PluginConfigFactory;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.project.ProjectState;
import com.google.gerrit.server.query.change.ChangeData;
import com.google.gerrit.server.rules.StoredValues;
import com.googlecode.prolog_cafe.lang.Prolog;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Check if a change needs owner approval. */
public class Checker {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  // Accept both "Exempt-" and "Exempted-".
  private static final String EXEMPT_MESSAGE1 = "Exempt-From-Owner-Approval:";
  private static final String EXEMPT_MESSAGE2 = "Exempted-From-Owner-Approval:";

  private final AccountCache accountCache;
  private final GitRepositoryManager repoManager;
  private final Emails emails;
  private final Config config;
  private final ProjectState projectState; // could be null when used by FindOwnersIT
  private final ChangeData changeData;
  private int minVoteLevel;

  Checker(
      AccountCache accountCache,
      GitRepositoryManager repoManager,
      Emails emails,
      PluginConfigFactory configFactory,
      ProjectState projectState,
      ChangeData changeData,
      int v) {
    this.accountCache = accountCache;
    this.repoManager = repoManager;
    this.emails = emails;
    this.projectState = projectState;
    this.changeData = changeData;
    this.config = new Config(configFactory, null);
    minVoteLevel = v;
  }

  /** Returns a map from reviewer email to vote value. */
  Map<String, Integer> getVotes(ChangeData changeData) {
    Map<String, Integer> map = new HashMap<>();
    for (PatchSetApproval p : changeData.currentApprovals()) {
      // Only collect non-zero Code-Review votes.
      if (p.value() != 0 && p.label().equals("Code-Review")) {
        // Reviewers may have no preferred email, skip them if the preferred email is not set.
        Optional<String> preferredEmail =
            accountCache.get(p.accountId()).map(a -> a.account().preferredEmail());
        if (preferredEmail.isPresent()) {
          map.put(preferredEmail.get(), Integer.valueOf(p.value()));
        }
      }
    }
    // Give CL author a default minVoteLevel vote.
    // The preferred email of the author may not be set. Pushing changes only requires an email in
    // the external IDs, but the preferred email may still be null (also emails may have been
    // deleted after creating the change). Skip the author if it doesn't have a preferred email.
    Optional<String> author =
        accountCache.get(changeData.change().getOwner()).map(a -> a.account().preferredEmail());
    if (author.isPresent() && (!map.containsKey(author.get()) || map.get(author.get()) == 0)) {
      map.put(author.get(), minVoteLevel);
    }
    return map;
  }

  /** Returns true if some owner in owners is "*" or in votes */
  boolean findOwnersInVotes(Set<String> owners, Map<String, Integer> votes) {
    boolean foundVeto = false;
    boolean foundApproval = false;
    boolean foundNull = false;
    for (String owner : owners) {
      if (owner == null) {
        foundNull = true; // Something is wrong in OwnersDb!
      } else if (votes.containsKey(owner)) {
        int v = votes.get(owner);
        foundApproval |= (v >= minVoteLevel);
        foundVeto |= (v < 0); // an owner's -1 vote is a veto
      } else if (owner.equals("*")) {
        foundApproval = true; // no specific owner
      }
    }
    if (foundNull) {
      logger.atSevere().log("Unexpected null owner email for %s", Config.getChangeId(changeData));
    }
    return foundApproval && !foundVeto;
  }

  /** Returns 1 if owner approval is found, -1 if missing, 0 if unneeded. */
  int findApproval(OwnersDb db) {
    Map<String, Set<String>> file2Owners = db.findOwners(changeData.currentFilePaths());
    if (file2Owners.isEmpty()) { // do not need owner approval
      return 0;
    }
    Map<String, Integer> votes = getVotes(changeData);
    for (Set<String> owners : file2Owners.values()) {
      if (!findOwnersInVotes(owners, votes)) {
        return -1;
      }
    }
    return 1;
  }

  /** Returns 1 if owner approval is found, -1 if missing, 0 if unneeded. */
  public static int findApproval(Prolog engine, int minVoteLevel) {
    ChangeData changeData = null;
    try {
      changeData = StoredValues.CHANGE_DATA.get(engine);
      Checker checker =
          new Checker(
              StoredValues.ACCOUNT_CACHE.get(engine),
              StoredValues.REPO_MANAGER.get(engine),
              StoredValues.EMAILS.get(engine),
              StoredValues.PLUGIN_CONFIG_FACTORY.get(engine),
              StoredValues.PROJECT_STATE.get(engine),
              changeData,
              minVoteLevel);
      return checker.findApproval();
    } catch (StorageException e) {
      logger.atSevere().withCause(e).log("Exception for %s ", Config.getChangeId(changeData));
      return 0; // owner approval may or may not be required.
    }
  }

  /** Returns 1 if owner approval is found, -1 if missing, 0 if unneeded. */
  int findApproval() {
    if (isExemptFromOwnerApproval(changeData)) {
      return 0;
    }
    // One update to a Gerrit change can call submit_rule or submit_filter
    // many times. So this function should use cached values.
    OwnersDb db =
        Cache.getInstance(config, repoManager)
            .get(
                true,
                null, /* allow submit checker to read all OWNERS files */
                projectState,
                accountCache,
                emails,
                repoManager,
                changeData);
    if (db.getNumOwners() <= 0) {
      return 0;
    }
    if (minVoteLevel <= 0) {
      minVoteLevel = config.getMinOwnerVoteLevel(projectState, changeData);
    }
    logger.atFiner().log("findApproval db key = %s", db.key);
    return findApproval(db);
  }

  /** Returns true if exempt from owner approval. */
  static boolean isExemptFromOwnerApproval(ChangeData changeData) {
    try {
      String message = changeData.commitMessage();
      if (message.contains(EXEMPT_MESSAGE1) || message.contains(EXEMPT_MESSAGE2)) {
        return true;
      }
    } catch (StorageException e) {
      logger.atSevere().withCause(e).log(
          "Cannot get commit message for %s", Config.getChangeId(changeData));
      return true; // exempt from owner approval due to lack of data
    }
    // Abandoned and merged changes do not need approval again.
    Status status = changeData.change().getStatus();
    return (status == Status.ABANDONED || status == Status.MERGED);
  }
}
