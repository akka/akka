/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.javadsl;

//#jackson-xml-support
import java.io.IOException;
import java.util.List;
import java.util.Arrays;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import akka.http.javadsl.model.*;
import akka.http.javadsl.marshalling.Marshaller;
import akka.http.javadsl.unmarshalling.Unmarshaller;

public class JacksonXmlSupport {
  private static final ObjectMapper DEFAULT_XML_MAPPER =
    new XmlMapper().enable(SerializationFeature.WRAP_ROOT_VALUE);
  private static final List<MediaType> XML_MEDIA_TYPES = Arrays.asList(MediaTypes.APPLICATION_XML, MediaTypes.TEXT_XML);

  public static <T> Marshaller<T, RequestEntity> marshaller() {
    return Marshaller.wrapEntity(
      u -> toXML(DEFAULT_XML_MAPPER, u),
      Marshaller.stringToEntity(),
      MediaTypes.APPLICATION_XML
    );
  }

  public static <T> Unmarshaller<HttpEntity, T> unmarshaller(Class<T> expectedType) {
    return Unmarshaller.forMediaTypes(XML_MEDIA_TYPES, Unmarshaller.entityToString())
                       .thenApply(xml -> fromXML(DEFAULT_XML_MAPPER, xml, expectedType));
  }

  private static <T> String toXML(ObjectMapper mapper, T object) {
    try {
      return mapper.writeValueAsString(object);
    } catch (IOException e) {
      throw new IllegalArgumentException("Cannot marshal to XML: " + object, e);
    }
  }

  private static <T> T fromXML(ObjectMapper mapper, String xml, Class<T> expectedType) {
    try {
      return mapper.readerFor(expectedType).readValue(xml);
    } catch (IOException e) {
      throw new IllegalArgumentException("Cannot unmarshal XML as " + expectedType.getSimpleName(), e);
    }
  }
}
//#jackson-xml-support
