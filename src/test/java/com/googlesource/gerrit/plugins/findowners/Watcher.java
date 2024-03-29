// Copyright (C) 2019 The Android Open Source Project
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

import com.google.common.flogger.FluentLogger;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;

/** Watcher for JUnit tests. */
class Watcher extends TestWatcher {
  private final FluentLogger logger;

  Watcher(FluentLogger logger) {
    this.logger = logger;
  }

  @Override
  public void starting(final Description method) {
    logger.atInfo().log("Test starting: %s", method);
  }

  @Override
  public void finished(final Description method) {
    logger.atInfo().log("Test finished: %s", method);
  }
}
