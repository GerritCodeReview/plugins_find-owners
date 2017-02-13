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

import com.googlesource.gerrit.plugins.findowners.Util.Owner2Weights;
import com.googlesource.gerrit.plugins.findowners.Util.StringSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Keep owned files and count number of files at control level 1, 2, 3, etc.
 *
 * <p>A source file can be owned by multiple OWNERS file in its directory or
 *    parent directories. The owners listed in the lowest OWNERS file has
 *    level 1 control of that source file. The 2nd lowest OWNERS file has
 *    level 2 control, etc.
 * <p>An owner can own multiple source files at different control level.
 * <p>Each owner has an OwnerWeights object to keep
 *    (0) the set of owned files,
 *    (1) number of owned files with level 1 control,
 *    (2) number of owned files with level 2 control,
 *    (3) number of owned files with level 3 or higher control,
 */
class OwnerWeights {
  static class WeightComparator implements Comparator<String> {
    private Owner2Weights map;
    WeightComparator(Owner2Weights weights) {
      map = weights;
    }
    @Override
    public int compare(String k1, String k2) {
      OwnerWeights w1 = map.get(k1);
      OwnerWeights w2 = map.get(k2);
      int n1 = w2.countL1 - w1.countL1;
      int n2 = w2.countL2 - w1.countL2;
      int n3 = w2.countL3 - w1.countL3;
      return n1 != 0 ? n1 : (n2 != 0 ? n2 : (n3 != 0 ? n3 : k1.compareTo(k2)));
    }
  }

  StringSet files;  /** paths of owned files */
  int countL1;  /** number of files with control level 1 */
  int countL2;  /** number of files with control level 2 */
  int countL3;  /** number of files with control level 3 or more */

  /** Return file counters as a compact string. */
  String encodeLevelCounts() {
    return "[" + countL1 + "+" + countL2 + "+" + countL3 + "]";
  }

  private void init() {
    files = new StringSet();
    countL1 = 0;
    countL2 = 0;
    countL3 = 0;
  }

  OwnerWeights(String file, int level) {
    init();
    addFile(file, level);
  }

  OwnerWeights() {
    init();
  }

  void addFile(String path, int level) {
    // If a file is added multiple times,
    // it should be added with lowest level first.
    if (!files.contains(path)) {
      files.add(path);
      if (level <= 1) {
        countL1++;
      } else if (level <= 2) {
        countL2++;
      } else {
        countL3++;
      }
    }
  }

  /** Sort keys in weights map by control levels, and return keys. */
  static List<String> sortKeys(Owner2Weights weights) {
    ArrayList<String> keys = new ArrayList(weights.keySet());
    Collections.sort(keys, new WeightComparator(weights));
    return keys;
  }
}
