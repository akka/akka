package akka.remote.netty.rcl

import java.io.DataInputStream;

object ByteCodeInspector {

  val magic = 0xCAFEBABE

  val utf8_tag = 1
  val int_tag = 3
  val float_tag = 4
  val long_tag = 5
  val double_tag = 6
  val class_ref_tag = 7
  val string_ref_tag = 8
  val field_ref_tag = 9
  val method_ref_tag = 10
  val intfce_ref_tag = 11
  val name_type_desc_tag = 12

  def findReferencedClassesFor(klass: Class[_]): List[String] = {
    // we will analyse just the constant pool no need to load the whole file
    val resource = klass.getClassLoader.getResource(klass.getName.replace('.', '/') + ".class");
    val input = new DataInputStream(resource.openStream());

    try {

      input.readInt match {
        case 0xCAFEBABE ⇒ // good
        case _          ⇒ throw new RuntimeException("Not a bytecode file.")
      }

      input.readUnsignedShort(); // minor
      input.readUnsignedShort(); // major

      // this values is equal to the number entries in the constants pool + 1
      val constantPoolEntries = input.readUnsignedShort() - 1;

      // we will fill this Map with UTF8 tags
      val utfTags = scala.collection.mutable.HashMap[Int, String]()

      // we will mark which utf8 tags point to class and to name_and_type
      val classTags = scala.collection.mutable.ArrayBuffer[Int]()
      val descTags = scala.collection.mutable.ArrayBuffer[Int]()

      // loop over all entries in the constants pool
      var i = 0
      while (i < constantPoolEntries) {
        i += 1
        // the tag to identify the record type
        input.readUnsignedByte() match {
          case 1 ⇒ utfTags += i -> input.readUTF
          case 7 ⇒ classTags += input.readUnsignedShort
          case 12 ⇒ {
            input.readUnsignedShort // don't care about the name
            descTags += input.readUnsignedShort
          }
          case 3 | 4 | 9 | 10 | 11 ⇒ {
            // this tags take 4 bytes and we don't care about them
            input.readInt
          }
          case 5 | 6 ⇒ {
            input.readLong // this take 8 bytes
            i += 1 // entry takes 2 slots
          }
          case 8 ⇒ input.readUnsignedShort // and this 2 bytes
          case _ ⇒ throw new RuntimeException("Encountered unknow constant pool tag. Corrupt bytecode or new format.")
        }
      }

      val answer = scala.collection.mutable.HashSet[String]()

      // read the utf8 tags for fqn class names
      classTags.foreach { answer += utfTags(_) replaceAll ("/", ".") }

      descTags.foreach {
        answer ++= """L[^;]+;""".r findAllIn utfTags(_) map {
          (s: String) ⇒ s drop (1) dropRight (1) replaceAll ("/", ".")
        }
      }

      answer.toList
    } finally {
      input.close
    }
  }
}