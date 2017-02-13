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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.googlesource.gerrit.plugins.findowners.Util.Owner2Weights;
import com.googlesource.gerrit.plugins.findowners.Util.String2StringSet;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Scanner;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Utility classes and functions. */
class Util {
  private static final Logger log = LoggerFactory.getLogger(Util.class);

  static class Owner2Weights extends HashMap<String, OwnerWeights> {}
  static class String2Integer extends HashMap<String, Integer> {}
  static class String2String extends HashMap<String, String> {}
  static class String2StringSet extends HashMap<String, StringSet> {}
  static class StringSet extends HashSet<String> {}

  /** Removes extra "/" in url. */
  static String normalizeURL(String url) {
    return url.replace(":///", "://"); // Assuming only one ":///" in url.
  }

  /** Strips REST magic prefix line. */
  static String stripMagicPrefix(String data) {
    final String magic = ")]}'\n";
    return data.startsWith(magic) ? data.substring(magic.length()) : data;
  }

  /** Issues Gerrit REST API GET command. */
  private static String getHTTP(String urlString, boolean ignoreIOException) {
    urlString = normalizeURL(urlString);
    try {
      URLConnection conn = new URL(urlString).openConnection();
      Scanner scanner = new Scanner(conn.getInputStream());
      String responseBody = scanner.useDelimiter("\\A").next();
      return stripMagicPrefix(responseBody);
    } catch (MalformedURLException e) {
      log.error("Malformed URL: " + urlString);
    } catch (IOException e) {
      // Not an error if looking for an OWNERS file
      // or revision info in the "refs/meta/config" branch.
      if (!ignoreIOException) {
        log.error("IOException URL: " + urlString);
      }
    }
    return null;
  }

  /** Issues Gerrit REST API GET; converts result to JsonObject. */
  static JsonObject getHTTPJsonObject(String url, boolean ignoreIOException) {
    String data = getHTTP(url, ignoreIOException);
    return (null == data) ? new JsonObject()
        : new JsonParser().parse(data).getAsJsonObject();
  }

  /** Issues Gerrit REST API GET; converts result to JsonArray. */
  static JsonArray getHTTPJsonArray(String url) {
    String data = getHTTP(url, false);
    return (null == data) ?  new JsonArray()
        : new JsonParser().parse(data).getAsJsonArray();
  }

  /** Issues Gerrit REST API GET; decodes base64 content. */
  static String getHTTPBase64Content(String url) {
    String data = getHTTP(url, true);
    return (null == data) ? "" : new String(Base64.getDecoder().decode(data));
  }

  static String getDirName(String path) {
    return new File(path).getParent();
  }

  static String normalizedFilePath(String path) {
    return path.startsWith("./") ? path : ("./" + path);
  }

  static String normalizedDirPath(String path) {
    return new File(normalizedFilePath(path)).getParent();
  }

  static boolean parseBoolean(String s) {
    return (null != s) && (s.equals("1")
        || s.equalsIgnoreCase("yes") || Boolean.parseBoolean(s));
  }

  static List<String> sort(Set<String> names) {
    List<String> list = new ArrayList<String>(names);
    Collections.sort(list);
    return list;
  }

  static JsonArray newJsonArrayFromStrings(Collection<String> names) {
    JsonArray result = new JsonArray();
    List<String> list = new ArrayList<String>(names);
    Collections.sort(list);
    for (String name : list) {
      result.add(name);
    }
    return result;
  }
}
