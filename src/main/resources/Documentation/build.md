Build
=====

This plugin is built using Bazel.  To install Bazel, follow
the instruction on: https://www.bazel.io/versions/master/docs/install.html.

Two build modes are supported: Standalone and in Gerrit tree.
The standalone build mode is recommended, as this mode doesn't
require the Gerrit tree to exist locally.

### Build standalone

Clone the plugin:

```
  git clone https://gerrit.googlesource.com/plugins/@PLUGIN@
  cd @PLUGIN@
```

Issue the command:

```
  bazel build :find-owners
```

The output is created in

```
  bazel-genfiles/@PLUGIN@.jar
```

To package the plugin sources run:

```
  bazel build lib@PLUGIN@__plugin-src.jar
```

The output is created in:

```
  bazel-bin/lib@PLUGIN@__plugin-src.jar
```

To execute the tests run:

```
  bazel test //...
```

This project can be imported into the Eclipse IDE:

```
  ./tools/eclipse/project.sh
```

### Build in Gerrit tree


Clone or link this plugin to the plugins directory of Gerrit's source
tree.

```bash
git clone https://gerrit.googlesource.com/gerrit
git clone https://gerrit.googlesource.com/plugins/find-owners
cd gerrit/plugins
ln -s ../../find-owners .
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

```bash
bazel test --test_tag_filters=findowners //...
```

This project can be imported into the Eclipse IDE.
Add the plugin name to the `CUSTOM_PLUGINS` set in
Gerrit core in `tools/bzl/plugins.bzl`, and execute:

```bash
./tools/eclipse/project.py
```
