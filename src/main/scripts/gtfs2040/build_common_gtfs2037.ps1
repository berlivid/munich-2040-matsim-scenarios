param(
    [ValidateSet('analyze', 'build')]
    [string]$Mode = 'analyze'
)

$ErrorActionPreference = 'Stop'
$root = Resolve-Path (Join-Path $PSScriptRoot '..\..\..\..')
$previousMavenOpts = $env:MAVEN_OPTS
Push-Location $root
try {
    if ([string]::IsNullOrWhiteSpace($env:MAVEN_OPTS)) { $env:MAVEN_OPTS = '-Xmx6g' }
    & .\mvnw.cmd -q -DskipTests compile
    if ($LASTEXITCODE -ne 0) { throw "Compilation failed with exit code $LASTEXITCODE." }
    & .\mvnw.cmd -q exec:java '-Dexec.mainClass=org.matsim.project.prepare.BuildCommonGtfs2037Measures' "-Dexec.args=--$Mode"
    if ($LASTEXITCODE -ne 0) { throw "Common GTFS $Mode failed with exit code $LASTEXITCODE." }
} finally {
    $env:MAVEN_OPTS = $previousMavenOpts
    Pop-Location
}
