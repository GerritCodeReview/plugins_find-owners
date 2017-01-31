Build
=====

This plugin is built using Bazel.
Only the Gerrit in-tree build is supported.

Clone or link this plugin to the plugins directory of Gerrit's source
tree.

```bash
git clone https://gerrit.googlesource.com/gerrit
git clone https://gerrit.googlesource.com/plugins/find-owners
cd gerrit/plugins
ln -s ../../find-owners .
```

Put the external dependency Bazel build file into the Gerrit /plugins
directory, replacing the existing empty one.

```bash
cd gerrit/plugins
rm external_plugin_deps.bzl
ln -s find-owners/external_plugin_deps.bzl .
```

From Gerrit source tree issue the command:

```bash
bazel build plugins/find-owners
```

The output is created in

```bash
bazel-genfiles/plugins/find-owners/find-owners.jar
```

To execute the tests run:

```bash
bazel test plugins/find-owners:findowners_tests
```

or filtering using the comma separated tags:

````bash
bazel test --test_tag_filters=findowners //...
````

This project can be imported into the Eclipse IDE.
Add the plugin name to the `CUSTOM_PLUGINS` set in
Gerrit core in `tools/bzl/plugins.bzl`, and execute:

```bash
./tools/eclipse/project.py
```
