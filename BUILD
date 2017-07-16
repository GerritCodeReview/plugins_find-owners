load("//lib/prolog:prolog.bzl", "prolog_cafe_library")
load("//tools/bzl:junit.bzl", "junit_tests")
load("//tools/bzl:plugin.bzl", "gerrit_plugin", "PLUGIN_DEPS", "PLUGIN_TEST_DEPS")

java_library(
  name = 'find-owners-lib',
  srcs = glob(['src/main/java/**/*.java']),
  deps = PLUGIN_DEPS + ['@prolog_runtime//jar'],
)

prolog_cafe_library(
  name = 'find-owners-prolog-rules',
  srcs = glob(['src/main/prolog/*.pl']),
  deps = [
    ':find-owners-lib',
    '//gerrit-server:prolog-common',
  ],
)

gerrit_plugin(
  name = 'find-owners',
  srcs = glob(['src/main/java/**/Module.java']),
  resources = glob(['src/main/resources/**/*']),
  manifest_entries = [
    'Gerrit-PluginName: find-owners',
    'Gerrit-ReloadMode: restart',
    'Gerrit-Module: com.googlesource.gerrit.plugins.findowners.Module',
    'Implementation-Title: Find-Owners plugin',
    'Implementation-URL: https://gerrit.googlesource.com/plugins/find-owners',
  ],
  deps = [
    ':find-owners-lib',
    ':find-owners-prolog-rules',
  ],
)

junit_tests(
  name = 'findowners_tests',
  srcs = glob(['src/test/java/**/*.java']),
  # resources = glob(['src/test/resources/**/*']),
  tags = ['findowners'],
  deps = PLUGIN_DEPS + PLUGIN_TEST_DEPS + [
    ':find-owners-lib',
    ':find-owners-prolog-rules',
  ],
)
