/**
 *   Copyright (C) 2011-2012 Typesafe Inc. <http://typesafe.com>
 */
package com.typesafe.config.impl;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import com.typesafe.config.ConfigException;
import com.typesafe.config.ConfigOrigin;

final class PropertiesParser {
    static AbstractConfigObject parse(Reader reader,
            ConfigOrigin origin) throws IOException {
        Properties props = new Properties();
        props.load(reader);
        return fromProperties(origin, props);
    }

    static String lastElement(String path) {
        int i = path.lastIndexOf('.');
        if (i < 0)
            return path;
        else
            return path.substring(i + 1);
    }

    static String exceptLastElement(String path) {
        int i = path.lastIndexOf('.');
        if (i < 0)
            return null;
        else
            return path.substring(0, i);
    }

    static Path pathFromPropertyKey(String key) {
        String last = lastElement(key);
        String exceptLast = exceptLastElement(key);
        Path path = new Path(last, null);
        while (exceptLast != null) {
            last = lastElement(exceptLast);
            exceptLast = exceptLastElement(exceptLast);
            path = new Path(last, path);
        }
        return path;
    }

    static AbstractConfigObject fromProperties(ConfigOrigin origin,
            Properties props) {
        Map<Path, Object> pathMap = new HashMap<Path, Object>();
        for (Map.Entry<Object, Object> entry : props.entrySet()) {
            Object key = entry.getKey();
            if (key instanceof String) {
                Path path = pathFromPropertyKey((String) key);
                pathMap.put(path, entry.getValue());
            }
        }
        return fromPathMap(origin, pathMap, true /* from properties */);
    }

    static AbstractConfigObject fromPathMap(ConfigOrigin origin,
            Map<?, ?> pathExpressionMap) {
        Map<Path, Object> pathMap = new HashMap<Path, Object>();
        for (Map.Entry<?, ?> entry : pathExpressionMap.entrySet()) {
            Object keyObj = entry.getKey();
            if (!(keyObj instanceof String)) {
                throw new ConfigException.BugOrBroken(
                        "Map has a non-string as a key, expecting a path expression as a String");
            }
            Path path = Path.newPath((String) keyObj);
            pathMap.put(path, entry.getValue());
        }
        return fromPathMap(origin, pathMap, false /* from properties */);
    }

    private static AbstractConfigObject fromPathMap(ConfigOrigin origin,
            Map<Path, Object> pathMap, boolean convertedFromProperties) {
        /*
         * First, build a list of paths that will have values, either string or
         * object values.
         */
        Set<Path> scopePaths = new HashSet<Path>();
        Set<Path> valuePaths = new HashSet<Path>();
        for (Path path : pathMap.keySet()) {
            // add value's path
            valuePaths.add(path);

            // all parent paths are objects
            Path next = path.parent();
            while (next != null) {
                scopePaths.add(next);
                next = next.parent();
            }
        }

        if (convertedFromProperties) {
            /*
             * If any string values are also objects containing other values,
             * drop those string values - objects "win".
             */
            valuePaths.removeAll(scopePaths);
        } else {
            /* If we didn't start out as properties, then this is an error. */
            for (Path path : valuePaths) {
                if (scopePaths.contains(path)) {
                    throw new ConfigException.BugOrBroken(
                            "In the map, path '"
                                    + path.render()
                                    + "' occurs as both the parent object of a value and as a value. "
                                    + "Because Map has no defined ordering, this is a broken situation.");
                }
            }
        }

        /*
         * Create maps for the object-valued values.
         */
        Map<String, AbstractConfigValue> root = new HashMap<String, AbstractConfigValue>();
        Map<Path, Map<String, AbstractConfigValue>> scopes = new HashMap<Path, Map<String, AbstractConfigValue>>();

        for (Path path : scopePaths) {
            Map<String, AbstractConfigValue> scope = new HashMap<String, AbstractConfigValue>();
            scopes.put(path, scope);
        }

        /* Store string values in the associated scope maps */
        for (Path path : valuePaths) {
            Path parentPath = path.parent();
            Map<String, AbstractConfigValue> parent = parentPath != null ? scopes
                    .get(parentPath) : root;

            String last = path.last();
            Object rawValue = pathMap.get(path);
            AbstractConfigValue value;
            if (convertedFromProperties) {
                value = new ConfigString(origin, (String) rawValue);
            } else {
                value = ConfigImpl.fromAnyRef(pathMap.get(path), origin,
                        FromMapMode.KEYS_ARE_PATHS);
            }
            parent.put(last, value);
        }

        /*
         * Make a list of scope paths from longest to shortest, so children go
         * before parents.
         */
        List<Path> sortedScopePaths = new ArrayList<Path>();
        sortedScopePaths.addAll(scopePaths);
        // sort descending by length
        Collections.sort(sortedScopePaths, new Comparator<Path>() {
            @Override
            public int compare(Path a, Path b) {
                // Path.length() is O(n) so in theory this sucks
                // but in practice we can make Path precompute length
                // if it ever matters.
                return b.length() - a.length();
            }
        });

        /*
         * Create ConfigObject for each scope map, working from children to
         * parents to avoid modifying any already-created ConfigObject. This is
         * where we need the sorted list.
         */
        for (Path scopePath : sortedScopePaths) {
            Map<String, AbstractConfigValue> scope = scopes.get(scopePath);

            Path parentPath = scopePath.parent();
            Map<String, AbstractConfigValue> parent = parentPath != null ? scopes
                    .get(parentPath) : root;

            AbstractConfigObject o = new SimpleConfigObject(origin, scope,
                    ResolveStatus.RESOLVED, false /* ignoresFallbacks */);
            parent.put(scopePath.last(), o);
        }

        // return root config object
        return new SimpleConfigObject(origin, root, ResolveStatus.RESOLVED,
                false /* ignoresFallbacks */);
    }
}
