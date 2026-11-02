param(
    [ValidateRange(1024, 65535)]
    [int]$Port = 16379,
    [switch]$Check
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$privateDirectory = Join-Path $workspace '.local/redis'
$privateSecrets = Join-Path $workspace '.local/redis-secrets.yml'
$manifestPath = Join-Path $privateDirectory 'setup.json'
$passwordPath = Join-Path $privateDirectory 'password'
$redisConfigPath = Join-Path $privateDirectory 'redis.conf'
$runtimeEnvironment = Join-Path $privateDirectory 'runtime.env'
$composePath = Join-Path $workspace 'deploy/compose.redis-local.yml'
$containerName = 'supportflow-local-redis'
$volumeName = 'supportflow-local-redis-data'
$networkName = 'supportflow-local-redis-network'
$image = 'redis:8.2.11-alpine'
$namespace = 'supportflow:local:session'
$role = 'local-session-redis'
$workspaceBytes = [Text.Encoding]::UTF8.GetBytes($workspace.ToLowerInvariant())
$workspaceId = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($workspaceBytes)).ToLowerInvariant()
$utf8 = [Text.UTF8Encoding]::new($false)
$docker = (Get-Command docker.exe -ErrorAction Stop).Source

function Require-PlainPath([string]$Path, [bool]$Directory) {
    $absolute = [IO.Path]::GetFullPath($Path)
    if (!$absolute.StartsWith($workspace + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Redis setup paths must remain inside the project'
    }
    if (Test-Path -LiteralPath $absolute) {
        $item = Get-Item -LiteralPath $absolute -Force
        if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0 -or $item.PSIsContainer -ne $Directory) {
            throw 'Redis setup refuses symbolic links, junctions and unexpected path types'
        }
    }
}

function Protect-PrivateFile([string]$Path) {
    if ([Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT) {
        $acl = [Security.AccessControl.FileSecurity]::new()
        $acl.SetAccessRuleProtection($true, $false)
        $owner = [Security.Principal.WindowsIdentity]::GetCurrent().User
        $system = [Security.Principal.SecurityIdentifier]::new('S-1-5-18')
        $rights = [Security.AccessControl.FileSystemRights]::FullControl
        $allow = [Security.AccessControl.AccessControlType]::Allow
        $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($owner, $rights, $allow))
        $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($system, $rights, $allow))
        Set-Acl -LiteralPath $Path -AclObject $acl
    }
}

function Write-NewPrivateFile([string]$Path, [string]$Text) {
    $stream = [IO.File]::Open($Path, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
    try {
        $bytes = $utf8.GetBytes($Text)
        $stream.Write($bytes, 0, $bytes.Length)
    } finally { $stream.Dispose() }
    Protect-PrivateFile $Path
}

function Docker-Inspect([string]$Kind, [string]$Name) {
    $output = & $docker inspect --type $Kind --format '{{json .}}' $Name 2>$null
    if ($LASTEXITCODE -ne 0) { return $null }
    return ($output | ConvertFrom-Json)
}

function Require-OwnedDockerResource($Resource) {
    if ($null -eq $Resource) { return }
    $labels = if ($null -ne $Resource.PSObject.Properties['Config']) { $Resource.Config.Labels } else { $Resource.Labels }
    if ($null -eq $labels -or $null -eq $labels.PSObject.Properties['io.supportflow.role'] -or
            $null -eq $labels.PSObject.Properties['io.supportflow.workspace'] -or
            $labels.'io.supportflow.role' -ne $role -or $labels.'io.supportflow.workspace' -ne $workspaceId) {
        throw 'An existing Redis resource belongs to another project; it was preserved'
    }
}

Require-PlainPath (Join-Path $workspace '.local') $true
Require-PlainPath $privateDirectory $true
foreach ($path in @($manifestPath, $passwordPath, $redisConfigPath, $runtimeEnvironment, $privateSecrets)) {
    Require-PlainPath $path $false
}
$existing = Docker-Inspect 'container' $containerName
$volume = Docker-Inspect 'volume' $volumeName
$network = Docker-Inspect 'network' $networkName
Require-OwnedDockerResource $existing
Require-OwnedDockerResource $volume
Require-OwnedDockerResource $network
if ($null -ne $network -and $network.Internal) {
    throw 'The managed Redis network is internal-only and cannot serve the Windows host; existing resources were preserved'
}

if ($null -ne $existing) {
    $bindings = @($existing.HostConfig.PortBindings.'6379/tcp')
    if ($existing.Config.Image -ne $image -or $bindings.Count -ne 1 -or
            $bindings[0].HostIp -ne '127.0.0.1' -or $bindings[0].HostPort -ne [string]$Port -or
            ($existing.Config.Cmd -join ' ') -ne 'redis-server /usr/local/etc/redis/redis.conf') {
        throw 'The managed Redis container has an unexpected image, port or command; it was preserved'
    }
}
if ($null -eq $existing -or !$existing.State.Running) {
    if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) {
        throw 'The requested local Redis port is already occupied; no process was stopped'
    }
}

if (Test-Path -LiteralPath $manifestPath) {
    $manifest = [IO.File]::ReadAllText($manifestPath, $utf8) | ConvertFrom-Json
    if ($manifest.version -ne 1 -or $manifest.owner -ne $role -or $manifest.workspaceId -ne $workspaceId -or
            $manifest.port -ne $Port -or $manifest.image -ne $image) {
        throw 'Existing Redis setup metadata differs from this request; existing files were preserved'
    }
    foreach ($path in @($passwordPath, $redisConfigPath, $runtimeEnvironment, $privateSecrets)) {
        if (!(Test-Path -LiteralPath $path -PathType Leaf)) { throw 'Managed Redis setup is incomplete; existing files were preserved' }
    }
    $password = [IO.File]::ReadAllText($passwordPath, $utf8).Trim()
    if ($password -notmatch '^[a-f0-9]{64}$') { throw 'Managed Redis credential has an unexpected format' }
} else {
    if ($Check) { throw 'Redis has not been initialized; run setup-redis.ps1 without -Check first' }
    if ($null -ne $existing -or $null -ne $volume -or $null -ne $network -or
            @($passwordPath, $redisConfigPath, $runtimeEnvironment, $privateSecrets).Where({ Test-Path -LiteralPath $_ }).Count -ne 0) {
        throw 'Unowned or incomplete Redis setup exists; no existing file or resource was changed'
    }
    $password = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)).ToLowerInvariant()
}

$expectedConfig = @"
protected-mode yes
port 6379
requirepass $password
dir /data
appendonly yes
appendfsync everysec
save ""
maxmemory 192mb
maxmemory-policy noeviction
maxclients 256
tcp-keepalive 60
loglevel warning
logfile ""
"@ + "`n"
$expectedSecrets = @"
# Generated by scripts/setup-redis.ps1; this file must remain outside Git.
SUPPORTFLOW_REDIS_SESSION_ENABLED: true
SUPPORTFLOW_REDIS_HOST: "127.0.0.1"
SUPPORTFLOW_REDIS_PORT: $Port
SUPPORTFLOW_REDIS_PASSWORD: "$password"
SUPPORTFLOW_REDIS_NAMESPACE: "$namespace"
"@ + "`n"
$expectedEnvironment = "SUPPORTFLOW_LOCAL_REDIS_PORT=$Port`nSUPPORTFLOW_REDIS_WORKSPACE_ID=$workspaceId`n"
if (Test-Path -LiteralPath $manifestPath) {
    $expectedFiles = @{
        $redisConfigPath = $expectedConfig
        $privateSecrets = $expectedSecrets
        $runtimeEnvironment = $expectedEnvironment
    }
    foreach ($path in $expectedFiles.Keys) {
        if ([IO.File]::ReadAllText($path, $utf8).Replace("`r`n", "`n") -ne $expectedFiles[$path].Replace("`r`n", "`n")) {
            throw 'Managed Redis configuration was edited; the script will not overwrite it'
        }
    }
} else {
    New-Item -ItemType Directory -Path $privateDirectory -Force | Out-Null
    Write-NewPrivateFile $passwordPath ($password + "`n")
    Write-NewPrivateFile $redisConfigPath $expectedConfig
    Write-NewPrivateFile $runtimeEnvironment $expectedEnvironment
    Write-NewPrivateFile $privateSecrets $expectedSecrets
    $metadata = @{ version = 1; owner = $role; workspaceId = $workspaceId; port = $Port; image = $image }
    Write-NewPrivateFile $manifestPath (($metadata | ConvertTo-Json -Compress) + "`n")
}

if (!$Check) {
    & $docker compose --env-file $runtimeEnvironment --file $composePath up --detach --no-recreate --wait --wait-timeout 60 redis
    if ($LASTEXITCODE -ne 0) { throw 'Redis did not become ready; all generated files and Docker data were preserved' }
}
$ready = Docker-Inspect 'container' $containerName
Require-OwnedDockerResource $ready
if ($null -eq $ready -or !$ready.State.Running) { throw 'The managed Redis container is not running' }
$ping = & $docker exec $containerName sh -c 'REDISCLI_AUTH="$(cat /run/secrets/supportflow_redis_password)" redis-cli --no-auth-warning ping' 2>$null
if ($LASTEXITCODE -ne 0 -or ($ping -join '').Trim() -ne 'PONG') { throw 'Authenticated Redis readiness check failed' }
$anonymous = & $docker exec $containerName redis-cli ping 2>$null
if (($anonymous -join '') -notmatch 'NOAUTH') { throw 'Redis unexpectedly accepted an unauthenticated command' }
Write-Output "REDIS_SESSION_READY host=127.0.0.1 port=$Port container=$containerName image=$image"
Write-Output "REDIS_SESSION_SECRETS_FILE=$privateSecrets"
Write-Output 'Redis password is stored only in ignored private files; no existing data was removed.'
