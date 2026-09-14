[CmdletBinding()]
param(
    [string]$VlessUri,
    [string[]]$CloudflareIp = @(),
    [string]$CloudflareIpFile,
    [ValidateSet("Smoke", "Focused", "Comprehensive")]
    [string]$Matrix = "Focused",
    [string]$MethodPattern,
    [ValidateRange(1, 5)]
    [int]$Attempts = 2,
    [ValidateRange(3, 60)]
    [int]$TimeoutSeconds = 12,
    [string]$ProbeUrl = "https://cp.cloudflare.com/generate_204",
    [string]$XrayPath,
    [switch]$ExcludeOriginalEndpoint,
    [switch]$PlanOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function ConvertFrom-UrlComponent {
    param([AllowEmptyString()] [string]$Value)
    return [System.Uri]::UnescapeDataString($Value.Replace('+', ' '))
}

function Get-QueryParameters {
    param([Parameter(Mandatory)] [uri]$Uri)

    $values = [System.Collections.Generic.Dictionary[string, string]]::new(
        [System.StringComparer]::OrdinalIgnoreCase
    )
    $query = $Uri.Query.TrimStart('?')
    if ([string]::IsNullOrWhiteSpace($query)) {
        return $values
    }
    foreach ($part in $query.Split('&', [System.StringSplitOptions]::RemoveEmptyEntries)) {
        $pair = $part.Split('=', 2)
        $key = ConvertFrom-UrlComponent $pair[0]
        $value = if ($pair.Count -gt 1) { ConvertFrom-UrlComponent $pair[1] } else { "" }
        $values[$key] = $value
    }
    return $values
}

function Get-FirstQueryValue {
    param(
        [Parameter(Mandatory)] $Query,
        [Parameter(Mandatory)] [string[]]$Names
    )
    foreach ($name in $Names) {
        if ($Query.ContainsKey($name) -and -not [string]::IsNullOrWhiteSpace($Query[$name])) {
            return $Query[$name]
        }
    }
    return $null
}

function Test-IsIpAddress {
    param([string]$Value)
    $parsed = $null
    return [System.Net.IPAddress]::TryParse($Value, [ref]$parsed)
}

function Read-VlessConfiguration {
    param([Parameter(Mandatory)] [string]$Link)

    $trimmed = $Link.Trim()
    try {
        $uri = [uri]$trimmed
    }
    catch {
        throw "The supplied VLESS link is not a valid URI"
    }
    if ($uri.Scheme -ne "vless") {
        throw "This lab currently accepts VLESS links only"
    }
    if ([string]::IsNullOrWhiteSpace($uri.UserInfo) -or [string]::IsNullOrWhiteSpace($uri.Host)) {
        throw "The VLESS link is missing its user ID or server address"
    }
    if ($uri.Port -lt 1 -or $uri.Port -gt 65535) {
        throw "The VLESS link must contain an explicit valid port"
    }

    $query = Get-QueryParameters $uri
    $security = (Get-FirstQueryValue $query @("security"))
    if ([string]::IsNullOrWhiteSpace($security)) { $security = "none" }
    $network = (Get-FirstQueryValue $query @("type", "network"))
    if ([string]::IsNullOrWhiteSpace($network)) { $network = "tcp" }
    $network = $network.ToLowerInvariant()
    if ($network -eq "raw") { $network = "tcp" }
    if ($network -eq "websocket") { $network = "ws" }

    $sni = Get-FirstQueryValue $query @("sni", "serverName", "servername")
    $hostHeader = Get-FirstQueryValue $query @("host")
    if ([string]::IsNullOrWhiteSpace($sni)) {
        if (-not [string]::IsNullOrWhiteSpace($hostHeader)) {
            $sni = $hostHeader.Split(',')[0].Trim()
        }
        elseif (-not (Test-IsIpAddress $uri.Host)) {
            $sni = $uri.Host
        }
    }

    $allowInsecureValue = Get-FirstQueryValue $query @("insecure", "allowInsecure", "allow_insecure")
    $allowInsecure = $allowInsecureValue -in @("1", "true", "yes")
    $alpnValue = Get-FirstQueryValue $query @("alpn")
    $alpn = if ([string]::IsNullOrWhiteSpace($alpnValue)) {
        @()
    }
    else {
        @($alpnValue.Split(',') | ForEach-Object { $_.Trim() } | Where-Object { $_ })
    }

    return [pscustomobject]@{
        UserId = ConvertFrom-UrlComponent $uri.UserInfo
        OriginalAddress = $uri.Host
        Port = $uri.Port
        Encryption = (Get-FirstQueryValue $query @("encryption")) ?? "none"
        Flow = Get-FirstQueryValue $query @("flow")
        Network = $network
        Security = $security.ToLowerInvariant()
        Sni = $sni
        Alpn = $alpn
        Fingerprint = Get-FirstQueryValue $query @("fp", "fingerprint")
        CipherSuites = Get-FirstQueryValue $query @("cs", "cipherSuites")
        AllowInsecure = $allowInsecure
        Host = $hostHeader
        Path = (Get-FirstQueryValue $query @("path")) ?? "/"
        HeaderType = (Get-FirstQueryValue $query @("headerType")) ?? "none"
        ServiceName = (Get-FirstQueryValue $query @("serviceName")) ?? ""
        Authority = (Get-FirstQueryValue $query @("authority")) ?? ""
        Mode = Get-FirstQueryValue $query @("mode")
        XhttpExtra = Get-FirstQueryValue $query @("extra")
        SourceFinalMask = Get-FirstQueryValue $query @("fm")
    }
}

function New-LabMethod {
    param(
        [Parameter(Mandatory)] [string]$Name,
        [AllowNull()] [string]$Fingerprint,
        [AllowNull()] $Fragment,
        [AllowNull()] [string]$CipherSuites,
        [string]$CipherProfile = "default",
        [switch]$UseSourceSettings
    )
    return [pscustomobject]@{
        Name = $Name
        Fingerprint = $Fingerprint
        Fragment = $Fragment
        CipherSuites = $CipherSuites
        CipherProfile = $CipherProfile
        UseSourceSettings = [bool]$UseSourceSettings
    }
}

function Get-LabMethods {
    param(
        [Parameter(Mandatory)] [string]$Mode,
        [AllowNull()] [string]$SourceFingerprint
    )

    $fragments = [ordered]@{
        fine = [pscustomobject]@{ packets = "tlshello"; length = "10-20"; delay = "1-5" }
        balanced = [pscustomobject]@{ packets = "tlshello"; length = "50-100"; delay = "10-20" }
        gentle = [pscustomobject]@{ packets = "tlshello"; length = "100-200"; delay = "1-3" }
        bytewise = [pscustomobject]@{ packets = "tlshello"; length = "1-5"; delay = "1-3" }
        first_bytes = [pscustomobject]@{ packets = "1-3"; length = "1-5"; delay = "1-3" }
    }
    $secureAesFirst = @(
        "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
        "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
        "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
        "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
        "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
        "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256"
    ) -join ':'
    $secureChachaFirst = @(
        "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
        "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256",
        "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
        "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
        "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
        "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384"
    ) -join ':'

    $methods = [System.Collections.Generic.List[object]]::new()
    $methods.Add((New-LabMethod -Name "Original" -Fingerprint $SourceFingerprint -Fragment $null `
        -CipherSuites $null -UseSourceSettings))
    $methods.Add((New-LabMethod -Name "Fragment balanced" -Fingerprint $SourceFingerprint `
        -Fragment $fragments.balanced -CipherSuites $null))
    $methods.Add((New-LabMethod -Name "Unsafe fingerprint" -Fingerprint "unsafe" -Fragment $null `
        -CipherSuites $null))
    $methods.Add((New-LabMethod -Name "Fragment balanced + unsafe" -Fingerprint "unsafe" `
        -Fragment $fragments.balanced -CipherSuites $null))

    if ($Mode -in @("Focused", "Comprehensive")) {
        $methods.Add((New-LabMethod -Name "Fragment balanced + core-default" -Fingerprint "" `
            -Fragment $fragments.balanced -CipherSuites $null))
        foreach ($fingerprint in @("chrome", "randomized")) {
            $methods.Add((New-LabMethod -Name "Fingerprint only: $fingerprint" -Fingerprint $fingerprint `
                -Fragment $null -CipherSuites $null))
        }
        foreach ($fingerprint in @("chrome", "firefox", "safari", "ios", "android", "edge", "randomized")) {
            $methods.Add((New-LabMethod -Name "Fragment balanced + $fingerprint" -Fingerprint $fingerprint `
                -Fragment $fragments.balanced -CipherSuites $null))
        }
        foreach ($entry in $fragments.GetEnumerator()) {
            if ($entry.Key -ne "balanced") {
                $methods.Add((New-LabMethod -Name "Fragment $($entry.Key) + unsafe" -Fingerprint "unsafe" `
                    -Fragment $entry.Value -CipherSuites $null))
            }
        }
        $methods.Add((New-LabMethod -Name "Fragment balanced + unsafe + AES-first" -Fingerprint "unsafe" `
            -Fragment $fragments.balanced -CipherSuites $secureAesFirst -CipherProfile "secure-aes-first"))
        $methods.Add((New-LabMethod -Name "Fragment balanced + unsafe + ChaCha-first" -Fingerprint "unsafe" `
            -Fragment $fragments.balanced -CipherSuites $secureChachaFirst -CipherProfile "secure-chacha-first"))
        $methods.Add((New-LabMethod -Name "Unsafe fingerprint + AES-first" -Fingerprint "unsafe" `
            -Fragment $null -CipherSuites $secureAesFirst -CipherProfile "secure-aes-first"))
        $methods.Add((New-LabMethod -Name "Unsafe fingerprint + ChaCha-first" -Fingerprint "unsafe" `
            -Fragment $null -CipherSuites $secureChachaFirst -CipherProfile "secure-chacha-first"))
    }

    if ($Mode -eq "Comprehensive") {
        $allFingerprints = @("", $SourceFingerprint, "chrome", "firefox", "safari", "ios", "android", "edge", "360", "qq", "random", "randomized", "unsafe") |
            ForEach-Object { if ([string]::IsNullOrWhiteSpace($_)) { "" } else { $_ } } |
            Select-Object -Unique
        foreach ($fingerprint in $allFingerprints) {
            $label = if ([string]::IsNullOrWhiteSpace($fingerprint)) { "core-default" } else { $fingerprint }
            $methods.Add((New-LabMethod -Name "Fingerprint only: $label" -Fingerprint $fingerprint `
                -Fragment $null -CipherSuites $null))
            foreach ($entry in $fragments.GetEnumerator()) {
                $methods.Add((New-LabMethod -Name "Fragment $($entry.Key) + $label" -Fingerprint $fingerprint `
                    -Fragment $entry.Value -CipherSuites $null))
            }
        }
        foreach ($entry in $fragments.GetEnumerator()) {
            $methods.Add((New-LabMethod -Name "Fragment $($entry.Key) + unsafe + AES-first" -Fingerprint "unsafe" `
                -Fragment $entry.Value -CipherSuites $secureAesFirst -CipherProfile "secure-aes-first"))
            $methods.Add((New-LabMethod -Name "Fragment $($entry.Key) + unsafe + ChaCha-first" -Fingerprint "unsafe" `
                -Fragment $entry.Value -CipherSuites $secureChachaFirst -CipherProfile "secure-chacha-first"))
        }
    }

    return @($methods | Sort-Object Name -Unique)
}

function Get-CandidateEndpoints {
    param(
        [Parameter(Mandatory)] $Configuration,
        [string[]]$Addresses,
        [string]$AddressFile,
        [switch]$WithoutOriginal
    )

    $allAddresses = [System.Collections.Generic.List[string]]::new()
    if (-not $WithoutOriginal) {
        $allAddresses.Add($Configuration.OriginalAddress)
    }
    foreach ($address in $Addresses) {
        if (-not [string]::IsNullOrWhiteSpace($address)) { $allAddresses.Add($address.Trim()) }
    }
    if (-not [string]::IsNullOrWhiteSpace($AddressFile)) {
        if (-not (Test-Path -LiteralPath $AddressFile -PathType Leaf)) {
            throw "Cloudflare IP file not found: $AddressFile"
        }
        foreach ($line in Get-Content -LiteralPath $AddressFile) {
            $value = $line.Trim()
            if ($value -and -not $value.StartsWith('#')) { $allAddresses.Add($value) }
        }
    }

    $endpoints = [System.Collections.Generic.List[object]]::new()
    $seen = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)
    foreach ($address in $allAddresses) {
        $isOriginal = $address.Equals($Configuration.OriginalAddress, [System.StringComparison]::OrdinalIgnoreCase)
        if (-not $isOriginal -and -not (Test-IsIpAddress $address)) {
            throw "Cloudflare candidate '$address' is not an individual IPv4 or IPv6 address"
        }
        if ($seen.Add($address)) {
            $endpoints.Add([pscustomobject]@{
                Address = $address
                Label = if ($isOriginal) { "Original endpoint" } else { $address }
            })
        }
    }
    if ($endpoints.Count -eq 0) {
        throw "No endpoint remains to test"
    }
    if ($endpoints.Count -gt 64) {
        throw "At most 64 individual Cloudflare IPs can be tested in one run"
    }
    return @($endpoints)
}

function Add-IfPresent {
    param(
        [Parameter(Mandatory)] $Dictionary,
        [Parameter(Mandatory)] [string]$Name,
        $Value
    )
    if ($null -ne $Value -and -not ($Value -is [string] -and [string]::IsNullOrWhiteSpace($Value))) {
        $Dictionary[$Name] = $Value
    }
}

function New-XrayConfiguration {
    param(
        [Parameter(Mandatory)] $Source,
        [Parameter(Mandatory)] [string]$Address,
        [Parameter(Mandatory)] $Method,
        [Parameter(Mandatory)] [int]$SocksPort,
        [Parameter(Mandatory)] [string]$ErrorLogPath
    )

    $user = [ordered]@{ id = $Source.UserId; encryption = $Source.Encryption }
    Add-IfPresent $user "flow" $Source.Flow
    $outboundSettings = [ordered]@{
        vnext = @([ordered]@{
            address = $Address
            port = $Source.Port
            users = @($user)
        })
    }

    $tlsSettings = [ordered]@{
        allowInsecure = [bool]$Source.AllowInsecure
    }
    Add-IfPresent $tlsSettings "serverName" $Source.Sni
    if (@($Source.Alpn).Count -gt 0) { $tlsSettings["alpn"] = @($Source.Alpn) }
    $effectiveFingerprint = if ($Method.UseSourceSettings) { $Source.Fingerprint } else { $Method.Fingerprint }
    $effectiveCipherSuites = if ($Method.UseSourceSettings) { $Source.CipherSuites } else { $Method.CipherSuites }
    Add-IfPresent $tlsSettings "fingerprint" $effectiveFingerprint
    Add-IfPresent $tlsSettings "cipherSuites" $effectiveCipherSuites

    $streamSettings = [ordered]@{
        network = $Source.Network
        security = "tls"
        tlsSettings = $tlsSettings
    }
    switch ($Source.Network) {
        "tcp" {
            $header = [ordered]@{ type = $Source.HeaderType }
            if ($Source.HeaderType -eq "http") {
                $request = [ordered]@{ path = @($Source.Path) }
                if (-not [string]::IsNullOrWhiteSpace($Source.Host)) {
                    $request["headers"] = [ordered]@{ Host = @($Source.Host.Split(',') | ForEach-Object { $_.Trim() }) }
                }
                $header["request"] = $request
            }
            $streamSettings["tcpSettings"] = [ordered]@{ header = $header }
        }
        "ws" {
            $ws = [ordered]@{ path = $Source.Path }
            Add-IfPresent $ws "host" $Source.Host
            $streamSettings["wsSettings"] = $ws
        }
        "httpupgrade" {
            $upgrade = [ordered]@{ path = $Source.Path }
            Add-IfPresent $upgrade "host" $Source.Host
            $streamSettings["httpupgradeSettings"] = $upgrade
        }
        "xhttp" {
            $xhttp = [ordered]@{ path = $Source.Path }
            Add-IfPresent $xhttp "host" $Source.Host
            Add-IfPresent $xhttp "mode" $Source.Mode
            if (-not [string]::IsNullOrWhiteSpace($Source.XhttpExtra)) {
                try { $xhttp["extra"] = $Source.XhttpExtra | ConvertFrom-Json -AsHashtable }
                catch { throw "The VLESS xhttp extra parameter is not valid JSON" }
            }
            $streamSettings["xhttpSettings"] = $xhttp
        }
        { $_ -in @("http", "h2") } {
            $streamSettings["network"] = "h2"
            $http = [ordered]@{ path = $Source.Path }
            if (-not [string]::IsNullOrWhiteSpace($Source.Host)) {
                $http["host"] = @($Source.Host.Split(',') | ForEach-Object { $_.Trim() })
            }
            $streamSettings["httpSettings"] = $http
        }
        "grpc" {
            $streamSettings["grpcSettings"] = [ordered]@{
                serviceName = $Source.ServiceName
                authority = $Source.Authority
                multiMode = ($Source.Mode -eq "multi")
                idle_timeout = 60
                health_check_timeout = 20
            }
        }
        default { throw "Unsupported TCP-based VLESS transport: $($Source.Network)" }
    }

    if ($Method.UseSourceSettings -and -not [string]::IsNullOrWhiteSpace($Source.SourceFinalMask)) {
        try { $streamSettings["finalmask"] = $Source.SourceFinalMask | ConvertFrom-Json -AsHashtable }
        catch { throw "The source VLESS finalMask parameter is not valid JSON" }
    }
    elseif ($null -ne $Method.Fragment) {
        $streamSettings["finalmask"] = [ordered]@{
            tcp = @([ordered]@{
                type = "fragment"
                settings = [ordered]@{
                    packets = $Method.Fragment.packets
                    length = $Method.Fragment.length
                    delay = $Method.Fragment.delay
                }
            })
        }
    }

    return [ordered]@{
        log = [ordered]@{ loglevel = "warning"; error = $ErrorLogPath }
        inbounds = @([ordered]@{
            listen = "127.0.0.1"
            port = $SocksPort
            protocol = "socks"
            settings = [ordered]@{ auth = "noauth"; udp = $true }
            sniffing = [ordered]@{ enabled = $true; destOverride = @("http", "tls") }
        })
        outbounds = @(
            [ordered]@{
                tag = "proxy"
                protocol = "vless"
                settings = $outboundSettings
                streamSettings = $streamSettings
                mux = [ordered]@{ enabled = $false }
            },
            [ordered]@{ tag = "direct"; protocol = "freedom" }
        )
    }
}

function Get-FreeTcpPort {
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
    try {
        $listener.Start()
        return ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port
    }
    finally {
        $listener.Stop()
    }
}

function Wait-LocalPort {
    param([int]$Port, [System.Diagnostics.Process]$Process, [int]$TimeoutMilliseconds = 4000)
    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    while ($watch.ElapsedMilliseconds -lt $TimeoutMilliseconds) {
        if ($Process.HasExited) { return $false }
        $client = [System.Net.Sockets.TcpClient]::new()
        try {
            $pending = $client.ConnectAsync("127.0.0.1", $Port)
            if ($pending.Wait(150) -and $client.Connected) { return $true }
        }
        catch { }
        finally { $client.Dispose() }
        Start-Sleep -Milliseconds 100
    }
    return $false
}

function Get-FailureCategory {
    param([int]$CurlExitCode, [string]$LogText)
    if ($LogText -match 'failed to load config|unknown.+fingerprint|failed to build config') { return "config_error" }
    if ($LogText -match 'connection refused') { return "refused" }
    if ($LogText -match 'reset by peer|unexpected EOF') { return "reset" }
    if ($LogText -match 'no such host|dns') { return "dns_error" }
    if ($LogText -match 'certificate|tls.+handshake|handshake.+tls') { return "tls_error" }
    if ($LogText -match 'i/o timeout|context deadline|operation timed out') { return "timeout" }
    if ($CurlExitCode -eq 28) { return "timeout" }
    if ($CurlExitCode -eq 35 -or $CurlExitCode -eq 60) { return "tls_error" }
    if ($CurlExitCode -eq 97) { return "proxy_error" }
    return "curl_$CurlExitCode"
}

function Invoke-DirectBaseline {
    param([string]$Url, [int]$Timeout)
    $output = & curl.exe --silent --show-error --location --connect-timeout $Timeout --max-time $Timeout `
        --output NUL --write-out '%{http_code}|%{time_total}' -- $Url 2>$null
    $exitCode = $LASTEXITCODE
    $parts = "$output".Trim().Split('|')
    return [pscustomobject]@{
        Success = ($exitCode -eq 0 -and $parts.Count -ge 2 -and [int]$parts[0] -ge 200 -and [int]$parts[0] -lt 400)
        HttpCode = if ($parts.Count -ge 1) { $parts[0] } else { "000" }
        TotalMs = if ($parts.Count -ge 2) { [math]::Round(([double]::Parse($parts[1], [Globalization.CultureInfo]::InvariantCulture) * 1000), 1) } else { $null }
    }
}

function Invoke-MethodTest {
    param(
        [Parameter(Mandatory)] [string]$Executable,
        [Parameter(Mandatory)] $Source,
        [Parameter(Mandatory)] $Endpoint,
        [Parameter(Mandatory)] $Method,
        [Parameter(Mandatory)] [int]$AttemptCount,
        [Parameter(Mandatory)] [int]$Timeout,
        [Parameter(Mandatory)] [string]$Url,
        [Parameter(Mandatory)] [string]$WorkingDirectory
    )

    $port = Get-FreeTcpPort
    $testId = [guid]::NewGuid().ToString("N")
    $configPath = Join-Path $WorkingDirectory "$testId.json"
    $errorLogPath = Join-Path $WorkingDirectory "$testId-error.log"
    $stdoutPath = Join-Path $WorkingDirectory "$testId-stdout.log"
    $stderrPath = Join-Path $WorkingDirectory "$testId-stderr.log"
    $config = New-XrayConfiguration -Source $Source -Address $Endpoint.Address -Method $Method `
        -SocksPort $port -ErrorLogPath $errorLogPath
    $config | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $configPath -Encoding utf8

    $process = $null
    $attemptRows = [System.Collections.Generic.List[object]]::new()
    try {
        $process = Start-Process -FilePath $Executable -ArgumentList @("run", "-config", $configPath) `
            -PassThru -WindowStyle Hidden -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath
        $ready = Wait-LocalPort -Port $port -Process $process
        if (-not $ready) {
            $logText = @($errorLogPath, $stderrPath | Where-Object { Test-Path -LiteralPath $_ } |
                ForEach-Object { Get-Content -LiteralPath $_ -Raw }) -join "`n"
            $attemptRows.Add([pscustomobject]@{
                Success = $false; HttpCode = "000"; TotalMs = $null
                Failure = Get-FailureCategory -CurlExitCode 97 -LogText $logText
            })
        }
        else {
            for ($attempt = 1; $attempt -le $AttemptCount; $attempt++) {
                $curlOutput = & curl.exe --silent --show-error --location `
                    --proxy "socks5h://127.0.0.1:$port" --connect-timeout $Timeout --max-time $Timeout `
                    --output NUL --write-out '%{http_code}|%{time_total}' -- $Url 2>$null
                $curlExitCode = $LASTEXITCODE
                $parts = "$curlOutput".Trim().Split('|')
                $httpCode = if ($parts.Count -ge 1 -and $parts[0] -match '^\d{3}$') { $parts[0] } else { "000" }
                $totalMs = $null
                if ($parts.Count -ge 2) {
                    $seconds = 0.0
                    if ([double]::TryParse($parts[1], [Globalization.NumberStyles]::Float,
                        [Globalization.CultureInfo]::InvariantCulture, [ref]$seconds)) {
                        $totalMs = [math]::Round($seconds * 1000, 1)
                    }
                }
                $success = $curlExitCode -eq 0 -and [int]$httpCode -ge 200 -and [int]$httpCode -lt 400
                $logText = if (Test-Path -LiteralPath $errorLogPath) {
                    Get-Content -LiteralPath $errorLogPath -Raw
                } else { "" }
                $attemptRows.Add([pscustomobject]@{
                    Success = $success
                    HttpCode = $httpCode
                    TotalMs = $totalMs
                    Failure = if ($success) { "" } else { Get-FailureCategory -CurlExitCode $curlExitCode -LogText $logText }
                })
            }
        }
    }
    finally {
        if ($null -ne $process -and -not $process.HasExited) {
            Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
            $process.WaitForExit(3000) | Out-Null
        }
        foreach ($path in @($configPath, $errorLogPath, $stdoutPath, $stderrPath)) {
            if (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path -Force }
        }
    }

    $successRows = @($attemptRows | Where-Object Success)
    $median = $null
    if ($successRows.Count -gt 0) {
        $sortedTimes = @($successRows.TotalMs | Where-Object { $null -ne $_ } | Sort-Object)
        if ($sortedTimes.Count -gt 0) {
            $middle = [math]::Floor($sortedTimes.Count / 2)
            $median = if ($sortedTimes.Count % 2 -eq 1) {
                $sortedTimes[$middle]
            } else {
                [math]::Round(($sortedTimes[$middle - 1] + $sortedTimes[$middle]) / 2, 1)
            }
        }
    }
    $failureGroup = $attemptRows | Where-Object { -not $_.Success } |
        Group-Object Failure | Sort-Object Count -Descending | Select-Object -First 1
    $dominantFailure = if ($null -eq $failureGroup) { "" } else { $failureGroup.Name }

    return [pscustomobject]@{
        Endpoint = $Endpoint.Label
        Method = $Method.Name
        Fingerprint = if ([string]::IsNullOrWhiteSpace($Method.Fingerprint)) { "source/default" } else { $Method.Fingerprint }
        FragmentPackets = if ($null -eq $Method.Fragment) { "" } else { $Method.Fragment.packets }
        FragmentLength = if ($null -eq $Method.Fragment) { "" } else { $Method.Fragment.length }
        FragmentDelay = if ($null -eq $Method.Fragment) { "" } else { $Method.Fragment.delay }
        CipherProfile = if ($Method.UseSourceSettings) { "source/default" } else { $Method.CipherProfile }
        Attempts = $attemptRows.Count
        Successes = $successRows.Count
        SuccessRate = [math]::Round(($successRows.Count * 100.0) / [math]::Max(1, $attemptRows.Count), 1)
        MedianMs = $median
        Failure = if ($successRows.Count -eq $attemptRows.Count) { "" } else { $dominantFailure }
        HttpCodes = (@($attemptRows.HttpCode | Select-Object -Unique) -join ',')
    }
}

function ConvertTo-HtmlEncoded {
    param($Value)
    return [System.Net.WebUtility]::HtmlEncode("$Value")
}

function Export-LabReport {
    param(
        [Parameter(Mandatory)] [object[]]$Rows,
        [Parameter(Mandatory)] $Metadata,
        [Parameter(Mandatory)] [string]$OutputDirectory
    )

    New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
    $jsonPath = Join-Path $OutputDirectory "report.json"
    $csvPath = Join-Path $OutputDirectory "report.csv"
    $htmlPath = Join-Path $OutputDirectory "report.html"
    [ordered]@{ metadata = $Metadata; results = $Rows } | ConvertTo-Json -Depth 10 |
        Set-Content -LiteralPath $jsonPath -Encoding utf8
    $Rows | Export-Csv -LiteralPath $csvPath -NoTypeInformation -Encoding utf8

    $tableRows = foreach ($row in ($Rows | Sort-Object Endpoint, @{ Expression = "SuccessRate"; Descending = $true }, MedianMs)) {
        $class = if ($row.SuccessRate -eq 100) { "pass" } elseif ($row.SuccessRate -gt 0) { "partial" } else { "fail" }
        "<tr class='$class'><td>$(ConvertTo-HtmlEncoded $row.Endpoint)</td><td>$(ConvertTo-HtmlEncoded $row.Method)</td>" +
        "<td>$($row.Successes)/$($row.Attempts)</td><td>$(ConvertTo-HtmlEncoded $row.MedianMs)</td>" +
        "<td>$(ConvertTo-HtmlEncoded $row.Fingerprint)</td><td>$(ConvertTo-HtmlEncoded $row.FragmentLength)</td>" +
        "<td>$(ConvertTo-HtmlEncoded $row.FragmentDelay)</td><td>$(ConvertTo-HtmlEncoded $row.CipherProfile)</td>" +
        "<td>$(ConvertTo-HtmlEncoded $row.Failure)</td></tr>"
    }
    $successfulMethods = @($Rows | Where-Object { $_.Successes -gt 0 }).Count
    $html = @"
<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>VLESS connectivity lab report</title>
<style>
body{font-family:Segoe UI,Arial,sans-serif;background:#f4f7fb;color:#172033;margin:0;padding:28px}main{max-width:1300px;margin:auto}
h1{margin:0 0 6px}.meta{color:#56647a;margin-bottom:20px}.cards{display:flex;gap:12px;flex-wrap:wrap;margin:18px 0}
.card{background:white;border:1px solid #dce3ec;border-radius:12px;padding:14px 18px;min-width:160px;box-shadow:0 3px 12px #1822380d}
.value{font-size:24px;font-weight:700}table{width:100%;border-collapse:collapse;background:white;border-radius:12px;overflow:hidden}
th,td{padding:10px 12px;border-bottom:1px solid #e7ebf1;text-align:left;font-size:13px}th{background:#eaf0f8;position:sticky;top:0}
tr.pass td:first-child{border-left:4px solid #24a46d}tr.partial td:first-child{border-left:4px solid #e0a12a}tr.fail td:first-child{border-left:4px solid #d95656}
.note{background:#fff8df;border:1px solid #ead99a;padding:12px;border-radius:10px;margin:16px 0}
</style></head><body><main><h1>VLESS connectivity lab</h1>
<div class="meta">Generated $(ConvertTo-HtmlEncoded $Metadata.generatedAtLocal) · Xray $(ConvertTo-HtmlEncoded $Metadata.xrayVersion) · Matrix $(ConvertTo-HtmlEncoded $Metadata.matrix)</div>
<div class="cards"><div class="card"><div class="value">$($Metadata.endpointCount)</div>Endpoints</div><div class="card"><div class="value">$($Metadata.methodCount)</div>Methods</div><div class="card"><div class="value">$successfulMethods</div>Successful combinations</div><div class="card"><div class="value">$(if($Metadata.directBaselineSuccess){'Online'}else{'Failed'})</div>Direct baseline</div></div>
<div class="note">Credentials, origin hostname, SNI, path and raw Xray logs are intentionally excluded. Latency is end-to-end HTTP time through the local SOCKS proxy, not ICMP ping.</div>
<table><thead><tr><th>Endpoint</th><th>Method</th><th>Success</th><th>Median ms</th><th>Fingerprint</th><th>Fragment length</th><th>Delay</th><th>Ciphers</th><th>Failure</th></tr></thead>
<tbody>$($tableRows -join "`n")</tbody></table></main></body></html>
"@
    Set-Content -LiteralPath $htmlPath -Value $html -Encoding utf8
    return [pscustomobject]@{ Json = $jsonPath; Csv = $csvPath; Html = $htmlPath }
}

if ([string]::IsNullOrWhiteSpace($VlessUri)) {
    $VlessUri = Read-Host -MaskInput "Paste the VLESS link (input is hidden and is not saved)"
}
$source = Read-VlessConfiguration $VlessUri
if ($source.Security -ne "tls") {
    throw "Fragment + fingerprint testing is limited to security=tls; the supplied link uses '$($source.Security)'"
}
$supportedNetworks = @("tcp", "ws", "grpc", "httpupgrade", "xhttp", "http", "h2")
if ($source.Network -notin $supportedNetworks) {
    throw "Fragment + fingerprint testing requires a TCP-based transport; '$($source.Network)' is unsupported"
}
if ($source.Alpn | Where-Object { $_.StartsWith("h3", [System.StringComparison]::OrdinalIgnoreCase) }) {
    throw "The supplied link requests HTTP/3 ALPN, which is not compatible with this TCP fragmentation matrix"
}

$methods = @(Get-LabMethods -Mode $Matrix -SourceFingerprint $source.Fingerprint)
if (-not [string]::IsNullOrWhiteSpace($MethodPattern)) {
    try { $methods = @($methods | Where-Object { $_.Name -match $MethodPattern }) }
    catch { throw "MethodPattern is not a valid regular expression" }
    if ($methods.Count -eq 0) { throw "MethodPattern did not match any methods in the $Matrix matrix" }
}
$endpoints = @(Get-CandidateEndpoints -Configuration $source -Addresses $CloudflareIp `
    -AddressFile $CloudflareIpFile -WithoutOriginal:$ExcludeOriginalEndpoint)
$testCount = $methods.Count * $endpoints.Count
if ($testCount -gt 5000) { throw "The requested matrix contains $testCount combinations; the safety limit is 5000" }

Write-Host "Validated redacted test plan:"
Write-Host "  Transport: $($source.Network) + TLS"
Write-Host "  Endpoints: $($endpoints.Count)"
Write-Host "  Methods:   $($methods.Count) ($Matrix matrix)"
Write-Host "  Attempts:  $Attempts per method"
Write-Host "  Total:     $testCount method/endpoint combinations"
if ($source.AllowInsecure) {
    Write-Warning "The supplied link disables certificate verification. The lab will preserve that setting for an exact comparison."
}
if ($PlanOnly) {
    Write-Host "Plan-only mode: no network tests were run and the credential was not written to disk."
    return
}

if ([string]::IsNullOrWhiteSpace($XrayPath)) {
    $XrayPath = Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot "runtime") -Filter "xray.exe" -File -Recurse -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1 -ExpandProperty FullName
}
if ([string]::IsNullOrWhiteSpace($XrayPath) -or -not (Test-Path -LiteralPath $XrayPath -PathType Leaf)) {
    throw "Portable Xray runtime not found. Run .\Install-XrayRuntime.ps1 first."
}
$xrayVersionLine = (& $XrayPath version | Select-Object -First 1).Trim()
$baseline = Invoke-DirectBaseline -Url $ProbeUrl -Timeout $TimeoutSeconds
Write-Host "Direct internet baseline: $(if ($baseline.Success) { 'online' } else { 'failed' })"

$tempParent = Join-Path ([System.IO.Path]::GetTempPath()) "VlessConnectivityLab"
New-Item -ItemType Directory -Force -Path $tempParent | Out-Null
$workingDirectory = Join-Path $tempParent ([guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $workingDirectory | Out-Null
$rows = [System.Collections.Generic.List[object]]::new()
try {
    $index = 0
    foreach ($endpoint in $endpoints) {
        foreach ($method in $methods) {
            $index++
            Write-Progress -Activity "VLESS connectivity matrix" -Status "$($endpoint.Label): $($method.Name)" `
                -PercentComplete (($index / $testCount) * 100)
            $rows.Add((Invoke-MethodTest -Executable $XrayPath -Source $source -Endpoint $endpoint `
                -Method $method -AttemptCount $Attempts -Timeout $TimeoutSeconds -Url $ProbeUrl `
                -WorkingDirectory $workingDirectory))
        }
    }
}
finally {
    Write-Progress -Activity "VLESS connectivity matrix" -Completed
    $safeTempParent = [System.IO.Path]::GetFullPath($tempParent).TrimEnd('\') + '\'
    $safeWorkingDirectory = [System.IO.Path]::GetFullPath($workingDirectory)
    if ($safeWorkingDirectory.StartsWith($safeTempParent, [System.StringComparison]::OrdinalIgnoreCase) -and
        (Test-Path -LiteralPath $safeWorkingDirectory)) {
        Remove-Item -LiteralPath $safeWorkingDirectory -Recurse -Force
    }
}

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$outputDirectory = Join-Path $PSScriptRoot "results\$timestamp"
$metadata = [ordered]@{
    generatedAtLocal = [DateTimeOffset]::Now.ToString("o")
    xrayVersion = $xrayVersionLine
    matrix = $Matrix
    methodPattern = if ([string]::IsNullOrWhiteSpace($MethodPattern)) { "all" } else { $MethodPattern }
    transport = $source.Network
    security = $source.Security
    sourceFingerprint = if ([string]::IsNullOrWhiteSpace($source.Fingerprint)) { "core-default" } else { $source.Fingerprint }
    certificateVerificationDisabled = [bool]$source.AllowInsecure
    endpointCount = $endpoints.Count
    methodCount = $methods.Count
    attemptsPerMethod = $Attempts
    probeHost = ([uri]$ProbeUrl).Host
    directBaselineSuccess = $baseline.Success
    directBaselineMs = $baseline.TotalMs
}
$paths = Export-LabReport -Rows @($rows) -Metadata $metadata -OutputDirectory $outputDirectory
$workingSecrets = @($VlessUri, $source.UserId)
$workingSecrets += @($source.OriginalAddress, $source.Sni, $source.Host, $source.Path) |
    Where-Object { -not [string]::IsNullOrWhiteSpace($_) -and $_.Length -ge 6 -and $_ -ne "/" }
foreach ($path in @($paths.Json, $paths.Csv, $paths.Html)) {
    $reportText = Get-Content -LiteralPath $path -Raw
    foreach ($secret in $workingSecrets) {
        if ($reportText.Contains($secret, [System.StringComparison]::OrdinalIgnoreCase)) {
            Remove-Item -LiteralPath $path -Force -ErrorAction SilentlyContinue
            throw "Secret-safety check failed while generating the report"
        }
    }
}

$ranked = @($rows | Where-Object { $_.Successes -gt 0 } |
    Sort-Object @{ Expression = "SuccessRate"; Descending = $true }, MedianMs | Select-Object -First 10)
Write-Host ""
Write-Host "Testing complete. Best successful combinations:"
$ranked | Format-Table Endpoint, Method, SuccessRate, MedianMs, Failure -AutoSize
Write-Host "Redacted HTML report: $($paths.Html)"
Write-Host "Redacted CSV report:  $($paths.Csv)"
Write-Host "Redacted JSON report: $($paths.Json)"
