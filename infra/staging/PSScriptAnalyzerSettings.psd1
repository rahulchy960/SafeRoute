# SPDX-License-Identifier: AGPL-3.0-only
# PSScriptAnalyzer settings for infra/staging (used by .github/workflows/infra-ci.yml).
# Every default rule is on, at every severity, except the one below.
@{
    ExcludeRules = @(
        # Information-level style rule. The audit is built from many table-like calls
        # (Add-AuditItem <state> <id> <item> <expected> <found> <status>); naming six parameters on
        # each of them makes the table harder to read, not easier.
        'PSAvoidUsingPositionalParameters'
    )
}
