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

import com.google.gerrit.reviewdb.client.Account;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.Change.Status;
import com.google.gerrit.reviewdb.client.PatchSetApproval;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.reviewdb.server.AccountAccess;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.rules.StoredValues;
import com.google.gerrit.server.change.RevisionResource;
import com.google.gerrit.server.query.change.ChangeData;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gwtorm.server.OrmException;
import com.googlecode.prolog_cafe.lang.Prolog;
import com.googlesource.gerrit.plugins.findowners.Util.String2Integer;
import com.googlesource.gerrit.plugins.findowners.Util.StringSet;
import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Wrapper of Gerrit server functions. */
class Server {
  // Usage: construct one instance and init for each change id.
  private static final Logger log = LoggerFactory.getLogger(Server.class);

  // Accept both "Exempt-" and "Exempted-".
  private static final String EXEMPT_MESSAGE1 = "Exempt-From-Owner-Approval:";
  private static final String EXEMPT_MESSAGE2 = "Exempted-From-Owner-Approval:";

  private Prolog engine; // Gerrit Prolog engine
  String url; // Gerrit server URL, could be changed in mocked server

  int change;
  int patchset; // patchset number
  String project;
  String branch;
  String error; // default init to null
  Collection<PatchSetApproval> approvals; // only used with Prolog engine

  Server() {}

  Server(Prolog p) {
    engine = p;
    ChangeData data = StoredValues.CHANGE_DATA.get(engine);
    change = data.getId().get();
    try {
      patchset = data.currentPatchSet().getId().get();
    } catch (OrmException e) {
      log.error("Cannot get patchset: " + e);
      patchset = 1;
    }
    Change c = StoredValues.getChange(engine);
    project = c.getProject().get();
    // NOTE: repository.getBranch() returns incorrect "master"
    branch = c.getDest().get(); // e.g. "refs/heads/BetaBranch"
    try {
      approvals = data.currentApprovals();
    } catch (OrmException e) {
      log.error("Cannot get approvals: " + e);
      approvals = new ArrayList<PatchSetApproval>();
    }
  }

  int getMinOwnerVoteLevel() {
    return Config.getMinOwnerVoteLevel(new Project.NameKey(project));
  }

  boolean getAddDebugMsg() {
    return Config.getAddDebugMsg();
  }

  boolean traceServerMsg() {
    return Config.traceServerMsg();
  }

  boolean getReportSyntaxError() {
    return Config.getReportSyntaxError();
  }

  /** Returns a revision's change review status. */
  Status getStatus(RevisionResource resource) {
    return resource.getChange().getStatus();
  }

  /** Sets change number; retrieves other parameters from REST API. */
  void setChangeId(String url, int change) {
    this.url = url;
    this.change = change;
    String request = url + "changes/?q=" + change + "&o=CURRENT_REVISION";
    JsonArray arr = Util.getHTTPJsonArray(request);
    if (null == arr || arr.size() != 1) {
      error = "Failed request: " + request;
      return;
    }
    JsonObject obj = arr.get(0).getAsJsonObject();
    project = obj.get("project").getAsString();
    branch = obj.get("branch").getAsString();
    String revisionString = obj.get("current_revision").getAsString();
    JsonObject revisions = obj.get("revisions").getAsJsonObject();
    JsonObject revInfo = revisions.get(revisionString).getAsJsonObject();
    patchset = revInfo.get("_number").getAsInt();
  }

  /** Returns error message if patchsetNum has invalid value. */
  String setPatchId(String patchsetNum) {
    if (null != patchsetNum) {
      int n = Integer.parseInt(patchsetNum);
      if (n < 1 || n > patchset) {
        return "Invalid patchset parameter: " + patchsetNum + "; must be 1"
            + ((1 != patchset) ? (" to " + patchset) : "");
      }
      patchset = n;
    }
    return null;
  }

  /** Returns a map from reviewer email to vote value; uses Prolog engine. */
  String2Integer getVotes() {
    ChangeData data = StoredValues.CHANGE_DATA.get(engine);
    ReviewDb db = StoredValues.REVIEW_DB.get(engine);
    String2Integer map = new String2Integer();
    AccountAccess ac = db.accounts();
    for (PatchSetApproval p : approvals) {
      if (p.getValue() != 0) {
        int id = p.getAccountId().get();
        try {
          Account a = ac.get(new Account.Id(id));
          String email = a.getPreferredEmail();
          map.put(email, new Integer(p.getValue()));
        } catch (OrmException e) {
          log.error("Cannot get email address of account id: " + id + " " + e);
        }
      }
    }
    return map;
  }

  /** Returns changed files, uses Prolog engine or url REST API. */
  Collection<String> getChangedFiles() {
    if (null != engine) { // Get changed files faster from StoredValues.
      try {
        return StoredValues.CHANGE_DATA.get(engine).currentFilePaths();
      } catch (OrmException e) {
        log.error("OrmException in getChangedFiles: " + e);
        return new StringSet();
      }
    }
    String request =
        url + "changes/" + change + "/revisions/" + patchset + "/files";
    JsonObject map = Util.getHTTPJsonObject(request, false);
    StringSet result = new StringSet();
    for (Map.Entry<String, JsonElement> entry : map.entrySet()) {
      String key = entry.getKey();
      if (!key.equals("/COMMIT_MSG")) { // ignore commit message
        result.add(key);
        // If a file was moved then we need approvals for old directory.
        JsonObject attr = entry.getValue().getAsJsonObject();
        if (null != attr && null != attr.get("old_path")) {
          result.add(attr.get("old_path").getAsString());
        }
      }
    }
    return result;
  }

  /** Returns reviewer emails got from url REST API. */
  JsonArray getReviewers() {
    String request = url + "changes/" + change + "/reviewers";
     JsonArray reviewers = Util.getHTTPJsonArray(request);
     JsonArray result = new JsonArray();
     int numReviewers = reviewers.size();
     for (int i = 0; i < numReviewers; i++) {
       JsonObject map = reviewers.get(i).getAsJsonObject();
       result.add(map.get("email").getAsString() + " []");
     }
     return result;
  }

  /** Returns file content or empty string; uses Repository. */
  String getRepositoryFile(Repository repo, String branch, String file) {
    try (RevWalk revWalk = new RevWalk(repo)) {
      RevTree tree = revWalk.parseCommit(repo.resolve(branch)).getTree();
      try (TreeWalk treeWalk = new TreeWalk(repo)) {
        try (ObjectReader reader = repo.newObjectReader()) {
          treeWalk.addTree(tree);
          treeWalk.setRecursive(true);
          treeWalk.setFilter(PathFilter.create(file));
          if (treeWalk.next()) {
            return new String(reader.open(treeWalk.getObjectId(0)).getBytes());
          }
        }
      }
    } catch (IOException e) {
      log.error("get file " + file + ": " + e);
    }
    return "";
  }

  /** Returns OWNERS file content; uses Repository or url REST API. */
  String getOWNERS(String dir, Repository repository, String url,
                   String project, String branch) {
    // e.g. dir = ./d1/d2
    String filePath = (dir + "/OWNERS").substring(2);  // remove "./"
    if (null != repository) {
      return getRepositoryFile(repository, branch, filePath);
    } else {
      String requestUrl = url + "projects/" + project
          + "/branches/" + branch + "/files/"
          + URLEncoder.encode(filePath) + "/content";
      return Util.getHTTPBase64Content(requestUrl);
    }
  }

  /** Returns the revision string of the tip of target branch. */
  String getBranchRevision(Repository repository, String url,
                           String project, String branch) {
    if (null != repository) {
      try {
        return repository.getRef(
            repository.getBranch()).getObjectId().getName();
      } catch (IOException e) {
        log.error("Fail to get branch revision: " + e);
      }
    } else {
      JsonObject obj = Util.getHTTPJsonObject(
          url + "projects/" + project + "/branches/" + branch, true);
      // cannot get revision of branch "refs/meta/config".
      if (null != obj && null != obj.get("revision")) {
        return obj.get("revision").getAsString();
      }
    }
    return "";
  }

  /** Returns true if exempt from owner approval; uses Prolog engine. */
  boolean isExemptFromOwnerApproval() {
    if (null == engine) {
      return true;
    }
    try {
      ChangeData data = StoredValues.CHANGE_DATA.get(engine);
      String message = data.commitMessage();
      if (message.contains(EXEMPT_MESSAGE1)
          || message.contains(EXEMPT_MESSAGE2)) {
        return true;
      }
    } catch (IOException | OrmException e) {
      log.error("Cannot get commit message: " + e);
      return true;  // exempt from owner approval due to lack of data
    }
    // Abandoned and merged changes do not need approval again.
    Status status = StoredValues.getChange(engine).getStatus();
    return (status == Status.ABANDONED || status == Status.MERGED);
  }

  /** Returns a cached or new OwnersDb. */
  OwnersDb getCachedOwnersDb() {
    if (null != engine) { // Get changed files faster from StoredValues.
      Repository repository = StoredValues.REPOSITORY.get(engine);
      String dbKey = Cache.makeKey(change, patchset, branch);
      return Cache.get(this, dbKey, repository, branch, getChangedFiles());
    }
    String key = Cache.makeKey(change, patchset, branch);
    return Cache.get(this, key, url, project, branch, getChangedFiles());
  }

  /** Returns a debug message string, for server side logging. */
  String genDebugMsg(OwnersDb db) {
    return (null == url ? "" : ("\n## url=" + url))
           + "\n## change=" + change + ", patchset=" + patchset
           + ", project=" + project + ", branch=" + branch
           + "\n## changedFiles=" + getChangedFiles()
           + "\nnumOwners=" + db.getNumOwners()
           + ", minVoteLevel=" + getMinOwnerVoteLevel()
           + ", approvals=" + getVotes();
  }
}
