/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 *
 * Based on Configgy by Robey Pointer.
 *   Copyright 2009 Robey Pointer <robeypointer@gmail.com>
 *   http://www.apache.org/licenses/LICENSE-2.0
 */

package akka.config

import java.io.File
import scala.collection.{Map, Set}
import scala.collection.{immutable, mutable}

class Configuration extends ConfigMap {
  private var root = new Attributes(this, "")
  private var reloadAction: Option[() => Unit] = None

  /**
   * Importer for resolving "include" lines when loading config files.
   * By default, it's a FilesystemImporter based on the current working
   * directory.
   */
  var importer: Importer = new FilesystemImporter(new File(".").getCanonicalPath)


  /**
   * Read config data from a string and use it to populate this object.
   */
  def load(data: String) {
    reloadAction = Some(() => configure(data))
    reload()
  }

  /**
   * Read config data from a file and use it to populate this object.
   */
  def loadFile(filename: String) {
    reloadAction = Some(() => configure(importer.importFile(filename)))
    reload()
  }

  /**
   * Read config data from a file and use it to populate this object.
   */
  def loadFile(path: String, filename: String) {
    importer = new FilesystemImporter(path)
    loadFile(filename)
  }

  /**
   * Reloads the configuration from whatever source it was previously loaded
   * from, undoing any in-memory changes.  This is a no-op if the configuration
   * data has not be loaded from a source (file or string).
   */
  def reload() {
    reloadAction.foreach(_())
  }

  private def configure(data: String) {
    val newRoot = new Attributes(this, "")
    new ConfigParser(newRoot, importer) parse data
    root.replaceWith(newRoot)
  }

  override def toString = root.toString

  // -----  implement AttributeMap by wrapping our root object:

  def getString(key: String): Option[String] = root.getString(key)
  def getConfigMap(key: String): Option[ConfigMap] = root.getConfigMap(key)
  def configMap(key: String): ConfigMap = root.configMap(key)
  def getList(key: String): Seq[String] = root.getList(key)
  def setString(key: String, value: String): Unit = root.setString(key, value)
  def setList(key: String, value: Seq[String]): Unit = root.setList(key, value)
  def setConfigMap(key: String, value: ConfigMap): Unit = root.setConfigMap(key, value)
  def contains(key: String): Boolean = root.contains(key)
  def remove(key: String): Boolean = root.remove(key)
  def keys: Iterator[String] = root.keys
  def asMap(): Map[String, String] = root.asMap()
  def toConfigString = root.toConfigString
  def copy(): ConfigMap = root.copy()
  def copyInto[T <: ConfigMap](m: T): T = root.copyInto(m)
  def inheritFrom = root.inheritFrom
  def inheritFrom_=(config: Option[ConfigMap]) = root.inheritFrom=(config)
  def getName(): String = root.name
}


object Configuration {
  /**
   * Create a configuration object from a config file of the given path
   * and filename. The filename must be relative to the path. The path is
   * used to resolve filenames given in "include" lines.
   */
  def fromFile(path: String, filename: String): Configuration = {
    val config = new Configuration
    config.loadFile(path, filename)
    config
  }

  /**
   * Create a Configuration object from a config file of the given filename.
   * The base folder will be extracted from the filename and used as a base
   * path for resolving filenames given in "include" lines.
   */
  def fromFile(filename: String): Configuration = {
    val n = filename.lastIndexOf('/')
    if (n < 0) {
      fromFile(new File(".").getCanonicalPath, filename)
    } else {
      fromFile(filename.substring(0, n), filename.substring(n + 1))
    }
  }

  /**
   * Create a Configuration object from the given named resource inside this jar
   * file, using the system class loader. "include" lines will also operate
   * on resource paths.
   */
  def fromResource(name: String): Configuration = {
    fromResource(name, ClassLoader.getSystemClassLoader)
  }

  /**
   * Create a Configuration object from the given named resource inside this jar
   * file, using a specific class loader. "include" lines will also operate
   * on resource paths.
   */
  def fromResource(name: String, classLoader: ClassLoader): Configuration = {
    val config = new Configuration
    config.importer = new ResourceImporter(classLoader)
    config.loadFile(name)
    config
  }

  /**
   * Create a Configuration object from a map of String keys and String values.
   */
  def fromMap(m: Map[String, String]) = {
    val config = new Configuration
    for ((k, v) <- m.elements) {
      config(k) = v
    }
    config
  }

  /**
   * Create a Configuration object from a string containing a config file's contents.
   */
  def fromString(data: String): Configuration = {
    val config = new Configuration
    config.load(data)
    config
  }
}
