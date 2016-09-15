# Predefined Directives (by trait)

All predefined directives are organized into traits that form one part of the overarching `Directives` trait.

<a id="request-directives"></a>
## Directives filtering or extracting from the request

@ref[MethodDirectives](method-directives/index.md#methoddirectives)
:   Filter and extract based on the request method.

@ref[HeaderDirectives](header-directives/index.md#headerdirectives)
:   Filter and extract based on request headers.

@ref[PathDirectives](path-directives/index.md#pathdirectives)
:   Filter and extract from the request URI path.

@ref[HostDirectives](host-directives/index.md#hostdirectives)
:   Filter and extract based on the target host.

@ref[ParameterDirectives](parameter-directives/index.md#parameterdirectives), @ref[FormFieldDirectives](form-field-directives/index.md#formfielddirectives)
:   Filter and extract based on query parameters or form fields.

@ref[CodingDirectives](coding-directives/index.md#codingdirectives)
:   Filter and decode compressed request content.

@ref[Marshalling Directives](marshalling-directives/index.md#marshallingdirectives)
:   Extract the request entity.

@ref[SchemeDirectives](scheme-directives/index.md#schemedirectives)
:   Filter and extract based on the request scheme.

@ref[SecurityDirectives](security-directives/index.md#securitydirectives)
:   Handle authentication data from the request.

@ref[CookieDirectives](cookie-directives/index.md#cookiedirectives)
:   Filter and extract cookies.

@ref[BasicDirectives](basic-directives/index.md#basicdirectives) and @ref[MiscDirectives](misc-directives/index.md#miscdirectives)
:   Directives handling request properties.

@ref[FileUploadDirectives](file-upload-directives/index.md#fileuploaddirectives)
:   Handle file uploads.

<a id="response-directives"></a>
## Directives creating or transforming the response

@ref[CacheConditionDirectives](cache-condition-directives/index.md#cacheconditiondirectives)
:   Support for conditional requests (`304 Not Modified` responses).

@ref[CookieDirectives](cookie-directives/index.md#cookiedirectives)
:   Set, modify, or delete cookies.

@ref[CodingDirectives](coding-directives/index.md#codingdirectives)
:   Compress responses.

@ref[FileAndResourceDirectives](file-and-resource-directives/index.md#fileandresourcedirectives)
:   Deliver responses from files and resources.

@ref[RangeDirectives](range-directives/index.md#rangedirectives)
:   Support for range requests (`206 Partial Content` responses).

@ref[RespondWithDirectives](respond-with-directives/index.md#respondwithdirectives)
:   Change response properties.

@ref[RouteDirectives](route-directives/index.md#routedirectives)
:   Complete or reject a request with a response.

@ref[BasicDirectives](basic-directives/index.md#basicdirectives) and @ref[MiscDirectives](misc-directives/index.md#miscdirectives)
:   Directives handling or transforming response properties.

@ref[TimeoutDirectives](timeout-directives/index.md#timeoutdirectives)
:   Configure request timeouts and automatic timeout responses.

## List of predefined directives by trait

@@toc { depth=1 }

@@@ index

* [basic-directives/index](basic-directives/index.md)
* [cache-condition-directives/index](cache-condition-directives/index.md)
* [coding-directives/index](coding-directives/index.md)
* [cookie-directives/index](cookie-directives/index.md)
* [debugging-directives/index](debugging-directives/index.md)
* [execution-directives/index](execution-directives/index.md)
* [file-and-resource-directives/index](file-and-resource-directives/index.md)
* [file-upload-directives/index](file-upload-directives/index.md)
* [form-field-directives/index](form-field-directives/index.md)
* [future-directives/index](future-directives/index.md)
* [header-directives/index](header-directives/index.md)
* [host-directives/index](host-directives/index.md)
* [marshalling-directives/index](marshalling-directives/index.md)
* [method-directives/index](method-directives/index.md)
* [misc-directives/index](misc-directives/index.md)
* [parameter-directives/index](parameter-directives/index.md)
* [path-directives/index](path-directives/index.md)
* [range-directives/index](range-directives/index.md)
* [respond-with-directives/index](respond-with-directives/index.md)
* [route-directives/index](route-directives/index.md)
* [scheme-directives/index](scheme-directives/index.md)
* [security-directives/index](security-directives/index.md)
* [websocket-directives/index](websocket-directives/index.md)
* [timeout-directives/index](timeout-directives/index.md)

@@@