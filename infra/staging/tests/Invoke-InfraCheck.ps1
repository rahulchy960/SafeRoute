# SPDX-License-Identifier: AGPL-3.0-only
<#
.SYNOPSIS
    Quality gate for infra/staging: PSScriptAnalyzer, then the Pester tests.

.DESCRIPTION
    Used by .github/workflows/infra-ci.yml and locally:

        powershell -NoProfile -ExecutionPolicy Bypass -File infra\staging\tests\Invoke-InfraCheck.ps1
        pwsh -NoProfile -File infra/staging/tests/Invoke-InfraCheck.ps1

    Downloads the two pinned modules from the PowerShell Gallery into a temporary folder, checks
    their SHA-512 against the values below, and loads them from there. Nothing is installed into
    the user profile. The tests need `node` on PATH and no Google or GitHub access: they never
    start the real gcloud or gh.

    Exit code 0 = no analyzer finding and no failed test.
#>
[CmdletBinding()]
param(
    [string]$ModulePath = (Join-Path ([System.IO.Path]::GetTempPath()) 'saferoute-infra-psmodules')
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

# Version and SHA-512 (base64, as published by the PowerShell Gallery) of each module.
$pinned = @(
    @{ Name = 'Pester'; Version = '6.2.0'; Sha512 = 'u9nl8XG2uMv86c1fQYOsnKcfs8acGWTWuhxyjIm0DqLdq5vR/q9dzPqrNf0rZ36gzXcF3yCs0kH53FjQ+fsT6A==' }
    @{ Name = 'PSScriptAnalyzer'; Version = '1.25.0'; Sha512 = '5/tXMmLmBLqymRSuYpIdJLl8L4i1GbQSd7QD72zhY/FLfRs23HNEmmjlrlqNlQKJGxDCZBMf0YE63ym/ExwWYw==' }
)

$staging = Split-Path -Parent $PSScriptRoot
Add-Type -AssemblyName System.IO.Compression.FileSystem
# Windows PowerShell 5.1 does not offer TLS 1.2 unless asked.
[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12

foreach ($module in $pinned) {
    $target = Join-Path (Join-Path $ModulePath $module.Name) $module.Version
    if (Test-Path -LiteralPath (Join-Path $target "$($module.Name).psd1")) { continue }
    $package = Join-Path ([System.IO.Path]::GetTempPath()) ("$($module.Name).$($module.Version)." + [guid]::NewGuid().ToString('N') + '.zip')
    try {
        Invoke-WebRequest -UseBasicParsing -Uri "https://www.powershellgallery.com/api/v2/package/$($module.Name)/$($module.Version)" -OutFile $package
        $sha = [System.Security.Cryptography.SHA512]::Create()
        try { $actual = [Convert]::ToBase64String($sha.ComputeHash([System.IO.File]::ReadAllBytes($package))) }
        finally { $sha.Dispose() }
        if ($actual -cne $module.Sha512) { throw "Checksum mismatch for $($module.Name) $($module.Version). Nothing was loaded." }
        if (Test-Path -LiteralPath $target) { Remove-Item -LiteralPath $target -Recurse -Force }
        [System.IO.Compression.ZipFile]::ExtractToDirectory($package, $target)
    }
    finally {
        if (Test-Path -LiteralPath $package) { Remove-Item -LiteralPath $package -Force }
    }
}

$env:PSModulePath = $ModulePath + [System.IO.Path]::PathSeparator + $env:PSModulePath
foreach ($module in $pinned) { Import-Module -Name $module.Name -RequiredVersion $module.Version }
Write-Output ("PowerShell {0} ({1}), Pester {2}, PSScriptAnalyzer {3}" -f $PSVersionTable.PSVersion, $PSVersionTable.PSEdition,
    (Get-Module Pester).Version, (Get-Module PSScriptAnalyzer).Version)

$findings = @(Invoke-ScriptAnalyzer -Path $staging -Recurse -Settings (Join-Path $staging 'PSScriptAnalyzerSettings.psd1'))
foreach ($finding in $findings) {
    Write-Output ('{0}:{1} [{2}] {3}: {4}' -f $finding.ScriptName, $finding.Line, $finding.Severity, $finding.RuleName, $finding.Message)
}
Write-Output "PSScriptAnalyzer: $($findings.Count) finding(s)"

$configuration = New-PesterConfiguration
$configuration.Run.Path = $PSScriptRoot
$configuration.Run.PassThru = $true
$configuration.Output.Verbosity = 'Detailed'
$result = Invoke-Pester -Configuration $configuration
Write-Output "Pester: $($result.PassedCount) passed, $($result.FailedCount) failed, $($result.SkippedCount) skipped"

if ($findings.Count -gt 0 -or $result.Result -ne 'Passed') { exit 1 }
exit 0
