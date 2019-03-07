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

import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.gerrit.extensions.restapi.RestApiModule;
import com.google.gerrit.extensions.webui.JavaScriptPlugin;
import com.google.gerrit.extensions.webui.WebUiPlugin;
import com.google.gerrit.server.rules.SubmitRule;
import com.google.inject.AbstractModule;
import com.googlesource.gerrit.plugins.findowners.submit_rules.OwnersFileRule;

/** find-owners plugin module */
public class Module extends AbstractModule {
  @Override
  protected void configure() {
    install(OwnersValidator.module());
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

    DynamicSet.bind(binder(), SubmitRule.class).to(OwnersFileRule.class);

    install(new PredicateModule());
  }
}
