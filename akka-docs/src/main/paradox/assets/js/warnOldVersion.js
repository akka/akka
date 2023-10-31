function initOldVersionWarnings($, thisVersion, projectUrl) {
    if (projectUrl && projectUrl !== "") {
        var schemeLessUrl = projectUrl;
        if (projectUrl.startsWith("http://")) projectUrl = schemeLessUrl.substring(5);
        else if (projectUrl.startsWith("https://")) projectUrl = schemeLessUrl.substring(6);
        const url = schemeLessUrl + (schemeLessUrl.endsWith("\/") ? "" : "/") + "paradox.json";
        $.get(url, function (versionData) {
            const currentVersion = versionData.version;
            if (thisVersion !== currentVersion) {
                showVersionWarning(thisVersion, currentVersion, projectUrl);
            }
        });
    }
}

function showVersionWarning(thisVersion, currentVersion, projectUrl) {
    $('#docs').prepend(
        '<div class="callout warning" style="margin-top: 16px">' +
        '<p><span style="font-weight: bold">This documentation regards version ' + thisVersion + ', ' +
        'however the current version is <a href="' + projectUrl + '">' + currentVersion + '</a>.</span></p>' +
        '</div>'
    );
}
