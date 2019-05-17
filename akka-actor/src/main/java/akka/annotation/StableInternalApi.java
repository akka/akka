/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.annotation;

import java.lang.annotation.*;

/**
 * Marks APIs that are interal so can be evolved but shoud only be done so for a very good reason as
 * satellite Akka projects may use them so will break their backward compatibility
 */
@Documented
@Target({
  ElementType.METHOD,
  ElementType.CONSTRUCTOR,
  ElementType.FIELD,
  ElementType.TYPE,
  ElementType.PACKAGE
})
public @interface StableInternalApi {

  String description() default "";
}
