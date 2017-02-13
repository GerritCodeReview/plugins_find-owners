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
import org.junit.Before;
import org.junit.Test;

/** Test OwnersDb class */
public class OwnersDbTest {
  private MockedServer server;

  @Before
  public void setUp() {
    server = new MockedServer();
  }

  @Test
  public void ctorTest() {
    // TODO: test getNumOwners, etc.
  }

  @Test
  public void addOwnerPathPairTest() {
    // TODO: test addOwnerPathPair
  }

  @Test
  public void addFileTest() {
    // TODO: test addFile
    assertThat(1 + 1).isEqualTo(2);
  }

  @Test
  public void findOwnersTest() {
    // TODO: test findOwners
  }
}
