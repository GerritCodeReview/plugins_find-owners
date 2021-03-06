load("//lib/prolog:prolog.bzl", "prolog_cafe_library")
load("//tools/bzl:junit.bzl", "junit_tests")
load(
    "//tools/bzl:plugin.bzl",
    "PLUGIN_DEPS",
    "PLUGIN_DEPS_NEVERLINK",
    "PLUGIN_TEST_DEPS",
    "gerrit_plugin",
)

MODULE = ["src/main/java/com/googlesource/gerrit/plugins/findowners/Module.java"]

java_library(
    name = "find-owners-lib",
    srcs = glob(
        ["src/main/java/**/*.java"],
        exclude = MODULE,
    ),
    deps = PLUGIN_DEPS_NEVERLINK + [
        "@prolog-runtime//jar:neverlink",
    ],
)

prolog_cafe_library(
    name = "find-owners-prolog-rules",
    srcs = glob(["src/main/prolog/*.pl"]),
    deps = PLUGIN_DEPS_NEVERLINK + [
        ":find-owners-lib",
    ],
)

gerrit_plugin(
    name = "find-owners",
    srcs = MODULE,
    manifest_entries = [
        "Gerrit-PluginName: find-owners",
        "Gerrit-ReloadMode: restart",
        "Gerrit-Module: com.googlesource.gerrit.plugins.findowners.Module",
        "Gerrit-BatchModule: com.googlesource.gerrit.plugins.findowners.PredicateModule",
        "Implementation-Title: Find-Owners plugin",
        "Implementation-URL: https://gerrit.googlesource.com/plugins/find-owners",
    ],
    resources = glob(["src/main/resources/**/*"]),
    deps = [
        ":find-owners-lib",
        ":find-owners-prolog-rules",
    ],
)

java_library(
    name = "find-owners-junit",
    testonly = 1,
    srcs = glob(["src/test/java/**/Watcher.java"]),
    deps = PLUGIN_TEST_DEPS,
)

java_library(
    name = "find-owners-IT",
    testonly = 1,
    srcs = glob(["src/test/java/**/FindOwners.java"]),
    deps = PLUGIN_TEST_DEPS + [
        ":find-owners-junit",
        ":find-owners-lib",
        ":find-owners__plugin",
    ],
)

# Separate fast junit tests from slow interation (IT) tests.
junit_tests(
    name = "findowners_junit_tests",
    srcs = glob(["src/test/java/**/*Test.java"]),
    tags = ["findowners"],
    deps = PLUGIN_DEPS + PLUGIN_TEST_DEPS + [
        "@commons-io//jar",
        ":find-owners-junit",
        ":find-owners-lib",
    ],
)

junit_tests(
    name = "findowners_IT_tests",
    srcs = glob(["src/test/java/**/*IT.java"]),
    tags = ["findowners"],
    deps = PLUGIN_DEPS + PLUGIN_TEST_DEPS + [
        "@commons-io//jar",
        ":find-owners-IT",
        ":find-owners-junit",
        ":find-owners-lib",
        ":find-owners-prolog-rules",
        ":find-owners__plugin",
    ],
)
