/**
 *   Copyright (C) 2011-2012 Typesafe Inc. <http://typesafe.com>
 */
package com.typesafe.config.impl;

import java.util.Stack;

import com.typesafe.config.ConfigException;

final class PathBuilder {
    // the keys are kept "backward" (top of stack is end of path)
    final private Stack<String> keys;
    private Path result;

    PathBuilder() {
        keys = new Stack<String>();
    }

    private void checkCanAppend() {
        if (result != null)
            throw new ConfigException.BugOrBroken(
                    "Adding to PathBuilder after getting result");
    }

    void appendKey(String key) {
        checkCanAppend();

        keys.push(key);
    }

    void appendPath(Path path) {
        checkCanAppend();

        String first = path.first();
        Path remainder = path.remainder();
        while (true) {
            keys.push(first);
            if (remainder != null) {
                first = remainder.first();
                remainder = remainder.remainder();
            } else {
                break;
            }
        }
    }

    Path result() {
        // note: if keys is empty, we want to return null, which is a valid
        // empty path
        if (result == null) {
            Path remainder = null;
            while (!keys.isEmpty()) {
                String key = keys.pop();
                remainder = new Path(key, remainder);
            }
            result = remainder;
        }
        return result;
    }
}
