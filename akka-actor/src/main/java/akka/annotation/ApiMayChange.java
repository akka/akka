/*
 * Copyright (C) 2017-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.annotation;

import java.lang.annotation.*;

/**
 * Marks APIs that are meant to evolve towards becoming stable APIs, but are not stable APIs yet.
 *
 * <p>Evolving interfaces MAY change from one patch release to another (i.e. 2.4.10 to 2.4.11)
 * without up-front notice. A best-effort approach is taken to not cause more breakage than really
 * necessary, and usual deprecation techniques are utilised while evolving these APIs, however there
 * is NO strong guarantee regarding the source or binary compatibility of APIs marked using this
 * annotation.
 *
 * <p>It MAY also change when promoting the API to stable, for example such changes may include
 * removal of deprecated methods that were introduced during the evolution and final refactoring
 * that were deferred because they would have introduced to much breaking changes during the
 * evolution phase.
 *
 * <p>Promoting the API to stable MAY happen in a patch release.
 *
 * <p>It is encouraged to document in ScalaDoc how exactly this API is expected to evolve.
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
public @interface ApiMayChange {

  /** Reference to issue discussing the future evolvement of this API */
  String issue() default "";
}
