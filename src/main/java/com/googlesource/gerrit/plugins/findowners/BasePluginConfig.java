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

import com.google.gerrit.server.config.PluginConfig;

/**
 * BasePluginConfig wraps Gerrit PluginConfig for find-owners Config.
 *
 * <p>This base class provides a subset of PluginConfig methods for find-owners Config. It could be
 * extended in the future to provide more dynamic evaluation of key values. Its contructor keeps and
 * delegates all methods to a PluginConfig.
 */
class BasePluginConfig {
  protected final String name; // name of this plugin
  protected final PluginConfig config; // wrapped PluginConfig

  public BasePluginConfig(String name, PluginConfig config) {
    this.name = name;
    this.config = config;
  }

  // Return the uninterpreted string value of a key.
  public String getRawString(String name) {
    return config.getString(name);
  }

  // Return the uninterpreted string value of a key with default value.
  public String getRawString(String name, String defaultValue) {
    return config.getString(name, defaultValue);
  }

  public String getString(String name) {
    return config.getString(name);
  }

  public String getString(String name, String defaultValue) {
    return config.getString(name, defaultValue);
  }

  public int getInt(String name, int defaultValue) {
    return config.getInt(name, defaultValue);
  }

  public boolean getBoolean(String name, boolean defaultValue) {
    return config.getBoolean(name, defaultValue);
  }

  public <T extends Enum<?>> T getEnum(String name, T defaultValue) {
    return config.getEnum(name, defaultValue);
  }

  public <T extends Enum<?>> T getEnum(T[] all, String name, T defaultValue) {
    return config.getEnum(all, name, defaultValue);
  }
}
