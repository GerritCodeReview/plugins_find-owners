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
import com.googlesource.gerrit.plugins.findowners.Util.Owner2Weights;
import com.googlesource.gerrit.plugins.findowners.Util.String2Integer;
import com.googlesource.gerrit.plugins.findowners.Util.String2String;
import com.googlesource.gerrit.plugins.findowners.Util.String2StringSet;
import com.googlesource.gerrit.plugins.findowners.Util.StringSet;
import org.junit.Test;

/** Test Util class */
public class UtilTest {

  @Test
  public void getOwner2WeightsTest() {
    Owner2Weights m = new Owner2Weights();
    assertThat(m.size()).isEqualTo(0);
    assertThat(m.get("s1")).isEqualTo(null);
    OwnerWeights v1 = new OwnerWeights();
    OwnerWeights v2 = new OwnerWeights();
    m.put("s1", v1);
    assertThat(m.get("s1")).isEqualTo(v1);
    // compare OwnerWeights by reference
    assertThat(m.get("s1")).isNotEqualTo(v2);
    assertThat(m.get("s2")).isEqualTo(null);
    assertThat(m.size()).isEqualTo(1);
  }

  @Test
  public void getString2IntegerTest() {
    String2Integer m = new String2Integer();
    assertThat(m.size()).isEqualTo(0);
    assertThat(m.get("s1")).isEqualTo(null);
    Integer v1 = 3;
    Integer v2 = 3;
    m.put("s1", v1);
    assertThat(m.get("s1")).isEqualTo(v1);
    // compare Integer by value
    assertThat(m.get("s1")).isEqualTo(v2);
    assertThat(m.get("s2")).isEqualTo(null);
    assertThat(m.size()).isEqualTo(1);
  }

  @Test
  public void getString2StringTest() {
    String2String m = new String2String();
    assertThat(m.size()).isEqualTo(0);
    assertThat(m.get("s1")).isEqualTo(null);
    String v1 = "x";
    String v2 = "x";
    m.put("s1", v1);
    assertThat(m.get("s1")).isEqualTo(v1);
    // compare String by value
    assertThat(m.get("s1")).isEqualTo(v2);
    assertThat(m.get("s2")).isEqualTo(null);
    assertThat(m.size()).isEqualTo(1);
  }

  @Test
  public void getString2StringSetTest() {
    String2StringSet m = new String2StringSet();
    assertThat(m.size()).isEqualTo(0);
    assertThat(m.get("s1")).isEqualTo(null);
    StringSet v1 = new StringSet();
    StringSet v2 = new StringSet();
    assertThat(v1.size()).isEqualTo(0);
    v1.add("x");
    v1.add("y");
    v2.add("y");
    v2.add("x");
    assertThat(v1.size()).isEqualTo(2);
    m.put("s1", v1);
    assertThat(m.get("s1")).isEqualTo(v1);
    // compare StringSet by value
    assertThat(m.get("s1")).isEqualTo(v2);
    assertThat(m.get("s2")).isEqualTo(null);
    assertThat(m.size()).isEqualTo(1);
  }

  @Test
  public void addStringSetTest() {
    StringSet s = new StringSet();
    assertThat(s.size()).isEqualTo(0);
    s.add("s1");
    assertThat(s.contains("s1")).isEqualTo(true);
    assertThat(s.contains("s2")).isEqualTo(false);
    assertThat(s.size()).isEqualTo(1);
  }

  @Test
  public void normalizeURLTest() {
    String otherURL = "other:///something///else";
    String normalOtherURL = "other://something///else";
    String normalURL = "http://www.google.com:8080/plugins";
    String badURL = "http:///www.google.com:8080/plugins";
    String localURL = "http://localhost:8080/plugins";
    String badLocalURL = "http:///localhost:8080/plugins";
    // Allow other URL protocols, although we might need only http for now.
    assertThat(Util.normalizeURL(otherURL)).isEqualTo(normalOtherURL);
    assertThat(Util.normalizeURL(normalURL)).isEqualTo(normalURL);
    assertThat(Util.normalizeURL(badURL)).isEqualTo(normalURL);
    assertThat(Util.normalizeURL(localURL)).isEqualTo(localURL);
    assertThat(Util.normalizeURL(badLocalURL)).isEqualTo(localURL);
  }

  @Test
  public void stripMagicPrefixTest() {
    assertThat(Util.stripMagicPrefix("abc\nxyz\n")).isEqualTo("abc\nxyz\n");
    assertThat(Util.stripMagicPrefix(")]}'\nxyz\n")).isEqualTo("xyz\n");
    assertThat(Util.stripMagicPrefix(")]}'xyz\n")).isEqualTo(")]}'xyz\n");
    assertThat(Util.stripMagicPrefix(")]}'\nxyz")).isEqualTo("xyz");
  }

  // TODO: use a mocked HTTP server to test:
  //     getHTTP getHTTPJsonObject getHTTPJsonArray getHTTPBase64Content

  @Test
  public void getDirNameTest() {
    String[] files = {"", "./d1/", "d1/d2/f1.c", "./d2/f2.c", "./d1" };
    String[] dirs = {null, ".", "d1/d2", "./d2", "."};
    for (int i = 0; i < files.length; i++) {
       assertThat(Util.getDirName(files[i])).isEqualTo(dirs[i]);
    }
  }

  @Test
  public void normalizeFilePathTest() {
    String[] files = {"", "./d1/", "d1/d2/f1.c", "d2/f2/", "d1" };
    String[] results = {"./", "./d1/", "./d1/d2/f1.c", "./d2/f2/", "./d1"};
    for (int i = 0; i < files.length; i++) {
       assertThat(Util.normalizedFilePath(files[i])).isEqualTo(results[i]);
    }
  }

  @Test
  public void normalizedDirPathTest() {
    String[] files = {"", "./d1/", "d1/d2/f1.c", "./d2/f2.c", "./d1" };
    String[] dirs = {null, ".", "./d1/d2", "./d2", "."};
    for (int i = 0; i < files.length; i++) {
       assertThat(Util.normalizedDirPath(files[i])).isEqualTo(dirs[i]);
    }
  }

  @Test
  public void parseBooleanTest() {
    String[] yesStrs = {"True", "1", "true", "TRUE", "yes"};
    String[] noStrs = {"", "False", "0", "false", "FALSE", "no", "other"};
    for (String s : yesStrs) {
       assertThat(Util.parseBoolean(s)).isEqualTo(true);
    }
    for (String s : noStrs) {
       assertThat(Util.parseBoolean(s)).isEqualTo(false);
    }
  }

  @Test
  public void sortTest() {
    // TODO: test Util.sort
  }

  @Test
  public void newJsonArrayFromStringSetTest() {
    // TODO: test Uril.newJsonArrayFromStringSet
  }
}
