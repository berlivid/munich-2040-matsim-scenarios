param(
    [ValidateSet('all', 'bau', 'fast-track')]
    [string]$Scenario = 'all',

    [ValidateSet('build', 'analyze')]
    [string]$Mode = 'build'
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..\..')).Path
$previousMavenOpts = $env:MAVEN_OPTS

try {
    Set-Location $projectRoot

    if ([string]::IsNullOrWhiteSpace($env:MAVEN_OPTS)) {
        $env:MAVEN_OPTS = '-Xmx12g'
    }

    & .\mvnw.cmd -q -DskipTests compile
    if ($LASTEXITCODE -ne 0) {
        throw "Maven compilation failed with exit code $LASTEXITCODE."
    }

    if ($Mode -eq 'analyze' -and $Scenario -ne 'fast-track') {
        throw "Mobility Hub analysis is Fast-Track-only; use -Scenario fast-track."
    }

    $converterArgs = if ($Mode -eq 'analyze') {
        '--analyze-mobility-hubs --scenario fast-track'
    } elseif ($Scenario -eq 'all') {
        '--all'
    } else {
        "--scenario $Scenario"
    }

    & .\mvnw.cmd -q exec:java `
        '-Dexec.mainClass=org.matsim.project.prepare.CreateGtfs2037MunichTransit' `
        "-Dexec.args=$converterArgs"
    if ($LASTEXITCODE -ne 0) {
        throw "MATSim transit conversion failed with exit code $LASTEXITCODE."
    }
} finally {
    $env:MAVEN_OPTS = $previousMavenOpts
}
