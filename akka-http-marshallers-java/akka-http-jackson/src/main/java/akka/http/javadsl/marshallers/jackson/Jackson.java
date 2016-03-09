/*
 * Copyright (C) 2009-2016 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.http.javadsl.marshallers.jackson;

import java.io.IOException;

import akka.http.javadsl.model.HttpEntity;
import akka.http.javadsl.model.MediaTypes;
import akka.http.javadsl.model.RequestEntity;
import akka.http.javadsl.server.Marshaller;
import akka.http.javadsl.server.Unmarshaller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

public class Jackson {
	private static final ObjectMapper defaultObjectMapper = 
			new ObjectMapper().enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY);
	
	public static <T> Marshaller<T, RequestEntity> marshaller() {
		return marshaller(defaultObjectMapper);
	}
	
	public static <T> Marshaller<T, RequestEntity> marshaller(ObjectMapper mapper) {
		return Marshaller.wrapEntity(
            u -> toJSON(mapper, u),
            Marshaller.stringToEntity(), 
            MediaTypes.APPLICATION_JSON 
        );
	}
	
	public static <T> Unmarshaller<HttpEntity,T> unmarshaller(Class<T> expectedType) {
		return unmarshaller(defaultObjectMapper, expectedType);
	}
	
	public static <T> Unmarshaller<HttpEntity,T> unmarshaller(ObjectMapper mapper, Class<T> expectedType) {
        return Unmarshaller.forMediaType(MediaTypes.APPLICATION_JSON, Unmarshaller.entityToString())
	        .map(s -> fromJSON(mapper, s, expectedType));		
	}
	
	private static String toJSON(ObjectMapper mapper, Object object) {
		try {
			return mapper.writeValueAsString(object);
		} catch (JsonProcessingException e) {
			throw new IllegalArgumentException("Cannot marshal to JSON: " + object, e);
		}
	}
	
	private static <T> T fromJSON(ObjectMapper mapper, String json, Class<T> expectedType) {
		try {
			return mapper.reader(expectedType).readValue(json);
		} catch (IOException e) {
			throw new IllegalArgumentException("Cannot unmarshal JSON as " + expectedType.getSimpleName(), e);
		}
	}
}
