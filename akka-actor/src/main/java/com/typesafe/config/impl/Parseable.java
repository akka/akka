/**
 *   Copyright (C) 2011 Typesafe Inc. <http://typesafe.com>
 */
package com.typesafe.config.impl;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FilterReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Iterator;
import java.util.Properties;

import com.typesafe.config.ConfigException;
import com.typesafe.config.ConfigIncludeContext;
import com.typesafe.config.ConfigObject;
import com.typesafe.config.ConfigOrigin;
import com.typesafe.config.ConfigParseOptions;
import com.typesafe.config.ConfigParseable;
import com.typesafe.config.ConfigSyntax;
import com.typesafe.config.ConfigValue;

/**
 * This is public but it's only for use by the config package; DO NOT TOUCH. The
 * point of this class is to avoid "propagating" each overload on
 * "thing which can be parsed" through multiple interfaces. Most interfaces can
 * have just one overload that takes a Parseable. Also it's used as an abstract
 * "resource handle" in the ConfigIncluder interface.
 */
public abstract class Parseable implements ConfigParseable {
    private ConfigIncludeContext includeContext;
    private ConfigParseOptions initialOptions;

    protected Parseable() {

    }

    private ConfigParseOptions fixupOptions(ConfigParseOptions baseOptions) {
        ConfigSyntax syntax = baseOptions.getSyntax();
        if (syntax == null) {
            syntax = guessSyntax();
        }
        if (syntax == null) {
            syntax = ConfigSyntax.CONF;
        }
        ConfigParseOptions modified = baseOptions.setSyntax(syntax);

        if (modified.getOriginDescription() == null)
            modified = modified.setOriginDescription(originDescription());

        modified = modified.appendIncluder(ConfigImpl.defaultIncluder());

        return modified;
    }

    protected void postConstruct(ConfigParseOptions baseOptions) {
        this.initialOptions = fixupOptions(baseOptions);

        this.includeContext = new ConfigIncludeContext() {
            @Override
            public ConfigParseable relativeTo(String filename) {
                return Parseable.this.relativeTo(filename);
            }
        };
    }

    // the general idea is that any work should be in here, not in the
    // constructor,
    // so that exceptions are thrown from the public parse() function and not
    // from the creation of the Parseable. Essentially this is a lazy field.
    // The parser should close the reader when it's done with it.
    // ALSO, IMPortANT: if the file or URL is not found, this must throw.
    // to support the "allow missing" feature.
    protected abstract Reader reader() throws IOException;

    ConfigSyntax guessSyntax() {
        return null;
    }

    ConfigParseable relativeTo(String filename) {
        return null;
    }

    ConfigIncludeContext includeContext() {
        return includeContext;
    }

    static AbstractConfigObject forceParsedToObject(ConfigValue value) {
        if (value instanceof AbstractConfigObject) {
            return (AbstractConfigObject) value;
        } else {
            throw new ConfigException.WrongType(value.origin(), "",
                    "object at file root", value.valueType().name());
        }
    }

    @Override
    public ConfigObject parse(ConfigParseOptions baseOptions) {
        return forceParsedToObject(parseValue(baseOptions));
    }

    AbstractConfigValue parseValue(ConfigParseOptions baseOptions) {
        // note that we are NOT using our "options" and "origin" fields,
        // but using the ones from the passed-in options. The idea is that
        // callers can get our original options and then parse with different
        // ones if they want.
        ConfigParseOptions options = fixupOptions(baseOptions);
        ConfigOrigin origin = new SimpleConfigOrigin(
                options.getOriginDescription());
        return parseValue(origin, options);
    }

    protected AbstractConfigValue parseValue(ConfigOrigin origin,
            ConfigParseOptions finalOptions) {
        try {
            Reader reader = reader();
            try {
                if (finalOptions.getSyntax() == ConfigSyntax.PROPERTIES) {
                    return PropertiesParser.parse(reader, origin);
                } else {
                    Iterator<Token> tokens = Tokenizer.tokenize(origin, reader,
                            finalOptions.getSyntax());
                    return Parser.parse(tokens, origin, finalOptions,
                            includeContext());
                }
            } finally {
                reader.close();
            }
        } catch (IOException e) {
            if (finalOptions.getAllowMissing()) {
                return SimpleConfigObject.emptyMissing(origin);
            } else {
                throw new ConfigException.IO(origin, e.getMessage(), e);
            }
        }
    }

    public ConfigObject parse() {
        return forceParsedToObject(parseValue(options()));
    }

    AbstractConfigValue parseValue() {
        return parseValue(options());
    }

    abstract String originDescription();

    @Override
    public URL url() {
        return null;
    }

    @Override
    public ConfigParseOptions options() {
        return initialOptions;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName();
    }

    private static ConfigSyntax syntaxFromExtension(String name) {
        if (name.endsWith(".json"))
            return ConfigSyntax.JSON;
        else if (name.endsWith(".conf"))
            return ConfigSyntax.CONF;
        else if (name.endsWith(".properties"))
            return ConfigSyntax.PROPERTIES;
        else
            return null;
    }

    private static Reader readerFromStream(InputStream input) {
        try {
            // well, this is messed up. If we aren't going to close
            // the passed-in InputStream then we have no way to
            // close these readers. So maybe we should not have an
            // InputStream version, only a Reader version.
            Reader reader = new InputStreamReader(input, "UTF-8");
            return new BufferedReader(reader);
        } catch (UnsupportedEncodingException e) {
            throw new ConfigException.BugOrBroken(
                    "Java runtime does not support UTF-8", e);
        }
    }

    private static Reader doNotClose(Reader input) {
        return new FilterReader(input) {
            @Override
            public void close() {
                // NOTHING.
            }
        };
    }

    static URL relativeTo(URL url, String filename) {
        // I'm guessing this completely fails on Windows, help wanted
        if (new File(filename).isAbsolute())
            return null;

        try {
            URI siblingURI = url.toURI();
            URI relative = new URI(filename);

            // this seems wrong, but it's documented that the last
            // element of the path in siblingURI gets stripped out,
            // so to get something in the same directory as
            // siblingURI we just call resolve().
            URL resolved = siblingURI.resolve(relative).toURL();

            return resolved;
        } catch (MalformedURLException e) {
            return null;
        } catch (URISyntaxException e) {
            return null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private final static class ParseableInputStream extends Parseable {
        final private InputStream input;

        ParseableInputStream(InputStream input, ConfigParseOptions options) {
            this.input = input;
            postConstruct(options);
        }

        @Override
        protected Reader reader() {
            return doNotClose(readerFromStream(input));
        }

        @Override
        String originDescription() {
            return "InputStream";
        }
    }

    /**
     * note that we will never close this stream; you have to do it when parsing
     * is complete.
     */
    public static Parseable newInputStream(InputStream input,
            ConfigParseOptions options) {
        return new ParseableInputStream(input, options);
    }

    private final static class ParseableReader extends Parseable {
        final private Reader reader;

        ParseableReader(Reader reader, ConfigParseOptions options) {
            this.reader = reader;
            postConstruct(options);
        }

        @Override
        protected Reader reader() {
            return reader;
        }

        @Override
        String originDescription() {
            return "Reader";
        }
    }

    /**
     * note that we will never close this reader; you have to do it when parsing
     * is complete.
     */
    public static Parseable newReader(Reader reader, ConfigParseOptions options) {
        return new ParseableReader(doNotClose(reader), options);
    }

    private final static class ParseableString extends Parseable {
        final private String input;

        ParseableString(String input, ConfigParseOptions options) {
            this.input = input;
            postConstruct(options);
        }

        @Override
        protected Reader reader() {
            return new StringReader(input);
        }

        @Override
        String originDescription() {
            return "String";
        }
    }

    public static Parseable newString(String input, ConfigParseOptions options) {
        return new ParseableString(input, options);
    }

    private final static class ParseableURL extends Parseable {
        final private URL input;

        ParseableURL(URL input, ConfigParseOptions options) {
            this.input = input;
            postConstruct(options);
        }

        @Override
        protected Reader reader() throws IOException {
            InputStream stream = input.openStream();
            return readerFromStream(stream);
        }

        @Override
        ConfigSyntax guessSyntax() {
            return syntaxFromExtension(input.getPath());
        }

        @Override
        ConfigParseable relativeTo(String filename) {
            URL url = relativeTo(input, filename);
            if (url == null)
                return null;
            return newURL(url, options()
                    .setOriginDescription(null));
        }

        @Override
        String originDescription() {
            return input.toExternalForm();
        }

        @Override
        public URL url() {
            return input;
        }

        @Override
        public String toString() {
            return getClass().getSimpleName() + "(" + input.toExternalForm()
                    + ")";
        }
    }

    public static Parseable newURL(URL input, ConfigParseOptions options) {
        return new ParseableURL(input, options);
    }

    private final static class ParseableFile extends Parseable {
        final private File input;

        ParseableFile(File input, ConfigParseOptions options) {
            this.input = input;
            postConstruct(options);
        }

        @Override
        protected Reader reader() throws IOException {
            InputStream stream = new FileInputStream(input);
            return readerFromStream(stream);
        }

        @Override
        ConfigSyntax guessSyntax() {
            return syntaxFromExtension(input.getName());
        }

        @Override
        ConfigParseable relativeTo(String filename) {
            try {
                URL url = relativeTo(input.toURI().toURL(), filename);
                if (url == null)
                    return null;
                return newURL(url, options().setOriginDescription(null));
            } catch (MalformedURLException e) {
                return null;
            }
        }

        @Override
        String originDescription() {
            return input.getPath();
        }

        @Override
        public URL url() {
            try {
                return input.toURI().toURL();
            } catch (MalformedURLException e) {
                return null;
            }
        }

        @Override
        public String toString() {
            return getClass().getSimpleName() + "(" + input.getPath() + ")";
        }
    }

    public static Parseable newFile(File input, ConfigParseOptions options) {
        return new ParseableFile(input, options);
    }

    private final static class ParseableResource extends Parseable {
        final private Class<?> klass;
        final private String resource;

        ParseableResource(Class<?> klass, String resource,
                ConfigParseOptions options) {
            this.klass = klass;
            this.resource = resource;
            postConstruct(options);
        }

        @Override
        protected Reader reader() throws IOException {
            InputStream stream = klass.getResourceAsStream(resource);
            if (stream == null) {
                throw new IOException("resource not found on classpath: "
                        + resource);
            }
            return readerFromStream(stream);
        }

        @Override
        ConfigSyntax guessSyntax() {
            return syntaxFromExtension(resource);
        }

        @Override
        ConfigParseable relativeTo(String filename) {
            // not using File.isAbsolute because resource paths always use '/'
            // (?)
            if (filename.startsWith("/"))
                return null;

            // here we want to build a new resource name and let
            // the class loader have it, rather than getting the
            // url with getResource() and relativizing to that url.
            // This is needed in case the class loader is going to
            // search a classpath.
            File parent = new File(resource).getParentFile();
            if (parent == null)
                return newResource(klass, "/" + filename, options()
                        .setOriginDescription(null));
            else
                return newResource(klass, new File(parent, filename).getPath(),
                        options().setOriginDescription(null));
        }

        @Override
        String originDescription() {
            return resource + " on classpath";
        }

        @Override
        public URL url() {
            return klass.getResource(resource);
        }

        @Override
        public String toString() {
            return getClass().getSimpleName() + "(" + resource + ","
                    + klass.getName()
                    + ")";
        }
    }

    public static Parseable newResource(Class<?> klass, String resource,
            ConfigParseOptions options) {
        return new ParseableResource(klass, resource, options);
    }

    private final static class ParseableProperties extends Parseable {
        final private Properties props;

        ParseableProperties(Properties props, ConfigParseOptions options) {
            this.props = props;
            postConstruct(options);
        }

        @Override
        protected Reader reader() throws IOException {
            throw new ConfigException.BugOrBroken(
                    "reader() should not be called on props");
        }

        @Override
        protected AbstractConfigObject parseValue(ConfigOrigin origin,
                ConfigParseOptions finalOptions) {
            return PropertiesParser.fromProperties(origin, props);
        }

        @Override
        ConfigSyntax guessSyntax() {
            return ConfigSyntax.PROPERTIES;
        }

        @Override
        String originDescription() {
            return "properties";
        }

        @Override
        public URL url() {
            return null;
        }

        @Override
        public String toString() {
            return getClass().getSimpleName() + "(" + props.size() + " props)";
        }
    }

    public static Parseable newProperties(Properties properties,
            ConfigParseOptions options) {
        return new ParseableProperties(properties, options);
    }
}
