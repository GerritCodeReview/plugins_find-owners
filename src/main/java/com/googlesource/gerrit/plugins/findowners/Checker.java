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

import com.google.gerrit.reviewdb.client.Change.Status;
import com.google.gerrit.reviewdb.client.PatchSetApproval;
import com.google.gerrit.rules.StoredValues;
import com.google.gerrit.server.account.AccountCache;
import com.google.gerrit.server.account.Accounts;
import com.google.gerrit.server.query.change.ChangeData;
import com.google.gwtorm.server.OrmException;
import com.googlecode.prolog_cafe.lang.Prolog;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.eclipse.jgit.lib.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Check if a change needs owner approval. */
public class Checker {
  private static final Logger log = LoggerFactory.getLogger(Checker.class);

  // Accept both "Exempt-" and "Exempted-".
  private static final String EXEMPT_MESSAGE1 = "Exempt-From-Owner-Approval:";
  private static final String EXEMPT_MESSAGE2 = "Exempted-From-Owner-Approval:";

  private Repository repository;
  private ChangeData changeData;
  private int minVoteLevel;

  Checker(Repository repository, ChangeData changeData, int v) {
    this.repository = repository;
    this.changeData = changeData;
    minVoteLevel = v;
  }

  /** Returns a map from reviewer email to vote value. */
  Map<String, Integer> getVotes(AccountCache accountCache, ChangeData changeData)
      throws OrmException {
    Map<String, Integer> map = new HashMap<>();
    for (PatchSetApproval p : changeData.currentApprovals()) {
      if (p.getValue() != 0) {
        map.put(
            accountCache.get(p.getAccountId()).getAccount().getPreferredEmail(),
            Integer.valueOf(p.getValue()));
      }
    }
    // Give CL author a default minVoteLevel vote.
    String author =
        accountCache.get(changeData.change().getOwner()).getAccount().getPreferredEmail();
    if (!map.containsKey(author) || map.get(author) == 0) {
      map.put(author, minVoteLevel);
    }
    return map;
  }

  /** Returns true if some owner in owners is "*" or in votes */
  boolean findOwnersInVotes(Set<String> owners, Map<String, Integer> votes) {
    boolean foundVeto = false;
    boolean foundApproval = false;
    for (String owner : owners) {
      if (votes.containsKey(owner)) {
        int v = votes.get(owner);
        foundApproval |= (v >= minVoteLevel);
        foundVeto |= (v < 0); // an owner's -1 vote is a veto
      } else if (owner.equals("*")) {
        foundApproval = true; // no specific owner
      }
    }
    return foundApproval && !foundVeto;
  }

  /** Returns 1 if owner approval is found, -1 if missing, 0 if unneeded. */
  int findApproval(AccountCache accountCache, OwnersDb db) throws OrmException {
    Map<String, Set<String>> file2Owners = db.findOwners(changeData.currentFilePaths());
    if (file2Owners.size() == 0) { // do not need owner approval
      return 0;
    }
    Map<String, Integer> votes = getVotes(accountCache, changeData);
    for (Set<String> owners : file2Owners.values()) {
      if (!findOwnersInVotes(owners, votes)) {
        return -1;
      }
    }
    return 1;
  }

  /** Returns 1 if owner approval is found, -1 if missing, 0 if unneeded. */
  public static int findApproval(Prolog engine, int minVoteLevel) {
    try {
      AccountCache accountCache = StoredValues.ACCOUNT_CACHE.get(engine);
      Accounts accounts = StoredValues.ACCOUNTS.get(engine);
      ChangeData changeData = StoredValues.CHANGE_DATA.get(engine);
      Repository repository = StoredValues.REPOSITORY.get(engine);
      return new Checker(repository, changeData, minVoteLevel).findApproval(accountCache, accounts);
    } catch (OrmException e) {
      log.error("Exception", e);
      return 0; // owner approval may or may not be required.
    }
  }

  /** Returns true if exempt from owner approval. */
  static boolean isExemptFromOwnerApproval(ChangeData changeData) throws OrmException {
    try {
      String message = changeData.commitMessage();
      if (message.contains(EXEMPT_MESSAGE1) || message.contains(EXEMPT_MESSAGE2)) {
        return true;
      }
    } catch (IOException | OrmException e) {
      log.error("Cannot get commit message", e);
      return true; // exempt from owner approval due to lack of data
    }
    // Abandoned and merged changes do not need approval again.
    Status status = changeData.change().getStatus();
    return (status == Status.ABANDONED || status == Status.MERGED);
  }

  int findApproval(AccountCache accountCache, Accounts accounts) throws OrmException {
    if (isExemptFromOwnerApproval(changeData)) {
      return 0;
    }
    // One update to a Gerrit change can call submit_rule or submit_filter
    // many times. So this function should use cached values.
    OwnersDb db = Cache.getInstance().get(accountCache, accounts, repository, changeData);
    if (db.getNumOwners() <= 0) {
      return 0;
    }
    if (minVoteLevel <= 0) {
      minVoteLevel = Config.getMinOwnerVoteLevel(changeData);
    }
    log.trace("findApproval db key = " + db.key);
    return findApproval(accountCache, db);
  }
}
