[CmdletBinding()]
param(
    [ValidateSet('GET', 'POST')][string] $Method = 'GET',
    [string] $Path = '/families',
    [string] $BodyFile,
    [switch] $DryRun,
    [uri] $BaseUri = 'http://127.0.0.1:18081'
)

$ErrorActionPreference = 'Stop'
if ($BaseUri.Scheme -notin @('http', 'https') -or
    $BaseUri.Host -notin @('127.0.0.1', 'localhost', '[::1]', '::1') -or
    $BaseUri.UserInfo -or $BaseUri.AbsolutePath -ne '/' -or $BaseUri.Query -or $BaseUri.Fragment) {
    throw 'BaseUri must be a localhost origin. Use an SSH tunnel to reach the Pi.'
}
if ($Path -notmatch '^/(families|targets|export|batches)([/?]|$)' -or $Path.Contains('#')) {
    throw 'Use a catalogue-relative path such as /families?q=vehicles.'
}
if (-not $env:MODS_API_TOKEN) { throw 'Set MODS_API_TOKEN in this process before using the client.' }
if ($Method -eq 'POST' -and (-not $BodyFile -or $Path -ne '/batches')) {
    throw 'POST requires -Path /batches and -BodyFile pointing to a JSON batch.'
}
if ($Method -eq 'GET' -and ($BodyFile -or $DryRun)) { throw 'BodyFile and DryRun require POST.' }

$taskRequestPath = '/internal/mod-catalogue/v1' + $Path
if ($DryRun) { $taskRequestPath += '?dryRun=true' }
$taskParameters = @{
    Uri = $BaseUri.GetLeftPart([System.UriPartial]::Authority) + $taskRequestPath
    Method = $Method
    Headers = @{ Authorization = 'Bearer ' + $env:MODS_API_TOKEN; Accept = 'application/json' }
    MaximumRedirection = 0
    TimeoutSec = 30
}
if ($BodyFile) {
    $taskBody = [System.IO.File]::ReadAllBytes((Resolve-Path -LiteralPath $BodyFile).Path)
    if ($taskBody.Length -gt 1048576) { throw 'Batch exceeds 1 MiB.' }
    $taskParameters.Body = $taskBody
    $taskParameters.ContentType = 'application/json; charset=utf-8'
}
Invoke-RestMethod @taskParameters | ConvertTo-Json -Depth 50
