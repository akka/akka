package com.typesafe.config.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigException;
import com.typesafe.config.ConfigList;
import com.typesafe.config.ConfigMergeable;
import com.typesafe.config.ConfigObject;
import com.typesafe.config.ConfigOrigin;
import com.typesafe.config.ConfigResolveOptions;
import com.typesafe.config.ConfigValue;
import com.typesafe.config.ConfigValueType;

/**
 * One thing to keep in mind in the future: if any Collection-like APIs are
 * added here, including iterators or size() or anything, then we'd have to
 * grapple with whether ConfigNull values are "in" the Config (probably not) and
 * we'd probably want to make the collection look flat - not like a tree. So the
 * key-value pairs would be all the tree's leaf values, in a big flat list with
 * their full paths.
 */
class SimpleConfig implements Config {

    AbstractConfigObject object;

    SimpleConfig(AbstractConfigObject object) {
        this.object = object;
    }

    @Override
    public AbstractConfigObject toObject() {
        return object;
    }

    @Override
    public ConfigOrigin origin() {
        return object.origin();
    }

    /**
     * Returns a version of this config that implements the ConfigRoot
     * interface.
     *
     * @return a config root
     */
    RootConfig asRoot(Path rootPath) {
        return asRoot(object, rootPath);
    }

    // RootConfig overrides this to avoid a new object on unchanged path.
    protected RootConfig asRoot(AbstractConfigObject underlying,
            Path newRootPath) {
        return new RootConfig(underlying, newRootPath);
    }

    protected AbstractConfigObject resolvedObject(ConfigResolveOptions options) {
        AbstractConfigValue resolved = SubstitutionResolver.resolve(object,
                object, options);
        return (AbstractConfigObject) resolved;
    }

    @Override
    public boolean hasPath(String pathExpression) {
        Path path = Path.newPath(pathExpression);
        ConfigValue peeked = object.peekPath(path, null, 0, null);
        return peeked != null && peeked.valueType() != ConfigValueType.NULL;
    }

    @Override
    public boolean isEmpty() {
        return object.isEmpty();
    }

    static private AbstractConfigValue find(AbstractConfigObject self,
            String pathExpression, ConfigValueType expected, String originalPath) {
        Path path = Path.newPath(pathExpression);
        return find(self, path, expected, originalPath);
    }

    static private AbstractConfigValue findKey(AbstractConfigObject self,
            String key, ConfigValueType expected, String originalPath) {
        AbstractConfigValue v = self.peek(key);
        if (v == null)
            throw new ConfigException.Missing(originalPath);

        if (expected != null)
            v = DefaultTransformer.transform(v, expected);

        if (v.valueType() == ConfigValueType.NULL)
            throw new ConfigException.Null(v.origin(), originalPath,
                    expected != null ? expected.name() : null);
        else if (expected != null && v.valueType() != expected)
            throw new ConfigException.WrongType(v.origin(), originalPath,
                    expected.name(), v.valueType().name());
        else
            return v;
    }

    static private AbstractConfigValue find(AbstractConfigObject self,
            Path path, ConfigValueType expected, String originalPath) {
        String key = path.first();
        Path next = path.remainder();
        if (next == null) {
            return findKey(self, key, expected, originalPath);
        } else {
            AbstractConfigObject o = (AbstractConfigObject) findKey(self, key,
                    ConfigValueType.OBJECT, originalPath);
            assert (o != null); // missing was supposed to throw
            return find(o, next, expected, originalPath);
        }
    }

    AbstractConfigValue find(String pathExpression, ConfigValueType expected,
            String originalPath) {
        return find(object, pathExpression, expected, originalPath);
    }

    @Override
    public AbstractConfigValue getValue(String path) {
        return find(path, null, path);
    }

    @Override
    public boolean getBoolean(String path) {
        ConfigValue v = find(path, ConfigValueType.BOOLEAN, path);
        return (Boolean) v.unwrapped();
    }

    @Override
    public Number getNumber(String path) {
        ConfigValue v = find(path, ConfigValueType.NUMBER, path);
        return (Number) v.unwrapped();
    }

    @Override
    public int getInt(String path) {
        return getNumber(path).intValue();
    }

    @Override
    public long getLong(String path) {
        return getNumber(path).longValue();
    }

    @Override
    public double getDouble(String path) {
        return getNumber(path).doubleValue();
    }

    @Override
    public String getString(String path) {
        ConfigValue v = find(path, ConfigValueType.STRING, path);
        return (String) v.unwrapped();
    }

    @Override
    public ConfigList getList(String path) {
        AbstractConfigValue v = find(path, ConfigValueType.LIST, path);
        return (ConfigList) v;
    }

    @Override
    public AbstractConfigObject getObject(String path) {
        AbstractConfigObject obj = (AbstractConfigObject) find(path,
                ConfigValueType.OBJECT, path);
        return obj;
    }

    @Override
    public SimpleConfig getConfig(String path) {
        return getObject(path).toConfig();
    }

    @Override
    public Object getAnyRef(String path) {
        ConfigValue v = find(path, null, path);
        return v.unwrapped();
    }

    @Override
    public Long getMemorySizeInBytes(String path) {
        Long size = null;
        try {
            size = getLong(path);
        } catch (ConfigException.WrongType e) {
            ConfigValue v = find(path, ConfigValueType.STRING, path);
            size = parseMemorySizeInBytes((String) v.unwrapped(),
                    v.origin(), path);
        }
        return size;
    }

    @Override
    public Long getMilliseconds(String path) {
        long ns = getNanoseconds(path);
        long ms = TimeUnit.NANOSECONDS.toMillis(ns);
        return ms;
    }

    @Override
    public Long getNanoseconds(String path) {
        Long ns = null;
        try {
            ns = TimeUnit.MILLISECONDS.toNanos(getLong(path));
        } catch (ConfigException.WrongType e) {
            ConfigValue v = find(path, ConfigValueType.STRING, path);
            ns = parseDuration((String) v.unwrapped(), v.origin(), path);
        }
        return ns;
    }

    @SuppressWarnings("unchecked")
    private <T> List<T> getHomogeneousUnwrappedList(String path,
            ConfigValueType expected) {
        List<T> l = new ArrayList<T>();
        List<? extends ConfigValue> list = getList(path);
        for (ConfigValue cv : list) {
            // variance would be nice, but stupid cast will do
            AbstractConfigValue v = (AbstractConfigValue) cv;
            if (expected != null) {
                v = DefaultTransformer.transform(v, expected);
            }
            if (v.valueType() != expected)
                throw new ConfigException.WrongType(v.origin(), path,
                        "list of " + expected.name(), "list of "
                                + v.valueType().name());
            l.add((T) v.unwrapped());
        }
        return l;
    }

    @Override
    public List<Boolean> getBooleanList(String path) {
        return getHomogeneousUnwrappedList(path, ConfigValueType.BOOLEAN);
    }

    @Override
    public List<Number> getNumberList(String path) {
        return getHomogeneousUnwrappedList(path, ConfigValueType.NUMBER);
    }

    @Override
    public List<Integer> getIntList(String path) {
        List<Integer> l = new ArrayList<Integer>();
        List<Number> numbers = getNumberList(path);
        for (Number n : numbers) {
            l.add(n.intValue());
        }
        return l;
    }

    @Override
    public List<Long> getLongList(String path) {
        List<Long> l = new ArrayList<Long>();
        List<Number> numbers = getNumberList(path);
        for (Number n : numbers) {
            l.add(n.longValue());
        }
        return l;
    }

    @Override
    public List<Double> getDoubleList(String path) {
        List<Double> l = new ArrayList<Double>();
        List<Number> numbers = getNumberList(path);
        for (Number n : numbers) {
            l.add(n.doubleValue());
        }
        return l;
    }

    @Override
    public List<String> getStringList(String path) {
        return getHomogeneousUnwrappedList(path, ConfigValueType.STRING);
    }

    @SuppressWarnings("unchecked")
    private <T extends ConfigValue> List<T> getHomogeneousWrappedList(
            String path, ConfigValueType expected) {
        List<T> l = new ArrayList<T>();
        List<? extends ConfigValue> list = getList(path);
        for (ConfigValue cv : list) {
            // variance would be nice, but stupid cast will do
            AbstractConfigValue v = (AbstractConfigValue) cv;
            if (expected != null) {
                v = DefaultTransformer.transform(v, expected);
            }
            if (v.valueType() != expected)
                throw new ConfigException.WrongType(v.origin(), path,
                        "list of " + expected.name(), "list of "
                                + v.valueType().name());
            l.add((T) v);
        }
        return l;
    }

    @Override
    public List<ConfigObject> getObjectList(String path) {
        return getHomogeneousWrappedList(path, ConfigValueType.OBJECT);
    }

    @Override
    public List<? extends Config> getConfigList(String path) {
        List<ConfigObject> objects = getObjectList(path);
        List<Config> l = new ArrayList<Config>();
        for (ConfigObject o : objects) {
            l.add(o.toConfig());
        }
        return l;
    }

    @Override
    public List<? extends Object> getAnyRefList(String path) {
        List<Object> l = new ArrayList<Object>();
        List<? extends ConfigValue> list = getList(path);
        for (ConfigValue v : list) {
            l.add(v.unwrapped());
        }
        return l;
    }

    @Override
    public List<Long> getMemorySizeInBytesList(String path) {
        List<Long> l = new ArrayList<Long>();
        List<? extends ConfigValue> list = getList(path);
        for (ConfigValue v : list) {
            if (v.valueType() == ConfigValueType.NUMBER) {
                l.add(((Number) v.unwrapped()).longValue());
            } else if (v.valueType() == ConfigValueType.STRING) {
                String s = (String) v.unwrapped();
                Long n = parseMemorySizeInBytes(s, v.origin(), path);
                l.add(n);
            } else {
                throw new ConfigException.WrongType(v.origin(), path,
                        "memory size string or number of bytes", v.valueType()
                                .name());
            }
        }
        return l;
    }

    @Override
    public List<Long> getMillisecondsList(String path) {
        List<Long> nanos = getNanosecondsList(path);
        List<Long> l = new ArrayList<Long>();
        for (Long n : nanos) {
            l.add(TimeUnit.NANOSECONDS.toMillis(n));
        }
        return l;
    }

    @Override
    public List<Long> getNanosecondsList(String path) {
        List<Long> l = new ArrayList<Long>();
        List<? extends ConfigValue> list = getList(path);
        for (ConfigValue v : list) {
            if (v.valueType() == ConfigValueType.NUMBER) {
                l.add(TimeUnit.MILLISECONDS.toNanos(((Number) v.unwrapped())
                        .longValue()));
            } else if (v.valueType() == ConfigValueType.STRING) {
                String s = (String) v.unwrapped();
                Long n = parseDuration(s, v.origin(), path);
                l.add(n);
            } else {
                throw new ConfigException.WrongType(v.origin(), path,
                        "duration string or number of nanoseconds", v
                                .valueType().name());
            }
        }
        return l;
    }

    @Override
    public AbstractConfigObject toValue() {
        return object;
    }

    @Override
    public SimpleConfig withFallback(ConfigMergeable other) {
        // this can return "this" if the withFallback doesn't need a new
        // ConfigObject
        return object.withFallback(other).toConfig();
    }

    @Override
    public SimpleConfig withFallbacks(ConfigMergeable... others) {
        // this can return "this" if the withFallbacks doesn't need a new
        // ConfigObject
        return object.withFallbacks(others).toConfig();
    }

    @Override
    public final boolean equals(Object other) {
        if (other instanceof SimpleConfig) {
            return object.equals(((SimpleConfig) other).object);
        } else {
            return false;
        }
    }

    @Override
    public final int hashCode() {
        // we do the "41*" just so our hash code won't match that of the
        // underlying object. there's no real reason it can't match, but
        // making it not match might catch some kinds of bug.
        return 41 * object.hashCode();
    }

    @Override
    public String toString() {
        return "Config(" + object.toString() + ")";
    }

    private static String getUnits(String s) {
        int i = s.length() - 1;
        while (i >= 0) {
            char c = s.charAt(i);
            if (!Character.isLetter(c))
                break;
            i -= 1;
        }
        return s.substring(i + 1);
    }

    /**
     * Parses a duration string. If no units are specified in the string, it is
     * assumed to be in milliseconds. The returned duration is in nanoseconds.
     * The purpose of this function is to implement the duration-related methods
     * in the ConfigObject interface.
     *
     * @param input
     *            the string to parse
     * @param originForException
     *            origin of the value being parsed
     * @param pathForException
     *            path to include in exceptions
     * @return duration in nanoseconds
     * @throws ConfigException
     *             if string is invalid
     */
    public static long parseDuration(String input,
            ConfigOrigin originForException, String pathForException) {
        String s = ConfigUtil.unicodeTrim(input);
        String originalUnitString = getUnits(s);
        String unitString = originalUnitString;
        String numberString = ConfigUtil.unicodeTrim(s.substring(0, s.length()
                - unitString.length()));
        TimeUnit units = null;

        // this would be caught later anyway, but the error message
        // is more helpful if we check it here.
        if (numberString.length() == 0)
            throw new ConfigException.BadValue(originForException,
                    pathForException, "No number in duration value '" + input
                            + "'");

        if (unitString.length() > 2 && !unitString.endsWith("s"))
            unitString = unitString + "s";

        // note that this is deliberately case-sensitive
        if (unitString.equals("") || unitString.equals("ms")
                || unitString.equals("milliseconds")) {
            units = TimeUnit.MILLISECONDS;
        } else if (unitString.equals("us") || unitString.equals("microseconds")) {
            units = TimeUnit.MICROSECONDS;
        } else if (unitString.equals("ns") || unitString.equals("nanoseconds")) {
            units = TimeUnit.NANOSECONDS;
        } else if (unitString.equals("d") || unitString.equals("days")) {
            units = TimeUnit.DAYS;
        } else if (unitString.equals("h") || unitString.equals("hours")) {
            units = TimeUnit.HOURS;
        } else if (unitString.equals("s") || unitString.equals("seconds")) {
            units = TimeUnit.SECONDS;
        } else if (unitString.equals("m") || unitString.equals("minutes")) {
            units = TimeUnit.MINUTES;
        } else {
            throw new ConfigException.BadValue(originForException,
                    pathForException, "Could not parse time unit '"
                            + originalUnitString
                            + "' (try ns, us, ms, s, m, d)");
        }

        try {
            // if the string is purely digits, parse as an integer to avoid
            // possible precision loss;
            // otherwise as a double.
            if (numberString.matches("[0-9]+")) {
                return units.toNanos(Long.parseLong(numberString));
            } else {
                long nanosInUnit = units.toNanos(1);
                return (long) (Double.parseDouble(numberString) * nanosInUnit);
            }
        } catch (NumberFormatException e) {
            throw new ConfigException.BadValue(originForException,
                    pathForException, "Could not parse duration number '"
                            + numberString + "'");
        }
    }

    private static enum MemoryUnit {
        BYTES(1), KILOBYTES(1024), MEGABYTES(1024 * 1024), GIGABYTES(
                1024 * 1024 * 1024), TERABYTES(1024 * 1024 * 1024 * 1024);

        int bytes;

        MemoryUnit(int bytes) {
            this.bytes = bytes;
        }
    }

    /**
     * Parses a memory-size string. If no units are specified in the string, it
     * is assumed to be in bytes. The returned value is in bytes. The purpose of
     * this function is to implement the memory-size-related methods in the
     * ConfigObject interface. The units parsed are interpreted as powers of
     * two, that is, the convention for memory rather than the convention for
     * disk space.
     *
     * @param input
     *            the string to parse
     * @param originForException
     *            origin of the value being parsed
     * @param pathForException
     *            path to include in exceptions
     * @return size in bytes
     * @throws ConfigException
     *             if string is invalid
     */
    public static long parseMemorySizeInBytes(String input,
            ConfigOrigin originForException, String pathForException) {
        String s = ConfigUtil.unicodeTrim(input);
        String unitStringMaybePlural = getUnits(s);
        String unitString;
        if (unitStringMaybePlural.endsWith("s"))
            unitString = unitStringMaybePlural.substring(0,
                    unitStringMaybePlural.length() - 1);
        else
            unitString = unitStringMaybePlural;
        String unitStringLower = unitString.toLowerCase();
        String numberString = ConfigUtil.unicodeTrim(s.substring(0, s.length()
                - unitStringMaybePlural.length()));

        // this would be caught later anyway, but the error message
        // is more helpful if we check it here.
        if (numberString.length() == 0)
            throw new ConfigException.BadValue(originForException,
                    pathForException, "No number in size-in-bytes value '"
                            + input + "'");

        MemoryUnit units = null;

        // the short abbreviations are case-insensitive but you can't write the
        // long form words in all caps.
        if (unitString.equals("") || unitStringLower.equals("b")
                || unitString.equals("byte")) {
            units = MemoryUnit.BYTES;
        } else if (unitStringLower.equals("k") || unitString.equals("kilobyte")) {
            units = MemoryUnit.KILOBYTES;
        } else if (unitStringLower.equals("m") || unitString.equals("megabyte")) {
            units = MemoryUnit.MEGABYTES;
        } else if (unitStringLower.equals("g") || unitString.equals("gigabyte")) {
            units = MemoryUnit.GIGABYTES;
        } else if (unitStringLower.equals("t") || unitString.equals("terabyte")) {
            units = MemoryUnit.TERABYTES;
        } else {
            throw new ConfigException.BadValue(originForException,
                    pathForException, "Could not parse size unit '"
                            + unitStringMaybePlural + "' (try b, k, m, g, t)");
        }

        try {
            // if the string is purely digits, parse as an integer to avoid
            // possible precision loss;
            // otherwise as a double.
            if (numberString.matches("[0-9]+")) {
                return Long.parseLong(numberString) * units.bytes;
            } else {
                return (long) (Double.parseDouble(numberString) * units.bytes);
            }
        } catch (NumberFormatException e) {
            throw new ConfigException.BadValue(originForException,
                    pathForException, "Could not parse memory size number '"
                            + numberString + "'");
        }
    }
}
