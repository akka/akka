/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 *
 * Based on Configgy by Robey Pointer.
 *   Copyright 2009 Robey Pointer <robeypointer@gmail.com>
 *   http://www.apache.org/licenses/LICENSE-2.0
 */

package akka.config

import java.io.File

object Configure {
  private var _config: Configuration = null

  def config = _config

  def config_=(c: Configuration) {
    _config = c
  }

  def configure(path: String, filename: String): Unit = {
    config = Configuration.fromFile(path, filename)
  }

  def configure(filename: String): Unit = {
    config = Configuration.fromFile(filename)
  }

  def configureFromResource(name: String) = {
    config = Configuration.fromResource(name)
  }

  def configureFromResource(name: String, classLoader: ClassLoader) = {
    config = Configuration.fromResource(name, classLoader)
  }

  def configureFromString(data: String) = {
    config = Configuration.fromString(data)
  }
}
