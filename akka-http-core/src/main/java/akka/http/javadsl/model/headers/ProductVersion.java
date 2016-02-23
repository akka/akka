/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

public abstract class ProductVersion {
    public abstract String product();
    public abstract String version();
    public abstract String comment();

    public static ProductVersion create(String product, String version, String comment) {
        return new akka.http.scaladsl.model.headers.ProductVersion(product, version, comment);
    }
    public static ProductVersion create(String product, String version) {
        return create(product, version, "");
    }
}
