load("//lib/prolog:prolog.bzl", "prolog_cafe_library")
load("//tools/bzl:junit.bzl", "junit_tests")
load(
    "//tools/bzl:plugin.bzl",
    "gerrit_plugin",
    "PLUGIN_DEPS",
    "PLUGIN_DEPS_NEVERLINK",
    "PLUGIN_TEST_DEPS",
)

MODULE = ["src/main/java/com/googlesource/gerrit/plugins/findowners/Module.java"]

java_library(
    name = "find-owners-lib",
    srcs = glob(
        ["src/main/java/**/*.java"],
        exclude = MODULE,
    ),
    deps = PLUGIN_DEPS_NEVERLINK + [
        "@prolog_runtime//jar:neverlink",
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
        "Gerrit-BatchModule: com.googlesource.gerrit.plugins.findowners.Module$PredicateModule",
        "Implementation-Title: Find-Owners plugin",
        "Implementation-URL: https://gerrit.googlesource.com/plugins/find-owners",
    ],
    resources = glob(["src/main/resources/**/*"]),
    deps = [
        ":find-owners-lib",
        ":find-owners-prolog-rules",
    ],
)

junit_tests(
    name = "findowners_tests",
    srcs = glob(["src/test/java/**/*.java"]),
    tags = ["findowners"],
    deps = PLUGIN_DEPS + PLUGIN_TEST_DEPS + [
        "@commons_io//jar",
        ":find-owners-lib",
        ":find-owners-prolog-rules",
        ":find-owners__plugin",
    ],
)
