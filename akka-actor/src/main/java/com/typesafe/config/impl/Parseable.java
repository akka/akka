/**
 *   Copyright (C) 2011-2012 Typesafe Inc. <http://typesafe.com>
 */
package com.typesafe.config.impl;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
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
import java.util.Enumeration;
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
    private ConfigOrigin initialOrigin;

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

        if (initialOptions.getOriginDescription() != null)
            initialOrigin = SimpleConfigOrigin.newSimple(initialOptions.getOriginDescription());
        else
            initialOrigin = createOrigin();
    }

    // the general idea is that any work should be in here, not in the
    // constructor,
    // so that exceptions are thrown from the public parse() function and not
    // from the creation of the Parseable. Essentially this is a lazy field.
    // The parser should close the reader when it's done with it.
    // ALSO, IMPORTANT: if the file or URL is not found, this must throw.
    // to support the "allow missing" feature.
    protected abstract Reader reader() throws IOException;

    protected static void trace(String message) {
        if (ConfigImpl.traceLoadsEnabled()) {
            ConfigImpl.trace(message);
        }
    }

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
            throw new ConfigException.WrongType(value.origin(), "", "object at file root", value
                    .valueType().name());
        }
    }

    @Override
    public ConfigObject parse(ConfigParseOptions baseOptions) {
        return forceParsedToObject(parseValue(baseOptions));
    }

    final AbstractConfigValue parseValue(ConfigParseOptions baseOptions) {
        // note that we are NOT using our "initialOptions",
        // but using the ones from the passed-in options. The idea is that
        // callers can get our original options and then parse with different
        // ones if they want.
        ConfigParseOptions options = fixupOptions(baseOptions);

        // passed-in options can override origin
        ConfigOrigin origin;
        if (options.getOriginDescription() != null)
            origin = SimpleConfigOrigin.newSimple(options.getOriginDescription());
        else
            origin = initialOrigin;
        return parseValue(origin, options);
    }

    final private AbstractConfigValue parseValue(ConfigOrigin origin,
            ConfigParseOptions finalOptions) {
        try {
            return rawParseValue(origin, finalOptions);
        } catch (IOException e) {
            if (finalOptions.getAllowMissing()) {
                return SimpleConfigObject.emptyMissing(origin);
            } else {
                throw new ConfigException.IO(origin, e.getMessage(), e);
            }
        }
    }

    // this is parseValue without post-processing the IOException or handling
    // options.getAllowMissing()
    protected AbstractConfigValue rawParseValue(ConfigOrigin origin, ConfigParseOptions finalOptions)
            throws IOException {
        Reader reader = reader();
        try {
            return rawParseValue(reader, origin, finalOptions);
        } finally {
            reader.close();
        }
    }

    protected AbstractConfigValue rawParseValue(Reader reader, ConfigOrigin origin,
            ConfigParseOptions finalOptions) throws IOException {
        if (finalOptions.getSyntax() == ConfigSyntax.PROPERTIES) {
            return PropertiesParser.parse(reader, origin);
        } else {
            Iterator<Token> tokens = Tokenizer.tokenize(origin, reader, finalOptions.getSyntax());
            return Parser.parse(tokens, origin, finalOptions, includeContext());
        }
    }

    public ConfigObject parse() {
        return forceParsedToObject(parseValue(options()));
    }

    AbstractConfigValue parseValue() {
        return parseValue(options());
    }

    @Override
    public final ConfigOrigin origin() {
        return initialOrigin;
    }

    protected abstract ConfigOrigin createOrigin();

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
            throw new ConfigException.BugOrBroken("Java runtime does not support UTF-8", e);
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

    static File relativeTo(File file, String filename) {
        File child = new File(filename);

        if (child.isAbsolute())
            return null;

        File parent = file.getParentFile();

        if (parent == null)
            return null;
        else
            return new File(parent, filename);
    }

    // this is a parseable that doesn't exist and just throws when you try to
    // parse it
    private final static class ParseableNotFound extends Parseable {
        final private String what;
        final private String message;

        ParseableNotFound(String what, String message, ConfigParseOptions options) {
            this.what = what;
            this.message = message;
            postConstruct(options);
        }

        @Override
        protected Reader reader() throws IOException {
            throw new FileNotFoundException(message);
        }

        @Override
        protected ConfigOrigin createOrigin() {
            return SimpleConfigOrigin.newSimple(what);
        }
    }

    public static Parseable newNotFound(String whatNotFound, String message,
            ConfigParseOptions options) {
        return new ParseableNotFound(whatNotFound, message, options);
    }

    private final static class ParseableReader extends Parseable {
        final private Reader reader;

        ParseableReader(Reader reader, ConfigParseOptions options) {
            this.reader = reader;
            postConstruct(options);
        }

        @Override
        protected Reader reader() {
            if (ConfigImpl.traceLoadsEnabled())
                trace("Loading config from reader " + reader);
            return reader;
        }

        @Override
        protected ConfigOrigin createOrigin() {
            return SimpleConfigOrigin.newSimple("Reader");
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
            if (ConfigImpl.traceLoadsEnabled())
                trace("Loading config from a String " + input);
            return new StringReader(input);
        }

        @Override
        protected ConfigOrigin createOrigin() {
            return SimpleConfigOrigin.newSimple("String");
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
            if (ConfigImpl.traceLoadsEnabled())
                trace("Loading config from a URL: " + input.toExternalForm());
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
            return newURL(url, options().setOriginDescription(null));
        }

        @Override
        protected ConfigOrigin createOrigin() {
            return SimpleConfigOrigin.newURL(input);
        }

        @Override
        public String toString() {
            return getClass().getSimpleName() + "(" + input.toExternalForm() + ")";
        }
    }

    public static Parseable newURL(URL input, ConfigParseOptions options) {
        // we want file: URLs and files to always behave the same, so switch
        // to a file if it's a file: URL
        if (input.getProtocol().equals("file")) {
            return newFile(ConfigImplUtil.urlToFile(input), options);
        } else {
            return new ParseableURL(input, options);
        }
    }

    private final static class ParseableFile extends Parseable {
        final private File input;

        ParseableFile(File input, ConfigParseOptions options) {
            this.input = input;
            postConstruct(options);
        }

        @Override
        protected Reader reader() throws IOException {
            if (ConfigImpl.traceLoadsEnabled())
                trace("Loading config from a file: " + input);
            InputStream stream = new FileInputStream(input);
            return readerFromStream(stream);
        }

        @Override
        ConfigSyntax guessSyntax() {
            return syntaxFromExtension(input.getName());
        }

        @Override
        ConfigParseable relativeTo(String filename) {
            File sibling;
            if ((new File(filename)).isAbsolute()) {
                sibling = new File(filename);
            } else {
                // this may return null
                sibling = relativeTo(input, filename);
            }
            if (sibling == null)
                return null;
            if (sibling.exists()) {
                return newFile(sibling, options().setOriginDescription(null));
            } else {
                // fall back to classpath; we treat the "filename" as absolute
                // (don't add a package name in front),
                // if it starts with "/" then remove the "/", for consistency
                // with ParseableResources.relativeTo
                String resource = filename;
                if (filename.startsWith("/"))
                    resource = filename.substring(1);
                return newResources(this.getClass().getClassLoader(), resource, options()
                        .setOriginDescription(null));
            }
        }

        @Override
        protected ConfigOrigin createOrigin() {
            return SimpleConfigOrigin.newFile(input.getPath());
        }

        @Override
        public String toString() {
            return getClass().getSimpleName() + "(" + input.getPath() + ")";
        }
    }

    public static Parseable newFile(File input, ConfigParseOptions options) {
        return new ParseableFile(input, options);
    }

    private final static class ParseableResources extends Parseable {
        final private ClassLoader loader;
        final private String resource;

        ParseableResources(ClassLoader loader, String resource, ConfigParseOptions options) {
            this.loader = loader;
            this.resource = resource;
            postConstruct(options);
        }

        @Override
        protected Reader reader() throws IOException {
            throw new ConfigException.BugOrBroken("reader() should not be called on resources");
        }

        @Override
        protected AbstractConfigObject rawParseValue(ConfigOrigin origin,
                ConfigParseOptions finalOptions) throws IOException {
            Enumeration<URL> e = loader.getResources(resource);
            if (!e.hasMoreElements()) {
                if (ConfigImpl.traceLoadsEnabled())
                    trace("Loading config from class loader " + loader
                            + " but there were no resources called " + resource);
                throw new IOException("resource not found on classpath: " + resource);
            }
            AbstractConfigObject merged = SimpleConfigObject.empty(origin);
            while (e.hasMoreElements()) {
                URL url = e.nextElement();

                if (ConfigImpl.traceLoadsEnabled())
                    trace("Loading config from URL " + url.toExternalForm() + " from class loader "
                            + loader);

                ConfigOrigin elementOrigin = ((SimpleConfigOrigin) origin).addURL(url);

                AbstractConfigValue v;

                // it's tempting to use ParseableURL here but it would be wrong
                // because the wrong relativeTo() would be used for includes.
                InputStream stream = url.openStream();
                try {
                    Reader reader = readerFromStream(stream);
                    stream = null; // reader now owns it
                    try {
                        // parse in "raw" mode which will throw any IOException
                        // from here.
                        v = rawParseValue(reader, elementOrigin, finalOptions);
                    } finally {
                        reader.close();
                    }
                } finally {
                    // stream is null if the reader owns it
                    if (stream != null)
                        stream.close();
                }

                merged = merged.withFallback(v);
            }

            return merged;
        }

        @Override
        ConfigSyntax guessSyntax() {
            return syntaxFromExtension(resource);
        }

        static String parent(String resource) {
            // the "resource" is not supposed to begin with a "/"
            // because it's supposed to be the raw resource
            // (ClassLoader#getResource), not the
            // resource "syntax" (Class#getResource)
            int i = resource.lastIndexOf('/');
            if (i < 0) {
                return null;
            } else {
                return resource.substring(0, i);
            }
        }

        @Override
        ConfigParseable relativeTo(String sibling) {
            if (sibling.startsWith("/")) {
                // if it starts with "/" then don't make it relative to
                // the including resource
                return newResources(loader, sibling.substring(1),
                        options().setOriginDescription(null));
            } else {
                // here we want to build a new resource name and let
                // the class loader have it, rather than getting the
                // url with getResource() and relativizing to that url.
                // This is needed in case the class loader is going to
                // search a classpath.
                String parent = parent(resource);
                if (parent == null)
                    return newResources(loader, sibling, options().setOriginDescription(null));
                else
                    return newResources(loader, parent + "/" + sibling, options()
                            .setOriginDescription(null));
            }
        }

        @Override
        protected ConfigOrigin createOrigin() {
            return SimpleConfigOrigin.newResource(resource);
        }

        @Override
        public String toString() {
            return getClass().getSimpleName() + "(" + resource + ","
                    + loader.getClass().getSimpleName() + ")";
        }
    }

    public static Parseable newResources(Class<?> klass, String resource, ConfigParseOptions options) {
        return newResources(klass.getClassLoader(), convertResourceName(klass, resource), options);
    }

    // this function is supposed to emulate the difference
    // between Class.getResource and ClassLoader.getResource
    // (unfortunately there doesn't seem to be public API for it).
    // We're using it because the Class API is more limited,
    // for example it lacks getResources(). So we want to be able to
    // use ClassLoader directly.
    private static String convertResourceName(Class<?> klass, String resource) {
        if (resource.startsWith("/")) {
            // "absolute" resource, chop the slash
            return resource.substring(1);
        } else {
            String className = klass.getName();
            int i = className.lastIndexOf('.');
            if (i < 0) {
                // no package
                return resource;
            } else {
                // need to be relative to the package
                String packageName = className.substring(0, i);
                String packagePath = packageName.replace('.', '/');
                return packagePath + "/" + resource;
            }
        }
    }

    public static Parseable newResources(ClassLoader loader, String resource,
            ConfigParseOptions options) {
        return new ParseableResources(loader, resource, options);
    }

    private final static class ParseableProperties extends Parseable {
        final private Properties props;

        ParseableProperties(Properties props, ConfigParseOptions options) {
            this.props = props;
            postConstruct(options);
        }

        @Override
        protected Reader reader() throws IOException {
            throw new ConfigException.BugOrBroken("reader() should not be called on props");
        }

        @Override
        protected AbstractConfigObject rawParseValue(ConfigOrigin origin,
                ConfigParseOptions finalOptions) {
            if (ConfigImpl.traceLoadsEnabled())
                trace("Loading config from properties " + props);
            return PropertiesParser.fromProperties(origin, props);
        }

        @Override
        ConfigSyntax guessSyntax() {
            return ConfigSyntax.PROPERTIES;
        }

        @Override
        protected ConfigOrigin createOrigin() {
            return SimpleConfigOrigin.newSimple("properties");
        }

        @Override
        public String toString() {
            return getClass().getSimpleName() + "(" + props.size() + " props)";
        }
    }

    public static Parseable newProperties(Properties properties, ConfigParseOptions options) {
        return new ParseableProperties(properties, options);
    }
}
