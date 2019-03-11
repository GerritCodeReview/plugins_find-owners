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
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestWatcher;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.junit.runner.Description;

/** Test Util class */
@RunWith(JUnit4.class)
public class UtilTest {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  @Rule
  public TestWatcher watcher = new TestWatcher() {
    @Override
    public void starting(final Description method) {
      logger.atInfo().log("Test starting: " + method.getMethodName());
    }

    @Override
    public void finished(final Description method) {
      logger.atInfo().log("Test finished: " + method.getMethodName());
    }
  };

  @Test
  public void getOwner2WeightsTest() {
    Map<String, OwnerWeights> m = new HashMap<>();
    assertThat(m).isEmpty();
    assertThat(m.get("s1")).isNull();
    OwnerWeights v1 = new OwnerWeights();
    OwnerWeights v2 = new OwnerWeights();
    m.put("s1", v1);
    assertThat(m.get("s1")).isEqualTo(v1);
    // compare OwnerWeights by reference
    assertThat(m.get("s1")).isNotEqualTo(v2);
    assertThat(m.get("s2")).isNull();
    assertThat(m).hasSize(1);
  }

  @Test
  public void getString2IntegerTest() {
    Map<String, Integer> m = new HashMap<>();
    assertThat(m).isEmpty();
    assertThat(m.get("s1")).isNull();
    Integer v1 = 3;
    Integer v2 = 3;
    m.put("s1", v1);
    assertThat(m.get("s1")).isEqualTo(v1);
    // compare Integer by value
    assertThat(m.get("s1")).isEqualTo(v2);
    assertThat(m.get("s2")).isNull();
    assertThat(m).hasSize(1);
  }

  @Test
  public void getString2StringTest() {
    Map<String, String> m = new HashMap<>();
    assertThat(m).isEmpty();
    assertThat(m.get("s1")).isNull();
    String v1 = "x";
    String v2 = "x";
    m.put("s1", v1);
    assertThat(m.get("s1")).isEqualTo(v1);
    // compare String by value
    assertThat(m.get("s1")).isEqualTo(v2);
    assertThat(m.get("s2")).isNull();
    assertThat(m).hasSize(1);
  }

  @Test
  public void getString2StringSetTest() {
    Map<String, Set<String>> m = new HashMap<>();
    assertThat(m).isEmpty();
    assertThat(m.get("s1")).isNull();
    Set<String> v1 = new HashSet<>();
    Set<String> v2 = new HashSet<>();
    assertThat(v1).isEmpty();
    v1.add("x");
    v1.add("y");
    v2.add("y");
    v2.add("x");
    assertThat(v1).hasSize(2);
    m.put("s1", v1);
    assertThat(m.get("s1")).isEqualTo(v1);
    // compare Set<String> by value
    assertThat(m.get("s1")).isEqualTo(v2);
    assertThat(m.get("s2")).isNull();
    assertThat(m).hasSize(1);
  }

  @Test
  public void addStringSetTest() {
    Set<String> s = new HashSet<>();
    assertThat(s).isEmpty();
    s.add("s1");
    assertThat(s).contains("s1");
    assertThat(s).doesNotContain("s2");
    assertThat(s).hasSize(1);
  }

  @Test
  public void stripMagicPrefixTest() {
    assertThat(Util.stripMagicPrefix("abc\nxyz\n")).isEqualTo("abc\nxyz\n");
    assertThat(Util.stripMagicPrefix(")]}'\nxyz\n")).isEqualTo("xyz\n");
    assertThat(Util.stripMagicPrefix(")]}'xyz\n")).isEqualTo(")]}'xyz\n");
    assertThat(Util.stripMagicPrefix(")]}'\nxyz")).isEqualTo("xyz");
  }

  @Test
  public void getDirNameTest() {
    String[] files = {"", "./d1/", "d1/d2/f1.c", "./d2/f2.c", "./d1"};
    String[] dirs = {null, ".", "d1/d2", "./d2", "."};
    for (int i = 0; i < files.length; i++) {
      assertThat(Util.getDirName(files[i])).isEqualTo(dirs[i]);
    }
  }

  @Test
  public void addDotPrefixTest() {
    String[] files = {"", "./d1/", "d1/d2/f1.c", "d2/f2/", "d1"};
    String[] results = {"./", "./d1/", "./d1/d2/f1.c", "./d2/f2/", "./d1"};
    for (int i = 0; i < files.length; i++) {
      assertThat(Util.addDotPrefix(files[i])).isEqualTo(results[i]);
    }
  }

  @Test
  public void getParentDirTest() {
    String[] files = {"", "./d1/", "d1/d2/f1.c", "./d2/f2.c", "./d1"};
    String[] dirs = {null, ".", "./d1/d2", "./d2", "."};
    for (int i = 0; i < files.length; i++) {
      assertThat(Util.getParentDir(files[i])).isEqualTo(dirs[i]);
    }
  }

  @Test
  public void gitRepoFilePathTest() {
    String[] inFiles = {null, "", "//", "./d1/", "d1/d2", "///d1/f//", "/././/f2", "/./f3"};
    String[] outFiles = {"", "", "", "d1", "d1/d2", "d1/f", "f2", "f3"};
    for (int i = 0; i < inFiles.length; i++) {
      assertThat(Util.gitRepoFilePath(inFiles[i])).isEqualTo(outFiles[i]);
    }
  }

  @Test
  public void normalizedDirFilePathTest() throws IOException {
    String[] dirs = {"", "", ".", "./d1/..", "/d1/d2/../..",
      "d1", "d2/../d1", "/d1", "/d1", "/d1"};
    String[] files = {"f0.c", "./f1.c", "./f2", "/f3", "f4",
      "f5", "f6", "f7", "f8", "../d5/../d1/f9"};
    String[] paths = {"./f0.c", "./f1.c", "./f2", "./f3", "./f4",
      "./d1/f5", "./d1/f6", "./d1/f7", "./d1/f8", "./d1/f9"};
    for (int i = 0; i < files.length; i++) {
      assertThat(Util.normalizedDirFilePath(dirs[i], files[i])).isEqualTo(paths[i]);
    }
  }

  @Test
  public void parseBooleanTest() {
    String[] yesStrs = {"True", "1", "true", "TRUE", "yes"};
    String[] noStrs = {"", "False", "0", "false", "FALSE", "no", "other"};
    for (String s : yesStrs) {
      assertThat(Util.parseBoolean(s)).isTrue();
    }
    for (String s : noStrs) {
      assertThat(Util.parseBoolean(s)).isFalse();
    }
  }

  @Test
  public void addKeyToMapTest() {
     Map<String, Set<String>> m = new HashMap<>();
     String key1 = "key1";
     String key2 = "key2";
     assertThat(m).isEmpty();
     Util.addKeyToMap(m, key1);
     assertThat(m).hasSize(1);
     assertThat(m.get(key1)).isEmpty();
     Util.addKeyToMap(m, key1);
     assertThat(m).hasSize(1);
     assertThat(m.get(key1)).isEmpty();
     Util.addKeyToMap(m, key2);
     assertThat(m).hasSize(2);
     assertThat(m.get(key1)).isEmpty();
     assertThat(m.get(key2)).isEmpty();
  }

  @Test
  public void addToMapTest() {
     Map<String, Set<String>> m = new HashMap<>();
     String key1 = "key1";
     String key2 = "key2";
     Util.addToMap(m, key1, "v1");
     Util.addToMap(m, key2, "v2");
     Util.addToMap(m, key2, "v3");
     assertThat(m.get(key1)).containsExactly("v1");
     assertThat(m.get(key2)).containsExactly("v2", "v3");
  }

  @Test
  public void addAllToMapTest() {
     Map<String, Set<String>> m = new HashMap<>();
     Set<String> s = new HashSet<>();
     String key = "key";
     Util.addAllToMap(m, key, s);
     assertThat(m.get(key)).isEmpty();
     s.add("v1");
     Util.addAllToMap(m, key, s);
     assertThat(m.get(key)).containsExactly("v1");
     Util.addAllToMap(m, key, s);
     assertThat(m.get(key)).containsExactly("v1");
     s.add("v2");
     Util.addAllToMap(m, key, s);
     assertThat(m.get(key)).containsExactly("v2", "v1");
     Util.addAllToMap(m, key, s);
     assertThat(m.get(key)).containsExactly("v2", "v1");
     s.add("v3");
     Util.addAllToMap(m, key, s);
     assertThat(m.get(key)).containsExactly("v2", "v1", "v3");
  }

  @Test
  public void makeSortedMapTest() {
    // TODO: test Util.makeSortedMap
  }
}
