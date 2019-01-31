// Copyright (C) 2018 The Android Open Source Project
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

import com.google.common.collect.ImmutableSet;
import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.gerrit.server.rules.PredicateProvider;
import com.google.inject.AbstractModule;

/** provides the Prolog predicate, even in a batch mode */
public class PredicateModule extends AbstractModule {
  /** Prolog Predicate Provider. */
  static class FindOwnersProvider implements PredicateProvider {

    @Override
    public ImmutableSet<String> getPackages() {
      return ImmutableSet.of(Config.PROLOG_NAMESPACE);
    }
  }

  @Override
  protected void configure() {
    DynamicSet.bind(binder(), PredicateProvider.class).to(FindOwnersProvider.class);
  }
}
