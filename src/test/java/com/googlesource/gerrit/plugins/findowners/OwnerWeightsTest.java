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
import com.googlesource.gerrit.plugins.findowners.OwnerWeights.WeightComparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Test OwnerWeights class */
@RunWith(JUnit4.class)
public class OwnerWeightsTest {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  @Rule public Watcher watcher = new Watcher(logger);

  private static OwnerWeights createOwnerWeights(int[] counts) {
    OwnerWeights obj = new OwnerWeights();
    for (int i = 0; i < counts.length; i++) {
      for (int j = 0; j < counts[i]; j++) {
        obj.addFile("f" + i + "_" + j, i + 1);
      }
    }
    return obj;
  }

  @Test
  public void addFileTest() {
    OwnerWeights obj = new OwnerWeights();
    assertThat(obj.encodeLevelCounts()).isEqualTo("[0+0+0]");
    obj = new OwnerWeights("tmp", 0);
    assertThat(obj.encodeLevelCounts()).isEqualTo("[1+0+0]");
    obj = new OwnerWeights("tmp", 1);
    assertThat(obj.encodeLevelCounts()).isEqualTo("[1+0+0]");
    obj.addFile("tmp", 2);
    assertThat(obj.encodeLevelCounts()).isEqualTo("[1+0+0]");
    obj.addFile("tmp2", 2);
    assertThat(obj.encodeLevelCounts()).isEqualTo("[1+1+0]");
    obj.addFile("tmp3", 3);
    assertThat(obj.encodeLevelCounts()).isEqualTo("[1+1+1]");
    obj.addFile("tmp4", 4);
    assertThat(obj.encodeLevelCounts()).isEqualTo("[1+1+2]");
  }

  @Test
  public void sortKeysTest() {
    int[] c000 = {0, 0, 0};
    int[] c021 = {0, 2, 1};
    int[] c023 = {0, 2, 1, 1, 1};
    int[] c111 = {1, 1, 1};
    OwnerWeights objX1 = createOwnerWeights(c000);
    OwnerWeights objX2 = createOwnerWeights(c021);
    OwnerWeights objX3 = createOwnerWeights(c023);
    OwnerWeights objX4 = createOwnerWeights(c111);
    assertThat(objX1.encodeLevelCounts()).isEqualTo("[0+0+0]");
    assertThat(objX2.encodeLevelCounts()).isEqualTo("[0+2+1]");
    assertThat(objX3.encodeLevelCounts()).isEqualTo("[0+2+3]");
    assertThat(objX4.encodeLevelCounts()).isEqualTo("[1+1+1]");
    Map<String, OwnerWeights> map = new HashMap<>();
    map.put("objX1", objX1);
    map.put("objX2", objX2);
    map.put("objX3", objX3);
    map.put("objX4", objX4);
    map.put("objX0", objX4);
    List<String> keys = OwnerWeights.sortKeys(map);
    assertThat(keys.get(0)).isEqualTo("objX0");
    assertThat(keys.get(1)).isEqualTo("objX4");
    assertThat(keys.get(2)).isEqualTo("objX3");
    assertThat(keys.get(3)).isEqualTo("objX2");
    assertThat(keys.get(4)).isEqualTo("objX1");
    WeightComparator comp = new WeightComparator(map);
    // comp.compare(A,B) < 0, if A has order before B
    // comp.compare(A,B) > 0, if A has order after B
    assertThat(comp.compare("objX1", "objX2")).isGreaterThan(0);
    assertThat(comp.compare("objX3", "objX2")).isLessThan(0);
    assertThat(comp.compare("objX3", "objX4")).isGreaterThan(0);
    assertThat(comp.compare("objX3", "objX3")).isEqualTo(0);
    assertThat(comp.compare("objX4", "objX0")).isGreaterThan(0);
    assertThat(comp.compare("objX0", "objX4")).isLessThan(0);
  }
}
