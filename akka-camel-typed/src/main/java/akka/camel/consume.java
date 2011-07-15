/**
 * Copyright (C) 2009-2010 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camel;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation used by implementations of {@link akka.actor.TypedActor}
 * (on method-level) to define consumer endpoints.
 *
 * @author Martin Krasser
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD})
public @interface consume {

    /**
     * Consumer endpoint URI
     */
    public abstract String value();

    /**
     * Route definition handler class for customizing route to annotated method.
     * The handler class must have a default constructor.
     */
    public abstract Class<? extends RouteDefinitionHandler> routeDefinitionHandler()
        default RouteDefinitionIdentity.class;

}
