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

import com.google.gson.annotations.SerializedName;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

/** REST API return data Java class. */
public class RestResult {
  @SerializedName("minOwnerVoteLevel")
  int minOwnerVoteLevel;

  @SerializedName("addDebugMsg")
  boolean addDebugMsg;

  int change;
  int patchset;

  @SerializedName("owner_revision")
  String ownerRevision;

  DebugMessages dbgmsgs;
  SortedMap<String, String> file2owners = new TreeMap<>();
  List<String> reviewers = new ArrayList<>();
  List<String> owners = new ArrayList<>();
  List<String> files = new ArrayList<>();

  RestResult(int voteLevel, boolean addDebugMsg) {
    minOwnerVoteLevel = voteLevel;
    this.addDebugMsg = addDebugMsg;
    if (addDebugMsg) {
      dbgmsgs = new DebugMessages();
      dbgmsgs.path2owners = new TreeMap<>();
      dbgmsgs.owner2paths = new TreeMap<>();
    }
  }

  static class DebugMessages {
    String user;
    String project;
    String branch;
    SortedMap<String, String> path2owners;
    SortedMap<String, String> owner2paths;
  };
}