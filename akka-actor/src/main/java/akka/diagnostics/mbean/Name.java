/**
 *  Copyright (C) 2015 Typesafe <http://typesafe.com/>
 */
package akka.diagnostics.mbean;

import static java.lang.annotation.ElementType.PARAMETER;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * INTERNAL API
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ PARAMETER })
public @interface Name {
  String value();
}
