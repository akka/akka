/**
 *   Copyright (C) 2011-2012 Typesafe Inc. <http://typesafe.com>
 */
package com.typesafe.config.impl;

import com.typesafe.config.ConfigOrigin;
import com.typesafe.config.ConfigValueType;

final class ConfigInt extends ConfigNumber {

    final private int value;

    ConfigInt(ConfigOrigin origin, int value, String originalText) {
        super(origin, originalText);
        this.value = value;
    }

    @Override
    public ConfigValueType valueType() {
        return ConfigValueType.NUMBER;
    }

    @Override
    public Integer unwrapped() {
        return value;
    }

    @Override
    String transformToString() {
        String s = super.transformToString();
        if (s == null)
            return Integer.toString(value);
        else
            return s;
    }

    @Override
    protected long longValue() {
        return value;
    }

    @Override
    protected double doubleValue() {
        return value;
    }

    @Override
    protected ConfigInt newCopy(boolean ignoresFallbacks, ConfigOrigin origin) {
        return new ConfigInt(origin, value, originalText);
    }
}
