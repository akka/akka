/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.http.javadsl.server.directives;

import akka.http.javadsl.model.ContentType;

/**
 * Implement this interface to provide a custom mapping from a file name to a [[akka.http.javadsl.model.ContentType]].
 */
@FunctionalInterface
public interface ContentTypeResolver {
    ContentType resolve(String fileName);

    /**
     * Returns a Scala DSL representation of this content type resolver
     */
    default akka.http.scaladsl.server.directives.ContentTypeResolver asScala() {
        ContentTypeResolver delegate = this;
        return new akka.http.scaladsl.server.directives.ContentTypeResolver() {
            @Override
            public ContentType resolve(String fileName) {
                return delegate.resolve(fileName);
            }
            
            @Override
            public akka.http.scaladsl.model.ContentType apply(String fileName) {
                return (akka.http.scaladsl.model.ContentType) delegate.resolve(fileName);
            }
        };
    }
}
