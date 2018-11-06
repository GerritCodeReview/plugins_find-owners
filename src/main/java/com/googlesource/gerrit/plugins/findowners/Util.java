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

import com.google.common.collect.Ordering;
import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/** Utility classes and functions. */
class Util {
  /** Strips REST magic prefix line. */
  static String stripMagicPrefix(String data) {
    final String magic = ")]}'\n";
    return data.startsWith(magic) ? data.substring(magic.length()) : data;
  }

  static String getDirName(String path) {
    return new File(path).getParent();
  }

  // Assuming that path is a valid git file or directory path, add a "./" prefix,
  // so that "." can be used as the "root" parent directory.
  static String addDotPrefix(String path) {
    return path.startsWith("./") ? path : ("./" + path);
  }

  // Assuming that path is a valid git file or directory path,
  // get the parent directory path of the given file or directory.
  static String getParentDir(String path) {
    return new File(addDotPrefix(path)).getParent();
  }

  // Given valid git 'dir' path and user-input relative or absolute file 'path',
  // return a valid git file path with "./" prefix.
  static String normalizedDirFilePath(String dir, String path) throws IOException {
    // With an absolute path, starting with "/", current dir is ignored.
    if (path.startsWith("/")) {
      dir = ".";
    }
    return "." + new File("/" + dir + "/" + path).getCanonicalPath();
  }

  // Git repository file path cannot contain leading "/" or "./", or "/" at the end.
  static String gitRepoFilePath(String file) {
    if (file == null) {
      return "";
    }
    int last = file.length() - 1;
    while (last >= 0 && file.charAt(last) == '/') {
      --last;
    }
    int first = 0;
    while (first < last && file.charAt(first) == '/') {
      ++first;
    }
    file = file.substring(first, last + 1);
    if (file.startsWith("./")) {
      return gitRepoFilePath(file.substring(2));
    }
    return file;
  }

  static String normalizedRepoDirFilePath(String dir, String path) throws IOException {
    return gitRepoFilePath(normalizedDirFilePath(dir, path));
  }

  static boolean parseBoolean(String s) {
    return (s != null) && (s.equals("1") || s.equalsIgnoreCase("yes") || Boolean.parseBoolean(s));
  }

  static SortedMap<String, List<String>> makeSortedMap(Map<String, Set<String>> map) {
    SortedMap<String, List<String>> result = new TreeMap<>();
    for (String key : Ordering.natural().sortedCopy(map.keySet())) {
      result.put(key, Ordering.natural().sortedCopy(map.get(key)));
    }
    return result;
  }

  static void addToMap(Map<String, Set<String>> map, String key, String value) {
    if (map.get(key) == null) {
      map.put(key, new HashSet<>());
    }
    map.get(key).add(value);
  }
}
