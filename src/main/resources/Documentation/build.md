Build
=====

This plugin is built with Buck.
Right now, it can only be built in the Gerrit tree.

### Build in Gerrit tree

Clone or link this plugin to the plugins directory of Gerrit's source tree,
and issue the command:

```bash
  buck build plugins/@PLUGIN@
```

The output is created in

```bash
  buck-out/gen/plugins/@PLUGIN@/@PLUGIN@.jar
  buck-out/gen/plugins/@PLUGIN@/@PLUGIN@-prolog-rules-abi.jar
  buck-out/gen/plugins/@PLUGIN@/gitective-core-abi.jar
```

### Untested yet, to import into the Eclipse IDE:

```bash
  ./tools/eclipse/project.py
```
