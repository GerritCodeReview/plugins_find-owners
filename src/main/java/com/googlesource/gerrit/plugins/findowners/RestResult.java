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

import com.google.gerrit.server.project.ProjectState;
import com.google.gerrit.server.query.change.ChangeData;
import com.google.gson.annotations.SerializedName;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

/** REST API return data Java class. */
public class RestResult {
  @SerializedName("addDebugMsg")
  boolean addDebugMsg;

  @SerializedName("autoAuthorApproval")
  boolean autoAuthorApproval;

  @SerializedName("maxCacheAge")
  int maxCacheAge;

  @SerializedName("maxCacheSize")
  int maxCacheSize;

  @SerializedName("minOwnerVoteLevel")
  int minOwnerVoteLevel;

  @SerializedName("ownersFileName")
  String ownersFileName;

  @SerializedName("rejectErrorInOwners")
  boolean rejectErrorInOwners;

  int change;
  int patchset;

  @SerializedName("owner_revision")
  String ownerRevision;

  DebugMessages dbgmsgs;
  SortedMap<String, List<String>> file2owners = new TreeMap<>();
  List<String> reviewers = new ArrayList<>();
  List<OwnerInfo> owners = new ArrayList<>();
  List<String> files = new ArrayList<>();

  RestResult(Config config, ProjectState projectState, ChangeData changeData, boolean addDebugMsg) {
    this.addDebugMsg = addDebugMsg;
    autoAuthorApproval = config.getAutoAuthorApproval();
    maxCacheAge = config.getMaxCacheAge();
    maxCacheSize = config.getMaxCacheSize();
    minOwnerVoteLevel = config.getMinOwnerVoteLevel(projectState, changeData);
    ownersFileName = config.getOwnersFileName(projectState, changeData);
    rejectErrorInOwners = config.getRejectErrorInOwners(projectState, changeData);
    change = changeData.getId().get();
    if (addDebugMsg) {
      dbgmsgs = new DebugMessages();
      dbgmsgs.path2owners = new TreeMap<>();
      dbgmsgs.owner2paths = new TreeMap<>();
      dbgmsgs.project = changeData.change().getProject().get();
      dbgmsgs.branch = changeData.change().getDest().branch();
    }
  }

  static class DebugMessages {
    String user;
    String project;
    String branch;
    List<String> errors;
    SortedMap<String, List<String>> path2owners;
    SortedMap<String, List<String>> owner2paths;
    List<String> logs;
  }
}
