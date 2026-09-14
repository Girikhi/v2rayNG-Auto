# VLESS Connectivity Lab

This is a self-contained Windows project for comparing a real VLESS-over-TLS configuration across Cloudflare IPs and client-side anti-filtering strategies. Its scripts, manifest, documentation, local ignore rules, portable runtime and redacted result format can be developed independently from the Android app. It does not modify the app or the remote server.

Project metadata lives in `project.json`. No global installation, Android SDK, or files outside this directory are required.

The lab tests meaningful client combinations while preserving the VLESS user ID, port, SNI, Host, path, flow and transport:

- Original configuration
- Fingerprint only
- Fragment only
- Fragment plus browser fingerprints
- Fragment plus Xray's `unsafe` (native Go TLS) fingerprint
- `unsafe` plus two orderings of secure TLS 1.2 cipher suites

The `unsafe` name does **not** mean certificate verification is disabled. The lab preserves the link's `allowInsecure` setting exactly and reports when the supplied link has disabled verification.

## Privacy and isolation

- Paste the VLESS link into the hidden prompt. Do not put it on the command line, in an IP file, or in a GitHub issue.
- The credential is written only to a randomly named directory below the Windows temporary directory while Xray is running.
- Temporary Xray configs and raw logs are deleted after every run.
- Reports omit the VLESS link, user ID, origin hostname, SNI, Host header, path and raw logs.
- `runtime/`, `private/` and `results/` are ignored by Git.
- For the strongest hygiene, rotate the VLESS user ID after testing; ordinary file deletion cannot guarantee physical erasure on SSD storage.

## One-time setup

Open PowerShell in this directory and install the portable Xray version used by the Android app:

```powershell
.\Install-XrayRuntime.ps1
```

It downloads `Xray-windows-64.zip` from the official XTLS GitHub release, verifies its published SHA-256 digest, and extracts it under the ignored `runtime/` directory. It does not require administrator rights and does not install anything system-wide.

## Preparing Cloudflare IPs

Create a plain text file outside Git, or under the ignored `private/` directory, with one individual IPv4 or IPv6 address per line:

```text
104.16.1.2
104.17.3.4
# comments and blank lines are ignored
```

CIDR ranges are deliberately not expanded. Use the exact candidate IPs that should be tested.

## Safe plan check

This validates the link and shows only a redacted test count. It performs no connection attempts:

```powershell
.\Invoke-VlessConnectivityLab.ps1 -CloudflareIpFile .\private\cf-ips.txt -Matrix Focused -PlanOnly
```

The script will securely prompt for the VLESS link.

## Running tests

Start with the focused matrix across all supplied IPs:

```powershell
.\Invoke-VlessConnectivityLab.ps1 -CloudflareIpFile .\private\cf-ips.txt -Matrix Focused -Attempts 2
```

Then run the comprehensive matrix against the IPs that gave promising results:

```powershell
.\Invoke-VlessConnectivityLab.ps1 -CloudflareIp 104.16.1.2,104.17.3.4 -Matrix Comprehensive -Attempts 3
```

To run a targeted diagnostic subset, filter the human-readable method names with a regular expression:

```powershell
.\Invoke-VlessConnectivityLab.ps1 -CloudflareIpFile .\private\cf-ips.txt -Matrix Comprehensive -MethodPattern '^Fragment (fine|balanced|bytewise) \+ unsafe$'
```

Available matrices:

- `Smoke`: original, balanced fragment, unsafe fingerprint, and their basic combination.
- `Focused`: practical browser fingerprints, five fragment patterns, and safe cipher-order tests.
- `Comprehensive`: the cross-product of supported fingerprints and fragment patterns, plus safe cipher-order tests. This can take a long time when an IP is blocked because every failed attempt waits for its timeout.

The original endpoint is tested automatically. Add `-ExcludeOriginalEndpoint` to test only the supplied Cloudflare IPs.

## Results

Each run creates an ignored timestamped directory under `results/` containing:

- `report.html`: readable ranked report
- `report.csv`: spreadsheet-friendly data
- `report.json`: structured results and redacted test metadata

Success means a complete HTTPS request passed through VLESS and returned HTTP 2xx or 3xx. Reported latency is end-to-end HTTP time through the local SOCKS proxy, not ICMP ping. This measures the actual path more accurately than merely checking whether a Cloudflare IP accepts TCP connections.

Fragment and fingerprint changes cannot repair a dead server, incorrect SNI/path, blocked server account, or an endpoint that is unreachable from both sides. Results are specific to the current ISP, connection type and filtering conditions, so record the network used for each run.
