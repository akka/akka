/**
 *   Copyright (C) 2011 Typesafe Inc. <http://typesafe.com>
 */
package com.typesafe.config.impl;

import com.typesafe.config.ConfigMergeable;
import com.typesafe.config.ConfigResolveOptions;
import com.typesafe.config.ConfigRoot;

final class RootConfig extends SimpleConfig implements ConfigRoot {

    final private Path rootPath;

    RootConfig(AbstractConfigObject underlying, Path rootPath) {
        super(underlying);
        this.rootPath = rootPath;
    }

    @Override
    protected RootConfig asRoot(AbstractConfigObject underlying,
            Path newRootPath) {
        if (newRootPath.equals(this.rootPath))
            return this;
        else
            return new RootConfig(underlying, newRootPath);
    }

    @Override
    public RootConfig resolve() {
        return resolve(ConfigResolveOptions.defaults());
    }

    @Override
    public RootConfig resolve(ConfigResolveOptions options) {
        // if the object is already resolved then we should end up returning
        // "this" here, since asRoot() should return this if the path
        // is unchanged.
        AbstractConfigObject resolved = resolvedObject(options);
        return newRootIfObjectChanged(this, resolved);
    }

    @Override
    public RootConfig withFallback(ConfigMergeable value) {
        // this can return "this" if the withFallback does nothing
        return newRootIfObjectChanged(this, super.withFallback(value).toObject());
    }

    Path rootPathObject() {
        return rootPath;
    }

    @Override
    public String rootPath() {
        return rootPath.render();
    }

    @Override
    public String toString() {
        return "Root" + super.toString();
    }
}
