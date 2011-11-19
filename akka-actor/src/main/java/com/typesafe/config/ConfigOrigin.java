/**
 *   Copyright (C) 2011 Typesafe Inc. <http://typesafe.com>
 */
package com.typesafe.config;

/**
 * ConfigOrigin is used to track the origin (such as filename and line number)
 * of a ConfigValue or other object. The origin is used in error messages.
 */
public interface ConfigOrigin {
    public String description();
}
