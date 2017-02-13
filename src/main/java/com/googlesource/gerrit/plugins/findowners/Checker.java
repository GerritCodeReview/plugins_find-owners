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

import com.googlecode.prolog_cafe.lang.Prolog;
import com.googlesource.gerrit.plugins.findowners.Util.String2Integer;
import com.googlesource.gerrit.plugins.findowners.Util.String2StringSet;
import com.googlesource.gerrit.plugins.findowners.Util.StringSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Check if a change needs owner approval. */
public class Checker {
  private static final Logger log = LoggerFactory.getLogger(Checker.class);

  private Server server;
  private int minVoteLevel;

  Checker(Server s, int v) {
    minVoteLevel = v;
    server = s; // could be a mocked server
  }

  /** Returns true if some owner in owners is "*" or in votes */
  boolean findOwnersInVotes(StringSet owners, String2Integer votes) {
    boolean foundVeto = false;
    boolean foundApproval = false;
    for (String owner : owners) {
      if (votes.containsKey(owner)) {
        int v = votes.get(owner);
        // TODO: Maybe add a configurable feature in the next version
        // to exclude the committer's vote from the "foundApproval".
        foundApproval |= (v >= minVoteLevel);
        foundVeto |= (v < 0); // an owner's -1 vote is a veto
      } else if (owner.equals("*")) {
        foundApproval = true;  // no specific owner
      }
    }
    return foundApproval && !foundVeto;
  }

  /** Returns 1 if owner approval is found, -1 if missing, 0 if unneeded. */
  int findApproval(OwnersDb db) {
    String2StringSet file2Owners = db.findOwners(server.getChangedFiles());
    if (file2Owners.size() == 0) {  // do not need owner approval
      return 0;
    }
    String2Integer votes = server.getVotes();
    for (StringSet owners : file2Owners.values()) {
      if (!findOwnersInVotes(owners, votes)) {
        return -1;
      }
    }
    return 1;
  }

  /** Returns 1 if owner approval is found, -1 if missing, 0 if unneeded. */
  public static int findApproval(Prolog engine, int minVoteLevel) {
    return new Checker(new Server(engine), minVoteLevel).findApproval();
  }

  int findApproval() {
    if (server.isExemptFromOwnerApproval()) {
      return 0;
    }
    // One update to a Gerrit change can call submit_rule or submit_filter
    // many times. So this function should use cached values.
    OwnersDb db = server.getCachedOwnersDb();
    if (db.getNumOwners() <= 0) {
      return 0;
    }
    if (minVoteLevel <= 0) {
      minVoteLevel = server.getMinOwnerVoteLevel();
    }
    if (server.traceServerMsg()) {
      log.info(server.genDebugMsg(db));
    }
    return findApproval(db);
  }
}
