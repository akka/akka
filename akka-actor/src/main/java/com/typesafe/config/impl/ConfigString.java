/**
 *   Copyright (C) 2011-2012 Typesafe Inc. <http://typesafe.com>
 */
package com.typesafe.config.impl;

import com.typesafe.config.ConfigOrigin;
import com.typesafe.config.ConfigValueType;

final class ConfigString extends AbstractConfigValue {

    final private String value;

    ConfigString(ConfigOrigin origin, String value) {
        super(origin);
        this.value = value;
    }

    @Override
    public ConfigValueType valueType() {
        return ConfigValueType.STRING;
    }

    @Override
    public String unwrapped() {
        return value;
    }

    @Override
    String transformToString() {
        return value;
    }

    @Override
    protected void render(StringBuilder sb, int indent, boolean formatted) {
        sb.append(ConfigImplUtil.renderJsonString(value));
    }

    @Override
    protected ConfigString newCopy(boolean ignoresFallbacks, ConfigOrigin origin) {
        return new ConfigString(origin, value);
    }
}
