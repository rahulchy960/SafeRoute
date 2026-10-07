# SPDX-License-Identifier: AGPL-3.0-only
# Pester tests for bootstrap-staging.ps1. No Google or GitHub access: every gcloud/gh call goes
# through Invoke-External, which is replaced by a router that answers from scenario data.
# All identifiers are fake (project "example-staging-000", number "000000000000").
[Diagnostics.CodeAnalysis.SuppressMessageAttribute('PSUseDeclaredVarsMoreThanAssignments', '',
    Justification = 'Pester shares $script: variables between BeforeAll, BeforeEach and It blocks.')]
[Diagnostics.CodeAnalysis.SuppressMessageAttribute('PSUseShouldProcessForStateChangingFunctions', '',
    Justification = 'Test helpers build data in memory.')]
param()

BeforeAll {
    $script:ScriptPath = Join-Path (Split-Path -Parent $PSScriptRoot) 'bootstrap-staging.ps1'
    $script:RepoRoot = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
    . $script:ScriptPath

    # Safety net: even if a mock were missing, a test can never start the real gcloud or gh
    # (which may be signed in on the machine that runs the tests).
    $script:RealResolveTool = ${function:Resolve-Tool}
    function Resolve-Tool {
        param([string]$Name)
        if ($Name -match '^(gcloud|gh)(\.cmd|\.exe|\.ps1)?$') { throw "TEST SAFETY: a test tried to start the real '$Name'." }
        return (& $script:RealResolveTool $Name)
    }

    $script:ProjectId = 'example-staging-000'
    $script:ProjectNumber = '000000000000'
    $script:GoodCondition = "assertion.repository == 'rahulchy960/SafeRoute' && assertion.ref == 'refs/heads/main' && assertion.environment == 'staging'"
    $script:PrincipalSet = "principalSet://iam.googleapis.com/projects/$script:ProjectNumber/locations/global/workloadIdentityPools/github/attribute.repository/rahulchy960/SafeRoute"

    function Get-FakeEmail {
        param([string]$Name)
        return "$Name@$script:ProjectId.iam.gserviceaccount.com"
    }

    function New-Scenario {
        <# 'full' = everything configured; 'real' = what the first audit found; 'nopool' = no pool. #>
        param([string]$Kind = 'full')
        $deploy = 'serviceAccount:' + (Get-FakeEmail 'sa-deploy')
        $runtime = 'serviceAccount:' + (Get-FakeEmail 'sa-api-runtime')
        $migration = 'serviceAccount:' + (Get-FakeEmail 'sa-migration')
        $owner = @{ role = 'roles/owner'; members = @('user:owner@example.com') }
        $scenario = @{
            ProjectId = $script:ProjectId; ProjectNumber = $script:ProjectNumber
            Apis = @('run.googleapis.com', 'artifactregistry.googleapis.com', 'sqladmin.googleapis.com',
                'secretmanager.googleapis.com', 'iam.googleapis.com', 'iamcredentials.googleapis.com',
                'sts.googleapis.com', 'cloudresourcemanager.googleapis.com', 'compute.googleapis.com')
            ServiceAccounts = @('sa-deploy', 'sa-api-runtime', 'sa-migration', 'sa-worker-runtime', 'firebase-adminsdk-fake', 'sa-osrm-runtime')
            # Routing (P012a2): both OSRM services deployed, private, callable by the API, logs excluded.
            OsrmServices = @('saferoute-osrm-walking', 'saferoute-osrm-driving')
            Exclusions = @(
                @{ name = 'exclude-saferoute-osrm-walking-requests'; filter = 'resource.type=cloud_run_revision AND resource.labels.service_name=saferoute-osrm-walking AND httpRequest.requestUrl:*' },
                @{ name = 'exclude-saferoute-osrm-driving-requests'; filter = 'resource.type=cloud_run_revision AND resource.labels.service_name=saferoute-osrm-driving AND httpRequest.requestUrl:*' },
                @{ name = 'exclude-search-request-urls'; filter = 'something else' }
            )
            Pool = 'ACTIVE'
            Providers = @(@{ Id = 'github-saferoute'; State = 'ACTIVE'; Condition = $script:GoodCondition })
            ArRepo = @{ Format = 'DOCKER'; Cleanup = $true }
            Sql = @{
                Version = 'POSTGRES_16'; Region = 'asia-south1'; State = 'RUNNABLE'; Backups = $true
                DeletionProtection = $true; SslMode = 'ENCRYPTED_ONLY'; Networks = @()
                Databases = @('postgres', 'saferoute'); Users = @('postgres', 'saferoute_app')
            }
            Secrets = @{ 'db-app-password' = 1; 'saferoute-staging-database-url' = 1 }
            Password = 'fake-password'
            Service = $true
            Policies = @{
                'project' = @($owner, @{ role = 'roles/run.developer'; members = @($deploy) },
                    @{ role = 'roles/cloudsql.client'; members = @($runtime, $migration) })
                'repo' = @(@{ role = 'roles/artifactregistry.writer'; members = @($deploy) })
                'sa:deploy' = @(@{ role = 'roles/iam.workloadIdentityUser'; members = @($script:PrincipalSet) })
                'sa:runtime' = @(@{ role = 'roles/iam.serviceAccountUser'; members = @($deploy) })
                'sa:migration' = @(@{ role = 'roles/iam.serviceAccountUser'; members = @($deploy) })
                'secret:url' = @(@{ role = 'roles/secretmanager.secretAccessor'; members = @($runtime, $migration) })
                'secret:password' = @()
                'run' = @(@{ role = 'roles/run.invoker'; members = @('allUsers') })
                'sa:osrm' = @(@{ role = 'roles/iam.serviceAccountUser'; members = @($deploy) })
                'run:saferoute-osrm-walking' = @(@{ role = 'roles/run.invoker'; members = @($runtime) })
                'run:saferoute-osrm-driving' = @(@{ role = 'roles/run.invoker'; members = @($runtime) })
            }
            PublicAfterDeploy = $true
            Github = @{
                Environment = $true
                EnvSecrets = @('GCP_PROJECT_ID', 'GCP_PROJECT_NUMBER', 'GCP_WIF_PROVIDER', 'GCP_DEPLOY_SA', 'GCP_RUNTIME_SA',
                    'GCP_MIGRATION_SA', 'CLOUD_SQL_CONNECTION_NAME', 'FIREBASE_PROJECT_ID')
                EnvVars = [ordered]@{ GCP_REGION = 'asia-south1'; AR_REPOSITORY = 'saferoute'; API_SERVICE = 'saferoute-api'
                    MIGRATION_JOB = 'saferoute-migrate'; API_MIN_INSTANCES = '0'; API_MAX_INSTANCES = '3' }
                RepoSecrets = @()
                RepoVars = [ordered]@{ STAGING_DEPLOY_ENABLED = 'true' }
            }
            FailOn = ''; FailMessage = 'ERROR: (gcloud) something failed'
        }
        if ($Kind -eq 'real' -or $Kind -eq 'nopool') {
            # What Rahul's audit found: accounts, pool, registry, instance and the password secret exist;
            # no provider, no URL secret, no Cloud Run service, no bindings, almost nothing on GitHub.
            $scenario.Providers = @()
            $scenario.ServiceAccounts = @('sa-deploy', 'sa-api-runtime', 'sa-migration', 'sa-worker-runtime', 'firebase-adminsdk-fake')
            $scenario.OsrmServices = @()
            $scenario.Exclusions = @()
            $scenario.ArRepo.Cleanup = $false
            $scenario.Secrets = @{ 'db-app-password' = 1 }
            $scenario.Service = $false
            $scenario.Policies = @{
                'project' = @($owner); 'repo' = @(); 'sa:deploy' = @(); 'sa:runtime' = @(); 'sa:migration' = @()
                'secret:url' = $null; 'secret:password' = @(); 'run' = $null
            }
            $scenario.Github.EnvSecrets = @()
            $scenario.Github.EnvVars = [ordered]@{ GCP_REGION = 'asia-south1' }
            $scenario.Github.RepoVars = [ordered]@{ STAGING_DEPLOY_ENABLED = 'true' }
        }
        if ($Kind -eq 'nopool') { $scenario.Pool = $null }
        return $scenario
    }

    function ConvertTo-FakeJson {
        param($Value)
        return (ConvertTo-Json -InputObject $Value -Depth 8 -Compress)
    }

    function Get-FakePolicy {
        param([string]$Scope)
        $bindings = $script:Scenario.Policies[$Scope]
        if ($null -eq $bindings) { return $null }
        return (ConvertTo-FakeJson @{ etag = 'fake'; bindings = @($bindings) })
    }

    function Get-FakeResponse {
        param([string]$Tool, [string[]]$Arguments)
        $s = $script:Scenario
        $line = $Arguments -join ' '
        $answer = { param($Out) [pscustomobject]@{ ExitCode = 0; StdOut = $Out; StdErr = '' } }
        $notFound = [pscustomobject]@{ ExitCode = 1; StdOut = ''; StdErr = 'ERROR: (gcloud) NOT_FOUND: Requested entity was not found.' }
        if ($s.FailOn -and $line -match $s.FailOn) {
            return [pscustomobject]@{ ExitCode = 1; StdOut = ''; StdErr = $s.FailMessage }
        }
        if ($Tool -eq 'gh') {
            $forEnv = $line -match '--env staging'
            switch -Regex ($line) {
                '^api repos/.+/environments/staging' {
                    if ($s.Github.Environment) { return & $answer "staging`n" }
                    return [pscustomobject]@{ ExitCode = 1; StdOut = ''; StdErr = 'gh: Not Found (HTTP 404)' }
                }
                '^secret list' {
                    $names = $s.Github.RepoSecrets
                    if ($forEnv) { $names = $s.Github.EnvSecrets }
                    return & $answer (ConvertTo-FakeJson @($names | ForEach-Object { @{ name = $_ } }))
                }
                '^variable list' {
                    $map = $s.Github.RepoVars
                    if ($forEnv) { $map = $s.Github.EnvVars }
                    return & $answer (ConvertTo-FakeJson @($map.Keys | ForEach-Object { @{ name = $_; value = $map[$_] } }))
                }
                '^(secret|variable) set ' { return & $answer '' }
            }
            throw "unexpected gh command in a test: $line"
        }
        switch -Regex ($line) {
            '^config get-value project' { return & $answer "$($s.ProjectId)`n" }
            '^projects describe' { return & $answer "$($s.ProjectNumber)`n" }
            '^projects get-iam-policy' { return & $answer (Get-FakePolicy 'project') }
            '^services list' { return & $answer (($s.Apis -join "`n") + "`n") }
            '^iam service-accounts list' { return & $answer ((($s.ServiceAccounts | ForEach-Object { Get-FakeEmail $_ }) -join "`n") + "`n") }
            '^iam service-accounts get-iam-policy (\S+)' {
                $scope = 'sa:deploy'
                if ($Matches[1] -match '^sa-api-runtime@') { $scope = 'sa:runtime' } elseif ($Matches[1] -match '^sa-migration@') { $scope = 'sa:migration' }
                elseif ($Matches[1] -match '^sa-osrm-runtime@') { $scope = 'sa:osrm' }
                $policy = Get-FakePolicy $scope
                if ($null -eq $policy) { return & $answer (ConvertTo-FakeJson @{ etag = 'fake' }) }
                return & $answer $policy
            }
            '^iam service-accounts create (\S+)' {
                $s.ServiceAccounts = @($s.ServiceAccounts) + $Matches[1]
                return & $answer ''
            }
            '^logging sinks describe _Default' {
                return & $answer (ConvertTo-FakeJson @{ name = '_Default'; exclusions = @($s.Exclusions) })
            }
            '^logging sinks update _Default --add-exclusion=name=([^,]+),filter=(.+)$' {
                $s.Exclusions = @($s.Exclusions) + @{ name = $Matches[1]; filter = $Matches[2] }
                return & $answer ''
            }
            '^run services describe (saferoute-osrm-\w+)' {
                if (@($s.OsrmServices) -notcontains $Matches[1]) { return $notFound }
                return & $answer (ConvertTo-FakeJson @{ metadata = @{ name = $Matches[1] }; status = @{ url = 'https://fake-osrm.example.run.app' } })
            }
            '^run services get-iam-policy (saferoute-osrm-\w+)' {
                if (@($s.OsrmServices) -notcontains $Matches[1]) { return $notFound }
                $policy = Get-FakePolicy "run:$($Matches[1])"
                if ($null -eq $policy) { return & $answer (ConvertTo-FakeJson @{ etag = 'fake' }) }
                return & $answer $policy
            }
            '^iam workload-identity-pools describe' {
                if (-not $s.Pool) { return $notFound }
                return & $answer (ConvertTo-FakeJson @{ name = "projects/$($s.ProjectNumber)/locations/global/workloadIdentityPools/github"; state = $s.Pool })
            }
            '^iam workload-identity-pools providers list' {
                $list = @($s.Providers | ForEach-Object {
                        @{
                            name = "projects/$($s.ProjectNumber)/locations/global/workloadIdentityPools/github/providers/$($_.Id)"
                            state = $_.State; attributeCondition = $_.Condition
                            attributeMapping = @{ 'google.subject' = 'assertion.sub'; 'attribute.repository' = 'assertion.repository' }
                            oidc = @{ issuerUri = 'https://token.actions.githubusercontent.com' }
                        }
                    })
                return & $answer (ConvertTo-FakeJson $list)
            }
            '^artifacts repositories describe' {
                if (-not $s.ArRepo) { return $notFound }
                $repo = @{ name = 'fake'; format = $s.ArRepo.Format }
                # Cleanup: $true = the committed policy file as the API returns it; a hashtable = that exact map.
                if ($s.ArRepo.Cleanup -is [hashtable]) { $repo.cleanupPolicies = $s.ArRepo.Cleanup }
                elseif ($s.ArRepo.Cleanup) {
                    $repo.cleanupPolicies = @{
                        'delete-images-older-than-7-days' = @{ id = 'delete-images-older-than-7-days'; action = 'DELETE'; condition = @{ tagState = 'ANY'; olderThan = '604800s' } }
                        'keep-10-most-recent-images' = @{ id = 'keep-10-most-recent-images'; action = 'KEEP'; mostRecentVersions = @{ keepCount = 10 } }
                        'keep-osrm-routing-images' = @{ id = 'keep-osrm-routing-images'; action = 'KEEP'; condition = @{ tagState = 'TAGGED'; tagPrefixes = @('osrm-') } }
                    }
                }
                return & $answer (ConvertTo-FakeJson $repo)
            }
            '^artifacts repositories get-iam-policy' { return & $answer (Get-FakePolicy 'repo') }
            '^sql instances describe' {
                if (-not $s.Sql) { return $notFound }
                return & $answer (ConvertTo-FakeJson @{
                        connectionName = "$($s.ProjectId):asia-south1:saferoute-db"; databaseVersion = $s.Sql.Version
                        region = $s.Sql.Region; state = $s.Sql.State
                        settings = @{
                            backupConfiguration = @{ enabled = $s.Sql.Backups }; deletionProtectionEnabled = $s.Sql.DeletionProtection
                            ipConfiguration = @{ sslMode = $s.Sql.SslMode; authorizedNetworks = @($s.Sql.Networks | ForEach-Object { @{ value = $_ } }) }
                        }
                    })
            }
            '^sql databases list' { return & $answer (($s.Sql.Databases -join "`n") + "`n") }
            '^sql users list' { return & $answer (ConvertTo-FakeJson @($s.Sql.Users | ForEach-Object { @{ name = $_; type = 'BUILT_IN' } })) }
            '^secrets describe (\S+)' {
                if (-not $s.Secrets.ContainsKey($Matches[1])) { return $notFound }
                return & $answer (ConvertTo-FakeJson @{ name = "projects/$($s.ProjectNumber)/secrets/$($Matches[1])" })
            }
            '^secrets versions list (\S+)' {
                $count = $s.Secrets[$Matches[1]]
                $versions = @()
                if ($count -gt 0) { $versions = @(1..$count | ForEach-Object { @{ name = "v$_"; state = 'ENABLED' } }) }
                return & $answer (ConvertTo-FakeJson $versions)
            }
            '^secrets versions access' { return & $answer $s.Password }
            '^secrets get-iam-policy (\S+)' {
                $scope = 'secret:password'
                if ($Matches[1] -eq 'saferoute-staging-database-url') { $scope = 'secret:url' }
                $policy = Get-FakePolicy $scope
                if ($null -eq $policy) { return $notFound }
                return & $answer $policy
            }
            '^run services describe' {
                if (-not $s.Service) { return $notFound }
                return & $answer (ConvertTo-FakeJson @{ metadata = @{ name = 'saferoute-api' }; status = @{ url = 'https://fake-service.example.run.app' } })
            }
            '^run services get-iam-policy' {
                $policy = Get-FakePolicy 'run'
                if ($null -eq $policy) { return $notFound }
                return & $answer $policy
            }
            '^run deploy' {
                $s.Service = $true
                $s.Policies['run'] = @()
                if ($s.PublicAfterDeploy) { $s.Policies['run'] = @(@{ role = 'roles/run.invoker'; members = @('allUsers') }) }
                return [pscustomobject]@{ ExitCode = 0; StdOut = ''; StdErr = 'Service URL: https://fake-service.example.run.app' }
            }
            '^secrets create (\S+)' { $s.Secrets[$Matches[1]] = 1; return & $answer '' }
            '(add-iam-policy-binding|services enable|create-oidc|set-cleanup-policies)' { return & $answer '' }
        }
        throw "unexpected gcloud command in a test: $line"
    }

    function Start-Scenario {
        param([string]$Kind = 'full', [hashtable]$Answers = @{})
        $script:Scenario = New-Scenario $Kind
        $script:Calls = New-Object System.Collections.ArrayList
        $script:Answers = $Answers
    }

    function Get-MutatingCall {
        return @($script:Calls | Where-Object {
                $_.Line -match '(^| )(create|create-oidc|deploy|enable|add-iam-policy-binding|set-cleanup-policies|patch|delete|update)( |$)' -or
                ($_.Tool -eq 'gh' -and $_.Line -match '^(secret|variable) set ')
            })
    }

    function Invoke-Mode {
        param([string]$Mode, [hashtable]$Extra = @{}, [switch]$ToHost)
        $bound = @{}
        foreach ($key in $Extra.Keys) { $bound[$key] = $Extra[$key] }
        $config = New-BootstrapConfig -Bound $bound -Mode $Mode
        if ($ToHost) { $script:ExitCode = Invoke-Bootstrap $config }
        else { $script:ExitCode = Invoke-Bootstrap $config 6>$null }
        $script:Output = ($script:Session.Lines -join "`n")
    }

    function Get-Row {
        param([string]$Pattern)
        return @($script:Session.Lines | Where-Object { $_ -match $Pattern })
    }
}

Describe 'bootstrap-staging.ps1' {
    BeforeEach {
        Mock Invoke-External {
            [void]$script:Calls.Add([pscustomobject]@{ Tool = $Tool; Line = ($Arguments -join ' '); Stdin = $StdinBytes })
            Get-FakeResponse -Tool $Tool -Arguments $Arguments
        }
        Mock Read-Answer {
            foreach ($pattern in $script:Answers.Keys) { if ($Prompt -match $pattern) { return $script:Answers[$pattern] } }
            if ($Prompt -match 'project ID') { return $script:Scenario.ProjectId }
            return 'y'
        }
    }

    Context 'the script file' {
        It 'is pure ASCII, so Windows PowerShell 5.1 reads it correctly without a byte-order mark' {
            $bytes = [System.IO.File]::ReadAllBytes($script:ScriptPath)
            @($bytes | Where-Object { $_ -gt 127 }).Count | Should -Be 0
        }
        It 'starts with the SPDX header' {
            (Get-Content -LiteralPath $script:ScriptPath -TotalCount 1) | Should -Be '# SPDX-License-Identifier: AGPL-3.0-only'
        }
        It 'accepts exactly one mode' {
            { & $script:ScriptPath -Apply -Verify -Plan } | Should -Throw
            { & $script:ScriptPath -Audit -SetGithubSecrets -Plan } | Should -Throw
        }
        It 'never contains a command that deletes, removes, patches or creates keys' {
            $text = Get-Content -LiteralPath $script:ScriptPath -Raw
            $text | Should -Not -Match "'(delete|remove-iam-policy-binding|keys)'"
            $text | Should -Not -Match "@\('sql', '(instances|users|databases)', '(create|patch|delete|set-password)'"
        }
    }

    Context 'audit: what the first real audit found' {
        BeforeEach { Start-Scenario 'real'; Invoke-Mode 'Audit' }

        It 'exits 0 and changes nothing' {
            $script:ExitCode | Should -Be 0
            @(Get-MutatingCall).Count | Should -Be 0
        }
        It 'reports the existing pieces as PRESENT' {
            (Get-Row 'Service account \(DeploySa\)') | Should -Match 'sa-deploy\s+sa-deploy\s+PRESENT'
            (Get-Row '^Workload Identity pool') | Should -Match 'PRESENT'
            (Get-Row '^Artifact Registry repository') | Should -Match 'PRESENT'
            (Get-Row '^Cloud SQL instance') | Should -Match 'state RUNNABLE\s+PRESENT'
            (Get-Row '^Cloud SQL database') | Should -Match 'saferoute\s+PRESENT'
            (Get-Row '^Cloud SQL user') | Should -Match 'saferoute_app\s+PRESENT'
            (Get-Row '^Secret with the database password') | Should -Match 'PRESENT'
        }
        It 'reports provider, URL secret, service and all ten bindings as MISSING' {
            (Get-Row '^Workload Identity provider') | Should -Match 'not found\s+MISSING'
            (Get-Row '^Secret with the database URL') | Should -Match 'not found\s+MISSING'
            (Get-Row '^Cloud Run service') | Should -Match 'MISSING'
            (Get-Row '^IAM: .+MISSING$').Count | Should -Be 10
        }
        It 'gives a next action for each open item' {
            $script:Output | Should -Match 'Next actions:'
            $script:Output | Should -Match '\[MISSING\] Workload Identity provider: Run with -Apply'
        }
        It 'never asks for the password secret value' {
            @($script:Calls | Where-Object { $_.Line -match 'versions access' }).Count | Should -Be 0
        }
    }

    Context 'audit: a fully configured project' {
        BeforeEach { Start-Scenario 'full'; Invoke-Mode 'Audit' }

        It 'reports every item as PRESENT' {
            $script:Output | Should -Match 'Nothing to do: every item is PRESENT'
            $script:Output | Should -Not -Match '(MISSING|WRONG|UNKNOWN)'
        }
        It 'ignores accounts it must leave alone' {
            $script:Output | Should -Not -Match 'firebase'
            $script:Output | Should -Not -Match 'sa-worker-runtime'
        }
    }

    Context 'audit: a missing pool' {
        BeforeEach { Start-Scenario 'nopool'; Invoke-Mode 'Audit' }

        It 'reports the pool as MISSING and points to the runbook' {
            (Get-Row '^Workload Identity pool') | Should -Match 'not found\s+MISSING'
            $script:Output | Should -Match 'Workload Identity pool: Not created by this script\. Follow docs/runbooks/gcp-staging-setup\.md step 7'
        }
        It 'does not list providers of a pool that does not exist' {
            @($script:Calls | Where-Object { $_.Line -match 'providers list' }).Count | Should -Be 0
            (Get-Row '^Workload Identity provider') | Should -Match 'MISSING'
        }
    }

    Context 'audit: wrong or risky settings' {
        It 'flags an authorized network 0.0.0.0/0 as WRONG' {
            Start-Scenario 'full'; $script:Scenario.Sql.Networks = @('0.0.0.0/0'); Invoke-Mode 'Audit'
            (Get-Row '^Cloud SQL authorized networks') | Should -Match 'open to the whole internet\s+WRONG'
        }
        It 'flags a wrong version, missing backups, deletion protection and SSL' {
            Start-Scenario 'full'
            $script:Scenario.Sql.Version = 'POSTGRES_15'; $script:Scenario.Sql.Backups = $false
            $script:Scenario.Sql.DeletionProtection = $false; $script:Scenario.Sql.SslMode = 'ALLOW_UNENCRYPTED_AND_ENCRYPTED'
            Invoke-Mode 'Audit'
            (Get-Row '^Cloud SQL (PostgreSQL version|automated backups|deletion protection|SSL required).+WRONG$').Count | Should -Be 4
        }
        It 'requires -DbName when there are several databases' {
            Start-Scenario 'full'; $script:Scenario.Sql.Databases = @('postgres', 'one', 'two'); Invoke-Mode 'Audit'
            (Get-Row '^Cloud SQL database') | Should -Match 'several: one, two\s+WRONG'
            $script:Output | Should -Match 'Pass -DbName <name>'
            Start-Scenario 'full'; $script:Scenario.Sql.Databases = @('postgres', 'one', 'two'); Invoke-Mode 'Audit' @{ DbName = 'two' }
            (Get-Row '^Cloud SQL database') | Should -Match 'two\s+two\s+PRESENT'
        }
        It 'reports extra roles as WRONG-EXTRA without failing the audit' {
            Start-Scenario 'full'
            $deploy = 'serviceAccount:' + (Get-FakeEmail 'sa-deploy')
            $script:Scenario.Policies['project'] += @{ role = 'roles/editor'; members = @($deploy) }
            $script:Scenario.Policies['sa:deploy'] += @{ role = 'roles/iam.workloadIdentityUser'; members = @('principalSet://iam.googleapis.com/projects/000000000000/locations/global/workloadIdentityPools/github/*') }
            Invoke-Mode 'Audit'
            (Get-Row 'IAM: sa-deploy on project\s+no other role\s+roles/editor\s+WRONG-EXTRA').Count | Should -Be 1
            (Get-Row 'on account sa-deploy\s+no other role\s+roles/iam.workloadIdentityUser\s+WRONG-EXTRA').Count | Should -Be 1
            @(Get-MutatingCall).Count | Should -Be 0
        }
        It 'uses a provider that exists under another name and reports its condition' {
            Start-Scenario 'full'; $script:Scenario.Providers[0].Id = 'gh-actions'; Invoke-Mode 'Audit'
            (Get-Row '^Workload Identity provider') | Should -Match '\(other name\) gh-actions: restricts repository, ref and environment\s+PRESENT'
            $script:Output.Contains("Note: No provider is named github-saferoute; using the only one on the pool, gh-actions. Its condition: [$script:GoodCondition]") | Should -BeTrue
        }
        It 'reports a provider deleted less than 30 days ago' {
            Start-Scenario 'full'; $script:Scenario.Providers[0].State = 'DELETED'; Invoke-Mode 'Audit'
            (Get-Row '^Workload Identity provider') | Should -Match 'deleted less than 30 days ago\s+WRONG'
        }
    }

    Context 'provider condition' {
        It 'accepts <Name>' -ForEach @(
            @{ Name = 'the exact condition'; Condition = "assertion.repository == 'rahulchy960/SafeRoute' && assertion.ref == 'refs/heads/main' && assertion.environment == 'staging'" }
            @{ Name = 'attribute.* names and double quotes'; Condition = 'attribute.repository=="rahulchy960/SafeRoute" && attribute.ref=="refs/heads/main" && attribute.environment=="staging"' }
            @{ Name = 'the subject form plus ref'; Condition = "assertion.sub == 'repo:rahulchy960/SafeRoute:environment:staging' && assertion.ref == 'refs/heads/main'" }
        ) {
            (Test-ProviderCondition $Condition).Ok | Should -BeTrue
        }
        It 'rejects <Name>' -ForEach @(
            @{ Name = 'no condition'; Condition = '' }
            @{ Name = 'owner only'; Condition = "assertion.repository_owner == 'rahulchy960'" }
            @{ Name = 'repository only'; Condition = "assertion.repository == 'rahulchy960/SafeRoute'" }
            @{ Name = 'no environment'; Condition = "assertion.repository == 'rahulchy960/SafeRoute' && assertion.ref == 'refs/heads/main'" }
            @{ Name = 'another repository'; Condition = "assertion.repository == 'someone/SafeRoute' && assertion.ref == 'refs/heads/main' && assertion.environment == 'staging'" }
            @{ Name = 'a prefix of the repository'; Condition = "assertion.repository == 'rahulchy960/SafeRoute-fork' && assertion.ref == 'refs/heads/main' && assertion.environment == 'staging'" }
            @{ Name = 'an OR'; Condition = "assertion.repository == 'rahulchy960/SafeRoute' && assertion.ref == 'refs/heads/main' && assertion.environment == 'staging' || true" }
            @{ Name = 'the literal true'; Condition = 'true' }
            @{ Name = 'a negation'; Condition = "!(assertion.repository == 'rahulchy960/SafeRoute' && assertion.ref == 'refs/heads/main' && assertion.environment == 'staging')" }
            @{ Name = 'a conditional'; Condition = "true ? true : assertion.repository == 'rahulchy960/SafeRoute' && assertion.ref == 'refs/heads/main' && assertion.environment == 'staging'" }
        ) {
            (Test-ProviderCondition $Condition).Ok | Should -BeFalse
        }
        It 'shows a too-permissive provider as WRONG and does not touch it' {
            Start-Scenario 'full'; $script:Scenario.Providers[0].Condition = "assertion.repository_owner == 'rahulchy960'"
            Invoke-Mode 'Apply'
            (Get-Row '^Workload Identity provider') | Should -Match 'too permissive.+WRONG'
            @(Get-MutatingCall | Where-Object { $_.Line -match 'providers' }).Count | Should -Be 0
        }
    }

    Context 'routing: the private OSRM services (ADR 0020)' {
        BeforeAll {
            function Remove-OsrmSetup {
                <# The state right after P012a2 is merged: nothing for routing exists yet. #>
                $script:Scenario.ServiceAccounts = @($script:Scenario.ServiceAccounts | Where-Object { $_ -ne 'sa-osrm-runtime' })
                $script:Scenario.OsrmServices = @()
                $script:Scenario.Exclusions = @($script:Scenario.Exclusions | Where-Object { $_.name -notmatch 'osrm' })
                $script:Scenario.Policies.Remove('sa:osrm')
            }
        }

        It 'audit: reports account, binding and exclusions as MISSING, services and invoker as PENDING, and changes nothing' {
            Start-Scenario 'full'; Remove-OsrmSetup; Invoke-Mode 'Audit'
            (Get-Row '^Routing: service account for OSRM') | Should -Match 'not found\s+MISSING'
            (Get-Row '^Routing: sa-deploy on account sa-osrm-runtime') | Should -Match 'MISSING'
            (Get-Row '^Routing: request-log exclusion for .+MISSING$').Count | Should -Be 2
            (Get-Row '^Routing: Cloud Run service .+PENDING$').Count | Should -Be 2
            (Get-Row '^Routing: sa-api-runtime may call .+PENDING$').Count | Should -Be 2
            $script:Output | Should -Match '\[PENDING\] Routing: Cloud Run service saferoute-osrm-walking: Run the osrm-staging workflow'
            @(Get-MutatingCall).Count | Should -Be 0
        }
        It 'verify: services that are not deployed yet do not make the setup "not ready"' {
            Start-Scenario 'full'; $script:Scenario.OsrmServices = @(); Invoke-Mode 'Verify'
            $script:Output | Should -Match 'PENDING'
            $script:Output | Should -Match 'VERIFY: OK'
            $script:ExitCode | Should -Be 0
        }
        It 'apply: creates the account, the binding and both exclusions, in that order, and nothing else' {
            Start-Scenario 'full'; Remove-OsrmSetup; Invoke-Mode 'Apply'
            $calls = @(Get-MutatingCall | ForEach-Object { $_.Line })
            $calls.Count | Should -Be 4
            $calls[0] | Should -Match '^iam service-accounts create sa-osrm-runtime --display-name='
            $calls[1] | Should -Be "iam service-accounts add-iam-policy-binding $(Get-FakeEmail 'sa-osrm-runtime') --member=serviceAccount:$(Get-FakeEmail 'sa-deploy') --role=roles/iam.serviceAccountUser"
            $calls[2] | Should -Be 'logging sinks update _Default --add-exclusion=name=exclude-saferoute-osrm-walking-requests,filter=resource.type=cloud_run_revision AND resource.labels.service_name=saferoute-osrm-walking AND httpRequest.requestUrl:*'
            $calls[3] | Should -Match 'name=exclude-saferoute-osrm-driving-requests,filter=.+service_name=saferoute-osrm-driving AND'
            $script:Output | Should -Match 'LATER: invoker binding on saferoute-osrm-walking: the service does not exist yet'
            $script:Output | Should -Not -Match 'NOT DONE, blocked'
            $script:ExitCode | Should -Be 0
        }
        It 'apply: the OSRM account never gets a project role, and no command names allUsers' {
            Start-Scenario 'full'; Remove-OsrmSetup; Invoke-Mode 'Apply'
            @($script:Calls | Where-Object { $_.Line -match '^projects add-iam-policy-binding' }).Count | Should -Be 0
            @($script:Calls | Where-Object { $_.Line -match 'allUsers|allAuthenticatedUsers|allow-unauthenticated' }).Count | Should -Be 0
        }
        It 'apply: once the services exist, lets only the API account call them' {
            Start-Scenario 'full'
            $script:Scenario.Policies['run:saferoute-osrm-walking'] = @(); $script:Scenario.Policies['run:saferoute-osrm-driving'] = @()
            Invoke-Mode 'Apply'
            $calls = @(Get-MutatingCall | ForEach-Object { $_.Line })
            $calls.Count | Should -Be 2
            $calls[0] | Should -Be "run services add-iam-policy-binding saferoute-osrm-walking --region=asia-south1 --member=serviceAccount:$(Get-FakeEmail 'sa-api-runtime') --role=roles/run.invoker"
            $calls[1] | Should -Match '^run services add-iam-policy-binding saferoute-osrm-driving .+sa-api-runtime@.+--role=roles/run.invoker$'
        }
        It 'apply: a second run changes nothing (idempotent)' {
            Start-Scenario 'full'; Remove-OsrmSetup; Invoke-Mode 'Apply'
            $script:Scenario.Policies['sa:osrm'] = @(@{ role = 'roles/iam.serviceAccountUser'; members = @('serviceAccount:' + (Get-FakeEmail 'sa-deploy')) })
            $script:Calls.Clear(); Invoke-Mode 'Apply'
            @(Get-MutatingCall).Count | Should -Be 0
        }
        It 'flags a PUBLIC OSRM service as WRONG, fails -Verify, and never touches the service' {
            Start-Scenario 'full'
            $script:Scenario.Policies['run:saferoute-osrm-driving'] += @{ role = 'roles/run.invoker'; members = @('allUsers') }
            Invoke-Mode 'Verify'
            (Get-Row '^Routing: saferoute-osrm-driving is not public') | Should -Match 'PUBLIC.+WRONG'
            (Get-Row '^Routing: saferoute-osrm-walking is not public') | Should -Match 'private\s+PRESENT'
            $script:ExitCode | Should -Be 1
            Start-Scenario 'full'
            $script:Scenario.Policies['run:saferoute-osrm-driving'] += @{ role = 'roles/run.invoker'; members = @('allAuthenticatedUsers') }
            Invoke-Mode 'Apply'
            (Get-Row '^Routing: saferoute-osrm-driving is not public') | Should -Match 'WRONG'
            @(Get-MutatingCall).Count | Should -Be 0
        }
        It 'reports a role held by the OSRM account as WRONG-EXTRA' {
            Start-Scenario 'full'
            $script:Scenario.Policies['project'] += @{ role = 'roles/logging.logWriter'; members = @('serviceAccount:' + (Get-FakeEmail 'sa-osrm-runtime')) }
            Invoke-Mode 'Audit'
            (Get-Row '^Routing: sa-osrm-runtime on project\s+no role\s+roles/logging.logWriter\s+WRONG-EXTRA').Count | Should -Be 1
        }
        It 'flags a disabled or rewritten exclusion as WRONG and does not change it' {
            Start-Scenario 'full'; $script:Scenario.Exclusions[0].disabled = $true
            $script:Scenario.Exclusions[1].filter = 'resource.type=cloud_run_revision'
            Invoke-Mode 'Apply'
            (Get-Row '^Routing: request-log exclusion for .+WRONG$').Count | Should -Be 2
            @(Get-MutatingCall).Count | Should -Be 0
        }
        It 'builds a filter that survives gcloud and cmd.exe: no comma, double quote or percent sign' {
            $exclusion = Get-RoutingExclusion 'saferoute-osrm-walking'
            $exclusion.Filter | Should -Not -Match '[,"%]'
            $exclusion.Filter | Should -Match 'requestUrl'
            $exclusion.Name | Should -Match '^[a-z0-9-]+$'
        }
        It 'uses the service and account names of the osrm-staging workflow' {
            $workflow = Get-Content -LiteralPath (Join-Path $script:RepoRoot '.github/workflows/osrm-staging.yml') -Raw
            $config = New-BootstrapConfig -Bound @{} -Mode 'Audit'
            $workflow | Should -Match "OSRM_WALKING_SERVICE: $($config.OsrmWalkingService)\s"
            $workflow | Should -Match "OSRM_DRIVING_SERVICE: $($config.OsrmDrivingService)\s"
            $workflow | Should -Match "OSRM_RUNTIME_SA_NAME: $($config.OsrmRuntimeSa)\s"
        }
    }

    Context 'registry cleanup and routing images (tag osrm-)' {
        BeforeAll {
            $script:OldPolicy = @{
                'delete-images-older-than-7-days' = @{ action = 'DELETE'; condition = @{ tagState = 'ANY'; olderThan = '604800s' } }
                'keep-10-most-recent-images' = @{ action = 'KEEP'; mostRecentVersions = @{ keepCount = 10 } }
            }
        }

        It 'the policy file deletes by age, keeps the 10 newest, and keeps every image tagged osrm-' {
            $policy = Get-Content -LiteralPath (Join-Path $script:RepoRoot 'infra/artifact-registry-cleanup-policy.json') -Raw | ConvertFrom-Json
            $keep = @($policy | Where-Object { $_.action.type -eq 'Keep' -and $_.condition })
            $keep.Count | Should -Be 1
            $keep[0].condition.tagState | Should -Be 'tagged'
            @($keep[0].condition.tagPrefixes) | Should -Be @('osrm-')
            $keep[0].condition.PSObject.Properties.Name | Should -Not -Contain 'olderThan'
            @($policy | Where-Object { $_.action.type -eq 'Delete' }).Count | Should -Be 1
            @($policy | Where-Object { $_.mostRecentVersions.keepCount -eq 10 }).Count | Should -Be 1
        }
        It 'the workflow tags routing images with the prefix the keep rule matches' {
            $workflow = Get-Content -LiteralPath (Join-Path $script:RepoRoot '.github/workflows/osrm-staging.yml') -Raw
            $workflow.Contains('echo "tag=osrm-$' + '{INPUT_PROFILE}-$' + '{INPUT_EXTRACT_DATE}"') | Should -BeTrue
        }
        It 'audit: the current policy is PRESENT for routing images' {
            Start-Scenario 'full'; Invoke-Mode 'Audit'
            (Get-Row '^Registry cleanup keeps routing images') | Should -Match 'keep-osrm-routing-images\s+PRESENT'
        }
        It 'audit: the earlier policy (no keep rule for osrm-) is a NOTE that does not fail -Verify' {
            Start-Scenario 'full'; $script:Scenario.ArRepo.Cleanup = $script:OldPolicy; Invoke-Mode 'Verify'
            (Get-Row '^Registry cleanup keeps routing images') | Should -Match 'a delete rule and no such keep rule\s+NOTE'
            $script:Output | Should -Match '\[NOTE\] Registry cleanup keeps routing images \(tag osrm-\): Run with -Apply'
            $script:ExitCode | Should -Be 0
        }
        It 'apply: sets the policy file again when the repository has the earlier version of it, and only that' {
            Start-Scenario 'full'; $script:Scenario.ArRepo.Cleanup = $script:OldPolicy; Invoke-Mode 'Apply'
            $calls = @(Get-MutatingCall | ForEach-Object { $_.Line })
            $calls.Count | Should -Be 1
            $calls[0] | Should -Match '^artifacts repositories set-cleanup-policies saferoute --location=asia-south1 --policy=.+artifact-registry-cleanup-policy\.json --no-dry-run$'
            $script:Output | Should -Match 'adds: keep every image tagged osrm-'
        }
        It 'apply: never replaces a policy it did not write, and says how to add the rule by hand' {
            Start-Scenario 'full'
            $script:Scenario.ArRepo.Cleanup = @{ 'my-own-rule' = @{ action = 'DELETE'; condition = @{ tagState = 'ANY'; olderThan = '2592000s' } } }
            Invoke-Mode 'Apply'
            @(Get-MutatingCall).Count | Should -Be 0
            $script:Output | Should -Match 'policies this script did not write \(my-own-rule\); they are not replaced'
            $script:Output | Should -Match 'Conditional keep, tag prefix osrm-'
        }
        It 'audit: a hand-made keep rule for osrm- under any name counts' {
            Start-Scenario 'full'
            $script:Scenario.ArRepo.Cleanup = @{
                'my-own-rule' = @{ action = 'DELETE'; condition = @{ tagState = 'ANY'; olderThan = '2592000s' } }
                'my-keep' = @{ action = 'KEEP'; condition = @{ tagState = 'TAGGED'; tagPrefixes = @('release', 'osrm-') } }
            }
            Invoke-Mode 'Audit'
            (Get-Row '^Registry cleanup keeps routing images') | Should -Match 'my-keep\s+PRESENT'
        }
        It 'audit: a policy without a delete rule needs no keep rule' {
            Start-Scenario 'full'; $script:Scenario.ArRepo.Cleanup = @{ 'keep-recent' = @{ action = 'KEEP'; mostRecentVersions = @{ keepCount = 5 } } }
            Invoke-Mode 'Audit'
            @(Get-Row '^Registry cleanup keeps routing images').Count | Should -Be 0
        }
    }

    Context 'apply' {
        It 'issues no command at all when everything is PRESENT (idempotent)' {
            Start-Scenario 'full'; Invoke-Mode 'Apply'
            $script:ExitCode | Should -Be 0
            @(Get-MutatingCall).Count | Should -Be 0
            $script:Output | Should -Match 'Nothing was changed'
        }
        It 'creates only what is MISSING, in dependency order' {
            Start-Scenario 'real'; Invoke-Mode 'Apply'
            $script:ExitCode | Should -Be 0
            $lines = @(Get-MutatingCall | ForEach-Object { $_.Line })
            $lines[0] | Should -Match '^iam workload-identity-pools providers create-oidc github-saferoute '
            $lines[1] | Should -Match '^secrets create saferoute-staging-database-url --replication-policy=user-managed --locations=asia-south1 --data-file=-$'
            $lines[2] | Should -Match '^run deploy saferoute-api --image=us-docker\.pkg\.dev/cloudrun/container/hello --region=asia-south1 --service-account=sa-api-runtime@.+ --allow-unauthenticated --max-instances=1$'
            # Nine bindings of the API deploy, plus one for routing: the deploy account may use sa-osrm-runtime.
            @($lines | Where-Object { $_ -match 'add-iam-policy-binding' }).Count | Should -Be 10
            @($lines | Where-Object { $_ -match '^iam service-accounts create sa-osrm-runtime ' }).Count | Should -Be 1
            @($lines | Where-Object { $_ -match '^logging sinks update _Default --add-exclusion=' }).Count | Should -Be 2
            $lines[-1] | Should -Match '^artifacts repositories set-cleanup-policies saferoute '
            $lines.Count | Should -Be 17
            @($lines | Where-Object { $_ -match 'services enable' }).Count | Should -Be 0
        }
        It 'creates the provider with the mapping and the restrictive condition' {
            Start-Scenario 'real'; Invoke-Mode 'Apply'
            $create = (Get-MutatingCall | Where-Object { $_.Line -match 'create-oidc' }).Line
            $create | Should -Match '--issuer-uri=https://token\.actions\.githubusercontent\.com'
            $create | Should -Match '--attribute-mapping=google\.subject=assertion\.sub,attribute\.repository=assertion\.repository,attribute\.repository_owner=assertion\.repository_owner,attribute\.ref=assertion\.ref,attribute\.environment=assertion\.environment'
            $condition = [regex]::Match($create, '--attribute-condition=(.+)$').Groups[1].Value
            (Test-ProviderCondition $condition).Ok | Should -BeTrue
        }
        It 'grants exactly the roles ADR 0007 lists and nothing broader' {
            Start-Scenario 'real'; Invoke-Mode 'Apply'
            $roles = @(Get-MutatingCall | Where-Object { $_.Line -match 'add-iam-policy-binding' } | ForEach-Object { [regex]::Match($_.Line, '--role=(\S+)').Groups[1].Value } | Sort-Object -Unique)
            $roles | Should -Be @('roles/artifactregistry.writer', 'roles/cloudsql.client', 'roles/iam.serviceAccountUser', 'roles/iam.workloadIdentityUser', 'roles/run.developer', 'roles/secretmanager.secretAccessor')
            @(Get-MutatingCall | Where-Object { $_.Line -match 'firebase|sa-worker-runtime|roles/(owner|editor)' }).Count | Should -Be 0
            @(Get-MutatingCall | Where-Object { $_.Line -match '^secrets add-iam-policy-binding db-app-password' }).Count | Should -Be 0
        }
        It 'adds the public binding itself when the deploy did not create it, and explains a refusal' {
            Start-Scenario 'real'; $script:Scenario.PublicAfterDeploy = $false
            $script:Scenario.FailOn = 'run services add-iam-policy-binding'; $script:Scenario.FailMessage = 'ERROR: FAILED_PRECONDITION: One or more users named in the policy do not belong to a permitted customer.'
            Invoke-Mode 'Apply'
            $script:ExitCode | Should -Be 1
            $script:Output | Should -Match 'iam\.allowedPolicyMemberDomains'
            $script:Output | Should -Match 'does not work around it'
        }
        It 'enables only the missing APIs' {
            Start-Scenario 'full'; $script:Scenario.Apis = @($script:Scenario.Apis | Where-Object { $_ -notmatch '^(sts|iamcredentials)\.' })
            Invoke-Mode 'Apply'
            @(Get-MutatingCall).Count | Should -Be 1
            @(Get-MutatingCall)[0].Line | Should -Be 'services enable iamcredentials.googleapis.com sts.googleapis.com'
        }
        It 'never creates the pool, an account other than the role-less OSRM one, the registry or anything in Cloud SQL' {
            Start-Scenario 'nopool'
            $script:Scenario.ServiceAccounts = @('sa-deploy'); $script:Scenario.ArRepo = $null; $script:Scenario.Sql = $null
            Invoke-Mode 'Apply'
            $script:ExitCode | Should -Be 1
            @(Get-MutatingCall | Where-Object { $_.Line -match 'workload-identity-pools create|service-accounts create (?!sa-osrm-runtime )|repositories create|^sql ' }).Count | Should -Be 0
            @(Get-MutatingCall | Where-Object { $_.Line -match 'create-oidc|secrets create|run deploy' }).Count | Should -Be 0
            $script:Output | Should -Match 'NOT DONE, blocked: provider: the Workload Identity pool is missing'
        }
        It 'does not add a version to a URL secret that already exists' {
            Start-Scenario 'full'; $script:Scenario.Secrets['saferoute-staging-database-url'] = 0; Invoke-Mode 'Apply'
            (Get-Row '^Secret with the database URL') | Should -Match '0 enabled version\(s\)\s+WRONG'
            @($script:Calls | Where-Object { $_.Line -match 'secrets (create|versions add|versions access)' }).Count | Should -Be 0
        }
        It 'changes nothing when the typed project ID is wrong' {
            Start-Scenario 'real' @{ 'project ID' = 'some-other-project' }; Invoke-Mode 'Apply'
            $script:ExitCode | Should -Be 2
            @(Get-MutatingCall).Count | Should -Be 0
        }
        It 'changes nothing when every step is answered with n' {
            Start-Scenario 'real' @{ '\[y/n\]' = 'n' }; Invoke-Mode 'Apply'
            @(Get-MutatingCall).Count | Should -Be 0
            @($script:Calls | Where-Object { $_.Line -match 'versions access' }).Count | Should -Be 0
        }
        It 'stops at the first error' {
            Start-Scenario 'real'; $script:Scenario.FailOn = 'create-oidc'; Invoke-Mode 'Apply'
            $script:ExitCode | Should -Be 1
            @(Get-MutatingCall).Count | Should -Be 1
            $script:Output | Should -Match 'FAILED: ERROR: \(gcloud\) something failed'
        }
    }

    Context 'the database URL secret' {
        It 'sends the exact URL on standard input, without a line break' {
            Start-Scenario 'real'; $script:Scenario.Password = 'p@ss:w/rd%#1 x'; Invoke-Mode 'Apply'
            $create = Get-MutatingCall | Where-Object { $_.Line -match '^secrets create' }
            [System.Text.Encoding]::UTF8.GetString($create.Stdin) |
                Should -BeExactly 'postgresql://saferoute_app:p%40ss%3Aw%2Frd%25%231%20x@/saferoute?host=/cloudsql/example-staging-000:asia-south1:saferoute-db'
            $create.Line | Should -Not -Match 'p@ss|p%40ss'
        }
        It 'ignores a byte-order mark and a line break around the stored password' {
            Start-Scenario 'real'; $script:Scenario.Password = ([char]0xFEFF) + "abc`r`n"; Invoke-Mode 'Apply'
            $create = Get-MutatingCall | Where-Object { $_.Line -match '^secrets create' }
            [System.Text.Encoding]::UTF8.GetString($create.Stdin) | Should -Match '^postgresql://saferoute_app:abc@/saferoute\?host='
        }
        It 'forgets the password and the URL when the step is over' {
            Start-Scenario 'real'; $script:Scenario.Password = 'p@ss-to-forget'; Invoke-Mode 'Apply'
            $script:Session.Secrets.Count | Should -Be 0
            Start-Scenario 'real'; $script:Scenario.Password = 'p@ss-to-forget'; $script:Scenario.FailOn = '^secrets create'; Invoke-Mode 'Apply'
            $script:Session.Secrets.Count | Should -Be 0
        }
        It 'warns that it cannot check that the password belongs to the user' {
            Start-Scenario 'real'; Invoke-Mode 'Apply'
            $script:Output | Should -Match 'cannot check that the password belongs to that user'
            $script:Output | Should -Match 'password authentication failed'
        }
        It 'never shows the secret value: output, transcript and errors' {
            $secret = 'Tr0ub4dor&3 p@ss:w/rd%#'
            $transcript = Join-Path ([System.IO.Path]::GetTempPath()) ("bootstrap-test-" + [guid]::NewGuid().ToString('N') + '.txt')
            Start-Scenario 'real'
            $script:Scenario.Password = $secret
            $script:Scenario.FailOn = '^secrets create'
            $script:Scenario.FailMessage = "ERROR: could not store postgresql://saferoute_app:$(ConvertTo-PercentEncoded $secret)@/saferoute (password $secret)"
            Start-Transcript -Path $transcript | Out-Null
            try { Invoke-Mode 'Apply' -Extra @{ ShowIds = $true } -ToHost } finally { Stop-Transcript | Out-Null }
            $recorded = Get-Content -LiteralPath $transcript -Raw
            Remove-Item -LiteralPath $transcript -Force
            $script:ExitCode | Should -Be 1
            $recorded | Should -Match 'STEP: create secret saferoute-staging-database-url'
            foreach ($text in @($script:Output, $recorded)) {
                $text.Contains($secret) | Should -BeFalse
                $text.Contains((ConvertTo-PercentEncoded $secret)) | Should -BeFalse
                $text.Contains('p@ss') | Should -BeFalse
            }
            $script:Output.Contains('FAILED: ERROR: could not store postgresql://saferoute_app:<secret>@/saferoute (password <secret>)') | Should -BeTrue
            @($script:Calls | Where-Object { $_.Line.Contains($secret) -or $_.Line.Contains('p%40ss') }).Count | Should -Be 0
        }
    }

    Context 'safety' {
        It 'refuses a project whose ID contains <Marker>' -ForEach @(
            @{ Marker = 'prod'; Id = 'example-prod-000' }
            @{ Marker = 'prd'; Id = 'example-prd-000' }
        ) {
            foreach ($mode in @('Audit', 'Apply', 'SetGithubSecrets', 'Verify')) {
                Start-Scenario 'real'; $script:Scenario.ProjectId = $Id; Invoke-Mode $mode
                $script:ExitCode | Should -Be 2
                $script:Calls.Count | Should -Be 1
                $script:Output | Should -Match 'REFUSED'
                $script:Output | Should -Not -Match ([regex]::Escape($Id))
            }
        }
        It 'masks the project ID, the project number, e-mail addresses and URLs by default' {
            foreach ($mode in @('Audit', 'Apply', 'SetGithubSecrets', 'Verify')) {
                Start-Scenario 'real'; Invoke-Mode $mode
                $script:Output | Should -Not -Match 'example-staging-000'
                $script:Output | Should -Not -Match '000000000000'
                $script:Output | Should -Not -Match '@'
                $script:Output | Should -Not -Match 'https?://'
            }
            Start-Scenario 'real'; Invoke-Mode 'Apply'
            $script:Output | Should -Match 'in project <project-id>'
            $script:Output | Should -Match '--service-account=<sa-email>'
            $script:Output | Should -Match '--issuer-uri=<url>'
            $script:Output | Should -Match 'projects/<project-number>/locations/global'
        }
        It 'stops with one readable line when a tool is missing or fails to start' {
            Start-Scenario 'full'
            Mock Invoke-External { throw "'gcloud' was not found on PATH. Install it, then open a new window." }
            Invoke-Mode 'Audit'
            $script:ExitCode | Should -Be 1
            $script:Output | Should -Match "STOPPED: 'gcloud' was not found on PATH"
        }
        It 'shows local paths relative to the repository' {
            Start-Scenario 'real'; Invoke-Mode 'Apply'
            $script:Output | Should -Match '--policy=<repo>.infra.artifact-registry-cleanup-policy\.json'
            $script:Output | Should -Not -Match ([regex]::Escape($script:RepoRoot))
        }
        It 'shows identifiers only with -ShowIds' {
            Start-Scenario 'real'; Invoke-Mode 'Apply' -Extra @{ ShowIds = $true }
            $script:Output | Should -Match 'example-staging-000'
            $script:Output | Should -Match 'sa-api-runtime@example-staging-000\.iam\.gserviceaccount\.com'
        }
        It 'runs nothing at all with -Plan, in every mode' {
            foreach ($mode in @('Audit', 'Apply', 'SetGithubSecrets', 'Verify')) {
                Start-Scenario 'real'; Invoke-Mode $mode -Extra @{ Plan = $true }
                $script:ExitCode | Should -Be 0
                $script:Calls.Count | Should -Be 0
                $script:Output | Should -Match 'would run: gcloud config get-value project'
            }
        }
        It 'prints every command with -Apply -Plan, including the ones that change something' {
            Start-Scenario 'real'; Invoke-Mode 'Apply' -Extra @{ Plan = $true }
            foreach ($command in @('services enable', 'providers create-oidc', 'secrets versions access latest --secret=db-app-password',
                    'secrets create saferoute-staging-database-url', 'run deploy saferoute-api', 'add-iam-policy-binding', 'set-cleanup-policies')) {
                $script:Output | Should -Match ('would run: gcloud .*' + [regex]::Escape($command))
            }
            $script:Output | Should -Match 'standard input: the database URL, built in memory, never shown'
        }
    }

    Context 'names derived from the workflow' {
        It 'reads secrets, environment variables and repository variables from a fixture' {
            $reference = Get-WorkflowReference (Join-Path (Join-Path $PSScriptRoot 'fixtures') 'workflow-sample.yml')
            $reference.Secrets | Should -Be @('SAMPLE_PROJECT', 'SAMPLE_PROVIDER')
            $reference.Variables | Should -Be @('SAMPLE_REGION', 'SAMPLE_STEP_FLAG')
            $reference.RepositoryVariables | Should -Be @('SAMPLE_SWITCH')
            $reference.UrlSecret | Should -Be 'sample-database-url'
        }
        It 'finds in deploy-staging.yml exactly the names this script can fill in' {
            $workflow = Join-Path (Join-Path (Join-Path $script:RepoRoot '.github') 'workflows') 'deploy-staging.yml'
            $reference = Get-WorkflowReference $workflow
            ($reference.Secrets | Sort-Object) | Should -Be @('CLOUD_SQL_CONNECTION_NAME', 'FIREBASE_PROJECT_ID', 'GCP_DEPLOY_SA',
                'GCP_MIGRATION_SA', 'GCP_PROJECT_ID', 'GCP_PROJECT_NUMBER', 'GCP_RUNTIME_SA', 'GCP_WIF_PROVIDER')
            ($reference.Variables | Sort-Object) | Should -Be @('API_MAX_INSTANCES', 'API_MIN_INSTANCES', 'API_SERVICE', 'AR_REPOSITORY', 'GCP_REGION', 'MIGRATION_JOB')
            $reference.RepositoryVariables | Should -Be @('STAGING_DEPLOY_ENABLED')
            $reference.UrlSecret | Should -Be 'saferoute-staging-database-url'
            $known = (Get-GithubValue @{ ProjectId = 'x' } (New-BootstrapConfig -Bound @{} -Mode 'Audit')).Keys
            foreach ($name in ($reference.Secrets + $reference.Variables)) { $known | Should -Contain $name }
        }
    }

    Context 'set GitHub secrets' {
        It 'refuses while something it needs is still missing in Google Cloud' {
            Start-Scenario 'real'; Invoke-Mode 'SetGithubSecrets'
            $script:ExitCode | Should -Be 1
            $script:Output | Should -Match 'No value yet for: GCP_WIF_PROVIDER'
            @(Get-MutatingCall | Where-Object { $_.Line -match 'GCP_WIF_PROVIDER' }).Count | Should -Be 0
        }
        It 'sets only the missing names and keeps existing ones' {
            Start-Scenario 'full'
            $script:Scenario.Github.EnvSecrets = @('GCP_PROJECT_ID')
            $script:Scenario.Github.EnvVars = [ordered]@{ GCP_REGION = 'asia-south1' }
            Invoke-Mode 'SetGithubSecrets'
            $script:ExitCode | Should -Be 0
            $set = @(Get-MutatingCall | ForEach-Object { $_.Line })
            $set.Count | Should -Be 12
            @($set | Where-Object { $_ -match ' (GCP_PROJECT_ID|GCP_REGION) ' }).Count | Should -Be 0
            $script:Output | Should -Match 'Already set, kept \(use -Force to overwrite\): GCP_PROJECT_ID, GCP_REGION'
        }
        It 'sends each value on standard input, exactly, and never as an argument' {
            Start-Scenario 'full'; $script:Scenario.Github.EnvSecrets = @(); Invoke-Mode 'SetGithubSecrets'
            $calls = @(Get-MutatingCall)
            $provider = $calls | Where-Object { $_.Line -match '^secret set GCP_WIF_PROVIDER' }
            $provider.Line | Should -Be 'secret set GCP_WIF_PROVIDER --env staging --repo rahulchy960/SafeRoute'
            [System.Text.Encoding]::UTF8.GetString($provider.Stdin) | Should -BeExactly 'projects/000000000000/locations/global/workloadIdentityPools/github/providers/github-saferoute'
            $connection = $calls | Where-Object { $_.Line -match '^secret set CLOUD_SQL_CONNECTION_NAME' }
            [System.Text.Encoding]::UTF8.GetString($connection.Stdin) | Should -BeExactly 'example-staging-000:asia-south1:saferoute-db'
            @($calls | Where-Object { $_.Line -match 'example-staging-000|000000000000|--body' }).Count | Should -Be 0
            $script:Output | Should -Not -Match 'example-staging-000'
        }
        It 'overwrites existing values only with -Force' {
            Start-Scenario 'full'; Invoke-Mode 'SetGithubSecrets'
            @(Get-MutatingCall).Count | Should -Be 0
            Start-Scenario 'full'; Invoke-Mode 'SetGithubSecrets' -Extra @{ Force = $true }
            @(Get-MutatingCall).Count | Should -Be 14
        }
        It 'uses -FirebaseProjectId when it is passed, and asks when it is not' {
            Start-Scenario 'full'; $script:Scenario.Github.EnvSecrets = @(); Invoke-Mode 'SetGithubSecrets' -Extra @{ FirebaseProjectId = 'example-firebase-000' }
            $firebase = Get-MutatingCall | Where-Object { $_.Line -match 'FIREBASE_PROJECT_ID' }
            [System.Text.Encoding]::UTF8.GetString($firebase.Stdin) | Should -BeExactly 'example-firebase-000'
            Start-Scenario 'full' @{ 'Firebase project the same' = 'n' }; $script:Scenario.Github.EnvSecrets = @(); Invoke-Mode 'SetGithubSecrets'
            $script:ExitCode | Should -Be 2
            @(Get-MutatingCall).Count | Should -Be 0
        }
        It 'creates the repository switch only when absent, and only as false' {
            Start-Scenario 'full'; $script:Scenario.Github.RepoVars = [ordered]@{}; Invoke-Mode 'SetGithubSecrets'
            (Get-MutatingCall | ForEach-Object { $_.Line }) | Should -Be @('variable set STAGING_DEPLOY_ENABLED --repo rahulchy960/SafeRoute --body false')
            Start-Scenario 'full'; $script:Scenario.Github.RepoVars = [ordered]@{ STAGING_DEPLOY_ENABLED = 'false' }; Invoke-Mode 'SetGithubSecrets' -Extra @{ Force = $true }
            @(Get-MutatingCall | Where-Object { $_.Line -match 'STAGING_DEPLOY_ENABLED' }).Count | Should -Be 0
        }
        It 'stops when the GitHub environment does not exist' {
            Start-Scenario 'full'; $script:Scenario.Github.Environment = $false; Invoke-Mode 'SetGithubSecrets'
            $script:ExitCode | Should -Be 1
            @(Get-MutatingCall).Count | Should -Be 0
        }
    }

    Context 'verify' {
        It 'exits 0 when Google Cloud and GitHub have everything the workflow needs' {
            Start-Scenario 'full'; Invoke-Mode 'Verify'
            $script:ExitCode | Should -Be 0
            $script:Output | Should -Match 'VERIFY: OK'
            @(Get-MutatingCall).Count | Should -Be 0
        }
        It 'exits 1 and lists the missing names for the real findings' {
            Start-Scenario 'real'; Invoke-Mode 'Verify'
            $script:ExitCode | Should -Be 1
            (Get-Row '^GitHub secret \S+\s+environment secret\s+not set\s+MISSING').Count | Should -Be 8
            (Get-Row '^GitHub variable \S+\s+environment variable\s+not set\s+MISSING').Count | Should -Be 5
            (Get-Row '^GitHub variable GCP_REGION') | Should -Match 'PRESENT'
            (Get-Row '^GitHub repository variable STAGING_DEPLOY_ENABLED') | Should -Match '= true while the setup is incomplete\s+WRONG'
        }
        It 'exits 1 when one secret is missing' {
            Start-Scenario 'full'; $script:Scenario.Github.EnvSecrets = @($script:Scenario.Github.EnvSecrets | Where-Object { $_ -ne 'GCP_WIF_PROVIDER' })
            Invoke-Mode 'Verify'
            $script:ExitCode | Should -Be 1
            (Get-Row '^GitHub secret GCP_WIF_PROVIDER') | Should -Match 'MISSING'
        }
        It 'reports a secret stored as a variable, and a variable stored as a secret' {
            Start-Scenario 'full'
            $script:Scenario.Github.EnvSecrets = @($script:Scenario.Github.EnvSecrets | Where-Object { $_ -ne 'GCP_DEPLOY_SA' }) + 'API_SERVICE'
            $script:Scenario.Github.EnvVars.Remove('API_SERVICE'); $script:Scenario.Github.EnvVars['GCP_DEPLOY_SA'] = 'x'
            Invoke-Mode 'Verify'
            $script:ExitCode | Should -Be 1
            (Get-Row '^GitHub secret GCP_DEPLOY_SA') | Should -Match 'stored as a VARIABLE\s+WRONG'
            (Get-Row '^GitHub variable API_SERVICE') | Should -Match 'stored as a SECRET\s+WRONG'
        }
        It 'reports the switch when it was created on the environment instead of the repository' {
            Start-Scenario 'full'; $script:Scenario.Github.RepoVars = [ordered]@{}; $script:Scenario.Github.EnvVars['STAGING_DEPLOY_ENABLED'] = 'true'
            Invoke-Mode 'Verify'
            $script:ExitCode | Should -Be 1
            (Get-Row '^GitHub repository variable STAGING_DEPLOY_ENABLED') | Should -Match 'set on the ENVIRONMENT.+WRONG'
            $script:Output | Should -Match 'It must be a repository variable'
        }
        It 'accepts the switch being off, and says how to turn it on once everything is ready' {
            Start-Scenario 'full'; $script:Scenario.Github.RepoVars['STAGING_DEPLOY_ENABLED'] = 'false'; Invoke-Mode 'Verify'
            $script:ExitCode | Should -Be 0
            $script:Output | Should -Match 'When you are ready to deploy: gh variable set STAGING_DEPLOY_ENABLED --repo rahulchy960/SafeRoute --body true'
        }
        It 'reports a variable whose value differs from the audited name' {
            Start-Scenario 'full'; $script:Scenario.Github.EnvVars['API_SERVICE'] = 'saferoute-apii'; Invoke-Mode 'Verify'
            $script:ExitCode | Should -Be 1
            (Get-Row '^GitHub variable API_SERVICE') | Should -Match 'WRONG'
        }
        It 'reports a secret name that differs from the one the workflow mounts' {
            Start-Scenario 'full'; $script:Scenario.Secrets['other-url'] = 1; Invoke-Mode 'Verify' -Extra @{ UrlSecret = 'other-url' }
            $script:ExitCode | Should -Be 1
            (Get-Row '^Secret name in the workflow') | Should -Match 'WRONG'
        }
        It 'exits 1 when the GitHub environment is missing' {
            Start-Scenario 'full'; $script:Scenario.Github.Environment = $false; Invoke-Mode 'Verify'
            $script:ExitCode | Should -Be 1
            (Get-Row '^GitHub environment') | Should -Match 'MISSING'
        }
    }
}

Describe 'helpers that are not mocked' {
    Context 'percent-encoding' {
        It 'encodes <Text>' -ForEach @(
            @{ Text = 'a@b:c/d%e#f g'; Expected = 'a%40b%3Ac%2Fd%25e%23f%20g' }
            @{ Text = 'AZaz09-._~'; Expected = 'AZaz09-._~' }
            @{ Text = '?&=+!*''()'; Expected = '%3F%26%3D%2B%21%2A%27%28%29' }
            @{ Text = ([string][char]0xE9); Expected = '%C3%A9' }
        ) {
            ConvertTo-PercentEncoded $Text | Should -BeExactly $Expected
        }
    }

    Context 'standard input and arguments, against a real child process (node)' {
        BeforeAll {
            $script:PrintStdin = "const c=[];process.stdin.on('data',d=>c.push(d)).on('end',()=>{const b=Buffer.concat(c);process.stdout.write(b.length+':'+b.toString('hex'))})"
            $script:PrintArgs = 'process.stdout.write(JSON.stringify(process.argv.slice(1)))'
            $script:OnWindows = ($env:OS -eq 'Windows_NT')
        }
        It 'needs node on PATH' {
            Get-Command node -CommandType Application -ErrorAction SilentlyContinue | Should -Not -BeNullOrEmpty
        }
        It 'delivers the exact bytes: no line break, no byte-order mark' {
            $text = 'postgresql://u:p%40ss@/db?host=/cloudsql/x:y:z'
            $result = Invoke-External -Tool 'node' -Arguments @('-e', $script:PrintStdin) -StdinBytes ([System.Text.Encoding]::UTF8.GetBytes($text))
            $result.ExitCode | Should -Be 0
            $hex = -join ([System.Text.Encoding]::UTF8.GetBytes($text) | ForEach-Object { $_.ToString('x2') })
            $result.StdOut | Should -BeExactly "$($text.Length):$hex"
        }
        It 'delivers non-ASCII text as UTF-8' {
            $text = 'p' + [char]0xE9 + [char]0x20AC
            $result = Invoke-External -Tool 'node' -Arguments @('-e', $script:PrintStdin) -StdinBytes ([System.Text.Encoding]::UTF8.GetBytes($text))
            $result.StdOut | Should -BeExactly '6:70c3a9e282ac'
        }
        It 'closes standard input when there is nothing to send' {
            (Invoke-External -Tool 'node' -Arguments @('-e', $script:PrintStdin)).StdOut | Should -BeExactly '0:'
        }
        It 'passes awkward arguments unchanged to a program' {
            $arguments = @('plain', 'two words', "assertion.repository == 'o/r' && assertion.ref == 'refs/heads/main'",
                '--format=value(projectNumber)', 'a"quote', 'trailing\', 'C:\path with space\', 'x<y>z|w')
            $result = Invoke-External -Tool 'node' -Arguments (@('-e', $script:PrintArgs, '--') + $arguments)
            @($result.StdOut | ConvertFrom-Json | ForEach-Object { $_ }) | Should -Be $arguments
        }
        It 'passes arguments and standard input unchanged through a .cmd wrapper, as gcloud.cmd needs' -Skip:($env:OS -ne 'Windows_NT') {
            $folder = Join-Path ([System.IO.Path]::GetTempPath()) ("bootstrap cmd test " + [guid]::NewGuid().ToString('N'))
            New-Item -ItemType Directory -Path $folder | Out-Null
            try {
                Set-Content -LiteralPath (Join-Path $folder 'echo.js') -Encoding Ascii -Value "const c=[];process.stdin.on('data',d=>c.push(d)).on('end',()=>process.stdout.write(JSON.stringify({args:process.argv.slice(2),stdin:Buffer.concat(c).toString('hex')})))"
                Set-Content -LiteralPath (Join-Path $folder 'fakecloud.cmd') -Encoding Ascii -Value "@echo off`r`nSETLOCAL DisableDelayedExpansion`r`nnode `"%~dp0echo.js`" %*"
                $arguments = @('secrets', 'create', 'name', '--data-file=-', '--format=value(name)',
                    "--attribute-condition=assertion.repository == 'o/r' && assertion.ref == 'refs/heads/main'", '--member=a|b>c<d^e!f')
                $result = Invoke-External -Tool (Join-Path $folder 'fakecloud.cmd') -Arguments $arguments -StdinBytes ([System.Text.Encoding]::UTF8.GetBytes('s3cret'))
                $parsed = $result.StdOut | ConvertFrom-Json
                @($parsed.args) | Should -Be $arguments
                $parsed.stdin | Should -BeExactly '733363726574'
                @(Get-ChildItem -LiteralPath $folder).Count | Should -Be 2
            }
            finally { Remove-Item -LiteralPath $folder -Recurse -Force }
        }
        It 'refuses an argument that cmd.exe would change' -Skip:($env:OS -ne 'Windows_NT') {
            $folder = Join-Path ([System.IO.Path]::GetTempPath()) ("bootstrap-cmd-" + [guid]::NewGuid().ToString('N'))
            New-Item -ItemType Directory -Path $folder | Out-Null
            try {
                Set-Content -LiteralPath (Join-Path $folder 'fakecloud.cmd') -Encoding Ascii -Value '@echo off'
                { Invoke-External -Tool (Join-Path $folder 'fakecloud.cmd') -Arguments @('%PATH%') } | Should -Throw
                { Invoke-External -Tool (Join-Path $folder 'fakecloud.cmd') -Arguments @('a"b') } | Should -Throw
            }
            finally { Remove-Item -LiteralPath $folder -Recurse -Force }
        }
        It 'fails clearly when a tool is not installed' {
            { Invoke-External -Tool 'no-such-tool-for-this-test' -Arguments @('x') } | Should -Throw '*was not found on PATH*'
        }
    }
}
