package akka.http.util

object Base64 {
  object rfc2045 {
    def encodeToChar(bytes: Array[Byte], flag: Boolean): Array[Char] = ???
    def decodeFast(string: String): Array[Byte] = ???
  }
}
