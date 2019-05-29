/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks internal members that shouldn't be changed without considering possible usage outside of
 * the Akka core modules.
 */
@Documented
@Retention(RetentionPolicy.CLASS) // to be accessible by MiMa
@Target({
  ElementType.METHOD,
  ElementType.CONSTRUCTOR,
  ElementType.FIELD,
  ElementType.TYPE,
  ElementType.PACKAGE
})
public @interface InternalStableApi {}
