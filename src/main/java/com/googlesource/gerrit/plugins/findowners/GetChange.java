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

import com.google.gerrit.extensions.annotations.Export;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.findowners.Util.String2String;
import java.io.IOException;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Serves the HTTP GET /change/id request. */
@Export("/change/*")
@Singleton
class GetChange extends HttpServlet {
  private static final Logger log = LoggerFactory.getLogger(GetChange.class);

  private static final String MAGIC_PREFIX = ")]}'";  // Gerrit REST specific

  private int getChangeId(String url) {
    // Expect url=".../plugins/find-owners/change/<digits>"
    final Pattern patChangeId =
      Pattern.compile("^.*/plugins/" + Config.PLUGIN_NAME
                      + "/change/([0-9]+)/?$");
    Matcher m = patChangeId.matcher(url);
    return m.find() ? Integer.parseInt(m.group(1)) : 0;
  }

  private String2String parseParameters(HttpServletRequest req) {
    Map<String, String[]> map = req.getParameterMap();
    String2String params = new String2String();
    for (Map.Entry<String, String[]> entry : map.entrySet()) {
      String[] value = entry.getValue();
      // Use only the last definition, if there are multiple.
      params.put(entry.getKey(), value[value.length - 1]);
    }
    return params;
  }

  protected void doGet(HttpServletRequest req, HttpServletResponse res)
      throws IOException {
    String reqURL = req.getRequestURL().toString();
    // e.g. http://localhost:8082/plugins/find-owners/change/36
    String localAddress = req.getLocalAddr(); // e.g. 100.108.228.206
    int localPort = req.getLocalPort(); // e.g. 8080, not client port number
    String2String params = parseParameters(req);
    String url = "http://" + localAddress + ":" + localPort + "/";
    // TODO: recognize pp=0 parameter and Accept HTTP request header
    // to output compact JSON.
    int changeId = getChangeId(reqURL);
    if (Config.traceServerMsg()) {
      String paramsDump = "";
      for (Map.Entry<String, String> entry : params.entrySet()) {
        paramsDump += " " + entry.getKey() + "=" + entry.getValue();
      }
      log.info("goGet reqURL=" + reqURL
               + ", address=" + localAddress + ", port=" + localPort
               + ", changeId=" + changeId + ", params:" + paramsDump);
    }
    createResponse(url, params, changeId, res);
  }

  private void createErrorResponse(HttpServletResponse res,
                                   String msg) throws IOException {
    res.setContentType("text/plain");
    res.getWriter().println("Error: " + msg);
  }

  private void createResponse(
      String url, String2String params, int changeId,
      HttpServletResponse res) throws IOException {
    res.setCharacterEncoding("UTF-8");
    if (changeId > 0) {
      Action finder = new Action(url, null);
      JsonObject obj = finder.getChangeData(changeId, params);
      if (null != obj.get("error")) {
        createErrorResponse(res, obj.get("error").getAsString());
      } else {
        // Current prototype always returns pretty-printed JSON.
        // TODO: recognize HTTP Accept-Encoding request header "gzip",
        // to gzip compress the response.
        Gson gs = new GsonBuilder()
            .setPrettyPrinting().disableHtmlEscaping().create();
        res.setContentType("application/json");
        res.getWriter().println(MAGIC_PREFIX);
        res.getWriter().println(gs.toJson(obj));
      }
    } else {
      createErrorResponse(res,
        "Missing change number.\n"
        + "Usage: <baseURL>/plugins/"
        + Config.PLUGIN_NAME + "/change/<changeId>");
    }
  }
}
