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

import com.google.gerrit.extensions.annotations.PluginCanonicalWebUrl;
import com.google.gerrit.extensions.restapi.RestModifyView;
import com.google.gerrit.extensions.webui.UiAction;
import com.google.gerrit.reviewdb.client.Change.Status;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.change.RevisionResource;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.googlesource.gerrit.plugins.findowners.Util.Owner2Weights;
import com.googlesource.gerrit.plugins.findowners.Util.String2String;
import com.googlesource.gerrit.plugins.findowners.Util.String2StringSet;
import com.googlesource.gerrit.plugins.findowners.Util.StringSet;
import java.util.Collection;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Create and return OWNERS info when "Find Owners" button is clicked. */
class Action implements UiAction<RevisionResource>,
    RestModifyView<RevisionResource, Action.Input> {

  private static final Logger log = LoggerFactory.getLogger(Action.class);

  private Provider<CurrentUser> user;  // is null if from HTTP request
  private String url; // from REST request or client action call
  private Server server;

  static class Input {
    // Only the change number is required.
    int    change;   // Revision change number
    String debug;    // REST API parameter, 1, true/false, yes
    String patchset; // REST API parameter, patchset number

    Input(int change) {
      this.change = change;
      // other parameters have default 0 or null, will be set later
    }

    Input addParams(String2String params) {
      debug = params.get("debug");
      patchset = params.get("patchset");
      // other keys in params are ignored
      return this;
    }
  }

  @Inject
  Action(@PluginCanonicalWebUrl String url, Provider<CurrentUser> user) {
    this.url = Util.normalizeURL(url); // replace "http:///" with "http://"
    int n = this.url.indexOf("/plugins/");
    if (n > 0) { // remove suffix "plugins/find-owners/...."
      this.url = this.url.substring(0, n + 1);
    }
    this.user = user;
    server = new Server();
  }

  /** Used by unit tests to set up mocked Server. */
  void setServer(Server s) {
    server = s;
  }

  private String getUserName() {
    return (null != user) ? user.get().getUserName() : "?";
  }

  private JsonArray getOwners(OwnersDb db, Collection<String> files) {
    Owner2Weights weights = new Owner2Weights();
    String2StringSet file2Owners = db.findOwners(files, weights);
    JsonArray result = new JsonArray();
    StringSet emails = new StringSet();
    for (String key : OwnerWeights.sortKeys(weights)) {
      if (!emails.contains(key)) {
        result.add(key + " " + weights.get(key).encodeLevelCounts());
        emails.add(key);
      }
    }
    return result;
  }

  private void addNamedMap(JsonObject obj, String name,
                           Map<String, StringSet> map) {
    JsonObject jsonMap = new JsonObject();
    for (String key : Util.sort(map.keySet())) {
      jsonMap.addProperty(key, String.join(" ", Util.sort(map.get(key))));
    }
    obj.add(name, jsonMap);
  }

  /** REST API to return owners info of a change. */
  public JsonObject getChangeData(int change, String2String params) {
    return apply(null, new Input(change).addParams(params));
  }

  /** Called by the client "Find Owners" button. */
  @Override
  public JsonObject apply(RevisionResource rev, Input input) {
    server.setChangeId(url, input.change);
    String error = (null != server.error)
        ? server.error : server.setPatchId(input.patchset);
    if (null != error) {
      JsonObject obj = new JsonObject();
      obj.addProperty("error", error);
      return obj;
    }
    OwnersDb db = server.getCachedOwnersDb();
    Collection<String> changedFiles = server.getChangedFiles();
    String2StringSet file2Owners = db.findOwners(changedFiles);

    JsonObject obj = new JsonObject();
    obj.addProperty(Config.MIN_OWNER_VOTE_LEVEL, server.getMinOwnerVoteLevel());
    boolean addDebugMsg = (null != input.debug)
        ? Util.parseBoolean(input.debug) : server.getAddDebugMsg();
    obj.addProperty(Config.ADD_DEBUG_MSG, addDebugMsg);
    obj.addProperty("change", input.change);
    obj.addProperty("patchset", server.patchset);
    obj.addProperty("owner_revision", db.revision);

    if (addDebugMsg) {
      JsonObject dbgMsgObj = new JsonObject();
      dbgMsgObj.addProperty("user", getUserName());
      dbgMsgObj.addProperty("project", server.project);
      dbgMsgObj.addProperty("branch", server.branch);
      dbgMsgObj.addProperty("server", url);
      obj.add("dbgmsgs", dbgMsgObj);
      addNamedMap(obj, "path2owners", db.path2Owners);
      addNamedMap(obj, "owner2paths", db.owner2Paths);
    }

    addNamedMap(obj, "file2owners", file2Owners);
    obj.add("reviewers", server.getReviewers());
    obj.add("owners", getOwners(db, changedFiles));
    obj.add("files", Util.newJsonArrayFromStrings(changedFiles));
    return obj;
  }

  @Override
  public Description getDescription(RevisionResource resource) {
    int change = resource.getChange().getId().get();
    server.setChangeId(url, change);
    if (null == server.branch) {
      log.error("Cannot get branch of change: " + change);
      return null; // no "Find Onwers" button
    }
    OwnersDb db = server.getCachedOwnersDb();
    if (server.traceServerMsg()) {
      log.info(server.genDebugMsg(db));
    }
    Status status = server.getStatus(resource);
    // Commit message is not used to enable/disable "Find Owners".
    boolean needFindOwners =
        (null != user && user.get() instanceof IdentifiedUser)
        && (db.getNumOwners() > 0)
        && (status != Status.ABANDONED && status != Status.MERGED);
    return new Description()
        .setLabel("Find Owners")
        .setTitle("Find owners to add to Reviewers list")
        .setVisible(needFindOwners);
  }
}
