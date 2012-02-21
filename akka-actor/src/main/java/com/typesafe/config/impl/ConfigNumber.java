/**
 *   Copyright (C) 2011-2012 Typesafe Inc. <http://typesafe.com>
 */
package com.typesafe.config.impl;

import com.typesafe.config.ConfigException;
import com.typesafe.config.ConfigOrigin;

abstract class ConfigNumber extends AbstractConfigValue {

    private static final long serialVersionUID = 1L;

    // This is so when we concatenate a number into a string (say it appears in
    // a sentence) we always have it exactly as the person typed it into the
    // config file. It's purely cosmetic; equals/hashCode don't consider this
    // for example.
    final protected String originalText;

    protected ConfigNumber(ConfigOrigin origin, String originalText) {
        super(origin);
        this.originalText = originalText;
    }

    @Override
    public abstract Number unwrapped();

    @Override
    String transformToString() {
        return originalText;
    }

    int intValueRangeChecked(String path) {
        long l = longValue();
        if (l < Integer.MIN_VALUE || l > Integer.MAX_VALUE) {
            throw new ConfigException.WrongType(origin(), path, "32-bit integer",
                    "out-of-range value " + l);
        }
        return (int) l;
    }

    protected abstract long longValue();

    protected abstract double doubleValue();

    private boolean isWhole() {
        long asLong = longValue();
        return asLong == doubleValue();
    }

    @Override
    protected boolean canEqual(Object other) {
        return other instanceof ConfigNumber;
    }

    @Override
    public boolean equals(Object other) {
        // note that "origin" is deliberately NOT part of equality
        if (canEqual(other)) {
            ConfigNumber n = (ConfigNumber) other;
            if (isWhole()) {
                return n.isWhole() && this.longValue() == n.longValue();
            } else {
                return (!n.isWhole()) && this.doubleValue() == n.doubleValue();
            }
        } else {
            return false;
        }
    }

    @Override
    public int hashCode() {
        // note that "origin" is deliberately NOT part of equality

        // this matches what standard Long.hashCode and Double.hashCode
        // do, though I don't think it really matters.
        long asLong;
        if (isWhole()) {
            asLong = longValue();
        } else {
            asLong = Double.doubleToLongBits(doubleValue());
        }
        return (int) (asLong ^ (asLong >>> 32));
    }

    static ConfigNumber newNumber(ConfigOrigin origin, long number,
            String originalText) {
        if (number <= Integer.MAX_VALUE && number >= Integer.MIN_VALUE)
            return new ConfigInt(origin, (int) number, originalText);
        else
            return new ConfigLong(origin, number, originalText);
    }

    static ConfigNumber newNumber(ConfigOrigin origin, double number,
            String originalText) {
        long asLong = (long) number;
        if (asLong == number) {
            return newNumber(origin, asLong, originalText);
        } else {
            return new ConfigDouble(origin, number, originalText);
        }
    }
}
