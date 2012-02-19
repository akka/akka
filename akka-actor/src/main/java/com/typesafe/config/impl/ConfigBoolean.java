/**
 *   Copyright (C) 2011-2012 Typesafe Inc. <http://typesafe.com>
 */
package com.typesafe.config.impl;

import com.typesafe.config.ConfigOrigin;
import com.typesafe.config.ConfigValueType;

final class ConfigBoolean extends AbstractConfigValue {

    private static final long serialVersionUID = 1L;

    final private boolean value;

    ConfigBoolean(ConfigOrigin origin, boolean value) {
        super(origin);
        this.value = value;
    }

    @Override
    public ConfigValueType valueType() {
        return ConfigValueType.BOOLEAN;
    }

    @Override
    public Boolean unwrapped() {
        return value;
    }

    @Override
    String transformToString() {
        return value ? "true" : "false";
    }

    @Override
    protected ConfigBoolean newCopy(boolean ignoresFallbacks, ConfigOrigin origin) {
        return new ConfigBoolean(origin, value);
    }
}
