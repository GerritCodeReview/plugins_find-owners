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

import static com.google.gerrit.server.change.ChangeResource.CHANGE_KIND;
import static com.google.gerrit.server.change.RevisionResource.REVISION_KIND;

import com.google.common.collect.ImmutableSet;
import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.gerrit.extensions.restapi.RestApiModule;
import com.google.gerrit.extensions.webui.JavaScriptPlugin;
import com.google.gerrit.extensions.webui.WebUiPlugin;
import com.google.gerrit.rules.PredicateProvider;
import com.google.gerrit.server.config.PluginConfigFactory;
import com.google.inject.AbstractModule;
import com.google.inject.Inject;

/** find-owners plugin module */
public class Module extends AbstractModule {
  /** Prolog Predicate Provider. */
  static class FindOwnersProvider implements PredicateProvider {

    @Inject
    public FindOwnersProvider(@PluginName String pluginName, PluginConfigFactory configFactory) {
      Config.setVariables(pluginName, configFactory);
      Cache.getInstance(); // Create a single Cache.
    }

    @Override
    public ImmutableSet<String> getPackages() {
      return ImmutableSet.of(Config.PROLOG_NAMESPACE);
    }
  }

  @Override
  protected void configure() {
    install(
        new RestApiModule() {
          @Override
          protected void configure() {
            get(CHANGE_KIND, "owners").to(GetOwners.class);
            get(REVISION_KIND, Config.PLUGIN_NAME).to(Action.class);
          }
        });
    DynamicSet.bind(binder(), WebUiPlugin.class)
        .toInstance(new JavaScriptPlugin(Config.PLUGIN_NAME + ".js"));
    DynamicSet.bind(binder(), PredicateProvider.class).to(FindOwnersProvider.class);
  }
}
