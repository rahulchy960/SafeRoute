# SPDX-License-Identifier: AGPL-3.0-only
<#
.SYNOPSIS
    Audits, completes and verifies the one-time Google Cloud STAGING setup for SafeRoute.

.DESCRIPTION
    One mode per run:

      -Audit (default)   Read-only. Prints item, expected, found and PRESENT / MISSING / WRONG,
                         then the next action for everything that is not PRESENT.
      -Apply             Creates only what the audit found MISSING. Asks first for the project ID,
                         then Y/N before each step. Stops at the first error.
      -SetGithubSecrets  Sets the GitHub environment secrets and variables that
                         .github/workflows/deploy-staging.yml reads. Keeps existing values unless
                         -Force is passed.
      -Verify            Audit plus a comparison of GitHub's secrets and variables (names only)
                         with what the workflow references. Exit code 0 only if nothing is missing.

    Add -Plan to any mode to print every command without running anything.

    Never created or changed here: the Cloud SQL instance, its database and user, existing
    secrets, existing service accounts, the Workload Identity pool and the Artifact Registry
    repository. Nothing is ever deleted. No service-account key is ever created.

    Routing (P012a2, ADR 0020): -Apply creates one new account without any role for the OSRM
    services (sa-osrm-runtime), lets the deploy account use it, adds a log exclusion per OSRM
    service so that request URLs (they hold coordinates) are never stored, and lets the API's
    account call the services once the osrm-staging workflow has created them. The services
    themselves are never created, changed or made public here.

    Output hides the project ID, the project number, e-mail addresses and URLs unless -ShowIds is
    passed. Secret values are never printed. Design: docs/adr/0007-gcp-staging-topology.md.
    How to use it: docs/runbooks/gcp-staging-setup.md.

.EXAMPLE
    .\infra\staging\bootstrap-staging.ps1

.EXAMPLE
    .\infra\staging\bootstrap-staging.ps1 -Apply -DbName saferoute_staging -DbUser saferoute_app
#>
[Diagnostics.CodeAnalysis.SuppressMessageAttribute('PSAvoidUsingPlainTextForPassword', 'PasswordSecret',
    Justification = 'The NAME of a Secret Manager secret, not a password.')]
[Diagnostics.CodeAnalysis.SuppressMessageAttribute('PSReviewUnusedParameter', '',
    Justification = 'Script parameters are read through $PSBoundParameters and New-BootstrapConfig.')]
[CmdletBinding(DefaultParameterSetName = 'Audit')]
param(
    [Parameter(ParameterSetName = 'Audit')] [switch]$Audit,
    [Parameter(ParameterSetName = 'Apply', Mandatory = $true)] [switch]$Apply,
    [Parameter(ParameterSetName = 'SetGithubSecrets', Mandatory = $true)] [switch]$SetGithubSecrets,
    [Parameter(ParameterSetName = 'Verify', Mandatory = $true)] [switch]$Verify,

    [switch]$Plan,
    [switch]$ShowIds,
    [switch]$Force,

    [string]$DeploySa = 'sa-deploy',
    [string]$RuntimeSa = 'sa-api-runtime',
    [string]$MigrationSa = 'sa-migration',
    [string]$PoolId = 'github',
    [string]$ProviderId = 'github-saferoute',
    [string]$SqlInstance = 'saferoute-db',
    [string]$DbName = '',
    [string]$DbUser = '',
    [string]$ArRepo = 'saferoute',
    [string]$ApiService = 'saferoute-api',
    [string]$MigrationJob = 'saferoute-migrate',
    [string]$UrlSecret = 'saferoute-staging-database-url',
    [string]$PasswordSecret = 'db-app-password',
    [string]$FirebaseProjectId = '',
    [string]$WorkflowPath = '',
    [string]$OsrmRuntimeSa = 'sa-osrm-runtime',
    [string]$OsrmWalkingService = 'saferoute-osrm-walking',
    [string]$OsrmDrivingService = 'saferoute-osrm-driving'
)

$ErrorActionPreference = 'Stop'

# The only hard-coded facts (ADR 0007): the repository, the region and the GitHub environment.
$script:Repo = 'rahulchy960/SafeRoute'
$script:Region = 'asia-south1'
$script:GithubEnvironment = 'staging'
$script:Issuer = 'https://token.actions.githubusercontent.com'
$script:HelloImage = 'us-docker.pkg.dev/cloudrun/container/hello'
$script:RequiredApis = @(
    'run.googleapis.com', 'artifactregistry.googleapis.com', 'sqladmin.googleapis.com',
    'secretmanager.googleapis.com', 'iam.googleapis.com', 'iamcredentials.googleapis.com',
    'sts.googleapis.com', 'cloudresourcemanager.googleapis.com'
)
$script:Runbook = 'docs/runbooks/gcp-staging-setup.md'
# Environment variables the deploy workflow reads but does not need: unset means "use the API's
# default". They are reported as a NOTE, never set by this script, and never fail -Verify.
$script:OptionalVariables = @('ROUTING_TIMEOUT_MS')
$script:Session = @{ ShowIds = $false; Plan = $false; Masks = @(); Secrets = @(); Lines = @() }

# --- Output: everything printed goes through Write-Line, which masks identifiers ---

function Initialize-Session {
    param([bool]$ShowIds, [bool]$Plan)
    $script:Session = @{
        ShowIds = $ShowIds
        Plan    = $Plan
        Masks   = New-Object System.Collections.ArrayList
        Secrets = New-Object System.Collections.ArrayList
        Lines   = New-Object System.Collections.ArrayList
    }
}

function Register-Mask {
    param([string]$Value, [string]$Label)
    if (-not [string]::IsNullOrEmpty($Value)) {
        [void]$script:Session.Masks.Add(@{ Value = $Value; Label = $Label })
    }
}

function Register-SecretValue {
    param([string]$Value)
    if (-not [string]::IsNullOrEmpty($Value)) { [void]$script:Session.Secrets.Add($Value) }
}

function ConvertTo-SafeText {
    <# Removes secret values always, and identifiers unless -ShowIds was passed. #>
    param([string]$Text)
    if ([string]::IsNullOrEmpty($Text)) { return '' }
    $safe = $Text
    foreach ($secret in $script:Session.Secrets) { $safe = $safe.Replace($secret, '<secret>') }
    if ($script:Session.ShowIds) { return $safe }
    $safe = [regex]::Replace($safe, '[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}', '<sa-email>')
    $safe = [regex]::Replace($safe, 'https?://[^\s''"<>]+', '<url>')
    foreach ($mask in $script:Session.Masks) { $safe = $safe.Replace($mask.Value, $mask.Label) }
    return $safe
}

function Write-Line {
    [Diagnostics.CodeAnalysis.SuppressMessageAttribute('PSAvoidUsingWriteHost', '',
        Justification = 'Interactive script. Write-Host goes to the information stream (6>) and, unlike Write-Information, is recorded by Windows PowerShell 5.1 transcripts.')]
    param([string]$Text = '')
    $safe = ConvertTo-SafeText $Text
    [void]$script:Session.Lines.Add($safe)
    Write-Host $safe
}

function Read-Answer {
    <# The only place that reads from the keyboard, so tests can replace it. #>
    param([string]$Prompt)
    return (Read-Host -Prompt (ConvertTo-SafeText $Prompt))
}

function Confirm-Step {
    param([string]$Question)
    while ($true) {
        $answer = (Read-Answer "$Question [y/n]").Trim().ToLowerInvariant()
        if ($answer -eq 'y' -or $answer -eq 'yes') { return $true }
        if ($answer -eq 'n' -or $answer -eq 'no') { return $false }
    }
}

# --- Running gcloud and gh ---

function ConvertTo-ArgumentString {
    <# Quotes every argument with the Windows argv rules, which .NET also applies on Linux. #>
    param([string[]]$Arguments)
    $parts = foreach ($argument in $Arguments) {
        if ($argument -match '[\r\n]') { throw 'A command argument contains a line break.' }
        $escaped = [regex]::Replace($argument, '(\\*)"', '$1$1\"')
        $escaped = [regex]::Replace($escaped, '(\\+)$', '$1$1')
        '"' + $escaped + '"'
    }
    return ($parts -join ' ')
}

function ConvertTo-PercentEncoded {
    <# RFC 3986: everything except A-Z a-z 0-9 - . _ ~ becomes %XX (UTF-8). Same on every .NET. #>
    param([string]$Text)
    $builder = New-Object System.Text.StringBuilder
    foreach ($byte in [System.Text.Encoding]::UTF8.GetBytes($Text)) {
        $char = [char]$byte
        if ($byte -lt 128 -and $char -match '[A-Za-z0-9\-._~]') { [void]$builder.Append($char) }
        else { [void]$builder.Append('%' + $byte.ToString('X2')) }
    }
    return $builder.ToString()
}

function Resolve-Tool {
    param([string]$Name)
    $command = Get-Command -Name $Name -CommandType Application -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($null -eq $command) { throw "'$Name' was not found on PATH. Install it, then open a new window." }
    return $command.Path
}

function Invoke-External {
    <#
        Starts a program and returns its exit code, standard output and standard error.
        -StdinBytes are written to the program's standard input exactly as given: no line break,
        no byte-order mark. PowerShell's own pipe adds both, which would end up inside a secret.
        On Windows, gcloud is a .cmd file and has to be started through cmd.exe.
    #>
    param([string]$Tool, [string[]]$Arguments, [byte[]]$StdinBytes)

    $path = Resolve-Tool $Tool
    $startInfo = New-Object System.Diagnostics.ProcessStartInfo
    if ($path -match '\.(cmd|bat)$') {
        foreach ($argument in $Arguments) {
            if ($argument -match '["%]') { throw "An argument for $Tool contains a character cmd.exe would change." }
        }
        $startInfo.FileName = $env:ComSpec
        $startInfo.Arguments = '/d /s /c "' + (ConvertTo-ArgumentString @($path)) + ' ' +
            (ConvertTo-ArgumentString $Arguments) + '"'
    }
    else {
        $startInfo.FileName = $path
        $startInfo.Arguments = ConvertTo-ArgumentString $Arguments
    }
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardInput = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $startInfo.StandardOutputEncoding = $utf8
    $startInfo.StandardErrorEncoding = $utf8
    # gcloud must never stop to ask a question that nobody can see.
    $startInfo.EnvironmentVariables['CLOUDSDK_CORE_DISABLE_PROMPTS'] = '1'

    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $startInfo
    # Windows PowerShell 5.1 creates the child's input writer from Console.InputEncoding and that
    # writer emits a byte-order mark the moment the process starts. PowerShell 7 has a property
    # for the encoding; 5.1 needs the console encoding swapped for the duration of Start().
    $consoleEncoding = $null
    if ($startInfo.PSObject.Properties.Name -contains 'StandardInputEncoding') {
        $startInfo.StandardInputEncoding = $utf8
    }
    elseif ([Console]::InputEncoding.GetPreamble().Length -gt 0) {
        $consoleEncoding = [Console]::InputEncoding
        [Console]::InputEncoding = $utf8
    }
    try { [void]$process.Start() }
    finally { if ($null -ne $consoleEncoding) { [Console]::InputEncoding = $consoleEncoding } }
    $outTask = $process.StandardOutput.ReadToEndAsync()
    $errTask = $process.StandardError.ReadToEndAsync()
    # The raw stream, not the StreamWriter, so that nothing is encoded or buffered a second time.
    $stdin = $process.StandardInput.BaseStream
    try {
        if ($null -ne $StdinBytes -and $StdinBytes.Length -gt 0) {
            $stdin.Write($StdinBytes, 0, $StdinBytes.Length)
            $stdin.Flush()
        }
    }
    finally { $stdin.Dispose() }
    $process.WaitForExit()
    return [pscustomobject]@{
        ExitCode = $process.ExitCode
        StdOut   = $outTask.Result
        StdErr   = $errTask.Result
    }
}

function Format-CommandText {
    param([string]$Tool, [string[]]$Arguments)
    $shown = foreach ($argument in $Arguments) {
        if ($argument -notmatch '[\s&|<>()'']') { $argument }
        elseif ($argument.Contains("'")) { '"' + $argument + '"' }
        else { "'" + $argument + "'" }
    }
    return ($Tool + ' ' + ($shown -join ' '))
}

function Invoke-Tool {
    <# Runs a command, or only prints it when -Plan was passed. Never prints what it returns. #>
    param([string]$Tool, [string[]]$Arguments, [byte[]]$StdinBytes, [string]$StdinNote = '')
    if ($script:Session.Plan) {
        $note = ''
        if ($StdinNote) { $note = "   (standard input: $StdinNote)" }
        Write-Line ('  would run: ' + (Format-CommandText $Tool $Arguments) + $note)
        return [pscustomobject]@{ ExitCode = 0; StdOut = ''; StdErr = ''; Planned = $true }
    }
    $result = Invoke-External -Tool $Tool -Arguments $Arguments -StdinBytes $StdinBytes
    Add-Member -InputObject $result -NotePropertyName Planned -NotePropertyValue $false -Force
    return $result
}

function Get-ErrorSummary {
    param($Result)
    $text = ('' + $Result.StdErr).Trim()
    if (-not $text) { $text = "exit code $($Result.ExitCode)" }
    $first = ($text -split "`r?`n" | Where-Object { $_.Trim() } | Select-Object -First 2) -join ' '
    return $first
}

function Invoke-GcloudJson {
    <# Status: ok (Data holds the parsed JSON), notfound, error, or planned (-Plan). #>
    param([string[]]$Arguments)
    $result = Invoke-Tool -Tool 'gcloud' -Arguments ($Arguments + '--format=json')
    if ($result.Planned) { return [pscustomobject]@{ Status = 'planned'; Data = $null; Message = '' } }
    if ($result.ExitCode -ne 0) {
        $status = 'error'
        if ($result.StdErr -match 'NOT_FOUND|not found|does not exist|Cannot find') { $status = 'notfound' }
        return [pscustomobject]@{ Status = $status; Data = $null; Message = (Get-ErrorSummary $result) }
    }
    $data = $null
    if (-not [string]::IsNullOrWhiteSpace($result.StdOut)) { $data = $result.StdOut | ConvertFrom-Json }
    return [pscustomobject]@{ Status = 'ok'; Data = $data; Message = '' }
}

function Get-GcloudLine {
    <# For --format=value(...) output: the non-empty lines, or $null if the command failed. #>
    param([string[]]$Arguments)
    $result = Invoke-Tool -Tool 'gcloud' -Arguments $Arguments
    if ($result.Planned -or $result.ExitCode -ne 0) { return $null }
    $lines = @($result.StdOut -split "`r?`n" | ForEach-Object { $_.Trim() } | Where-Object { $_ })
    # The comma keeps an empty list an empty list; PowerShell would otherwise turn it into $null.
    return , $lines
}

# --- Audit ---

function Add-AuditItem {
    param($State, [string]$Id, [string]$Item, [string]$Expected, [string]$Found, [string]$Status, [string]$Next = '')
    if ($script:Session.Plan) { $Status = 'NOT RUN'; $Found = '-'; $Next = '' }
    [void]$State.Items.Add([pscustomobject]@{
            Id = $Id; Item = $Item; Expected = $Expected; Found = $Found; Status = $Status; Next = $Next
        })
}

function Get-ItemStatus {
    param($State, [string]$Id)
    $item = $State.Items | Where-Object { $_.Id -eq $Id } | Select-Object -First 1
    if ($null -eq $item) { return '' }
    return $item.Status
}

function Get-StatusOf {
    <# Maps a describe result to a status word. #>
    param($Response)
    switch ($Response.Status) {
        'ok' { return 'PRESENT' }
        'notfound' { return 'MISSING' }
        'planned' { return 'NOT RUN' }
        default { return 'UNKNOWN' }
    }
}

function Get-ProjectContext {
    param($State)
    if ($script:Session.Plan) {
        [void](Invoke-Tool 'gcloud' @('config', 'get-value', 'project'))
        [void](Invoke-Tool 'gcloud' @('projects', 'describe', '<project-id>', '--format=value(projectNumber)'))
        $State.ProjectId = '<project-id>'
        $State.ProjectNumber = '<project-number>'
        $State.ProviderName = '<provider-resource-name>'
        $State.ConnectionName = '<connection-name>'
        $State.DbName = '<db-name>'
        $State.DbUser = '<db-user>'
        return $true
    }
    $result = Invoke-Tool 'gcloud' @('config', 'get-value', 'project')
    $projectId = ('' + $result.StdOut).Trim()
    if ($result.ExitCode -ne 0 -or -not $projectId -or $projectId -eq '(unset)') {
        Write-Line 'No active gcloud project. Run: gcloud config set project <project-id>'
        return $false
    }
    Register-Mask $projectId '<project-id>'
    if ($projectId -match '(prd|prod)') {
        Write-Line 'REFUSED: the active gcloud project looks like PRODUCTION. This script is for staging only.'
        Write-Line 'Switch with: gcloud config set project <staging-project-id>'
        return $false
    }
    $number = Get-GcloudLine @('projects', 'describe', $projectId, '--format=value(projectNumber)')
    if (-not $number) {
        Write-Line 'Could not read the project number. Are you signed in (gcloud auth login) with access to the project?'
        return $false
    }
    $State.ProjectId = $projectId
    $State.ProjectNumber = $number[0]
    # Longer value first, so a number that contains the ID (or the reverse) is still fully masked.
    $script:Session.Masks.Insert(0, @{ Value = $State.ProjectNumber; Label = '<project-number>' })
    return $true
}

function Get-SaEmail {
    param($State, [string]$Name)
    return "$Name@$($State.ProjectId).iam.gserviceaccount.com"
}

function Test-Api {
    param($State)
    $enabled = Get-GcloudLine @('services', 'list', '--enabled', '--format=value(config.name)')
    foreach ($api in $script:RequiredApis) {
        $status = 'MISSING'
        if ($null -eq $enabled) { $status = 'UNKNOWN' } elseif ($enabled -contains $api) { $status = 'PRESENT' }
        $found = 'not enabled'
        if ($status -eq 'PRESENT') { $found = 'enabled' } elseif ($status -eq 'UNKNOWN') { $found = 'could not list APIs' }
        Add-AuditItem $State "api:$api" "API $api" 'enabled' $found $status 'Run with -Apply (enables the API).'
    }
}

function Test-ServiceAccount {
    param($State, $Config)
    $emails = Get-GcloudLine @('iam', 'service-accounts', 'list', '--format=value(email)')
    $State.SaEmails = $emails
    foreach ($key in @('DeploySa', 'RuntimeSa', 'MigrationSa')) {
        $name = $Config[$key]
        $status = 'MISSING'
        if ($null -eq $emails) { $status = 'UNKNOWN' }
        elseif ($emails -contains (Get-SaEmail $State $name)) { $status = 'PRESENT' }
        $found = 'not found'
        if ($status -eq 'PRESENT') { $found = $name } elseif ($status -eq 'UNKNOWN') { $found = 'could not list accounts' }
        Add-AuditItem $State "sa:$key" "Service account ($key)" $name $found $status `
            "Not created by this script. Follow $script:Runbook step 3, or pass -$key <existing name>."
    }
}

function Test-ProviderCondition {
    <#
        A provider is safe only if its condition admits this repository AND refs/heads/main AND the
        staging environment. Claim names: repository, ref, environment (GitHub OIDC reference).
        An "||" could admit more than it seems, so it is never accepted.
    #>
    param([string]$Condition)
    if ([string]::IsNullOrWhiteSpace($Condition)) {
        return @{ Ok = $false; Reason = 'no attribute condition: every GitHub repository would be accepted' }
    }
    if ($Condition -match '\|\||!(?!=)|\?') {
        return @{ Ok = $false; Reason = 'the condition contains "||", "!" or "?" and may accept more than this repository' }
    }
    $flat = ($Condition -replace '\s', '') -replace '"', "'"
    $repo = [regex]::Escape($script:Repo)
    $viaSubject = $flat -match "assertion\.sub=='repo:${repo}:environment:$script:GithubEnvironment'"
    $missing = @()
    if (-not $viaSubject -and $flat -notmatch "(assertion|attribute)\.repository=='$repo'") { $missing += 'repository' }
    if ($flat -notmatch "(assertion|attribute)\.ref=='refs/heads/main'") { $missing += 'ref (refs/heads/main)' }
    if (-not $viaSubject -and $flat -notmatch "(assertion|attribute)\.environment=='$script:GithubEnvironment'") {
        $missing += "environment ($script:GithubEnvironment)"
    }
    if ($missing.Count -gt 0) {
        return @{ Ok = $false; Reason = 'too permissive, it does not restrict: ' + ($missing -join ', ') }
    }
    return @{ Ok = $true; Reason = 'restricts repository, ref and environment' }
}

function Test-WorkloadIdentity {
    param($State, $Config)
    $poolArgs = @("--workload-identity-pool=$($Config.PoolId)", '--location=global')
    $pool = Invoke-GcloudJson @('iam', 'workload-identity-pools', 'describe', $Config.PoolId, '--location=global')
    $poolStatus = Get-StatusOf $pool
    $poolFound = $pool.Message
    if ($pool.Status -eq 'ok') {
        $poolFound = "state $($pool.Data.state)"
        if ($pool.Data.state -ne 'ACTIVE') { $poolStatus = 'WRONG' }
    }
    elseif ($pool.Status -eq 'notfound') { $poolFound = 'not found' }
    Add-AuditItem $State 'wif:pool' 'Workload Identity pool' "$($Config.PoolId), ACTIVE" $poolFound $poolStatus `
        "Not created by this script. Follow $script:Runbook step 7 (a deleted pool is restored with 'undelete')."

    $expected = "$($Config.ProviderId): GitHub issuer, condition = repo + main + $script:GithubEnvironment"
    $next = 'Run with -Apply (creates the provider).'
    if ($poolStatus -ne 'PRESENT' -and $poolStatus -ne 'NOT RUN') {
        Add-AuditItem $State 'wif:provider' 'Workload Identity provider' $expected 'the pool is not usable' 'MISSING' `
            'Fix the pool first, then run -Apply.'
        return
    }
    $list = Invoke-GcloudJson (@('iam', 'workload-identity-pools', 'providers', 'list') + $poolArgs + '--show-deleted')
    if ($list.Status -ne 'ok') {
        Add-AuditItem $State 'wif:provider' 'Workload Identity provider' $expected $list.Message (Get-StatusOf $list) $next
        return
    }
    $providers = @($list.Data | Where-Object { $null -ne $_ })
    $live = @($providers | Where-Object { $_.state -ne 'DELETED' })
    $chosen = $live | Where-Object { ($_.name -split '/')[-1] -eq $Config.ProviderId } | Select-Object -First 1
    if ($null -eq $chosen -and $live.Count -eq 1) { $chosen = $live[0] }
    if ($null -eq $chosen) {
        $found = 'not found'
        if ($live.Count -gt 1) {
            $found = "$($live.Count) providers, none named $($Config.ProviderId): " +
                (($live | ForEach-Object { ($_.name -split '/')[-1] }) -join ', ')
            $next = 'Pass -ProviderId <name> to choose one.'
            Add-AuditItem $State 'wif:provider' 'Workload Identity provider' $expected $found 'WRONG' $next
            return
        }
        $deleted = $providers | Where-Object { ($_.name -split '/')[-1] -eq $Config.ProviderId }
        if ($deleted) {
            $found = 'deleted less than 30 days ago'
            $next = "Restore it: gcloud iam workload-identity-pools providers undelete $($Config.ProviderId) " +
                "--workload-identity-pool=$($Config.PoolId) --location=global"
            Add-AuditItem $State 'wif:provider' 'Workload Identity provider' $expected $found 'WRONG' $next
            return
        }
        Add-AuditItem $State 'wif:provider' 'Workload Identity provider' $expected $found 'MISSING' $next
        return
    }

    $State.ProviderName = $chosen.name
    $State.ProviderId = ($chosen.name -split '/')[-1]
    $problems = @()
    if ($chosen.state -ne 'ACTIVE') { $problems += "state $($chosen.state)" }
    if ($chosen.disabled) { $problems += 'disabled' }
    if ($chosen.oidc.issuerUri -ne $script:Issuer) { $problems += 'issuer is not GitHub Actions' }
    $mapping = $chosen.attributeMapping
    foreach ($key in @('google.subject', 'attribute.repository')) {
        if (-not ($mapping.PSObject.Properties.Name -contains $key)) { $problems += "mapping lacks $key" }
    }
    $condition = Test-ProviderCondition $chosen.attributeCondition
    if (-not $condition.Ok) { $problems += $condition.Reason }
    $found = "$($State.ProviderId): $($condition.Reason)"
    if ($State.ProviderId -ne $Config.ProviderId) {
        $found = "(other name) $found"
        [void]$State.Notes.Add("No provider is named $($Config.ProviderId); using the only one on the pool, " +
            "$($State.ProviderId). Its condition: [$($chosen.attributeCondition)]")
    }
    if ($problems.Count -gt 0) {
        Add-AuditItem $State 'wif:provider' 'Workload Identity provider' $expected ($problems -join '; ') 'WRONG' `
            ("Not changed by this script. Condition found: [$($chosen.attributeCondition)]. " +
            "Fix it by hand ($script:Runbook step 7), or delete the provider and run -Apply.")
        return
    }
    Add-AuditItem $State 'wif:provider' 'Workload Identity provider' $expected $found 'PRESENT'
}

function Test-ArtifactRegistry {
    param($State, $Config)
    $repo = Invoke-GcloudJson @('artifacts', 'repositories', 'describe', $Config.ArRepo, "--location=$script:Region")
    $status = Get-StatusOf $repo
    $found = $repo.Message
    if ($repo.Status -eq 'ok') {
        $found = "format $($repo.Data.format)"
        if ($repo.Data.format -ne 'DOCKER') { $status = 'WRONG' }
    }
    elseif ($repo.Status -eq 'notfound') { $found = 'not found' }
    Add-AuditItem $State 'ar:repo' 'Artifact Registry repository' "$($Config.ArRepo) in $script:Region, DOCKER" $found $status `
        "Not created by this script. Follow $script:Runbook step 2."
    if ($repo.Status -eq 'ok' -or $repo.Status -eq 'planned') {
        $hasPolicy = $false
        if ($repo.Status -eq 'ok' -and $repo.Data.cleanupPolicies) {
            $hasPolicy = @($repo.Data.cleanupPolicies.PSObject.Properties).Count -gt 0
        }
        $policyStatus = 'NOTE'
        $policyFound = 'none (optional: old images are never deleted)'
        if ($hasPolicy) { $policyStatus = 'PRESENT'; $policyFound = 'set' }
        Add-AuditItem $State 'ar:cleanup' 'Registry cleanup policy (optional)' 'a cleanup policy' $policyFound $policyStatus `
            'Optional. -Apply offers to set infra/artifact-registry-cleanup-policy.json.'
        if ($hasPolicy) { Test-RoutingImageKeep $State $repo.Data.cleanupPolicies }
    }
}

function Get-CleanupPolicyFile {
    return (Join-Path (Split-Path -Parent $PSScriptRoot) 'artifact-registry-cleanup-policy.json')
}

function Test-RoutingImageKeep {
    <#
        A cleanup policy that deletes by age also deletes routing images (tag osrm-<profile>-<date>).
        A revision that serves traffic keeps its own copy (Cloud Run imports the image at deploy),
        but the previous revision, which is the rollback target, may need the registry copy, and
        an old graph cannot be rebuilt once its dated extract is gone. So such a policy must have
        a keep rule for the tag prefix "osrm-".
        Policies this script did not write (a name that is not in the policy file) are never
        replaced: the rule is then to be added by hand.
    #>
    param($State, $Policies)
    $entries = @($Policies.PSObject.Properties)
    # The API reports a rule's action as the text DELETE or KEEP; the policy file writes { type }.
    # This only READS which rules delete. (The words are in double quotes because a test forbids
    # the single-quoted form, which is how a gcloud command that deletes would be written.)
    $actionOf = { param($Rule) ('' + $Rule.action.type + $Rule.action).ToUpperInvariant() }
    $deletes = @($entries | Where-Object { (& $actionOf $_.Value).Contains("DELETE") })
    if ($deletes.Count -eq 0) { return }
    $keeps = @($entries | Where-Object {
            (& $actionOf $_.Value).Contains("KEEP") -and @($_.Value.condition.tagPrefixes) -contains 'osrm-'
        })
    $item = 'Registry cleanup keeps routing images (tag osrm-)'
    if ($keeps.Count -gt 0) {
        Add-AuditItem $State 'ar:cleanup-osrm' $item 'a keep rule for tag prefix osrm-' $keeps[0].Name 'PRESENT'
        return
    }
    $ours = @((Get-Content -LiteralPath (Get-CleanupPolicyFile) -Raw | ConvertFrom-Json) | ForEach-Object { $_.name })
    $foreign = @($entries | Where-Object { $ours -notcontains $_.Name } | ForEach-Object { $_.Name })
    $State.CleanupIsOurs = ($foreign.Count -eq 0)
    $next = 'Run with -Apply (sets infra/artifact-registry-cleanup-policy.json again; it now has the keep rule).'
    if (-not $State.CleanupIsOurs) {
        $next = "The repository has policies this script did not write ($($foreign -join ', ')); they are not replaced. " +
            'Add a keep rule by hand: Artifact Registry > the repository > Edit > cleanup policies > Conditional keep, tag prefix osrm-.'
    }
    Add-AuditItem $State 'ar:cleanup-osrm' $item 'a keep rule for tag prefix osrm-' 'a delete rule and no such keep rule' 'NOTE' $next
}

function Select-Candidate {
    <# Returns the wanted name if it exists, or the only candidate; otherwise $null. #>
    param([string[]]$Candidates, [string]$Wanted)
    if ($Wanted) {
        if ($Candidates -contains $Wanted) { return $Wanted }
        return $null
    }
    if (@($Candidates).Count -eq 1) { return @($Candidates)[0] }
    return $null
}

function Test-CloudSql {
    param($State, $Config)
    $manual = "Not created or changed by this script. Follow $script:Runbook step 4."
    $instance = Invoke-GcloudJson @('sql', 'instances', 'describe', $Config.SqlInstance)
    $status = Get-StatusOf $instance
    if ($instance.Status -ne 'ok') {
        $found = $instance.Message
        if ($instance.Status -eq 'notfound') { $found = 'not found' }
        Add-AuditItem $State 'sql:instance' 'Cloud SQL instance' "$($Config.SqlInstance), RUNNABLE" $found $status `
            "$manual If the instance has another name, pass -SqlInstance <name>."
        if ($instance.Status -ne 'planned') { return }
    }
    $data = $instance.Data
    $settings = $data.settings
    $ip = $settings.ipConfiguration
    if ($instance.Status -eq 'ok') {
        $State.ConnectionName = $data.connectionName
        $runStatus = 'PRESENT'
        if ($data.state -ne 'RUNNABLE') { $runStatus = 'WRONG' }
        Add-AuditItem $State 'sql:instance' 'Cloud SQL instance' "$($Config.SqlInstance), RUNNABLE" "state $($data.state)" $runStatus `
            "Start it: gcloud sql instances patch $($Config.SqlInstance) --activation-policy=ALWAYS"
    }
    $checks = @(
        @{ Id = 'version'; Item = 'PostgreSQL version'; Expected = 'POSTGRES_16'; Found = $data.databaseVersion; Ok = ($data.databaseVersion -eq 'POSTGRES_16') },
        @{ Id = 'region'; Item = 'region'; Expected = $script:Region; Found = $data.region; Ok = ($data.region -eq $script:Region) },
        @{ Id = 'backups'; Item = 'automated backups'; Expected = 'on'; Found = "enabled=$($settings.backupConfiguration.enabled)"; Ok = ($settings.backupConfiguration.enabled -eq $true) },
        @{ Id = 'deletion'; Item = 'deletion protection'; Expected = 'on'; Found = "enabled=$($settings.deletionProtectionEnabled)"; Ok = ($settings.deletionProtectionEnabled -eq $true) },
        @{ Id = 'ssl'; Item = 'SSL required'; Expected = 'ENCRYPTED_ONLY or stricter'; Found = "sslMode=$($ip.sslMode)"; Ok = (@('ENCRYPTED_ONLY', 'TRUSTED_CLIENT_CERTIFICATE_REQUIRED') -contains $ip.sslMode -or $ip.requireSsl -eq $true) }
    )
    foreach ($check in $checks) {
        $checkStatus = 'WRONG'
        if ($check.Ok) { $checkStatus = 'PRESENT' }
        Add-AuditItem $State "sql:$($check.Id)" "Cloud SQL $($check.Item)" $check.Expected ('' + $check.Found) $checkStatus $manual
    }
    $networks = @($ip.authorizedNetworks | Where-Object { $null -ne $_ } | ForEach-Object { $_.value })
    $open = @($networks | Where-Object { $_ -eq '0.0.0.0/0' })
    $netStatus = 'PRESENT'
    $netFound = 'none'
    if ($open.Count -gt 0) { $netStatus = 'WRONG'; $netFound = 'open to the whole internet' }
    elseif ($networks.Count -gt 0) { $netStatus = 'NOTE'; $netFound = "$($networks.Count) authorized network(s); ADR 0007 expects none" }
    Add-AuditItem $State 'sql:networks' 'Cloud SQL authorized networks' 'no 0.0.0.0/0' $netFound $netStatus `
        'Remove the network in the console (SQL > Connections > Networking). Not changed by this script.'

    $databases = Get-GcloudLine @('sql', 'databases', 'list', "--instance=$($Config.SqlInstance)", '--format=value(name)')
    $dbCandidates = @($databases | Where-Object { $_ -and $_ -ne 'postgres' })
    $dbName = Select-Candidate $dbCandidates $Config.DbName
    $expectedDb = 'exactly one application database'
    if ($Config.DbName) { $expectedDb = $Config.DbName }
    if ($dbName) {
        $State.DbName = $dbName
        Add-AuditItem $State 'sql:database' 'Cloud SQL database' $expectedDb $dbName 'PRESENT'
    }
    else {
        $dbStatus = 'MISSING'
        $dbFound = 'none besides "postgres"'
        $dbNext = "$manual (create the database)."
        if ($null -eq $databases) { $dbStatus = 'UNKNOWN'; $dbFound = 'could not list databases' }
        elseif ($Config.DbName) { $dbFound = "not found (found: $($dbCandidates -join ', '))" }
        elseif ($dbCandidates.Count -gt 1) {
            $dbStatus = 'WRONG'; $dbFound = "several: $($dbCandidates -join ', ')"; $dbNext = 'Pass -DbName <name> to choose one.'
        }
        Add-AuditItem $State 'sql:database' 'Cloud SQL database' $expectedDb $dbFound $dbStatus $dbNext
    }

    $users = Invoke-GcloudJson @('sql', 'users', 'list', "--instance=$($Config.SqlInstance)")
    $userCandidates = @($users.Data | Where-Object {
            $null -ne $_ -and $_.name -ne 'postgres' -and $_.name -notmatch '^cloudsql' -and
            (-not $_.type -or $_.type -eq 'BUILT_IN')
        } | ForEach-Object { $_.name })
    $dbUser = Select-Candidate $userCandidates $Config.DbUser
    $expectedUser = 'exactly one application user'
    if ($Config.DbUser) { $expectedUser = $Config.DbUser }
    if ($dbUser) {
        $State.DbUser = $dbUser
        Add-AuditItem $State 'sql:user' 'Cloud SQL user' $expectedUser $dbUser 'PRESENT'
    }
    else {
        $userStatus = 'MISSING'
        $userFound = 'none besides "postgres"'
        $userNext = "$manual (create the user)."
        if ($users.Status -ne 'ok') { $userStatus = Get-StatusOf $users; $userFound = 'could not list users' }
        elseif ($Config.DbUser) { $userFound = "not found (found: $($userCandidates -join ', '))" }
        elseif ($userCandidates.Count -gt 1) {
            $userStatus = 'WRONG'; $userFound = "several: $($userCandidates -join ', ')"; $userNext = 'Pass -DbUser <name> to choose one.'
        }
        Add-AuditItem $State 'sql:user' 'Cloud SQL user' $expectedUser $userFound $userStatus $userNext
    }
}

function Test-Secret {
    param($State, $Config)
    $password = Invoke-GcloudJson @('secrets', 'describe', $Config.PasswordSecret)
    $passwordFound = $password.Message
    if ($password.Status -eq 'ok') { $passwordFound = 'exists' } elseif ($password.Status -eq 'notfound') { $passwordFound = 'not found' }
    Add-AuditItem $State 'secret:password' 'Secret with the database password' $Config.PasswordSecret $passwordFound (Get-StatusOf $password) `
        "Not created by this script. Follow $script:Runbook step 5, or pass -PasswordSecret <existing name>."

    $url = Invoke-GcloudJson @('secrets', 'describe', $Config.UrlSecret)
    $urlStatus = Get-StatusOf $url
    $urlFound = $url.Message
    $urlNext = 'Run with -Apply (builds the database URL from the password secret; nothing is printed).'
    if ($url.Status -eq 'notfound') { $urlFound = 'not found' }
    if ($url.Status -eq 'ok') {
        $versions = Invoke-GcloudJson @('secrets', 'versions', 'list', $Config.UrlSecret)
        $enabled = @($versions.Data | Where-Object { $null -ne $_ -and $_.state -eq 'ENABLED' })
        $urlFound = "$($enabled.Count) enabled version(s)"
        if ($versions.Status -ne 'ok') { $urlStatus = 'UNKNOWN'; $urlFound = $versions.Message }
        elseif ($enabled.Count -eq 0) {
            $urlStatus = 'WRONG'
            $urlNext = "An existing secret is not changed by this script. Add a version by hand: $script:Runbook, 'Rotating the password later'."
        }
    }
    Add-AuditItem $State 'secret:url' 'Secret with the database URL' "$($Config.UrlSecret), 1+ enabled version" $urlFound $urlStatus $urlNext
}

function Test-CloudRun {
    param($State, $Config)
    $service = Invoke-GcloudJson @('run', 'services', 'describe', $Config.ApiService, "--region=$script:Region")
    $found = $service.Message
    if ($service.Status -eq 'ok') { $found = 'exists' } elseif ($service.Status -eq 'notfound') { $found = 'not found' }
    Add-AuditItem $State 'run:service' 'Cloud Run service (placeholder or real)' "$($Config.ApiService) in $script:Region" $found (Get-StatusOf $service) `
        'Run with -Apply (deploys the public sample image once, so a previous revision exists).'
}

function Get-IamPolicy {
    <# Returns the bindings of one policy, or $null when the resource is missing or unreadable. #>
    param($State, [string]$Scope, [string[]]$Arguments)
    $response = Invoke-GcloudJson $Arguments
    if ($response.Status -ne 'ok') { $State.Policies[$Scope] = $null; return }
    $State.Policies[$Scope] = @($response.Data.bindings | Where-Object { $null -ne $_ })
}

function Test-PolicyMember {
    param($Bindings, [string]$Member, [string]$Role)
    foreach ($binding in @($Bindings)) {
        if ($binding.role -eq $Role -and @($binding.members) -contains $Member) { return $true }
    }
    return $false
}

function Get-ExpectedBinding {
    <# Every IAM binding the deploy needs (ADR 0007, decision 3). Nothing broader is ever added. #>
    param($State, $Config)
    $deploy = 'serviceAccount:' + (Get-SaEmail $State $Config.DeploySa)
    $runtime = 'serviceAccount:' + (Get-SaEmail $State $Config.RuntimeSa)
    $migration = 'serviceAccount:' + (Get-SaEmail $State $Config.MigrationSa)
    $principalSet = "principalSet://iam.googleapis.com/projects/$($State.ProjectNumber)/locations/global/" +
        "workloadIdentityPools/$($Config.PoolId)/attribute.repository/$script:Repo"
    return @(
        @{ Scope = 'project'; Who = $Config.DeploySa; Member = $deploy; Role = 'roles/run.developer'; On = 'project' },
        @{ Scope = 'project'; Who = $Config.RuntimeSa; Member = $runtime; Role = 'roles/cloudsql.client'; On = 'project' },
        @{ Scope = 'project'; Who = $Config.MigrationSa; Member = $migration; Role = 'roles/cloudsql.client'; On = 'project' },
        @{ Scope = 'repo'; Who = $Config.DeploySa; Member = $deploy; Role = 'roles/artifactregistry.writer'; On = "repository $($Config.ArRepo)" },
        @{ Scope = 'sa:runtime'; Who = $Config.DeploySa; Member = $deploy; Role = 'roles/iam.serviceAccountUser'; On = "account $($Config.RuntimeSa)" },
        @{ Scope = 'sa:migration'; Who = $Config.DeploySa; Member = $deploy; Role = 'roles/iam.serviceAccountUser'; On = "account $($Config.MigrationSa)" },
        @{ Scope = 'sa:deploy'; Who = 'GitHub (this repository)'; Member = $principalSet; Role = 'roles/iam.workloadIdentityUser'; On = "account $($Config.DeploySa)" },
        @{ Scope = 'secret:url'; Who = $Config.RuntimeSa; Member = $runtime; Role = 'roles/secretmanager.secretAccessor'; On = "secret $($Config.UrlSecret)" },
        @{ Scope = 'secret:url'; Who = $Config.MigrationSa; Member = $migration; Role = 'roles/secretmanager.secretAccessor'; On = "secret $($Config.UrlSecret)" },
        @{ Scope = 'run'; Who = 'allUsers (public by design)'; Member = 'allUsers'; Role = 'roles/run.invoker'; On = "service $($Config.ApiService)" }
    )
}

function Get-BindingCommand {
    <# The add-iam-policy-binding command for one expected binding. Adding twice is harmless. #>
    param($State, $Config, $Binding)
    $tail = @("--member=$($Binding.Member)", "--role=$($Binding.Role)")
    switch ($Binding.Scope) {
        'project' { return @('projects', 'add-iam-policy-binding', $State.ProjectId) + $tail + '--condition=None' }
        'repo' { return @('artifacts', 'repositories', 'add-iam-policy-binding', $Config.ArRepo, "--location=$script:Region") + $tail }
        'sa:runtime' { return @('iam', 'service-accounts', 'add-iam-policy-binding', (Get-SaEmail $State $Config.RuntimeSa)) + $tail }
        'sa:migration' { return @('iam', 'service-accounts', 'add-iam-policy-binding', (Get-SaEmail $State $Config.MigrationSa)) + $tail }
        'sa:deploy' { return @('iam', 'service-accounts', 'add-iam-policy-binding', (Get-SaEmail $State $Config.DeploySa)) + $tail }
        'secret:url' { return @('secrets', 'add-iam-policy-binding', $Config.UrlSecret) + $tail }
        'run' { return @('run', 'services', 'add-iam-policy-binding', $Config.ApiService, "--region=$script:Region") + $tail }
    }
}

function Test-IamBinding {
    param($State, $Config)
    $State.Policies = @{}
    Get-IamPolicy $State 'project' @('projects', 'get-iam-policy', $State.ProjectId)
    Get-IamPolicy $State 'repo' @('artifacts', 'repositories', 'get-iam-policy', $Config.ArRepo, "--location=$script:Region")
    Get-IamPolicy $State 'sa:deploy' @('iam', 'service-accounts', 'get-iam-policy', (Get-SaEmail $State $Config.DeploySa))
    Get-IamPolicy $State 'sa:runtime' @('iam', 'service-accounts', 'get-iam-policy', (Get-SaEmail $State $Config.RuntimeSa))
    Get-IamPolicy $State 'sa:migration' @('iam', 'service-accounts', 'get-iam-policy', (Get-SaEmail $State $Config.MigrationSa))
    Get-IamPolicy $State 'secret:url' @('secrets', 'get-iam-policy', $Config.UrlSecret)
    Get-IamPolicy $State 'secret:password' @('secrets', 'get-iam-policy', $Config.PasswordSecret)
    Get-IamPolicy $State 'run' @('run', 'services', 'get-iam-policy', $Config.ApiService, "--region=$script:Region")

    $expected = Get-ExpectedBinding $State $Config
    $index = 0
    foreach ($binding in $expected) {
        $index++
        $bindings = $State.Policies[$binding.Scope]
        $status = 'MISSING'
        $found = 'no such binding'
        if ($null -eq $bindings) { $found = "cannot be read yet ($($binding.On) missing?)" }
        elseif (Test-PolicyMember $bindings $binding.Member $binding.Role) { $status = 'PRESENT'; $found = 'bound' }
        Add-AuditItem $State "iam:$index" "IAM: $($binding.Who) on $($binding.On)" $binding.Role $found $status `
            'Run with -Apply (adds the binding).'
    }

    # Anything else these three accounts hold is reported, never removed.
    $accounts = @{
        ('serviceAccount:' + (Get-SaEmail $State $Config.DeploySa))    = $Config.DeploySa
        ('serviceAccount:' + (Get-SaEmail $State $Config.RuntimeSa))   = $Config.RuntimeSa
        ('serviceAccount:' + (Get-SaEmail $State $Config.MigrationSa)) = $Config.MigrationSa
    }
    $labels = @{
        'project' = 'project'; 'repo' = "repository $($Config.ArRepo)"; 'sa:deploy' = "account $($Config.DeploySa)"
        'sa:runtime' = "account $($Config.RuntimeSa)"; 'sa:migration' = "account $($Config.MigrationSa)"
        'secret:url' = "secret $($Config.UrlSecret)"; 'secret:password' = "secret $($Config.PasswordSecret)"
        'run' = "service $($Config.ApiService)"
    }
    $extra = 0
    foreach ($scope in @('project', 'repo', 'sa:deploy', 'sa:runtime', 'sa:migration', 'secret:url', 'secret:password', 'run')) {
        foreach ($binding in @($State.Policies[$scope])) {
            if ($null -eq $binding) { continue }
            foreach ($member in @($binding.members)) {
                $wanted = $expected | Where-Object { $_.Scope -eq $scope -and $_.Member -eq $member -and $_.Role -eq $binding.role }
                if ($wanted) { continue }
                $who = $accounts[$member]
                # Besides the three accounts: anyone else who may impersonate the deploy account.
                if (-not $who -and $scope -eq 'sa:deploy' -and
                    @('roles/iam.workloadIdentityUser', 'roles/iam.serviceAccountTokenCreator') -contains $binding.role) {
                    $who = $member
                }
                if (-not $who) { continue }
                $extra++
                Add-AuditItem $State "iam:extra:$extra" "IAM: $who on $($labels[$scope])" 'no other role' $binding.role 'WRONG-EXTRA' `
                    'More than the deploy needs. Reported only: remove it by hand if it is not intended.'
            }
        }
    }
}

# --- Routing: the private OSRM services (ADR 0020) ---

function Get-RoutingService {
    param($Config)
    return @(
        @{ Key = 'walking'; Name = $Config.OsrmWalkingService },
        @{ Key = 'driving'; Name = $Config.OsrmDrivingService }
    )
}

function Get-RoutingExclusion {
    <#
        OSRM takes the origin and the destination in the URL path, and Cloud Run's request log
        stores every URL. This exclusion keeps every entry that has a request URL out of the log
        bucket for one service; lines the container writes (start-up errors) are kept.
        No double quote, comma or percent sign: gcloud splits the flag at commas, and on Windows
        the command passes through cmd.exe.
    #>
    param([string]$Service)
    return @{
        Name   = "exclude-$Service-requests"
        Filter = "resource.type=cloud_run_revision AND resource.labels.service_name=$Service AND httpRequest.requestUrl:*"
    }
}

function Test-Routing {
    param($State, $Config)
    $workflow = 'Run the osrm-staging workflow (Actions > osrm-staging > Run workflow), then -Apply again.'
    $osrmEmail = Get-SaEmail $State $Config.OsrmRuntimeSa
    $osrmMember = "serviceAccount:$osrmEmail"
    $deploy = 'serviceAccount:' + (Get-SaEmail $State $Config.DeploySa)
    $runtime = 'serviceAccount:' + (Get-SaEmail $State $Config.RuntimeSa)
    # The URL of each deployed OSRM service, for the GitHub secrets the API deploy reads (P012b).
    $State.OsrmUrls = @{}

    $saStatus = 'MISSING'; $saFound = 'not found'
    if ($null -eq $State.SaEmails) { $saStatus = 'UNKNOWN'; $saFound = 'could not list accounts' }
    elseif ($State.SaEmails -contains $osrmEmail) { $saStatus = 'PRESENT'; $saFound = $Config.OsrmRuntimeSa }
    Add-AuditItem $State 'osrm:sa' 'Routing: service account for OSRM (no roles)' $Config.OsrmRuntimeSa $saFound $saStatus `
        'Run with -Apply (creates the account; it gets no role).'

    $actStatus = 'MISSING'; $actFound = 'the account does not exist yet'
    if ($saStatus -eq 'PRESENT') {
        $policy = Invoke-GcloudJson @('iam', 'service-accounts', 'get-iam-policy', $osrmEmail)
        $actFound = 'no such binding'
        if ($policy.Status -ne 'ok') { $actStatus = Get-StatusOf $policy; $actFound = $policy.Message }
        elseif (Test-PolicyMember @($policy.Data.bindings) $deploy 'roles/iam.serviceAccountUser') { $actStatus = 'PRESENT'; $actFound = 'bound' }
    }
    Add-AuditItem $State 'osrm:actas' "Routing: $($Config.DeploySa) on account $($Config.OsrmRuntimeSa)" 'roles/iam.serviceAccountUser' $actFound $actStatus `
        'Run with -Apply (adds the binding).'

    # The OSRM account must hold nothing: it only runs a container that reads its own files.
    $extra = 0
    foreach ($binding in @($State.Policies['project'])) {
        if ($null -eq $binding -or @($binding.members) -notcontains $osrmMember) { continue }
        $extra++
        Add-AuditItem $State "osrm:extra:$extra" "Routing: $($Config.OsrmRuntimeSa) on project" 'no role' $binding.role 'WRONG-EXTRA' `
            'The OSRM account needs no role. Reported only: remove it by hand.'
    }

    $sink = Invoke-GcloudJson @('logging', 'sinks', 'describe', '_Default')
    foreach ($service in (Get-RoutingService $Config)) {
        $name = $service.Name
        $wanted = Get-RoutingExclusion $name
        $logStatus = 'MISSING'; $logFound = 'no such exclusion'
        $logNext = 'Run with -Apply (adds the exclusion). Do this BEFORE the first deploy of the service.'
        if ($sink.Status -ne 'ok') { $logStatus = Get-StatusOf $sink; $logFound = $sink.Message; if ($sink.Status -eq 'notfound') { $logFound = 'the _Default sink was not found' } }
        else {
            $existing = @($sink.Data.exclusions | Where-Object { $null -ne $_ -and $_.name -eq $wanted.Name }) | Select-Object -First 1
            if ($existing) {
                $logStatus = 'PRESENT'; $logFound = 'set'
                $filter = '' + $existing.filter
                if ($existing.disabled -or -not $filter.Contains($name) -or -not $filter.Contains('requestUrl')) {
                    $logStatus = 'WRONG'; $logFound = 'disabled, or its filter does not name this service and requestUrl'
                    $logNext = "Not changed by this script. Fix it in the console (Logging > Log router > _Default), filter: $($wanted.Filter)"
                }
            }
        }
        Add-AuditItem $State "osrm:log:$($service.Key)" "Routing: request-log exclusion for $name" $wanted.Name $logFound $logStatus $logNext

        $described = Invoke-GcloudJson @('run', 'services', 'describe', $name, "--region=$script:Region")
        if ($described.Status -eq 'notfound') {
            Add-AuditItem $State "osrm:service:$($service.Key)" "Routing: Cloud Run service $name" 'deployed by the workflow' 'not deployed yet' 'PENDING' $workflow
            Add-AuditItem $State "osrm:invoker:$($service.Key)" "Routing: $($Config.RuntimeSa) may call $name" 'roles/run.invoker' 'the service does not exist yet' 'PENDING' $workflow
            continue
        }
        $found = $described.Message
        if ($described.Status -eq 'ok') { $found = 'exists' }
        Add-AuditItem $State "osrm:service:$($service.Key)" "Routing: Cloud Run service $name" 'deployed by the workflow' $found (Get-StatusOf $described) $workflow
        if ($described.Status -ne 'ok') { continue }
        # Exactly as Cloud Run reports it (no trailing slash): it is also the ID-token audience.
        $State.OsrmUrls[$service.Key] = ('' + $described.Data.status.url).TrimEnd('/')

        $policy = Invoke-GcloudJson @('run', 'services', 'get-iam-policy', $name, "--region=$script:Region")
        if ($policy.Status -ne 'ok') {
            Add-AuditItem $State "osrm:invoker:$($service.Key)" "Routing: $($Config.RuntimeSa) may call $name" 'roles/run.invoker' $policy.Message (Get-StatusOf $policy) 'Check your access to the service, then run -Audit again.'
            continue
        }
        $bindings = @($policy.Data.bindings | Where-Object { $null -ne $_ })
        $invStatus = 'MISSING'; $invFound = 'no such binding'
        if (Test-PolicyMember $bindings $runtime 'roles/run.invoker') { $invStatus = 'PRESENT'; $invFound = 'bound' }
        Add-AuditItem $State "osrm:invoker:$($service.Key)" "Routing: $($Config.RuntimeSa) may call $name" 'roles/run.invoker' $invFound $invStatus `
            'Run with -Apply (adds the binding).'

        $public = @($bindings | Where-Object { @($_.members) -contains 'allUsers' -or @($_.members) -contains 'allAuthenticatedUsers' })
        $pubStatus = 'PRESENT'; $pubFound = 'private'
        if ($public.Count -gt 0) { $pubStatus = 'WRONG'; $pubFound = 'PUBLIC: allUsers or allAuthenticatedUsers is bound' }
        Add-AuditItem $State "osrm:private:$($service.Key)" "Routing: $name is not public" 'no allUsers, no allAuthenticatedUsers' $pubFound $pubStatus `
            'Remove the public binding NOW in the console (Cloud Run > the service > Security). Not changed by this script.'
    }
}

function Invoke-RoutingApply {
    <# Creates only what Test-Routing found MISSING. Returns @{ Ran; Failed; Blocked }. #>
    param($State, $Config, [scriptblock]$Todo, [bool]$Plan)
    $result = @{ Ran = 0; Failed = $false; Blocked = (New-Object System.Collections.ArrayList) }
    $osrmEmail = Get-SaEmail $State $Config.OsrmRuntimeSa
    $steps = New-Object System.Collections.ArrayList
    $saReady = ((Get-ItemStatus $State 'osrm:sa') -eq 'PRESENT')
    if (& $Todo 'osrm:sa') {
        [void]$steps.Add(@{ Title = "create the role-less service account $($Config.OsrmRuntimeSa)"; Creates = 'sa'
                Arguments = @('iam', 'service-accounts', 'create', $Config.OsrmRuntimeSa, '--display-name=SafeRoute OSRM runtime (no roles)') })
    }
    if (& $Todo 'osrm:actas') {
        [void]$steps.Add(@{ Title = "grant roles/iam.serviceAccountUser to $($Config.DeploySa) on account $($Config.OsrmRuntimeSa)"; Needs = 'sa'
                Arguments = @('iam', 'service-accounts', 'add-iam-policy-binding', $osrmEmail,
                    "--member=serviceAccount:$(Get-SaEmail $State $Config.DeploySa)", '--role=roles/iam.serviceAccountUser') })
    }
    foreach ($service in (Get-RoutingService $Config)) {
        if (& $Todo "osrm:log:$($service.Key)") {
            $exclusion = Get-RoutingExclusion $service.Name
            [void]$steps.Add(@{ Title = "exclude request URLs of $($service.Name) from the log bucket"
                    Arguments = @('logging', 'sinks', 'update', '_Default', "--add-exclusion=name=$($exclusion.Name),filter=$($exclusion.Filter)") })
        }
        $invoker = Get-ItemStatus $State "osrm:invoker:$($service.Key)"
        if ($invoker -eq 'MISSING' -or ($Plan -and $invoker -eq 'NOT RUN')) {
            [void]$steps.Add(@{ Title = "grant roles/run.invoker to $($Config.RuntimeSa) on service $($service.Name)"
                    Arguments = @('run', 'services', 'add-iam-policy-binding', $service.Name, "--region=$script:Region",
                        "--member=serviceAccount:$(Get-SaEmail $State $Config.RuntimeSa)", '--role=roles/run.invoker') })
        }
        elseif ($invoker -eq 'PENDING') {
            [void]$result.Blocked.Add("invoker binding on $($service.Name): the service does not exist yet (run the osrm-staging workflow, then -Apply again)")
        }
    }
    foreach ($step in $steps) {
        if ($step.Needs -eq 'sa' -and -not $saReady -and -not $Plan) {
            [void]$result.Blocked.Add("binding on account $($Config.OsrmRuntimeSa): the account does not exist")
            continue
        }
        $outcome = Invoke-Step $step.Title $step.Arguments
        if ($outcome -eq 'failed') { $result.Failed = $true; return $result }
        if ($outcome -eq 'done') { $result.Ran++; if ($step.Creates -eq 'sa') { $saReady = $true } }
    }
    return $result
}

function Invoke-Audit {
    <# Read-only. Returns the state, or $null when the project could not be determined. #>
    param($Config)
    $State = @{
        Items = New-Object System.Collections.ArrayList
        ProjectId = ''; ProjectNumber = ''; ProviderName = ''; ProviderId = ''
        ConnectionName = ''; DbName = ''; DbUser = ''; Policies = @{}
        Notes = New-Object System.Collections.ArrayList
    }
    if (-not (Get-ProjectContext $State)) { return $null }
    Test-Api $State
    Test-ServiceAccount $State $Config
    Test-WorkloadIdentity $State $Config
    Test-ArtifactRegistry $State $Config
    Test-CloudSql $State $Config
    Test-Secret $State $Config
    Test-CloudRun $State $Config
    Test-IamBinding $State $Config
    Test-Routing $State $Config
    return $State
}

function Test-AuditClean {
    param($Items)
    return (@($Items | Where-Object { @('MISSING', 'WRONG', 'UNKNOWN') -contains $_.Status }).Count -eq 0)
}

function Show-AuditTable {
    param($Items, [string]$Title, $Notes = @())
    Write-Line ''
    Write-Line $Title
    foreach ($note in $Notes) { Write-Line "Note: $note" }
    $rows = @($Items | ForEach-Object {
            [pscustomobject]@{
                Item = ConvertTo-SafeText $_.Item; Expected = ConvertTo-SafeText $_.Expected
                Found = ConvertTo-SafeText $_.Found; Status = $_.Status
            }
        })
    $widths = @{}
    foreach ($column in @('Item', 'Expected', 'Found')) {
        $widths[$column] = [Math]::Max($column.Length, ($rows | ForEach-Object { $_.$column.Length } | Measure-Object -Maximum).Maximum)
    }
    $format = "{0,-$($widths.Item)}  {1,-$($widths.Expected)}  {2,-$($widths.Found)}  {3}"
    Write-Line ($format -f 'ITEM', 'EXPECTED', 'FOUND', 'STATUS')
    foreach ($row in $rows) { Write-Line ($format -f $row.Item, $row.Expected, $row.Found, $row.Status) }

    $open = @($Items | Where-Object { @('PRESENT', 'NOT RUN') -notcontains $_.Status })
    Write-Line ''
    if ($open.Count -eq 0) {
        if (-not $script:Session.Plan) { Write-Line 'Nothing to do: every item is PRESENT.' }
        return
    }
    Write-Line 'Next actions:'
    $number = 0
    foreach ($item in $open) {
        $number++
        Write-Line ("  {0}. [{1}] {2}: {3}" -f $number, $item.Status, $item.Item, $item.Next)
    }
}

# --- Apply ---

function Invoke-Step {
    <# Shows one change, asks, runs it. Returns 'done', 'skipped' or 'failed'. #>
    param([string]$Title, [string[]]$Arguments, [byte[]]$StdinBytes, [string]$StdinNote = '')
    Write-Line ''
    Write-Line "STEP: $Title"
    if ($script:Session.Plan) {
        [void](Invoke-Tool -Tool 'gcloud' -Arguments $Arguments -StdinNote $StdinNote)
        return 'done'
    }
    Write-Line ('  ' + (Format-CommandText 'gcloud' $Arguments))
    if (-not (Confirm-Step 'Run this step?')) { Write-Line '  skipped'; return 'skipped' }
    $result = Invoke-Tool -Tool 'gcloud' -Arguments $Arguments -StdinBytes $StdinBytes
    if ($result.ExitCode -ne 0) {
        Write-Line "  FAILED: $(Get-ErrorSummary $result)"
        return 'failed'
    }
    Write-Line '  done'
    return 'done'
}

function Get-DatabaseUrlByte {
    <#
        Reads the database password from Secret Manager into memory and returns the socket-form
        URL as bytes. Nothing is printed or written to disk; the values are also registered so that
        no later message can contain them.
    #>
    param($State, $Config)
    $access = @('secrets', 'versions', 'access', 'latest', "--secret=$($Config.PasswordSecret)")
    if ($script:Session.Plan) {
        [void](Invoke-Tool 'gcloud' $access)
        return [System.Text.Encoding]::UTF8.GetBytes('planned')
    }
    $result = Invoke-External -Tool 'gcloud' -Arguments $access
    if ($result.ExitCode -ne 0) {
        Write-Line "  FAILED to read $($Config.PasswordSecret): $(Get-ErrorSummary $result)"
        return $null
    }
    $password = ('' + $result.StdOut)
    $trimmed = $password.TrimStart([char]0xFEFF).TrimEnd("`r", "`n")
    if ($trimmed.Length -ne $password.Length) {
        Write-Line "  note: a byte-order mark or a line break around the stored password was ignored."
    }
    if (-not $trimmed) { Write-Line "  FAILED: $($Config.PasswordSecret) is empty."; return $null }
    $encoded = ConvertTo-PercentEncoded $trimmed
    $url = 'postgresql://{0}:{1}@/{2}?host=/cloudsql/{3}' -f (ConvertTo-PercentEncoded $State.DbUser), $encoded,
        (ConvertTo-PercentEncoded $State.DbName), $State.ConnectionName
    Register-SecretValue $url
    Register-SecretValue $encoded
    Register-SecretValue $trimmed
    Register-SecretValue $password
    return [System.Text.Encoding]::UTF8.GetBytes($url)
}

function Invoke-Apply {
    <# Creates only MISSING items, in dependency order. Returns the exit code. #>
    param($State, $Config)
    $plan = $script:Session.Plan
    $todo = { param($Id) $status = Get-ItemStatus $State $Id; $status -eq 'MISSING' -or ($plan -and $status -eq 'NOT RUN') }
    $present = { param($Id) $status = Get-ItemStatus $State $Id; $status -eq 'PRESENT' -or $plan }

    if (-not $plan) {
        Write-Line ''
        Write-Line "-Apply creates only MISSING items in project $($State.ProjectId). Nothing is deleted or renamed."
        $typed = Read-Answer 'Type the project ID to continue'
        if ($typed -cne $State.ProjectId) { Write-Line 'The project ID did not match. Nothing was changed.'; return 2 }
    }
    $ran = 0
    $blocked = New-Object System.Collections.ArrayList

    # 1. APIs
    $apis = @($script:RequiredApis | Where-Object { & $todo "api:$_" })
    if ($apis.Count -gt 0) {
        $outcome = Invoke-Step "enable $($apis.Count) API(s)" (@('services', 'enable') + $apis)
        if ($outcome -eq 'failed') { return 1 }
        if ($outcome -eq 'done') { $ran++ }
    }

    # 2. Workload Identity provider (the pool is never created here)
    if (& $todo 'wif:provider') {
        if (& $present 'wif:pool') {
            $mapping = 'google.subject=assertion.sub,attribute.repository=assertion.repository,' +
                'attribute.repository_owner=assertion.repository_owner,attribute.ref=assertion.ref,' +
                'attribute.environment=assertion.environment'
            $condition = "assertion.repository == '$script:Repo' && assertion.ref == 'refs/heads/main' " +
                "&& assertion.environment == '$script:GithubEnvironment'"
            $outcome = Invoke-Step "create Workload Identity provider $($Config.ProviderId)" @(
                'iam', 'workload-identity-pools', 'providers', 'create-oidc', $Config.ProviderId, '--location=global',
                "--workload-identity-pool=$($Config.PoolId)", '--display-name=GitHub SafeRoute',
                "--issuer-uri=$script:Issuer", "--attribute-mapping=$mapping", "--attribute-condition=$condition")
            if ($outcome -eq 'failed') { return 1 }
            if ($outcome -eq 'done') { $ran++ }
        }
        else { [void]$blocked.Add('provider: the Workload Identity pool is missing') }
    }

    # 3. Database URL secret (created only when it does not exist at all)
    $secretReady = (& $present 'secret:url')
    if (& $todo 'secret:url') {
        $needs = @('secret:password', 'sql:instance', 'sql:database', 'sql:user') | Where-Object { -not (& $present $_) }
        if (@($needs).Count -gt 0) { [void]$blocked.Add("database URL secret: not PRESENT yet: $($needs -join ', ')") }
        else {
            Write-Line ''
            Write-Line "The database URL will be built from $($Config.PasswordSecret) for user $($State.DbUser), database $($State.DbName)."
            Write-Line 'This script cannot check that the password belongs to that user. The first migration job proves it:'
            Write-Line 'if it fails with "password authentication failed", the two do not match.'
            $bytes = $null
            if ($plan -or (Confirm-Step "Read $($Config.PasswordSecret) into memory to build the URL?")) {
                $outcome = 'failed'
                try {
                    $bytes = Get-DatabaseUrlByte $State $Config
                    if ($null -ne $bytes) {
                        $outcome = Invoke-Step "create secret $($Config.UrlSecret) with its first version" @(
                            'secrets', 'create', $Config.UrlSecret, '--replication-policy=user-managed',
                            "--locations=$script:Region", '--data-file=-') $bytes 'the database URL, built in memory, never shown'
                    }
                }
                finally {
                    # Forget the password and the URL as soon as the step is over, whatever happened.
                    if ($null -ne $bytes) { [Array]::Clear($bytes, 0, $bytes.Length) }
                    $bytes = $null
                    $script:Session.Secrets.Clear()
                }
                if ($outcome -eq 'failed') { return 1 }
                if ($outcome -eq 'done') { $ran++; $secretReady = $true }
            }
        }
    }

    # 4. Placeholder service, so that a previous revision and the public binding exist
    $serviceReady = (& $present 'run:service')
    $deployedNow = $false
    if (& $todo 'run:service') {
        if (& $present 'sa:RuntimeSa') {
            Write-Line ''
            Write-Line 'The API is public by design: it checks Firebase tokens itself (ADR 0006), so the service allows unauthenticated calls.'
            $outcome = Invoke-Step "deploy the placeholder service $($Config.ApiService)" @(
                'run', 'deploy', $Config.ApiService, "--image=$script:HelloImage", "--region=$script:Region",
                "--service-account=$(Get-SaEmail $State $Config.RuntimeSa)", '--allow-unauthenticated', '--max-instances=1')
            if ($outcome -eq 'failed') { return 1 }
            if ($outcome -eq 'done') { $ran++; $serviceReady = $true; $deployedNow = (-not $plan) }
        }
        else { [void]$blocked.Add('placeholder service: the runtime service account is missing') }
    }

    # 5. IAM bindings (adding an existing binding again changes nothing)
    $index = 0
    foreach ($binding in (Get-ExpectedBinding $State $Config)) {
        $index++
        if (-not (& $todo "iam:$index")) { continue }
        if ($binding.Scope -eq 'secret:url' -and -not $secretReady) { [void]$blocked.Add("binding on $($binding.On): the secret does not exist"); continue }
        if ($binding.Scope -eq 'run' -and -not $serviceReady) { [void]$blocked.Add("binding on $($binding.On): the service does not exist"); continue }
        if ($binding.Scope -eq 'run' -and $deployedNow) {
            # The deploy above already asked for public access; check instead of adding twice.
            $policy = Invoke-GcloudJson @('run', 'services', 'get-iam-policy', $Config.ApiService, "--region=$script:Region")
            if ($policy.Status -eq 'ok' -and (Test-PolicyMember @($policy.Data.bindings) 'allUsers' 'roles/run.invoker')) { continue }
        }
        $outcome = Invoke-Step "grant $($binding.Role) to $($binding.Who) on $($binding.On)" (Get-BindingCommand $State $Config $binding)
        if ($outcome -eq 'failed') {
            if ($binding.Scope -eq 'run') {
                Write-Line '  Public access was refused. An organization policy (iam.allowedPolicyMemberDomains, "domain restricted'
                Write-Line '  sharing") probably blocks allUsers. This script does not work around it. An organization administrator must'
                Write-Line '  allow allUsers for this project, then rerun -Apply. Until then the smoke tests cannot reach the service.'
            }
            return 1
        }
        if ($outcome -eq 'done') { $ran++ }
    }

    # 6. Routing: OSRM account, bindings and log exclusions (ADR 0020)
    $routing = Invoke-RoutingApply $State $Config $todo $plan
    if ($routing.Failed) { return 1 }
    $ran += $routing.Ran
    $pending = New-Object System.Collections.ArrayList
    foreach ($reason in $routing.Blocked) {
        if ($reason -match 'does not exist yet') { [void]$pending.Add($reason) } else { [void]$blocked.Add($reason) }
    }

    # 7. Optional cleanup policy
    if ((Get-ItemStatus $State 'ar:cleanup') -eq 'NOTE' -or ($plan -and (Get-ItemStatus $State 'ar:cleanup') -eq 'NOT RUN')) {
        $outcome = Invoke-Step 'OPTIONAL: set the registry cleanup policy (delete after 7 days; keep the 10 newest and every image tagged osrm-)' @(
            'artifacts', 'repositories', 'set-cleanup-policies', $Config.ArRepo, "--location=$script:Region",
            "--policy=$(Get-CleanupPolicyFile)", '--no-dry-run')
        if ($outcome -eq 'failed') { return 1 }
        if ($outcome -eq 'done') { $ran++ }
    }
    elseif ((Get-ItemStatus $State 'ar:cleanup-osrm') -eq 'NOTE' -and $State.CleanupIsOurs) {
        # The policy on the repository is an earlier version of our own file: set the file again.
        $outcome = Invoke-Step 'update the registry cleanup policy (adds: keep every image tagged osrm-)' @(
            'artifacts', 'repositories', 'set-cleanup-policies', $Config.ArRepo, "--location=$script:Region",
            "--policy=$(Get-CleanupPolicyFile)", '--no-dry-run')
        if ($outcome -eq 'failed') { return 1 }
        if ($outcome -eq 'done') { $ran++ }
    }

    Write-Line ''
    foreach ($reason in $blocked) { Write-Line "NOT DONE, blocked: $reason" }
    foreach ($reason in $pending) { Write-Line "LATER: $reason" }
    if ($plan) { Write-Line 'Plan only: nothing was run. Steps above run only for items the audit finds MISSING.'; return 0 }
    if ($ran -eq 0 -and $blocked.Count -eq 0) { Write-Line 'Nothing was changed.' }
    Write-Line 'Next: run with -SetGithubSecrets, then -Verify.'
    if ($blocked.Count -gt 0) { return 1 }
    return 0
}

# --- GitHub ---

function Get-WorkflowReference {
    <#
        Reads which secrets and variables the workflow uses, so the list is never hard-coded here.
        A vars.X inside a JOB-level "if:" must be a repository variable: GitHub evaluates that
        condition before the job enters its environment, so environment variables are not visible.
        Assumes the repository's two-space YAML indentation.
    #>
    param([string]$Path)
    $secrets = New-Object System.Collections.ArrayList
    $variables = New-Object System.Collections.ArrayList
    $repositoryVariables = New-Object System.Collections.ArrayList
    $urlSecret = ''
    $inJobIf = $false
    foreach ($raw in (Get-Content -LiteralPath $Path)) {
        if ($raw -match '^\s*#') { continue }
        $line = $raw -replace '\s+#.*$', ''
        if ($line -match '^ {4}if:') { $inJobIf = $true }
        elseif ($inJobIf -and $line -match '^ {0,4}\S') { $inJobIf = $false }
        foreach ($match in [regex]::Matches($line, '\b(secrets|vars)\.([A-Za-z_][A-Za-z0-9_]*)')) {
            $name = $match.Groups[2].Value
            if ($name -eq 'GITHUB_TOKEN') { continue }
            $target = $variables
            if ($match.Groups[1].Value -eq 'secrets') { $target = $secrets }
            elseif ($inJobIf) { $target = $repositoryVariables }
            if (-not $target.Contains($name)) { [void]$target.Add($name) }
        }
        if ($line -match '^\s*DATABASE_URL_SECRET:\s*[''"]?([A-Za-z0-9_-]+)') { $urlSecret = $Matches[1] }
    }
    return @{
        Secrets = @($secrets); Variables = @($variables | Where-Object { -not $repositoryVariables.Contains($_) })
        RepositoryVariables = @($repositoryVariables); UrlSecret = $urlSecret
    }
}

function Get-GithubName {
    <# Names (and, for variables, values) at one level. $null if the list could not be read. #>
    param([string]$Kind, [bool]$ForEnvironment)
    $fields = 'name'
    if ($Kind -eq 'variable') { $fields = 'name,value' }
    $arguments = @($Kind, 'list', '--repo', $script:Repo, '--json', $fields)
    if ($ForEnvironment) { $arguments += @('--env', $script:GithubEnvironment) }
    $result = Invoke-Tool -Tool 'gh' -Arguments $arguments
    if ($result.ExitCode -ne 0) { return $null }
    $entries = @()
    if (-not $result.Planned -and -not [string]::IsNullOrWhiteSpace($result.StdOut)) {
        $entries = @($result.StdOut | ConvertFrom-Json | ForEach-Object { $_ } | Where-Object { $null -ne $_ })
    }
    return , $entries
}

function Get-GithubState {
    $environment = Invoke-Tool -Tool 'gh' -Arguments @('api', "repos/$script:Repo/environments/$script:GithubEnvironment", '--jq', '.name')
    return @{
        EnvironmentExists    = ($environment.Planned -or $environment.ExitCode -eq 0)
        EnvironmentSecrets   = Get-GithubName 'secret' $true
        EnvironmentVariables = Get-GithubName 'variable' $true
        RepositorySecrets    = Get-GithubName 'secret' $false
        RepositoryVariables  = Get-GithubName 'variable' $false
    }
}

function Get-GithubValue {
    <# What this script knows how to fill in. A name the workflow adds later is reported, not guessed. #>
    param($State, $Config)
    return @{
        GCP_PROJECT_ID            = $State.ProjectId
        GCP_PROJECT_NUMBER        = $State.ProjectNumber
        GCP_WIF_PROVIDER          = $State.ProviderName
        GCP_DEPLOY_SA             = Get-SaEmail $State $Config.DeploySa
        GCP_RUNTIME_SA            = Get-SaEmail $State $Config.RuntimeSa
        GCP_MIGRATION_SA          = Get-SaEmail $State $Config.MigrationSa
        CLOUD_SQL_CONNECTION_NAME = $State.ConnectionName
        FIREBASE_PROJECT_ID       = $Config.FirebaseProjectId
        GCP_REGION                = $script:Region
        AR_REPOSITORY             = $Config.ArRepo
        API_SERVICE               = $Config.ApiService
        MIGRATION_JOB             = $Config.MigrationJob
        API_MIN_INSTANCES         = '0'
        API_MAX_INSTANCES         = '3'
        # Empty until the osrm-staging workflow has deployed the service.
        OSRM_WALKING_URL          = '' + $State.OsrmUrls.walking
        OSRM_DRIVING_URL          = '' + $State.OsrmUrls.driving
        # Optional (see $script:OptionalVariables): never set here.
        ROUTING_TIMEOUT_MS        = ''
    }
}

function Test-GithubSetup {
    <# Adds one audit item per name the workflow references. #>
    param($State, $Config, $Reference, $Github, [bool]$GcpClean)
    $names = { param($List) @($List | ForEach-Object { $_.name }) }
    $envSecrets = & $names $Github.EnvironmentSecrets
    $envVariables = & $names $Github.EnvironmentVariables
    $repoSecrets = & $names $Github.RepositorySecrets
    $repoVariables = & $names $Github.RepositoryVariables
    $setNext = 'Run with -SetGithubSecrets.'

    $envStatus = 'MISSING'
    if ($Github.EnvironmentExists) { $envStatus = 'PRESENT' }
    Add-AuditItem $State 'gh:environment' "GitHub environment" $script:GithubEnvironment $envStatus.ToLowerInvariant() $envStatus `
        "Create it: repository Settings > Environments > New environment '$script:GithubEnvironment', deployment branch 'main' ($script:Runbook step 9)."
    if ($Reference.UrlSecret -and $Reference.UrlSecret -ne $Config.UrlSecret) {
        Add-AuditItem $State 'gh:urlsecret' 'Secret name in the workflow' $Reference.UrlSecret "-UrlSecret is $($Config.UrlSecret)" 'WRONG' `
            "The workflow mounts '$($Reference.UrlSecret)'. Pass -UrlSecret $($Reference.UrlSecret)."
    }
    if ($null -eq $Github.EnvironmentSecrets -or $null -eq $Github.EnvironmentVariables -or
        $null -eq $Github.RepositorySecrets -or $null -eq $Github.RepositoryVariables) {
        Add-AuditItem $State 'gh:lists' 'GitHub secrets and variables' 'readable' 'gh could not list them' 'UNKNOWN' `
            'Run: gh auth status. The account needs admin access to the repository.'
        return
    }
    foreach ($name in $Reference.Secrets) {
        $status = 'MISSING'; $found = 'not set'; $next = $setNext
        if ($envSecrets -contains $name) { $status = 'PRESENT'; $found = 'environment secret' }
        elseif ($repoSecrets -contains $name) { $status = 'PRESENT'; $found = 'repository secret (works; the runbook keeps it on the environment)' }
        elseif (($envVariables + $repoVariables) -contains $name) {
            $status = 'WRONG'; $found = 'stored as a VARIABLE'
            $next = "The workflow reads secrets.$name. Delete the variable, then run -SetGithubSecrets."
        }
        Add-AuditItem $State "gh:secret:$name" "GitHub secret $name" 'environment secret' $found $status $next
    }
    $values = Get-GithubValue $State $Config
    foreach ($name in $Reference.Variables) {
        $status = 'MISSING'; $found = 'not set'; $next = $setNext
        $entry = $Github.EnvironmentVariables | Where-Object { $_.name -eq $name } | Select-Object -First 1
        if (-not $entry) { $entry = $Github.RepositoryVariables | Where-Object { $_.name -eq $name } | Select-Object -First 1 }
        if ($entry) {
            $status = 'PRESENT'; $found = "= $($entry.value)"
            $wanted = $values[$name]
            if ($wanted -and @('API_MIN_INSTANCES', 'API_MAX_INSTANCES') -notcontains $name -and $entry.value -ne $wanted) {
                $status = 'WRONG'; $found = "= $($entry.value), but this run uses $wanted"
                $next = "Correct it: gh variable set $name --env $script:GithubEnvironment --repo $script:Repo --body $wanted"
            }
        }
        elseif (($envSecrets + $repoSecrets) -contains $name) {
            $status = 'WRONG'; $found = 'stored as a SECRET'
            $next = "The workflow reads vars.$name. Delete the secret, then run -SetGithubSecrets."
        }
        elseif ($script:OptionalVariables -contains $name) {
            $status = 'NOTE'; $found = 'not set (the default applies)'
            $next = "Optional. To set it: gh variable set $name --env $script:GithubEnvironment --repo $script:Repo --body <value>"
        }
        Add-AuditItem $State "gh:variable:$name" "GitHub variable $name" 'environment variable' $found $status $next
    }
    foreach ($name in $Reference.RepositoryVariables) {
        $entry = $Github.RepositoryVariables | Where-Object { $_.name -eq $name } | Select-Object -First 1
        $status = 'MISSING'; $found = 'not set'
        $next = "Create it switched off: gh variable set $name --repo $script:Repo --body false"
        if ($entry) {
            $status = 'PRESENT'; $found = "= $($entry.value)"; $next = ''
            if ($entry.value -eq 'true' -and -not $GcpClean) {
                $status = 'WRONG'; $found = '= true while the setup is incomplete'
                $next = "Switch deploys off until everything is PRESENT: gh variable set $name --repo $script:Repo --body false"
            }
            elseif ($entry.value -ne 'true' -and $GcpClean) {
                $status = 'NOTE'; $next = "When you are ready to deploy: gh variable set $name --repo $script:Repo --body true"
            }
        }
        elseif ($envVariables -contains $name) {
            $status = 'WRONG'; $found = 'set on the ENVIRONMENT, where a job-level "if" cannot see it'
            $next = "It must be a repository variable: gh variable set $name --repo $script:Repo --body false (then delete the environment one)."
        }
        Add-AuditItem $State "gh:repovar:$name" "GitHub repository variable $name" 'repository variable' $found $status $next
    }
}

function Invoke-GithubSetup {
    <# Sets what is missing (or everything with -Force). Secret values travel on standard input. #>
    param($State, $Config, $Reference, $Github, [bool]$Force)
    $plan = $script:Session.Plan
    if (-not $Github.EnvironmentExists) {
        Write-Line "The GitHub environment '$script:GithubEnvironment' does not exist. Create it first ($script:Runbook step 9)."
        return 1
    }
    if ($null -eq $Github.EnvironmentSecrets -or $null -eq $Github.EnvironmentVariables) {
        Write-Line 'gh could not list the environment secrets and variables. Run: gh auth status'
        return 1
    }
    $values = Get-GithubValue $State $Config
    $work = New-Object System.Collections.ArrayList
    $unknown = @(); $empty = @(); $kept = @()
    foreach ($kind in @('secret', 'variable')) {
        $wanted = $Reference.Variables
        $existing = @($Github.EnvironmentVariables | ForEach-Object { $_.name })
        if ($kind -eq 'secret') { $wanted = $Reference.Secrets; $existing = @($Github.EnvironmentSecrets | ForEach-Object { $_.name }) }
        foreach ($name in $wanted) {
            if ($kind -eq 'variable' -and $script:OptionalVariables -contains $name) { continue }
            if (-not $values.ContainsKey($name)) { $unknown += "$kind $name"; continue }
            if (-not $plan -and -not $values[$name]) { $empty += $name; continue }
            if ($existing -contains $name -and -not $Force) { $kept += $name; continue }
            [void]$work.Add(@{ Kind = $kind; Name = $name; Value = $values[$name] })
        }
    }
    if ($kept.Count -gt 0) { Write-Line "Already set, kept (use -Force to overwrite): $($kept -join ', ')" }
    if ($empty.Count -gt 0) {
        Write-Line "No value yet for: $($empty -join ', '). Something in Google Cloud is still MISSING: run -Audit, then -Apply."
    }
    if ($unknown.Count -gt 0) {
        Write-Line "The workflow references names this script has no value for: $($unknown -join ', '). Set them by hand."
    }
    if ($work.Count -gt 0) {
        Write-Line ("To set on environment '$script:GithubEnvironment' (names only): " + (($work | ForEach-Object { $_.Name }) -join ', '))
        if (-not $plan -and -not (Confirm-Step "Set these $($work.Count) value(s)?")) { Write-Line 'Nothing was set.'; return 1 }
    }
    foreach ($entry in $work) {
        $arguments = @($entry.Kind, 'set', $entry.Name, '--env', $script:GithubEnvironment, '--repo', $script:Repo)
        $bytes = [System.Text.Encoding]::UTF8.GetBytes('' + $entry.Value)
        $result = Invoke-Tool -Tool 'gh' -Arguments $arguments -StdinBytes $bytes -StdinNote 'the value, without a line break'
        if ($result.ExitCode -ne 0) { Write-Line "FAILED to set $($entry.Name): $(Get-ErrorSummary $result)"; return 1 }
        if (-not $plan) { Write-Line "  set $($entry.Kind) $($entry.Name)" }
    }
    # The switch is a REPOSITORY variable. It is only ever created here, switched off.
    foreach ($name in $Reference.RepositoryVariables) {
        if (@($Github.RepositoryVariables | ForEach-Object { $_.name }) -contains $name) { continue }
        $result = Invoke-Tool -Tool 'gh' -Arguments @('variable', 'set', $name, '--repo', $script:Repo, '--body', 'false')
        if ($result.ExitCode -ne 0) { Write-Line "FAILED to create $name`: $(Get-ErrorSummary $result)"; return 1 }
        if (-not $plan) { Write-Line "  created repository variable $name = false (you switch it to true yourself)" }
    }
    if ($empty.Count -gt 0 -or $unknown.Count -gt 0) { return 1 }
    Write-Line 'Next: run with -Verify.'
    return 0
}

# --- Entry point ---

function New-BootstrapConfig {
    [Diagnostics.CodeAnalysis.SuppressMessageAttribute('PSUseShouldProcessForStateChangingFunctions', '',
        Justification = 'Builds a hashtable in memory; changes nothing.')]
    param([hashtable]$Bound, [string]$Mode)
    $config = @{
        Mode = $Mode; Plan = $false; ShowIds = $false; Force = $false
        DeploySa = 'sa-deploy'; RuntimeSa = 'sa-api-runtime'; MigrationSa = 'sa-migration'
        PoolId = 'github'; ProviderId = 'github-saferoute'; SqlInstance = 'saferoute-db'; DbName = ''; DbUser = ''
        ArRepo = 'saferoute'; ApiService = 'saferoute-api'; MigrationJob = 'saferoute-migrate'
        UrlSecret = 'saferoute-staging-database-url'; PasswordSecret = 'db-app-password'
        FirebaseProjectId = ''; WorkflowPath = ''
        OsrmRuntimeSa = 'sa-osrm-runtime'; OsrmWalkingService = 'saferoute-osrm-walking'
        OsrmDrivingService = 'saferoute-osrm-driving'
    }
    foreach ($key in @($Bound.Keys)) {
        if ($config.ContainsKey($key)) {
            $value = $Bound[$key]
            if ($value -is [System.Management.Automation.SwitchParameter]) { $value = [bool]$value }
            $config[$key] = $value
        }
    }
    if (-not $config.WorkflowPath) {
        $root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
        $config.WorkflowPath = Join-Path (Join-Path (Join-Path $root '.github') 'workflows') 'deploy-staging.yml'
    }
    return $config
}

function Invoke-Bootstrap {
    <# Runs one mode and returns the process exit code: 0 fine, 1 something is missing or failed, 2 refused. #>
    param([hashtable]$Config)
    Initialize-Session -ShowIds $Config.ShowIds -Plan $Config.Plan
    # Local paths (they contain the OS user name) are shown relative to the repository.
    Register-Mask (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)) '<repo>'
    Write-Line "SafeRoute staging setup: mode $($Config.Mode)$(if ($Config.Plan) { ' (plan only: nothing is run)' })"
    try { return (Invoke-BootstrapMode $Config) }
    catch {
        # One readable line instead of a stack trace; masked like everything else.
        Write-Line "STOPPED: $($_.Exception.Message)"
        return 1
    }
}

function Invoke-BootstrapMode {
    param([hashtable]$Config)
    $State = Invoke-Audit $Config
    if ($null -eq $State) { return 2 }
    $firebaseDefaulted = -not $Config.FirebaseProjectId
    if ($firebaseDefaulted) { $Config.FirebaseProjectId = $State.ProjectId }
    elseif ($Config.FirebaseProjectId -ne $State.ProjectId) { Register-Mask $Config.FirebaseProjectId '<firebase-project-id>' }

    switch ($Config.Mode) {
        'Audit' {
            Show-AuditTable $State.Items 'Google Cloud staging setup' $State.Notes
            return 0
        }
        'Apply' {
            Show-AuditTable $State.Items 'Google Cloud staging setup (before -Apply)' $State.Notes
            return (Invoke-Apply $State $Config)
        }
        'SetGithubSecrets' {
            $reference = Get-WorkflowReference $Config.WorkflowPath
            $github = Get-GithubState
            if ($firebaseDefaulted -and -not $Config.Plan -and
                -not (Confirm-Step "FIREBASE_PROJECT_ID was not passed. Is the Firebase project the same as the Google Cloud project ($($State.ProjectId))?")) {
                Write-Line 'Pass -FirebaseProjectId <id> and run again. Nothing was set.'
                return 2
            }
            return (Invoke-GithubSetup $State $Config $reference $github $Config.Force)
        }
        'Verify' {
            $reference = Get-WorkflowReference $Config.WorkflowPath
            $github = Get-GithubState
            $gcpClean = Test-AuditClean $State.Items
            Test-GithubSetup $State $Config $reference $github $gcpClean
            Show-AuditTable $State.Items 'Staging setup compared with .github/workflows/deploy-staging.yml' $State.Notes
            if ($Config.Plan) { return 0 }
            if (Test-AuditClean $State.Items) { Write-Line 'VERIFY: OK. Everything the workflow needs is present.'; return 0 }
            Write-Line 'VERIFY: NOT READY. See "Next actions" above.'
            return 1
        }
    }
}

# Dot-sourcing (". bootstrap-staging.ps1") only defines the functions; that is how the tests load it.
if ($MyInvocation.InvocationName -ne '.') {
    $mode = $PSCmdlet.ParameterSetName
    exit (Invoke-Bootstrap (New-BootstrapConfig -Bound $PSBoundParameters -Mode $mode))
}
