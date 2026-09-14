[CmdletBinding()]
param(
    [string]$Version = "v26.6.1",
    [switch]$Force
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if (-not $Version.StartsWith("v", [System.StringComparison]::OrdinalIgnoreCase)) {
    $Version = "v$Version"
}

$runtimeRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot "runtime"))
$installDirectory = [System.IO.Path]::GetFullPath((Join-Path $runtimeRoot "xray-$Version"))
$xrayPath = Join-Path $installDirectory "xray.exe"

function Assert-ChildPath {
    param(
        [Parameter(Mandatory)] [string]$Child,
        [Parameter(Mandatory)] [string]$Parent
    )

    $fullChild = [System.IO.Path]::GetFullPath($Child)
    $fullParent = [System.IO.Path]::GetFullPath($Parent).TrimEnd('\') + '\'
    if (-not $fullChild.StartsWith($fullParent, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to modify a path outside $Parent"
    }
}

if ((Test-Path -LiteralPath $xrayPath) -and -not $Force) {
    Write-Host "Xray $Version is already available at $xrayPath"
    & $xrayPath version | Select-Object -First 1
    return
}

New-Item -ItemType Directory -Force -Path $runtimeRoot | Out-Null
Assert-ChildPath -Child $installDirectory -Parent $runtimeRoot

if ($Force -and (Test-Path -LiteralPath $installDirectory)) {
    Remove-Item -LiteralPath $installDirectory -Recurse -Force
}

$tempParent = Join-Path ([System.IO.Path]::GetTempPath()) "VlessConnectivityLab"
New-Item -ItemType Directory -Force -Path $tempParent | Out-Null
$downloadDirectory = Join-Path $tempParent ([guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $downloadDirectory | Out-Null

try {
    $headers = @{ "User-Agent" = "v2rayNG-Auto-connectivity-lab" }
    $releaseUri = "https://api.github.com/repos/XTLS/Xray-core/releases/tags/$Version"
    Write-Host "Reading release metadata for Xray $Version from GitHub..."
    $release = Invoke-RestMethod -Uri $releaseUri -Headers $headers
    $asset = $release.assets | Where-Object { $_.name -eq "Xray-windows-64.zip" } | Select-Object -First 1
    if ($null -eq $asset) {
        throw "The GitHub release does not contain Xray-windows-64.zip"
    }

    $archivePath = Join-Path $downloadDirectory $asset.name
    Write-Host "Downloading the portable Windows Xray runtime ($([math]::Round($asset.size / 1MB, 1)) MB)..."
    Invoke-WebRequest -Uri $asset.browser_download_url -Headers $headers -OutFile $archivePath

    $expectedHash = $null
    if ($asset.PSObject.Properties.Name -contains "digest" -and $asset.digest -match '^sha256:([0-9a-fA-F]{64})$') {
        $expectedHash = $Matches[1]
    }
    if ([string]::IsNullOrWhiteSpace($expectedHash)) {
        $digestAsset = $release.assets | Where-Object { $_.name -eq "Xray-windows-64.zip.dgst" } | Select-Object -First 1
        if ($null -eq $digestAsset) {
            throw "No SHA-256 digest was published for the Windows Xray archive"
        }
        $digestPath = Join-Path $downloadDirectory $digestAsset.name
        Invoke-WebRequest -Uri $digestAsset.browser_download_url -Headers $headers -OutFile $digestPath
        $digestText = Get-Content -LiteralPath $digestPath -Raw
        if ($digestText -notmatch '(?im)^SHA2-256=\s*([0-9a-f]{64})\s*$' -and
            $digestText -notmatch '(?im)^SHA256.*?=\s*([0-9a-f]{64})\s*$') {
            throw "The published digest file could not be parsed"
        }
        $expectedHash = $Matches[1]
    }

    $actualHash = (Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash
    if (-not $actualHash.Equals($expectedHash, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Xray archive checksum mismatch"
    }

    New-Item -ItemType Directory -Force -Path $installDirectory | Out-Null
    Expand-Archive -LiteralPath $archivePath -DestinationPath $installDirectory -Force
    if (-not (Test-Path -LiteralPath $xrayPath)) {
        $nestedExecutable = Get-ChildItem -LiteralPath $installDirectory -Filter "xray.exe" -File -Recurse |
            Select-Object -First 1
        if ($null -eq $nestedExecutable) {
            throw "xray.exe was not found in the verified archive"
        }
        Copy-Item -LiteralPath $nestedExecutable.FullName -Destination $xrayPath
    }

    $metadata = [ordered]@{
        version = $Version
        source = $asset.browser_download_url
        sha256 = $actualHash.ToLowerInvariant()
        installedAtUtc = [DateTimeOffset]::UtcNow.ToString("o")
    }
    $metadata | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $installDirectory "install-metadata.json") -Encoding utf8

    Write-Host "Verified portable runtime installed at $xrayPath"
    & $xrayPath version | Select-Object -First 1
}
finally {
    $safeTempParent = [System.IO.Path]::GetFullPath($tempParent).TrimEnd('\') + '\'
    $safeDownloadDirectory = [System.IO.Path]::GetFullPath($downloadDirectory)
    if ($safeDownloadDirectory.StartsWith($safeTempParent, [System.StringComparison]::OrdinalIgnoreCase) -and
        (Test-Path -LiteralPath $safeDownloadDirectory)) {
        Remove-Item -LiteralPath $safeDownloadDirectory -Recurse -Force
    }
}
