/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.io.dns.internal

import akka.io.dns.RecordType
import akka.util.{ ByteIterator, ByteStringBuilder, OptionVal }

/**
 * INTERNAL API
 */
private[akka] object RecordTypeSerializer {

  // TODO other type than ByteStringBuilder? (was used in akka-dns)
  def write(out: ByteStringBuilder, value: RecordType): Unit = {
    out.putShort(value.code)
  }

  def parse(it: ByteIterator): RecordType = {
    val id = it.getShort
    RecordType(id) match {
      case OptionVal.None    ⇒ throw new IllegalArgumentException(s"Illegal id [$id] for DnsRecordType")
      case OptionVal.Some(t) ⇒ t
    }
  }

}
