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

import com.googlesource.gerrit.plugins.findowners.Util.String2Integer;
import com.googlesource.gerrit.plugins.findowners.Util.StringSet;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Test Checker class */
@RunWith(JUnit4.class)
public class CheckerTest {
  private MockedServer server;

  @Before
  public void setUp() {
    server = new MockedServer();
  }

  @Test
  public void findOwnersInVotesTest() {
    Checker c = new Checker(server, 2);
    StringSet owners = new StringSet();
    String2Integer votes = new String2Integer();
    // no owner, default is false.
    assertThat(c.findOwnersInVotes(owners, votes)).isFalse();
    // has owner, no vote, default is false.
    owners.add("xyz@google.com");
    assertThat(c.findOwnersInVotes(owners, votes)).isFalse();
    // has "*" owner, no owner vote is needed
    owners.add("*");
    assertThat(c.findOwnersInVotes(owners, votes)).isTrue();
    // again, no owner means no
    owners = new StringSet();
    assertThat(c.findOwnersInVotes(owners, votes)).isFalse();
    // two owners, but only +1
    owners.add("abc@google.com");
    owners.add("xyz@google.com");
    votes.put("xyz@google.com", 1);
    assertThat(c.findOwnersInVotes(owners, votes)).isFalse();
    // one owner +2 vote is enough
    votes.put("xyz@google.com", 2);
    assertThat(c.findOwnersInVotes(owners, votes)).isTrue();
    // two +1 votes is not the same as one +2
    votes.put("abc@google.com", 1);
    votes.put("xyz@google.com", 1);
    assertThat(c.findOwnersInVotes(owners, votes)).isFalse();
    votes.put("xyz@google.com", 2);
    assertThat(c.findOwnersInVotes(owners, votes)).isTrue();
    // one owner -1 is a veto.
    votes.put("abc@google.com", -1);
    assertThat(c.findOwnersInVotes(owners, votes)).isFalse();
  }

  void addOwnersInfo(MockedOwnersDb db) {
    String[] emails = {"abc@google.com", "xyz@google.com"};
    StringSet dirs = new StringSet();
    StringSet owners = new StringSet();
    dirs.add("./d1");
    for (String e : emails) {
      db.owner2Paths.put(e, dirs);
      owners.add(e);
    }
    db.mockedFile2Owners.put("./d1/f1.c", owners);
  }

  @Test
  public void findApprovalOwnersDbTest() {
    Checker c = new Checker(server, 2);
    MockedOwnersDb db = new MockedOwnersDb();
    assertThat(c.findApproval(db)).isEqualTo(0); // no owners info
    addOwnersInfo(db); // add one file and two owners
    assertThat(db.getNumOwners()).isEqualTo(2);
    assertThat(db.findOwners(null).size()).isEqualTo(1);
    assertThat(db.findOwners(null).get("f1")).isNull();
    assertThat(db.findOwners(null).get("./d1/f1.c")).hasSize(2);
    assertThat(c.findApproval(db)).isEqualTo(-1);
    server.votes.put("abc@google.com", 1);
    // sever has minOwnerVoteLevel 1, but checker requires 2.
    assertThat(server.getMinOwnerVoteLevel()).isEqualTo(1);
    assertThat(c.findApproval(db)).isEqualTo(-1); // vote 1 is not enough
    c = new Checker(server, 1);
    assertThat(c.findApproval(db)).isEqualTo(1);
    server.votes.put("xyz@google.com", -1);
    assertThat(c.findApproval(db)).isEqualTo(-1); // an owner's veto
  }

  @Test
  public void findApprovalTest() {
    Checker c = new Checker(server, 1);
    // default mocked, not exempted from owner approval
    assertThat(server.isExemptFromOwnerApproval()).isFalse();
    // not exempted, but no owner in mocked OwnersDb
    assertThat(c.findApproval()).isEqualTo(0);
    server.exemptFromOwnerApproval = true;
    assertThat(server.isExemptFromOwnerApproval()).isTrue();
    // exempted, no owner, should be 0, not 1 or -1.
    assertThat(c.findApproval()).isEqualTo(0);
    server.exemptFromOwnerApproval = false;
    // add mocked owners, no vote, should be -1, not approved.
    addOwnersInfo(server.ownersDb);
    assertThat(c.findApproval()).isEqualTo(-1);
    // add vote, should be approved now.
    server.votes.put("abc@google.com", 1);
    assertThat(c.findApproval()).isEqualTo(1);
  }
}
