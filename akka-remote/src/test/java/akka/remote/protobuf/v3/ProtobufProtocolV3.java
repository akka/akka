/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: ProtobufProtocolV3.proto

package akka.remote.protobuf.v3;

public final class ProtobufProtocolV3 {
  private ProtobufProtocolV3() {}

  public static void registerAllExtensions(
      akka.protobufv3.internal.ExtensionRegistryLite registry) {}

  public static void registerAllExtensions(akka.protobufv3.internal.ExtensionRegistry registry) {
    registerAllExtensions((akka.protobufv3.internal.ExtensionRegistryLite) registry);
  }

  public interface MyMessageV3OrBuilder
      extends
      // @@protoc_insertion_point(interface_extends:MyMessageV3)
      akka.protobufv3.internal.MessageOrBuilder {

    /**
     * <code>string query = 1;</code>
     *
     * @return The query.
     */
    java.lang.String getQuery();
    /**
     * <code>string query = 1;</code>
     *
     * @return The bytes for query.
     */
    akka.protobufv3.internal.ByteString getQueryBytes();

    /**
     * <code>int32 page_number = 2;</code>
     *
     * @return The pageNumber.
     */
    int getPageNumber();

    /**
     * <code>int32 result_per_page = 3;</code>
     *
     * @return The resultPerPage.
     */
    int getResultPerPage();
  }
  /** Protobuf type {@code MyMessageV3} */
  public static final class MyMessageV3 extends akka.protobufv3.internal.GeneratedMessageV3
      implements
      // @@protoc_insertion_point(message_implements:MyMessageV3)
      MyMessageV3OrBuilder {
    private static final long serialVersionUID = 0L;
    // Use MyMessageV3.newBuilder() to construct.
    private MyMessageV3(akka.protobufv3.internal.GeneratedMessageV3.Builder<?> builder) {
      super(builder);
    }

    private MyMessageV3() {
      query_ = "";
    }

    @java.lang.Override
    @SuppressWarnings({"unused"})
    protected java.lang.Object newInstance(
        akka.protobufv3.internal.GeneratedMessageV3.UnusedPrivateParameter unused) {
      return new MyMessageV3();
    }

    @java.lang.Override
    public final akka.protobufv3.internal.UnknownFieldSet getUnknownFields() {
      return this.unknownFields;
    }

    private MyMessageV3(
        akka.protobufv3.internal.CodedInputStream input,
        akka.protobufv3.internal.ExtensionRegistryLite extensionRegistry)
        throws akka.protobufv3.internal.InvalidProtocolBufferException {
      this();
      if (extensionRegistry == null) {
        throw new java.lang.NullPointerException();
      }
      akka.protobufv3.internal.UnknownFieldSet.Builder unknownFields =
          akka.protobufv3.internal.UnknownFieldSet.newBuilder();
      try {
        boolean done = false;
        while (!done) {
          int tag = input.readTag();
          switch (tag) {
            case 0:
              done = true;
              break;
            case 10:
              {
                java.lang.String s = input.readStringRequireUtf8();

                query_ = s;
                break;
              }
            case 16:
              {
                pageNumber_ = input.readInt32();
                break;
              }
            case 24:
              {
                resultPerPage_ = input.readInt32();
                break;
              }
            default:
              {
                if (!parseUnknownField(input, unknownFields, extensionRegistry, tag)) {
                  done = true;
                }
                break;
              }
          }
        }
      } catch (akka.protobufv3.internal.InvalidProtocolBufferException e) {
        throw e.setUnfinishedMessage(this);
      } catch (java.io.IOException e) {
        throw new akka.protobufv3.internal.InvalidProtocolBufferException(e)
            .setUnfinishedMessage(this);
      } finally {
        this.unknownFields = unknownFields.build();
        makeExtensionsImmutable();
      }
    }

    public static final akka.protobufv3.internal.Descriptors.Descriptor getDescriptor() {
      return akka.remote.protobuf.v3.ProtobufProtocolV3.internal_static_MyMessageV3_descriptor;
    }

    @java.lang.Override
    protected akka.protobufv3.internal.GeneratedMessageV3.FieldAccessorTable
        internalGetFieldAccessorTable() {
      return akka.remote.protobuf.v3.ProtobufProtocolV3
          .internal_static_MyMessageV3_fieldAccessorTable
          .ensureFieldAccessorsInitialized(
              akka.remote.protobuf.v3.ProtobufProtocolV3.MyMessageV3.class,
              akka.remote.protobuf.v3.ProtobufProtocolV3.MyMessageV3.Builder.class);
    }

    public static final int QUERY_FIELD_NUMBER = 1;
    private volatile java.lang.Object query_;
    /**
     * <code>string query = 1;</code>
     *
     * @return The query.
     */
    public java.lang.String getQuery() {
      java.lang.Object ref = query_;
      if (ref instanceof java.lang.String) {
        return (java.lang.String) ref;
      } else {
        akka.protobufv3.internal.ByteString bs = (akka.protobufv3.internal.ByteString) ref;
        java.lang.String s = bs.toStringUtf8();
        query_ = s;
        return s;
      }
    }
    /**
     * <code>string query = 1;</code>
     *
     * @return The bytes for query.
     */
    public akka.protobufv3.internal.ByteString getQueryBytes() {
      java.lang.Object ref = query_;
      if (ref instanceof java.lang.String) {
        akka.protobufv3.internal.ByteString b =
            akka.protobufv3.internal.ByteString.copyFromUtf8((java.lang.String) ref);
        query_ = b;
        return b;
      } else {
        return (akka.protobufv3.internal.ByteString) ref;
      }
    }

    public static final int PAGE_NUMBER_FIELD_NUMBER = 2;
    private int pageNumber_;
    /**
     * <code>int32 page_number = 2;</code>
     *
     * @return The pageNumber.
     */
    public int getPageNumber() {
      return pageNumber_;
    }

    public static final int RESULT_PER_PAGE_FIELD_NUMBER = 3;
    private int resultPerPage_;
    /**
     * <code>int32 result_per_page = 3;</code>
     *
     * @return The resultPerPage.
     */
    public int getResultPerPage() {
      return resultPerPage_;
    }

    private byte memoizedIsInitialized = -1;

    @java.lang.Override
    public final boolean isInitialized() {
      byte isInitialized = memoizedIsInitialized;
      if (isInitialized == 1) return true;
      if (isInitialized == 0) return false;

      memoizedIsInitialized = 1;
      return true;
    }

    @java.lang.Override
    public void writeTo(akka.protobufv3.internal.CodedOutputStream output)
        throws java.io.IOException {
      if (!getQueryBytes().isEmpty()) {
        akka.protobufv3.internal.GeneratedMessageV3.writeString(output, 1, query_);
      }
      if (pageNumber_ != 0) {
        output.writeInt32(2, pageNumber_);
      }
      if (resultPerPage_ != 0) {
        output.writeInt32(3, resultPerPage_);
      }
      unknownFields.writeTo(output);
    }

    @java.lang.Override
    public int getSerializedSize() {
      int size = memoizedSize;
      if (size != -1) return size;

      size = 0;
      if (!getQueryBytes().isEmpty()) {
        size += akka.protobufv3.internal.GeneratedMessageV3.computeStringSize(1, query_);
      }
      if (pageNumber_ != 0) {
        size += akka.protobufv3.internal.CodedOutputStream.computeInt32Size(2, pageNumber_);
      }
      if (resultPerPage_ != 0) {
        size += akka.protobufv3.internal.CodedOutputStream.computeInt32Size(3, resultPerPage_);
      }
      size += unknownFields.getSerializedSize();
      memoizedSize = size;
      return size;
    }

    @java.lang.Override
    public boolean equals(final java.lang.Object obj) {
      if (obj == this) {
        return true;
      }
      if (!(obj instanceof akka.remote.protobuf.v3.ProtobufProtocolV3.MyMessageV3)) {
        return super.equals(obj);
      }
      akka.remote.protobuf.v3.ProtobufProtocolV3.MyMessageV3 other =
          (akka.remote.protobuf.v3.ProtobufProtocolV3.MyMessageV3) obj;

      if (!getQuery().equals(other.getQuery())) return false;
      if (getPageNumber() != other.getPageNumber()) return false;
      if (getResultPerPage() != other.getResultPerPage()) return false;
      if (!unknownFields.equals(other.unknownFields)) return false;
      return true;
    }

    @java.lang.Override
    public int hashCode() {
      if (memoizedHashCode != 0) {
        return memoizedHashCode;
      }
      int hash = 41;
      hash = (19 * hash) + getDescriptor().hashCode();
      hash = (37 * hash) + QUERY_FIELD_NUMBER;
      hash = (53 * hash) + getQuery().hashCode();
      hash = (37 * hash) + PAGE_NUMBER_FIELD_NUMBER;
      hash = (53 * hash) + getPageNumber();
      hash = (37 * hash) + RESULT_PER_PAGE_FIELD_NUMBER;
      hash = (53 * hash) + getResultPerPage();
      hash = (29 * hash) + unknownFields.hashCode();
      memoizedHashCode = hash;
      return hash;
    }

    public static akka.remote.protobuf.v3.ProtobufProtocolV3.MyMessageV3 parseFrom(
        java.nio.ByteBuffer data) throws akka.protobufv3.internal.InvalidProtocolBufferException {
      return PARSER.parseFrom(data);
    }

    public static akka.remote.protobuf.v3.ProtobufProtocolV3.MyMessageV3 parseFrom(
        java.nio.ByteBuffer data, akka.protobufv3.internal.ExtensionRegistryLite extensionRegistry)
        throws akka.protobufv3.internal.InvalidProtocolBufferException {
      return PARSER.parseFrom(data, extensionRegistry);
    }

    public static akka.remote.protobuf.v3.ProtobufProtocolV3.MyMessageV3 parseFrom(
        akka.protobufv3.internal.ByteString data)
        throws akka.protobufv3.internal.InvalidProtocolBufferException {
      return PARSER.parseFrom(data);
    }

    public static akka.remote.protobuf.v3.ProtobufProtocolV3.MyMessageV3 parseFrom(
        akka.protobufv3.internal.ByteString data,
        akka.protobufv3.internal.ExtensionRegistryLite extensionRegistry)
        throws akka.protobufv3.internal.InvalidProtocolBufferException {
      return PARSER.parseFrom(data, extensionRegistry);
    }

    public static akka.remote.protobuf.v3.ProtobufProtocolV3.MyMessageV3 parseFrom(byte[] data)
        throws akka.protobufv3.internal.InvalidProtocolBufferException {
      return PARSER.parseFrom(data);
    }

    public static akka.remote.protobuf.v3.ProtobufProtocolV3.MyMessageV3 parseFrom(
        byte[] data, akka.protobufv3.internal.ExtensionRegistryLite extensionRegistry)
        throws akka.protobufv3.internal.InvalidProtocolBufferException {
      return PARSER.parseFrom(data, extensionRegistry);
    }

    public static akka.remote.protobuf.v3.ProtobufProtocolV3.MyMessageV3 parseFrom(
        java.io.InputStream input) throws java.io.IOException {
      return akka.protobufv3.internal.GeneratedMessageV3.parseWithIOException(PARSER, input);
    }

    public static akka.remote.protobuf.v3.ProtobufProtocolV3.MyMessageV3 parseFrom(
        java.io.InputStream input, akka.protobufv3.internal.ExtensionRegistryLite extensionRegistry)
        throws java.io.IOException {
      return akka.protobufv3.internal.GeneratedMessageV3.parseWithIOException(
          PARSER, input, extensionRegistry);
    }

    public static akka.remote.protobuf.v3.ProtobufProtocolV3.MyMessageV3 parseDelimitedFrom(
        java.io.InputStream input) throws java.io.IOException {
      return akka.protobufv3.internal.GeneratedMessageV3.parseDelimitedWithIOException(
          PARSER, input);
    }

    public static akka.remote.protobuf.v3.ProtobufProtocolV3.MyMessageV3 parseDelimitedFrom(
        java.io.InputStream input, akka.protobufv3.internal.ExtensionRegistryLite extensionRegistry)
        throws java.io.IOException {
      return akka.protobufv3.internal.GeneratedMessageV3.parseDelimitedWithIOException(
          PARSER, input, extensionRegistry);
    }

    public static akka.remote.protobuf.v3.ProtobufProtocolV3.MyMessageV3 parseFrom(
        akka.protobufv3.internal.CodedInputStream input) throws java.io.IOException {
      return akka.protobufv3.internal.GeneratedMessageV3.parseWithIOException(PARSER, input);
    }

    public static akka.remote.protobuf.v3.ProtobufProtocolV3.MyMessageV3 parseFrom(
        akka.protobufv3.internal.CodedInputStream input,
        akka.protobufv3.internal.ExtensionRegistryLite extensionRegistry)
        throws java.io.IOException {
      return akka.protobufv3.internal.GeneratedMessageV3.parseWithIOException(
          PARSER, input, extensionRegistry);
    }

    @java.lang.Override
    public Builder newBuilderForType() {
      return newBuilder();
    }

    public static Builder newBuilder() {
      return DEFAULT_INSTANCE.toBuilder();
    }

    public static Builder newBuilder(
        akka.remote.protobuf.v3.ProtobufProtocolV3.MyMessageV3 prototype) {
      return DEFAULT_INSTANCE.toBuilder().mergeFrom(prototype);
    }

    @java.lang.Override
    public Builder toBuilder() {
      return this == DEFAULT_INSTANCE ? new Builder() : new Builder().mergeFrom(this);
    }

    @java.lang.Override
    protected Builder newBuilderForType(
        akka.protobufv3.internal.GeneratedMessageV3.BuilderParent parent) {
      Builder builder = new Builder(parent);
      return builder;
    }
    /** Protobuf type {@code MyMessageV3} */
    public static final class Builder
        extends akka.protobufv3.internal.GeneratedMessageV3.Builder<Builder>
        implements
        // @@protoc_insertion_point(builder_implements:MyMessageV3)
        akka.remote.protobuf.v3.ProtobufProtocolV3.MyMessageV3OrBuilder {
      public static final akka.protobufv3.internal.Descriptors.Descriptor getDescriptor() {
        return akka.remote.protobuf.v3.ProtobufProtocolV3.internal_static_MyMessageV3_descriptor;
      }

      @java.lang.Override
      protected akka.protobufv3.internal.GeneratedMessageV3.FieldAccessorTable
          internalGetFieldAccessorTable() {
        return akka.remote.protobuf.v3.ProtobufProtocolV3
            .internal_static_MyMessageV3_fieldAccessorTable
            .ensureFieldAccessorsInitialized(
                akka.remote.protobuf.v3.ProtobufProtocolV3.MyMessageV3.class,
                akka.remote.protobuf.v3.ProtobufProtocolV3.MyMessageV3.Builder.class);
      }

      // Construct using akka.remote.protobuf.v3.ProtobufProtocolV3.MyMessageV3.newBuilder()
      private Builder() {
        maybeForceBuilderInitialization();
      }

      private Builder(akka.protobufv3.internal.GeneratedMessageV3.BuilderParent parent) {
        super(parent);
        maybeForceBuilderInitialization();
      }

      private void maybeForceBuilderInitialization() {
        if (akka.protobufv3.internal.GeneratedMessageV3.alwaysUseFieldBuilders) {}
      }

      @java.lang.Override
      public Builder clear() {
        super.clear();
        query_ = "";

        pageNumber_ = 0;

        resultPerPage_ = 0;

        return this;
      }

      @java.lang.Override
      public akka.protobufv3.internal.Descriptors.Descriptor getDescriptorForType() {
        return akka.remote.protobuf.v3.ProtobufProtocolV3.internal_static_MyMessageV3_descriptor;
      }

      @java.lang.Override
      public akka.remote.protobuf.v3.ProtobufProtocolV3.MyMessageV3 getDefaultInstanceForType() {
        return akka.remote.protobuf.v3.ProtobufProtocolV3.MyMessageV3.getDefaultInstance();
      }

      @java.lang.Override
      public akka.remote.protobuf.v3.ProtobufProtocolV3.MyMessageV3 build() {
        akka.remote.protobuf.v3.ProtobufProtocolV3.MyMessageV3 result = buildPartial();
        if (!result.isInitialized()) {
          throw newUninitializedMessageException(result);
        }
        return result;
      }

      @java.lang.Override
      public akka.remote.protobuf.v3.ProtobufProtocolV3.MyMessageV3 buildPartial() {
        akka.remote.protobuf.v3.ProtobufProtocolV3.MyMessageV3 result =
            new akka.remote.protobuf.v3.ProtobufProtocolV3.MyMessageV3(this);
        result.query_ = query_;
        result.pageNumber_ = pageNumber_;
        result.resultPerPage_ = resultPerPage_;
        onBuilt();
        return result;
      }

      @java.lang.Override
      public Builder clone() {
        return super.clone();
      }

      @java.lang.Override
      public Builder setField(
          akka.protobufv3.internal.Descriptors.FieldDescriptor field, java.lang.Object value) {
        return super.setField(field, value);
      }

      @java.lang.Override
      public Builder clearField(akka.protobufv3.internal.Descriptors.FieldDescriptor field) {
        return super.clearField(field);
      }

      @java.lang.Override
      public Builder clearOneof(akka.protobufv3.internal.Descriptors.OneofDescriptor oneof) {
        return super.clearOneof(oneof);
      }

      @java.lang.Override
      public Builder setRepeatedField(
          akka.protobufv3.internal.Descriptors.FieldDescriptor field,
          int index,
          java.lang.Object value) {
        return super.setRepeatedField(field, index, value);
      }

      @java.lang.Override
      public Builder addRepeatedField(
          akka.protobufv3.internal.Descriptors.FieldDescriptor field, java.lang.Object value) {
        return super.addRepeatedField(field, value);
      }

      @java.lang.Override
      public Builder mergeFrom(akka.protobufv3.internal.Message other) {
        if (other instanceof akka.remote.protobuf.v3.ProtobufProtocolV3.MyMessageV3) {
          return mergeFrom((akka.remote.protobuf.v3.ProtobufProtocolV3.MyMessageV3) other);
        } else {
          super.mergeFrom(other);
          return this;
        }
      }

      public Builder mergeFrom(akka.remote.protobuf.v3.ProtobufProtocolV3.MyMessageV3 other) {
        if (other == akka.remote.protobuf.v3.ProtobufProtocolV3.MyMessageV3.getDefaultInstance())
          return this;
        if (!other.getQuery().isEmpty()) {
          query_ = other.query_;
          onChanged();
        }
        if (other.getPageNumber() != 0) {
          setPageNumber(other.getPageNumber());
        }
        if (other.getResultPerPage() != 0) {
          setResultPerPage(other.getResultPerPage());
        }
        this.mergeUnknownFields(other.unknownFields);
        onChanged();
        return this;
      }

      @java.lang.Override
      public final boolean isInitialized() {
        return true;
      }

      @java.lang.Override
      public Builder mergeFrom(
          akka.protobufv3.internal.CodedInputStream input,
          akka.protobufv3.internal.ExtensionRegistryLite extensionRegistry)
          throws java.io.IOException {
        akka.remote.protobuf.v3.ProtobufProtocolV3.MyMessageV3 parsedMessage = null;
        try {
          parsedMessage = PARSER.parsePartialFrom(input, extensionRegistry);
        } catch (akka.protobufv3.internal.InvalidProtocolBufferException e) {
          parsedMessage =
              (akka.remote.protobuf.v3.ProtobufProtocolV3.MyMessageV3) e.getUnfinishedMessage();
          throw e.unwrapIOException();
        } finally {
          if (parsedMessage != null) {
            mergeFrom(parsedMessage);
          }
        }
        return this;
      }

      private java.lang.Object query_ = "";
      /**
       * <code>string query = 1;</code>
       *
       * @return The query.
       */
      public java.lang.String getQuery() {
        java.lang.Object ref = query_;
        if (!(ref instanceof java.lang.String)) {
          akka.protobufv3.internal.ByteString bs = (akka.protobufv3.internal.ByteString) ref;
          java.lang.String s = bs.toStringUtf8();
          query_ = s;
          return s;
        } else {
          return (java.lang.String) ref;
        }
      }
      /**
       * <code>string query = 1;</code>
       *
       * @return The bytes for query.
       */
      public akka.protobufv3.internal.ByteString getQueryBytes() {
        java.lang.Object ref = query_;
        if (ref instanceof String) {
          akka.protobufv3.internal.ByteString b =
              akka.protobufv3.internal.ByteString.copyFromUtf8((java.lang.String) ref);
          query_ = b;
          return b;
        } else {
          return (akka.protobufv3.internal.ByteString) ref;
        }
      }
      /**
       * <code>string query = 1;</code>
       *
       * @param value The query to set.
       * @return This builder for chaining.
       */
      public Builder setQuery(java.lang.String value) {
        if (value == null) {
          throw new NullPointerException();
        }

        query_ = value;
        onChanged();
        return this;
      }
      /**
       * <code>string query = 1;</code>
       *
       * @return This builder for chaining.
       */
      public Builder clearQuery() {

        query_ = getDefaultInstance().getQuery();
        onChanged();
        return this;
      }
      /**
       * <code>string query = 1;</code>
       *
       * @param value The bytes for query to set.
       * @return This builder for chaining.
       */
      public Builder setQueryBytes(akka.protobufv3.internal.ByteString value) {
        if (value == null) {
          throw new NullPointerException();
        }
        checkByteStringIsUtf8(value);

        query_ = value;
        onChanged();
        return this;
      }

      private int pageNumber_;
      /**
       * <code>int32 page_number = 2;</code>
       *
       * @return The pageNumber.
       */
      public int getPageNumber() {
        return pageNumber_;
      }
      /**
       * <code>int32 page_number = 2;</code>
       *
       * @param value The pageNumber to set.
       * @return This builder for chaining.
       */
      public Builder setPageNumber(int value) {

        pageNumber_ = value;
        onChanged();
        return this;
      }
      /**
       * <code>int32 page_number = 2;</code>
       *
       * @return This builder for chaining.
       */
      public Builder clearPageNumber() {

        pageNumber_ = 0;
        onChanged();
        return this;
      }

      private int resultPerPage_;
      /**
       * <code>int32 result_per_page = 3;</code>
       *
       * @return The resultPerPage.
       */
      public int getResultPerPage() {
        return resultPerPage_;
      }
      /**
       * <code>int32 result_per_page = 3;</code>
       *
       * @param value The resultPerPage to set.
       * @return This builder for chaining.
       */
      public Builder setResultPerPage(int value) {

        resultPerPage_ = value;
        onChanged();
        return this;
      }
      /**
       * <code>int32 result_per_page = 3;</code>
       *
       * @return This builder for chaining.
       */
      public Builder clearResultPerPage() {

        resultPerPage_ = 0;
        onChanged();
        return this;
      }

      @java.lang.Override
      public final Builder setUnknownFields(
          final akka.protobufv3.internal.UnknownFieldSet unknownFields) {
        return super.setUnknownFields(unknownFields);
      }

      @java.lang.Override
      public final Builder mergeUnknownFields(
          final akka.protobufv3.internal.UnknownFieldSet unknownFields) {
        return super.mergeUnknownFields(unknownFields);
      }

      // @@protoc_insertion_point(builder_scope:MyMessageV3)
    }

    // @@protoc_insertion_point(class_scope:MyMessageV3)
    private static final akka.remote.protobuf.v3.ProtobufProtocolV3.MyMessageV3 DEFAULT_INSTANCE;

    static {
      DEFAULT_INSTANCE = new akka.remote.protobuf.v3.ProtobufProtocolV3.MyMessageV3();
    }

    public static akka.remote.protobuf.v3.ProtobufProtocolV3.MyMessageV3 getDefaultInstance() {
      return DEFAULT_INSTANCE;
    }

    private static final akka.protobufv3.internal.Parser<MyMessageV3> PARSER =
        new akka.protobufv3.internal.AbstractParser<MyMessageV3>() {
          @java.lang.Override
          public MyMessageV3 parsePartialFrom(
              akka.protobufv3.internal.CodedInputStream input,
              akka.protobufv3.internal.ExtensionRegistryLite extensionRegistry)
              throws akka.protobufv3.internal.InvalidProtocolBufferException {
            return new MyMessageV3(input, extensionRegistry);
          }
        };

    public static akka.protobufv3.internal.Parser<MyMessageV3> parser() {
      return PARSER;
    }

    @java.lang.Override
    public akka.protobufv3.internal.Parser<MyMessageV3> getParserForType() {
      return PARSER;
    }

    @java.lang.Override
    public akka.remote.protobuf.v3.ProtobufProtocolV3.MyMessageV3 getDefaultInstanceForType() {
      return DEFAULT_INSTANCE;
    }
  }

  private static final akka.protobufv3.internal.Descriptors.Descriptor
      internal_static_MyMessageV3_descriptor;
  private static final akka.protobufv3.internal.GeneratedMessageV3.FieldAccessorTable
      internal_static_MyMessageV3_fieldAccessorTable;

  public static akka.protobufv3.internal.Descriptors.FileDescriptor getDescriptor() {
    return descriptor;
  }

  private static akka.protobufv3.internal.Descriptors.FileDescriptor descriptor;

  static {
    java.lang.String[] descriptorData = {
      "\n\030ProtobufProtocolV3.proto\"J\n\013MyMessageV"
          + "3\022\r\n\005query\030\001 \001(\t\022\023\n\013page_number\030\002 \001(\005\022\027\n"
          + "\017result_per_page\030\003 \001(\005B\031\n\027akka.remote.pr"
          + "otobuf.v3b\006proto3"
    };
    descriptor =
        akka.protobufv3.internal.Descriptors.FileDescriptor.internalBuildGeneratedFileFrom(
            descriptorData, new akka.protobufv3.internal.Descriptors.FileDescriptor[] {});
    internal_static_MyMessageV3_descriptor = getDescriptor().getMessageTypes().get(0);
    internal_static_MyMessageV3_fieldAccessorTable =
        new akka.protobufv3.internal.GeneratedMessageV3.FieldAccessorTable(
            internal_static_MyMessageV3_descriptor,
            new java.lang.String[] {
              "Query", "PageNumber", "ResultPerPage",
            });
  }

  // @@protoc_insertion_point(outer_class_scope)
}
