package com.typesafe.config;

/**
 * A root object. The only special thing about a root object is that you can
 * resolve substitutions against it. So it can have a resolve() method that
 * doesn't require you to pass in an object to resolve against.
 */
public interface ConfigRoot extends Config {
    /**
     * Returns a replacement root object with all substitutions (the
     * "${foo.bar}" syntax) resolved. Substitutions are looked up in this root
     * object. A configuration value tree must be resolved before you can use
     * it. This method uses ConfigResolveOptions.defaults().
     *
     * @return an immutable object with substitutions resolved
     */
    ConfigRoot resolve();

    ConfigRoot resolve(ConfigResolveOptions options);

    @Override
    ConfigRoot withFallback(ConfigMergeable fallback);

    @Override
    ConfigRoot withFallbacks(ConfigMergeable... fallbacks);

    /**
     * Gets the global app name that this root represents.
     *
     * @return the app's root config path
     */
    String rootPath();
}
