# CveReportAgent Usage

## Overview

`CveReportAgent` reads a CycloneDX SBOM, builds an observed component inventory, collects CVE candidates, applies deterministic applicability rules, and writes customer-facing report artifacts.

Current implementation focus:

- distinguish SBOM-observed components from `license_map`-injected candidates
- merge supplemental grype CycloneDX vulnerability sources when present
- use build artifact path evidence from `buildDirectory`
- classify findings into `affected`, `not_affected_not_present`, `not_affected_unused_code_path`, `not_affected_patched`, `fixed`, `under_investigation`

## AI Usage Policy

AI is used in only three places:

1. Applicability recommendation in Stage 3
2. Evidence interpretation support in Stage 3
3. Final report wording generation in Stage 4

AI is not used for:

- SBOM parsing
- inventory construction
- candidate collection
- file existence checks
- input validation

## Command

Spring Shell command:

```bash
cve-report --sbom <path> --product <name> [options]
```

Main options:

- `--sbom`: Path to CycloneDX SBOM JSON
- `--product`: Product name
- `--version`: Product version
- `--build-dir`: Optional build output directory for artifact evidence scanning
- `--license-map`: Path to `license_map.json`
- `--format`: Output preference. Current implementation writes all artifacts regardless of this value
- `--notes`: Analyst override notes
- `-p`, `--show-prompts`: Show prompts
- `-r`, `--show-responses`: Show LLM responses

## Examples

Basic run:

```bash
cve-report \
  --sbom sbom/temp/merged_sbom.json \
  --product ACV2X-EE \
  --version 5.3.49
```

Run with build evidence:

```bash
cve-report \
  --sbom sbom/temp/merged_sbom.json \
  --product ACV2X-EE \
  --version 5.3.49 \
  --build-dir /workdir/workspace/securityplatform/build/x86-64/ydt3957/Debug
```

Run with analyst override tags:

```bash
cve-report \
  --sbom sbom/temp/merged_sbom.json \
  --product ACV2X-EE \
  --version 5.3.49 \
  --build-dir /workdir/workspace/securityplatform/build/x86-64/ydt3957/Debug \
  --license-map sbom/license_map.json \
  --notes $'patched:CVE-2026-28389\nfixed:CVE-2026-28390\nunused:CVE-2024-39705\naffected:GHSA-hcpj-qp55-gfph'
```

Run deterministic Stage 1 and Stage 2 only without Spring Boot or LLM provider initialization:

```bash
./mvnw -q -DskipTests compile
./mvnw -q -DskipTests org.codehaus.mojo:exec-maven-plugin:3.5.0:java \
  -Dexec.mainClass=io.autocrypt.eeTeam.cowork.cvereportagent.CveReportDeterministicMain \
  -Dexec.args="--sbom sbom/temp/merged_sbom.json --product ACV2X-EE --version 5.3.49 --build-dir /workdir/workspace/securityplatform/build/x86-64/ydt3957/Debug --license-map sbom/license_map.json --until-stage 2"
```

Show available stages and capabilities before running:

```bash
./mvnw -q -DskipTests org.codehaus.mojo:exec-maven-plugin:3.5.0:java \
  -Dexec.mainClass=io.autocrypt.eeTeam.cowork.cvereportagent.CveReportDeterministicMain \
  -Dexec.args="--list-stages"
```

Run Stage 1 only and stop after `inventory.json` generation:

```bash
./mvnw -q -DskipTests org.codehaus.mojo:exec-maven-plugin:3.5.0:java \
  -Dexec.mainClass=io.autocrypt.eeTeam.cowork.cvereportagent.CveReportDeterministicMain \
  -Dexec.args="--sbom sbom/temp/merged_sbom.json --product ACV2X-EE --version 5.3.49 --build-dir /workdir/workspace/securityplatform/build/x86-64/ydt3957/Debug --license-map sbom/license_map.json --until-stage 1"
```

Multiple CVEs can be written on one line with commas:

```text
patched:CVE-2026-28389,CVE-2026-28390
unused:CVE-2024-39705,CVE-2024-39706
```

## Analyst Notes Tags

Supported tags:

- `patched:` Marks a CVE as `not_affected_patched`
- `fixed:` Marks a CVE as `fixed`
- `affected:` Marks a CVE as `affected`
- `notpresent:` Marks a CVE as `not_affected_not_present`
- `unused:` Marks a CVE as `not_affected_unused_code_path`

Notes:

- Tags are case-insensitive on the tag prefix
- CVE ids should match the values stored in candidate data
- Multiple entries can be comma-separated on the same line

## Output Location

Artifacts are written under:

```text
output/cvereportagent/<workspace-slug>/artifacts/
```

The workspace slug is derived from:

```text
<productName>-<productVersion>
```

Generated files:

- `inventory.json`
- `cve_candidates.json`
- `cve_assessment.json`
- `cve_report.md`
- `cve_report.csv`
- `cve_report.json`

## Output Meaning

`inventory.json`

- normalized observed component list
- includes whether the component is considered present in product
- includes evidence for SBOM observation, build artifact matches, or likely `license_map` injection

`cve_candidates.json`

- merged candidate list from SBOM and supplemental grype CycloneDX files
- preserves source provenance in `matchedBy`
- can now be generated standalone together with `inventory.json` via `CveReportDeterministicMain`

`cve_assessment.json`

- final per-CVE applicability status used for reporting
- includes rationale and evidence lines

`cve_report.md`

- customer-facing markdown summary

`cve_report.csv`

- spreadsheet-friendly export for review and customer delivery support

`cve_report.json`

- machine-readable export of the final assessment result

## Current Behavior and Limits

Important note:

- Current implementation now uses AI only in Stage 3 and Stage 4.
- Input validation runs before deterministic processing and before full agent execution.

Implemented today:

- `license_map`-aware inventory classification
- supplemental grype CycloneDX merge from known local files
- build directory path evidence scan
- deterministic applicability rules with analyst override tags

Not yet implemented:

- direct NVD or OSV online lookup
- deep binary inspection such as `ldd`, `readelf`, `nm`
- automatic backport or patch commit verification
- code-path reachability analysis beyond path-name evidence
- final LLM-based rationale refinement prompts

## Recommended Workflow

1. Generate or prepare the final CycloneDX SBOM JSON.
2. Run `cve-report` once without notes.
3. Review `inventory.json` and `cve_assessment.json`.
4. Add analyst overrides for known patched, fixed, unused, or not-present cases.
5. Re-run the command with `--notes`.
6. Deliver `cve_report.md` and `cve_report.csv` as the primary human-readable outputs.
