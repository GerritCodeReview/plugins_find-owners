package com.googlesource.gerrit.plugins.findowners;

public enum EnforcementLevel {
  DISABLED, ENFORCE, WARN;
  static final String CONFIG_NAME = "enforceLevel";
}
