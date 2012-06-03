package akka.remote.netty.rcl

import java.net.URL
import java.io.{ OutputStream, InputStream, ByteArrayOutputStream }

object IOUtil {

  // 4K
  val BUF_SIZE = 0x1000;

  def toByteArray(url: URL): Array[Byte] = {
    val out = new ByteArrayOutputStream();
    copy(url.openStream(), out);
    return out.toByteArray();
  }

  def copy(from: InputStream, to: OutputStream): Long = {
    val buf = new Array[Byte](BUF_SIZE);
    var total = 0;
    while (true) {
      var r = from.read(buf);
      if (r == -1) {
        return total;
      }
      to.write(buf, 0, r);
      total += r;
    }
    return total;
  }
}
