/**
 * Copyright (C) 2016-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.http.javadsl.server.directives;

import akka.http.impl.engine.rendering.BodyPartRenderer;
import akka.http.javadsl.model.*;
import akka.http.javadsl.server.Route;
import akka.http.javadsl.server.directives.FileInfo;
import akka.http.javadsl.testkit.JUnitRouteTest;
import org.junit.Test;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;


public class FileUploadDirectivesExampleTest extends JUnitRouteTest {

    @Test
    public void testUploadedFile() {
        //#uploadedFile
        // function (FileInfo, File) => Route to process the file metadata and file itself
        BiFunction<FileInfo, File, Route> infoFileRoute =
                (info, file) -> {
                    // do something with the file and file metadata ...
                    file.delete();
                    return complete(StatusCodes.OK);
                };


        final Route route = uploadedFile("csv", infoFileRoute);

        Map<String, String> filenameMapping = new HashMap<>();
        filenameMapping.put("filename", "data.csv");
        // tests:
        akka.http.javadsl.model.Multipart.FormData multipartForm =
                HttpEntities.fromParts(HttpEntities.createStrict("csv", HttpEntities.create(ContentTypes.TEXT_PLAIN_UTF8, "1,5,7\n11,13,17"), filenameMapping));

        testRoute(route).run(HttpRequest.POST("/").withEntity(multipartForm.toEntity(HttpCharsets.UTF_8, BodyPartRenderer.
                randomBoundary(BodyPartRenderer.randomBoundary$default$1(), BodyPartRenderer.randomBoundary$default$2())))).assertStatusCode(StatusCodes.OK);
        //#
    }
}
