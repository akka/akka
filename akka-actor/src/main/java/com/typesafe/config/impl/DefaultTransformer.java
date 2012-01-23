/**
 *   Copyright (C) 2011-2012 Typesafe Inc. <http://typesafe.com>
 */
package com.typesafe.config.impl;

import com.typesafe.config.ConfigValueType;

/**
 * Default automatic type transformations.
 */
final class DefaultTransformer {

    static AbstractConfigValue transform(AbstractConfigValue value,
            ConfigValueType requested) {
        if (value.valueType() == ConfigValueType.STRING) {
            String s = (String) value.unwrapped();
            switch (requested) {
            case NUMBER:
                try {
                    Long v = Long.parseLong(s);
                    return new ConfigLong(value.origin(), v, s);
                } catch (NumberFormatException e) {
                    // try Double
                }
                try {
                    Double v = Double.parseDouble(s);
                    return new ConfigDouble(value.origin(), v, s);
                } catch (NumberFormatException e) {
                    // oh well.
                }
                break;
            case NULL:
                if (s.equals("null"))
                    return new ConfigNull(value.origin());
                break;
            case BOOLEAN:
                if (s.equals("true") || s.equals("yes") || s.equals("on")) {
                    return new ConfigBoolean(value.origin(), true);
                } else if (s.equals("false") || s.equals("no")
                        || s.equals("off")) {
                    return new ConfigBoolean(value.origin(), false);
                }
                break;
            case LIST:
                // can't go STRING to LIST automatically
                break;
            case OBJECT:
                // can't go STRING to OBJECT automatically
                break;
            case STRING:
                // no-op STRING to STRING
                break;
            }
        } else if (requested == ConfigValueType.STRING) {
            // if we converted null to string here, then you wouldn't properly
            // get a missing-value error if you tried to get a null value
            // as a string.
            switch (value.valueType()) {
            case NUMBER: // FALL THROUGH
            case BOOLEAN:
                return new ConfigString(value.origin(),
                        value.transformToString());
            case NULL:
                // want to be sure this throws instead of returning "null" as a
                // string
                break;
            case OBJECT:
                // no OBJECT to STRING automatically
                break;
            case LIST:
                // no LIST to STRING automatically
                break;
            case STRING:
                // no-op STRING to STRING
                break;
            }
        }

        return value;
    }
}
