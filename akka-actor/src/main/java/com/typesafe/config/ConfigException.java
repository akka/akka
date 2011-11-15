package com.typesafe.config;

/**
 * All exceptions thrown by the library are subclasses of ConfigException.
 */
public class ConfigException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    protected ConfigException(ConfigOrigin origin, String message,
            Throwable cause) {
        super(origin.description() + ": " + message, cause);
    }

    protected ConfigException(ConfigOrigin origin, String message) {
        this(origin.description() + ": " + message, null);
    }

    protected ConfigException(String message, Throwable cause) {
        super(message, cause);
    }

    protected ConfigException(String message) {
        this(message, null);
    }

    /**
     * Exception indicating that the type of a value does not match the type you
     * requested.
     *
     */
    public static class WrongType extends ConfigException {
        private static final long serialVersionUID = 1L;

        public WrongType(ConfigOrigin origin, String path, String expected,
                String actual,
                Throwable cause) {
            super(origin, path + " has type " + actual + " rather than "
                    + expected,
                    cause);
        }

        public WrongType(ConfigOrigin origin, String path, String expected,
                String actual) {
            this(origin, path, expected, actual, null);
        }

        WrongType(ConfigOrigin origin, String message, Throwable cause) {
            super(origin, message, cause);
        }

        WrongType(ConfigOrigin origin, String message) {
            this(origin, message, null);
        }
    }

    /**
     * Exception indicates that the setting was never set to anything, not even
     * null.
     */
    public static class Missing extends ConfigException {
        private static final long serialVersionUID = 1L;

        public Missing(String path, Throwable cause) {
            super("No configuration setting found for key '" + path + "'",
                    cause);
        }

        public Missing(String path) {
            this(path, null);
        }

        protected Missing(ConfigOrigin origin, String message, Throwable cause) {
            super(origin, message, cause);
        }

        protected Missing(ConfigOrigin origin, String message) {
            this(origin, message, null);
        }
    }

    /**
     * Exception indicates that the setting was treated as missing because it
     * was set to null.
     */
    public static class Null extends Missing {
        private static final long serialVersionUID = 1L;

        private static String makeMessage(String path, String expected) {
            if (expected != null) {
                return "Configuration key '" + path
                        + "' is set to null but expected " + expected;
            } else {
                return "Configuration key '" + path + "' is null";
            }
        }

        public Null(ConfigOrigin origin, String path, String expected,
                Throwable cause) {
            super(origin, makeMessage(path, expected), cause);
        }

        public Null(ConfigOrigin origin, String path, String expected) {
            this(origin, path, expected, null);
        }
    }

    /**
     * Exception indicating that a value was messed up, for example you may have
     * asked for a duration and the value can't be sensibly parsed as a
     * duration.
     *
     */
    public static class BadValue extends ConfigException {
        private static final long serialVersionUID = 1L;

        public BadValue(ConfigOrigin origin, String path, String message,
                Throwable cause) {
            super(origin, "Invalid value at '" + path + "': " + message, cause);
        }

        public BadValue(ConfigOrigin origin, String path, String message) {
            this(origin, path, message, null);
        }

        public BadValue(String path, String message, Throwable cause) {
            super("Invalid value at '" + path + "': " + message, cause);
        }

        public BadValue(String path, String message) {
            this(path, message, null);
        }
    }

    public static class BadPath extends ConfigException {
        private static final long serialVersionUID = 1L;

        public BadPath(ConfigOrigin origin, String path, String message,
                Throwable cause) {
            super(origin,
                    path != null ? ("Invalid path '" + path + "': " + message)
                            : message, cause);
        }

        public BadPath(ConfigOrigin origin, String path, String message) {
            this(origin, path, message, null);
        }

        public BadPath(String path, String message, Throwable cause) {
            super(path != null ? ("Invalid path '" + path + "': " + message)
                    : message, cause);
        }

        public BadPath(String path, String message) {
            this(path, message, null);
        }

        public BadPath(ConfigOrigin origin, String message) {
            this(origin, null, message);
        }
    }

    /**
     * Exception indicating that there's a bug in something or the runtime
     * environment is broken. This exception should never be handled; instead,
     * something should be fixed to keep the exception from occurring.
     *
     */
    public static class BugOrBroken extends ConfigException {
        private static final long serialVersionUID = 1L;

        public BugOrBroken(String message, Throwable cause) {
            super(message, cause);
        }

        public BugOrBroken(String message) {
            this(message, null);
        }
    }

    /**
     * Exception indicating that there was an IO error.
     *
     */
    public static class IO extends ConfigException {
        private static final long serialVersionUID = 1L;

        public IO(ConfigOrigin origin, String message, Throwable cause) {
            super(origin, message, cause);
        }

        public IO(ConfigOrigin origin, String message) {
            this(origin, message, null);
        }
    }

    /**
     * Exception indicating that there was a parse error.
     *
     */
    public static class Parse extends ConfigException {
        private static final long serialVersionUID = 1L;

        public Parse(ConfigOrigin origin, String message, Throwable cause) {
            super(origin, message, cause);
        }

        public Parse(ConfigOrigin origin, String message) {
            this(origin, message, null);
        }
    }

    /**
     * Exception indicating that you tried to use a function that requires
     * substitutions to be resolved, but substitutions have not been resolved.
     * This is always a bug in either application code or the library; it's
     * wrong to write a handler for this exception because you should be able to
     * fix the code to avoid it.
     */
    public static class NotResolved extends BugOrBroken {
        private static final long serialVersionUID = 1L;

        public NotResolved(String message, Throwable cause) {
            super(message, cause);
        }

        public NotResolved(String message) {
            this(message, null);
        }
    }
}
