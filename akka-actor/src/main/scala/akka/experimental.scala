/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka

import annotation.target._

/**
 * This annotation marks a feature which is not yet considered stable and may
 * change or be removed in a future release.
 *
 * @author Roland Kuhn
 * @since 1.2
 */
@getter
@setter
@beanGetter
@beanSetter
final class experimental(since: String) extends annotation.StaticAnnotation
