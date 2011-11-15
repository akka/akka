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
}
