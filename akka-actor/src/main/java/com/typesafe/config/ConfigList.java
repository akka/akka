package com.typesafe.config;

import java.util.List;

/**
 * A list (aka array) value corresponding to ConfigValueType.LIST or JSON's
 * "[1,2,3]" value. Implements java.util.List<ConfigValue> so you can use it
 * like a regular Java list.
 * 
 */
public interface ConfigList extends List<ConfigValue>, ConfigValue {

    /**
     * Recursively unwraps the list, returning a list of plain Java values such
     * as Integer or String or whatever is in the list.
     */
    @Override
    List<Object> unwrapped();

}
