package com.googlesource.gerrit.plugins.findowners;

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
import com.google.gerrit.server.project.ProjectCache;
import com.google.gerrit.server.project.ProjectState;
import com.google.gerrit.server.project.SubmitRuleOptions;
import com.google.gerrit.server.query.change.ChangeData;
import com.google.gerrit.server.rules.SubmitRule;
import com.google.gwtorm.server.OrmException;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.io.IOException;
import java.util.Collection;
import java.util.List;

@Singleton
public class OwnersFileRule implements SubmitRule {

  private final PluginConfigFactory pluginConfigFactory;
  private final GitRepositoryManager repoManager;
  private final AccountCache accounts;
  private final Emails emails;
  private final ProjectCache projectCache;
  private final String pluginName;
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final SubmitRequirement REQUIREMENT =
      SubmitRequirement.builder()
          .setType("missing_owners_permissions")
          .setFallbackText("Missing Approvals From File Owners")
          .build();
  public static final List<SubmitRequirement> SUBMIT_REQUIREMENTS = ImmutableList.of(REQUIREMENT);

  private final String LOOKUP_ERROR_MSG = "An exception occurred while looking up data for this change";

  @Inject
  public OwnersFileRule(
      AccountCache accounts, Emails emails,
      PluginConfigFactory pluginConfigFactory,
      GitRepositoryManager repoManager, ProjectCache projectCache,
      @PluginName String pluginName) {
    this.accounts = accounts;
    this.emails = emails;
    this.pluginConfigFactory = pluginConfigFactory;
    this.repoManager = repoManager;
    this.projectCache = projectCache;
    this.pluginName = pluginName;
  }

  @Override
  public Collection<SubmitRecord> evaluate(ChangeData cd, SubmitRuleOptions options) {
    ProjectState projectState = projectCache.get(cd.project());
    PluginConfig pluginConfig = pluginConfigFactory.getFromProjectConfigWithInheritance(
        projectState, pluginName
    );

    EnforcementLevel enforce_level = pluginConfig
        .getEnum(EnforcementLevel.CONFIG_NAME, EnforcementLevel.DISABLED);

    if (enforce_level == EnforcementLevel.DISABLED) {
      return ImmutableList.of();
    }

    Checker checker = new Checker(repoManager, pluginConfigFactory, projectState, cd, 1);
    int result;
    try {
      OwnersDb db = Cache.getInstance(pluginConfigFactory, repoManager)
          .get(true, projectState, accounts, emails, repoManager,
              pluginConfigFactory, cd);
      result = checker.findApproval(accounts, db);
    } catch (OrmException | IOException e) {
      this.logger.atSevere().withCause(e).log("Exception for %s", Config.getChangeId(cd));

      SubmitRecord rec = new SubmitRecord();
      rec.status = SubmitRecord.Status.RULE_ERROR;
      rec.errorMessage = LOOKUP_ERROR_MSG;
      return ImmutableList.of(rec);
    }

    SubmitRecord sr = new SubmitRecord();
    sr.requirements = SUBMIT_REQUIREMENTS;

    switch (enforce_level) {
      case WARN:
        sr.status = (result >= 0) ? Status.OK : Status.FORCED;
        break;
      case ENFORCE:
        sr.status =
            (result >= 0) ? Status.OK : Status.NOT_READY;
        break;
    }

    return ImmutableList.of(sr);
  }
}
