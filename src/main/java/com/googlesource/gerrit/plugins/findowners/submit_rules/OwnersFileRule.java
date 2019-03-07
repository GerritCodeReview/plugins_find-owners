package com.googlesource.gerrit.plugins.findowners.submit_rules;

import com.google.common.collect.ImmutableList;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.common.data.SubmitRecord;
import com.google.gerrit.common.data.SubmitRecord.Status;
import com.google.gerrit.common.data.SubmitRequirement;
import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.server.account.AccountCache;
import com.google.gerrit.server.account.Emails;
import com.google.gerrit.server.config.PluginConfig;
import com.google.gerrit.server.config.PluginConfigFactory;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.project.NoSuchProjectException;
import com.google.gerrit.server.project.ProjectCache;
import com.google.gerrit.server.project.ProjectConfig;
import com.google.gerrit.server.project.ProjectState;
import com.google.gerrit.server.project.SubmitRuleOptions;
import com.google.gerrit.server.query.change.ChangeData;
import com.google.gerrit.server.rules.SubmitRule;
import com.google.gwtorm.server.OrmException;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.findowners.Cache;
import com.googlesource.gerrit.plugins.findowners.Checker;
import com.googlesource.gerrit.plugins.findowners.Config;
import com.googlesource.gerrit.plugins.findowners.OwnersDb;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;

@Singleton
public class OwnersFileRule implements SubmitRule {

  private final PluginConfigFactory pluginConfigFactory;
  private final GitRepositoryManager repoManager;
  private final AccountCache accounts;
  private final Emails emails;
  private final ProjectCache projectCache;
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final SubmitRequirement REQUIREMENT =
      SubmitRequirement.builder()
          .setType("missing_owners_permissions")
          .setFallbackText("Obtain permission from the owners of all files involved in this change")
          .build();

  @Inject
  public OwnersFileRule(
      AccountCache accounts, Emails emails,
      PluginConfigFactory pluginConfigFactory,
      GitRepositoryManager repoManager, ProjectCache projectCache) {
    this.accounts = accounts;
    this.emails = emails;
    this.pluginConfigFactory = pluginConfigFactory;
    this.repoManager = repoManager;
    this.projectCache = projectCache;
  }

  @Override
  public Collection<SubmitRecord> evaluate(ChangeData cd, SubmitRuleOptions options) {
    ProjectState projectState = projectCache.get(cd.project());

    Checker checker = new Checker(repoManager, pluginConfigFactory, projectState, cd, 1);
    int result;
    try {
      OwnersDb db = Cache.getInstance(pluginConfigFactory, repoManager)
          .get(true, projectState, accounts, emails, repoManager,
              pluginConfigFactory, cd);
      result = checker.findApproval(accounts, db);
    } catch (OrmException | IOException e) {
      this.logger.atSevere().withCause(e).log("Exception for %s", Config.getChangeId(cd));
      result = 0;
    }

    SubmitRecord sr = new SubmitRecord();
    sr.requirements = Collections.singletonList(REQUIREMENT);
    sr.status =
        (result >= 0) ? Status.OK : Status.NOT_READY;

    return ImmutableList.of(sr);
  }
}
