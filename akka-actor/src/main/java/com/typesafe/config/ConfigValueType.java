package com.typesafe.config;

/**
 * The type of a configuration value. Value types follow the JSON type schema.
 */
public enum ConfigValueType {
    OBJECT, LIST, NUMBER, BOOLEAN, NULL, STRING
}
