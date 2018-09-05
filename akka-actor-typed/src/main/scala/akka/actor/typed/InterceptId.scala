/**
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed

/**
 * Identifies (through instance equality) when nested interceptors are the logical same intercept, and instances
 * further out in the behavior stack can be eliminated to avoid infinite behavior stack
 * build up (and stack overflow).
 *
 * In cases where no recursive behavior build up happens, you can use a new instance each time, in cases where
 * a behavior may return a new behavior with the same wrapping behavior, define a constant instance and use that.
 */
final class InterceptId
