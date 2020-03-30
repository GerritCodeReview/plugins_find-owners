load("//lib/prolog:prolog.bzl", "prolog_cafe_library")
load("//tools/bzl:junit.bzl", "junit_tests")
load(
    "//tools/bzl:plugin.bzl",
    "PLUGIN_DEPS_NEVERLINK",
    "PLUGIN_TEST_DEPS",
    "gerrit_plugin",
)

MODULE = ["src/main/java/com/googlesource/gerrit/plugins/findowners/Module.java"]

prolog_cafe_library(
    name = "find_owners_prolog_rules",
    srcs = glob(["src/main/prolog/*.pl"]),
    deps = PLUGIN_DEPS_NEVERLINK + [":find_owners"],
)

FIND_OWNERS_SRCS = glob(["src/main/java/**/*.java"])

FIND_OWNERS_DEPS = ["@prolog-runtime//jar:neverlink"]

java_library(
    name = "find_owners",
    srcs = FIND_OWNERS_SRCS,
    resources = glob(["src/main/resources/**/*"]),
    deps = FIND_OWNERS_DEPS + PLUGIN_DEPS_NEVERLINK,
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
    deps = [":find_owners", ":find_owners_prolog_rules"],
)

# Libraries used by all find-owners junit tests.
FIND_OWNERS_TESTS_DEPS = PLUGIN_TEST_DEPS

FIND_OWNERS_TESTS_DEPS = FIND_OWNERS_TESTS_DEPS + [
    ":find_owners",
]

# Base find_owners_IT library depends on these Gerrit IT acceptance libraries.
FIND_OWNERS_IT_DEPS = [
    "@commons-io//jar",
]

# All IT tests need the find_owners_IT and find_owners_IT libraries.
FIND_OWNERS_IT_TESTS_DEPS = FIND_OWNERS_IT_DEPS + [
    ":find_owners_IT",
    ":find_owners_junit",
    ":find_owners_prolog_rules",
]

# Utilities for junit tests.
java_library(
    name = "find_owners_junit",
    testonly = 1,
    srcs = glob(["src/test/java/**/Watcher.java"]),
    deps = FIND_OWNERS_TESTS_DEPS,
)

# Base class and utilities for IT tests.
java_library(
    name = "find_owners_IT",
    testonly = 1,
    srcs = MODULE + glob(["src/test/java/**/FindOwners.java"]),
    deps = FIND_OWNERS_TESTS_DEPS + FIND_OWNERS_IT_DEPS,
)

# Simple fast junit non-IT tests.
junit_tests(
    name = "findowners_junit_tests",
    size = "small",
    srcs = glob(["src/test/java/**/*Test.java"]),
    deps = FIND_OWNERS_TESTS_DEPS + [":find_owners_junit"],
)

junit_tests(
    name = "findowners_IT_tests",
    size = "large",
    srcs = glob(["src/test/java/**/*IT.java"]),
    deps = FIND_OWNERS_IT_TESTS_DEPS + FIND_OWNERS_TESTS_DEPS,
)
