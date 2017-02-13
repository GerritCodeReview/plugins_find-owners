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

import static com.google.common.truth.Truth.assertThat;
import com.googlesource.gerrit.plugins.findowners.Util.String2StringSet;
import com.googlesource.gerrit.plugins.findowners.Util.StringSet;
import java.util.Collection;
import org.junit.Test;

/** Mocked OwnersDb to test Parser class */
public class MockedOwnersDb extends OwnersDb {
  private String savedData;
  String2StringSet mockedFile2Owners;

  public MockedOwnersDb() {
    super(null);
    resetData();
    mockedFile2Owners = new String2StringSet();
  }

  void resetData() {
    savedData = "";
    stopLooking = new StringSet();
  }

  void appendSavedData(String s) {
    savedData += s;
  }

  String getSavedData() {
    return savedData;
  }

  StringSet getStopLooking() {
    return stopLooking;
  }

  @Override
  void addOwnerPathPair(String s1, String s2) {
    savedData += "s1:" + s1 + "\ns2:" + s2 + "\n";
  }

  @Override
  String2StringSet findOwners(Collection<String> files) {
    return mockedFile2Owners;
  }

  @Test
  public void defaultTest() {
    // Trivial test of default OwnersDb members.
    assertThat(revision).isEqualTo("");
    assertThat(dir2Globs.size()).isEqualTo(0);
    assertThat(owner2Paths.size()).isEqualTo(0);
    assertThat(path2Owners.size()).isEqualTo(0);
    assertThat(readDirs.size()).isEqualTo(0);
    assertThat(stopLooking.size()).isEqualTo(0);
  }
}
