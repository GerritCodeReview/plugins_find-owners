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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.googlesource.gerrit.plugins.findowners.Util.String2String;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Test Action class */
@RunWith(JUnit4.class)
public class ActionTest {
  private MockedServer server;
  private Action finder;
  private Gson gs;

  @Before
  public void setUp() {
    finder = new Action("http://mocked:8888/", null);
    server = new MockedServer();
    finder.setServer(server);
    gs = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
  }

  @Test
  public void getChangeDataTest() {
    JsonObject obj = finder.getChangeData(23, new String2String());
    // Write expected output as a more readable string literal,
    // remove all ' ', then use '\'' for '\"' and ' ' for '\n'.
    String expected = "{ 'minOwnerVoteLevel':1, 'addDebugMsg':true, "
        + "'change':23, 'patchset':3, 'owner_revision':'', "
        + "'dbgmsgs':{ 'user':'?', 'project':'projectA', "
        + "'branch':'master', 'server':'http://mocked:8888/' }, "
        + "'path2owners':{}, 'owner2paths':{}, 'file2owners':{}, "
        + "'reviewers':[], 'owners':[], 'files':[ './README', "
        + "'./d1/test.c', './d2/t.txt' ] }";
    String result = gs.toJson(obj).replace(" ", "");
    expected = expected.replace(' ', '\n');
    expected = expected.replace('\'', '\"');
    assertThat(result).isEqualTo(expected);
  }

  // TODO: test getChangeData with non-trivial parameters
}
