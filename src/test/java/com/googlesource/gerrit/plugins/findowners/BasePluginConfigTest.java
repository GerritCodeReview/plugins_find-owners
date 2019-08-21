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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gerrit.server.config.PluginConfig;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.Config;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class BasePluginConfigTest {

  private static BasePluginConfig createConfig(String name, String content)
      throws ConfigInvalidException {
    Config cfg = new Config();
    cfg.fromText(content); // could throw ConfigInvalidException
    return new BasePluginConfig(name, new PluginConfig(name, cfg));
  }

  private static String pluginSection(String name) {
    return "[plugin \"" + name + "\"]\n";
  }

  private static void sanityChecks(BasePluginConfig cfg) throws ConfigInvalidException {
    assertEquals("string", "1+1", cfg.getString("k1"));
    assertEquals("string", "'t'+k1", cfg.getString("v1"));
    assertEquals("int", 2, cfg.getInt("v2", 1));
    assertNull(cfg.getString("k3"));
    assertTrue("boolean", cfg.getBoolean("k2", false));
    assertEquals("string", "1+1", cfg.getRawString("k1"));
    assertEquals("string", "'t'+k1", cfg.getRawString("v1"));
    assertEquals("string", "true", cfg.getRawString("k2"));
    assertEquals("string", "2", cfg.getRawString("v2"));
  }

  @Test
  public void sanity() throws ConfigInvalidException {
    String name = "find-owners";
    String content0 = pluginSection(name) + "k1=1+1\nk2=true\nv1='t'+k1\nv2=2\n";
    String content1 = pluginSection("other") + "k3='abc'\n";
    BasePluginConfig cfg = createConfig(name, content0 + content1);
    sanityChecks(cfg);
    assertFalse("boolean", cfg.getBoolean("useJSE", false));
    assertTrue("boolean", cfg.getBoolean("useJSE", true));
    // Config contains useJSE=true, but BasePluginConfig does not use it.
    cfg = createConfig(name, content0 + "useJSE=true\n" + content1);
    sanityChecks(cfg);
    assertTrue("boolean", cfg.getBoolean("useJSE", false));
    assertTrue("boolean", cfg.getBoolean("useJSE", true));
  }
}
