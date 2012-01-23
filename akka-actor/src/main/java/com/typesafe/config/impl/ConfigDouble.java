/**
 *   Copyright (C) 2011-2012 Typesafe Inc. <http://typesafe.com>
 */
package com.typesafe.config.impl;

import com.typesafe.config.ConfigOrigin;
import com.typesafe.config.ConfigValueType;

final class ConfigDouble extends ConfigNumber {

    final private double value;

    ConfigDouble(ConfigOrigin origin, double value, String originalText) {
        super(origin, originalText);
        this.value = value;
    }

    @Override
    public ConfigValueType valueType() {
        return ConfigValueType.NUMBER;
    }

    @Override
    public Double unwrapped() {
        return value;
    }

    @Override
    String transformToString() {
        String s = super.transformToString();
        if (s == null)
            return Double.toString(value);
        else
            return s;
    }

    @Override
    protected long longValue() {
        return (long) value;
    }

    @Override
    protected double doubleValue() {
        return value;
    }

    @Override
    protected ConfigDouble newCopy(boolean ignoresFallbacks, ConfigOrigin origin) {
        return new ConfigDouble(origin, value, originalText);
    }
}
