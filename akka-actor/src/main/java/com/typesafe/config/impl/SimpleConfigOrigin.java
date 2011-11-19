/**
 *   Copyright (C) 2011 Typesafe Inc. <http://typesafe.com>
 */
package com.typesafe.config.impl;

import com.typesafe.config.ConfigOrigin;

final class SimpleConfigOrigin implements ConfigOrigin {

    final private String description;

    SimpleConfigOrigin(String description) {
        this.description = description;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public boolean equals(Object other) {
        if (other instanceof SimpleConfigOrigin) {
            return this.description
                    .equals(((SimpleConfigOrigin) other).description);
        } else {
            return false;
        }
    }

    @Override
    public int hashCode() {
        return description.hashCode();
    }

    @Override
    public String toString() {
        return "ConfigOrigin(" + description + ")";
    }
}
