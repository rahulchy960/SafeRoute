# Runbook: one-time Google Cloud staging setup

Creates everything the staging deploy needs: Artifact Registry, three service accounts,
Cloud SQL, the database secret, least-privilege IAM, Workload Identity Federation for GitHub
Actions, a placeholder Cloud Run service and the GitHub `staging` environment. Design and
reasons: [ADR 0007](../adr/0007-gcp-staging-topology.md).

**Use the script first** ([The short path](#the-short-path-the-setup-script) below). It shows
what exists, creates only what is missing, fills in the GitHub secrets and variables, and checks
the result against the deploy workflow. The numbered steps after it are the reference for what
the script does, and the instructions for the few things it never creates.

| | |
| --- | --- |
| **Who runs it** | Rahul, signed in to `gcloud` and `gh` as the project owner. Claude Code never runs the script or these commands and has no Google credentials. |
| **When** | Once, before the first deploy; again whenever `deploy-staging` fails at its guard step or at sign-in. |
| **Scope** | The **staging** project only. The script refuses a project whose ID contains `prd` or `prod`. |
| **Time and cost** | About 15 minutes with the script. Cloud SQL costs money from step 4 onward (see step 11). |

## Before you start

- **Shell.** The commands are written for **Windows PowerShell 5.1** in the repository root.
  In **Cloud Shell or bash** the `gcloud`/`gh` commands are the same; change three things:
  - variables are defined as `NAME="value"` (no `$`, no spaces) and read as `"$NAME"`;
  - a line ends with `\` instead of a backtick;
  - the few steps marked **bash:** have their own variant.
- **Placeholders.** `<PROJECT_ID>` and `<FIREBASE_PROJECT_ID>` are typed by you in step 0 and
  nowhere else. Every later command reads shell variables.
- **Each step ends with a "Verify" command.** Don't continue until it shows what is described.
- **Rerunning.** "already exists" errors on a rerun are harmless for the create commands; the
  `add-iam-policy-binding` commands can be repeated safely.
- ⚠️ marks steps that cost money or are hard to undo.

### What must never be committed or pasted

- The database password and the full `DATABASE_URL`. They exist only in shell variables for a
  few minutes and then in Secret Manager.
- Project IDs, project numbers, service-account emails, the Workload Identity provider path,
  the Cloud SQL connection name and the Cloud Run service URL (it contains the project number).
  They aren't credentials, but they contain a personal handle: keep them out of the repository,
  pull requests, issues, Notion and any chat with Claude Code.
- Screenshots or terminal output that show any of the above.
- Service-account JSON keys. None are created here. If a guide tells you to download one, stop.

## The short path: the setup script

[`infra/staging/bootstrap-staging.ps1`](../../infra/staging/bootstrap-staging.ps1) works in
Windows PowerShell 5.1 and PowerShell 7. Run it from the repository root, in a window where
`gcloud` is signed in and the **staging** project is active
(`gcloud config set project <PROJECT_ID>`), and `gh auth status` shows your account.

| Step | Command | What it does |
| --- | --- | --- |
| 1 | `.\infra\staging\bootstrap-staging.ps1` | **Audit.** Read-only. A table of item · expected · found · `PRESENT` / `MISSING` / `WRONG`, then the next action for each open item |
| 2 | `.\infra\staging\bootstrap-staging.ps1 -Apply` | Creates only what is `MISSING`. Asks you to type the project ID, then `y/n` before each step. Stops at the first error |
| 3 | `.\infra\staging\bootstrap-staging.ps1 -SetGithubSecrets` | Sets the GitHub `staging` environment secrets and variables the workflow reads. Keeps values that already exist (add `-Force` to overwrite) |
| 4 | `.\infra\staging\bootstrap-staging.ps1 -Verify` | Audit plus a comparison with [`deploy-staging.yml`](../../.github/workflows/deploy-staging.yml). Ends with `VERIFY: OK` and exit code 0 only if nothing is missing |

If PowerShell refuses to run the file ("running scripts is disabled"), start it as
`powershell -ExecutionPolicy Bypass -File .\infra\staging\bootstrap-staging.ps1`.

**Useful switches:**

- `-Plan` prints every command the chosen mode could run and runs nothing. Read it once before
  the first `-Apply`.
- `-ShowIds` shows project ID, project number, e-mail addresses and URLs. Without it they appear
  as `<project-id>`, `<project-number>`, `<sa-email>` and `<url>`, so the output is safe to
  paste when you report a problem. Secret values are never shown, with or without it.

**Names.** The defaults are the names that exist in the staging project. Pass a parameter only
where yours differ:

| Parameter | Default | Meaning |
| --- | --- | --- |
| `-DeploySa`, `-RuntimeSa`, `-MigrationSa` | `sa-deploy`, `sa-api-runtime`, `sa-migration` | The three service accounts (short names) |
| `-PoolId`, `-ProviderId` | `github`, `github-saferoute` | Workload Identity pool and provider. A single provider under another name is used as it is |
| `-SqlInstance` | `saferoute-db` | Cloud SQL instance |
| `-DbName`, `-DbUser` | discovered | Needed only if the instance has more than one application database or user |
| `-PasswordSecret` | `db-app-password` | Existing secret that holds the database user's password |
| `-UrlSecret` | `saferoute-staging-database-url` | Secret the workflow mounts as `DATABASE_URL`. Must match the workflow |
| `-ArRepo`, `-ApiService`, `-MigrationJob` | `saferoute`, `saferoute-api`, `saferoute-migrate` | Registry repository, Cloud Run service, migration job |
| `-FirebaseProjectId` | the Google Cloud project ID (asks you to confirm) | Value of the `FIREBASE_PROJECT_ID` secret |

**What `-Apply` creates, and what it never touches:**

| Created if missing | Never created or changed (it prints the step below instead) |
| --- | --- |
| The eight APIs (step 1) | Artifact Registry repository (step 2) |
| The Workload Identity **provider**, with the restrictive condition (step 7) | Service accounts (step 3) |
| The database URL secret and its first version (step 5) | Cloud SQL instance, database and user (step 4) |
| The ten IAM bindings (eight in step 6, one each in steps 7 and 8) | The Workload Identity **pool** (step 7) |
| The placeholder Cloud Run service with public access (step 8) | Any secret that already exists, including `db-app-password` |
| Optionally, the registry cleanup policy (step 2) | The GitHub environment itself (step 9.1) |
| For routing (since P012a2): the role-less account `sa-osrm-runtime`, its `serviceAccountUser` binding for `sa-deploy`, one request-log exclusion per OSRM service, and `run.invoker` for `sa-api-runtime` once a service exists ([routing runbook](routing-capacity-staging.md), section 5) | The OSRM services themselves (the `osrm-staging` workflow deploys them) and any public access to them |

Nothing is ever deleted, renamed or removed, no key is created, and roles an account holds
beyond the list are reported as `WRONG-EXTRA`, not removed. The Firebase Admin SDK account, the
default compute account and `sa-worker-runtime` are never touched.

**The database URL.** `-Apply` reads the password from `-PasswordSecret` into memory, builds
`postgresql://<user>:<password>@/<database>?host=/cloudsql/<connection name>` with the
password URL-encoded, and sends it to Secret Manager on standard input, without a trailing line
break. Nothing is printed or written to disk. ⚠️ The script cannot check that the password
belongs to the database user it found. The first migration job proves it: if that job fails
with `password authentication failed`, see "Rotating the password later" in step 5.

**Public access.** The placeholder service allows unauthenticated calls on purpose: the API is
public and checks Firebase tokens itself (ADR 0006). If an organization policy
(`iam.allowedPolicyMemberDomains`) refuses `allUsers`, the script says so and stops. It does
not work around the policy; an organization administrator has to allow it for the project.

**The deploy switch.** `STAGING_DEPLOY_ENABLED` is a **repository** variable (step 9.4).
`-SetGithubSecrets` creates it as `false` if it doesn't exist and never changes it afterwards.
You turn it on yourself once `-Verify` says OK:

```powershell
gh variable set STAGING_DEPLOY_ENABLED --repo rahulchy960/SafeRoute --body "true"
```

**When `-Verify` is not OK,** each open item names its next action: usually "run with -Apply"
or "run with -SetGithubSecrets", otherwise the numbered step below.

## Reference: the manual steps

Each step says what the script does for it. Follow a step by hand only where the script says
it will not create something.

| Step | By the script | By hand |
| --- | --- | --- |
| 1 APIs | Enables missing ones | |
| 2 Artifact Registry | Optional cleanup policy | Create the repository |
| 3 Service accounts | Checks they exist | Create them |
| 4 Cloud SQL | Checks version, backups, deletion protection, SSL, networks, database, user | Create or change anything |
| 5 Database secret | Builds the URL secret from the password secret | Create the password secret; rotate |
| 6 IAM | Adds missing bindings; reports extra roles | Remove extra roles |
| 7 Workload Identity | Creates the provider; checks its condition | Create the pool; fix a wrong provider |
| 8 Placeholder service | Deploys it with public access | |
| 9 GitHub | Sets secrets and variables | Create the `staging` environment (9.1) |
| 10–12 | | Enable deploys, budget, teardown |

## Step 0: prerequisites and variables

1. Install the [Google Cloud CLI](https://cloud.google.com/sdk/docs/install) and the
   [GitHub CLI](https://cli.github.com/). Check them:

   ```powershell
   gcloud --version
   gh --version
   ```

   If `gcloud` says it runs with an unsupported Python, point it at Python 3.10 or newer for this
   window, for example `$env:CLOUDSDK_PYTHON = "C:\Program Files\Python312\python.exe"`.

2. Stop `gcloud` from writing its local log for this window. That log records every command
   **with its arguments**, and step 4 passes a password as an argument.

   ```powershell
   $env:CLOUDSDK_CORE_DISABLE_FILE_LOGGING = "1"
   ```

   **bash:** `export CLOUDSDK_CORE_DISABLE_FILE_LOGGING=1`

3. Define the variables. Type the two real IDs here only. The names below are the ones the
   script uses by default; `$DB_NAME` and `$DB_USER` are examples, so use the names your
   instance really has (the audit shows them).

   ```powershell
   $PROJECT_ID = "<PROJECT_ID>"
   $FIREBASE_PROJECT_ID = "<FIREBASE_PROJECT_ID>"
   $REGION = "asia-south1"
   $GITHUB_REPO = "rahulchy960/SafeRoute"

   $AR_REPOSITORY = "saferoute"
   $SQL_INSTANCE = "saferoute-db"
   $DB_NAME = "saferoute_staging"
   $DB_USER = "saferoute_app"
   $SECRET_NAME = "saferoute-staging-database-url"
   $API_SERVICE = "saferoute-api"
   $DEPLOY_SA = "sa-deploy@${PROJECT_ID}.iam.gserviceaccount.com"
   $RUNTIME_SA = "sa-api-runtime@${PROJECT_ID}.iam.gserviceaccount.com"
   $MIGRATION_SA = "sa-migration@${PROJECT_ID}.iam.gserviceaccount.com"
   ```

4. Sign in and select the project.

   ```powershell
   gcloud auth login
   gcloud config set project $PROJECT_ID
   $PROJECT_NUMBER = gcloud projects describe $PROJECT_ID --format="value(projectNumber)"
   gh auth status
   ```

   **bash:** `PROJECT_NUMBER="$(gcloud projects describe "$PROJECT_ID" --format='value(projectNumber)')"`

5. Define the helper that step 5 uses. PowerShell's own pipe (`"text" | command`) adds a line
   break, and on some consoles a byte-order mark, to what it sends. Either would end up inside
   the secret. This function sends the text exactly as written.

   ```powershell
   function Send-Exact([string]$Text, [string]$CommandLine) {
     $previous = [Console]::InputEncoding
     [Console]::InputEncoding = [System.Text.Encoding]::ASCII
     try {
       $psi = New-Object System.Diagnostics.ProcessStartInfo "cmd.exe", "/c $CommandLine"
       $psi.RedirectStandardInput = $true
       $psi.UseShellExecute = $false
       $p = [System.Diagnostics.Process]::Start($psi)
       $p.StandardInput.Write($Text)
       $p.StandardInput.Close()
       $p.WaitForExit()
       if ($p.ExitCode -ne 0) { throw "command failed with exit code $($p.ExitCode)" }
     } finally {
       [Console]::InputEncoding = $previous
     }
   }
   ```

   **bash:** not needed; `printf '%s' "$TEXT" | command` is already exact.

**Verify:**

```powershell
gcloud config get-value project
gcloud billing projects describe $PROJECT_ID --format="value(billingEnabled)"
```

The first prints your staging project ID. The second prints `True`. If it prints `False`, link a
billing account in the console (Billing → Account management) before continuing.

## Step 1: enable the APIs

*Script: `-Apply` enables the ones that are missing.*

```powershell
gcloud services enable run.googleapis.com artifactregistry.googleapis.com sqladmin.googleapis.com `
  secretmanager.googleapis.com iam.googleapis.com iamcredentials.googleapis.com `
  sts.googleapis.com cloudresourcemanager.googleapis.com
```

**Verify:** the list contains all eight names.

```powershell
gcloud services list --enabled --format="value(config.name)"
```

## Step 2: Artifact Registry

*Script: checks the repository; never creates it. `-Apply` offers the cleanup policy if none is set.*

A Docker repository for the API image, with a cleanup policy so old images don't accumulate.

```powershell
gcloud artifacts repositories create $AR_REPOSITORY --repository-format=docker `
  --location=$REGION --description="SafeRoute container images (staging)"

gcloud artifacts repositories set-cleanup-policies $AR_REPOSITORY --location=$REGION `
  --policy=infra/artifact-registry-cleanup-policy.json --no-dry-run
```

The policy file ([`infra/artifact-registry-cleanup-policy.json`](../../infra/artifact-registry-cleanup-policy.json))
deletes images older than 7 days but always keeps the 10 most recent versions of each image and,
since P012a3, **every image whose tag starts with `osrm-`** (the routing images; why:
[routing runbook](routing-capacity-staging.md), section 4). If the repository still has the
earlier two-rule policy, the audit shows a NOTE and `-Apply` offers to set the file again; a
policy you made yourself is never replaced. Tags are deliberately
**not** immutable in staging: Artifact Registry can't delete tagged images in an immutable
repository, so the cleanup policy would do nothing, and re-running a deploy for the same commit
would be rejected. Deploys use the image **digest**, which can't change (ADR 0007).

**Verify:** the format is `DOCKER`, and all three policies are listed.

```powershell
gcloud artifacts repositories describe $AR_REPOSITORY --location=$REGION --format="value(format)"
gcloud artifacts repositories list-cleanup-policies $AR_REPOSITORY --location=$REGION
```

## Step 3: service accounts

*Script: checks that the three exist; never creates one.*

A service account is an identity for software rather than a person. Three are created, and none
gets a key file. The deploy identity is `sa-deploy` (Plan v7 §13.1 and earlier versions of this
runbook called it `gcp-deploy-staging`; the name in the project is what counts).

```powershell
gcloud iam service-accounts create sa-deploy --display-name="GitHub Actions deploy (staging)" `
  --description="Used by the deploy-staging workflow through Workload Identity Federation. No keys."
gcloud iam service-accounts create sa-api-runtime --display-name="saferoute-api runtime (staging)" `
  --description="Identity of the Cloud Run API service. Reads the database secret, connects to Cloud SQL."
gcloud iam service-accounts create sa-migration --display-name="Migration and admin jobs (staging)" `
  --description="Identity of the migration and admin Cloud Run Jobs."
```

**Verify:** the three emails are listed.

```powershell
gcloud iam service-accounts list --format="value(email)"
```

## Step 4: Cloud SQL ⚠️ costs money from here

*Script: checks version, region, backups, deletion protection, SSL, authorized networks,
database and user. It never creates or changes anything here.*

PostgreSQL 16 on the smallest shared-core machine. Cost class: the cheapest Cloud SQL tier,
roughly US$10–15 a month if it runs all the time (an estimate: check the
[pricing calculator](https://cloud.google.com/products/calculator)). Shared-core machines are
not covered by the Cloud SQL SLA, which is acceptable for staging only.

1. Create the instance. This takes 5–15 minutes.

   ```powershell
   gcloud sql instances create $SQL_INSTANCE `
     --database-version=POSTGRES_16 --edition=enterprise --tier=db-f1-micro `
     --region=$REGION --availability-type=zonal `
     --storage-type=SSD --storage-size=10GB --storage-auto-increase --storage-auto-increase-limit=20 `
     --backup --backup-start-time=20:30 --retained-backups-count=7 `
     --assign-ip --ssl-mode=ENCRYPTED_ONLY --deletion-protection
   ```

   What the flags mean:

   | Flag | Why |
   | --- | --- |
   | `--edition=enterprise` | Required: for PostgreSQL 16 the default is Enterprise Plus, which has no shared-core tier and costs far more. |
   | `--tier=db-f1-micro` | Shared core, about 0.6 GB memory, 25 connections at most. |
   | `--backup …` | Daily automated backup at 20:30 UTC (02:00 IST), 7 kept. Point-in-time recovery stays **off** (the flag for it is not passed). |
   | `--assign-ip` without `--authorized-networks` | A public address exists, but no network is allowed to connect to it directly. The only way in is the Cloud SQL connector, which requires IAM permission (step 6). |
   | `--ssl-mode=ENCRYPTED_ONLY` | Unencrypted connections are refused. |
   | `--deletion-protection` | The instance can't be deleted by accident (see step 12). |

2. Create the database.

   ```powershell
   gcloud sql databases create $DB_NAME --instance=$SQL_INSTANCE
   ```

3. Generate a password in memory and create the application user with it. The password is
   never displayed. It is hexadecimal, so it needs no URL-encoding later.

   ```powershell
   $bytes = New-Object byte[] 24
   [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
   $DbPassword = -join ($bytes | ForEach-Object { $_.ToString("x2") })
   gcloud sql users create $DB_USER --instance=$SQL_INSTANCE --password="$DbPassword"
   ```

   **bash:** `DB_PASSWORD="$(openssl rand -hex 24)"`, then the same `gcloud` command with
   `--password="$DB_PASSWORD"`.

   Your command history stores the text `$DbPassword`, not the value. **Keep this window open
   until step 5 is done**; the variable is the only copy.

   The built-in `postgres` user keeps having no password, so nobody can sign in as it.

**Verify:**

```powershell
gcloud sql instances describe $SQL_INSTANCE --format="value(state,databaseVersion,settings.tier,settings.ipConfiguration.sslMode,settings.backupConfiguration.enabled,settings.backupConfiguration.pointInTimeRecoveryEnabled,settings.deletionProtectionEnabled)"
gcloud sql instances describe $SQL_INSTANCE --format="value(settings.ipConfiguration.authorizedNetworks)"
gcloud sql databases list --instance=$SQL_INSTANCE --format="value(name)"
gcloud sql users list --instance=$SQL_INSTANCE --format="value(name)"
```

- First line: `RUNNABLE  POSTGRES_16  db-f1-micro  ENCRYPTED_ONLY  True  False  True`. If
  point-in-time recovery shows `True`, turn it off with
  `gcloud sql instances patch $SQL_INSTANCE --no-enable-point-in-time-recovery`.
- Second line: empty (no authorized networks).
- The databases include `saferoute_staging`; the users include `saferoute_app`.

## Step 5: the database URL in Secret Manager

*Script: if the URL secret is missing, `-Apply` builds it from the password secret
(`db-app-password` by default) and stores it. It never changes a secret that exists.*

Two secrets are involved. The **password secret** holds only the database user's password; it
is optional and exists in the staging project as `db-app-password`. The **URL secret**
(`saferoute-staging-database-url`) holds the whole connection URL and is the one the workflow
mounts as `DATABASE_URL`. The commands below create the URL secret by hand from the password
generated in step 4.3, without a password secret.

The API connects through a unix socket that Cloud Run mounts at `/cloudsql/<connection name>`,
so the URL has no host before the `/` and names the socket directory in `host=`.

```powershell
$CONNECTION_NAME = gcloud sql instances describe $SQL_INSTANCE --format="value(connectionName)"
$DatabaseUrl = "postgresql://${DB_USER}:${DbPassword}@/${DB_NAME}?host=/cloudsql/${CONNECTION_NAME}"
Send-Exact $DatabaseUrl "gcloud secrets create $SECRET_NAME --replication-policy=user-managed --locations=$REGION --data-file=-"
Remove-Variable DbPassword, DatabaseUrl, bytes
```

**bash:**

```bash
CONNECTION_NAME="$(gcloud sql instances describe "$SQL_INSTANCE" --format='value(connectionName)')"
printf '%s' "postgresql://${DB_USER}:${DB_PASSWORD}@/${DB_NAME}?host=/cloudsql/${CONNECTION_NAME}" \
  | gcloud secrets create "$SECRET_NAME" --replication-policy=user-managed --locations="$REGION" --data-file=-
unset DB_PASSWORD
```

The value goes from memory to `gcloud` through a pipe. No file is written. The secret is stored
in `asia-south1` only.

**Verify:** one version, state `enabled`. Never run `gcloud secrets versions access`: it prints
the password.

```powershell
gcloud secrets versions list $SECRET_NAME --format="value(name,state)"
```

### Rotating the password later

1. Repeat step 4.3 with `gcloud sql users set-password $DB_USER --instance=$SQL_INSTANCE --password="$DbPassword"`
   in place of the `create` command. From this moment new connections with the old password
   fail, so staging is briefly unavailable.
2. Build `$DatabaseUrl` as above and add a version:
   `Send-Exact $DatabaseUrl "gcloud secrets versions add $SECRET_NAME --data-file=-"`.
3. Run the `deploy-staging` workflow (Actions → deploy-staging → Run workflow). New revisions
   and job executions read the `latest` version when they start.
4. Check `/health/ready`, then disable the old version:
   `gcloud secrets versions disable <OLD_VERSION_NUMBER> --secret=$SECRET_NAME`.

## Step 5b: the geocoding key in Secret Manager (since P011a)

*Script: `bootstrap-staging.ps1` does not know this secret yet (follow-up). Do it by hand.*

Place search ([ADR 0018](../adr/0018-search-and-geocoding.md)) needs the key of the geocoding
provider. It is a **server-only** key: it is never put in the app, the repository, a workflow
file, a log or a chat. The deploy mounts it on the API revision as `GEOCODING_API_KEY`, and the
API refuses to start in production without it.

**Do this before merging the pull request that adds search.** Otherwise the next deploy creates
a candidate revision that cannot start; the deploy fails and traffic stays on the old revision.

1. Create the secret. Type or paste the key at the prompt; it is not echoed and not written to
   a file or to the shell history.

   ```powershell
   $GEOCODING_SECRET = "saferoute-staging-geocoding-key"
   $key = Read-Host -AsSecureString "Geocoding API key"
   $plain = [System.Net.NetworkCredential]::new("", $key).Password
   Send-Exact $plain "gcloud secrets create $GEOCODING_SECRET --replication-policy=user-managed --locations=$REGION --data-file=-"
   Remove-Variable plain, key
   ```

   `Send-Exact` is the helper from step 0: it sends the text without a trailing line break or a
   byte-order mark, either of which would become part of the key.

2. Let the API's runtime identity, and only it, read the secret. The deploy identity and the
   migration identity get no access.

   ```powershell
   gcloud secrets add-iam-policy-binding $GEOCODING_SECRET `
     --member="serviceAccount:$RUNTIME_SA" --role=roles/secretmanager.secretAccessor
   ```

3. Check that the key belongs to the provider named in the workflow: the line
   `GEOCODING_PROVIDER: geoapify` near the top of
   [`deploy-staging.yml`](../../.github/workflows/deploy-staging.yml). It is a constant in the
   file, not a GitHub variable. If the key is for the other provider (`locationiq`), change that
   line in a pull request; a key and a provider name that do not match make every search answer
   503.

**Verify:** one version, state `enabled`; one member on the secret. Never run
`gcloud secrets versions access`: it prints the key.

```powershell
gcloud secrets versions list $GEOCODING_SECRET --format="value(name,state)"
gcloud secrets get-iam-policy $GEOCODING_SECRET --format="value(bindings.members)"
```

These are the runbook's commands, written in the style of step 5. If you created the secret
another way (the console, for example), check the three results above instead of repeating them.

### Rotating or replacing the geocoding key

1. Create the new key in the provider's dashboard. Keep the old one active for now.
2. Add it as a new version (same prompt as above, with
   `gcloud secrets versions add $GEOCODING_SECRET --data-file=-` in place of `create`).
3. If the provider changed, change `GEOCODING_PROVIDER` in `deploy-staging.yml` in a pull
   request and merge it: the merge starts the deploy. Add the new secret version just before
   merging, because a revision started in between would pair the new key with the old provider.
4. Otherwise run the `deploy-staging` workflow. A new revision reads the `latest` version when
   it starts.
5. Search once from the app, then disable the old version
   (`gcloud secrets versions disable <OLD_VERSION_NUMBER> --secret=$GEOCODING_SECRET`) and
   delete the old key in the provider's dashboard.

A key that leaked is rotated the same way, without waiting between the steps.

## Step 6: IAM, least privilege

*Script: `-Apply` adds the bindings that are missing. Roles beyond this table are reported as
`WRONG-EXTRA` and left for you to remove.*

Each identity gets only what its job needs.

| Identity | Role | On | Why |
| --- | --- | --- | --- |
| `sa-api-runtime` | `roles/cloudsql.client` | project | Open the Cloud SQL connector socket |
| `sa-api-runtime` | `roles/secretmanager.secretAccessor` | that one secret | Read `DATABASE_URL` at start |
| `sa-migration` | `roles/cloudsql.client` | project | Same, for the migration and admin jobs |
| `sa-migration` | `roles/secretmanager.secretAccessor` | that one secret | Same |
| `sa-deploy` | `roles/run.developer` | project | Deploy revisions, run jobs, move traffic |
| `sa-deploy` | `roles/artifactregistry.writer` | the `saferoute` repository | Push images |
| `sa-deploy` | `roles/iam.serviceAccountUser` | `sa-api-runtime` and `sa-migration` only | Attach those two identities to a service or job |

```powershell
foreach ($sa in @($RUNTIME_SA, $MIGRATION_SA)) {
  gcloud projects add-iam-policy-binding $PROJECT_ID --member="serviceAccount:$sa" `
    --role=roles/cloudsql.client --condition=None
  gcloud secrets add-iam-policy-binding $SECRET_NAME --member="serviceAccount:$sa" `
    --role=roles/secretmanager.secretAccessor
  gcloud iam service-accounts add-iam-policy-binding $sa --member="serviceAccount:$DEPLOY_SA" `
    --role=roles/iam.serviceAccountUser
}

gcloud projects add-iam-policy-binding $PROJECT_ID --member="serviceAccount:$DEPLOY_SA" `
  --role=roles/run.developer --condition=None
gcloud artifacts repositories add-iam-policy-binding $AR_REPOSITORY --location=$REGION `
  --member="serviceAccount:$DEPLOY_SA" --role=roles/artifactregistry.writer
```

**bash:** `for sa in "$RUNTIME_SA" "$MIGRATION_SA"; do … done` around the same three commands.

**Why the deploy identity can't read secrets or data.** It has no role on the secret and no
`cloudsql.client`. It can tell Cloud Run "start this image as `sa-api-runtime` with that
secret mounted", but only the runtime identity can read the value, inside Google's
infrastructure. A compromised workflow could deploy a bad image; it could not print the
database password or open a database connection of its own. `roles/run.developer` also can't
change who may call the service (that needs `run.services.setIamPolicy`), which is why step 8
sets public access once, by hand.

**Verify:**

```powershell
gcloud projects get-iam-policy $PROJECT_ID --flatten="bindings[].members" --filter="bindings.members:serviceAccount:*@${PROJECT_ID}.iam.gserviceaccount.com" --format="table(bindings.role, bindings.members)"
gcloud secrets get-iam-policy $SECRET_NAME --format="table(bindings.role, bindings.members)"
gcloud iam service-accounts get-iam-policy $RUNTIME_SA --format="table(bindings.role, bindings.members)"
gcloud iam service-accounts get-iam-policy $MIGRATION_SA --format="table(bindings.role, bindings.members)"
gcloud artifacts repositories get-iam-policy $AR_REPOSITORY --location=$REGION --format="table(bindings.role, bindings.members)"
```

The output matches the table above and nothing more: in particular `sa-deploy` has no
`secretmanager` or `cloudsql` role, and no account has `roles/owner` or `roles/editor`.

## Step 7: Workload Identity Federation

*Script: checks the pool and never creates it. `-Apply` creates the provider if none exists and
adds the `workloadIdentityUser` binding. A provider whose condition is missing or too permissive
is reported as `WRONG` and not changed.*

GitHub Actions gets a short-lived token from GitHub that says which repository, branch and
environment a job runs in. Google exchanges it for a one-hour access token of
`sa-deploy`, if and only if the token matches the condition below. No key is stored
anywhere.

```powershell
gcloud iam workload-identity-pools create github --location=global `
  --display-name="GitHub Actions" --description="Federation for GitHub Actions workflows"

gcloud iam workload-identity-pools providers create-oidc github-saferoute `
  --location=global --workload-identity-pool=github `
  --display-name="GitHub SafeRoute" `
  --issuer-uri="https://token.actions.githubusercontent.com" `
  --attribute-mapping="google.subject=assertion.sub,attribute.repository=assertion.repository,attribute.repository_owner=assertion.repository_owner,attribute.ref=assertion.ref,attribute.environment=assertion.environment" `
  --attribute-condition="assertion.repository == '$GITHUB_REPO' && assertion.ref == 'refs/heads/main' && assertion.environment == 'staging'"

gcloud iam service-accounts add-iam-policy-binding $DEPLOY_SA `
  --role=roles/iam.workloadIdentityUser `
  --member="principalSet://iam.googleapis.com/projects/$PROJECT_NUMBER/locations/global/workloadIdentityPools/github/attribute.repository/$GITHUB_REPO"
```

The condition accepts a token only when all three are true: it comes from this repository, the
workflow runs on `main`, and the job uses the GitHub environment `staging`. GitHub puts the
`environment` claim in the token only for jobs that declare an environment, so a job without
`environment: staging` is rejected. Pull requests, other branches and forks can't satisfy it.

Store the provider's full name for step 9:

```powershell
$WIF_PROVIDER = gcloud iam workload-identity-pools providers describe github-saferoute `
  --location=global --workload-identity-pool=github --format="value(name)"
```

**Verify:**

```powershell
gcloud iam workload-identity-pools providers describe github-saferoute --location=global --workload-identity-pool=github --format="value(state,attributeCondition)"
gcloud iam service-accounts get-iam-policy $DEPLOY_SA --format="value(bindings.members)"
$WIF_PROVIDER.StartsWith("projects/$PROJECT_NUMBER/locations/global/workloadIdentityPools/github/providers/")
```

- First: `ACTIVE` and the condition with your repository, `refs/heads/main` and `staging`.
- Second: one `principalSet://…/attribute.repository/rahulchy960/SafeRoute` member.
- Third: `True`.

## Step 8: placeholder Cloud Run service

*Script: `-Apply` deploys the placeholder if the service doesn't exist, and adds public access
if it is missing.*

Creates `saferoute-api` once, from Google's public sample image, so that two things exist before
the first real deploy: the "anyone may call this" permission, and a previous revision to roll
back to.

```powershell
gcloud run deploy $API_SERVICE --image=us-docker.pkg.dev/cloudrun/container/hello `
  --region=$REGION --service-account=$RUNTIME_SA --allow-unauthenticated --max-instances=1
```

`--allow-unauthenticated` is intended: the API is public by design and checks Firebase tokens
itself (ADR 0006). Real deploys never pass this flag and can't change this permission.

**Verify:**

```powershell
$SERVICE_URL = gcloud run services describe $API_SERVICE --region=$REGION --format="value(status.url)"
(Invoke-WebRequest -UseBasicParsing $SERVICE_URL).StatusCode
gcloud run services get-iam-policy $API_SERVICE --region=$REGION --format="value(bindings.members)"
```

**bash:** `curl -s -o /dev/null -w '%{http_code}\n' "$SERVICE_URL"`

The status is `200` (Google's sample page), and the members include `allUsers`. Don't paste
the URL anywhere public: it contains the project number.

## Step 9: GitHub environment, secrets and variables

*Script: you create the environment (9.1); `-SetGithubSecrets` does 9.2 and 9.3 and creates the
switch in 9.4 as `false` if it doesn't exist. The names come from the workflow file, so they
can't drift from what the deploy reads.*

1. In the browser: repository → **Settings → Environments → New environment**, name `staging`.
   - **Deployment branches and tags:** "Selected branches and tags" → add the rule `main`.
   - **Required reviewers:** leave off for staging.
2. Environment **secrets**. They are not credentials; they are stored as secrets so that GitHub
   masks them in the public Actions logs.

   ```powershell
   gh secret set GCP_PROJECT_ID --env staging --repo $GITHUB_REPO --body "$PROJECT_ID"
   gh secret set GCP_PROJECT_NUMBER --env staging --repo $GITHUB_REPO --body "$PROJECT_NUMBER"
   gh secret set GCP_WIF_PROVIDER --env staging --repo $GITHUB_REPO --body "$WIF_PROVIDER"
   gh secret set GCP_DEPLOY_SA --env staging --repo $GITHUB_REPO --body "$DEPLOY_SA"
   gh secret set GCP_RUNTIME_SA --env staging --repo $GITHUB_REPO --body "$RUNTIME_SA"
   gh secret set GCP_MIGRATION_SA --env staging --repo $GITHUB_REPO --body "$MIGRATION_SA"
   gh secret set CLOUD_SQL_CONNECTION_NAME --env staging --repo $GITHUB_REPO --body "$CONNECTION_NAME"
   gh secret set FIREBASE_PROJECT_ID --env staging --repo $GITHUB_REPO --body "$FIREBASE_PROJECT_ID"
   ```

   `GCP_PROJECT_NUMBER` is there for masking: the Cloud Run service URL contains it.

3. Environment **variables** (plain configuration):

   ```powershell
   gh variable set GCP_REGION --env staging --repo $GITHUB_REPO --body "$REGION"
   gh variable set AR_REPOSITORY --env staging --repo $GITHUB_REPO --body "$AR_REPOSITORY"
   gh variable set API_SERVICE --env staging --repo $GITHUB_REPO --body "$API_SERVICE"
   gh variable set MIGRATION_JOB --env staging --repo $GITHUB_REPO --body "saferoute-migrate"
   gh variable set API_MIN_INSTANCES --env staging --repo $GITHUB_REPO --body "0"
   gh variable set API_MAX_INSTANCES --env staging --repo $GITHUB_REPO --body "3"
   ```

4. The switch that keeps deploys off until everything is ready. A **repository** variable, not
   an environment one:

   ```powershell
   gh variable set STAGING_DEPLOY_ENABLED --repo $GITHUB_REPO --body "false"
   ```

**Environment variables and repository variables are not interchangeable.** GitHub decides
whether a job runs (its job-level `if:`) *before* the job enters its environment, so that
condition can read **repository** variables only. A switch created on the `staging` environment
is invisible to it: the job is skipped with no error, whatever the value. Everything else the
workflow reads is used *inside* the job, after it has entered the environment, and lives on the
environment so that the branch rule and secret masking apply. In short:

| Value | Where it must be | What happens in the wrong place |
| --- | --- | --- |
| `STAGING_DEPLOY_ENABLED` | Repository variable | On the environment: the deploy job is always skipped |
| The eight identifiers (`GCP_PROJECT_ID`, …) | Environment **secrets** | As variables: `secrets.X` is empty, so the guard step or the sign-in fails, and a variable's value isn't masked in logs |
| The six settings (`GCP_REGION`, …) | Environment **variables** | As secrets: `vars.X` is empty and the guard step fails |

**Verify:**

```powershell
gh api "repos/$GITHUB_REPO/environments/staging" --jq ".deployment_branch_policy, [.protection_rules[].type]"
gh api "repos/$GITHUB_REPO/environments/staging/deployment-branch-policies" --jq ".branch_policies[].name"
gh secret list --env staging --repo $GITHUB_REPO
gh variable list --env staging --repo $GITHUB_REPO
gh variable list --repo $GITHUB_REPO
```

- `{"custom_branch_policies":true,"protected_branches":false}`, then the protection rule types
  (`branch_policy` only, no `required_reviewers`), then `main`.
- Eight secrets (names only; values are never shown), six environment variables, and
  `STAGING_DEPLOY_ENABLED  false`.

## Step 10: pre-flight, then enable deploys

Run these four checks again in one go:

```powershell
gcloud iam service-accounts get-iam-policy $DEPLOY_SA --format="value(bindings.members)"
gcloud secrets versions list $SECRET_NAME --format="value(name,state)"
gcloud sql instances describe $SQL_INSTANCE --format="value(state)"
(Invoke-WebRequest -UseBasicParsing $SERVICE_URL).StatusCode
```

Expected: the `principalSet://…` member, `1  enabled`, `RUNNABLE`, `200`.

The script's `-Verify` runs these checks and more, and compares GitHub with the workflow. Flip
the switch only when it ends with `VERIFY: OK`:

```powershell
gh variable set STAGING_DEPLOY_ENABLED --repo $GITHUB_REPO --body "true"
```

Then start a deploy: Actions → deploy-staging → Run workflow → `main`.

Finally, close this PowerShell window. That discards every variable and restores `gcloud`'s
normal logging.

## Step 11: budget alert and stopping the database ⚠️

**Budget.** In the console: **Billing → Budgets & alerts → Create budget**. Scope it to the
staging project, choose a small monthly amount you are comfortable with, and set alert
thresholds at 50%, 90% and 100%. A budget only sends e-mail; it does not stop spending.

**What costs money while idle:** the Cloud SQL instance (all day, whether or not anyone uses
staging) and its storage. Cloud Run with minimum instances 0 costs nothing while idle; any
minimum above 0 is billed all day.

**Stop the database** when you won't use staging for a few days:

```powershell
gh variable set STAGING_DEPLOY_ENABLED --repo $GITHUB_REPO --body "false"
gcloud sql instances patch $SQL_INSTANCE --activation-policy=NEVER
```

**Start it again:**

```powershell
gcloud sql instances patch $SQL_INSTANCE --activation-policy=ALWAYS
gh variable set STAGING_DEPLOY_ENABLED --repo $GITHUB_REPO --body "true"
```

While it is stopped the machine isn't billed, but storage and the IP address still are. The
API answers `/health` 200 and `/health/ready` 503, and a deploy would fail at the migration
step, which is why the switch is turned off first.

**Verify:** `gcloud sql instances describe $SQL_INSTANCE --format="value(state,settings.activationPolicy)"`
prints `STOPPED  NEVER` after stopping and `RUNNABLE  ALWAYS` after starting.

## Step 12: teardown and undoing a step ⚠️

Only if staging is being rebuilt or abandoned. Deleting the database destroys its data.

| Resource | Command | Caveat |
| --- | --- | --- |
| Deploy switch | `gh variable set STAGING_DEPLOY_ENABLED --repo $GITHUB_REPO --body "false"` | Do this first |
| Cloud Run service | `gcloud run services delete $API_SERVICE --region=$REGION` | |
| Cloud SQL | `gcloud sql instances patch $SQL_INSTANCE --no-deletion-protection`, then `gcloud sql instances delete $SQL_INSTANCE` | Deletion protection must be removed first. The instance name can't be reused for about a week. |
| Secret | `gcloud secrets delete $SECRET_NAME` | All versions are destroyed |
| WIF provider and pool | `gcloud iam workload-identity-pools providers delete github-saferoute --location=global --workload-identity-pool=github`, then `gcloud iam workload-identity-pools delete github --location=global` | Soft-deleted for 30 days; the IDs can't be reused in that time (undelete instead) |
| Service accounts | `gcloud iam service-accounts delete <email>` for each of the three | Remove their project-level role bindings too (`gcloud projects remove-iam-policy-binding`) |
| Artifact Registry | `gcloud artifacts repositories delete $AR_REPOSITORY --location=$REGION` | Deletes every image |
| GitHub environment | Settings → Environments → staging → Delete | Deletes its secrets and variables |

To undo a single step during setup, delete just that resource with the command above and run
the step again.

## Troubleshooting

| Symptom | Likely cause | What to do |
| --- | --- | --- |
| `deploy-staging` fails at "Check configuration and the connection budget" with `The staging environment has no value for …` (the deploy guard lists missing values) | Those environment secrets or variables don't exist, or are stored as the wrong kind (a secret where the workflow reads `vars.X`, or the reverse). The guard runs before any cloud call, so nothing was changed | `.\infra\staging\bootstrap-staging.ps1 -Verify` names each one and where it is; then `-SetGithubSecrets`. If a value can't be filled in yet, something in Google Cloud is missing: `-Apply` first |
| The `deploy-staging` job shows **skipped**, although `STAGING_DEPLOY_ENABLED` is `true` | The variable was created on the `staging` environment. A job-level `if:` only sees repository variables (step 9.4) | `gh variable set STAGING_DEPLOY_ENABLED --repo rahulchy960/SafeRoute --body "true"`, and delete the environment one. Also check the run is on `main` |
| The script says `REFUSED: the active gcloud project looks like PRODUCTION` | The active project's ID contains `prd` or `prod` | `gcloud config set project <staging project ID>` |
| The script shows an item as `UNKNOWN` | A read failed: not signed in, no permission, or an API the check needs is still disabled | `gcloud auth login`; run `-Apply` to enable the APIs, then audit again |
| The script asks for `-DbName` or `-DbUser` | The instance has several application databases or users | Pass the one the API should use |
| `-Apply` stops at the placeholder with a message about `iam.allowedPolicyMemberDomains` | An organization policy forbids public (`allUsers`) access | An organization administrator must allow it for this project; the script doesn't work around it |
| `PERMISSION_DENIED … API has not been used in project … or it is disabled` | An API from step 1 isn't enabled, or was enabled seconds ago | Rerun step 1, wait a minute, retry |
| `Permission 'iam.serviceaccounts.actAs' denied` in a deploy | `sa-deploy` lacks `roles/iam.serviceAccountUser` on the runtime or migration account | Rerun the loop in step 6; check with `get-iam-policy` on that account |
| GitHub Actions auth fails with `The given credential is rejected by the attribute condition` | The job isn't on `main`, has no `environment: staging`, or the repository name in the condition is wrong (it is case-sensitive) | Check the workflow's branch and `environment:`; compare the condition from the step 7 verify with `rahulchy960/SafeRoute` |
| Auth fails with `Permission 'iam.serviceAccounts.getAccessToken' denied` | The `roles/iam.workloadIdentityUser` binding is missing or uses the wrong project number | Rerun the third command of step 7 |
| `/health/ready` 503, logs show a connection error to `/cloudsql/…` | The runtime account lacks `roles/cloudsql.client`, the service has no Cloud SQL connection attached, the Cloud SQL Admin API is off, or the instance is stopped | Step 6 verify; `gcloud sql instances describe … --format="value(state)"`; the deploy must pass `--set-cloudsql-instances` |
| A revision fails to start: `Permission denied on secret` | The identity lacks `secretAccessor` on the secret | Step 6 |
| The API refuses to start: `DATABASE_URL: must be a postgres:// or postgresql:// URL` | The secret value is damaged (extra characters, wrong form) | Add a new version as in "Rotating the password later", using `Send-Exact` |
| Migration job fails at `CREATE EXTENSION postgis` with `permission denied` | The database user isn't a member of `cloudsqlsuperuser` | Users created with `gcloud sql users create` are members by default; recreate the user with step 4.3 rather than with SQL |
| `password authentication failed for user "…"` in the migration job | The password in the URL secret isn't that user's password (for example, the password secret belonged to another user) | Rotate: "Rotating the password later" |
| `gcloud sql instances create` rejects `--tier` | `--edition=enterprise` is missing (Enterprise Plus has no shared-core tiers) | Use the command in step 4 exactly |
| `Resource … already exists` when recreating a pool or provider | It was deleted less than 30 days ago | `gcloud iam workload-identity-pools undelete github --location=global` (and `providers undelete`) |
| `gh secret set` answers `HTTP 404` | The environment `staging` doesn't exist yet | Step 9.1 |

## How this runbook was checked

Claude Code wrote it without access to Google Cloud, so no command here was executed against a
project. Every `gcloud` command and flag was checked against the installed CLI's `--help`
(Google Cloud SDK 587.0.0), and every `gh` command against `gh` 2.102.0. Behaviour that `--help`
can't show was checked against Google's and GitHub's current documentation. The PowerShell
pieces (`Send-Exact`, the password generator, quoting of the long arguments) were run locally
with fake values. The list of what could not be verified is in the P006a prompt log
([`docs/prompt-logs/006a-container-and-runbooks.md`](../prompt-logs/006a-container-and-runbooks.md), section 6).
If a command fails, stop and record the exact error (with IDs masked) before trying variations.

The script (P006c) was never run against Google Cloud or GitHub either, in any mode. Its
commands were checked against `--help` of Google Cloud SDK 587.0.0 and `gh` 2.102.0, and its
behaviour is covered by tests that replace `gcloud` and `gh` with fixtures
([`infra/staging/tests`](../../infra/staging/tests)); they run in CI
([`infra-ci`](../../.github/workflows/infra-ci.yml)). What the fixtures can't prove is the exact
shape of `gcloud`'s real output; the list is in the P006c prompt log
([`docs/prompt-logs/006c-staging-setup-script.md`](../prompt-logs/006c-staging-setup-script.md), section 6).
The first audit shows any mismatch as `UNKNOWN` or an obviously wrong "found" value: report it
(the default output hides identifiers) before running `-Apply`.

**History.** The first version of this runbook (P006a) assumed names that the project didn't
have: the deploy account is `sa-deploy`, not `gcp-deploy-staging`, and the instance is
`saferoute-db`, not `saferoute-staging-db`. The first deploy failed at the guard step for that
reason and because most GitHub values were missing. Names are now parameters of the script.
