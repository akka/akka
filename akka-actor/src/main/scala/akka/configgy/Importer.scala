/*
 * Copyright 2009 Robey Pointer <robeypointer@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package akka.configgy

import java.io.{BufferedReader, File, FileInputStream, InputStream, InputStreamReader}


/**
 * An interface for finding config files and reading them into strings for
 * parsing. This is used to handle `include` directives in config files.
 */
trait Importer {
  /**
   * Imports a requested file and returns the string contents of that file,
   * if the file exists, and empty string if it does not exist and `required`
   * is false.
   *
   * If the file couldn't be imported, throws a `ParseException`.
   */
  @throws(classOf[ParseException])
  def importFile(filename: String, required: Boolean): String

  /**
   * Imports a requested file and returns the string contents of that file.
   * If the file couldn't be imported, throws a `ParseException`.
   */
  @throws(classOf[ParseException])
  def importFile(filename: String): String = importFile(filename, true)

  private val BUFFER_SIZE = 8192

  /**
   * Exhaustively reads an InputStream and converts it into a String (using
   * UTF-8 encoding). This is meant as a helper function for custom Importer
   * classes.
   *
   * No exceptions are caught!
   */
  protected def streamToString(in: InputStream): String = {
    val reader = new BufferedReader(new InputStreamReader(in, "UTF-8"))
    val buffer = new Array[Char](BUFFER_SIZE)
    val out = new StringBuilder
    var n = 0
    while (n >= 0) {
      n = reader.read(buffer, 0, buffer.length)
      if (n >= 0) {
        out.append(buffer, 0, n)
      }
    }
    try {
      in.close()
    } catch {
      case _ =>
    }
    out.toString
  }
}


/**
 * An Importer that looks for imported config files in the filesystem.
 * This is the default importer.
 */
class FilesystemImporter(val baseFolder: String) extends Importer {
  def importFile(filename: String, required: Boolean): String = {
    var f = new File(filename)
    if (! f.isAbsolute) {
      f = new File(baseFolder, filename)
    }
    if (!required && !f.exists) {
      ""
    } else {
      try {
        streamToString(new FileInputStream(f))
      } catch {
        case x => throw new ParseException(x.toString)
      }
    }
  }
}


/**
 * An Importer that looks for imported config files in the java resources
 * of the system class loader (usually the jar used to launch this app).
 */
class ResourceImporter(classLoader: ClassLoader) extends Importer {
  def importFile(filename: String, required: Boolean): String = {
    try {
      val stream = classLoader.getResourceAsStream(filename)
      if (stream eq null) {
        if (required) {
          throw new ParseException("Can't find resource: " + filename)
        }
        ""
      } else {
        streamToString(stream)
      }
    } catch {
      case x => throw new ParseException(x.toString)
    }
  }
}
