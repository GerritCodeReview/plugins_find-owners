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

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.acceptance.GitUtil;
import com.google.gerrit.acceptance.LightweightPluginDaemonTest;
import com.google.gerrit.acceptance.PushOneCommit;
import com.google.gerrit.acceptance.Sandboxed;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.reviewdb.client.RefNames;
import find_owners.PRED_add_may_label_2;
import find_owners.PRED_check_owner_approval_2;
import find_owners.PRED_remove_may_label_2;
import find_owners.PRED_remove_need_label_2;
import find_owners.PRED_submit_filter_2;
import find_owners.PRED_submit_filter_3;
import find_owners.PRED_submit_rule_1;
import find_owners.PRED_submit_rule_2;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.Rule;
import org.junit.Test;

/** Test existence of compiled find-owners plugin Prolog predicates. */
@TestPlugin(name = "find-owners", sysModule = "com.googlesource.gerrit.plugins.findowners.Module")
public class PrologIT extends LightweightPluginDaemonTest {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  @Rule public Watcher watcher = new Watcher(logger);

  @Test
  public void predefinedPredicateTest() throws Exception {
    Object term = new PRED_check_owner_approval_2(null, null, null);
    assertThat(term).isNotNull();
  }

  @Test
  public void compiledPredicateTest() throws Exception {
    Object term = new PRED_submit_rule_1(null, null);
    assertThat(term).isNotNull();
    term = new PRED_submit_rule_2(null, null, null);
    assertThat(term).isNotNull();
    term = new PRED_submit_filter_2(null, null, null);
    assertThat(term).isNotNull();
    term = new PRED_submit_filter_3(null, null, null, null);
    assertThat(term).isNotNull();
    term = new PRED_add_may_label_2(null, null, null);
    assertThat(term).isNotNull();
    term = new PRED_remove_may_label_2(null, null, null);
    assertThat(term).isNotNull();
    term = new PRED_remove_need_label_2(null, null, null);
    assertThat(term).isNotNull();
  }

  @Sandboxed
  @Test
  public void submitRuleTest() throws Exception {
    RevCommit oldHead = getRemoteHead();
    GitUtil.fetch(testRepo, RefNames.REFS_CONFIG + ":config");
    testRepo.reset("config");
    PushOneCommit push =
        pushFactory.create(
            admin.getIdent(),
            testRepo,
            "Configure",
            "rules.pl",
            "submit_rule(submit(R)) :- \n"
                + "gerrit:default_submit(S0),\n"
                + "find_owners:submit_rule(S1),\n"
                + "find_owners:submit_filter(S1, S2), !,\n"
                + "S1 = S2,\n" // find_owners:submit_filter does nothing
                + "S0 \\= S1,\n" // find_owners:submit_rule adds 'may(_)'
                + "find_owners:remove_may_label(S1, S3),\n"
                + "S0 = S3,\n" // find_owners:submit_rule only adds 'may(_)'
                + "gerrit:commit_author(A), \n"
                + "R = label('Code-Review', ok(A)).\n");
    push.to(RefNames.REFS_CONFIG);
    testRepo.reset(oldHead);
    oldHead = getRemoteHead();
    PushOneCommit.Result result =
        pushFactory.create(user.getIdent(), testRepo).to("refs/for/master");
    testRepo.reset(oldHead);
    gApi.changes().id(result.getChangeId()).current().submit();
  }
}
