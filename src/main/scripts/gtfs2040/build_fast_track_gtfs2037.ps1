param(
    [ValidateSet('analyze', 'build')]
    [string]$Mode = 'analyze'
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..\..')).Path
$previousMavenOpts = $env:MAVEN_OPTS

try {
    Set-Location $projectRoot

    if ([string]::IsNullOrWhiteSpace($env:MAVEN_OPTS)) {
        $env:MAVEN_OPTS = '-Xmx6g'
    }

    & .\mvnw.cmd -q -DskipTests compile
    if ($LASTEXITCODE -ne 0) {
        throw "Maven compilation failed with exit code $LASTEXITCODE."
    }

    & .\mvnw.cmd -q exec:java `
        '-Dexec.mainClass=org.matsim.project.prepare.BuildFastTrackGtfs2037' `
        "-Dexec.args=--$Mode"
    if ($LASTEXITCODE -ne 0) {
        throw "Fast Track GTFS $Mode mode failed with exit code $LASTEXITCODE."
    }
} finally {
    $env:MAVEN_OPTS = $previousMavenOpts
}
