package com.typesafe.config.impl;

import com.typesafe.config.ConfigOrigin;
import com.typesafe.config.ConfigValueType;

final class ConfigBoolean extends AbstractConfigValue {

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
}
